package org.waabox.andersoni;

import java.util.List;

/**
 * A strategy for loading data items into a catalog.
 *
 * <p>Implementations of this interface are responsible for fetching the
 * complete set of items from an external data source (database, API, file,
 * etc.) so the catalog can be populated or refreshed.
 *
 * <p>The loader is invoked during catalog initialization and on every
 * refresh cycle. It must return the full dataset; partial loads are not
 * supported.
 *
 * @param <T> the type of items this loader produces
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@FunctionalInterface
public interface DataLoader<T> {

  /**
   * Loads all items from the underlying data source.
   *
   * <p>This method must return a complete, non-null list of items. An empty
   * list is valid and indicates that the data source has no items.
   *
   * @return a list of items, never null
   */
  List<T> load();
}
