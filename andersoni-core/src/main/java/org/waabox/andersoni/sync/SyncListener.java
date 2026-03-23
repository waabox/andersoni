package org.waabox.andersoni.sync;

/** A listener for synchronization events received from other nodes.
 *
 * <p>Implementations receive all event types and typically delegate to
 * {@link SyncEventHandler} via {@link SyncEvent#accept(SyncEventHandler)}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@FunctionalInterface
public interface SyncListener {
  /** Called when a synchronization event is received.
   * @param event the event, never null
   */
  void onEvent(SyncEvent event);
}
