package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a message that is broadcast across nodes over the
 * synchronization channel.
 *
 * <p>A message is either an {@link RefreshKind#EVENT} (the default) or a
 * {@link RefreshKind#REQUEST}:
 * <ul>
 *   <li>An {@code EVENT} is published after a catalog is refreshed on the
 *       leader so that other nodes can synchronize their local caches. It
 *       carries enough metadata for receivers to decide whether they need
 *       to reload (version comparison, hash verification).</li>
 *   <li>A {@code REQUEST} is a command published by a non-leader node that
 *       received a {@code refreshAndSync} call. Only the leader acts upon
 *       it. For a request, {@code version} is {@code 0} and {@code hash} is
 *       empty, as they carry no meaning until the refresh actually happens.</li>
 * </ul>
 *
 * <p><strong>Rolling-upgrade note:</strong> {@code REQUEST} was introduced in
 * 1.10. A node running an older version decodes a request as a plain event
 * (unknown kinds default to {@code EVENT}) with an empty hash, and may perform
 * one spurious refresh. This is transient and never loops, but to avoid it,
 * upgrade all nodes to 1.10+ before relying on follower-initiated refresh.
 *
 * @param catalogName   the name of the catalog the message refers to,
 *                      never null
 * @param sourceNodeId  the identifier of the node that originated the
 *                      message, never null
 * @param version       a monotonically increasing version number for
 *                      ordering; {@code 0} for requests
 * @param hash          a content hash (e.g. SHA-256) of the refreshed data,
 *                      used for integrity verification; empty for requests,
 *                      never null
 * @param timestamp     the instant at which the message was created,
 *                      never null
 * @param kind          the message kind, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record RefreshEvent(
    String catalogName,
    String sourceNodeId,
    long version,
    String hash,
    Instant timestamp,
    RefreshKind kind
) implements SyncMessage {

  /**
   * Canonical constructor validating mandatory fields.
   *
   * @param catalogName  the catalog name, never null.
   * @param sourceNodeId the originating node id, never null.
   * @param version      the version number.
   * @param hash         the content hash, never null.
   * @param timestamp    the creation instant, never null.
   * @param kind         the message kind, never null.
   */
  public RefreshEvent {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
    Objects.requireNonNull(hash, "hash must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
  }

  /**
   * Convenience constructor that builds an {@link RefreshKind#EVENT}
   * message. Kept so existing call sites that predate the message kind
   * discriminator continue to compile unchanged.
   *
   * @param catalogName  the catalog name, never null.
   * @param sourceNodeId the originating node id, never null.
   * @param version      the version number.
   * @param hash         the content hash, never null.
   * @param timestamp    the creation instant, never null.
   */
  public RefreshEvent(final String catalogName, final String sourceNodeId,
      final long version, final String hash, final Instant timestamp) {
    this(catalogName, sourceNodeId, version, hash, timestamp, RefreshKind.EVENT);
  }

  /**
   * Builds a {@link RefreshKind#REQUEST} message asking the cluster to
   * refresh the given catalog.
   *
   * @param catalogName  the catalog to refresh, never null.
   * @param sourceNodeId the node originating the request, never null.
   * @param timestamp    the creation instant, never null.
   * @return a request message, never null.
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static RefreshEvent request(final String catalogName,
      final String sourceNodeId, final Instant timestamp) {
    return new RefreshEvent(catalogName, sourceNodeId, 0L, "", timestamp,
        RefreshKind.REQUEST);
  }

  /**
   * Returns whether this message is a {@link RefreshKind#REQUEST}.
   *
   * @return {@code true} if this is a request command.
   */
  public boolean isRequest() {
    return kind == RefreshKind.REQUEST;
  }
}
