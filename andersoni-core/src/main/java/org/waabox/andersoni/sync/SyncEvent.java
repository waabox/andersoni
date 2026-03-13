package org.waabox.andersoni.sync;

import java.time.Instant;

/**
 * Base interface for all synchronization events broadcast across nodes.
 *
 * <p>Events are dispatched via the visitor pattern using
 * {@link SyncEventVisitor}. Implementations include {@link RefreshEvent}
 * for full catalog reloads and {@link PatchEvent} for incremental
 * add/remove operations.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SyncEvent {

  /**
   * Returns the name of the catalog this event targets.
   *
   * @return the catalog name, never null
   */
  String catalogName();

  /**
   * Returns the identifier of the node that originated this event.
   *
   * @return the source node identifier, never null
   */
  String sourceNodeId();

  /**
   * Returns the monotonically increasing version number.
   *
   * @return the version number
   */
  long version();

  /**
   * Returns the content hash of the snapshot after this event was applied.
   *
   * @return the hash string, never null
   */
  String hash();

  /**
   * Returns the instant at which this event was created.
   *
   * @return the timestamp, never null
   */
  Instant timestamp();

  /**
   * Dispatches this event to the appropriate visitor method.
   *
   * @param visitor the visitor to accept, never null
   */
  void accept(SyncEventVisitor visitor);
}
