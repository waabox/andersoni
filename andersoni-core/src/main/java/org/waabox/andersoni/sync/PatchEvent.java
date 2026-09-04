package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a single-item surgical patch broadcast across nodes after a
 * leader-side {@code replaceAndSync}.
 *
 * <p>A patch event carries enough information for a follower to apply the
 * exact same swap that the leader applied, with {@link #fromHash} and
 * {@link #toHash} as optimistic-concurrency preconditions so the follower
 * can detect that it has already applied the patch, or that its local state
 * has drifted and it must fall back to a full reload from the snapshot
 * store.
 *
 * <p>{@link #fromVersion} and {@link #toVersion} are diagnostic only —
 * never preconditions. A snapshot version is a per-node counter that starts
 * at zero in every process and is never adopted from another node, so two
 * nodes holding identical data may report different versions. Hashes are
 * derived from content and are comparable across nodes.
 *
 * <p>The {@code serializedOldItem} and {@code serializedNewItem} payloads
 * are produced by the catalog's {@code SnapshotSerializer}. The follower
 * deserializes them and re-derives the lookup key locally by feeding
 * {@code oldItem} into the catalog's own index definition for
 * {@link #indexName} — so the lookup key itself does not travel over the
 * wire.
 *
 * @param catalogName        the name of the catalog being patched,
 *                           never null
 * @param sourceNodeId       the identifier of the node that produced the
 *                           patch, never null
 * @param fromVersion        the leader's snapshot version the patch was
 *                           computed against; diagnostic only
 * @param toVersion          the resulting snapshot version on the leader;
 *                           diagnostic only
 * @param fromHash           the leader's snapshot hash before the patch;
 *                           the precondition for applying surgically,
 *                           never null
 * @param toHash             the leader's snapshot hash after the patch;
 *                           a follower already at this hash has applied
 *                           the patch, never null
 * @param indexName          the index used for the lookup, never null
 * @param serializedOldItem  the serialized item being replaced, never null
 * @param serializedNewItem  the serialized replacement item, never null
 * @param timestamp          the instant of the patch, never null
 */
public record PatchEvent(
    String catalogName,
    String sourceNodeId,
    long fromVersion,
    long toVersion,
    String fromHash,
    String toHash,
    String indexName,
    byte[] serializedOldItem,
    byte[] serializedNewItem,
    Instant timestamp
) implements SyncMessage {

  /**
   * Canonical constructor with null checks. Byte arrays are stored by
   * reference (consistent with record semantics — callers must not mutate
   * after construction).
   */
  public PatchEvent {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
    Objects.requireNonNull(fromHash, "fromHash must not be null");
    Objects.requireNonNull(toHash, "toHash must not be null");
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(serializedOldItem,
        "serializedOldItem must not be null");
    Objects.requireNonNull(serializedNewItem,
        "serializedNewItem must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
  }
}
