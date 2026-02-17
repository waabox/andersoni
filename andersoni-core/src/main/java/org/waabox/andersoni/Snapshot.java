package org.waabox.andersoni;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * An immutable point-in-time view of catalog data and its indices.
 *
 * <p>Snapshots capture the complete state of a catalog at a specific moment,
 * including the raw data items and any pre-built indices for fast lookups.
 * They are designed to be held via {@link java.util.concurrent.atomic
 * .AtomicReference} for lock-free reads in concurrent environments.
 *
 * <p>All collections returned by this class are unmodifiable and will throw
 * {@link UnsupportedOperationException} on mutation attempts.
 *
 * <p>Instances are created through the static factory methods {@link #of}
 * and {@link #empty()}.
 *
 * @param <T> the type of data items held in this snapshot
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class Snapshot<T> {

  /** The data items in this snapshot. */
  private final List<T> data;

  /** The indices mapping index name to key-to-items mappings. */
  private final Map<String, Map<Object, List<T>>> indices;

  /** Sorted indices mapping index name to navigable key-to-items mappings. */
  private final Map<String, NavigableMap<Comparable<?>, List<T>>> sortedIndices;

  /** Reversed-key indices for efficient endsWith queries on String keys. */
  private final Map<String, NavigableMap<String, List<T>>> reversedKeyIndices;

  /** The version number of this snapshot. */
  private final long version;

  /** The hash identifying the content of this snapshot. */
  private final String hash;

  /** The instant when this snapshot was created. */
  private final Instant createdAt;

  /**
   * Creates a new snapshot.
   *
   * @param data               the data items, never null
   * @param indices            the indices, never null
   * @param sortedIndices      the sorted indices, never null
   * @param reversedKeyIndices the reversed-key indices, never null
   * @param version            the version number
   * @param hash               the content hash, never null
   * @param createdAt          the creation timestamp, never null
   */
  private Snapshot(final List<T> data,
      final Map<String, Map<Object, List<T>>> indices,
      final Map<String, NavigableMap<Comparable<?>, List<T>>> sortedIndices,
      final Map<String, NavigableMap<String, List<T>>> reversedKeyIndices,
      final long version, final String hash, final Instant createdAt) {
    this.data = data;
    this.indices = indices;
    this.sortedIndices = sortedIndices;
    this.reversedKeyIndices = reversedKeyIndices;
    this.version = version;
    this.hash = hash;
    this.createdAt = createdAt;
  }

  /**
   * Creates a new snapshot from the given data and indices.
   *
   * <p>All provided collections are defensively copied into unmodifiable
   * structures. The creation timestamp is set to the current instant.
   *
   * @param data    the list of data items, never null
   * @param indices the index definitions mapping index name to
   *                key-to-items mappings, never null
   * @param version the version number for this snapshot
   * @param hash    the content hash identifying this snapshot, never null
   * @param <T>     the type of data items
   *
   * @return a new immutable snapshot, never null
   *
   * @throws NullPointerException if data, indices, or hash is null
   */
  public static <T> Snapshot<T> of(final List<T> data,
      final Map<String, Map<Object, List<T>>> indices,
      final long version, final String hash) {

    return of(data, indices, Collections.emptyMap(), Collections.emptyMap(),
        version, hash);
  }

  /**
   * Creates a new snapshot from the given data, indices, sorted indices,
   * and reversed-key indices.
   *
   * <p>All provided collections are defensively copied into unmodifiable
   * structures. The creation timestamp is set to the current instant.
   *
   * @param data               the list of data items, never null
   * @param indices            the index definitions mapping index name to
   *                           key-to-items mappings, never null
   * @param sortedIndices      the sorted index definitions mapping index
   *                           name to navigable key-to-items mappings,
   *                           never null
   * @param reversedKeyIndices the reversed-key index definitions for
   *                           efficient endsWith queries, never null
   * @param version            the version number for this snapshot
   * @param hash               the content hash identifying this snapshot,
   *                           never null
   * @param <T>                the type of data items
   *
   * @return a new immutable snapshot, never null
   *
   * @throws NullPointerException if any argument is null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static <T> Snapshot<T> of(final List<T> data,
      final Map<String, Map<Object, List<T>>> indices,
      final Map<String, NavigableMap<Comparable<?>, List<T>>> sortedIndices,
      final Map<String, NavigableMap<String, List<T>>> reversedKeyIndices,
      final long version, final String hash) {

    Objects.requireNonNull(data, "data must not be null");
    Objects.requireNonNull(indices, "indices must not be null");
    Objects.requireNonNull(sortedIndices, "sortedIndices must not be null");
    Objects.requireNonNull(reversedKeyIndices,
        "reversedKeyIndices must not be null");
    Objects.requireNonNull(hash, "hash must not be null");

    final List<T> immutableData = Collections.unmodifiableList(
        List.copyOf(data));

    final Map<String, Map<Object, List<T>>> immutableIndices =
        copyIndices(indices);

    final Map<String, NavigableMap<Comparable<?>, List<T>>> immutableSorted =
        copySortedIndices(sortedIndices);

    final Map<String, NavigableMap<String, List<T>>> immutableReversed =
        copyReversedKeyIndices(reversedKeyIndices);

    return new Snapshot<>(immutableData, immutableIndices, immutableSorted,
        immutableReversed, version, hash, Instant.now());
  }

  /**
   * Creates an empty snapshot with version 0, an empty hash, no data,
   * and no indices.
   *
   * <p>This is useful as the initial state for a catalog that has not
   * yet loaded its data.
   *
   * @param <T> the type of data items
   *
   * @return an empty snapshot, never null
   */
  public static <T> Snapshot<T> empty() {
    return new Snapshot<>(Collections.emptyList(), Collections.emptyMap(),
        Collections.emptyMap(), Collections.emptyMap(), 0L, "", Instant.now());
  }

  /**
   * Searches the specified index for items matching the given key.
   *
   * <p>Returns the list of items associated with the key in the named
   * index. If the index does not exist, or the key is not present in
   * the index, an empty list is returned. This method never returns null.
   *
   * @param indexName the name of the index to search, never null
   * @param key       the key to look up in the index, never null
   *
   * @return an unmodifiable list of matching items, never null
   */
  public List<T> search(final String indexName, final Object key) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(key, "key must not be null");
    final Map<Object, List<T>> index = indices.get(indexName);
    if (index == null) {
      return Collections.emptyList();
    }
    final List<T> result = index.get(key);
    if (result == null) {
      return Collections.emptyList();
    }
    return result;
  }

  /**
   * Returns the unmodifiable list of all data items in this snapshot.
   *
   * @return the data items, never null
   */
  public List<T> data() {
    return data;
  }

  /**
   * Returns the version number of this snapshot.
   *
   * @return the version number
   */
  public long version() {
    return version;
  }

  /**
   * Returns the content hash identifying this snapshot.
   *
   * @return the hash string, never null
   */
  public String hash() {
    return hash;
  }

  /**
   * Returns the instant when this snapshot was created.
   *
   * @return the creation timestamp, never null
   */
  public Instant createdAt() {
    return createdAt;
  }

  // -----------------------------------------------------------------------
  // Index existence checks
  // -----------------------------------------------------------------------

  /**
   * Returns whether an index with the given name exists in this snapshot.
   *
   * <p>Checks both regular (equality) indices and sorted indices.
   *
   * @param indexName the name of the index to check, never null
   *
   * @return true if the index exists, false otherwise
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public boolean hasIndex(final String indexName) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    return indices.containsKey(indexName)
        || sortedIndices.containsKey(indexName);
  }

  /**
   * Returns whether a sorted index with the given name exists in this
   * snapshot.
   *
   * @param indexName the name of the sorted index to check, never null
   *
   * @return true if the sorted index exists, false otherwise
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public boolean hasSortedIndex(final String indexName) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    return sortedIndices.containsKey(indexName);
  }

  // -----------------------------------------------------------------------
  // Range query methods
  // -----------------------------------------------------------------------

  /**
   * Searches the sorted index for items whose keys fall within the
   * inclusive range [{@code from}, {@code to}].
   *
   * @param indexName the name of the sorted index to search, never null
   * @param from      the inclusive lower bound, never null
   * @param to        the inclusive upper bound, never null
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support range queries
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> searchBetween(final String indexName,
      final Comparable<?> from, final Comparable<?> to) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    final NavigableMap<Comparable<?>, List<T>> sorted =
        requireSortedIndex(indexName);
    return flattenValues(sorted.subMap(from, true, to, true));
  }

  /**
   * Searches the sorted index for items whose keys are strictly greater
   * than the given key.
   *
   * @param indexName the name of the sorted index to search, never null
   * @param key       the exclusive lower bound, never null
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support range queries
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> searchGreaterThan(final String indexName,
      final Comparable<?> key) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(key, "key must not be null");
    final NavigableMap<Comparable<?>, List<T>> sorted =
        requireSortedIndex(indexName);
    return flattenValues(sorted.tailMap(key, false));
  }

  /**
   * Searches the sorted index for items whose keys are greater than or
   * equal to the given key.
   *
   * @param indexName the name of the sorted index to search, never null
   * @param key       the inclusive lower bound, never null
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support range queries
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> searchGreaterOrEqual(final String indexName,
      final Comparable<?> key) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(key, "key must not be null");
    final NavigableMap<Comparable<?>, List<T>> sorted =
        requireSortedIndex(indexName);
    return flattenValues(sorted.tailMap(key, true));
  }

  /**
   * Searches the sorted index for items whose keys are strictly less
   * than the given key.
   *
   * @param indexName the name of the sorted index to search, never null
   * @param key       the exclusive upper bound, never null
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support range queries
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> searchLessThan(final String indexName,
      final Comparable<?> key) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(key, "key must not be null");
    final NavigableMap<Comparable<?>, List<T>> sorted =
        requireSortedIndex(indexName);
    return flattenValues(sorted.headMap(key, false));
  }

  /**
   * Searches the sorted index for items whose keys are less than or
   * equal to the given key.
   *
   * @param indexName the name of the sorted index to search, never null
   * @param key       the inclusive upper bound, never null
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support range queries
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> searchLessOrEqual(final String indexName,
      final Comparable<?> key) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(key, "key must not be null");
    final NavigableMap<Comparable<?>, List<T>> sorted =
        requireSortedIndex(indexName);
    return flattenValues(sorted.headMap(key, true));
  }

  // -----------------------------------------------------------------------
  // Text pattern query methods
  // -----------------------------------------------------------------------

  /**
   * Searches the sorted index for items whose String keys start with the
   * given prefix.
   *
   * <p>Uses a NavigableMap range scan on the sorted index by computing
   * the exclusive upper bound from the prefix.
   *
   * @param indexName the name of the sorted index to search, never null
   * @param prefix    the prefix to match, never null or empty
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support range queries
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> searchStartsWith(final String indexName,
      final String prefix) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(prefix, "prefix must not be null");
    if (prefix.isEmpty()) {
      return Collections.emptyList();
    }
    final NavigableMap<Comparable<?>, List<T>> sorted =
        requireSortedIndex(indexName);
    final String prefixEnd = computePrefixEnd(prefix);
    if (prefixEnd == null) {
      return flattenValues(sorted.tailMap(prefix, true));
    }
    return flattenValues(sorted.subMap(prefix, true, prefixEnd, false));
  }

  /**
   * Searches the reversed-key index for items whose String keys end with
   * the given suffix.
   *
   * <p>Reverses the suffix and performs a prefix scan on the reversed-key
   * TreeMap to efficiently find all matching keys.
   *
   * @param indexName the name of the sorted index to search, never null
   * @param suffix    the suffix to match, never null or empty
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support text queries (key type must be String)
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> searchEndsWith(final String indexName,
      final String suffix) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(suffix, "suffix must not be null");
    if (suffix.isEmpty()) {
      return Collections.emptyList();
    }
    final NavigableMap<String, List<T>> reversed =
        requireReversedKeyIndex(indexName);
    final String reversedSuffix = new StringBuilder(suffix)
        .reverse().toString();
    final String prefixEnd = computePrefixEnd(reversedSuffix);
    if (prefixEnd == null) {
      return flattenValues(reversed.tailMap(reversedSuffix, true));
    }
    return flattenValues(reversed.subMap(reversedSuffix, true,
        prefixEnd, false));
  }

  /**
   * Searches the sorted index for items whose String keys contain the
   * given substring.
   *
   * <p>Performs a linear scan over all keys in the sorted index, filtering
   * by {@link String#contains(CharSequence)}. This is O(n) in the number
   * of unique keys.
   *
   * @param indexName the name of the sorted index to search, never null
   * @param substring the substring to match, never null or empty
   *
   * @return an unmodifiable list of matching items, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support range queries
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public List<T> searchContains(final String indexName,
      final String substring) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(substring, "substring must not be null");
    if (substring.isEmpty()) {
      return Collections.emptyList();
    }
    final NavigableMap<Comparable<?>, List<T>> sorted =
        requireSortedIndex(indexName);
    final List<T> result = new ArrayList<>();
    for (final Map.Entry<Comparable<?>, List<T>> entry
        : sorted.entrySet()) {
      if (entry.getKey().toString().contains(substring)) {
        result.addAll(entry.getValue());
      }
    }
    if (result.isEmpty()) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(result);
  }

  // -----------------------------------------------------------------------
  // Private helpers for sorted index operations
  // -----------------------------------------------------------------------

  /**
   * Retrieves the sorted index for the given name, throwing an exception
   * if it does not exist.
   *
   * @param indexName the name of the sorted index, never null
   *
   * @return the navigable map for the sorted index, never null
   *
   * @throws UnsupportedIndexOperationException if the index is not sorted
   */
  private NavigableMap<Comparable<?>, List<T>> requireSortedIndex(
      final String indexName) {
    final NavigableMap<Comparable<?>, List<T>> sorted =
        sortedIndices.get(indexName);
    if (sorted == null) {
      throw new UnsupportedIndexOperationException(
          "Index '" + indexName + "' does not support range queries."
              + " Use indexSorted() to enable them");
    }
    return sorted;
  }

  /**
   * Retrieves the reversed-key index for the given name, throwing an
   * exception if it does not exist.
   *
   * @param indexName the name of the index, never null
   *
   * @return the navigable map with reversed String keys, never null
   *
   * @throws UnsupportedIndexOperationException if the index does not
   *         support text queries
   */
  private NavigableMap<String, List<T>> requireReversedKeyIndex(
      final String indexName) {
    final NavigableMap<String, List<T>> reversed =
        reversedKeyIndices.get(indexName);
    if (reversed == null) {
      throw new UnsupportedIndexOperationException(
          "Index '" + indexName + "' does not support text queries."
              + " Key type must be String");
    }
    return reversed;
  }

  /**
   * Flattens the values of a map into a single unmodifiable list.
   *
   * @param subMap the map whose values to flatten, never null
   *
   * @return an unmodifiable list of all items, never null
   */
  private List<T> flattenValues(final Map<?, List<T>> subMap) {
    if (subMap.isEmpty()) {
      return Collections.emptyList();
    }
    final List<T> result = new ArrayList<>();
    for (final List<T> list : subMap.values()) {
      result.addAll(list);
    }
    return Collections.unmodifiableList(result);
  }

  /**
   * Computes the exclusive upper bound for a prefix scan by incrementing
   * the last character of the prefix.
   *
   * @param prefix the prefix string, never null or empty
   *
   * @return the exclusive upper bound, or null if the last character
   *         cannot be incremented (edge case: Character.MAX_VALUE)
   */
  private static String computePrefixEnd(final String prefix) {
    final char lastChar = prefix.charAt(prefix.length() - 1);
    if (lastChar == Character.MAX_VALUE) {
      return null;
    }
    return prefix.substring(0, prefix.length() - 1) + (char) (lastChar + 1);
  }

  // -----------------------------------------------------------------------
  // Defensive copy helpers
  // -----------------------------------------------------------------------

  /**
   * Defensively copies the indices map into an unmodifiable structure.
   *
   * <p>Each inner map and its list values are also copied to ensure
   * complete immutability.
   *
   * @param indices the source indices to copy, never null
   * @param <T>     the type of data items
   *
   * @return an unmodifiable copy of the indices, never null
   */
  private static <T> Map<String, Map<Object, List<T>>> copyIndices(
      final Map<String, Map<Object, List<T>>> indices) {

    final Map<String, Map<Object, List<T>>> outerCopy = new HashMap<>();

    for (final Map.Entry<String, Map<Object, List<T>>> entry
        : indices.entrySet()) {

      final Map<Object, List<T>> innerCopy = new HashMap<>();

      for (final Map.Entry<Object, List<T>> innerEntry
          : entry.getValue().entrySet()) {
        innerCopy.put(innerEntry.getKey(),
            Collections.unmodifiableList(List.copyOf(innerEntry.getValue())));
      }

      outerCopy.put(entry.getKey(), Collections.unmodifiableMap(innerCopy));
    }

    return Collections.unmodifiableMap(outerCopy);
  }

  /**
   * Defensively copies the sorted indices map into an unmodifiable
   * structure.
   *
   * <p>Each inner NavigableMap and its list values are also copied to
   * ensure complete immutability.
   *
   * @param sortedIndices the source sorted indices to copy, never null
   * @param <T>           the type of data items
   *
   * @return an unmodifiable copy of the sorted indices, never null
   */
  private static <T> Map<String, NavigableMap<Comparable<?>, List<T>>>
      copySortedIndices(
          final Map<String, NavigableMap<Comparable<?>, List<T>>>
              sortedIndices) {

    if (sortedIndices.isEmpty()) {
      return Collections.emptyMap();
    }

    final Map<String, NavigableMap<Comparable<?>, List<T>>> outerCopy =
        new HashMap<>();

    for (final Map.Entry<String, NavigableMap<Comparable<?>, List<T>>> entry
        : sortedIndices.entrySet()) {

      final TreeMap<Comparable<?>, List<T>> innerCopy = new TreeMap<>();

      for (final Map.Entry<Comparable<?>, List<T>> innerEntry
          : entry.getValue().entrySet()) {
        innerCopy.put(innerEntry.getKey(),
            Collections.unmodifiableList(List.copyOf(innerEntry.getValue())));
      }

      outerCopy.put(entry.getKey(),
          Collections.unmodifiableNavigableMap(innerCopy));
    }

    return Collections.unmodifiableMap(outerCopy);
  }

  /**
   * Defensively copies the reversed-key indices map into an unmodifiable
   * structure.
   *
   * <p>Each inner NavigableMap and its list values are also copied to
   * ensure complete immutability.
   *
   * @param reversedKeyIndices the source reversed-key indices to copy,
   *                           never null
   * @param <T>                the type of data items
   *
   * @return an unmodifiable copy of the reversed-key indices, never null
   */
  private static <T> Map<String, NavigableMap<String, List<T>>>
      copyReversedKeyIndices(
          final Map<String, NavigableMap<String, List<T>>>
              reversedKeyIndices) {

    if (reversedKeyIndices.isEmpty()) {
      return Collections.emptyMap();
    }

    final Map<String, NavigableMap<String, List<T>>> outerCopy =
        new HashMap<>();

    for (final Map.Entry<String, NavigableMap<String, List<T>>> entry
        : reversedKeyIndices.entrySet()) {

      final TreeMap<String, List<T>> innerCopy = new TreeMap<>();

      for (final Map.Entry<String, List<T>> innerEntry
          : entry.getValue().entrySet()) {
        innerCopy.put(innerEntry.getKey(),
            Collections.unmodifiableList(List.copyOf(innerEntry.getValue())));
      }

      outerCopy.put(entry.getKey(),
          Collections.unmodifiableNavigableMap(innerCopy));
    }

    return Collections.unmodifiableMap(outerCopy);
  }

  // -----------------------------------------------------------------------
  // Index info and memory estimation
  // -----------------------------------------------------------------------

  /** Base overhead of a HashMap instance. */
  private static final long HASHMAP_BASE = 48L;

  /** Size of a reference in the bucket array. */
  private static final long BUCKET_REF = 8L;

  /** Size of a HashMap.Node (hash + key + value + next + header). */
  private static final long ENTRY_NODE = 32L;

  /** Base overhead of an ArrayList instance + internal Object[] header. */
  private static final long ARRAYLIST_HEADER = 40L;

  /** Size of an object reference within an array. */
  private static final long REFERENCE = 8L;

  /** Estimated size of a boxed Number key. */
  private static final long NUMBER_KEY_SIZE = 16L;

  /** Default estimated size for keys of unknown type. */
  private static final long DEFAULT_KEY_SIZE = 50L;

  /** Base overhead of a String object. */
  private static final long STRING_BASE = 40L;

  /**
   * Computes statistics for each index in this snapshot, including an
   * estimated memory footprint based on JVM structural heuristics.
   *
   * <p>The estimation accounts for HashMap overhead (base, bucket array,
   * Entry nodes), key object sizes, ArrayList headers per bucket, and
   * object references for each entry. Items in the lists are shared
   * references to the data list and are not counted as duplicated memory.
   *
   * @return an unmodifiable list of IndexInfo, one per index, never null
   */
  public List<IndexInfo> indexInfo() {
    if (indices.isEmpty()) {
      return Collections.emptyList();
    }
    final List<IndexInfo> result = new ArrayList<>();
    for (final Map.Entry<String, Map<Object, List<T>>> entry
        : indices.entrySet()) {
      result.add(computeIndexInfo(entry.getKey(), entry.getValue()));
    }
    return Collections.unmodifiableList(result);
  }

  private static <T> IndexInfo computeIndexInfo(final String indexName,
      final Map<Object, List<T>> index) {
    final int uniqueKeys = index.size();
    int totalEntries = 0;
    long keySizeSum = 0;
    for (final Map.Entry<Object, List<T>> entry : index.entrySet()) {
      totalEntries += entry.getValue().size();
      keySizeSum += estimateKeySize(entry.getKey());
    }
    final long tableBuckets = nextPowerOfTwo(
        Math.max(16, (long) (uniqueKeys / 0.75) + 1));
    final long estimatedBytes = HASHMAP_BASE
        + tableBuckets * BUCKET_REF
        + uniqueKeys * (ENTRY_NODE + ARRAYLIST_HEADER)
        + keySizeSum
        + totalEntries * REFERENCE;
    return new IndexInfo(indexName, uniqueKeys, totalEntries, estimatedBytes);
  }

  private static long estimateKeySize(final Object key) {
    if (key instanceof String s) {
      return STRING_BASE + s.length();
    }
    if (key instanceof Number) {
      return NUMBER_KEY_SIZE;
    }
    return DEFAULT_KEY_SIZE;
  }

  private static long nextPowerOfTwo(final long value) {
    long n = value - 1;
    n |= n >>> 1;
    n |= n >>> 2;
    n |= n >>> 4;
    n |= n >>> 8;
    n |= n >>> 16;
    n |= n >>> 32;
    return n + 1;
  }
}
