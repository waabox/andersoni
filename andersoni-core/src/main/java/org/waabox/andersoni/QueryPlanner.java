package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Selects the best graph index and hotpath for a set of query conditions.
 *
 * <p>The planner evaluates each hotpath in each graph index, computing the
 * longest usable prefix (contiguous fields from the left covered by eq
 * conditions). It picks the hotpath with the highest score (most fields
 * covered), builds the {@link CompositeKey}, and returns the plan.
 *
 * <p>This class is stateless and all methods are thread-safe.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class QueryPlanner {

  private QueryPlanner() {}

  /**
   * The result of a planning decision, containing the chosen index name,
   * the composite lookup key, and any conditions that could not be covered
   * by the index and must be applied as a post-filter.
   *
   * @param <T> the type of entity being queried
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static final class Plan<T> {
    private final String graphIndexName;
    private final CompositeKey key;
    private final List<GraphQueryCondition> postFilterConditions;

    Plan(final String graphIndexName, final CompositeKey key,
        final List<GraphQueryCondition> postFilterConditions) {
      this.graphIndexName = graphIndexName;
      this.key = key;
      this.postFilterConditions = Collections.unmodifiableList(postFilterConditions);
    }

    /**
     * Returns the name of the graph index selected by the planner.
     *
     * @return the graph index name, never null
     *
     * @author waabox(waabox[at]gmail[dot]com)
     */
    public String graphIndexName() {
      return graphIndexName;
    }

    /**
     * Returns the composite key to use for the index lookup.
     *
     * <p>The key is built from the longest usable prefix of the chosen
     * hotpath for which {@link GraphQueryCondition.Operation#EQUAL_TO}
     * conditions were provided.
     *
     * @return the composite key, never null
     *
     * @author waabox(waabox[at]gmail[dot]com)
     */
    public CompositeKey key() {
      return key;
    }

    /**
     * Returns query conditions that could not be covered by the selected
     * index and must be evaluated in memory after the index lookup.
     *
     * @return an unmodifiable list of post-filter conditions, never null,
     *     may be empty
     *
     * @author waabox(waabox[at]gmail[dot]com)
     */
    public List<GraphQueryCondition> postFilterConditions() {
      return postFilterConditions;
    }
  }

  /**
   * Evaluates the provided graph indexes against the given conditions and
   * returns the plan that offers the best coverage.
   *
   * <p>Coverage is measured as the length of the longest contiguous prefix
   * (from the left) of a hotpath's field list that can be matched by an
   * {@link GraphQueryCondition.Operation#EQUAL_TO} condition. When two
   * hotpaths have equal coverage, the first one encountered wins.
   *
   * <p>Any conditions not covered by the chosen hotpath prefix are
   * collected into {@link Plan#postFilterConditions()} so callers can apply
   * them after the index lookup.
   *
   * <p>Returns {@code null} when no hotpath in any index has at least one
   * field covered by the supplied conditions.
   *
   * @param <T>          the type of entity being queried
   * @param graphIndexes the candidate graph indexes to evaluate, must not be null
   * @param conditions   the query conditions keyed by traversal field name,
   *                     must not be null
   * @return a {@link Plan} describing the best index and key to use, or
   *     {@code null} if no index can be used for these conditions
   * @throws NullPointerException if {@code graphIndexes} or {@code conditions} is null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static <T> Plan<T> plan(
      final List<GraphIndexDefinition<T>> graphIndexes,
      final Map<String, GraphQueryCondition> conditions) {
    Objects.requireNonNull(graphIndexes, "graphIndexes must not be null");
    Objects.requireNonNull(conditions, "conditions must not be null");

    String bestIndexName = null;
    List<String> bestCoveredFields = null;
    int bestScore = 0;

    for (final GraphIndexDefinition<T> graphIndex : graphIndexes) {
      for (final Hotpath hotpath : graphIndex.hotpaths()) {
        final List<String> covered = new ArrayList<>();
        for (final String fieldName : hotpath.fieldNames()) {
          final GraphQueryCondition condition = conditions.get(fieldName);
          if (condition == null) {
            break;
          }
          if (condition.operation() != GraphQueryCondition.Operation.EQUAL_TO) {
            break;
          }
          covered.add(fieldName);
        }
        if (covered.size() > bestScore) {
          bestScore = covered.size();
          bestIndexName = graphIndex.name();
          bestCoveredFields = covered;
        }
      }
    }

    if (bestScore == 0) {
      return null;
    }

    final Object[] components = new Object[bestCoveredFields.size()];
    for (int i = 0; i < bestCoveredFields.size(); i++) {
      final GraphQueryCondition cond = conditions.get(bestCoveredFields.get(i));
      components[i] = cond.args()[0];
    }
    final CompositeKey key = CompositeKey.of(components);

    final List<GraphQueryCondition> postFilter = new ArrayList<>();
    for (final Map.Entry<String, GraphQueryCondition> entry : conditions.entrySet()) {
      if (!bestCoveredFields.contains(entry.getKey())) {
        postFilter.add(entry.getValue());
      }
    }

    return new Plan<>(bestIndexName, key, postFilter);
  }
}
