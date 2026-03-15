package org.waabox.andersoni;

/**
 * Thrown when the number of composite keys generated for a single item
 * exceeds the configured {@code maxKeysPerItem} safety limit.
 *
 * <p>This is a fail-fast mechanism to prevent combinatorial explosion
 * during indexing. Users should set the limit generously based on
 * expected data characteristics.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class IndexKeyLimitExceededException extends AndersoniException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a new exception describing the limit violation.
   *
   * @param indexName the name of the graph index that exceeded its limit
   * @param itemToString the string representation of the offending item
   * @param keyCount the number of keys that were generated
   * @param maxKeysPerItem the configured maximum keys per item
   */
  public IndexKeyLimitExceededException(final String indexName,
      final String itemToString, final int keyCount,
      final int maxKeysPerItem) {
    super("Graph index '" + indexName + "' generated " + keyCount
        + " keys for item [" + itemToString + "], exceeding limit of "
        + maxKeysPerItem + ". Increase maxKeysPerItem or reduce data"
        + " cardinality.");
  }
}
