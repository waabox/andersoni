package org.waabox.andersoni.leader;

/**
 * A strategy for electing a leader among a set of nodes.
 *
 * <p>The leader is responsible for initiating catalog refreshes and
 * publishing refresh events so follower nodes can synchronize. Only one
 * node in the cluster should be the leader at any given time.
 *
 * <p>Implementations can use different mechanisms for leader election
 * (e.g. Kubernetes lease, database lock, single-node always-leader).
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface LeaderElectionStrategy {

  /**
   * Starts the leader election process.
   *
   * <p>After this method returns, the node participates in leader
   * election and registered listeners may be notified of leadership
   * changes.
   */
  void start();

  /**
   * Returns whether this node is currently the leader.
   *
   * @return {@code true} if this node holds the leadership lease
   */
  boolean isLeader();

  /**
   * Registers a listener to be notified when leadership status changes.
   *
   * @param listener the listener to register, never null
   */
  void onLeaderChange(LeaderChangeListener listener);

  /**
   * Stops the leader election process and releases all resources.
   *
   * <p>After this method returns, this node no longer participates in
   * leader election.
   */
  void stop();
}
