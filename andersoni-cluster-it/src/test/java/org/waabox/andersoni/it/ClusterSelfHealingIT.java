package org.waabox.andersoni.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * Verifies that a cluster self-heals from realistic node failures: a leader
 * dying after a refresh whose event never reached the followers, a leader
 * whose snapshot store save fails, a follower that misses Kafka events while
 * Kafka itself stays healthy, and a follower whose bootstrap fails before any
 * leader has ever published a snapshot.
 *
 * <p>Each scenario injects a single deterministic fault through a node's HTTP
 * API (see {@link ClusterNode}) and relies on Andersoni's reconciliation loop
 * and leader-promotion refresh to repair the resulting drift, rather than on
 * manual retries.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class ClusterSelfHealingIT {

  private static final Logger LOG = LoggerFactory.getLogger(ClusterSelfHealingIT.class);

  @Test
  void whenLeaderDiesAfterRefresh_givenFollowersMissedTheEvent_shouldConvergeOnPromotedLeaderRefresh()
      throws Exception {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
        "Docker is required for this integration test");

    try (ClusterHarness cluster = new ClusterHarness()) {
      cluster.start();

      final GenericContainer<?> node0 = cluster.startNode("node-0", true,
          Map.of("RECONCILE_INTERVAL_MS", "60000"));
      final GenericContainer<?> node1 = cluster.startNode("node-1", false,
          Map.of("RECONCILE_INTERVAL_MS", "60000"));
      final GenericContainer<?> node2 = cluster.startNode("node-2", false,
          Map.of("RECONCILE_INTERVAL_MS", "2000"));

      cluster.await(Duration.ofSeconds(60), () -> cluster.allConverged(3));
      cluster.warmUpLeaderRefresh(node0, 3);
      LOG.info("Baseline converged at 3 items across node-0, node-1 and node-2");

      assertEquals(200, cluster.post(node1, "/fault/sync?dropIncoming=true"));
      assertEquals(200, cluster.post(node2, "/fault/sync?dropIncoming=true"));

      cluster.insertItem(4, "item-4");
      assertEquals(200, cluster.post(node0, "/refresh"));
      cluster.await(Duration.ofSeconds(10), () -> itemCount(cluster, node0) == 4);
      assertEquals(3, itemCount(cluster, node1),
          "node-1 has a 60s reconcile interval and dropped the leader's event");

      final String storeHashBefore = cluster.storeHash(node1).orElseThrow();

      cluster.stopNode(node0);
      assertEquals(200, cluster.post(node1, "/leader?value=true"));

      final List<GenericContainer<?>> survivors = List.of(node1, node2);
      cluster.await(Duration.ofSeconds(30), () -> cluster.allConverged(survivors, 4));
      assertTrue(cluster.state(node1).getBoolean("leader"));
      cluster.post(node1, "/reconcile");
      cluster.await(Duration.ofSeconds(15), () -> cluster.allInSync(survivors));

      assertEquals(cluster.storeHash(node1).orElseThrow(), cluster.hashOf(node1));
      assertEquals(4, itemCount(cluster, node1));
      assertEquals(4, itemCount(cluster, node2));
      LOG.info("Store hash unchanged after promotion: {}",
          storeHashBefore.equals(cluster.storeHash(node1).orElseThrow()));
      LOG.info("node-1 promoted to leader, re-established truth from the source, and "
          + "converged node-2 through its authoritative refresh");
    }
  }

  @Test
  void whenLeaderSaveFails_givenRefresh_shouldRepublishOnceStoreRecovers() throws Exception {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
        "Docker is required for this integration test");

    try (ClusterHarness cluster = new ClusterHarness()) {
      cluster.start();

      final GenericContainer<?> node0 = cluster.startNode("node-0", true, Map.of());
      final GenericContainer<?> node1 = cluster.startNode("node-1", false, Map.of());
      final GenericContainer<?> node2 = cluster.startNode("node-2", false, Map.of());
      final List<GenericContainer<?>> all = List.of(node0, node1, node2);

      cluster.await(Duration.ofSeconds(60), () -> cluster.allConverged(3));
      cluster.warmUpLeaderRefresh(node0, 3);
      LOG.info("Baseline converged at 3 items across node-0, node-1 and node-2");

      assertEquals(200, cluster.post(node0, "/fault/store?failSave=true"));

      cluster.insertItem(4, "item-4");
      assertEquals(500, cluster.post(node0, "/refresh"));
      assertEquals(4, itemCount(cluster, node0));
      Thread.sleep(3000);
      assertEquals(3, itemCount(cluster, node1), "nothing was published: the store still holds 3 items");
      assertEquals(3, itemCount(cluster, node2), "nothing was published: the store still holds 3 items");

      assertEquals(200, cluster.post(node0, "/fault/store?failSave=false"));

      cluster.await(Duration.ofSeconds(30), () -> cluster.allConverged(4));
      cluster.await(Duration.ofSeconds(15), () -> cluster.allInSync(all));
      assertEquals(cluster.storeHash(node0).orElseThrow(), cluster.hashOf(node0));
      LOG.info("Leader recovered from a failed save and republished once the store came back");
    }
  }

  @Test
  void whenOneFollowerMissesEvents_givenKafkaHealthy_shouldConvergeThroughStore() throws Exception {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
        "Docker is required for this integration test");

    try (ClusterHarness cluster = new ClusterHarness()) {
      cluster.start();

      final GenericContainer<?> node0 = cluster.startNode("node-0", true, Map.of());
      final GenericContainer<?> node1 = cluster.startNode("node-1", false, Map.of());
      final GenericContainer<?> node2 = cluster.startNode("node-2", false, Map.of());
      final List<GenericContainer<?>> all = List.of(node0, node1, node2);

      cluster.await(Duration.ofSeconds(60), () -> cluster.allConverged(3));
      cluster.warmUpLeaderRefresh(node0, 3);
      LOG.info("Baseline converged at 3 items across node-0, node-1 and node-2");

      assertEquals(200, cluster.post(node2, "/fault/sync?dropIncoming=true"));

      cluster.insertItem(4, "item-4");
      // node-2's consumer may not have joined its group yet (offset reset =
      // latest), so keep re-publishing until it provably received and dropped
      // one event; the data does not change between refreshes.
      cluster.refreshUntil(node0,
          () -> droppedEvents(cluster, node2) >= 1,
          Duration.ofSeconds(60));

      cluster.await(Duration.ofSeconds(30), () -> cluster.allConverged(4));
      cluster.await(Duration.ofSeconds(15), () -> cluster.allInSync(all));
      assertTrue(cluster.state(node2).getLong("droppedEvents") >= 1,
          "node-2 must have really missed the event and converged through the store");
      assertEquals(0, cluster.state(node1).getLong("droppedEvents"));
      LOG.info("node-2 missed the leader's event over Kafka and converged through the snapshot store");
    }
  }

  @Test
  void whenFollowerBootstrapFails_givenLeaderUploadsLater_shouldRecoverFromStore() throws Exception {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
        "Docker is required for this integration test");

    try (ClusterHarness cluster = new ClusterHarness()) {
      cluster.start();

      final GenericContainer<?> node2 = cluster.startNode("node-2", false,
          Map.of("LOADER_MODE", "fail"));
      cluster.await(Duration.ofSeconds(30), () -> !available(cluster, node2));
      assertEquals(0, itemCount(cluster, node2));
      LOG.info("node-2 bootstrap failed as expected: no store snapshot and a faulty loader");

      final GenericContainer<?> node0 = cluster.startNode("node-0", true, Map.of());
      cluster.await(Duration.ofSeconds(60), () -> itemCount(cluster, node0) == 3);

      cluster.await(Duration.ofSeconds(30),
          () -> available(cluster, node2) && itemCount(cluster, node2) == 3);
      cluster.await(Duration.ofSeconds(15), () -> cluster.allInSync(List.of(node0, node2)));
      assertEquals(cluster.hashOf(node0), cluster.hashOf(node2));
      LOG.info("node-2 recovered from a failed bootstrap once the leader published a snapshot");
    }
  }

  /**
   * Reads a node's current item count for use inside polling conditions,
   * returning {@code -1} if the node cannot be queried.
   *
   * @param cluster the harness, never null.
   * @param node    the node to query, never null.
   * @return the item count, or {@code -1} on failure.
   */
  private int itemCount(final ClusterHarness cluster, final GenericContainer<?> node) {
    try {
      return cluster.state(node).getInt("itemCount");
    } catch (final Exception e) {
      return -1;
    }
  }

  private long droppedEvents(final ClusterHarness cluster, final GenericContainer<?> node) {
    try {
      return cluster.state(node).getLong("droppedEvents");
    } catch (final Exception e) {
      return -1L;
    }
  }

  /**
   * Reads whether a node's catalog is currently available for use inside
   * polling conditions, returning {@code false} if the node cannot be
   * queried.
   *
   * @param cluster the harness, never null.
   * @param node    the node to query, never null.
   * @return {@code true} if the catalog is bootstrapped and queryable.
   */
  private boolean available(final ClusterHarness cluster, final GenericContainer<?> node) {
    try {
      return cluster.state(node).getBoolean("available");
    } catch (final Exception e) {
      return false;
    }
  }
}
