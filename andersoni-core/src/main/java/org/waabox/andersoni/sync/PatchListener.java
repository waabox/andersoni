package org.waabox.andersoni.sync;

/**
 * A listener that is notified when a snapshot patch event is received.
 *
 * <p>Implementations react to {@link PatchEvent}s published by other nodes
 * after a leader-side {@code Andersoni.replaceAndSync(...)}. The follower
 * either applies the patch surgically when the local snapshot matches the
 * event's precondition, or falls back to a full reload from the snapshot
 * store when the precondition fails.
 */
@FunctionalInterface
public interface PatchListener {

  /**
   * Called when a patch event is received from the synchronization layer.
   *
   * @param event the patch event, never null
   */
  void onPatch(PatchEvent event);
}
