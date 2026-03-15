package org.waabox.andersoni;

import java.util.Objects;

/**
 * Intermediate step for specifying the operation on a graph query condition.
 *
 * <p>Instances are returned by {@link GraphQueryBuilder#where(String)} and
 * {@link GraphQueryBuilder#and(String)} to complete a condition declaration
 * before returning control to the parent builder for further chaining.
 *
 * @param <T> the item type
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class GraphQueryConditionStep<T> {

  private final GraphQueryBuilder<T> builder;
  private final String fieldName;

  /**
   * Creates a new condition step bound to the given builder and field name.
   *
   * @param builder   the parent builder, never null
   * @param fieldName the traversal field name for this condition, never null
   */
  GraphQueryConditionStep(final GraphQueryBuilder<T> builder,
      final String fieldName) {
    this.builder = builder;
    this.fieldName = fieldName;
  }

  /**
   * Completes this condition with an equality check against the given value.
   *
   * <p>Registers a {@link GraphQueryCondition} with operation
   * {@link GraphQueryCondition.Operation#EQUAL_TO} on the parent builder and
   * returns the builder for further chaining.
   *
   * @param value the value to match, never null
   * @return the parent builder for chaining, never null
   * @throws NullPointerException if value is null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public GraphQueryBuilder<T> eq(final Object value) {
    Objects.requireNonNull(value, "value must not be null");
    builder.addCondition(new GraphQueryCondition(fieldName,
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{value}));
    return builder;
  }
}
