package org.waabox.andersoni;

import java.util.List;
import java.util.Objects;

/**
 * Fluent query API that delegates search operations to a {@link Snapshot}.
 *
 * <p>A QueryStep is bound to a specific index within a snapshot and exposes
 * typed query methods: equality lookup for any index, plus range and text
 * pattern operations for sorted indices.
 *
 * <p>Instances are created by the catalog via its {@code query()} method and
 * are not intended to be constructed directly by application code.
 *
 * @param <T> the type of data items in the underlying snapshot
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class QueryStep<T> {

  /** The snapshot to query against. */
  private final Snapshot<T> snapshot;

  /** The name of the index to query. */
  private final String indexName;

  /** The name of the catalog that owns this query, used for error messages. */
  private final String catalogName;

  /**
   * Creates a new query step targeting the given index within the snapshot.
   *
   * @param snapshot    the snapshot to query, never null
   * @param indexName   the name of the index to query, never null
   * @param catalogName the name of the owning catalog for error messages,
   *                    never null
   */
  QueryStep(final Snapshot<T> snapshot, final String indexName,
      final String catalogName) {
    this.snapshot = Objects.requireNonNull(snapshot,
        "snapshot must not be null");
    this.indexName = Objects.requireNonNull(indexName,
        "indexName must not be null");
    this.catalogName = Objects.requireNonNull(catalogName,
        "catalogName must not be null");
  }

  /**
   * Searches for items whose indexed key equals the given value.
   *
   * <p>This operation works on any index type (both regular and sorted).
   * It validates that the index exists before performing the lookup,
   * throwing {@link IndexNotFoundException} if the index was never
   * registered.
   *
   * @param key the key to look up, never null
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws IndexNotFoundException if the index does not exist in the
   *         snapshot
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> equalTo(final Object key) {
    Objects.requireNonNull(key, "key must not be null");
    if (!snapshot.hasIndex(indexName)) {
      throw new IndexNotFoundException(indexName, catalogName);
    }
    return snapshot.search(indexName, key);
  }

  /**
   * Searches for items whose indexed key falls within the inclusive range
   * [{@code from}, {@code to}].
   *
   * <p>Requires a sorted index. If the index is not sorted, the snapshot
   * will throw {@link UnsupportedIndexOperationException}.
   *
   * @param from the inclusive lower bound, never null
   * @param to   the inclusive upper bound, never null
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support range queries
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> between(final Comparable<?> from, final Comparable<?> to) {
    return snapshot.searchBetween(indexName, from, to);
  }

  /**
   * Searches for items whose indexed key is strictly greater than the
   * given value.
   *
   * <p>Requires a sorted index. If the index is not sorted, the snapshot
   * will throw {@link UnsupportedIndexOperationException}.
   *
   * @param key the exclusive lower bound, never null
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support range queries
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> greaterThan(final Comparable<?> key) {
    return snapshot.searchGreaterThan(indexName, key);
  }

  /**
   * Searches for items whose indexed key is greater than or equal to the
   * given value.
   *
   * <p>Requires a sorted index. If the index is not sorted, the snapshot
   * will throw {@link UnsupportedIndexOperationException}.
   *
   * @param key the inclusive lower bound, never null
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support range queries
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> greaterOrEqual(final Comparable<?> key) {
    return snapshot.searchGreaterOrEqual(indexName, key);
  }

  /**
   * Searches for items whose indexed key is strictly less than the given
   * value.
   *
   * <p>Requires a sorted index. If the index is not sorted, the snapshot
   * will throw {@link UnsupportedIndexOperationException}.
   *
   * @param key the exclusive upper bound, never null
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support range queries
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> lessThan(final Comparable<?> key) {
    return snapshot.searchLessThan(indexName, key);
  }

  /**
   * Searches for items whose indexed key is less than or equal to the
   * given value.
   *
   * <p>Requires a sorted index. If the index is not sorted, the snapshot
   * will throw {@link UnsupportedIndexOperationException}.
   *
   * @param key the inclusive upper bound, never null
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support range queries
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> lessOrEqual(final Comparable<?> key) {
    return snapshot.searchLessOrEqual(indexName, key);
  }

  /**
   * Searches for items whose String key starts with the given prefix.
   *
   * <p>Requires a sorted index with String keys. If the index is not
   * sorted, the snapshot will throw
   * {@link UnsupportedIndexOperationException}.
   *
   * @param prefix the prefix to match, never null
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support range queries
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> startsWith(final String prefix) {
    return snapshot.searchStartsWith(indexName, prefix);
  }

  /**
   * Searches for items whose String key ends with the given suffix.
   *
   * <p>Requires a sorted index with String keys and a corresponding
   * reversed-key index. If the index does not support text queries,
   * the snapshot will throw {@link UnsupportedIndexOperationException}.
   *
   * @param suffix the suffix to match, never null
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support text queries
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> endsWith(final String suffix) {
    return snapshot.searchEndsWith(indexName, suffix);
  }

  /**
   * Searches for items whose String key contains the given substring.
   *
   * <p>Requires a sorted index. If the index is not sorted, the snapshot
   * will throw {@link UnsupportedIndexOperationException}.
   *
   * @param substring the substring to match, never null
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support range queries
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> contains(final String substring) {
    return snapshot.searchContains(indexName, substring);
  }
}
