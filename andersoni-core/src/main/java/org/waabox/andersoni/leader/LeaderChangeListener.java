package org.waabox.andersoni.leader;

/**
 * A listener that is notified when the leadership status of this node
 * changes.
 *
 * <p>Implementations typically trigger or cancel catalog refresh
 * operations based on whether this node has become the leader or has
 * lost leadership.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@FunctionalInterface
public interface LeaderChangeListener {

  /**
   * Called when the leadership status of this node changes.
   *
   * @param isLeader {@code true} if this node has become the leader,
   *                 {@code false} if it has lost leadership
   */
  void onLeaderChange(boolean isLeader);
}
