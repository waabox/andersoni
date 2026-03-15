package org.waabox.andersoni;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Defines how to extract values from a root entity by navigating its
 * relationships.
 *
 * <p>A traversal produces a set of distinct values for a given item.
 * These values become components of {@link CompositeKey}s when combined
 * in a hotpath.
 *
 * <p>This is a sealed interface with three permitted implementations:
 * {@link Simple}, {@link FanOut}, and {@link Path}.
 *
 * @param <T> the type of root entity being traversed
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public sealed interface Traversal<T> {

  /**
   * Returns the name that identifies this traversal within an index definition.
   *
   * @return the traversal name, never null or empty
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  String name();

  /**
   * Evaluates this traversal against the given item and returns the set of
   * extracted values.
   *
   * <p>Null values are never included in the result. The returned set is
   * unmodifiable.
   *
   * @param item the root entity to traverse, must not be null
   * @return an unmodifiable set of extracted values, never null, may be empty
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  Set<?> evaluate(T item);

  /**
   * Creates a {@link Simple} traversal that extracts a single value from the
   * root entity using the given extractor function.
   *
   * @param <T> the type of root entity
   * @param name the traversal name, must not be null or empty
   * @param extractor the function to extract the value, must not be null
   * @return a new {@link Simple} traversal
   * @throws NullPointerException if name or extractor is null
   * @throws IllegalArgumentException if name is empty
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  static <T> Traversal<T> simple(final String name, final Function<T, ?> extractor) {
    return new Simple<>(name, extractor);
  }

  /**
   * Creates a {@link FanOut} traversal that extracts a collection from the root
   * entity and then maps each element to a value.
   *
   * @param <T> the type of root entity
   * @param <C> the type of collection element
   * @param name the traversal name, must not be null or empty
   * @param collectionAccessor the function to extract the collection, must not be null
   * @param valueExtractor the function to extract a value from each element, must not be null
   * @return a new {@link FanOut} traversal
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if name is empty
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  static <T, C> Traversal<T> fanOut(final String name,
      final Function<T, ? extends Collection<C>> collectionAccessor,
      final Function<C, ?> valueExtractor) {
    return new FanOut<>(name, collectionAccessor, valueExtractor);
  }

  /**
   * Creates a {@link Path} traversal that extracts a delimited path string from
   * the root entity and produces all prefix segments as values.
   *
   * <p>For example, the path {@code "a/b/c"} with separator {@code "/"} produces
   * {@code {"a", "a/b", "a/b/c"}}.
   *
   * @param <T> the type of root entity
   * @param name the traversal name, must not be null or empty
   * @param separator the path segment separator, must not be null or empty
   * @param extractor the function to extract the path string, must not be null
   * @return a new {@link Path} traversal
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if name or separator is empty
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  static <T> Traversal<T> path(final String name, final String separator,
      final Function<T, String> extractor) {
    return new Path<>(name, separator, extractor);
  }

  /**
   * Validates that name is non-null, non-empty, and extractor is non-null.
   *
   * @param name the traversal name to validate
   * @param extractor the extractor object to validate
   * @throws NullPointerException if name or extractor is null
   * @throws IllegalArgumentException if name is empty
   */
  private static void validateParams(final String name, final Object extractor) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isEmpty()) {
      throw new IllegalArgumentException("name must not be empty");
    }
    Objects.requireNonNull(extractor, "extractor must not be null");
  }

  /**
   * Extracts a single value from the root entity using a provided function.
   *
   * <p>If the extractor returns null, the result is an empty set.
   *
   * @param <T> the type of root entity
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  record Simple<T>(String name, Function<T, ?> extractor) implements Traversal<T> {

    /** Compact canonical constructor that validates all parameters. */
    public Simple {
      validateParams(name, extractor);
    }

    @Override
    public Set<?> evaluate(final T item) {
      final Object value = extractor.apply(item);
      if (value == null) {
        return Set.of();
      }
      return Set.of(value);
    }
  }

  /**
   * Extracts a collection from the root entity and maps each element to a value,
   * returning the distinct non-null results.
   *
   * <p>Null elements in the collection and null extracted values are silently
   * skipped.
   *
   * @param <T> the type of root entity
   * @param <C> the type of collection element
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  record FanOut<T, C>(String name,
      Function<T, ? extends Collection<C>> collectionAccessor,
      Function<C, ?> valueExtractor) implements Traversal<T> {

    /** Compact canonical constructor that validates all parameters. */
    public FanOut {
      validateParams(name, collectionAccessor);
      Objects.requireNonNull(valueExtractor, "valueExtractor must not be null");
    }

    @Override
    public Set<?> evaluate(final T item) {
      final Collection<C> collection = collectionAccessor.apply(item);
      if (collection == null || collection.isEmpty()) {
        return Set.of();
      }
      final Set<Object> result = new LinkedHashSet<>();
      for (final C element : collection) {
        if (element == null) {
          continue;
        }
        final Object value = valueExtractor.apply(element);
        if (value != null) {
          result.add(value);
        }
      }
      return Set.copyOf(result);
    }
  }

  /**
   * Extracts a delimited path string from the root entity and returns all
   * cumulative prefix segments as distinct values.
   *
   * <p>Empty segments produced by leading, trailing, or consecutive separators
   * are ignored.
   *
   * @param <T> the type of root entity
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  record Path<T>(String name, String separator,
      Function<T, String> extractor) implements Traversal<T> {

    /** Compact canonical constructor that validates all parameters. */
    public Path {
      validateParams(name, extractor);
      Objects.requireNonNull(separator, "separator must not be null");
      if (separator.isEmpty()) {
        throw new IllegalArgumentException("separator must not be empty");
      }
    }

    @Override
    public Set<?> evaluate(final T item) {
      final String pathValue = extractor.apply(item);
      if (pathValue == null || pathValue.isEmpty()) {
        return Set.of();
      }
      final String[] segments = pathValue.split(java.util.regex.Pattern.quote(separator), -1);
      final Set<Object> result = new LinkedHashSet<>();
      final StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (final String segment : segments) {
        if (segment.isEmpty()) {
          continue;
        }
        if (!first) {
          sb.append(separator);
        }
        sb.append(segment);
        result.add(sb.toString());
        first = false;
      }
      return Set.copyOf(result);
    }
  }
}
