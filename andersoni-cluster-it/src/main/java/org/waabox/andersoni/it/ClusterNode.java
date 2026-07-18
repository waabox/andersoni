package org.waabox.andersoni.it;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.Catalog;
import org.waabox.andersoni.RetryPolicy;
import org.waabox.andersoni.Snapshot;
import org.waabox.andersoni.sync.kafka.KafkaSyncConfig;
import org.waabox.andersoni.sync.kafka.KafkaSyncStrategy;

/**
 * A minimal, self-contained Andersoni node used by the cluster integration
 * test. It is packaged into a Docker image and run as N replicas.
 *
 * <p>Each node:
 * <ul>
 *   <li>loads an {@code items} catalog from a shared PostgreSQL database
 *       (the DataLoader);</li>
 *   <li>synchronizes via raw Kafka ({@link KafkaSyncStrategy});</li>
 *   <li>has a fixed leadership role via {@link StaticLeaderElection}, driven
 *       by the {@code LEADER} environment variable;</li>
 *   <li>exposes a tiny HTTP API to trigger refreshes and observe state.</li>
 * </ul>
 *
 * <p>HTTP API:
 * <ul>
 *   <li>{@code GET  /health} — liveness, always {@code 200}.</li>
 *   <li>{@code GET  /state}  — JSON: {@code nodeId, leader, version, hash,
 *       itemCount}.</li>
 *   <li>{@code POST /refresh} — calls {@link Andersoni#refreshAndSync}.</li>
 * </ul>
 *
 * <p>Configuration is read from environment variables: {@code NODE_ID},
 * {@code LEADER}, {@code KAFKA_BOOTSTRAP}, {@code KAFKA_TOPIC},
 * {@code JDBC_URL}, {@code JDBC_USER}, {@code JDBC_PASSWORD}, {@code HTTP_PORT}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class ClusterNode {

  /** The class logger. */
  private static final Logger LOG = LoggerFactory.getLogger(ClusterNode.class);

  /** The catalog name shared across the cluster. */
  private static final String CATALOG = "items";

  /** Private constructor: this is an application entry point. */
  private ClusterNode() {
  }

  /**
   * Boots the node and blocks forever serving the HTTP API.
   *
   * @param args ignored.
   * @throws Exception if the node cannot start.
   */
  public static void main(final String[] args) throws Exception {
    final String nodeId = env("NODE_ID", "node");
    final boolean leader = Boolean.parseBoolean(env("LEADER", "false"));
    final String bootstrap = env("KAFKA_BOOTSTRAP", "localhost:9092");
    final String topic = env("KAFKA_TOPIC", "andersoni-it");
    final String jdbcUrl = env("JDBC_URL", "jdbc:postgresql://localhost/it");
    final String jdbcUser = env("JDBC_USER", "test");
    final String jdbcPassword = env("JDBC_PASSWORD", "test");
    final int httpPort = Integer.parseInt(env("HTTP_PORT", "8080"));

    LOG.info("Starting node '{}' (leader={})", nodeId, leader);

    awaitDatabase(jdbcUrl, jdbcUser, jdbcPassword);

    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named(CATALOG)
        .loadWith(() -> loadItems(jdbcUrl, jdbcUser, jdbcPassword))
        .index("by-name").by(Item::name, Function.identity())
        .build();

    final KafkaSyncConfig kafkaConfig = KafkaSyncConfig.builder()
        .bootstrapServers(bootstrap)
        .topic(topic)
        .nodeId(nodeId)
        .build();
    final KafkaSyncStrategy sync = new KafkaSyncStrategy(kafkaConfig);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId(nodeId)
        .syncStrategy(sync)
        .leaderElection(new StaticLeaderElection(leader))
        .retryPolicy(RetryPolicy.of(1, Duration.ofMillis(300)))
        .build();

    andersoni.register(catalog);
    andersoni.start();

    startHttpServer(httpPort, nodeId, leader, andersoni, catalog);

    LOG.info("Node '{}' ready on port {}", nodeId, httpPort);
    Thread.currentThread().join();
  }

  /**
   * Blocks until the database accepts connections, up to a fixed budget.
   *
   * @param url      the JDBC url, never null.
   * @param user     the database user, never null.
   * @param password the database password, never null.
   * @throws IllegalStateException if the database never becomes reachable.
   */
  private static void awaitDatabase(final String url, final String user,
      final String password) {
    final long deadline = System.nanoTime()
        + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      try (Connection ignored =
          DriverManager.getConnection(url, user, password)) {
        return;
      } catch (final Exception e) {
        sleep(500);
      }
    }
    throw new IllegalStateException("Database not reachable: " + url);
  }

  /**
   * Loads all items from the shared database, ordered by id for a stable
   * content hash across nodes.
   *
   * @param url      the JDBC url, never null.
   * @param user     the database user, never null.
   * @param password the database password, never null.
   * @return the loaded items, never null.
   */
  private static List<Item> loadItems(final String url, final String user,
      final String password) {
    final List<Item> items = new ArrayList<>();
    final String sql = "SELECT id, name FROM items ORDER BY id";
    try (Connection conn = DriverManager.getConnection(url, user, password);
        PreparedStatement ps = conn.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        items.add(new Item(rs.getInt("id"), rs.getString("name")));
      }
    } catch (final Exception e) {
      throw new IllegalStateException("Failed to load items", e);
    }
    return items;
  }

  /**
   * Starts the HTTP server exposing the node's control and inspection API.
   *
   * @param port      the port to listen on.
   * @param nodeId    this node's id, never null.
   * @param leader    whether this node is the leader.
   * @param andersoni the Andersoni instance, never null.
   * @param catalog   the items catalog, never null.
   * @throws IOException if the server cannot bind.
   */
  private static void startHttpServer(final int port, final String nodeId,
      final boolean leader, final Andersoni andersoni,
      final Catalog<Item> catalog) throws IOException {
    final HttpServer server = HttpServer.create(
        new InetSocketAddress(port), 0);

    server.createContext("/health", exchange ->
        respond(exchange, 200, "ok"));

    server.createContext("/state", exchange -> {
      final Snapshot<Item> snapshot = catalog.currentSnapshot();
      final JSONObject json = new JSONObject();
      json.put("nodeId", nodeId);
      json.put("leader", leader);
      json.put("version", snapshot.version());
      json.put("hash", snapshot.hash());
      json.put("itemCount", snapshot.data().size());
      respond(exchange, 200, json.toString());
    });

    server.createContext("/refresh", exchange -> {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        respond(exchange, 405, "{\"error\":\"POST only\"}");
        return;
      }
      andersoni.refreshAndSync(CATALOG);
      respond(exchange, 200, "{\"status\":\"ok\"}");
    });

    server.setExecutor(null);
    server.start();
  }

  /**
   * Writes an HTTP response and closes the exchange.
   *
   * @param exchange the exchange, never null.
   * @param status   the HTTP status code.
   * @param body     the response body, never null.
   * @throws IOException if writing fails.
   */
  private static void respond(final HttpExchange exchange, final int status,
      final String body) throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  /**
   * Returns the value of an environment variable or a default.
   *
   * @param name         the variable name, never null.
   * @param defaultValue the fallback value, never null.
   * @return the resolved value, never null.
   */
  private static String env(final String name, final String defaultValue) {
    final String value = System.getenv(name);
    return value != null && !value.isBlank() ? value : defaultValue;
  }

  /**
   * Sleeps uninterruptibly for the given number of milliseconds.
   *
   * @param millis the sleep duration in milliseconds.
   */
  private static void sleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
