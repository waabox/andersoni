package org.waabox.andersoni;

import java.util.Objects;

/**
 * Fluent step within a {@link CompoundQuery} that specifies the query
 * operation for a single index condition.
 *
 * <p>Each method registers a {@link Condition} and returns the parent
 * {@link CompoundQuery} for further chaining.
 *
 * @param <T> the type of data items in the underlying snapshot
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class CompoundConditionStep<T> {

  /** The parent query to return after registering a condition. */
  private final CompoundQuery<T> query;

  /** The index name this step targets. */
  private final String indexName;

  /**
   * Creates a new condition step for the given query and index.
   *
   * @param theQuery     the parent compound query, never null
   * @param theIndexName the name of the index, never null
   */
  CompoundConditionStep(final CompoundQuery<T> theQuery, final String theIndexName) {
    this.query = Objects.requireNonNull(theQuery, "query must not be null");
    this.indexName = Objects.requireNonNull(theIndexName, "indexName must not be null");
  }

  /**
   * Adds an equality condition for the given key.
   *
   * @param key the key to match, never null
   *
   * @return the parent query for further chaining, never null
   *
   * @throws NullPointerException if key is null
   */
  public CompoundQuery<T> equalTo(final Object key) {
    Objects.requireNonNull(key, "key must not be null");
    return query.addCondition(new Condition(indexName, Condition.Operation.EQUAL_TO, new Object[]{key}));
  }

  /**
   * Adds a range condition for items with keys between from and to
   * (inclusive).
   *
   * @param from the inclusive lower bound, never null
   * @param to   the inclusive upper bound, never null
   *
   * @return the parent query for further chaining, never null
   *
   * @throws NullPointerException if from or to is null
   */
  public CompoundQuery<T> between(final Comparable<?> from, final Comparable<?> to) {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    return query.addCondition(new Condition(indexName, Condition.Operation.BETWEEN, new Object[]{from, to}));
  }

  /**
   * Adds a condition for items with keys strictly greater than the given
   * key.
   *
   * @param key the exclusive lower bound, never null
   *
   * @return the parent query for further chaining, never null
   *
   * @throws NullPointerException if key is null
   */
  public CompoundQuery<T> greaterThan(final Comparable<?> key) {
    Objects.requireNonNull(key, "key must not be null");
    return query.addCondition(new Condition(indexName, Condition.Operation.GREATER_THAN, new Object[]{key}));
  }

  /**
   * Adds a condition for items with keys greater than or equal to the
   * given key.
   *
   * @param key the inclusive lower bound, never null
   *
   * @return the parent query for further chaining, never null
   *
   * @throws NullPointerException if key is null
   */
  public CompoundQuery<T> greaterOrEqual(final Comparable<?> key) {
    Objects.requireNonNull(key, "key must not be null");
    return query.addCondition(new Condition(indexName, Condition.Operation.GREATER_OR_EQUAL, new Object[]{key}));
  }

  /**
   * Adds a condition for items with keys strictly less than the given key.
   *
   * @param key the exclusive upper bound, never null
   *
   * @return the parent query for further chaining, never null
   *
   * @throws NullPointerException if key is null
   */
  public CompoundQuery<T> lessThan(final Comparable<?> key) {
    Objects.requireNonNull(key, "key must not be null");
    return query.addCondition(new Condition(indexName, Condition.Operation.LESS_THAN, new Object[]{key}));
  }

  /**
   * Adds a condition for items with keys less than or equal to the given
   * key.
   *
   * @param key the inclusive upper bound, never null
   *
   * @return the parent query for further chaining, never null
   *
   * @throws NullPointerException if key is null
   */
  public CompoundQuery<T> lessOrEqual(final Comparable<?> key) {
    Objects.requireNonNull(key, "key must not be null");
    return query.addCondition(new Condition(indexName, Condition.Operation.LESS_OR_EQUAL, new Object[]{key}));
  }

  /**
   * Adds a prefix match condition for String keys.
   *
   * @param prefix the prefix to match, never null
   *
   * @return the parent query for further chaining, never null
   *
   * @throws NullPointerException if prefix is null
   */
  public CompoundQuery<T> startsWith(final String prefix) {
    Objects.requireNonNull(prefix, "prefix must not be null");
    return query.addCondition(new Condition(indexName, Condition.Operation.STARTS_WITH, new Object[]{prefix}));
  }

  /**
   * Adds a suffix match condition for String keys.
   *
   * @param suffix the suffix to match, never null
   *
   * @return the parent query for further chaining, never null
   *
   * @throws NullPointerException if suffix is null
   */
  public CompoundQuery<T> endsWith(final String suffix) {
    Objects.requireNonNull(suffix, "suffix must not be null");
    return query.addCondition(new Condition(indexName, Condition.Operation.ENDS_WITH, new Object[]{suffix}));
  }

  /**
   * Adds a substring match condition for String keys.
   *
   * @param substring the substring to match, never null
   *
   * @return the parent query for further chaining, never null
   *
   * @throws NullPointerException if substring is null
   */
  public CompoundQuery<T> contains(final String substring) {
    Objects.requireNonNull(substring, "substring must not be null");
    return query.addCondition(new Condition(indexName, Condition.Operation.CONTAINS, new Object[]{substring}));
  }
}
