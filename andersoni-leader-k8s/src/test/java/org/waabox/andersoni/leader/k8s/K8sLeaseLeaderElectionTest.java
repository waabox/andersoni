package org.waabox.andersoni.leader.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.waabox.andersoni.leader.LeaderChangeListener;

/**
 * Tests for {@link K8sLeaseLeaderElection} and {@link K8sLeaseConfig}.
 *
 * <p>These tests verify configuration construction, default values,
 * initial state, and listener registration without requiring a running
 * Kubernetes cluster.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class K8sLeaseLeaderElectionTest {

  @Test
  void whenCreatingConfig_givenRequiredParams_shouldRetainValues() {
    final K8sLeaseConfig config = K8sLeaseConfig.create(
        "andersoni-leader", "production", "pod-abc-123",
        Duration.ofSeconds(10), Duration.ofSeconds(20));

    assertEquals("andersoni-leader", config.leaseName());
    assertEquals("production", config.leaseNamespace());
    assertEquals("pod-abc-123", config.identity());
    assertEquals(Duration.ofSeconds(10), config.renewalInterval());
    assertEquals(Duration.ofSeconds(20), config.leaseDuration());
  }

  @Test
  void whenCreatingConfig_givenDefaults_shouldHaveCorrectValues() {
    final K8sLeaseConfig config = K8sLeaseConfig.create(
        "andersoni-leader", "pod-xyz-789");

    assertEquals("andersoni-leader", config.leaseName());
    assertEquals("default", config.leaseNamespace());
    assertEquals("pod-xyz-789", config.identity());
    assertEquals(Duration.ofSeconds(15), config.renewalInterval());
    assertEquals(Duration.ofSeconds(30), config.leaseDuration());
  }

  @Test
  void whenCreatingConfig_givenNullLeaseName_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        K8sLeaseConfig.create(null, "pod-1"));
  }

  @Test
  void whenCreatingConfig_givenNullIdentity_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        K8sLeaseConfig.create("lease", null));
  }

  @Test
  void whenCheckingIsLeader_givenNotStarted_shouldReturnFalse() {
    final K8sLeaseConfig config = K8sLeaseConfig.create(
        "andersoni-leader", "pod-1");

    final K8sLeaseLeaderElection election =
        new K8sLeaseLeaderElection(config);

    assertFalse(election.isLeader());
  }

  @Test
  void whenRegisteringListener_givenMultipleListeners_shouldStoreAll() {
    final K8sLeaseConfig config = K8sLeaseConfig.create(
        "andersoni-leader", "pod-1");

    final K8sLeaseLeaderElection election =
        new K8sLeaseLeaderElection(config);

    final LeaderChangeListener listener1 = isLeader -> { };
    final LeaderChangeListener listener2 = isLeader -> { };
    final LeaderChangeListener listener3 = isLeader -> { };

    election.onLeaderChange(listener1);
    election.onLeaderChange(listener2);
    election.onLeaderChange(listener3);

    assertEquals(3, election.listenerCount());
  }

  @Test
  void whenRegisteringListener_givenNullListener_shouldThrow() {
    final K8sLeaseConfig config = K8sLeaseConfig.create(
        "andersoni-leader", "pod-1");

    final K8sLeaseLeaderElection election =
        new K8sLeaseLeaderElection(config);

    assertThrows(NullPointerException.class, () ->
        election.onLeaderChange(null));
  }

  @Test
  void whenCreatingElection_givenNullConfig_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        new K8sLeaseLeaderElection(null));
  }

  @Test
  void whenCreatingElection_givenValidConfig_shouldNotBeNull() {
    final K8sLeaseConfig config = K8sLeaseConfig.create(
        "andersoni-leader", "pod-1");

    final K8sLeaseLeaderElection election =
        new K8sLeaseLeaderElection(config);

    assertNotNull(election);
  }
}
