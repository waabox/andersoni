package org.waabox.andersoni;

/**
 * Thrown when a catalog is not available for querying.
 *
 * <p>This typically occurs when a catalog has not yet completed its
 * initial load, or when all retry attempts to load the catalog data
 * have been exhausted.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public class CatalogNotAvailableException extends RuntimeException {

  /**
   * Creates a new exception indicating that the specified catalog is
   * not available.
   *
   * @param catalogName the name of the unavailable catalog, never null
   */
  public CatalogNotAvailableException(final String catalogName) {
    super("Catalog not available: " + catalogName);
  }

  /**
   * Creates a new exception indicating that the specified catalog is
   * not available, with an underlying cause.
   *
   * @param catalogName the name of the unavailable catalog, never null
   * @param cause       the underlying cause of the failure, never null
   */
  public CatalogNotAvailableException(final String catalogName,
      final Throwable cause) {
    super("Catalog not available: " + catalogName, cause);
  }
}
