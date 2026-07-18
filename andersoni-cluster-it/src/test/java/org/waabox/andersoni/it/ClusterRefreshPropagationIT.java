package org.waabox.andersoni.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Docker-based integration test validating cluster refresh-request
 * propagation. It boots real containers: Kafka, PostgreSQL, and N Andersoni
 * nodes ({@link ClusterNode}), one leader and the rest followers.
 *
 * <p>The scenario under test is the bug fix: when a <strong>follower</strong>
 * is asked to refresh, it must publish a {@code REQUEST} that only the leader
 * acts upon; the leader refreshes and broadcasts an {@code EVENT}, and every
 * node converges to the same snapshot — with no propagation loop.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class ClusterRefreshPropagationIT {

  /** The class logger. */
  private static final Logger LOG =
      LoggerFactory.getLogger(ClusterRefreshPropagationIT.class);

  /** Total number of Andersoni node containers (index 0 is the leader). */
  private static final int NODE_COUNT = 3;

  /** In-network Kafka listener host:port advertised to node containers. */
  private static final String KAFKA_INTERNAL = "kafka:19092";

  /** HTTP client for talking to node containers. */
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void whenFollowerAsksRefresh_givenCluster_shouldPropagateViaLeaderWithoutLoop()
      throws Exception {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
        "Docker is required for this integration test");

    final Network network = Network.newNetwork();

    final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1")
        .withNetwork(network)
        .withNetworkAliases("kafka")
        .withListener(KAFKA_INTERNAL);

    final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
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

      // Build the node image once from the shaded jar and the Dockerfile.
      final Future<String> image = new ImageFromDockerfile(
          "andersoni-cluster-node:it", false)
          .withFileFromPath("Dockerfile", Paths.get("Dockerfile"))
          .withFileFromPath("app.jar", Paths.get("target/app.jar"));

      for (int i = 0; i < NODE_COUNT; i++) {
        final String nodeId = "node-" + i;
        final boolean leader = i == 0;
        final GenericContainer<?> node = new GenericContainer<>(image)
            .withNetwork(network)
            .withNetworkAliases(nodeId)
            .dependsOn(kafka, postgres)
            .withEnv("NODE_ID", nodeId)
            .withEnv("LEADER", Boolean.toString(leader))
            .withEnv("KAFKA_BOOTSTRAP", KAFKA_INTERNAL)
            .withEnv("KAFKA_TOPIC", "andersoni-it")
            .withEnv("JDBC_URL",
                "jdbc:postgresql://postgres:5432/andersoni")
            .withEnv("JDBC_USER", "test")
            .withEnv("JDBC_PASSWORD", "test")
            .withEnv("HTTP_PORT", "8080")
            .withExposedPorts(8080)
            .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix(nodeId))
            .waitingFor(Wait.forHttp("/health").forPort(8080)
                .forStatusCode(200))
            .withStartupTimeout(Duration.ofSeconds(150));
        node.start();
        nodes.add(node);
      }

      // 1) Every node bootstraps to the 3 seeded rows and agrees on the hash.
      await(Duration.ofSeconds(60),
          () -> allConverged(nodes, 3));
      final String baselineHash = hashOf(nodes.get(0));
      LOG.info("Baseline converged at 3 items, hash={}", baselineHash);

      // 2) Warm-up: exercise the leader path and, since consumers use
      //    auto.offset.reset=latest, retry until consumer groups are joined.
      insertItem(postgres, 4, "item-4");
      warmUpLeaderRefresh(nodes, 4);
      final String warmHash = hashOf(nodes.get(0));
      assertNotEquals(baselineHash, warmHash,
          "Warm-up refresh must change the snapshot hash");

      // 3) THE FIX: ask a FOLLOWER to refresh. It must delegate to the leader
      //    via a REQUEST; the leader refreshes and broadcasts; all converge.
      insertItem(postgres, 5, "item-5");
      final GenericContainer<?> follower = nodes.get(1);
      assertEquals(false, isLeader(follower), "node-1 must be a follower");
      post(follower, "/refresh");

      await(Duration.ofSeconds(45), () -> allConverged(nodes, 5));

      final String finalHash = hashOf(nodes.get(0));
      assertNotEquals(warmHash, finalHash,
          "Follower-triggered refresh must change the snapshot hash");
      for (final GenericContainer<?> node : nodes) {
        final JSONObject state = state(node);
        assertEquals(5, state.getInt("itemCount"),
            "Every node must hold 5 items after propagation");
        assertEquals(finalHash, state.getString("hash"),
            "Every node must agree on the snapshot hash");
      }
      LOG.info("Follower-triggered refresh converged on all {} nodes",
          NODE_COUNT);

      // 4) No loop: the system must quiesce. Snapshot versions must not keep
      //    advancing after convergence (a loop would refresh endlessly).
      final List<Long> versions = versionsOf(nodes);
      Thread.sleep(6000);
      final List<Long> versionsAfter = versionsOf(nodes);
      assertEquals(versions, versionsAfter,
          "Versions must be stable after convergence (no propagation loop)");
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
   * are still joining by re-publishing.
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
   * Returns the snapshot hash reported by a node.
   *
   * @param node the node container, never null.
   * @return the hash, never null.
   * @throws Exception if the node cannot be queried.
   */
  private String hashOf(final GenericContainer<?> node) throws Exception {
    return state(node).getString("hash");
  }

  /**
   * Returns whether a node reports itself as the leader.
   *
   * @param node the node container, never null.
   * @return {@code true} if the node is the leader.
   * @throws Exception if the node cannot be queried.
   */
  private boolean isLeader(final GenericContainer<?> node) throws Exception {
    return state(node).getBoolean("leader");
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
