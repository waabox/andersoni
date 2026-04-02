package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Defines a graph-based composite index that navigates entity relationships
 * via traversals and pre-computes hotpath keys for O(1) lookups.
 *
 * <p>Each traversal extracts named values from the root entity. Hotpaths
 * declare which traversal combinations to pre-compute as
 * {@link CompositeKey} entries. The output is a standard
 * {@code Map<Object, List<T>>} compatible with {@link Snapshot}.
 *
 * <p>Instances are created using the fluent builder starting with
 * {@link #named(String)}.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @param <T> the type of root entity being indexed
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class GraphIndexDefinition<T> {

  private final String name;
  private final Map<String, Traversal<T>> traversals;
  private final List<Hotpath> hotpaths;
  private final int maxKeysPerItem;

  private GraphIndexDefinition(final String name,
      final Map<String, Traversal<T>> traversals,
      final List<Hotpath> hotpaths, final int maxKeysPerItem) {
    this.name = name;
    this.traversals = traversals;
    this.hotpaths = hotpaths;
    this.maxKeysPerItem = maxKeysPerItem;
  }

  /**
   * Starts building a new {@link GraphIndexDefinition} with the given name.
   *
   * <p>The name identifies this index definition within a catalog and is
   * used in error messages.
   *
   * @param <T> the type of root entity being indexed
   * @param name the index name, must not be null or empty
   * @return a new {@link Builder} to configure the index definition
   * @throws NullPointerException if name is null
   * @throws IllegalArgumentException if name is empty
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static <T> Builder<T> named(final String name) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isEmpty()) {
      throw new IllegalArgumentException("name must not be empty");
    }
    return new Builder<>(name);
  }

  /**
   * Returns the name of this index definition.
   *
   * @return the index name, never null or empty
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public String name() {
    return name;
  }

  /**
   * Returns the hotpaths configured for this index definition.
   *
   * @return an unmodifiable list of hotpaths, never null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<Hotpath> hotpaths() {
    return hotpaths;
  }

  /**
   * Returns the traversals configured for this index definition, keyed by
   * traversal name.
   *
   * @return an unmodifiable map of traversal name to traversal, never null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public Map<String, Traversal<T>> traversals() {
    return traversals;
  }

  /**
   * Builds a complete index from the given data by evaluating all traversals
   * against each item and generating composite keys for each hotpath prefix.
   *
   * <p>The resulting map associates each {@link CompositeKey} with the list
   * of items that match it. Items are deduplicated per key using identity
   * comparison.
   *
   * <p>For each hotpath, all left-to-right prefixes are generated. When a
   * traversal returns multiple values (fan-out), the cartesian product of
   * values is computed. When a traversal returns empty (null value), no
   * further prefixes are generated for that hotpath beyond the last
   * non-empty traversal.
   *
   * @param data the list of items to index, must not be null
   * @return an unmodifiable map of composite keys to unmodifiable item lists
   * @throws NullPointerException if data is null
   * @throws IndexKeyLimitExceededException if any single item generates more
   *     keys than the configured {@code maxKeysPerItem}
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public Map<Object, List<T>> buildIndex(final List<T> data) {
    Objects.requireNonNull(data, "data must not be null");
    if (data.isEmpty()) {
      return Collections.emptyMap();
    }
    final Map<CompositeKey, Set<T>> keyToItems = new HashMap<>();
    for (final T item : data) {
      final Set<CompositeKey> allKeys = new LinkedHashSet<>();
      for (final Hotpath hotpath : hotpaths) {
        final List<Set<?>> traversalValues = new ArrayList<>();
        boolean hasNullTraversal = false;
        for (final String fieldName : hotpath.fieldNames()) {
          final Traversal<T> traversal = traversals.get(fieldName);
          final Set<?> values = traversal.evaluate(item);
          if (values.isEmpty()) {
            hasNullTraversal = true;
            break;
          }
          traversalValues.add(values);
        }
        if (hasNullTraversal && traversalValues.isEmpty()) {
          continue;
        }
        for (int prefixLen = 1; prefixLen <= traversalValues.size(); prefixLen++) {
          final List<Set<?>> prefixValues = traversalValues.subList(0, prefixLen);
          generateCartesianKeys(prefixValues, allKeys);
        }
      }
      if (allKeys.size() > maxKeysPerItem) {
        throw new IndexKeyLimitExceededException(name, item.toString(),
            allKeys.size(), maxKeysPerItem);
      }
      for (final CompositeKey key : allKeys) {
        keyToItems.computeIfAbsent(key,
            k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(item);
      }
    }
    final Map<Object, List<T>> result = new HashMap<>();
    for (final Map.Entry<CompositeKey, Set<T>> entry : keyToItems.entrySet()) {
      result.put(entry.getKey(),
          Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
    }
    return Collections.unmodifiableMap(result);
  }

  /**
   * Accumulates a single catalog item into the given graph index
   * accumulator by evaluating all hotpaths and traversals.
   *
   * @param item        the catalog item to index, never null
   * @param accumulator the mutable accumulator map, never null
   */
  void accumulate(final AndersoniCatalogItem<T> item,
      final Map<Object, Set<AndersoniCatalogItem<T>>> accumulator) {
    final T domainItem = item.item();
    final Set<CompositeKey> allKeys = new LinkedHashSet<>();
    for (final Hotpath hotpath : hotpaths) {
      final List<Set<?>> traversalValues = new ArrayList<>();
      boolean hasNullTraversal = false;
      for (final String fieldName : hotpath.fieldNames()) {
        final Traversal<T> traversal = traversals.get(fieldName);
        final Set<?> values = traversal.evaluate(domainItem);
        if (values.isEmpty()) {
          hasNullTraversal = true;
          break;
        }
        traversalValues.add(values);
      }
      if (hasNullTraversal && traversalValues.isEmpty()) {
        continue;
      }
      for (int prefixLen = 1; prefixLen <= traversalValues.size(); prefixLen++) {
        final List<Set<?>> prefixValues = traversalValues.subList(0, prefixLen);
        generateCartesianKeys(prefixValues, allKeys);
      }
    }
    if (allKeys.size() > maxKeysPerItem) {
      throw new IndexKeyLimitExceededException(name, domainItem.toString(),
          allKeys.size(), maxKeysPerItem);
    }
    for (final CompositeKey key : allKeys) {
      accumulator.computeIfAbsent(key,
          k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(item);
    }
  }

  /**
   * Generates the cartesian product of the given value sets and adds each
   * combination as a {@link CompositeKey} to the target set.
   *
   * @param valueSets the ordered list of value sets to combine
   * @param target the set to add generated keys to
   */
  private void generateCartesianKeys(final List<Set<?>> valueSets,
      final Set<CompositeKey> target) {
    generateCartesianKeysRecursive(valueSets, 0, new ArrayList<>(), target);
  }

  /**
   * Recursive helper that builds composite keys by iterating through each
   * depth level of the value sets.
   *
   * @param valueSets the ordered list of value sets
   * @param depth the current recursion depth (value set index)
   * @param current the components accumulated so far
   * @param target the set to add completed keys to
   */
  private void generateCartesianKeysRecursive(
      final List<Set<?>> valueSets, final int depth,
      final List<Object> current, final Set<CompositeKey> target) {
    if (depth == valueSets.size()) {
      target.add(CompositeKey.ofList(current));
      return;
    }
    for (final Object value : valueSets.get(depth)) {
      final List<Object> next = new ArrayList<>(current);
      next.add(value);
      generateCartesianKeysRecursive(valueSets, depth + 1, next, target);
    }
  }

  /**
   * Fluent builder for {@link GraphIndexDefinition}.
   *
   * <p>Traversals are added via {@link #traverse}, {@link #traverseMany},
   * and {@link #traversePath}. Hotpaths are declared via {@link #hotpath}.
   * At least one traversal and one hotpath are required.
   *
   * @param <T> the type of root entity being indexed
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static final class Builder<T> {
    private final String name;
    private final Map<String, Traversal<T>> traversals;
    private final List<Hotpath> hotpaths;
    private int maxKeysPerItem = 100;

    private Builder(final String name) {
      this.name = name;
      this.traversals = new LinkedHashMap<>();
      this.hotpaths = new ArrayList<>();
    }

    /**
     * Adds a simple traversal that extracts a single value from the root
     * entity.
     *
     * @param traversalName the unique name for this traversal, must not be
     *     null or empty
     * @param extractor the function to extract the value, must not be null
     * @return this builder for fluent chaining
     * @throws IllegalArgumentException if a traversal with the same name
     *     already exists
     *
     * @author waabox(waabox[at]gmail[dot]com)
     */
    public Builder<T> traverse(final String traversalName,
        final Function<T, ?> extractor) {
      addTraversal(Traversal.simple(traversalName, extractor));
      return this;
    }

    /**
     * Adds a fan-out traversal that extracts a collection from the root
     * entity and maps each element to a value.
     *
     * @param <C> the type of collection element
     * @param traversalName the unique name for this traversal, must not be
     *     null or empty
     * @param collectionAccessor the function to extract the collection,
     *     must not be null
     * @param valueExtractor the function to extract a value from each
     *     element, must not be null
     * @return this builder for fluent chaining
     * @throws IllegalArgumentException if a traversal with the same name
     *     already exists
     *
     * @author waabox(waabox[at]gmail[dot]com)
     */
    public <C> Builder<T> traverseMany(final String traversalName,
        final Function<T, ? extends Collection<C>> collectionAccessor,
        final Function<C, ?> valueExtractor) {
      addTraversal(Traversal.fanOut(traversalName, collectionAccessor, valueExtractor));
      return this;
    }

    /**
     * Adds a path traversal that extracts a delimited path string and
     * produces all prefix segments as values.
     *
     * @param traversalName the unique name for this traversal, must not be
     *     null or empty
     * @param separator the path segment separator, must not be null or empty
     * @param extractor the function to extract the path string, must not
     *     be null
     * @return this builder for fluent chaining
     * @throws IllegalArgumentException if a traversal with the same name
     *     already exists
     *
     * @author waabox(waabox[at]gmail[dot]com)
     */
    public Builder<T> traversePath(final String traversalName,
        final String separator, final Function<T, String> extractor) {
      addTraversal(Traversal.path(traversalName, separator, extractor));
      return this;
    }

    /**
     * Declares a hotpath specifying which traversal fields to combine as
     * composite key prefixes.
     *
     * <p>Multiple hotpaths can be declared; their keys are merged into
     * a single index map. Field names must reference traversals added
     * via {@link #traverse}, {@link #traverseMany}, or
     * {@link #traversePath}.
     *
     * @param fieldNames the ordered traversal names to combine, must not
     *     be null or empty
     * @return this builder for fluent chaining
     *
     * @author waabox(waabox[at]gmail[dot]com)
     */
    public Builder<T> hotpath(final String... fieldNames) {
      hotpaths.add(Hotpath.of(fieldNames));
      return this;
    }

    /**
     * Sets the maximum number of composite keys that may be generated for
     * a single item before an {@link IndexKeyLimitExceededException} is
     * thrown.
     *
     * <p>The default limit is 100.
     *
     * @param max the maximum keys per item, must be positive
     * @return this builder for fluent chaining
     * @throws IllegalArgumentException if max is not positive
     *
     * @author waabox(waabox[at]gmail[dot]com)
     */
    public Builder<T> maxKeysPerItem(final int max) {
      if (max <= 0) {
        throw new IllegalArgumentException("maxKeysPerItem must be positive, got: " + max);
      }
      this.maxKeysPerItem = max;
      return this;
    }

    /**
     * Builds an immutable {@link GraphIndexDefinition} from the current
     * builder state.
     *
     * <p>Validates that at least one traversal and one hotpath are
     * configured, and that all hotpath field names reference known
     * traversals.
     *
     * @return a new immutable {@link GraphIndexDefinition}
     * @throws IllegalStateException if no traversals or no hotpaths are
     *     configured
     * @throws IllegalArgumentException if any hotpath references an
     *     unknown traversal
     *
     * @author waabox(waabox[at]gmail[dot]com)
     */
    public GraphIndexDefinition<T> build() {
      if (traversals.isEmpty()) {
        throw new IllegalStateException("At least one traversal is required");
      }
      if (hotpaths.isEmpty()) {
        throw new IllegalStateException("At least one hotpath is required");
      }
      for (final Hotpath hp : hotpaths) {
        for (final String fieldName : hp.fieldNames()) {
          if (!traversals.containsKey(fieldName)) {
            throw new IllegalArgumentException(
                "Hotpath references unknown traversal: '" + fieldName + "'");
          }
        }
      }
      return new GraphIndexDefinition<>(name,
          Collections.unmodifiableMap(new LinkedHashMap<>(traversals)),
          Collections.unmodifiableList(new ArrayList<>(hotpaths)),
          maxKeysPerItem);
    }

    /**
     * Adds a traversal to the internal map, checking for duplicate names.
     *
     * @param traversal the traversal to add
     * @throws IllegalArgumentException if a traversal with the same name
     *     already exists
     */
    private void addTraversal(final Traversal<T> traversal) {
      if (traversals.containsKey(traversal.name())) {
        throw new IllegalArgumentException(
            "Duplicate traversal name: '" + traversal.name() + "'");
      }
      traversals.put(traversal.name(), traversal);
    }
  }
}
