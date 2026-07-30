package org.waabox.andersoni.leader.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Tests for the supervisor loop of {@link K8sLeaseLeaderElection}.
 *
 * <p>These tests inject a mock {@link K8sLeaseLeaderElection.ElectionRunner}
 * to simulate the {@link io.kubernetes.client.extended.leaderelection.LeaderElector}
 * returning, throwing, or blocking, and verify that the supervisor restarts
 * the election loop with backoff and releases leadership correctly. They do
 * not require a running Kubernetes cluster.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class K8sLeaseLeaderElectionSupervisorTest {

  /** A backoff policy that records requested attempts but never sleeps,
   * so the tests can drive many restart cycles synchronously. */
  private static final class ZeroBackoff
      implements K8sLeaseLeaderElection.BackoffPolicy {
    final List<Integer> attempts = new CopyOnWriteArrayList<>();

    @Override
    public Duration nextBackoff(final int attempt) {
      attempts.add(attempt);
      return Duration.ZERO;
    }
  }

  private static K8sLeaseConfig anyConfig() {
    return K8sLeaseConfig.create(
        "test-lease", "test-ns", "test-pod",
        Duration.ofMillis(100), Duration.ofMillis(200));
  }

  @Test
  void whenElectorRunReturns_thenSupervisorRestartsIt() throws Exception {
    final AtomicInteger runInvocations = new AtomicInteger(0);
    final CountDownLatch reachedThreeRestarts = new CountDownLatch(3);
    final ZeroBackoff backoff = new ZeroBackoff();

    final K8sLeaseLeaderElection.ElectionRunner runner =
        (onStart, onStop, onNewLeader) -> {
          runInvocations.incrementAndGet();
          reachedThreeRestarts.countDown();
          // Return immediately, simulating LeaderElector.run() exiting.
        };

    final K8sLeaseLeaderElection election = new K8sLeaseLeaderElection(
        anyConfig(), () -> runner, backoff);

    election.start();
    try {
      assertTrue(reachedThreeRestarts.await(2, TimeUnit.SECONDS),
          "supervisor should have restarted the runner at least 3 times");
      assertTrue(runInvocations.get() >= 3);
    } finally {
      election.stop();
    }
  }

  @Test
  void whenElectorRunThrows_thenSupervisorRestartsIt() throws Exception {
    final AtomicInteger runInvocations = new AtomicInteger(0);
    final CountDownLatch reachedThreeRestarts = new CountDownLatch(3);

    final K8sLeaseLeaderElection.ElectionRunner runner =
        (onStart, onStop, onNewLeader) -> {
          runInvocations.incrementAndGet();
          reachedThreeRestarts.countDown();
          throw new RuntimeException("k8s api unavailable");
        };

    final K8sLeaseLeaderElection election = new K8sLeaseLeaderElection(
        anyConfig(), () -> runner, new ZeroBackoff());

    election.start();
    try {
      assertTrue(reachedThreeRestarts.await(2, TimeUnit.SECONDS),
          "supervisor should have restarted after exceptions");
      assertTrue(runInvocations.get() >= 3);
    } finally {
      election.stop();
    }
  }

  @Test
  void whenStopCalled_thenSupervisorDoesNotRestart() throws Exception {
    final AtomicInteger runInvocations = new AtomicInteger(0);
    final CountDownLatch firstRunStarted = new CountDownLatch(1);
    final CountDownLatch releaseRunner = new CountDownLatch(1);

    final K8sLeaseLeaderElection.ElectionRunner runner =
        (onStart, onStop, onNewLeader) -> {
          runInvocations.incrementAndGet();
          firstRunStarted.countDown();
          releaseRunner.await();
        };

    final K8sLeaseLeaderElection election = new K8sLeaseLeaderElection(
        anyConfig(), () -> runner, new ZeroBackoff());

    election.start();
    assertTrue(firstRunStarted.await(2, TimeUnit.SECONDS));

    releaseRunner.countDown();
    election.stop();

    // Give the supervisor a moment; if it kept restarting we'd see more
    // invocations.
    Thread.sleep(200);
    assertEquals(1, runInvocations.get(),
        "supervisor must not restart after stop()");
  }

  @Test
  void whenRunnerReleasesLeadership_thenIsLeaderReturnsFalse() throws Exception {
    final CountDownLatch becameLeader = new CountDownLatch(1);
    final CountDownLatch releaseRunner = new CountDownLatch(1);

    final K8sLeaseLeaderElection.ElectionRunner runner =
        (onStart, onStop, onNewLeader) -> {
          onStart.run();
          becameLeader.countDown();
          releaseRunner.await();
          // Returning here simulates LeaderElector.run() exiting after
          // the node had been leader — the supervisor must clear the flag.
        };

    final K8sLeaseLeaderElection election = new K8sLeaseLeaderElection(
        anyConfig(), () -> runner, new ZeroBackoff());

    election.start();
    try {
      assertTrue(becameLeader.await(2, TimeUnit.SECONDS));
      assertTrue(election.isLeader());

      releaseRunner.countDown();
      // Wait until the supervisor observes the return and relinquishes.
      final long deadline = System.currentTimeMillis() + 2000;
      while (election.isLeader() && System.currentTimeMillis() < deadline) {
        Thread.sleep(10);
      }
      assertFalse(election.isLeader(),
          "isLeader() must return false after runner exits");
    } finally {
      election.stop();
    }
  }

  @Test
  void whenRestartHappens_thenListenerIsNotifiedWithReason() throws Exception {
    final CountDownLatch reachedTwoRestarts = new CountDownLatch(2);
    final List<String> reasons = new CopyOnWriteArrayList<>();

    final K8sLeaseLeaderElection.ElectionRunner runner =
        (onStart, onStop, onNewLeader) -> {
          if (reasons.isEmpty()) {
            throw new RuntimeException("boom");
          }
          // second cycle returns cleanly to exercise the run_returned path
        };

    final K8sLeaseLeaderElection election = new K8sLeaseLeaderElection(
        anyConfig(), () -> runner, new ZeroBackoff());
    election.onElectionRestart(reason -> {
      reasons.add(reason);
      reachedTwoRestarts.countDown();
    });

    election.start();
    try {
      assertTrue(reachedTwoRestarts.await(2, TimeUnit.SECONDS));
      assertTrue(reasons.contains("exception"),
          "listener should see 'exception' reason; got " + reasons);
      assertTrue(reasons.contains("run_returned"),
          "listener should see 'run_returned' reason; got " + reasons);
    } finally {
      election.stop();
    }
  }

  @Test
  void whenComputingBackoff_thenGrowsExponentiallyAndIsCapped() {
    final K8sLeaseLeaderElection.BackoffPolicy policy =
        K8sLeaseLeaderElection.exponentialBackoff(
            Duration.ofSeconds(1), Duration.ofSeconds(60));

    final Duration attempt1 = policy.nextBackoff(1);
    final Duration attempt2 = policy.nextBackoff(2);
    final Duration attempt5 = policy.nextBackoff(5);
    final Duration attempt20 = policy.nextBackoff(20);

    // attempt 1 ≈ 1s ± jitter
    assertTrue(attempt1.compareTo(Duration.ofMillis(500)) >= 0);
    assertTrue(attempt1.compareTo(Duration.ofMillis(2000)) <= 0);
    // attempt 2 should be roughly double attempt 1's base
    assertTrue(attempt2.compareTo(attempt1) >= 0
        || attempt2.compareTo(Duration.ofSeconds(1)) >= 0);
    // attempt 5 & 20 must be capped at 60s (allow +20% jitter)
    assertTrue(attempt5.compareTo(Duration.ofSeconds(72)) <= 0);
    assertTrue(attempt20.compareTo(Duration.ofSeconds(72)) <= 0);
  }
}
