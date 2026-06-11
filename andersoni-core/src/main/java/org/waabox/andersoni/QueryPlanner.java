package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Plans the execution of a graph query against one or more {@link GraphIndexDefinition} instances.
 *
 * <p>Given a set of {@link GraphQueryCondition} keyed by field name, the planner selects the
 * best-matching index hotpath and resolves which conditions can be satisfied as a direct
 * {@link CompositeKey} lookup and which must be evaluated as post-filters on the result set.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class QueryPlanner {

  private QueryPlanner() {}

  /**
   * The result of planning a graph query.
   *
   * <p>Carries the resolved index name, the composite keys to use for the direct lookup,
   * and any conditions that could not be covered by the hotpath and must be evaluated
   * as post-filters on the result set.
   *
   * <p>A plan carries multiple keys when one or more covered conditions use the
   * {@link GraphQueryCondition.Operation#IN_LIST} operation: the keys are the
   * cartesian product of every covered field's candidate values. Single-value
   * {@link GraphQueryCondition.Operation#EQUAL_TO} conditions yield exactly one key.
   *
   * @param <T> the type of root entity being indexed
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public record Plan<T>(
      String graphIndexName,
      List<CompositeKey> keys,
      List<GraphQueryCondition> postFilterConditions) {
  }

  /**
   * Plans a graph query against the provided indexes.
   *
   * <p>Evaluates each index's hotpaths and selects the one whose ordered field prefix
   * covers the most fields present in {@code conditions}. The covered fields are resolved
   * into a {@link CompositeKey} for direct lookup; uncovered conditions are returned as
   * post-filters. Returns {@code null} when no index hotpath has any overlap with the
   * supplied conditions.
   *
   * @param <T>        the type of root entity
   * @param indexes    the candidate indexes to evaluate, never null
   * @param conditions the query conditions keyed by field name, never null
   * @return the best {@link Plan}, or {@code null} if no index can be used
   * @throws NullPointerException if {@code indexes} or {@code conditions} is null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static <T> Plan<T> plan(
      final List<GraphIndexDefinition<T>> indexes,
      final Map<String, GraphQueryCondition> conditions) {
    Objects.requireNonNull(indexes, "indexes must not be null");
    Objects.requireNonNull(conditions, "conditions must not be null");

    Plan<T> best = null;
    int bestCoverage = 0;

    for (final GraphIndexDefinition<T> index : indexes) {
      for (final Hotpath hotpath : index.hotpaths()) {
        final List<String> fields = hotpath.fieldNames();
        int coverage = 0;
        for (final String field : fields) {
          if (conditions.containsKey(field)) {
            coverage++;
          } else {
            break;
          }
        }
        if (coverage == 0) {
          continue;
        }
        if (coverage > bestCoverage) {
          bestCoverage = coverage;
          best = buildPlan(index.name(), fields, coverage, conditions);
        }
      }
    }
    return best;
  }

  private static <T> Plan<T> buildPlan(
      final String indexName,
      final List<String> hotpathFields,
      final int coverage,
      final Map<String, GraphQueryCondition> conditions) {
    List<List<Object>> combinations = new ArrayList<>();
    combinations.add(new ArrayList<>());
    for (int i = 0; i < coverage; i++) {
      final GraphQueryCondition cond = conditions.get(hotpathFields.get(i));
      final List<List<Object>> expanded = new ArrayList<>();
      for (final List<Object> prefix : combinations) {
        for (final Object value : cond.args()) {
          final List<Object> next = new ArrayList<>(prefix);
          next.add(value);
          expanded.add(next);
        }
      }
      combinations = expanded;
    }
    final List<CompositeKey> keys = new ArrayList<>(combinations.size());
    for (final List<Object> components : combinations) {
      keys.add(CompositeKey.ofList(components));
    }
    final List<String> coveredFields = hotpathFields.subList(0, coverage);
    final List<GraphQueryCondition> postFilters = conditions.entrySet().stream()
        .filter(e -> !coveredFields.contains(e.getKey()))
        .map(Map.Entry::getValue)
        .toList();
    return new Plan<>(indexName, keys, postFilters);
  }
}
