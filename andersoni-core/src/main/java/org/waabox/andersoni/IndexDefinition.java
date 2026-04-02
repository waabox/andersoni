package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Defines how to extract an index key from a domain object using composed
 * functions.
 *
 * <p>An {@code IndexDefinition} specifies a named index and the two-step
 * key extraction path. For example, to index events by venue name, you
 * compose {@code Event::getVenue} with {@code Venue::getName}.
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
public final class IndexDefinition<T> {

  /** The name of this index. */
  private final String name;

  /** The composed function that extracts the index key from an item. */
  private final Function<T, ?> keyExtractor;

  /**
   * Creates a new index definition.
   *
   * @param name         the index name, never null
   * @param keyExtractor the composed key extraction function, never null
   */
  private IndexDefinition(final String name,
      final Function<T, ?> keyExtractor) {
    this.name = name;
    this.keyExtractor = keyExtractor;
  }

  /**
   * Starts building a new index definition with the given name.
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
   * Builds an index from the given data by grouping items according to
   * the key extraction function defined in this definition.
   *
   * <p>The returned map is unmodifiable, and each list value within
   * the map is also unmodifiable.
   *
   * @param data the list of items to index, never null
   *
   * @return an unmodifiable map from keys to lists of matching items,
   *         never null
   *
   * @throws NullPointerException if data is null
   */
  public Map<Object, List<T>> buildIndex(final List<T> data) {
    Objects.requireNonNull(data, "data must not be null");

    if (data.isEmpty()) {
      return Collections.emptyMap();
    }

    final Map<Object, List<T>> index = new HashMap<>();

    for (final T item : data) {
      final Object key = keyExtractor.apply(item);
      index.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
    }

    // Make each list unmodifiable.
    final Map<Object, List<T>> unmodifiable = new HashMap<>();
    for (final Map.Entry<Object, List<T>> entry : index.entrySet()) {
      unmodifiable.put(entry.getKey(),
          Collections.unmodifiableList(entry.getValue()));
    }

    return Collections.unmodifiableMap(unmodifiable);
  }

  /**
   * Builds an index from catalog items, using the item's domain object
   * for key extraction but storing the full wrapper.
   *
   * @param items the catalog items to index, never null
   *
   * @return the index mapping keys to lists of catalog items, never null
   */
  Map<Object, List<AndersoniCatalogItem<T>>> buildIndexFromItems(
      final List<AndersoniCatalogItem<T>> items) {
    if (items.isEmpty()) {
      return Collections.emptyMap();
    }

    final Map<Object, List<AndersoniCatalogItem<T>>> index = new HashMap<>();

    for (final AndersoniCatalogItem<T> entry : items) {
      final Object key = keyExtractor.apply(entry.item());
      if (key != null) {
        index.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
      }
    }

    final Map<Object, List<AndersoniCatalogItem<T>>> unmodifiable =
        new HashMap<>();
    for (final Map.Entry<Object, List<AndersoniCatalogItem<T>>> e
        : index.entrySet()) {
      unmodifiable.put(e.getKey(),
          Collections.unmodifiableList(e.getValue()));
    }

    return Collections.unmodifiableMap(unmodifiable);
  }

  /**
   * Accumulates a single catalog item into the given index map.
   *
   * <p>Extracts the key from the item's domain object and adds the
   * catalog item to the corresponding bucket. If the key is null,
   * the item is skipped.
   *
   * @param item  the catalog item to index, never null
   * @param index the mutable index map to accumulate into, never null
   */
  void accumulate(final AndersoniCatalogItem<T> item,
      final Map<Object, List<AndersoniCatalogItem<T>>> index) {
    final Object key = keyExtractor.apply(item.item());
    if (key != null) {
      index.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
    }
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
   * for an {@link IndexDefinition}.
   *
   * @param <T> the type of domain objects being indexed
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
     * domain object, and the second function extracts the final index
     * key from that intermediate value. For example:
     * {@code by(Event::venue, Venue::name)} extracts the venue name.
     *
     * @param first  the function to extract the intermediate value,
     *               never null
     * @param second the function to extract the key from the
     *               intermediate value, never null
     * @param <I>    the type of the intermediate value
     *
     * @return a fully configured index definition, never null
     *
     * @throws NullPointerException if first or second is null
     */
    public <I> IndexDefinition<T> by(final Function<T, I> first,
        final Function<I, ?> second) {

      Objects.requireNonNull(first, "first function must not be null");
      Objects.requireNonNull(second, "second function must not be null");

      final Function<T, ?> composed = item -> {
        final I intermediate = first.apply(item);
        if (intermediate == null) {
          return null;
        }
        return second.apply(intermediate);
      };
      return new IndexDefinition<>(name, composed);
    }
  }
}
