package org.waabox.andersoni.sync;

import java.time.Instant;

/**
 * Common contract for messages broadcast across nodes via a
 * {@link SyncStrategy}.
 *
 * <p>Two concrete types are supported:
 * <ul>
 *   <li>{@link RefreshEvent} — full-refresh signal, used when a catalog
 *       has been rebuilt from scratch and followers need to reload it.</li>
 *   <li>{@link PatchEvent} — surgical single-item replacement, used when a
 *       single item is patched in place via
 *       {@code Andersoni.replaceAndSync(...)}.</li>
 * </ul>
 *
 * <p>The sealed type lets the transport-layer codec ({@link SyncMessageCodec})
 * dispatch on a single discriminator and lets receivers pattern-match
 * exhaustively without an open-class hierarchy.
 */
public sealed interface SyncMessage permits RefreshEvent, PatchEvent {

  /**
   * Returns the name of the catalog this message refers to.
   *
   * @return the catalog name, never null
   */
  String catalogName();

  /**
   * Returns the identifier of the node that produced this message.
   *
   * @return the source node id, never null
   */
  String sourceNodeId();

  /**
   * Returns the instant at which the originating mutation occurred.
   *
   * @return the timestamp, never null
   */
  Instant timestamp();
}
