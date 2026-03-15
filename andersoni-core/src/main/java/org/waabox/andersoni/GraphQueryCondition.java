package org.waabox.andersoni;

import java.util.Objects;

/**
 * Represents a query condition for graph-based composite index queries.
 *
 * <p>References traversal field names (e.g., "country", "category") rather
 * than index names. This is distinct from the existing {@link Condition}
 * used by {@link CompoundQuery}.
 *
 * @param fieldName the traversal field name, never null
 * @param operation the operation to apply, never null
 * @param args      the operation arguments, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record GraphQueryCondition(String fieldName, Operation operation, Object[] args) {

  /**
   * The set of supported operations for graph query conditions.
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public enum Operation {
    /** Matches items whose indexed value equals the single argument. */
    EQUAL_TO
  }

  /**
   * Compact canonical constructor; validates all fields.
   *
   * @param fieldName the traversal field name, must not be null
   * @param operation the operation to apply, must not be null
   * @param args      the operation arguments, must not be null
   * @throws NullPointerException if any parameter is null
   */
  public GraphQueryCondition {
    Objects.requireNonNull(fieldName, "fieldName must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(args, "args must not be null");
  }
}
