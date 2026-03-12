package org.waabox.andersoni;

import java.util.Objects;

/**
 * Represents a single query condition within a {@link CompoundQuery}.
 *
 * <p>Each condition targets a named index and specifies an operation with
 * its arguments. Conditions are accumulated by the compound query builder
 * and evaluated lazily during {@link CompoundQuery#execute()}.
 *
 * <p>This is a package-private implementation detail not exposed to
 * application code.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
record Condition(String indexName, Operation operation, Object[] args) {

  enum Operation {
    EQUAL_TO,
    BETWEEN,
    GREATER_THAN,
    GREATER_OR_EQUAL,
    LESS_THAN,
    LESS_OR_EQUAL,
    STARTS_WITH,
    ENDS_WITH,
    CONTAINS
  }

  Condition {
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(args, "args must not be null");
  }
}
