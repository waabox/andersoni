package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Objects;

import org.waabox.andersoni.PatchOperation;

/** A synchronization event representing a granular patch operation.
 *
 * <p>The {@code payload} contains serialized items (always a {@code List<T>})
 * using the catalog's {@code SnapshotSerializer}.
 *
 * @param catalogName   the name of the catalog, never null
 * @param sourceNodeId  the node that originated this patch, never null
 * @param version       the snapshot version after the patch was applied
 * @param operationType the type of patch operation, never null
 * @param payload       the serialized items, never null
 * @param timestamp     the instant at which the patch occurred, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record PatchEvent(
    String catalogName,
    String sourceNodeId,
    long version,
    PatchOperation operationType,
    byte[] payload,
    Instant timestamp
) implements SyncEvent {

  /** Compact constructor with null checks. */
  public PatchEvent {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
    Objects.requireNonNull(operationType, "operationType must not be null");
    Objects.requireNonNull(payload, "payload must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
  }

  /** {@inheritDoc} */
  @Override
  public void accept(final SyncEventHandler handler) {
    handler.onPatch(this);
  }
}
