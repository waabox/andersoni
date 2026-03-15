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
 * <p>This class is immutable and thread-safe.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class CompositeKey {

  private final List<Object> components;
  private final int hash;

  private CompositeKey(final List<Object> components) {
    this.components = components;
    this.hash = components.hashCode();
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
    return new CompositeKey(Collections.unmodifiableList(list));
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
    return new CompositeKey(Collections.unmodifiableList(list));
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
    return components.equals(other.components);
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
    for (final Object c : components) {
      joiner.add(c.toString());
    }
    return joiner.toString();
  }
}
