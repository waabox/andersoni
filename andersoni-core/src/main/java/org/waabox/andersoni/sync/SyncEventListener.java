package org.waabox.andersoni.sync;

/**
 * A listener that is notified when a synchronization event is received.
 *
 * <p>Replaces {@code RefreshListener} to support both {@link RefreshEvent}
 * and {@link PatchEvent} types.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@FunctionalInterface
public interface SyncEventListener {

  /**
   * Called when a sync event is received from the synchronization layer.
   *
   * @param event the sync event, never null
   */
  void onEvent(SyncEvent event);
}
