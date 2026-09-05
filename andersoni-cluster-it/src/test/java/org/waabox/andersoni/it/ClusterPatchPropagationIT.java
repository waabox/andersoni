package org.waabox.andersoni.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

import org.json.JSONArray;
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
 * Docker-based integration test for cross-node snapshot patches. Boots real
 * containers — Kafka, PostgreSQL and three {@link ClusterNode}s, one leader
 * and two followers — and drives them over HTTP.
 *
 * <p>The whole suite rests on one arrangement: <strong>a patch is applied to
 * the leader's memory only, never to the database</strong>. The DataLoader
 * therefore cannot reproduce the patched state. A follower that converges on
 * the patched value can only have done so by applying the delta surgically; a
 * follower that fell back to a full reload would land on the untouched
 * database rows and disagree on the hash. That is what makes these assertions
 * evidence rather than coincidence — the two paths have visibly different
 * outcomes.
 *
 * <p>No snapshot store is configured, which is deliberate for the same
 * reason: it keeps the fallback path anchored to the database.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class ClusterPatchPropagationIT {

  /** The class logger. */
  private static final Logger LOG =
      LoggerFactory.getLogger(ClusterPatchPropagationIT.class);

  /** Total number of Andersoni node containers (index 0 is the leader). */
  private static final int NODE_COUNT = 3;

  /** In-network Kafka listener host:port advertised to node containers. */
  private static final String KAFKA_INTERNAL = "kafka:19092";

  /** HTTP client for talking to node containers. */
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void whenLeaderPatches_givenCluster_shouldApplySurgicallyOnFollowers()
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

      final Future<String> image = new ImageFromDockerfile(
          "andersoni-cluster-node:it", false)
          .withFileFromPath("Dockerfile", Paths.get("Dockerfile"))
          .withFileFromPath("app.jar", Paths.get("target/app.jar"));

      for (int i = 0; i < NODE_COUNT; i++) {
        nodes.add(startNode(image, network, kafka, postgres, i));
      }

      // 1) Baseline: every node holds the 3 seeded rows and agrees.
      await(Duration.ofSeconds(60), () -> allConverged(nodes, 3));

      // 2) Warm-up. Kafka consumers reset to `latest`, so a message published
      //    before the groups have joined is missed. A refresh is idempotent
      //    and can be retried until it lands; a patch cannot, so the cluster
      //    must be known-live before the first patch.
      insertItem(postgres, 4, "item-4");
      warmUpLeaderRefresh(nodes, 4);
      final String warmHash = hashOf(nodes.get(0));

      // 3) The leader patches an item that the database still holds unchanged.
      final GenericContainer<?> leader = nodes.get(0);
      assertTrue(isLeader(leader), "node-0 must be the leader");
      assertTrue(replace(leader, "item-1", 1, "item-1-PATCHED"),
          "The leader must report the replace as applied");

      await(Duration.ofSeconds(45), () -> allConverged(nodes, 4)
          && allHold(nodes, "item-1-PATCHED"));

      final String patchedHash = hashOf(leader);
      assertNotEquals(warmHash, patchedHash,
          "A patch must move the snapshot hash");
      for (final GenericContainer<?> node : nodes) {
        assertEquals(patchedHash, state(node).getString("hash"),
            "Every node must agree on the hash after the patch");
        assertTrue(namesOf(node).contains("item-1-PATCHED"),
            "Every node must hold the patched item");
        assertFalse(namesOf(node).contains("item-1"),
            "No node may still hold the pre-patch item");
      }
      assertEquals("item-1", nameInDatabase(postgres, 1),
          "The database must be untouched: convergence on the patched value "
              + "is only reachable by applying the delta, not by reloading");
      LOG.info("Patch applied surgically across {} nodes, hash={}",
          NODE_COUNT, patchedHash);

      // 4) A lookup that matches nothing is a no-op, not a broadcast.
      assertFalse(replace(leader, "no-such-item", 99, "ghost"),
          "A replace whose lookup matches nothing must report not-applied");
      Thread.sleep(3000);
      for (final GenericContainer<?> node : nodes) {
        assertEquals(patchedHash, state(node).getString("hash"),
            "A no-op replace must leave every node's hash untouched");
      }

      // 5) A follower whose snapshot has drifted must refuse the delta and
      //    fall back, rather than applying it onto the wrong base.
      final GenericContainer<?> drifted = nodes.get(2);
      updateItemName(postgres, 2, "item-2-DBCHANGED");
      post(drifted, "/local-refresh");
      await(Duration.ofSeconds(20),
          () -> !hashQuietly(drifted).equals(hashQuietly(leader)));
      LOG.info("node-2 driven out of sync on purpose");

      assertTrue(replace(leader, "item-3", 3, "item-3-PATCHED"),
          "The leader must apply the second patch locally");

      await(Duration.ofSeconds(45), () -> {
        final List<String> names = namesQuietly(nodes.get(1));
        return names.contains("item-3-PATCHED")
            && hashQuietly(nodes.get(1)).equals(hashQuietly(leader));
      });

      // The drifted follower must have reloaded from the DataLoader: it holds
      // exactly the database, with no trace of either patch.
      await(Duration.ofSeconds(30),
          () -> namesQuietly(drifted).contains("item-2-DBCHANGED"));
      final List<String> driftedNames = namesOf(drifted);
      assertFalse(driftedNames.contains("item-3-PATCHED"),
          "A drifted follower must not apply a delta onto a base the leader "
              + "did not compute it from");
      assertFalse(driftedNames.contains("item-1-PATCHED"),
          "The drifted follower reloaded from the database, so neither patch "
              + "may be present");
      assertEquals(List.of("item-1", "item-2-DBCHANGED", "item-3", "item-4"),
          driftedNames,
          "The drifted follower must hold exactly the database contents");
      assertNotEquals(hashOf(leader), hashOf(drifted),
          "Leader and drifted follower must disagree: the leader's state "
              + "exists only in memory and no snapshot store can carry it");
      LOG.info("Drifted follower refused the delta and fell back cleanly");

      // 6) Re-converge everyone on the database before exercising the other
      //    two operations, since node-2 was deliberately left behind.
      warmUpLeaderRefresh(nodes, 4);
      final String reconverged = hashOf(leader);

      // 7) An insert of an item the database has never held. Only a
      //    surgical apply can produce it on a follower.
      assertTrue(insert(leader, 99, "item-99-INSERTED"),
          "The leader must report the insert as applied");
      await(Duration.ofSeconds(45), () -> allConverged(nodes, 5)
          && allHold(nodes, "item-99-INSERTED"));
      assertNotEquals(reconverged, hashOf(leader),
          "An insert must move the snapshot hash");
      for (final GenericContainer<?> node : nodes) {
        assertTrue(namesOf(node).contains("item-99-INSERTED"),
            "Every node must hold the inserted item");
      }
      LOG.info("Insert propagated surgically across {} nodes", NODE_COUNT);

      // 8) A removal of an item the database still holds. A follower that
      //    reloaded would bring it straight back, so convergence without it
      //    is what proves the removal was applied and not reloaded around.
      assertTrue(remove(leader, "item-2-DBCHANGED"),
          "The leader must report the removal as applied");
      await(Duration.ofSeconds(45), () -> allConverged(nodes, 4));
      for (final GenericContainer<?> node : nodes) {
        final List<String> names = namesOf(node);
        assertFalse(names.contains("item-2-DBCHANGED"),
            "Every node must have dropped the removed item");
        assertTrue(names.contains("item-99-INSERTED"),
            "The earlier insert must survive the removal");
      }
      assertEquals("item-2-DBCHANGED", nameInDatabase(postgres, 2),
          "The database must still hold the removed row: convergence "
              + "without it is only reachable by applying the removal");
      LOG.info("Removal propagated surgically across {} nodes", NODE_COUNT);
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
   * Starts one node container.
   *
   * @param image    the node image, never null.
   * @param network  the shared network, never null.
   * @param kafka    the Kafka container, never null.
   * @param postgres the PostgreSQL container, never null.
   * @param index    the node index; index {@code 0} is the leader.
   *
   * @return the started container, never null.
   */
  private GenericContainer<?> startNode(final Future<String> image,
      final Network network, final KafkaContainer kafka,
      final PostgreSQLContainer<?> postgres, final int index) {
    final String nodeId = "node-" + index;
    final GenericContainer<?> node = new GenericContainer<>(image)
        .withNetwork(network)
        .withNetworkAliases(nodeId)
        .dependsOn(kafka, postgres)
        .withEnv("NODE_ID", nodeId)
        .withEnv("LEADER", Boolean.toString(index == 0))
        .withEnv("KAFKA_BOOTSTRAP", KAFKA_INTERNAL)
        .withEnv("KAFKA_TOPIC", "andersoni-patch-it")
        .withEnv("JDBC_URL", "jdbc:postgresql://postgres:5432/andersoni")
        .withEnv("JDBC_USER", "test")
        .withEnv("JDBC_PASSWORD", "test")
        .withEnv("HTTP_PORT", "8080")
        .withExposedPorts(8080)
        .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix(nodeId))
        .waitingFor(Wait.forHttp("/health").forPort(8080)
            .forStatusCode(200))
        .withStartupTimeout(Duration.ofSeconds(150));
    node.start();
    return node;
  }

  /**
   * Asks a node to replace an item found through the {@code by-name} index.
   *
   * @param node    the node container, never null.
   * @param key     the current name to look the item up by, never null.
   * @param id      the replacement item's id.
   * @param newName the replacement item's name, never null.
   *
   * @return whether the node reported the replace as applied.
   *
   * @throws Exception if the request fails.
   */
  private boolean replace(final GenericContainer<?> node, final String key,
      final int id, final String newName) throws Exception {
    final String path = "/replace?key=" + key + "&id=" + id
        + "&name=" + newName;
    final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl(node) + path))
        .timeout(Duration.ofSeconds(15))
        .POST(HttpRequest.BodyPublishers.noBody())
        .build();
    final HttpResponse<String> response =
        http.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(),
        "Replace failed: " + response.body());
    return new JSONObject(response.body()).getBoolean("applied");
  }

  /**
   * Asks a node to insert a new item.
   *
   * @param node the node container, never null.
   * @param id   the new item's id.
   * @param name the new item's name, never null.
   *
   * @return whether the node reported the insert as applied.
   *
   * @throws Exception if the request fails.
   */
  private boolean insert(final GenericContainer<?> node, final int id,
      final String name) throws Exception {
    return applied(node, "/insert?id=" + id + "&name=" + name);
  }

  /**
   * Asks a node to remove the item found through the {@code by-name} index.
   *
   * @param node the node container, never null.
   * @param key  the name to look the item up by, never null.
   *
   * @return whether the node reported the removal as applied.
   *
   * @throws Exception if the request fails.
   */
  private boolean remove(final GenericContainer<?> node, final String key)
      throws Exception {
    return applied(node, "/remove?key=" + key);
  }

  /**
   * POSTs to a node path and reads the {@code applied} flag it answers.
   *
   * @param node the node container, never null.
   * @param path the request path, never null.
   *
   * @return the reported flag.
   *
   * @throws Exception if the request fails.
   */
  private boolean applied(final GenericContainer<?> node, final String path)
      throws Exception {
    final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl(node) + path))
        .timeout(Duration.ofSeconds(15))
        .POST(HttpRequest.BodyPublishers.noBody())
        .build();
    final HttpResponse<String> response =
        http.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(),
        "Request " + path + " failed: " + response.body());
    return new JSONObject(response.body()).getBoolean("applied");
  }

  /**
   * Returns the item names a node currently holds, in snapshot order.
   *
   * @param node the node container, never null.
   *
   * @return the names, never null.
   *
   * @throws Exception if the node cannot be queried.
   */
  private List<String> namesOf(final GenericContainer<?> node)
      throws Exception {
    final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl(node) + "/items"))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();
    final HttpResponse<String> response =
        http.send(request, HttpResponse.BodyHandlers.ofString());
    final JSONArray array = new JSONArray(response.body());
    final List<String> names = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      names.add(array.getJSONObject(i).getString("name"));
    }
    return names;
  }

  /**
   * {@link #namesOf} for use inside a polling predicate, where a request that
   * loses a race with a snapshot swap should retry rather than fail.
   *
   * @param node the node container, never null.
   *
   * @return the names, or an empty list if the node could not be queried.
   */
  private List<String> namesQuietly(final GenericContainer<?> node) {
    try {
      return namesOf(node);
    } catch (final Exception e) {
      return List.of();
    }
  }

  /**
   * {@link #hashOf} for use inside a polling predicate.
   *
   * @param node the node container, never null.
   *
   * @return the hash, or an empty string if the node could not be queried.
   */
  private String hashQuietly(final GenericContainer<?> node) {
    try {
      return hashOf(node);
    } catch (final Exception e) {
      return "";
    }
  }

  /**
   * Returns whether every node holds an item with the given name.
   *
   * @param nodes the node containers, never null.
   * @param name  the name to look for, never null.
   *
   * @return {@code true} if all nodes hold it.
   */
  private boolean allHold(final List<GenericContainer<?>> nodes,
      final String name) {
    for (final GenericContainer<?> node : nodes) {
      if (!namesQuietly(node).contains(name)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Creates the {@code items} table and seeds three rows.
   *
   * @param postgres the PostgreSQL container, never null.
   *
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
   * Inserts a single item.
   *
   * @param postgres the PostgreSQL container, never null.
   * @param id       the item id.
   * @param name     the item name, never null.
   *
   * @throws Exception if the insert fails.
   */
  private void insertItem(final PostgreSQLContainer<?> postgres,
      final int id, final String name) throws Exception {
    try (Connection conn = hostConnection(postgres);
        Statement st = conn.createStatement()) {
      st.execute("INSERT INTO items (id, name) VALUES ("
          + id + ", '" + name + "')");
    }
  }

  /**
   * Renames a single item in the database.
   *
   * @param postgres the PostgreSQL container, never null.
   * @param id       the item id.
   * @param name     the new name, never null.
   *
   * @throws Exception if the update fails.
   */
  private void updateItemName(final PostgreSQLContainer<?> postgres,
      final int id, final String name) throws Exception {
    try (Connection conn = hostConnection(postgres);
        Statement st = conn.createStatement()) {
      st.execute("UPDATE items SET name = '" + name + "' WHERE id = " + id);
    }
  }

  /**
   * Reads an item's name straight from the database.
   *
   * @param postgres the PostgreSQL container, never null.
   * @param id       the item id.
   *
   * @return the stored name, or null if the row is absent.
   *
   * @throws Exception if the query fails.
   */
  private String nameInDatabase(final PostgreSQLContainer<?> postgres,
      final int id) throws Exception {
    try (Connection conn = hostConnection(postgres);
        Statement st = conn.createStatement();
        var rs = st.executeQuery(
            "SELECT name FROM items WHERE id = " + id)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  /**
   * Opens a JDBC connection to the host-mapped database port.
   *
   * @param postgres the PostgreSQL container, never null.
   *
   * @return a new connection, never null.
   *
   * @throws Exception if the connection cannot be opened.
   */
  private Connection hostConnection(final PostgreSQLContainer<?> postgres)
      throws Exception {
    return DriverManager.getConnection(postgres.getJdbcUrl(),
        postgres.getUsername(), postgres.getPassword());
  }

  /**
   * Retries a leader refresh until every node converges, tolerating the
   * {@code latest} offset reset while consumer groups are still joining.
   *
   * @param nodes    the node containers, never null.
   * @param expected the expected item count on every node.
   *
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
   *
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
   * Returns the snapshot hash reported by a node.
   *
   * @param node the node container, never null.
   *
   * @return the hash, never null.
   *
   * @throws Exception if the node cannot be queried.
   */
  private String hashOf(final GenericContainer<?> node) throws Exception {
    return state(node).getString("hash");
  }

  /**
   * Returns whether a node reports itself as the leader.
   *
   * @param node the node container, never null.
   *
   * @return {@code true} if the node is the leader.
   *
   * @throws Exception if the node cannot be queried.
   */
  private boolean isLeader(final GenericContainer<?> node) throws Exception {
    return state(node).getBoolean("leader");
  }

  /**
   * Fetches and parses a node's {@code /state} endpoint.
   *
   * @param node the node container, never null.
   *
   * @return the parsed state, never null.
   *
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
   *
   * @throws Exception if the request fails.
   */
  private void post(final GenericContainer<?> node, final String path)
      throws Exception {
    final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl(node) + path))
        .timeout(Duration.ofSeconds(15))
        .POST(HttpRequest.BodyPublishers.noBody())
        .build();
    http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Returns the host-mapped base URL for a node's HTTP API.
   *
   * @param node the node container, never null.
   *
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
   *
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
   *
   * @return {@code true} if the condition held before the timeout.
   *
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
