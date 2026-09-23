package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Immutable value object representing a multi-component key for composite
 * index lookups.
 *
 * <p>Components are compared using {@link Object#equals(Object)} and
 * {@link Object#hashCode()}. All lookups use {@link java.util.HashMap},
 * so this class does not implement {@link Comparable}.
 *
 * <p>Keys stored in a graph index are also qualified by the traversal names
 * they were built from, so equal values on different fields never collide.
 * Keys created through the public factories carry no field names.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class CompositeKey {

  private final List<Object> components;
  private final List<String> fields;
  private final int hash;

  private CompositeKey(final List<Object> components, final List<String> fields) {
    this.components = components;
    this.fields = fields;
    this.hash = 31 * components.hashCode() + Objects.hashCode(fields);
  }

  /**
   * Creates a {@link CompositeKey} from the given varargs components.
   *
   * <p>Each component must be non-null. The resulting key is immutable and
   * suitable for use as a {@link java.util.HashMap} key.
   *
   * @param components the key components; must not be null or empty, and no element may be null
   * @return a new {@link CompositeKey} wrapping the provided components
   * @throws NullPointerException if {@code components} is null or any element is null
   * @throws IllegalArgumentException if {@code components} is empty
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static CompositeKey of(final Object... components) {
    Objects.requireNonNull(components, "components must not be null");
    if (components.length == 0) {
      throw new IllegalArgumentException("components must not be empty");
    }
    final List<Object> list = new ArrayList<>(components.length);
    for (final Object c : components) {
      Objects.requireNonNull(c, "component must not be null");
      list.add(c);
    }
    return new CompositeKey(Collections.unmodifiableList(list), null);
  }

  /**
   * Creates a {@link CompositeKey} from the given list of components.
   *
   * <p>Each component must be non-null. The resulting key is immutable and
   * suitable for use as a {@link java.util.HashMap} key.
   *
   * @param components the key components; must not be null or empty, and no element may be null
   * @return a new {@link CompositeKey} wrapping the provided components
   * @throws NullPointerException if {@code components} is null or any element is null
   * @throws IllegalArgumentException if {@code components} is empty
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static CompositeKey ofList(final List<Object> components) {
    Objects.requireNonNull(components, "components must not be null");
    if (components.isEmpty()) {
      throw new IllegalArgumentException("components must not be empty");
    }
    final List<Object> list = new ArrayList<>(components.size());
    for (final Object c : components) {
      Objects.requireNonNull(c, "component must not be null");
      list.add(c);
    }
    return new CompositeKey(Collections.unmodifiableList(list), null);
  }

  /**
   * Creates a graph index key qualified by the traversal names its components
   * belong to.
   *
   * @param fields     the traversal names, one per component, never null
   * @param components the key components, never null, no null elements
   * @return a new qualified key, never null
   * @throws IllegalArgumentException if the sizes differ
   */
  static CompositeKey qualified(final List<String> fields, final List<Object> components) {
    Objects.requireNonNull(fields, "fields must not be null");
    final CompositeKey key = ofList(components);
    if (fields.size() != key.size()) {
      throw new IllegalArgumentException("fields and components must have the same size");
    }
    return new CompositeKey(key.components, List.copyOf(fields));
  }

  /**
   * Returns the ordered list of components that make up this key.
   *
   * @return an unmodifiable list of components; never null
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<Object> components() {
    return components;
  }

  /**
   * Returns the number of components in this key.
   *
   * @return the component count; always &gt;= 1
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public int size() {
    return components.size();
  }

  /** {@inheritDoc} */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CompositeKey other)) {
      return false;
    }
    return components.equals(other.components) && Objects.equals(fields, other.fields);
  }

  /** {@inheritDoc} */
  @Override
  public int hashCode() {
    return hash;
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    final StringJoiner joiner = new StringJoiner(", ", "CompositeKey[", "]");
    for (int i = 0; i < components.size(); i++) {
      final String value = components.get(i).toString();
      joiner.add(fields == null ? value : fields.get(i) + "=" + value);
    }
    return joiner.toString();
  }
}
