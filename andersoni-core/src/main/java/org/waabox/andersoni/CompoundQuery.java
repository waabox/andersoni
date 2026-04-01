package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A compound query that accumulates multiple conditions across different
 * indices and evaluates them with short-circuit intersection.
 *
 * <p>Instances are created via {@link Catalog#compound()} or
 * {@link Andersoni#compound(String, Class)} and are bound to the snapshot
 * at creation time.
 *
 * @param <T> the type of data items in the underlying snapshot
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class CompoundQuery<T> {

  /** The snapshot bound to this query. */
  private final Snapshot<T> snapshot;

  /** The name of the catalog this query belongs to. */
  private final String catalogName;

  /** The accumulated conditions for this query. */
  private final List<Condition> conditions;

  /** Whether {@link #where(String)} has already been called. */
  private boolean whereAdded;

  /**
   * Creates a new compound query bound to the given snapshot.
   *
   * @param theSnapshot    the snapshot to query against, never null
   * @param theCatalogName the catalog name, never null
   */
  CompoundQuery(final Snapshot<T> theSnapshot, final String theCatalogName) {
    this.snapshot = Objects.requireNonNull(theSnapshot, "snapshot must not be null");
    this.catalogName = Objects.requireNonNull(theCatalogName, "catalogName must not be null");
    this.conditions = new ArrayList<>();
    this.whereAdded = false;
  }

  /**
   * Begins the compound query with the first condition on the given index.
   *
   * <p>This method can only be called once per query. Use {@link #and(String)}
   * for additional conditions.
   *
   * @param indexName the name of the index for the first condition, never null
   *
   * @return a step builder for specifying the operation, never null
   *
   * @throws NullPointerException  if indexName is null
   * @throws IllegalStateException if where() has already been called
   */
  public CompoundConditionStep<T> where(final String indexName) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    if (whereAdded) {
      throw new IllegalStateException(
          "where() has already been called. Use and() for additional conditions");
    }
    whereAdded = true;
    return new CompoundConditionStep<>(this, indexName);
  }

  /**
   * Adds an additional condition on the given index.
   *
   * @param indexName the name of the index for the condition, never null
   *
   * @return a step builder for specifying the operation, never null
   *
   * @throws NullPointerException if indexName is null
   */
  public CompoundConditionStep<T> and(final String indexName) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    return new CompoundConditionStep<>(this, indexName);
  }

  /**
   * Executes the compound query by evaluating all accumulated conditions
   * against the bound snapshot with short-circuit intersection.
   *
   * <p>Each condition is evaluated sequentially. After the first condition,
   * results are intersected using identity comparison (not {@code equals}).
   * If any intermediate result is empty, evaluation stops immediately.
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws IllegalStateException   if no conditions have been added
   * @throws IndexNotFoundException  if a condition references an unknown index
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> execute() {
    if (conditions.isEmpty()) {
      throw new IllegalStateException(
          "At least one condition is required. Call where() before execute()");
    }

    List<T> result = evaluateCondition(conditions.get(0));

    if (result.isEmpty()) {
      return Collections.emptyList();
    }

    for (int i = 1; i < conditions.size(); i++) {
      final List<T> candidates = evaluateCondition(conditions.get(i));

      if (candidates.isEmpty()) {
        return Collections.emptyList();
      }

      result = intersect(result, candidates);

      if (result.isEmpty()) {
        return Collections.emptyList();
      }
    }

    return Collections.unmodifiableList(result);
  }

  /**
   * Executes the compound query and returns the results projected into the
   * given view type.
   *
   * <p>Internally delegates to {@link #execute()} to obtain the matched
   * domain objects, then resolves the pre-computed view for each result
   * from the snapshot's catalog items. Identity comparison is used for
   * the lookup so that no additional equality contract is required on T.
   *
   * <p>The returned list preserves the order produced by {@link #execute()}.
   *
   * @param <V>      the view type
   * @param viewType the class of the view projection, never null
   *
   * @return an unmodifiable list of matching views, never null
   *
   * @throws IllegalStateException    if no conditions have been added
   * @throws IndexNotFoundException   if a condition references an unknown index
   * @throws IllegalArgumentException if the view type is not registered
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public <V> List<V> execute(final Class<V> viewType) {
    Objects.requireNonNull(viewType, "viewType must not be null");
    final List<T> result = execute();
    if (result.isEmpty()) {
      return Collections.emptyList();
    }
    final IdentityHashMap<T, AndersoniCatalogItem<T>> lookup =
        new IdentityHashMap<>(snapshot.items().size());
    for (final AndersoniCatalogItem<T> entry : snapshot.items()) {
      lookup.put(entry.item(), entry);
    }
    final List<V> views = new ArrayList<>(result.size());
    for (final T item : result) {
      views.add(lookup.get(item).view(viewType));
    }
    return Collections.unmodifiableList(views);
  }

  /**
   * Evaluates a single condition against the bound snapshot.
   *
   * @param condition the condition to evaluate, never null
   *
   * @return the list of matching items, never null
   *
   * @throws IndexNotFoundException if the index does not exist
   */
  private List<T> evaluateCondition(final Condition condition) {
    final String idx = condition.indexName();
    final Object[] args = condition.args();

    if (!snapshot.hasIndex(idx)) {
      throw new IndexNotFoundException(idx, catalogName);
    }

    return switch (condition.operation()) {
      case EQUAL_TO -> snapshot.search(idx, args[0]);
      case BETWEEN -> snapshot.searchBetween(idx, (Comparable<?>) args[0], (Comparable<?>) args[1]);
      case GREATER_THAN -> snapshot.searchGreaterThan(idx, (Comparable<?>) args[0]);
      case GREATER_OR_EQUAL -> snapshot.searchGreaterOrEqual(idx, (Comparable<?>) args[0]);
      case LESS_THAN -> snapshot.searchLessThan(idx, (Comparable<?>) args[0]);
      case LESS_OR_EQUAL -> snapshot.searchLessOrEqual(idx, (Comparable<?>) args[0]);
      case STARTS_WITH -> snapshot.searchStartsWith(idx, (String) args[0]);
      case ENDS_WITH -> snapshot.searchEndsWith(idx, (String) args[0]);
      case CONTAINS -> snapshot.searchContains(idx, (String) args[0]);
    };
  }

  /**
   * Computes the intersection of two lists using identity comparison.
   *
   * <p>Uses an {@link IdentityHashMap}-backed set for O(1) identity lookups,
   * placing the smaller list in the set for optimal performance.
   *
   * @param a the first list, never null
   * @param b the second list, never null
   *
   * @return the intersection preserving order from the larger list,
   *         never null
   */
  private List<T> intersect(final List<T> a, final List<T> b) {
    final List<T> smaller;
    final List<T> larger;
    if (a.size() <= b.size()) {
      smaller = a;
      larger = b;
    } else {
      smaller = b;
      larger = a;
    }
    final Set<T> retain = Collections.newSetFromMap(new IdentityHashMap<>(smaller.size()));
    retain.addAll(smaller);
    final List<T> result = new ArrayList<>();
    for (final T item : larger) {
      if (retain.contains(item)) {
        result.add(item);
      }
    }
    return result;
  }

  /**
   * Adds a condition to the accumulator. Package-private, called by
   * {@link CompoundConditionStep}.
   *
   * @param condition the condition to add, never null
   *
   * @return this query for fluent chaining, never null
   */
  CompoundQuery<T> addCondition(final Condition condition) {
    conditions.add(condition);
    return this;
  }

  /**
   * Returns an unmodifiable view of the accumulated conditions.
   *
   * @return the conditions list, never null
   */
  List<Condition> conditions() {
    return Collections.unmodifiableList(conditions);
  }
}
