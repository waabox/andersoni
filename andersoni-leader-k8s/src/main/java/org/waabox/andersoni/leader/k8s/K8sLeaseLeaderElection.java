package org.waabox.andersoni.leader.k8s;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.waabox.andersoni.AndersoniException;
import org.waabox.andersoni.leader.LeaderChangeListener;
import org.waabox.andersoni.leader.LeaderElectionStrategy;

import io.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.kubernetes.client.extended.leaderelection.LeaderElector;
import io.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;

/**
 * Leader election strategy using the Kubernetes Lease API.
 *
 * <p>This implementation uses the official Kubernetes Java client to
 * participate in leader election via the Lease coordination API. A
 * background <em>supervisor</em> daemon thread runs the {@link LeaderElector}
 * inside a retry loop: if {@link LeaderElector#run} ever returns or throws
 * (transient API-server errors, deserialization failures, long GC pauses
 * that miss the renew deadline, etc.), the supervisor releases any local
 * leadership state and re-runs the elector after a bounded exponential
 * backoff. Without this loop the node would silently give up participating
 * in leader election and the cluster could stall with no leader for hours.
 *
 * <p>When this node becomes the leader, all registered
 * {@link LeaderChangeListener} instances are notified with {@code true}.
 * When leadership is lost, they are notified with {@code false}.
 *
 * <p>Usage:
 * <pre>{@code
 * K8sLeaseConfig config = K8sLeaseConfig.create(
 *     "my-leader-lease", "my-pod-name");
 * K8sLeaseLeaderElection election = new K8sLeaseLeaderElection(config);
 * election.onLeaderChange(isLeader -> {
 *     if (isLeader) { // start leading }
 * });
 * election.start();
 * }</pre>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class K8sLeaseLeaderElection implements LeaderElectionStrategy {

  /** The logger for this class. */
  private static final Logger log =
      LoggerFactory.getLogger(K8sLeaseLeaderElection.class);

  /** Upper bound on the retry backoff so the supervisor keeps trying at a
   * reasonable pace even after many consecutive failures. */
  private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(60);

  /** The configuration for the Kubernetes lease. */
  private final K8sLeaseConfig config;

  /** Supplies a fresh {@link ElectionRunner} for each supervisor cycle so a
   * failed API client is not reused across restarts. Package-private to
   * allow tests to inject a fake runner without a real Kubernetes cluster. */
  private final Supplier<ElectionRunner> runnerSupplier;

  /** Backoff policy between restarts of the election loop. */
  private final BackoffPolicy backoff;

  /** The list of registered leader change listeners. */
  private final List<LeaderChangeListener> listeners =
      new CopyOnWriteArrayList<>();

  /** Listener notified each time the supervisor restarts the election loop,
   * with a short machine-readable reason tag. */
  private volatile Consumer<String> restartListener = reason -> { };

  /** The current leader status, volatile for cross-thread visibility. */
  private volatile boolean leader = false;

  /** Latch that signals the first election round has completed. */
  private final CountDownLatch firstElectionLatch = new CountDownLatch(1);

  /** Set by {@link #stop()} so the supervisor loop exits instead of
   * restarting the elector. */
  private final AtomicBoolean stopping = new AtomicBoolean(false);

  /** Count of supervisor-driven restarts, exposed for testing/metrics. */
  private final AtomicInteger restartCount = new AtomicInteger(0);

  /** The background thread running the supervisor loop. */
  private volatile Thread supervisorThread;

  /**
   * Creates a new Kubernetes lease-based leader election strategy.
   *
   * @param theConfig the Kubernetes lease configuration, never null
   */
  public K8sLeaseLeaderElection(final K8sLeaseConfig theConfig) {
    this(theConfig, null, null);
  }

  /**
   * Package-private constructor used by tests to inject a fake election
   * runner and a deterministic backoff policy. Production callers use the
   * single-argument constructor, which supplies a Kubernetes-backed runner
   * and an exponential backoff.
   *
   * @param theConfig         the Kubernetes lease configuration, never null
   * @param theRunnerSupplier supplies an election runner per attempt, or
   *                          {@code null} to use the default Kubernetes
   *                          Lease-based runner
   * @param theBackoff        backoff between restarts, or {@code null} to
   *                          use the default exponential policy
   */
  K8sLeaseLeaderElection(final K8sLeaseConfig theConfig,
      final Supplier<ElectionRunner> theRunnerSupplier,
      final BackoffPolicy theBackoff) {
    config = Objects.requireNonNull(theConfig, "config must not be null");
    if (theRunnerSupplier != null) {
      runnerSupplier = theRunnerSupplier;
    } else {
      runnerSupplier = this::createKubernetesRunner;
    }
    if (theBackoff != null) {
      backoff = theBackoff;
    } else {
      backoff = exponentialBackoff(config.renewalInterval(),
          DEFAULT_MAX_BACKOFF);
    }
  }

  /**
   * Starts the leader election process and blocks until the first
   * election round resolves.
   *
   * <p>Launches a supervisor daemon thread that keeps the underlying
   * Kubernetes {@link LeaderElector} running: if the elector returns or
   * throws, the supervisor logs the reason, notifies the restart listener,
   * sleeps for the backoff duration and starts a fresh elector. This
   * guarantees the node keeps participating in leader election even after
   * transient failures.
   *
   * <p>After launching the thread, this method waits up to
   * {@link K8sLeaseConfig#renewalInterval()} for the first election round
   * to complete. This guarantees that {@link #isLeader()} returns a
   * meaningful result before the caller proceeds with bootstrap logic.
   *
   * @throws AndersoniException if the wait is interrupted or the
   *                             supervisor thread cannot be launched
   */
  @Override
  public void start() {
    final Thread thread = new Thread(this::supervisorLoop,
        "k8s-leader-election-supervisor");
    thread.setDaemon(true);
    thread.start();
    supervisorThread = thread;

    log.info("K8s leader election supervisor started for lease '{}',"
        + " waiting for first election result...", config.leaseName());

    try {
      final boolean resolved = firstElectionLatch.await(
          config.renewalInterval().toMillis(), TimeUnit.MILLISECONDS);
      if (resolved) {
        log.info("K8s leader election resolved: isLeader={}", leader);
      } else {
        log.warn("K8s leader election did not resolve within {},"
            + " proceeding as follower", config.renewalInterval());
      }
    } catch (final InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new AndersoniException(
          "Interrupted while waiting for K8s leader election", ie);
    }
  }

  /**
   * Returns whether this node is currently the leader.
   *
   * @return {@code true} if this node holds the Kubernetes lease
   */
  @Override
  public boolean isLeader() {
    return leader;
  }

  /**
   * Registers a listener to be notified when leadership status changes.
   *
   * <p>If the supervisor is already running, the listener is invoked
   * immediately with the current leadership state so a follower's
   * observability wire is seeded without polling {@link #isLeader()}.
   *
   * @param listener the listener to register, never null
   */
  @Override
  public void onLeaderChange(final LeaderChangeListener listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    listeners.add(listener);
    if (supervisorThread != null) {
      try {
        listener.onLeaderChange(leader);
      } catch (final Exception e) {
        log.error("Error notifying leader change listener on register", e);
      }
    }
  }

  /**
   * Registers a listener notified each time the supervisor restarts the
   * election loop. The reason string is a short machine-readable tag
   * (e.g. {@code "run_returned"}, {@code "exception"}) suitable for
   * metric tagging.
   *
   * @param listener the listener to register, never null
   */
  public void onElectionRestart(final Consumer<String> listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    restartListener = listener;
  }

  /**
   * Stops the leader election supervisor.
   *
   * <p>Signals the supervisor to exit its loop and interrupts the thread
   * so any blocking {@code run()} or backoff sleep returns. Idempotent.
   */
  @Override
  public void stop() {
    stopping.set(true);
    final Thread thread = supervisorThread;
    if (thread != null) {
      log.info("Stopping K8s leader election supervisor");
      thread.interrupt();
      supervisorThread = null;
      try {
        thread.join(TimeUnit.SECONDS.toMillis(5));
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    relinquishLeadership();
  }

  /**
   * Returns the number of registered leader-change listeners.
   *
   * <p>Exposed for testing.
   *
   * @return the number of registered listeners
   */
  int listenerCount() {
    return listeners.size();
  }

  /**
   * Returns the number of times the supervisor has restarted the election
   * loop.
   *
   * <p>Exposed for testing and diagnostics.
   *
   * @return the restart count
   */
  int restartCount() {
    return restartCount.get();
  }

  /**
   * The body of the supervisor thread. Runs the election loop until
   * {@link #stop()} is called, restarting it after each failure or clean
   * return.
   */
  private void supervisorLoop() {
    boolean firstAttempt = true;
    while (!stopping.get()) {
      String reason = "unknown";
      try {
        final ElectionRunner runner = runnerSupplier.get();
        log.info("Starting K8s leader election for lease '{}'"
            + " in namespace '{}' with identity '{}'",
            config.leaseName(), config.leaseNamespace(),
            config.identity());
        runner.run(this::onStartLeading, this::onStopLeading,
            this::onNewLeader);
        reason = "run_returned";
        log.warn("K8s leader elector run() returned for lease '{}'",
            config.leaseName());
      } catch (final InterruptedException ie) {
        Thread.currentThread().interrupt();
        reason = "interrupted";
        log.info("K8s leader election supervisor interrupted");
      } catch (final Exception e) {
        reason = "exception";
        log.error("K8s leader election thread failed for lease '{}'",
            config.leaseName(), e);
      } finally {
        // First attempt has completed one way or another — release
        // start() so the rest of the boot can proceed even if the first
        // election never resolved cleanly.
        if (firstAttempt) {
          firstElectionLatch.countDown();
          firstAttempt = false;
        }
        // Whether the runner threw, returned, or exited after leading,
        // clear any local leadership state so a leader that lost its
        // renewal cannot keep reporting isLeader() = true while its
        // lease has already expired in Kubernetes (split brain).
        relinquishLeadership();
      }

      if (stopping.get()) {
        break;
      }

      final int attempt = restartCount.incrementAndGet();
      try {
        restartListener.accept(reason);
      } catch (final RuntimeException e) {
        log.warn("Election restart listener threw", e);
      }

      final Duration wait = backoff.nextBackoff(attempt);
      log.warn("Restarting K8s leader election for lease '{}' in {}ms"
          + " (attempt {}, reason={})",
          config.leaseName(), wait.toMillis(), attempt, reason);
      if (!sleepQuietly(wait)) {
        break;
      }
    }
    log.info("K8s leader election supervisor exiting for lease '{}'"
        + " after {} restart(s)", config.leaseName(), restartCount.get());
  }

  /**
   * Sleeps for the given duration, returning {@code false} if the sleep
   * was interrupted (indicating {@link #stop()} was called).
   *
   * @param duration the duration to sleep, never null; a zero or negative
   *                 duration is a no-op
   * @return {@code true} if the sleep completed, {@code false} if
   *         interrupted
   */
  private boolean sleepQuietly(final Duration duration) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      return true;
    }
    try {
      Thread.sleep(duration.toMillis());
      return true;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Creates the production Kubernetes-backed election runner: a fresh
   * {@link ApiClient}, {@link LeaseLock} and {@link LeaderElector} per
   * cycle so a broken client cannot poison subsequent attempts.
   *
   * @return a runner that delegates to a Kubernetes {@link LeaderElector},
   *         never null
   */
  private ElectionRunner createKubernetesRunner() {
    return (onStart, onStop, onNewLeader) -> {
      final ApiClient apiClient = Config.defaultClient();
      final LeaseLock leaseLock = new LeaseLock(
          config.leaseNamespace(),
          config.leaseName(),
          config.identity(),
          apiClient);
      final Duration leaseDuration = config.leaseDuration();
      final Duration renewDeadline =
          leaseDuration.multipliedBy(2).dividedBy(3);
      final Duration retryPeriod = config.renewalInterval();
      final LeaderElectionConfig electionConfig = new LeaderElectionConfig(
          leaseLock, leaseDuration, renewDeadline, retryPeriod);
      final LeaderElector leaderElector = new LeaderElector(electionConfig);
      leaderElector.run(onStart, onStop, onNewLeader);
    };
  }

  /** Called when this node starts leading. */
  private synchronized void onStartLeading() {
    log.info("This node ('{}') is now the leader", config.identity());
    leader = true;
    notifyListeners(true);
  }

  /** Called when this node stops leading. */
  private void onStopLeading() {
    log.info("This node ('{}') lost leadership", config.identity());
    relinquishLeadership();
  }

  /**
   * Atomically clears leadership and notifies listeners, at most once per
   * leadership term.
   */
  private synchronized void relinquishLeadership() {
    if (leader) {
      leader = false;
      notifyListeners(false);
    }
  }

  /**
   * Called when a new leader is elected.
   *
   * <p>Signals the {@link #firstElectionLatch} so that {@link #start()}
   * unblocks once the initial election round is resolved.
   *
   * @param leaderIdentity the identity of the new leader
   */
  private void onNewLeader(final String leaderIdentity) {
    log.info("New leader elected: '{}'", leaderIdentity);
    firstElectionLatch.countDown();
  }

  /**
   * Notifies all registered listeners of a leadership change.
   *
   * @param isLeader whether this node is now the leader
   */
  private void notifyListeners(final boolean isLeader) {
    for (final LeaderChangeListener listener : listeners) {
      try {
        listener.onLeaderChange(isLeader);
      } catch (final Exception e) {
        log.error("Error notifying leader change listener", e);
      }
    }
  }

  /**
   * Creates an exponential backoff policy with jitter, growing from
   * {@code base} to {@code max} and staying at {@code max} thereafter.
   * Jitter is ±20% of the computed value.
   *
   * @param base the base backoff (used at the first restart), never null
   * @param max  the maximum backoff, never null
   * @return a backoff policy, never null
   */
  static BackoffPolicy exponentialBackoff(final Duration base,
      final Duration max) {
    Objects.requireNonNull(base, "base must not be null");
    Objects.requireNonNull(max, "max must not be null");
    return attempt -> {
      final int shift = Math.min(Math.max(attempt - 1, 0), 30);
      long expMs;
      try {
        expMs = Math.multiplyExact(base.toMillis(), 1L << shift);
      } catch (final ArithmeticException overflow) {
        expMs = max.toMillis();
      }
      final long cappedMs = Math.min(expMs, max.toMillis());
      final long jitterMs = (long) (cappedMs * 0.2
          * (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0));
      final long withJitter = Math.max(0L, cappedMs + jitterMs);
      return Duration.ofMillis(withJitter);
    };
  }

  /**
   * Package-private abstraction over the blocking body of the election
   * loop so tests can drive the supervisor without a Kubernetes cluster.
   *
   * <p>In production this delegates to
   * {@link LeaderElector#run(Runnable, Runnable, Consumer)}.
   */
  @FunctionalInterface
  interface ElectionRunner {

    /**
     * Runs one cycle of leader election. May block indefinitely while
     * this node is the leader, may return normally when the underlying
     * election is torn down, and may throw for any transient failure.
     *
     * @param onStartLeading callback invoked when this node acquires
     *                       leadership, never null
     * @param onStopLeading  callback invoked when this node loses
     *                       leadership, never null
     * @param onNewLeader    callback invoked when the cluster learns
     *                       about a new leader (this node or another),
     *                       never null
     *
     * @throws Exception if the underlying elector fails
     */
    void run(Runnable onStartLeading, Runnable onStopLeading,
        Consumer<String> onNewLeader) throws Exception;
  }

  /**
   * Package-private policy for how long the supervisor waits between
   * restarts of the election loop.
   */
  @FunctionalInterface
  interface BackoffPolicy {

    /**
     * Returns the wait before the next restart attempt.
     *
     * @param attempt the 1-based restart attempt number
     * @return a non-negative duration, never null
     */
    Duration nextBackoff(int attempt);
  }
}
