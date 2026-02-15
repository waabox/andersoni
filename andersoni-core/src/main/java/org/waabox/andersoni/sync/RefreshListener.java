package org.waabox.andersoni.sync;

/**
 * A listener that is notified when a catalog refresh event is received.
 *
 * <p>Implementations react to refresh events published by other nodes in the
 * cluster, typically by reloading the affected catalog from its data source
 * or from a shared snapshot.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@FunctionalInterface
public interface RefreshListener {

  /**
   * Called when a refresh event is received from the synchronization layer.
   *
   * @param event the refresh event containing catalog name, version, hash,
   *              and the originating node identifier, never null
   */
  void onRefresh(RefreshEvent event);
}
