package org.waabox.andersoni;

import java.util.Objects;

/**
 * Thrown when a query references an index name that does not exist in the
 * catalog.
 *
 * <p>This typically occurs when a caller passes an index name to
 * {@code Snapshot#query} or a similar API that was never registered
 * via the catalog's {@code index()} / {@code indexSorted()} builders.</p>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class IndexNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a new exception indicating that the given index was not found
   * in the specified catalog.
   *
   * @param indexName   the name of the missing index, cannot be null.
   * @param catalogName the name of the catalog that was searched,
   *                    cannot be null.
   */
  public IndexNotFoundException(final String indexName,
      final String catalogName) {
    super("Index '" + Objects.requireNonNull(indexName, "indexName")
        + "' not found in catalog '"
        + Objects.requireNonNull(catalogName, "catalogName") + "'");
  }
}
