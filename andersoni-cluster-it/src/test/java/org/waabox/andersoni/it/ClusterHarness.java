package org.waabox.andersoni.it;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;


/**
 * Boots a Kafka broker, a PostgreSQL database, and any number of Andersoni
 * node containers on a shared Docker network, for {@link ClusterSelfHealingIT}.
 *
 * <p>Consolidates the container wiring and polling helpers that would
 * otherwise be duplicated across self-healing scenarios; the two other
 * integration tests in this module keep their own copies and are left
 * untouched.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
final class ClusterHarness implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ClusterHarness.class);
  private static final String KAFKA_INTERNAL = "kafka:19092";
  private static final String TOPIC = "andersoni-selfheal-it";
  private static final String CATALOG = "items";

  /** The Docker network shared by Kafka, PostgreSQL, and every node. */
  private final Network network;

  /** The shared Kafka broker. */
  private final KafkaContainer kafka;

  /** The shared PostgreSQL database. */
  private final PostgreSQLContainer<?> postgres;

  /** The node image, built once and reused for every started node. */
  private final Future<String> image;

  /** The host directory bind-mounted into every node as its snapshot store. */
  private final Path snapshotDir;

  /** HTTP client used to talk to node containers. */
  private final HttpClient http;

  /** The nodes started so far, in start order. */
  private final List<GenericContainer<?>> nodes = new ArrayList<>();

  /**
   * Creates the harness's containers without starting them.
   *
   * @throws Exception if the snapshot directory cannot be created.
   */
  ClusterHarness() throws Exception {
    network = Network.newNetwork();
    kafka = new KafkaContainer("apache/kafka:3.8.1")
        .withNetwork(network)
        .withNetworkAliases("kafka")
        .withListener(KAFKA_INTERNAL);
    postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withNetwork(network)
        .withNetworkAliases("postgres")
        .withDatabaseName("andersoni")
        .withUsername("test")
        .withPassword("test");
    image = new ImageFromDockerfile("andersoni-cluster-node:it", false)
        .withFileFromPath("Dockerfile", Paths.get("Dockerfile"))
        .withFileFromPath("app.jar", Paths.get("target/app.jar"));
    snapshotDir = Paths.get("target", "it-snapshots-" + UUID.randomUUID()).toAbsolutePath();
    Files.createDirectories(snapshotDir);
    http = HttpClient.newHttpClient();
  }

  /**
   * Starts Kafka and PostgreSQL and initializes the shared schema.
   *
   * @throws Exception if either container fails to start or seeding fails.
   */
  void start() throws Exception {
    kafka.start();
    postgres.start();
    initSchemaAndSeed();
  }

  /**
   * Starts a node container and waits for it to become healthy.
   *
   * @param nodeId   this node's id, never null.
   * @param leader   whether the node starts out as the leader.
   * @param extraEnv environment overrides applied on top of the base
   *                 configuration (e.g. {@code RECONCILE_INTERVAL_MS} or
   *                 {@code LOADER_MODE}), never null.
   * @return the started node, never null.
   * @throws Exception if the node fails to become healthy in time.
   */
  GenericContainer<?> startNode(final String nodeId, final boolean leader,
      final Map<String, String> extraEnv) throws Exception {
    final Map<String, String> env = new LinkedHashMap<>();
    env.put("NODE_ID", nodeId);
    env.put("LEADER", Boolean.toString(leader));
    env.put("KAFKA_BOOTSTRAP", KAFKA_INTERNAL);
    env.put("KAFKA_TOPIC", TOPIC);
    env.put("JDBC_URL", "jdbc:postgresql://postgres:5432/andersoni");
    env.put("JDBC_USER", "test");
    env.put("JDBC_PASSWORD", "test");
    env.put("HTTP_PORT", "8080");
    env.put("SNAPSHOT_DIR", "/snapshots");
    env.put("RECONCILE_INTERVAL_MS", "2000");
    env.putAll(extraEnv);

    final GenericContainer<?> node = new GenericContainer<>(image)
        .withNetwork(network)
        .withNetworkAliases(nodeId)
        .dependsOn(kafka, postgres)
        .withFileSystemBind(snapshotDir.toString(), "/snapshots", BindMode.READ_WRITE)
        .withExposedPorts(8080)
        .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix(nodeId))
        .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))
        .withStartupTimeout(Duration.ofSeconds(150));
    env.forEach(node::withEnv);

    node.start();
    nodes.add(node);
    return node;
  }

  /**
   * Creates the {@code items} table and seeds three rows using the host-mapped
   * JDBC endpoint.
   *
   * @throws Exception if the database cannot be initialized.
   */
  private void initSchemaAndSeed() throws Exception {
    try (Connection conn = hostConnection();
        Statement st = conn.createStatement()) {
      st.execute("CREATE TABLE items (id INT PRIMARY KEY, name VARCHAR(64))");
      st.execute("INSERT INTO items (id, name) VALUES "
          + "(1, 'item-1'), (2, 'item-2'), (3, 'item-3')");
    }
  }

  /**
   * Inserts a single item using the host-mapped JDBC endpoint.
   *
   * @param id   the item id.
   * @param name the item name, never null.
   * @throws Exception if the insert fails.
   */
  void insertItem(final int id, final String name) throws Exception {
    try (Connection conn = hostConnection();
        Statement st = conn.createStatement()) {
      st.execute("INSERT INTO items (id, name) VALUES (" + id + ", '" + name + "')");
    }
  }

  /**
   * Opens a JDBC connection to the host-mapped database port.
   *
   * @return a new connection, never null.
   * @throws Exception if the connection cannot be opened.
   */
  private Connection hostConnection() throws Exception {
    return DriverManager.getConnection(postgres.getJdbcUrl(),
        postgres.getUsername(), postgres.getPassword());
  }

  /**
   * Fetches and parses a node's {@code /state} endpoint.
   *
   * @param node the node container, never null.
   * @return the parsed state, never null.
   * @throws Exception if the request fails.
   */
  JSONObject state(final GenericContainer<?> node) throws Exception {
    final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl(node) + "/state"))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();
    final HttpResponse<String> response =
        http.send(request, HttpResponse.BodyHandlers.ofString());
    return new JSONObject(response.body());
  }

  /**
   * Sends a POST request to a node path.
   *
   * @param node the node container, never null.
   * @param path the request path, including any query string, never null.
   * @return the HTTP status code of the response.
   * @throws Exception if the request fails.
   */
  int post(final GenericContainer<?> node, final String path) throws Exception {
    final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl(node) + path))
        .timeout(Duration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.noBody())
        .build();
    final HttpResponse<String> response =
        http.send(request, HttpResponse.BodyHandlers.ofString());
    return response.statusCode();
  }

  /**
   * Returns the host-mapped base URL for a node's HTTP API.
   *
   * @param node the node container, never null.
   * @return the base URL, never null.
   */
  private String baseUrl(final GenericContainer<?> node) {
    return "http://" + node.getHost() + ":" + node.getMappedPort(8080);
  }

  /**
   * Returns a node's current snapshot hash.
   *
   * @param node the node container, never null.
   * @return the snapshot hash, never null.
   * @throws Exception if the node cannot be queried.
   */
  String hashOf(final GenericContainer<?> node) throws Exception {
    return state(node).getString("hash");
  }

  /**
   * Returns whether every node this harness started reports the expected item
   * count and shares a single snapshot hash.
   *
   * @param expected the expected item count.
   * @return {@code true} if the cluster has converged.
   */
  boolean allConverged(final int expected) {
    return allConverged(nodes, expected);
  }

  /**
   * Returns whether every given node reports the expected item count and
   * shares a single snapshot hash.
   *
   * @param targetNodes the nodes to check, never null.
   * @param expected    the expected item count.
   * @return {@code true} if the given nodes have converged.
   */
  boolean allConverged(final List<GenericContainer<?>> targetNodes, final int expected) {
    final Set<String> hashes = new HashSet<>();
    for (final GenericContainer<?> node : targetNodes) {
      final JSONObject state;
      try {
        state = state(node);
      } catch (final Exception e) {
        return false;
      }
      if (state.getInt("itemCount") != expected) {
        return false;
      }
      hashes.add(state.getString("hash"));
    }
    return hashes.size() == 1;
  }

  /**
   * Returns whether every given node reports {@code syncState == "IN_SYNC"}.
   *
   * @param targetNodes the nodes to check, never null.
   * @return {@code true} if every node is in sync.
   */
  boolean allInSync(final List<GenericContainer<?>> targetNodes) {
    return targetNodes.stream().allMatch(node -> {
      try {
        return "IN_SYNC".equals(state(node).getString("syncState"));
      } catch (final Exception e) {
        return false;
      }
    });
  }

  /**
   * Returns the current snapshot versions of the given nodes, in order.
   *
   * @param targetNodes the nodes to query, never null.
   * @return the versions, never null.
   * @throws Exception if a node cannot be queried.
   */
  List<Long> versionsOf(final List<GenericContainer<?>> targetNodes) throws Exception {
    final List<Long> versions = new ArrayList<>();
    for (final GenericContainer<?> node : targetNodes) {
      versions.add(state(node).getLong("version"));
    }
    return versions;
  }

  /**
   * Retries a leader refresh until every node this harness started converges
   * to the expected item count. Tolerates the {@code latest} offset reset
   * while consumer groups are still joining by re-publishing, and warms up
   * the leader's Kafka producer (topic metadata) in the process.
   *
   * @param leaderNode the leader node to refresh, never null.
   * @param expected   the expected item count on every node.
   * @throws Exception if convergence is not reached within the budget.
   */
  void warmUpLeaderRefresh(final GenericContainer<?> leaderNode, final int expected)
      throws Exception {
    final long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      post(leaderNode, "/refresh");
      if (awaitQuietly(Duration.ofSeconds(10), () -> allConverged(expected))) {
        return;
      }
      LOG.info("Warm-up not converged yet; re-publishing leader refresh");
    }
    throw new AssertionError("Warm-up never converged to " + expected
        + " items across the cluster");
  }

  /**
   * Polls a condition until it holds or the timeout elapses, throwing on
   * timeout.
   *
   * @param timeout   the maximum time to wait, never null.
   * @param condition the condition to satisfy, never null.
   * @throws InterruptedException if interrupted while waiting.
   */
  void await(final Duration timeout, final BooleanSupplier condition)
      throws InterruptedException {
    if (!awaitQuietly(timeout, condition)) {
      throw new AssertionError(
          "Condition not met within " + timeout.toSeconds() + "s");
    }
  }

  /**
   * Polls a condition until it holds or the timeout elapses.
   *
   * @param timeout   the maximum time to wait, never null.
   * @param condition the condition to satisfy, never null.
   * @return {@code true} if the condition held before the timeout.
   * @throws InterruptedException if interrupted while waiting.
   */
  boolean awaitQuietly(final Duration timeout, final BooleanSupplier condition)
      throws InterruptedException {
    final long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.sleep(500);
    }
    return condition.getAsBoolean();
  }

  /**
   * Returns the shared Kafka container.
   *
   * @return the Kafka container, never null.
   */
  KafkaContainer kafka() {
    return kafka;
  }

  /**
   * Returns the snapshot store's current hash for the catalog as seen by the
   * given node.
   *
   * <p>Read through the node on purpose: on Linux the container writes the
   * bind-mounted snapshot as root, so the test process cannot open it.
   *
   * @param node the node to ask, never null.
   * @return the store hash, or empty when the store holds no snapshot.
   * @throws Exception if the node cannot be reached.
   */
  Optional<String> storeHash(final GenericContainer<?> node) throws Exception {
    final JSONObject state = state(node);
    return state.isNull("storeHash")
        ? Optional.empty()
        : Optional.of(state.getString("storeHash"));
  }

  /**
   * Re-publishes a leader refresh every two seconds until the condition
   * holds. Used when a scenario needs a follower's Kafka consumer to have
   * actually joined its group ({@code auto.offset.reset=latest} drops
   * anything published before that).
   *
   * @param leaderNode the leader node to refresh, never null.
   * @param condition  the condition to satisfy, never null.
   * @param budget     the maximum time to keep trying, never null.
   * @throws Exception if the condition is not met within the budget.
   */
  void refreshUntil(final GenericContainer<?> leaderNode, final BooleanSupplier condition,
      final Duration budget) throws Exception {
    final long deadline = System.nanoTime() + budget.toNanos();
    while (System.nanoTime() < deadline) {
      post(leaderNode, "/refresh");
      if (awaitQuietly(Duration.ofSeconds(2), condition)) {
        return;
      }
      LOG.info("Condition not met yet; re-publishing leader refresh");
    }
    throw new AssertionError("Condition not met within " + budget.toSeconds()
        + "s of repeated leader refreshes");
  }

  /**
   * Stops a node and removes it from this harness's tracked nodes.
   *
   * @param node the node to stop, never null.
   */
  void stopNode(final GenericContainer<?> node) {
    quietStop(node);
    nodes.remove(node);
  }

  /**
   * Stops a container without propagating shutdown errors.
   *
   * @param container the container to stop, may be null.
   */
  private void quietStop(final GenericContainer<?> container) {
    if (container == null) {
      return;
    }
    try {
      container.stop();
    } catch (final Exception e) {
      LOG.warn("Failed to stop container: {}", e.getMessage());
    }
  }

  /** {@inheritDoc} */
  @Override
  public void close() {
    for (final GenericContainer<?> node : List.copyOf(nodes)) {
      quietStop(node);
    }
    nodes.clear();
    quietStop(postgres);
    quietStop(kafka);
    try {
      network.close();
    } catch (final Exception e) {
      LOG.warn("Failed to close network: {}", e.getMessage());
    }
  }
}
