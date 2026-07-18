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
 * <p><strong>Deterministic order is required for multi-node deployments.</strong>
 * The catalog content hash (the cross-node convergence signal) is
 * order-sensitive, so the loader must return items in a stable, repeatable
 * order across nodes and refreshes (e.g. an SQL {@code ORDER BY} on a stable
 * key). A nondeterministic order (unordered queries, {@code HashSet}
 * iteration, parallel fetch) makes two nodes holding identical data compute
 * different hashes, causing perpetual refresh churn.
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
