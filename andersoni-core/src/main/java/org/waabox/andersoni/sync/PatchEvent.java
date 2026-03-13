package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Objects;

/**
 * An event carrying a patch (add or remove) to be applied to a catalog
 * snapshot.
 *
 * <p>The {@link #items()} field contains the affected items serialized via
 * the catalog's {@link org.waabox.andersoni.snapshot.SnapshotSerializer},
 * always as a list (even for single-item patches).
 *
 * <p><strong>Note:</strong> This record contains a {@code byte[]} field,
 * so the generated {@code equals()} and {@code hashCode()} use identity
 * comparison for that field. This is acceptable because {@code PatchEvent}
 * instances are not expected to be compared or stored in collections.
 *
 * @param catalogName   the catalog this patch targets, never null
 * @param sourceNodeId  the node that originated this patch, never null
 * @param version       the snapshot version after the patch
 * @param hash          the snapshot hash after the patch, never null
 * @param timestamp     the instant this event was created, never null
 * @param patchType     ADD or REMOVE, never null
 * @param items         the serialized items, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record PatchEvent(
    String catalogName,
    String sourceNodeId,
    long version,
    String hash,
    Instant timestamp,
    PatchType patchType,
    byte[] items
) implements SyncEvent {

  /** Validates all parameters are non-null. */
  public PatchEvent {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
    Objects.requireNonNull(hash, "hash must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
    Objects.requireNonNull(patchType, "patchType must not be null");
    Objects.requireNonNull(items, "items must not be null");
  }

  /** {@inheritDoc} */
  @Override
  public void accept(final SyncEventVisitor visitor) {
    Objects.requireNonNull(visitor, "visitor must not be null");
    visitor.visit(this);
  }
}
