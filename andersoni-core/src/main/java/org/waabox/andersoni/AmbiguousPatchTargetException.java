package org.waabox.andersoni;

import java.util.Objects;

/**
 * Thrown by any patch operation that locates its target through an index
 * lookup — {@link Catalog#replace(String, Object, Object)},
 * {@link Catalog#remove(String, Object)} and
 * {@link Catalog#upsert(String, Object, Object)} — when the lookup at the
 * requested {@code (indexName, key)} resolves to more than one item.
 *
 * <p>These operations act on a single item, so the lookup must identify
 * one. Zero matches is not an error: {@code replace} and {@code remove}
 * report that nothing happened, and {@code upsert} inserts instead. More
 * than one match is an error, because the caller's choice of
 * {@code (indexName, key)} did not say which item was meant, and guessing
 * would corrupt the catalog silently.
 *
 * <p>The usual remedy is to look the item up through an index whose keys
 * are unique in the caller's domain (commonly an id-based index) rather
 * than a non-unique one like {@code by-venue}.
 */
public final class AmbiguousPatchTargetException extends RuntimeException {

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
  public AmbiguousPatchTargetException(final String catalogName,
      final String indexName, final Object key, final int matchedCount) {
    super("Cannot replace in catalog '"
        + Objects.requireNonNull(catalogName, "catalogName")
        + "': lookup on index '"
        + Objects.requireNonNull(indexName, "indexName")
        + "' with key '" + Objects.requireNonNull(key, "key")
        + "' resolved to " + matchedCount + " items, expected at most one");
  }
}
