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

  /** {@inheritDoc} */
  @Override
  public void start() {
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
  }

  /** {@inheritDoc} */
  @Override
  public void stop() {
    // Nothing to release in a single-node deployment.
  }
}
