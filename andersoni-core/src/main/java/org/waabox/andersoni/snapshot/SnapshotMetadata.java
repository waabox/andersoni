package org.waabox.andersoni.snapshot;

import java.time.Instant;
import java.util.Objects;

/**
 * The metadata of a stored snapshot: everything a {@link SerializedSnapshot}
 * carries except the serialized bytes.
 *
 * <p>Returned by {@link SnapshotStore#describe(String)} so a node can compare
 * the store's content hash with its own without downloading the snapshot.
 *
 * @param catalogName the catalog the snapshot belongs to, never null
 * @param hash        the content hash of the stored bytes, never null
 * @param version     the snapshot version recorded by the writing node
 * @param createdAt   the instant the snapshot was created, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record SnapshotMetadata(
    String catalogName,
    String hash,
    long version,
    Instant createdAt) {

  /**
   * Canonical constructor validating inputs.
   *
   * @param catalogName the catalog name, never null.
   * @param hash        the content hash, never null.
   * @param version     the snapshot version.
   * @param createdAt   the creation instant, never null.
   */
  public SnapshotMetadata {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(hash, "hash must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  /**
   * Extracts the metadata of a serialized snapshot.
   *
   * @param snapshot the snapshot to describe, never null
   *
   * @return the metadata, never null
   */
  public static SnapshotMetadata of(final SerializedSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    return new SnapshotMetadata(snapshot.catalogName(), snapshot.hash(),
        snapshot.version(), snapshot.createdAt());
  }
}
