package org.waabox.andersoni.leader.k8s;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * background daemon thread runs the {@link LeaderElector}, which handles
 * acquiring, renewing, and losing the lease automatically.
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

  /** The configuration for the Kubernetes lease. */
  private final K8sLeaseConfig config;

  /** The list of registered leader change listeners. */
  private final List<LeaderChangeListener> listeners =
      new CopyOnWriteArrayList<>();

  /** The current leader status, volatile for cross-thread visibility. */
  private volatile boolean leader = false;

  /** The background thread running the leader elector. */
  private volatile Thread electionThread;

  /**
   * Creates a new Kubernetes lease-based leader election strategy.
   *
   * @param theConfig the Kubernetes lease configuration, never null
   */
  public K8sLeaseLeaderElection(final K8sLeaseConfig theConfig) {
    config = Objects.requireNonNull(theConfig,
        "config must not be null");
  }

  /**
   * Starts the leader election process.
   *
   * <p>Creates an in-cluster Kubernetes API client, configures a
   * {@link LeaseLock} with the parameters from the config, and launches
   * a {@link LeaderElector} in a daemon thread. The elector will
   * continuously attempt to acquire or renew the lease.
   *
   * @throws AndersoniException if the Kubernetes API client cannot be
   *                             initialized
   */
  @Override
  public void start() {
    try {
      final ApiClient apiClient = Config.defaultClient();

      final LeaseLock leaseLock = new LeaseLock(
          config.leaseNamespace(),
          config.leaseName(),
          config.identity(),
          apiClient);

      final Duration leaseDuration = config.leaseDuration();
      final Duration renewDeadline = leaseDuration.multipliedBy(2)
          .dividedBy(3);
      final Duration retryPeriod = config.renewalInterval();

      final LeaderElectionConfig electionConfig =
          new LeaderElectionConfig(
              leaseLock,
              leaseDuration,
              renewDeadline,
              retryPeriod);

      final LeaderElector leaderElector = new LeaderElector(
          electionConfig);

      final Thread thread = new Thread(() -> {
        log.info("Starting K8s leader election for lease '{}'"
            + " in namespace '{}' with identity '{}'",
            config.leaseName(), config.leaseNamespace(),
            config.identity());
        leaderElector.run(
            this::onStartLeading,
            this::onStopLeading,
            this::onNewLeader);
      }, "k8s-leader-election");

      thread.setDaemon(true);
      thread.start();
      electionThread = thread;

      log.info("K8s leader election thread started");

    } catch (final Exception e) {
      throw new AndersoniException(
          "Failed to start K8s leader election", e);
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
   * @param listener the listener to register, never null
   */
  @Override
  public void onLeaderChange(final LeaderChangeListener listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    listeners.add(listener);
  }

  /**
   * Stops the leader election process.
   *
   * <p>Interrupts the background election thread, which causes the
   * {@link LeaderElector} to stop renewing the lease and effectively
   * release leadership.
   */
  @Override
  public void stop() {
    final Thread thread = electionThread;
    if (thread != null) {
      log.info("Stopping K8s leader election");
      thread.interrupt();
      electionThread = null;
    }
  }

  /**
   * Returns the number of registered listeners.
   *
   * <p>This method exists for testing purposes to verify that listeners
   * are correctly registered.
   *
   * @return the number of registered listeners
   */
  int listenerCount() {
    return listeners.size();
  }

  /** Called when this node starts leading. */
  private void onStartLeading() {
    log.info("This node ('{}') is now the leader", config.identity());
    leader = true;
    notifyListeners(true);
  }

  /** Called when this node stops leading. */
  private void onStopLeading() {
    log.info("This node ('{}') lost leadership", config.identity());
    leader = false;
    notifyListeners(false);
  }

  /**
   * Called when a new leader is elected.
   *
   * @param leaderIdentity the identity of the new leader
   */
  private void onNewLeader(final String leaderIdentity) {
    log.info("New leader elected: '{}'", leaderIdentity);
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
}
