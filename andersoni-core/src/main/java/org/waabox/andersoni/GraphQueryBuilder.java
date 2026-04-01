package org.waabox.andersoni;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Fluent DSL for building and executing graph-based composite queries.
 *
 * <p>Instances are created via {@code catalog.graphQuery()} and are bound
 * to the snapshot at creation time. Conditions are accumulated via
 * {@link #where(String)} and {@link #and(String)}, each returning a
 * {@link GraphQueryConditionStep} to specify the operation, which in turn
 * returns this builder for further chaining. The query is executed by calling
 * {@link #execute()}.
 *
 * <p>Query planning is delegated to {@link QueryPlanner}, which selects the
 * best-matching {@link GraphIndexDefinition} hotpath for the accumulated
 * conditions. Conditions that cannot be covered by any hotpath cause
 * {@link #execute()} to throw {@link UnsupportedOperationException}.
 *
 * <p>This class is not thread-safe; a new instance should be obtained per
 * query invocation.
 *
 * @param <T> the item type
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class GraphQueryBuilder<T> {

  private final Snapshot<T> snapshot;
  private final String catalogName;
  private final List<GraphIndexDefinition<T>> graphIndexes;
  private final Map<String, GraphQueryCondition> conditions;

  /**
   * Creates a new builder bound to the given snapshot, catalog name, and graph index definitions.
   *
   * @param snapshot     the snapshot to query, never null
   * @param catalogName  the name of the catalog this query targets, never null
   * @param graphIndexes the graph index definitions available for query planning, never null
   * @throws NullPointerException if any parameter is null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  GraphQueryBuilder(final Snapshot<T> snapshot, final String catalogName,
      final List<GraphIndexDefinition<T>> graphIndexes) {
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
    this.catalogName = Objects.requireNonNull(catalogName, "catalogName must not be null");
    this.graphIndexes = Objects.requireNonNull(graphIndexes, "graphIndexes must not be null");
    this.conditions = new LinkedHashMap<>();
  }

  /**
   * Begins the query with the first condition on the given traversal field.
   *
   * <p>Returns a {@link GraphQueryConditionStep} that must be completed with
   * an operation (e.g., {@link GraphQueryConditionStep#eq(Object)}) before
   * chaining further conditions or calling {@link #execute()}.
   *
   * @param fieldName the traversal field name to constrain, never null
   * @return the condition step to specify the operation, never null
   * @throws NullPointerException if fieldName is null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public GraphQueryConditionStep<T> where(final String fieldName) {
    Objects.requireNonNull(fieldName, "fieldName must not be null");
    return new GraphQueryConditionStep<>(this, fieldName);
  }

  /**
   * Adds an additional condition on the given traversal field.
   *
   * <p>Returns a {@link GraphQueryConditionStep} that must be completed with
   * an operation (e.g., {@link GraphQueryConditionStep#eq(Object)}) before
   * chaining further conditions or calling {@link #execute()}.
   *
   * @param fieldName the traversal field name to constrain, never null
   * @return the condition step to specify the operation, never null
   * @throws NullPointerException if fieldName is null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public GraphQueryConditionStep<T> and(final String fieldName) {
    Objects.requireNonNull(fieldName, "fieldName must not be null");
    return new GraphQueryConditionStep<>(this, fieldName);
  }

  /**
   * Executes the graph query against the bound snapshot using the accumulated conditions.
   *
   * <p>If no conditions have been added, returns an empty list immediately.
   * Otherwise, delegates to {@link QueryPlanner} to select the best-matching
   * hotpath across the available {@link GraphIndexDefinition} instances. When
   * no hotpath has any overlap with the supplied conditions, returns an empty
   * list. When a hotpath is selected but some conditions are not covered by
   * that hotpath, throws {@link UnsupportedOperationException} with a message
   * listing the uncovered fields.
   *
   * @return an unmodifiable list of matching items, never null
   * @throws UnsupportedOperationException if one or more conditions are not covered
   *         by any available hotpath; callers should define a graph index that covers
   *         all query fields, or restrict the query to covered fields
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> execute() {
    if (conditions.isEmpty()) {
      return Collections.emptyList();
    }

    final QueryPlanner.Plan<T> plan =
        QueryPlanner.plan(graphIndexes, conditions);

    if (plan == null) {
      return Collections.emptyList();
    }

    if (!plan.postFilterConditions().isEmpty()) {
      final String uncovered = plan.postFilterConditions().stream()
          .map(GraphQueryCondition::fieldName)
          .collect(Collectors.joining(", "));
      throw new UnsupportedOperationException(
          "Graph query has conditions not covered by any hotpath: ["
              + uncovered + "]. Define a graph index that covers all"
              + " query fields, or use compound() for uncovered fields.");
    }

    return snapshot.search(plan.graphIndexName(), plan.key());
  }

  /**
   * Executes the graph query and returns results projected into the given
   * view type.
   *
   * <p>Uses the same query planning logic as {@link #execute()}, then
   * delegates to the snapshot's view-aware search overload to extract the
   * pre-computed view projection for each matching item.
   *
   * @param <V>      the view type
   * @param viewType the class of the view projection, never null
   *
   * @return an unmodifiable list of matching views, never null
   *
   * @throws NullPointerException          if viewType is null
   * @throws UnsupportedOperationException if one or more conditions are not
   *         covered by any available hotpath
   * @throws IllegalArgumentException      if the view type is not registered
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public <V> List<V> execute(final Class<V> viewType) {
    Objects.requireNonNull(viewType, "viewType must not be null");
    if (conditions.isEmpty()) {
      return Collections.emptyList();
    }

    final QueryPlanner.Plan<T> plan = QueryPlanner.plan(graphIndexes, conditions);

    if (plan == null) {
      return Collections.emptyList();
    }

    if (!plan.postFilterConditions().isEmpty()) {
      final String uncovered = plan.postFilterConditions().stream()
          .map(GraphQueryCondition::fieldName)
          .collect(Collectors.joining(", "));
      throw new UnsupportedOperationException(
          "Graph query has conditions not covered by any hotpath: ["
              + uncovered + "]. Define a graph index that covers all"
              + " query fields, or use compound() for uncovered fields.");
    }

    return snapshot.search(plan.graphIndexName(), plan.key(), viewType);
  }

  /**
   * Registers a condition into the accumulated condition map, keyed by field name.
   *
   * <p>Called internally by {@link GraphQueryConditionStep} after an operation
   * is specified. A later condition for the same field name replaces any
   * previously registered condition for that field.
   *
   * @param condition the condition to register, never null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  void addCondition(final GraphQueryCondition condition) {
    conditions.put(condition.fieldName(), condition);
  }
}
