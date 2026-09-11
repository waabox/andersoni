package org.waabox.andersoni.it;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
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
import org.waabox.andersoni.AndersoniStatus;
import org.waabox.andersoni.Catalog;
import org.waabox.andersoni.DataLoader;
import org.waabox.andersoni.ReconciliationPolicy;
import org.waabox.andersoni.RetryPolicy;
import org.waabox.andersoni.Snapshot;
import org.waabox.andersoni.snapshot.fs.FileSystemSnapshotStore;
import org.waabox.andersoni.sync.kafka.KafkaSyncConfig;
import org.waabox.andersoni.sync.kafka.KafkaSyncStrategy;

/**
 * A minimal, self-contained Andersoni node used by the cluster integration
 * test. It is packaged into a Docker image and run as N replicas.
 *
 * <p>Each node:
 * <ul>
 *   <li>loads an {@code items} catalog from a shared PostgreSQL database
 *       (the DataLoader), unless {@code LOADER_MODE=fail} simulates a
 *       permanent data source outage;</li>
 *   <li>synchronizes via raw Kafka ({@link KafkaSyncStrategy}), wrapped so
 *       incoming events can be dropped on demand
 *       ({@link FaultInjectingSyncStrategy});</li>
 *   <li>has a controllable leadership role, initially driven by the
 *       {@code LEADER} environment variable and changeable afterwards
 *       ({@link ControllableLeaderElection});</li>
 *   <li>optionally persists snapshots to a shared filesystem store that can
 *       be made to fail saves on demand
 *       ({@link FaultInjectingSnapshotStore});</li>
 *   <li>exposes a tiny HTTP API to trigger refreshes, inject faults, and
 *       observe state.</li>
 * </ul>
 *
 * <p>HTTP API:
 * <ul>
 *   <li>{@code GET  /health} — liveness, always {@code 200}.</li>
 *   <li>{@code GET  /state}  — JSON: {@code nodeId, leader, version, hash,
 *       itemCount, syncState, available, droppedEvents}.</li>
 *   <li>{@code POST /refresh} — calls {@link Andersoni#refreshAndSync};
 *       responds {@code 500} with the exception message on failure.</li>
 *   <li>{@code POST /reconcile} — calls {@link Andersoni#reconcile}.</li>
 *   <li>{@code POST /leader?value=true|false} — flips this node's
 *       leadership.</li>
 *   <li>{@code POST /fault/store?failSave=true|false} — makes the snapshot
 *       store fail (or stop failing) saves; {@code 400} if no store is
 *       configured.</li>
 *   <li>{@code POST /fault/sync?dropIncoming=true|false} — makes the sync
 *       strategy drop (or stop dropping) incoming refresh events.</li>
 * </ul>
 *
 * <p>Configuration is read from environment variables: {@code NODE_ID},
 * {@code LEADER}, {@code KAFKA_BOOTSTRAP}, {@code KAFKA_TOPIC},
 * {@code JDBC_URL}, {@code JDBC_USER}, {@code JDBC_PASSWORD}, {@code HTTP_PORT},
 * and, optionally, {@code SNAPSHOT_DIR}, {@code RECONCILE_INTERVAL_MS}, and
 * {@code LOADER_MODE} to enable snapshot persistence, reconciliation, and a
 * simulated data source outage.
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
    final String snapshotDir = optionalEnv("SNAPSHOT_DIR");
    final String reconcileIntervalMs = optionalEnv("RECONCILE_INTERVAL_MS");
    final boolean failLoader = "fail".equals(optionalEnv("LOADER_MODE"));

    LOG.info("Starting node '{}' (leader={})", nodeId, leader);

    awaitDatabase(jdbcUrl, jdbcUser, jdbcPassword);

    final DataLoader<Item> loader = failLoader
        ? () -> {
          throw new IllegalStateException("simulated data source outage");
        }
        : () -> loadItems(jdbcUrl, jdbcUser, jdbcPassword);

    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named(CATALOG)
        .loadWith(loader)
        .index("by-name").by(Item::name, Function.identity())
        .serializer(new ItemSerializer())
        .build();

    final KafkaSyncConfig kafkaConfig = KafkaSyncConfig.builder()
        .bootstrapServers(bootstrap)
        .topic(topic)
        .nodeId(nodeId)
        .build();
    final ControllableLeaderElection election = new ControllableLeaderElection(leader);
    final FaultInjectingSyncStrategy sync =
        new FaultInjectingSyncStrategy(new KafkaSyncStrategy(kafkaConfig));
    final FaultInjectingSnapshotStore store = snapshotDir != null
        ? new FaultInjectingSnapshotStore(new FileSystemSnapshotStore(Paths.get(snapshotDir)))
        : null;

    final Andersoni.Builder builder = Andersoni.builder()
        .nodeId(nodeId)
        .syncStrategy(sync)
        .leaderElection(election)
        .retryPolicy(RetryPolicy.of(1, Duration.ofMillis(300)));
    if (store != null) {
      builder.snapshotStore(store);
    }
    if (reconcileIntervalMs != null) {
      builder.reconciliation(
          ReconciliationPolicy.of(Duration.ofMillis(Long.parseLong(reconcileIntervalMs))));
    }
    final Andersoni andersoni = builder.build();

    andersoni.register(catalog);
    andersoni.start();

    startHttpServer(httpPort, nodeId, andersoni, catalog, election, sync, store);

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
   * @param andersoni the Andersoni instance, never null.
   * @param catalog   the items catalog, never null.
   * @param election  the controllable leader election, never null.
   * @param sync      the fault-injecting sync strategy, never null.
   * @param store     the fault-injecting snapshot store, or {@code null} if
   *                  no snapshot store is configured.
   * @throws IOException if the server cannot bind.
   */
  private static void startHttpServer(final int port, final String nodeId,
      final Andersoni andersoni, final Catalog<Item> catalog,
      final ControllableLeaderElection election, final FaultInjectingSyncStrategy sync,
      final FaultInjectingSnapshotStore store) throws IOException {
    final HttpServer server = HttpServer.create(
        new InetSocketAddress(port), 0);

    server.createContext("/health", exchange ->
        respond(exchange, 200, "ok"));

    server.createContext("/state", exchange -> {
      final Snapshot<Item> snapshot = catalog.currentSnapshot();
      final AndersoniStatus status = andersoni.status();
      final AndersoniStatus.CatalogStatus catalogStatus = status.catalogs().stream()
          .filter(c -> c.catalogName().equals(CATALOG))
          .findFirst()
          .orElse(null);
      final JSONObject json = new JSONObject();
      json.put("nodeId", nodeId);
      json.put("leader", status.leader());
      json.put("version", snapshot.version());
      json.put("hash", snapshot.hash());
      json.put("itemCount", snapshot.data().size());
      json.put("syncState", catalogStatus != null
          ? catalogStatus.syncState().name() : "UNKNOWN");
      json.put("available", catalogStatus != null && catalogStatus.available());
      json.put("droppedEvents", sync.droppedEvents());
      respond(exchange, 200, json.toString());
    });

    server.createContext("/refresh", exchange -> {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        respond(exchange, 405, "{\"error\":\"POST only\"}");
        return;
      }
      try {
        andersoni.refreshAndSync(CATALOG);
      } catch (final RuntimeException e) {
        respond(exchange, 500, new JSONObject().put("error", String.valueOf(e.getMessage()))
            .toString());
        return;
      }
      respond(exchange, 200, "{\"status\":\"ok\"}");
    });

    server.createContext("/reconcile", exchange -> {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        respond(exchange, 405, "{\"error\":\"POST only\"}");
        return;
      }
      andersoni.reconcile();
      respond(exchange, 200, "{\"status\":\"ok\"}");
    });

    server.createContext("/leader", exchange -> {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        respond(exchange, 405, "{\"error\":\"POST only\"}");
        return;
      }
      final boolean value = Boolean.parseBoolean(queryParam(exchange, "value"));
      election.become(value);
      respond(exchange, 200, new JSONObject().put("leader", value).toString());
    });

    server.createContext("/fault/store", exchange -> {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        respond(exchange, 405, "{\"error\":\"POST only\"}");
        return;
      }
      if (store == null) {
        respond(exchange, 400, "{\"error\":\"no snapshot store\"}");
        return;
      }
      final boolean failSave = Boolean.parseBoolean(queryParam(exchange, "failSave"));
      store.failSave(failSave);
      respond(exchange, 200, new JSONObject().put("failSave", failSave).toString());
    });

    server.createContext("/fault/sync", exchange -> {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        respond(exchange, 405, "{\"error\":\"POST only\"}");
        return;
      }
      final boolean dropIncoming = Boolean.parseBoolean(queryParam(exchange, "dropIncoming"));
      sync.dropIncoming(dropIncoming);
      respond(exchange, 200, new JSONObject().put("dropIncoming", dropIncoming).toString());
    });

    server.setExecutor(null);
    server.start();
  }

  /**
   * Extracts a single query parameter's value from an exchange's request URI.
   *
   * @param exchange the exchange, never null.
   * @param name     the parameter name, never null.
   * @return the parameter value, or {@code null} if absent.
   */
  private static String queryParam(final HttpExchange exchange, final String name) {
    final String query = exchange.getRequestURI().getQuery();
    if (query == null) {
      return null;
    }
    for (final String pair : query.split("&")) {
      final int separator = pair.indexOf('=');
      if (separator < 0) {
        continue;
      }
      if (pair.substring(0, separator).equals(name)) {
        return pair.substring(separator + 1);
      }
    }
    return null;
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
   * Returns the value of an optional environment variable, or {@code null}
   * if it is unset or blank.
   *
   * @param name the variable name, never null.
   * @return the resolved value, or {@code null} if unset.
   */
  private static String optionalEnv(final String name) {
    final String value = System.getenv(name);
    return value != null && !value.isBlank() ? value : null;
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
