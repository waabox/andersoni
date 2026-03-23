package org.waabox.andersoni.sync;

/** Visitor interface for handling different types of {@link SyncEvent}s.
 *
 * <p>Implementors provide type-specific handling for each event subtype,
 * avoiding instanceof/switch chains.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SyncEventHandler {
  /** Handles a full refresh event.
   * @param event the refresh event, never null
   */
  void onRefresh(RefreshEvent event);
  /** Handles a patch event.
   * @param event the patch event, never null
   */
  void onPatch(PatchEvent event);
}
