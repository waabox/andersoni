package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Defines how to extract a comparable index key from a domain object and
 * builds dual-structure indexes: a {@link HashMap} for O(1) equality lookups,
 * a {@link TreeMap} for O(log n + k) range queries, and an optional
 * reversed-key {@link TreeMap} for efficient {@code endsWith} operations on
 * {@link String} keys.
 *
 * <p>An {@code SortedIndexDefinition} specifies a named index and the
 * two-step key extraction path. The second function must return a type that
 * implements {@link Comparable}, enforced at compile time via the
 * {@link KeyStep#by(Function, Function)} method signature.
 *
 * <p>Instances are created using the fluent builder starting with
 * {@link #named(String)}.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @param <T> the type of domain objects being indexed
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class SortedIndexDefinition<T> {

  /** The name of this index. */
  private final String name;

  /** The composed function that extracts the index key from an item. */
  private final Function<T, ?> keyExtractor;

  /**
   * Creates a new sorted index definition.
   *
   * @param name         the index name, never null
   * @param keyExtractor the composed key extraction function, never null
   */
  private SortedIndexDefinition(final String name,
      final Function<T, ?> keyExtractor) {
    this.name = name;
    this.keyExtractor = keyExtractor;
  }

  /**
   * Starts building a new sorted index definition with the given name.
   *
   * <p>This is the entry point for the fluent builder. Follow with
   * {@link KeyStep#by(Function, Function)} to define the key extraction.
   *
   * @param name the name of the index, never null or empty
   * @param <T>  the type of domain objects being indexed
   *
   * @return the next step of the builder, never null
   *
   * @throws NullPointerException     if name is null
   * @throws IllegalArgumentException if name is empty
   */
  public static <T> KeyStep<T> named(final String name) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isEmpty()) {
      throw new IllegalArgumentException("name must not be empty");
    }
    return new KeyStep<>(name);
  }

  /**
   * Builds the sorted index from the given data by grouping items according
   * to the key extraction function defined in this definition.
   *
   * <p>The returned {@link SortedIndexResult} contains:
   * <ul>
   *   <li>A {@link HashMap} for O(1) equality lookups (supports null keys)
   *   </li>
   *   <li>A {@link TreeMap} for O(log n + k) range queries (null keys
   *       excluded)</li>
   *   <li>An optional reversed-key {@link TreeMap} for efficient
   *       {@code endsWith} operations, only built when keys are
   *       {@link String}</li>
   * </ul>
   *
   * <p>The {@link List} instances stored in all three maps are shared
   * (same object reference) to minimize memory usage. All returned
   * collections are unmodifiable.
   *
   * @param data the list of items to index, never null
   *
   * @return a {@link SortedIndexResult} containing the built indexes,
   *         never null
   *
   * @throws NullPointerException if data is null
   */
  @SuppressWarnings("unchecked")
  public SortedIndexResult<T> buildIndex(final List<T> data) {
    Objects.requireNonNull(data, "data must not be null");

    if (data.isEmpty()) {
      return SortedIndexResult.empty();
    }

    final Map<Object, List<T>> hashIndex = new HashMap<>();
    final TreeMap<Comparable<?>, List<T>> sortedIndex = new TreeMap<>();
    TreeMap<String, List<T>> reversedKeyIndex = null;

    boolean stringKeysDetected = false;
    boolean keyTypeResolved = false;

    for (final T item : data) {
      final Object key = keyExtractor.apply(item);

      // Get or create the shared list from the HashMap.
      List<T> bucket = hashIndex.get(key);
      if (bucket == null) {
        bucket = new ArrayList<>();
        hashIndex.put(key, bucket);
      }
      bucket.add(item);

      if (key != null) {

        // Detect key type from first non-null key.
        if (!keyTypeResolved) {
          stringKeysDetected = key instanceof String;
          if (stringKeysDetected) {
            reversedKeyIndex = new TreeMap<>();
          }
          keyTypeResolved = true;
        }

        // Add to TreeMap only if not already there (share the same list).
        if (!sortedIndex.containsKey((Comparable<?>) key)) {
          sortedIndex.put((Comparable<?>) key, bucket);
        }

        // Add reversed key to reversed TreeMap for String keys.
        if (stringKeysDetected) {
          final String reversed = new StringBuilder((String) key)
              .reverse().toString();
          if (!reversedKeyIndex.containsKey(reversed)) {
            reversedKeyIndex.put(reversed, bucket);
          }
        }
      }
    }

    return SortedIndexResult.of(
        hashIndex, sortedIndex, reversedKeyIndex, stringKeysDetected);
  }

  /**
   * Returns the name of this index.
   *
   * @return the index name, never null
   */
  public String name() {
    return name;
  }

  /**
   * Intermediate builder step that collects the key extraction functions
   * for a {@link SortedIndexDefinition}.
   *
   * @param <T> the type of domain objects being indexed
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static final class KeyStep<T> {

    /** The index name. */
    private final String name;

    /**
     * Creates a new key step.
     *
     * @param name the index name, never null
     */
    private KeyStep(final String name) {
      this.name = name;
    }

    /**
     * Defines the two-step key extraction by composing two functions.
     *
     * <p>The first function extracts an intermediate value from the
     * domain object, and the second function extracts the final comparable
     * index key from that intermediate value. The compiler enforces that
     * {@code K} extends {@link Comparable}, ensuring all keys in the
     * sorted index are naturally ordered.
     *
     * <p>If the first function returns null (null intermediate value),
     * the key is treated as null and only stored in the HashMap (not in
     * the TreeMap, which cannot hold null keys).
     *
     * @param first  the function to extract the intermediate value,
     *               never null
     * @param second the function to extract the comparable key from the
     *               intermediate value, never null
     * @param <I>    the type of the intermediate value
     * @param <K>    the type of the index key, must extend Comparable
     *
     * @return a fully configured sorted index definition, never null
     *
     * @throws NullPointerException if first or second is null
     */
    public <I, K extends Comparable<K>> SortedIndexDefinition<T> by(
        final Function<T, I> first, final Function<I, K> second) {

      Objects.requireNonNull(first, "first function must not be null");
      Objects.requireNonNull(second, "second function must not be null");

      final Function<T, ?> composed = item -> {
        final I intermediate = first.apply(item);
        if (intermediate == null) {
          return null;
        }
        return second.apply(intermediate);
      };
      return new SortedIndexDefinition<>(name, composed);
    }
  }

  /**
   * The result of building a sorted index, containing the three index
   * structures produced by
   * {@link SortedIndexDefinition#buildIndex(List)}.
   *
   * <p>All collections returned by this class are unmodifiable and will
   * throw {@link UnsupportedOperationException} on mutation attempts.
   *
   * <p>The {@link List} instances stored in all three maps are shared
   * (same object reference) to minimize memory usage.
   *
   * @param <T> the type of domain objects in the index
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static final class SortedIndexResult<T> {

    /** HashMap for O(1) equality lookups. Supports null keys. */
    private final Map<Object, List<T>> hashIndex;

    /** NavigableMap for O(log n + k) range queries. Null keys excluded. */
    private final NavigableMap<Comparable<?>, List<T>> sortedIndex;

    /** Reversed-key NavigableMap for endsWith queries. Null if keys are
     * not String. */
    private final NavigableMap<String, List<T>> reversedKeyIndex;

    /** Whether the keys in this index are Strings. */
    private final boolean hasStringKeys;

    /**
     * Creates a new sorted index result.
     *
     * @param hashIndex        the hash index, never null
     * @param sortedIndex      the sorted index, never null
     * @param reversedKeyIndex the reversed key index, null if keys are
     *                         not String
     * @param hasStringKeys    whether the keys are Strings
     */
    private SortedIndexResult(
        final Map<Object, List<T>> hashIndex,
        final NavigableMap<Comparable<?>, List<T>> sortedIndex,
        final NavigableMap<String, List<T>> reversedKeyIndex,
        final boolean hasStringKeys) {
      this.hashIndex = hashIndex;
      this.sortedIndex = sortedIndex;
      this.reversedKeyIndex = reversedKeyIndex;
      this.hasStringKeys = hasStringKeys;
    }

    /**
     * Creates a new sorted index result with unmodifiable collections.
     *
     * <p>The list values within each map are made unmodifiable while
     * preserving the shared reference identity across all three maps.
     *
     * @param hashIndex        the mutable hash index, never null
     * @param sortedIndex      the mutable sorted index, never null
     * @param reversedKeyIndex the mutable reversed key index, or null
     * @param hasStringKeys    whether the keys are Strings
     * @param <T>              the type of domain objects
     *
     * @return a new immutable sorted index result, never null
     */
    static <T> SortedIndexResult<T> of(
        final Map<Object, List<T>> hashIndex,
        final TreeMap<Comparable<?>, List<T>> sortedIndex,
        final TreeMap<String, List<T>> reversedKeyIndex,
        final boolean hasStringKeys) {

      // Make each list unmodifiable. We freeze the lists in the HashMap
      // first, then point the TreeMap and reversed TreeMap entries to
      // the same frozen lists so that reference identity is preserved.
      final Map<Object, List<T>> frozenHash = new HashMap<>();
      final Map<List<T>, List<T>> frozenListCache = new HashMap<>();

      for (final Map.Entry<Object, List<T>> entry : hashIndex.entrySet()) {
        final List<T> frozen = Collections.unmodifiableList(
            entry.getValue());
        frozenHash.put(entry.getKey(), frozen);
        frozenListCache.put(entry.getValue(), frozen);
      }

      final TreeMap<Comparable<?>, List<T>> frozenSorted = new TreeMap<>();
      for (final Map.Entry<Comparable<?>, List<T>> entry
          : sortedIndex.entrySet()) {
        frozenSorted.put(entry.getKey(),
            frozenListCache.getOrDefault(entry.getValue(),
                Collections.unmodifiableList(entry.getValue())));
      }

      NavigableMap<String, List<T>> frozenReversed = null;
      if (reversedKeyIndex != null) {
        final TreeMap<String, List<T>> reversedCopy = new TreeMap<>();
        for (final Map.Entry<String, List<T>> entry
            : reversedKeyIndex.entrySet()) {
          reversedCopy.put(entry.getKey(),
              frozenListCache.getOrDefault(entry.getValue(),
                  Collections.unmodifiableList(entry.getValue())));
        }
        frozenReversed = Collections.unmodifiableNavigableMap(reversedCopy);
      }

      return new SortedIndexResult<>(
          Collections.unmodifiableMap(frozenHash),
          Collections.unmodifiableNavigableMap(frozenSorted),
          frozenReversed,
          hasStringKeys);
    }

    /**
     * Creates an empty sorted index result.
     *
     * @param <T> the type of domain objects
     *
     * @return an empty result with no data, never null
     */
    static <T> SortedIndexResult<T> empty() {
      return new SortedIndexResult<>(
          Collections.emptyMap(),
          Collections.emptyNavigableMap(),
          null,
          false);
    }

    /**
     * Returns the hash index for O(1) equality lookups.
     *
     * <p>This map supports null keys for items whose key extractor
     * returned null.
     *
     * @return an unmodifiable map from keys to lists of matching items,
     *         never null
     */
    public Map<Object, List<T>> hashIndex() {
      return hashIndex;
    }

    /**
     * Returns the sorted index for O(log n + k) range queries.
     *
     * <p>This {@link NavigableMap} excludes null keys since
     * {@link TreeMap} cannot hold them. Use {@link #hashIndex()} to look
     * up items with null keys.
     *
     * <p>Callers can use {@link NavigableMap#subMap(Object, boolean,
     * Object, boolean)}, {@link NavigableMap#headMap(Object, boolean)},
     * and {@link NavigableMap#tailMap(Object, boolean)} for range queries.
     *
     * @return an unmodifiable navigable map from comparable keys to lists
     *         of matching items, never null
     */
    public NavigableMap<Comparable<?>, List<T>> sortedIndex() {
      return sortedIndex;
    }

    /**
     * Returns the reversed-key {@link NavigableMap} for efficient
     * {@code endsWith} queries on String keys.
     *
     * <p>Each key in this map is the reverse of the original String key.
     * This allows {@code endsWith("foo")} to be translated to a
     * {@code startsWith} prefix scan on the reversed map.
     *
     * @return an unmodifiable navigable map with reversed String keys,
     *         or null if the index keys are not Strings
     */
    public NavigableMap<String, List<T>> reversedKeyIndex() {
      return reversedKeyIndex;
    }

    /**
     * Returns whether the keys in this index are Strings.
     *
     * <p>When true, the {@link #reversedKeyIndex()} is available for
     * text pattern operations.
     *
     * @return true if keys are Strings, false otherwise
     */
    public boolean hasStringKeys() {
      return hasStringKeys;
    }
  }
}
