package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;

/**
 * Estimates the heap footprint of a snapshot: the cached items and every
 * index structure built over them.
 *
 * <p>Large populations are sampled. Up to {@link #SAMPLE_SIZE} items, and
 * up to {@link #SAMPLE_SIZE} keys per index, are measured and scaled to the
 * full count. Objects that several sampled items share (such as an
 * interned venue name) are counted once instead of being scaled.
 *
 * <p>Index keys that are the same object the indexed item already holds
 * (the usual case for keys read straight from a field) are not counted
 * again, since they take no memory beyond the item's own.
 */
final class SnapshotSizeEstimator {

  /** Maximum number of items, or keys per index, measured individually. */
  static final int SAMPLE_SIZE = 1_000;

  /** Limit on objects collected from an item when checking key sharing. */
  private static final int ITEM_GRAPH_LIMIT = 10_000;

  private SnapshotSizeEstimator() {
  }

  /**
   * Estimates the bytes held by the snapshot's items, excluding indices.
   *
   * @param sizer the object sizer, never null
   * @param items the catalog items, never null
   * @param data  the unwrapped data list, never null
   * @param <T>   the item type
   *
   * @return the estimated bytes, zero when there are no items
   */
  static <T> long dataBytes(final ObjectSizer sizer,
      final List<AndersoniCatalogItem<T>> items, final List<T> data) {
    final int count = items.size();
    if (count == 0) {
      return 0L;
    }
    final ObjectSizer.Walk walk = sizer.walk();
    final int samples = Math.min(count, SAMPLE_SIZE);
    for (int i = 0; i < samples; i++) {
      walk.add(items.get(sampleIndex(i, samples, count)), i);
    }
    final long owned = Math.round(walk.ownedBytes() * ((double) count / samples));
    return sizer.containerSize(items, count)
        + sizer.containerSize(data, data.size())
        + owned + walk.sharedBytes();
  }

  /**
   * Computes per-index statistics and estimated sizes.
   *
   * <p>A sorted index is reported under its name with the size of its hash
   * map, its sorted tree and, for String keys, its reversed-key tree.
   *
   * @param sizer              the object sizer, never null
   * @param indices            the hash indices, never null
   * @param sortedIndices      the sorted trees, never null
   * @param reversedKeyIndices the reversed-key trees, never null
   * @param <T>                the item type
   *
   * @return an unmodifiable list with one entry per index, never null
   */
  static <T> List<IndexInfo> indexInfo(final ObjectSizer sizer,
      final Map<String, Map<Object, List<AndersoniCatalogItem<T>>>> indices,
      final Map<String, NavigableMap<Comparable<?>,
          List<AndersoniCatalogItem<T>>>> sortedIndices,
      final Map<String, NavigableMap<String,
          List<AndersoniCatalogItem<T>>>> reversedKeyIndices) {
    if (indices.isEmpty()) {
      return Collections.emptyList();
    }
    final List<IndexInfo> result = new ArrayList<>(indices.size());
    for (final Map.Entry<String, Map<Object,
        List<AndersoniCatalogItem<T>>>> entry : indices.entrySet()) {
      final String name = entry.getKey();
      final Map<Object, List<AndersoniCatalogItem<T>>> index = entry.getValue();

      int totalEntries = 0;
      for (final List<AndersoniCatalogItem<T>> bucket : index.values()) {
        totalEntries += bucket.size();
      }

      long bytes = sizer.containerSize(index, index.size())
          + bucketBytes(sizer, index)
          + keyBytes(sizer, index);

      final NavigableMap<Comparable<?>, List<AndersoniCatalogItem<T>>> sorted =
          sortedIndices.get(name);
      if (sorted != null) {
        // Keys are the hash index's own key objects; only the tree is new.
        bytes += sizer.containerSize(sorted, sorted.size())
            + bucketBytes(sizer, sorted);
      }

      final NavigableMap<String, List<AndersoniCatalogItem<T>>> reversed =
          reversedKeyIndices.get(name);
      if (reversed != null) {
        bytes += sizer.containerSize(reversed, reversed.size())
            + bucketBytes(sizer, reversed);
        for (final String key : reversed.keySet()) {
          bytes += sizer.stringSize(key);
        }
      }

      result.add(new IndexInfo(name, index.size(), totalEntries, bytes));
    }
    return Collections.unmodifiableList(result);
  }

  private static <T> long bucketBytes(final ObjectSizer sizer,
      final Map<?, List<AndersoniCatalogItem<T>>> index) {
    long bytes = 0L;
    for (final List<AndersoniCatalogItem<T>> bucket : index.values()) {
      bytes += sizer.frozenListSize(bucket.size());
    }
    return bytes;
  }

  /**
   * Estimates the memory held by index keys that the indexed items do not
   * already reference, sampling when there are many keys.
   */
  private static <T> long keyBytes(final ObjectSizer sizer,
      final Map<Object, List<AndersoniCatalogItem<T>>> index) {
    final int keys = index.size();
    if (keys == 0) {
      return 0L;
    }
    final int samples = Math.min(keys, SAMPLE_SIZE);
    final int stride = keys / samples;
    long sampledBytes = 0L;
    int sampled = 0;
    int position = 0;
    for (final Map.Entry<Object, List<AndersoniCatalogItem<T>>> entry
        : index.entrySet()) {
      if (position++ % stride != 0 || sampled == samples) {
        continue;
      }
      sampled++;
      final Object key = entry.getKey();
      if (key == null) {
        continue;
      }
      final List<AndersoniCatalogItem<T>> bucket = entry.getValue();
      final Set<Object> owned = bucket.isEmpty()
          ? Collections.emptySet()
          : sizer.reachable(bucket.get(0), ITEM_GRAPH_LIMIT);
      sampledBytes += sizer.deepSize(key, owned);
    }
    return Math.round(sampledBytes * ((double) keys / sampled));
  }

  private static int sampleIndex(final int i, final int samples,
      final int count) {
    return (int) ((long) i * count / samples);
  }
}
