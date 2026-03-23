package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a catalog refresh event that is broadcast across nodes.
 *
 * <p>When a catalog is refreshed on one node, a {@code RefreshEvent} is
 * published so that other nodes in the cluster can synchronize their local
 * caches. The event carries enough metadata for receivers to decide whether
 * they need to reload their catalog (version comparison, hash verification).
 *
 * @param catalogName   the name of the catalog that was refreshed, never null
 * @param sourceNodeId  the identifier of the node that originated the refresh,
 *                      never null
 * @param version       a monotonically increasing version number for ordering
 * @param hash          a content hash (e.g. SHA-256) of the refreshed data,
 *                      used for integrity verification, never null
 * @param timestamp     the instant at which the refresh occurred, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record RefreshEvent(
    String catalogName,
    String sourceNodeId,
    long version,
    String hash,
    Instant timestamp
) implements SyncEvent {

  /** Compact constructor with null checks. */
  public RefreshEvent {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
    Objects.requireNonNull(hash, "hash must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
  }

  /** {@inheritDoc} */
  @Override
  public void accept(final SyncEventHandler handler) {
    handler.onRefresh(this);
  }
}
