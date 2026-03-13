package org.waabox.andersoni.sync;

/**
 * Visitor for {@link SyncEvent} subtypes, enabling type-safe dispatch
 * without instanceof checks.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SyncEventVisitor {

  /**
   * Visits a full refresh event.
   *
   * @param event the refresh event, never null
   */
  void visit(RefreshEvent event);

  /**
   * Visits a patch (add/remove) event.
   *
   * @param event the patch event, never null
   */
  void visit(PatchEvent event);
}
