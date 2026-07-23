package org.waabox.andersoni;

import java.util.Objects;

/**
 * Thrown by {@link Catalog#replace(String, Object, Object)} when the lookup
 * at the requested {@code (indexName, key)} resolves to more than one item.
 *
 * <p>{@code replace} requires the lookup to identify a single item to swap.
 * If the bucket contains zero items, {@code replace} simply returns
 * {@code false}; if it contains exactly one, the swap is performed; if it
 * contains more than one, this exception is raised because the operation
 * is ambiguous — the caller's choice of {@code (indexName, key)} did not
 * uniquely identify a single item.
 *
 * <p>The usual remedy is to call {@code replace} against an index whose
 * keys are unique for the caller's domain (commonly an id-based index)
 * rather than a non-unique index like {@code by-venue}.
 */
public final class AmbiguousReplaceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a new exception describing the ambiguous lookup.
   *
   * @param catalogName  the name of the catalog being patched, cannot be
   *                     null.
   * @param indexName    the name of the index used for the lookup, cannot
   *                     be null.
   * @param key          the key supplied to the lookup, cannot be null.
   * @param matchedCount the number of items found at that key, must be
   *                     greater than one.
   */
  public AmbiguousReplaceException(final String catalogName,
      final String indexName, final Object key, final int matchedCount) {
    super("Cannot replace in catalog '"
        + Objects.requireNonNull(catalogName, "catalogName")
        + "': lookup on index '"
        + Objects.requireNonNull(indexName, "indexName")
        + "' with key '" + Objects.requireNonNull(key, "key")
        + "' resolved to " + matchedCount + " items, expected at most one");
  }
}
