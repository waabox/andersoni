package org.waabox.andersoni;

import java.time.Instant;
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
}
