package org.waabox.andersoni.it;

import java.util.concurrent.CopyOnWriteArrayList;

import org.waabox.andersoni.leader.LeaderChangeListener;
import org.waabox.andersoni.leader.LeaderElectionStrategy;

/**
 * A leader election strategy whose leadership can be flipped on demand
 * through the node's HTTP API.
 *
 * <p>Used by the cluster integration test to simulate a leader promotion
 * (for instance after the original leader dies) without depending on
 * Kubernetes or any external coordination service. Leadership starts at a
 * fixed value and only changes when {@link #become(boolean)} is called,
 * notifying every registered listener when the value actually flips.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class ControllableLeaderElection implements LeaderElectionStrategy {

  /** Whether this node is currently the leader. */
  private volatile boolean leader;

  /** The registered leadership change listeners. */
  private final CopyOnWriteArrayList<LeaderChangeListener> listeners =
      new CopyOnWriteArrayList<>();

  /**
   * Creates a controllable leader election with an initial leadership value.
   *
   * @param initiallyLeader {@code true} if this node starts out as the leader.
   */
  public ControllableLeaderElection(final boolean initiallyLeader) {
    leader = initiallyLeader;
  }

  /**
   * Sets this node's leadership status, notifying every registered listener
   * when the value actually changes.
   *
   * @param isLeader {@code true} to make this node the leader.
   */
  public void become(final boolean isLeader) {
    final boolean changed = leader != isLeader;
    leader = isLeader;
    if (changed) {
      for (final LeaderChangeListener listener : listeners) {
        listener.onLeaderChange(isLeader);
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public void start() {
    // Nothing to start: leadership is driven externally via become().
  }

  /** {@inheritDoc} */
  @Override
  public boolean isLeader() {
    return leader;
  }

  /** {@inheritDoc} */
  @Override
  public void onLeaderChange(final LeaderChangeListener listener) {
    listeners.add(listener);
  }

  /** {@inheritDoc} */
  @Override
  public void stop() {
    // Nothing to stop.
  }
}
