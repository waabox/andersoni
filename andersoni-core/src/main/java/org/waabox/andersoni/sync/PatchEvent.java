package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A surgical patch broadcast across nodes after a leader-side patch
 * operation or batch.
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
 * <p>An event carries one or more {@link Op operations}, applied in order
 * as a unit. A single patch is a list of one; a batch is the same event
 * with more entries, so followers, transports and the codec have one shape
 * to handle rather than two.
 *
 * <p>Which payloads an operation carries depends on its kind:
 *
 * <table border="1">
 *   <caption>Payloads by operation</caption>
 *   <tr><th>operation</th><th>indexName</th><th>oldItem</th><th>newItem</th></tr>
 *   <tr><td>{@code REPLACE}</td><td>yes</td><td>yes</td><td>yes</td></tr>
 *   <tr><td>{@code INSERT}</td><td>no</td><td>no</td><td>yes</td></tr>
 *   <tr><td>{@code REMOVE}</td><td>yes</td><td>yes</td><td>no</td></tr>
 * </table>
 *
 * <p>The item payloads are produced by the catalog's
 * {@code SnapshotSerializer}. Where an operation needs a lookup, the
 * follower deserializes {@code oldItem} and re-derives the key by feeding
 * it through the catalog's own index definition for {@code indexName} — so
 * the lookup key itself never travels, and index keys are under no
 * constraint to be wire-encodable.
 *
 * @param catalogName  the name of the catalog being patched, never null
 * @param sourceNodeId the identifier of the node that produced the patch,
 *                     never null
 * @param fromVersion  the leader's snapshot version the patch was computed
 *                     against; diagnostic only
 * @param toVersion    the resulting snapshot version on the leader;
 *                     diagnostic only
 * @param fromHash     the leader's snapshot hash before the patch; the
 *                     precondition for applying surgically, never null
 * @param toHash       the leader's snapshot hash after the patch; a
 *                     follower already at this hash has applied it,
 *                     never null
 * @param operations   the changes to apply in order, never null or empty
 * @param timestamp    the instant of the patch, never null
 *
 * @author borafael
 */
public record PatchEvent(
    String catalogName,
    String sourceNodeId,
    long fromVersion,
    long toVersion,
    String fromHash,
    String toHash,
    List<Op> operations,
    Instant timestamp
) implements SyncMessage {

  /**
   * One change within a patch event.
   *
   * @param operation the kind of change, never null
   * @param indexName the index used for the lookup; null for an insert
   * @param oldItem   the serialized item leaving the snapshot; null for an
   *                  insert
   * @param newItem   the serialized item entering the snapshot; null for a
   *                  removal
   */
  public record Op(PatchOperation operation, String indexName,
      byte[] oldItem, byte[] newItem) {

    /** Canonical constructor validating what each operation requires. */
    public Op {
      Objects.requireNonNull(operation, "operation must not be null");
      switch (operation) {
        case REPLACE -> {
          Objects.requireNonNull(indexName,
              "indexName must not be null for a REPLACE");
          Objects.requireNonNull(oldItem,
              "oldItem must not be null for a REPLACE");
          Objects.requireNonNull(newItem,
              "newItem must not be null for a REPLACE");
        }
        case INSERT -> Objects.requireNonNull(newItem,
            "newItem must not be null for an INSERT");
        case REMOVE -> {
          Objects.requireNonNull(indexName,
              "indexName must not be null for a REMOVE");
          Objects.requireNonNull(oldItem,
              "oldItem must not be null for a REMOVE");
        }
        default -> throw new IllegalArgumentException(
            "Unhandled patch operation: " + operation);
      }
    }
  }

  /** Canonical constructor validating mandatory fields. */
  public PatchEvent {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
    Objects.requireNonNull(fromHash, "fromHash must not be null");
    Objects.requireNonNull(toHash, "toHash must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
    Objects.requireNonNull(operations, "operations must not be null");
    if (operations.isEmpty()) {
      throw new IllegalArgumentException(
          "A patch event must carry at least one operation");
    }
    operations = List.copyOf(operations);
  }

  /**
   * Builds an event carrying a single {@link PatchOperation#REPLACE}.
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
        fromHash, toHash,
        List.of(new Op(PatchOperation.REPLACE, indexName, oldItem, newItem)),
        timestamp);
  }

  /**
   * Builds an event carrying a single {@link PatchOperation#INSERT}.
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
        fromHash, toHash,
        List.of(new Op(PatchOperation.INSERT, null, null, newItem)),
        timestamp);
  }

  /**
   * Builds an event carrying a single {@link PatchOperation#REMOVE}.
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
        fromHash, toHash,
        List.of(new Op(PatchOperation.REMOVE, indexName, oldItem, null)),
        timestamp);
  }

  /**
   * Builds an event carrying a batch of operations.
   *
   * @param catalogName  the catalog name, never null.
   * @param sourceNodeId the originating node id, never null.
   * @param fromVersion  the leader's version before the batch.
   * @param toVersion    the leader's version after the batch.
   * @param fromHash     the leader's hash before the batch, never null.
   * @param toHash       the leader's hash after the batch, never null.
   * @param operations   the operations in order, never null or empty.
   * @param timestamp    the creation instant, never null.
   *
   * @return the event, never null.
   */
  public static PatchEvent batch(final String catalogName,
      final String sourceNodeId, final long fromVersion,
      final long toVersion, final String fromHash, final String toHash,
      final List<Op> operations, final Instant timestamp) {
    return new PatchEvent(catalogName, sourceNodeId, fromVersion, toVersion,
        fromHash, toHash, operations, timestamp);
  }
}
