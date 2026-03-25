package org.waabox.andersoni.sync;

import java.time.Instant;

/** Common interface for all synchronization events broadcast across nodes.
 *
 * <p>Implementations use the visitor pattern via {@link #accept(SyncEventHandler)}
 * to enable type-safe dispatch without instanceof checks.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SyncEvent {
  /** Returns the name of the catalog this event relates to. */
  String catalogName();
  /** Returns the identifier of the node that originated this event. */
  String sourceNodeId();
  /** Returns the snapshot version after the operation was applied. */
  long version();
  /** Returns the instant at which the event was created. */
  Instant timestamp();
  /** Dispatches this event to the appropriate handler method.
   * @param handler the handler to dispatch to, never null
   */
  void accept(SyncEventHandler handler);
}
