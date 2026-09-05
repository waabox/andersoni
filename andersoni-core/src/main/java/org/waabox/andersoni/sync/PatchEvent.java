package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Objects;

/**
 * A single-item surgical patch broadcast across nodes after a leader-side
 * patch operation.
 *
 * <p>A patch event carries enough information for a follower to apply the
 * exact same change the leader applied, with {@link #fromHash} and
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
 * <p>Which payloads are present depends on {@link #operation}:
 *
 * <table border="1">
 *   <caption>Payloads by operation</caption>
 *   <tr><th>operation</th><th>indexName</th><th>oldItem</th><th>newItem</th></tr>
 *   <tr><td>{@code REPLACE}</td><td>yes</td><td>yes</td><td>yes</td></tr>
 *   <tr><td>{@code INSERT}</td><td>no</td><td>no</td><td>yes</td></tr>
 *   <tr><td>{@code REMOVE}</td><td>yes</td><td>yes</td><td>no</td></tr>
 * </table>
 *
 * <p>The {@code serializedOldItem} and {@code serializedNewItem} payloads
 * are produced by the catalog's {@code SnapshotSerializer}. Where an
 * operation needs a lookup, the follower deserializes {@code oldItem} and
 * re-derives the key by feeding it through the catalog's own index
 * definition for {@link #indexName} — so the lookup key itself never
 * travels, and index keys are under no constraint to be wire-encodable.
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
 * @param operation          the kind of change, never null
 * @param indexName          the index used for the lookup; null for an
 *                           insert, which performs none
 * @param serializedOldItem  the serialized item leaving the snapshot;
 *                           null for an insert
 * @param serializedNewItem  the serialized item entering the snapshot;
 *                           null for a removal
 * @param timestamp          the instant of the patch, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record PatchEvent(
    String catalogName,
    String sourceNodeId,
    long fromVersion,
    long toVersion,
    String fromHash,
    String toHash,
    PatchOperation operation,
    String indexName,
    byte[] serializedOldItem,
    byte[] serializedNewItem,
    Instant timestamp
) implements SyncMessage {

  /**
   * Canonical constructor. Validates the fields every operation needs, then
   * the ones its own operation requires. Byte arrays are stored by
   * reference (consistent with record semantics — callers must not mutate
   * after construction).
   */
  public PatchEvent {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
    Objects.requireNonNull(fromHash, "fromHash must not be null");
    Objects.requireNonNull(toHash, "toHash must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");

    switch (operation) {
      case REPLACE -> {
        Objects.requireNonNull(indexName,
            "indexName must not be null for a REPLACE");
        Objects.requireNonNull(serializedOldItem,
            "serializedOldItem must not be null for a REPLACE");
        Objects.requireNonNull(serializedNewItem,
            "serializedNewItem must not be null for a REPLACE");
      }
      case INSERT -> Objects.requireNonNull(serializedNewItem,
          "serializedNewItem must not be null for an INSERT");
      case REMOVE -> {
        Objects.requireNonNull(indexName,
            "indexName must not be null for a REMOVE");
        Objects.requireNonNull(serializedOldItem,
            "serializedOldItem must not be null for a REMOVE");
      }
      default -> throw new IllegalArgumentException(
          "Unhandled patch operation: " + operation);
    }
  }

  /**
   * Builds a {@link PatchOperation#REPLACE} event.
   *
   * @param catalogName  the catalog name, never null.
   * @param sourceNodeId the originating node id, never null.
   * @param fromVersion  the leader's version before the patch.
   * @param toVersion    the leader's version after the patch.
   * @param fromHash     the leader's hash before the patch, never null.
   * @param toHash       the leader's hash after the patch, never null.
   * @param indexName    the index used for the lookup, never null.
   * @param oldItem      the serialized replaced item, never null.
   * @param newItem      the serialized replacement, never null.
   * @param timestamp    the creation instant, never null.
   *
   * @return the event, never null.
   */
  public static PatchEvent replace(final String catalogName,
      final String sourceNodeId, final long fromVersion,
      final long toVersion, final String fromHash, final String toHash,
      final String indexName, final byte[] oldItem, final byte[] newItem,
      final Instant timestamp) {
    return new PatchEvent(catalogName, sourceNodeId, fromVersion, toVersion,
        fromHash, toHash, PatchOperation.REPLACE, indexName, oldItem,
        newItem, timestamp);
  }

  /**
   * Builds a {@link PatchOperation#INSERT} event.
   *
   * @param catalogName  the catalog name, never null.
   * @param sourceNodeId the originating node id, never null.
   * @param fromVersion  the leader's version before the patch.
   * @param toVersion    the leader's version after the patch.
   * @param fromHash     the leader's hash before the patch, never null.
   * @param toHash       the leader's hash after the patch, never null.
   * @param newItem      the serialized added item, never null.
   * @param timestamp    the creation instant, never null.
   *
   * @return the event, never null.
   */
  public static PatchEvent insert(final String catalogName,
      final String sourceNodeId, final long fromVersion,
      final long toVersion, final String fromHash, final String toHash,
      final byte[] newItem, final Instant timestamp) {
    return new PatchEvent(catalogName, sourceNodeId, fromVersion, toVersion,
        fromHash, toHash, PatchOperation.INSERT, null, null, newItem,
        timestamp);
  }

  /**
   * Builds a {@link PatchOperation#REMOVE} event.
   *
   * @param catalogName  the catalog name, never null.
   * @param sourceNodeId the originating node id, never null.
   * @param fromVersion  the leader's version before the patch.
   * @param toVersion    the leader's version after the patch.
   * @param fromHash     the leader's hash before the patch, never null.
   * @param toHash       the leader's hash after the patch, never null.
   * @param indexName    the index used for the lookup, never null.
   * @param oldItem      the serialized removed item, never null.
   * @param timestamp    the creation instant, never null.
   *
   * @return the event, never null.
   */
  public static PatchEvent remove(final String catalogName,
      final String sourceNodeId, final long fromVersion,
      final long toVersion, final String fromHash, final String toHash,
      final String indexName, final byte[] oldItem,
      final Instant timestamp) {
    return new PatchEvent(catalogName, sourceNodeId, fromVersion, toVersion,
        fromHash, toHash, PatchOperation.REMOVE, indexName, oldItem, null,
        timestamp);
  }
}
