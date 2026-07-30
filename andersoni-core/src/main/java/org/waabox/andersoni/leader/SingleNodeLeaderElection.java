package org.waabox.andersoni.leader;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A leader election strategy for single-node deployments.
 *
 * <p>This implementation always considers the current node as the leader.
 * It is the default strategy used when no distributed leader election
 * mechanism is configured, which is typical for development environments
 * or single-instance production deployments.
 *
 * <p>On {@link #start()}, all registered listeners are notified with
 * {@code isLeader = true}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class SingleNodeLeaderElection implements LeaderElectionStrategy {

  /** The list of registered leader change listeners. */
  private final List<LeaderChangeListener> listeners =
      new CopyOnWriteArrayList<>();

  /** Whether {@link #start()} has been invoked; controls whether
   * post-start listener registrations receive an immediate notification. */
  private volatile boolean started;

  /** {@inheritDoc} */
  @Override
  public void start() {
    started = true;
    for (final LeaderChangeListener listener : listeners) {
      listener.onLeaderChange(true);
    }
  }

  /**
   * Always returns {@code true} since a single node is always the leader.
   *
   * @return {@code true}, always
   */
  @Override
  public boolean isLeader() {
    return true;
  }

  /** {@inheritDoc} */
  @Override
  public void onLeaderChange(final LeaderChangeListener listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    listeners.add(listener);
    // Fire immediately only when registration happens after start(), so
    // callers seeding a gauge see the initial state without polling
    // isLeader(). Pre-start registrations are still notified by start().
    if (started) {
      listener.onLeaderChange(true);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void stop() {
    started = false;
  }
}
