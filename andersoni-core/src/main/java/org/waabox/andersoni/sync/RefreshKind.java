package org.waabox.andersoni.sync;

/**
 * Discriminates the two kinds of message that travel over the
 * synchronization channel.
 *
 * <p>The distinction exists to keep the propagation graph acyclic in
 * multi-node deployments: a {@link #REQUEST} is a command that only the
 * leader acts upon (by performing a real refresh), while an {@link #EVENT}
 * is the result notification that followers consume to reload their local
 * caches. No message kind, when handled, produces another message of the
 * same kind, so no infinite loop is possible.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public enum RefreshKind {

  /**
   * A result notification: a catalog was refreshed and receivers should
   * reload their local cache. Published by the leader, consumed by
   * followers, which never re-publish.
   */
  EVENT,

  /**
   * A command asking the cluster to refresh a catalog. Published by a node
   * that received a {@code refreshAndSync} call while not being the leader.
   * Only the leader acts upon it; followers ignore it, so it is never
   * re-emitted.
   */
  REQUEST
}
