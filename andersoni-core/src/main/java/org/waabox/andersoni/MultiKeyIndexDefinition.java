package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Defines how to extract multiple index keys from a single domain object.
 *
 * <p>Unlike {@link IndexDefinition} which maps each item to a single key,
 * a {@code MultiKeyIndexDefinition} maps each item to a {@link List} of
 * keys. The item is indexed under every key in the list.
 *
 * <p>This is useful for hierarchical data where an item should be
 * discoverable from any ancestor in its hierarchy (e.g., a publication
 * indexed under all ancestor category IDs).
 *
 * <p>The output format is identical to {@link IndexDefinition#buildIndex},
 * so {@link Snapshot} requires no modifications.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @param <T> the type of domain objects being indexed
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class MultiKeyIndexDefinition<T> {

  /** The name of this index. */
  private final String name;

  /** The function that extracts multiple keys from an item. */
  private final Function<T, List<?>> keysExtractor;

  /**
   * Creates a new multi-key index definition.
   *
   * @param name          the index name, never null
   * @param keysExtractor the key extraction function, never null
   */
  private MultiKeyIndexDefinition(final String name, final Function<T, List<?>> keysExtractor) {
    this.name = name;
    this.keysExtractor = keysExtractor;
  }

  /**
   * Starts building a new multi-key index definition with the given name.
   *
   * <p>This is the entry point for the fluent builder. Follow with
   * {@link KeyStep#by(Function)} to define the key extraction.
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
   * the multi-key extraction function defined in this definition.
   *
   * <p>Each item is indexed under every key returned by the extractor.
   * Items whose extractor returns null or an empty list are skipped.
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
      final List<?> keys = keysExtractor.apply(item);
      if (keys == null || keys.isEmpty()) {
        continue;
      }
      for (final Object key : keys) {
        index.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
      }
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
      final List<?> keys = keysExtractor.apply(entry.item());
      if (keys == null || keys.isEmpty()) {
        continue;
      }
      for (final Object key : keys) {
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
   * Accumulates a single catalog item into the given index map under
   * all keys returned by the multi-key extractor.
   *
   * @param item  the catalog item to index, never null
   * @param index the mutable index map to accumulate into, never null
   */
  void accumulate(final AndersoniCatalogItem<T> item,
      final Map<Object, List<AndersoniCatalogItem<T>>> index) {
    final List<?> keys = keysExtractor.apply(item.item());
    if (keys == null || keys.isEmpty()) {
      return;
    }
    for (final Object key : keys) {
      if (key != null) {
        index.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
      }
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
   * Intermediate builder step that collects the key extraction function
   * for a {@link MultiKeyIndexDefinition}.
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
     * Defines the key extraction function that returns multiple keys
     * for each domain object.
     *
     * @param keysExtractor the function that extracts a list of keys
     *                      from a domain object, never null
     *
     * @return a fully configured multi-key index definition, never null
     *
     * @throws NullPointerException if keysExtractor is null
     */
    public MultiKeyIndexDefinition<T> by(final Function<T, List<?>> keysExtractor) {
      Objects.requireNonNull(keysExtractor, "keysExtractor must not be null");
      return new MultiKeyIndexDefinition<>(name, keysExtractor);
    }
  }
}
