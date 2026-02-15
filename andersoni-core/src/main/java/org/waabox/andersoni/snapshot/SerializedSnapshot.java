package org.waabox.andersoni.snapshot;

import java.time.Instant;

/**
 * An immutable representation of a serialized catalog snapshot.
 *
 * <p>A snapshot captures the state of a catalog at a specific point in
 * time. It includes the raw serialized data along with metadata needed
 * for version ordering and integrity verification.
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
}
