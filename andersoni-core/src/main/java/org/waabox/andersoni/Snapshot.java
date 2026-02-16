package org.waabox.andersoni;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

  /** The version number of this snapshot. */
  private final long version;

  /** The hash identifying the content of this snapshot. */
  private final String hash;

  /** The instant when this snapshot was created. */
  private final Instant createdAt;

  /**
   * Creates a new snapshot.
   *
   * @param data      the data items, never null
   * @param indices   the indices, never null
   * @param version   the version number
   * @param hash      the content hash, never null
   * @param createdAt the creation timestamp, never null
   */
  private Snapshot(final List<T> data,
      final Map<String, Map<Object, List<T>>> indices,
      final long version, final String hash, final Instant createdAt) {
    this.data = data;
    this.indices = indices;
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

    Objects.requireNonNull(data, "data must not be null");
    Objects.requireNonNull(indices, "indices must not be null");
    Objects.requireNonNull(hash, "hash must not be null");

    final List<T> immutableData = Collections.unmodifiableList(
        List.copyOf(data));

    final Map<String, Map<Object, List<T>>> immutableIndices =
        copyIndices(indices);

    return new Snapshot<>(immutableData, immutableIndices, version, hash,
        Instant.now());
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
        0L, "", Instant.now());
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
