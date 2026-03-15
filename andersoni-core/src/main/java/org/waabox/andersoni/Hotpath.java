package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Declares an ordered combination of traversal field names to pre-compute
 * as composite keys at indexing time.
 *
 * <p>The field order defines the left-to-right prefix order, analogous to
 * a composite B-tree index in a relational database.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class Hotpath {

  private final List<String> fieldNames;

  private Hotpath(final List<String> fieldNames) {
    this.fieldNames = fieldNames;
  }

  /**
   * Creates a new {@code Hotpath} from the given ordered field names.
   *
   * <p>The order of the field names defines the traversal prefix order for
   * composite key generation. No duplicate or blank names are allowed.
   *
   * @param fieldNames one or more non-null, non-empty field names in traversal order; must not
   *     be null or empty
   * @return a new immutable {@code Hotpath}
   * @throws NullPointerException if {@code fieldNames} is null or any element is null
   * @throws IllegalArgumentException if {@code fieldNames} is empty, any element is blank,
   *     or any duplicate name is present
   */
  public static Hotpath of(final String... fieldNames) {
    Objects.requireNonNull(fieldNames, "fieldNames must not be null");
    if (fieldNames.length == 0) {
      throw new IllegalArgumentException("fieldNames must not be empty");
    }
    final Set<String> seen = new HashSet<>();
    final List<String> list = new ArrayList<>(fieldNames.length);
    for (final String name : fieldNames) {
      Objects.requireNonNull(name, "fieldName must not be null");
      if (name.isEmpty()) {
        throw new IllegalArgumentException("fieldName must not be empty");
      }
      if (!seen.add(name)) {
        throw new IllegalArgumentException("Duplicate field name: '" + name + "'");
      }
      list.add(name);
    }
    return new Hotpath(Collections.unmodifiableList(list));
  }

  /**
   * Returns the ordered list of field names declared in this hotpath.
   *
   * @return an unmodifiable list of field names in declaration order
   */
  public List<String> fieldNames() {
    return fieldNames;
  }

  /**
   * Returns the number of field names in this hotpath.
   *
   * @return the number of fields
   */
  public int size() {
    return fieldNames.size();
  }
}
