package org.waabox.andersoni;

import java.util.Objects;

/**
 * Thrown when an operation inside a {@link Catalog#apply(java.util.List)
 * batch} needs an item that its lookup did not find.
 *
 * <p>A standalone {@code replace} reports a miss by returning
 * {@code false}, leaving the caller to decide what it means. A batch
 * cannot: it applies as a unit, so "operation 37 did nothing while the
 * others applied" is not a state the caller can act on, and silently
 * skipping the operation would hide a wrong key rather than surface it.
 * The batch is therefore abandoned before anything is swapped in, and this
 * names the operation that could not be satisfied.
 *
 * @author borafael
 */
public final class PatchTargetNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a new exception describing the unsatisfied lookup.
   *
   * @param catalogName the catalog being patched, cannot be null.
   * @param indexName   the index used for the lookup, cannot be null.
   * @param key         the key supplied to the lookup, cannot be null.
   */
  public PatchTargetNotFoundException(final String catalogName,
      final String indexName, final Object key) {
    super("No item found in catalog '"
        + Objects.requireNonNull(catalogName, "catalogName cannot be null")
        + "' at index '"
        + Objects.requireNonNull(indexName, "indexName cannot be null")
        + "' for key '"
        + Objects.requireNonNull(key, "key cannot be null")
        + "'; the batch was abandoned and nothing was applied");
  }
}
