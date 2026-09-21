package org.waabox.andersoni.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Verifies that followers converge through the snapshot store when the sync
 * channel is down: reconciliation repairs what a lost event would leave
 * stale.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class ClusterReconciliationIT {

  private static final Logger LOG = LoggerFactory.getLogger(ClusterReconciliationIT.class);
  private static final int NODE_COUNT = 3;
  private static final String KAFKA_INTERNAL = "kafka:19092";

  /** HTTP client for talking to node containers. */
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void whenLeaderRefreshes_givenKafkaDown_shouldConvergeThroughSnapshotStore()
      throws Exception {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
        "Docker is required for this integration test");

    final Path snapshotDir = Paths.get("target", "it-snapshots-" + UUID.randomUUID())
        .toAbsolutePath();
    Files.createDirectories(snapshotDir);

    final Network network = Network.newNetwork();
    final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1")
        .withNetwork(network)
        .withNetworkAliases("kafka")
        .withListener(KAFKA_INTERNAL);
    final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withNetwork(network)
        .withNetworkAliases("postgres")
        .withDatabaseName("andersoni")
        .withUsername("test")
        .withPassword("test");
    final List<GenericContainer<?>> nodes = new ArrayList<>();

    try {
      kafka.start();
      postgres.start();
      initSchemaAndSeed(postgres);

      final Future<String> image = new ImageFromDockerfile("andersoni-cluster-node:it", false)
          .withFileFromPath("Dockerfile", Paths.get("Dockerfile"))
          .withFileFromPath("app.jar", Paths.get("target/app.jar"));

      for (int i = 0; i < NODE_COUNT; i++) {
        final String nodeId = "node-" + i;
        final GenericContainer<?> node = new GenericContainer<>(image)
            .withNetwork(network)
            .withNetworkAliases(nodeId)
            .dependsOn(kafka, postgres)
            .withEnv("NODE_ID", nodeId)
            .withEnv("LEADER", Boolean.toString(i == 0))
            .withEnv("KAFKA_BOOTSTRAP", KAFKA_INTERNAL)
            .withEnv("KAFKA_TOPIC", "andersoni-reconcile-it")
            .withEnv("JDBC_URL", "jdbc:postgresql://postgres:5432/andersoni")
            .withEnv("JDBC_USER", "test")
            .withEnv("JDBC_PASSWORD", "test")
            .withEnv("HTTP_PORT", "8080")
            .withEnv("SNAPSHOT_DIR", "/snapshots")
            .withEnv("RECONCILE_INTERVAL_MS", "2000")
            .withFileSystemBind(snapshotDir.toString(), "/snapshots", BindMode.READ_WRITE)
            .withExposedPorts(8080)
            .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix(nodeId))
            .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))
            .withStartupTimeout(Duration.ofSeconds(150));
        node.start();
        nodes.add(node);
      }

      // 1) Baseline: everyone at the 3 seeded rows, same hash.
      await(Duration.ofSeconds(60), () -> allConverged(nodes, 3));
      LOG.info("Baseline converged at 3 items");

      // Warm up the leader's Kafka producer (topic metadata) and the
      // followers' consumer groups while Kafka is still reachable. Without
      // this, the leader's first-ever publish call below would block inside
      // the Kafka client fetching topic metadata for a broker that is
      // already down (up to the client's max.block.ms), instead of the
      // fire-and-forget send the outage step means to exercise.
      warmUpLeaderRefresh(nodes, 3);
      LOG.info("Kafka warm-up complete");

      // 2) Sync channel outage.
      kafka.stop();
      LOG.info("Kafka stopped; the leader's next publish cannot reach anyone");

      // 3) Leader refreshes: saves to the store, publish fails silently.
      insertItem(postgres, 4, "item-4");
      post(nodes.get(0), "/refresh");

      // 4) Followers must converge through the store within a few passes.
      await(Duration.ofSeconds(30), () -> allConverged(nodes, 4));
      for (final GenericContainer<?> node : nodes) {
        final JSONObject state = state(node);
        assertEquals(4, state.getInt("itemCount"));
      }
      await(Duration.ofSeconds(15), () -> nodes.stream().allMatch(n -> {
        try {
          return "IN_SYNC".equals(state(n).getString("syncState"));
        } catch (final Exception e) {
          return false;
        }
      }));
      LOG.info("All {} nodes converged and report IN_SYNC without Kafka", NODE_COUNT);

      // 5) Quiescence: versions stable once converged (no repair loop).
      final List<Long> versions = versionsOf(nodes);
      Thread.sleep(6000);
      assertEquals(versions, versionsOf(nodes),
          "Versions must be stable after convergence (no reconciliation loop)");
    } finally {
      for (final GenericContainer<?> node : nodes) {
        quietStop(node);
      }
      quietStop(postgres);
      quietStop(kafka);
      network.close();
    }
  }

  /**
   * Creates the {@code items} table and seeds three rows using the host-mapped
   * JDBC endpoint.
   *
   * @param postgres the PostgreSQL container, never null.
   * @throws Exception if the database cannot be initialized.
   */
  private void initSchemaAndSeed(final PostgreSQLContainer<?> postgres)
      throws Exception {
    try (Connection conn = hostConnection(postgres);
        Statement st = conn.createStatement()) {
      st.execute("CREATE TABLE items (id INT PRIMARY KEY, name VARCHAR(64))");
      st.execute("INSERT INTO items (id, name) VALUES "
          + "(1, 'item-1'), (2, 'item-2'), (3, 'item-3')");
    }
  }

  /**
   * Inserts a single item using the host-mapped JDBC endpoint.
   *
   * @param postgres the PostgreSQL container, never null.
   * @param id       the item id.
   * @param name     the item name, never null.
   * @throws Exception if the insert fails.
   */
  private void insertItem(final PostgreSQLContainer<?> postgres, final int id,
      final String name) throws Exception {
    try (Connection conn = hostConnection(postgres);
        Statement st = conn.createStatement()) {
      st.execute("INSERT INTO items (id, name) VALUES ("
          + id + ", '" + name + "')");
    }
  }

  /**
   * Opens a JDBC connection to the host-mapped database port.
   *
   * @param postgres the PostgreSQL container, never null.
   * @return a new connection, never null.
   * @throws Exception if the connection cannot be opened.
   */
  private Connection hostConnection(final PostgreSQLContainer<?> postgres)
      throws Exception {
    return DriverManager.getConnection(postgres.getJdbcUrl(),
        postgres.getUsername(), postgres.getPassword());
  }

  /**
   * Retries a leader refresh until every node converges to the expected item
   * count. Tolerates the {@code latest} offset reset while consumer groups
   * are still joining by re-publishing, and warms up the leader's Kafka
   * producer (topic metadata) in the process.
   *
   * @param nodes    the node containers, never null.
   * @param expected the expected item count on every node.
   * @throws Exception if convergence is not reached within the budget.
   */
  private void warmUpLeaderRefresh(final List<GenericContainer<?>> nodes,
      final int expected) throws Exception {
    final long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      post(nodes.get(0), "/refresh");
      if (awaitQuietly(Duration.ofSeconds(10),
          () -> allConverged(nodes, expected))) {
        return;
      }
      LOG.info("Warm-up not converged yet; re-publishing leader refresh");
    }
    throw new AssertionError("Warm-up never converged to " + expected
        + " items across the cluster");
  }

  /**
   * Returns whether all nodes report the expected item count and share a
   * single snapshot hash.
   *
   * @param nodes    the node containers, never null.
   * @param expected the expected item count.
   * @return {@code true} if the cluster has converged.
   */
  private boolean allConverged(final List<GenericContainer<?>> nodes,
      final int expected) {
    final Set<String> hashes = new HashSet<>();
    for (final GenericContainer<?> node : nodes) {
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
   * Returns the current snapshot versions of every node, in order.
   *
   * @param nodes the node containers, never null.
   * @return the versions, never null.
   * @throws Exception if a node cannot be queried.
   */
  private List<Long> versionsOf(final List<GenericContainer<?>> nodes)
      throws Exception {
    final List<Long> versions = new ArrayList<>();
    for (final GenericContainer<?> node : nodes) {
      versions.add(state(node).getLong("version"));
    }
    return versions;
  }

  /**
   * Fetches and parses a node's {@code /state} endpoint.
   *
   * @param node the node container, never null.
   * @return the parsed state, never null.
   * @throws Exception if the request fails.
   */
  private JSONObject state(final GenericContainer<?> node) throws Exception {
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
   * @param path the request path, never null.
   * @throws Exception if the request fails.
   */
  private void post(final GenericContainer<?> node, final String path)
      throws Exception {
    final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl(node) + path))
        .timeout(Duration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.noBody())
        .build();
    http.send(request, HttpResponse.BodyHandlers.ofString());
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
   * Polls a condition until it holds or the timeout elapses, throwing on
   * timeout.
   *
   * @param timeout   the maximum time to wait, never null.
   * @param condition the condition to satisfy, never null.
   * @throws InterruptedException if interrupted while waiting.
   */
  private void await(final Duration timeout, final BooleanSupplier condition)
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
  private boolean awaitQuietly(final Duration timeout,
      final BooleanSupplier condition) throws InterruptedException {
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
}
