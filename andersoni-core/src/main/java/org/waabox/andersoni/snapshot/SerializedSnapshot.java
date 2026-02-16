package org.waabox.andersoni.snapshot;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable representation of a serialized catalog snapshot.
 *
 * <p>A snapshot captures the state of a catalog at a specific point in
 * time. It includes the raw serialized data along with metadata needed
 * for version ordering and integrity verification.
 *
 * <p>The {@code data} byte array is defensively copied on construction
 * and on access to guarantee immutability.
 *
 * @param catalogName the name of the catalog this snapshot belongs to,
 *                    never null
 * @param hash        a content hash (e.g. SHA-256) for integrity
 *                    verification, never null
 * @param version     a monotonically increasing version number
 * @param createdAt   the instant at which this snapshot was created,
 *                    never null
 * @param data        the raw serialized catalog data, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record SerializedSnapshot(
    String catalogName,
    String hash,
    long version,
    Instant createdAt,
    byte[] data
) {

  /**
   * Compact constructor that defensively copies the byte array and
   * validates required fields.
   */
  public SerializedSnapshot {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(hash, "hash must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    Objects.requireNonNull(data, "data must not be null");
    data = data.clone();
  }

  /**
   * Returns a defensive copy of the serialized data.
   *
   * @return a copy of the data byte array, never null
   */
  @Override
  public byte[] data() {
    return data.clone();
  }

  /**
   * Compares this snapshot to another using content equality for the
   * byte array rather than reference identity.
   *
   * @param o the object to compare with
   * @return true if equal by content, false otherwise
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SerializedSnapshot that)) {
      return false;
    }
    return version == that.version
        && Objects.equals(catalogName, that.catalogName)
        && Objects.equals(hash, that.hash)
        && Objects.equals(createdAt, that.createdAt)
        && Arrays.equals(data, that.data);
  }

  /**
   * Returns a hash code using content-based hashing for the byte array.
   *
   * @return the hash code
   */
  @Override
  public int hashCode() {
    int result = Objects.hash(catalogName, hash, version, createdAt);
    result = 31 * result + Arrays.hashCode(data);
    return result;
  }
}
