package org.waabox.andersoni.sync;

/**
 * The kind of change a {@link PatchEvent} carries.
 *
 * <p>There is no {@code UPSERT}: an upsert resolves on the leader, against
 * the leader's own snapshot, into whichever concrete change actually
 * happened. Followers are told what was done, not what was asked for, so
 * they never have to repeat a decision that depends on state they may not
 * share.
 *
 * @author borafael
 */
public enum PatchOperation {

  /** One item was exchanged for another. Carries both items. */
  REPLACE,

  /** An item was added, displacing nothing. Carries only the new item. */
  INSERT,

  /** An item was dropped. Carries only the item that was removed. */
  REMOVE
}
