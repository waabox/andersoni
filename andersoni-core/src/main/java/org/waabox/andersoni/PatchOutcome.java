package org.waabox.andersoni;

/**
 * What a cluster-coordinated patch call did.
 *
 * <p>These operations run only on the leader, and their lookups can match
 * nothing, so "it returned without applying" has two unrelated causes that a
 * caller needs to tell apart. On a five-pod cluster, four pods see
 * {@link #NOT_LEADER} on every call and should ignore it; {@link #NOT_FOUND}
 * usually means a wrong key, a missed create, or stale upstream data, and is
 * worth logging. Collapsing both into {@code false} forces a caller to alert
 * on every follower or on neither.
 *
 * <p>The local, uncoordinated operations — {@link Catalog#replace},
 * {@link Andersoni#replace} and their siblings — keep returning
 * {@code boolean}, because without a leader question only found and
 * not-found are possible there.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public enum PatchOutcome {

  /** This node is the leader and the change was applied and broadcast. */
  APPLIED,

  /**
   * This node is the leader, but the operation changed nothing: a lookup
   * matched no item, or a batch's operations all matched nothing. Never
   * returned by an insert, which performs no lookup, or by an upsert,
   * which writes either way.
   */
  NOT_FOUND,

  /**
   * This node is not the leader, so it did not attempt the change. The
   * leader owns the write and will broadcast it.
   */
  NOT_LEADER;

  /**
   * Returns whether the change was applied, for call sites that only need
   * to know that much.
   *
   * @return true if this outcome is {@link #APPLIED}
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public boolean applied() {
    return this == APPLIED;
  }
}
