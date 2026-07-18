package org.waabox.andersoni.it;

import org.waabox.andersoni.leader.LeaderChangeListener;
import org.waabox.andersoni.leader.LeaderElectionStrategy;

/**
 * A leader election strategy whose leadership is fixed at construction time.
 *
 * <p>Used by the cluster integration test to make exactly one node the leader
 * deterministically (driven by an environment variable), without depending on
 * Kubernetes or any external coordination service. Leadership never changes,
 * so registered listeners are never invoked.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class StaticLeaderElection implements LeaderElectionStrategy {

  /** Whether this node is the leader; fixed for the node's lifetime. */
  private final boolean leader;

  /**
   * Creates a static leader election.
   *
   * @param theLeader {@code true} if this node is the leader.
   */
  public StaticLeaderElection(final boolean theLeader) {
    leader = theLeader;
  }

  /** {@inheritDoc} */
  @Override
  public void start() {
    // Nothing to start: leadership is fixed.
  }

  /** {@inheritDoc} */
  @Override
  public boolean isLeader() {
    return leader;
  }

  /** {@inheritDoc} */
  @Override
  public void onLeaderChange(final LeaderChangeListener listener) {
    // Leadership is fixed, so the listener is never notified.
  }

  /** {@inheritDoc} */
  @Override
  public void stop() {
    // Nothing to stop.
  }
}
