package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.waabox.andersoni.Catalog.PrioritizedHook;

/**
 * Surgical snapshot patcher for {@link Catalog}.
 *
 * <p>Given a {@link Snapshot} and a {@code (indexName, key, newItem)} triple,
 * computes a patched copy where only the index buckets affected by the swap
 * are rebuilt. Buckets that aren't touched share references with the previous
 * snapshot.
 *
 * <p>{@link Catalog} owns the refresh lock, the snapshot reference, version
 * counter, and hash computation. This class is a pure computation: it does
 * not mutate any catalog state. The caller wraps the returned
 * {@link PatchResult} into a new {@link Snapshot} and swaps it in.
 *
 * @param <T> the catalog item type
 */
final class CatalogPatcher<T> {

  /** Outcome returned to callers of the {@link Catalog#replace} family. */
  record ReplaceOutcome<T>(T oldItem, long fromVersion, String fromHash,
      long toVersion, String toHash) {
  }

  /**
   * The patched components of a snapshot, plus the item that was displaced.
   * {@code oldItem} is null for an insert, which displaces nothing.
   */
  record PatchResult<T>(
      T oldItem,
      List<AndersoniCatalogItem<T>> newItems,
      Map<String, Map<Object, List<AndersoniCatalogItem<T>>>> newIndices,
      Map<String, NavigableMap<Comparable<?>,
          List<AndersoniCatalogItem<T>>>> newSorted,
      Map<String, NavigableMap<String,
          List<AndersoniCatalogItem<T>>>> newReversed) {
  }

  /**
   * Tracks which index maps a computation already owns, and whether its
   * result is about to be published.
   *
   * <p>A patch replaces one entry of an immutable map, which means copying
   * the map — the dominant cost of the operation. Within a batch, the map
   * an operation would copy was created by the operation before it and is
   * referenced by nothing else yet, so copying it again is pure waste. The
   * first operation to touch an index copies it and records ownership here;
   * later ones mutate it directly.
   *
   * <p>{@code freeze} is false while a batch is still being computed, so
   * intermediate results are left mutable and are wrapped once at the end.
   * A single operation runs with an empty scope and {@code freeze} true,
   * which is exactly the old behaviour.
   */
  static final class PatchScope {

    /** Index names whose maps this computation owns and may mutate. */
    private final Set<String> owned = new HashSet<>();

    /** Whether the result is final and should be made unmodifiable. */
    private boolean freeze = true;

    /** Marks the scope as mid-batch, so results stay mutable. */
    void openBatch() {
      freeze = false;
    }

    /** Marks the batch finished, so the next result is frozen. */
    void closeBatch() {
      freeze = true;
    }

    /**
     * Returns whether this computation may mutate the named index's map,
     * recording ownership if it may not yet.
     *
     * @param name the index name, never null
     *
     * @return true if the map is already owned and safe to mutate
     */
    private boolean claim(final String name) {
      return !owned.add(name);
    }
  }

  private final String catalogName;
  private final List<IndexDefinition<T>> indexDefinitions;
  private final List<SortedIndexDefinition<T>> sortedIndexDefinitions;
  private final List<MultiKeyIndexDefinition<T>> multiKeyIndexDefinitions;
  private final List<GraphIndexDefinition<T>> graphIndexDefinitions;
  private final List<ViewDefinition<T, ?>> viewDefinitions;
  private final List<PrioritizedHook<T>> hooks;

  CatalogPatcher(final String catalogName,
      final List<IndexDefinition<T>> indexDefinitions,
      final List<SortedIndexDefinition<T>> sortedIndexDefinitions,
      final List<MultiKeyIndexDefinition<T>> multiKeyIndexDefinitions,
      final List<GraphIndexDefinition<T>> graphIndexDefinitions,
      final List<ViewDefinition<T, ?>> viewDefinitions,
      final List<PrioritizedHook<T>> hooks) {
    this.catalogName = catalogName;
    this.indexDefinitions = indexDefinitions;
    this.sortedIndexDefinitions = sortedIndexDefinitions;
    this.multiKeyIndexDefinitions = multiKeyIndexDefinitions;
    this.graphIndexDefinitions = graphIndexDefinitions;
    this.viewDefinitions = viewDefinitions;
    this.hooks = hooks;
  }

  /**
   * Computes a surgical patch on {@code snapshot} that replaces the single
   * item resolved by {@code (indexName, key)} with {@code newItem}.
   *
   * <p>Returns empty when the bucket is empty (no-op). Throws
   * {@link AmbiguousPatchTargetException} when the bucket holds more than one
   * item, {@link IndexNotFoundException} when the index is not declared.
   *
   * @param snapshot  the current snapshot, never null
   * @param indexName the index used for the lookup, never null
   * @param key       the lookup key, never null
   * @param newItem   the replacement item, never null
   *
   * @return the patched components on success, empty on no-match
   */
  Optional<PatchResult<T>> patch(final Snapshot<T> snapshot,
      final String indexName, final Object key, final T newItem) {
    return patch(snapshot, indexName, key, newItem, new PatchScope());
  }

  /**
   * As {@link #patch(Snapshot, String, Object, Object)}, sharing a scope
   * with the other operations of a batch so each index map is copied once
   * for the batch rather than once per operation.
   *
   * @param snapshot  the state this operation applies to, never null
   * @param indexName the index used for the lookup, never null
   * @param key       the lookup key, never null
   * @param newItem   the replacement item, never null
   * @param scope     the batch's ownership scope, never null
   *
   * @return the patched components on success, empty on no-match
   */
  Optional<PatchResult<T>> patch(final Snapshot<T> snapshot,
      final String indexName, final Object key, final T newItem,
      final PatchScope scope) {
    final Optional<AndersoniCatalogItem<T>> found =
        resolveSingle(snapshot, indexName, key);
    if (found.isEmpty()) {
      return Optional.empty();
    }

    final AndersoniCatalogItem<T> oldWrapped = found.get();
    final AndersoniCatalogItem<T> newWrapped = wrapWithViews(newItem);
    runHooks(newItem);

    return Optional.of(build(snapshot, oldWrapped, newWrapped,
        swapItemInList(snapshot.items(), oldWrapped, newWrapped), scope));
  }

  /**
   * Computes a surgical patch on {@code snapshot} that appends
   * {@code newItem}, indexing it under every key its definitions yield.
   *
   * <p>An insert performs no lookup, so it cannot be ambiguous and cannot
   * miss. It also cannot detect that the item is already present: the
   * caller decides that, through {@code upsert} or its own check.
   *
   * @param snapshot the current snapshot, never null
   * @param newItem  the item to add, never null
   *
   * @return the patched components, never null
   */
  PatchResult<T> insert(final Snapshot<T> snapshot, final T newItem) {
    return insert(snapshot, newItem, new PatchScope());
  }

  /**
   * As {@link #insert(Snapshot, Object)}, sharing a batch's scope.
   *
   * @param snapshot the state this operation applies to, never null
   * @param newItem  the item to add, never null
   * @param scope    the batch's ownership scope, never null
   *
   * @return the patched components, never null
   */
  PatchResult<T> insert(final Snapshot<T> snapshot, final T newItem,
      final PatchScope scope) {
    final AndersoniCatalogItem<T> newWrapped = wrapWithViews(newItem);
    runHooks(newItem);

    final List<AndersoniCatalogItem<T>> newItemsList =
        new ArrayList<>(snapshot.items());
    newItemsList.add(newWrapped);

    return build(snapshot, null, newWrapped, newItemsList, scope);
  }

  /**
   * Computes a surgical patch on {@code snapshot} that drops the single
   * item resolved by {@code (indexName, key)} from the item list and from
   * every bucket its key sets touch.
   *
   * <p>Returns empty when the lookup matches nothing, which is a no-op
   * rather than an error: removal is idempotent by nature, and at-least-once
   * sync delivery makes a repeated removal ordinary.
   *
   * @param snapshot  the current snapshot, never null
   * @param indexName the index used for the lookup, never null
   * @param key       the lookup key, never null
   *
   * @return the patched components, or empty when nothing matched
   *
   * @throws AmbiguousPatchTargetException if the lookup matched more than one
   * @throws IndexNotFoundException        if the index is not declared
   */
  Optional<PatchResult<T>> remove(final Snapshot<T> snapshot,
      final String indexName, final Object key) {
    return remove(snapshot, indexName, key, new PatchScope());
  }

  /**
   * As {@link #remove(Snapshot, String, Object)}, sharing a batch's scope.
   *
   * @param snapshot  the state this operation applies to, never null
   * @param indexName the index used for the lookup, never null
   * @param key       the lookup key, never null
   * @param scope     the batch's ownership scope, never null
   *
   * @return the patched components, or empty when nothing matched
   */
  Optional<PatchResult<T>> remove(final Snapshot<T> snapshot,
      final String indexName, final Object key, final PatchScope scope) {
    final Optional<AndersoniCatalogItem<T>> found =
        resolveSingle(snapshot, indexName, key);
    if (found.isEmpty()) {
      return Optional.empty();
    }

    final AndersoniCatalogItem<T> oldWrapped = found.get();
    final List<AndersoniCatalogItem<T>> newItemsList =
        new ArrayList<>(snapshot.items().size() - 1);
    for (final AndersoniCatalogItem<T> existing : snapshot.items()) {
      if (existing != oldWrapped) {
        newItemsList.add(existing);
      }
    }

    return Optional.of(build(snapshot, oldWrapped, null, newItemsList,
        scope));
  }

  /**
   * Resolves {@code (indexName, key)} to the single item it must identify.
   *
   * @param snapshot  the snapshot to look in, never null
   * @param indexName the index name, never null
   * @param key       the lookup key, never null
   *
   * @return the single matching item, or empty when nothing matched
   *
   * @throws AmbiguousPatchTargetException if more than one item matched
   * @throws IndexNotFoundException        if the index is not declared
   */
  Optional<AndersoniCatalogItem<T>> resolveSingle(
      final Snapshot<T> snapshot, final String indexName, final Object key) {
    final Map<Object, List<AndersoniCatalogItem<T>>> bucketMap =
        snapshot.indicesMap().get(indexName);
    if (bucketMap == null) {
      throw new IndexNotFoundException(indexName, catalogName);
    }
    final List<AndersoniCatalogItem<T>> bucket = bucketMap.get(key);
    if (bucket == null || bucket.isEmpty()) {
      return Optional.empty();
    }
    if (bucket.size() > 1) {
      throw new AmbiguousPatchTargetException(catalogName, indexName, key,
          bucket.size());
    }
    return Optional.of(bucket.get(0));
  }

  /**
   * Assembles a {@link PatchResult} from the already-decided item list and
   * the pair being exchanged.
   *
   * <p>The three operations differ only in which side of that pair is
   * present: a replace has both, an insert has no old item, a removal has
   * no new one. Everything downstream — the per-index key sets, the bucket
   * rewrites, the sorted and reversed maps — treats an absent side as an
   * empty key set, so one code path serves all three.
   *
   * @param snapshot     the snapshot being patched, never null
   * @param oldWrapped   the item leaving the snapshot, null for an insert
   * @param newWrapped   the item entering the snapshot, null for a removal
   * @param newItemsList the already-computed item list, never null
   *
   * @return the patched components, never null
   */
  private PatchResult<T> build(final Snapshot<T> snapshot,
      final AndersoniCatalogItem<T> oldWrapped,
      final AndersoniCatalogItem<T> newWrapped,
      final List<AndersoniCatalogItem<T>> newItemsList,
      final PatchScope scope) {

    final T oldItem = oldWrapped == null ? null : oldWrapped.item();
    final T newItem = newWrapped == null ? null : newWrapped.item();

    return new PatchResult<>(
        oldItem,
        newItemsList,
        patchIndices(snapshot, oldItem, newItem, oldWrapped, newWrapped,
            scope),
        patchSortedIndices(snapshot, oldItem, newItem, oldWrapped, newWrapped,
            scope),
        patchReversedIndices(snapshot, oldItem, newItem,
            oldWrapped, newWrapped, scope));
  }

  /**
   * Extracts a lookup key for {@code item} under the named index, picking
   * any one of the keys for multi-keyed indexes. Returns {@code null} if
   * the item would not be indexed at all (extractor returned null or an
   * empty key set).
   *
   * <p>Used by the follower-side patch dispatch to re-derive the bucket
   * key from a deserialized {@code oldItem} without having to ship the
   * key separately on the wire.
   *
   * @throws IndexNotFoundException if no index with that name exists
   */
  PatchResult<T> freeze(final PatchResult<T> result) {
    final Map<String, Map<Object, List<AndersoniCatalogItem<T>>>> indices =
        new HashMap<>();
    for (final Map.Entry<String, Map<Object, List<AndersoniCatalogItem<T>>>>
        entry : result.newIndices().entrySet()) {
      indices.put(entry.getKey(),
          Collections.unmodifiableMap(entry.getValue()));
    }

    final Map<String, NavigableMap<Comparable<?>,
        List<AndersoniCatalogItem<T>>>> sorted = new HashMap<>();
    for (final Map.Entry<String, NavigableMap<Comparable<?>,
        List<AndersoniCatalogItem<T>>>> entry
        : result.newSorted().entrySet()) {
      sorted.put(entry.getKey(),
          Collections.unmodifiableNavigableMap(entry.getValue()));
    }

    final Map<String, NavigableMap<String,
        List<AndersoniCatalogItem<T>>>> reversed = new HashMap<>();
    for (final Map.Entry<String, NavigableMap<String,
        List<AndersoniCatalogItem<T>>>> entry
        : result.newReversed().entrySet()) {
      reversed.put(entry.getKey(),
          Collections.unmodifiableNavigableMap(entry.getValue()));
    }

    return new PatchResult<>(result.oldItem(), result.newItems(),
        Collections.unmodifiableMap(indices),
        Collections.unmodifiableMap(sorted),
        Collections.unmodifiableMap(reversed));
  }

  /**
   * Extracts a lookup key for {@code item} under the named index, picking
   * any one of the keys for multi-keyed indexes.
   *
   * @param indexName the index name, never null
   * @param item      the item, never null
   *
   * @return the key, or null when the item would not be indexed there
   */
  Object extractKeyFor(final String indexName, final T item) {
    for (final IndexDefinition<T> def : indexDefinitions) {
      if (def.name().equals(indexName)) {
        return def.extractKey(item);
      }
    }
    for (final SortedIndexDefinition<T> def : sortedIndexDefinitions) {
      if (def.name().equals(indexName)) {
        return def.extractKey(item);
      }
    }
    for (final MultiKeyIndexDefinition<T> def : multiKeyIndexDefinitions) {
      if (def.name().equals(indexName)) {
        final Set<Object> keys = def.extractKeys(item);
        return keys.isEmpty() ? null : keys.iterator().next();
      }
    }
    for (final GraphIndexDefinition<T> def : graphIndexDefinitions) {
      if (def.name().equals(indexName)) {
        final Set<Object> keys = def.extractKeys(item);
        return keys.isEmpty() ? null : keys.iterator().next();
      }
    }
    throw new IndexNotFoundException(indexName, catalogName);
  }

  private AndersoniCatalogItem<T> wrapWithViews(final T item) {
    final Map<Class<?>, Object> views = new HashMap<>();
    for (final ViewDefinition<T, ?> viewDef : viewDefinitions) {
      views.put(viewDef.viewType(), viewDef.mapper().apply(item));
    }
    return AndersoniCatalogItem.of(item, views);
  }

  private void runHooks(final T item) {
    T processed = item;
    for (final PrioritizedHook<T> prioritizedHook : hooks) {
      processed = prioritizedHook.hook().process(processed);
    }
  }

  private List<AndersoniCatalogItem<T>> swapItemInList(
      final List<AndersoniCatalogItem<T>> source,
      final AndersoniCatalogItem<T> oldWrapped,
      final AndersoniCatalogItem<T> newWrapped) {
    final List<AndersoniCatalogItem<T>> copy = new ArrayList<>(source.size());
    for (final AndersoniCatalogItem<T> existing : source) {
      copy.add(existing == oldWrapped ? newWrapped : existing);
    }
    return copy;
  }

  /**
   * Computes the per-named-index key sets for {@code item}, covering all
   * four index definition types declared on the catalog. The returned map
   * is keyed by index name; each value is the set of keys under which
   * {@code item} would be indexed there. Null keys are excluded.
   */
  private Map<String, Set<Object>> keysByIndexName(final T item) {
    final Map<String, Set<Object>> result = new HashMap<>();
    for (final IndexDefinition<T> def : indexDefinitions) {
      final Object key = item == null ? null : def.extractKey(item);
      result.put(def.name(),
          key == null ? Collections.emptySet() : Set.of(key));
    }
    for (final SortedIndexDefinition<T> def : sortedIndexDefinitions) {
      final Object key = item == null ? null : def.extractKey(item);
      result.put(def.name(),
          key == null ? Collections.emptySet() : Set.of(key));
    }
    for (final MultiKeyIndexDefinition<T> def : multiKeyIndexDefinitions) {
      result.put(def.name(),
          item == null ? Collections.emptySet() : def.extractKeys(item));
    }
    for (final GraphIndexDefinition<T> def : graphIndexDefinitions) {
      result.put(def.name(),
          item == null ? Collections.emptySet() : def.extractKeys(item));
    }
    return result;
  }

  /**
   * Extracts a sorted index's key for an item that may be absent.
   *
   * @param def  the sorted index definition, never null
   * @param item the item, or null for the absent side of the exchange
   *
   * @return the key, or null when the item is absent or yields no key
   */
  private Object sortedKeyOf(final SortedIndexDefinition<T> def,
      final T item) {
    return item == null ? null : def.extractKey(item);
  }

  /**
   * Builds the patched {@code indices} map. Buckets that aren't affected
   * by the change share references with the previous snapshot.
   */
  private Map<String, Map<Object, List<AndersoniCatalogItem<T>>>>
      patchIndices(final Snapshot<T> snapshot, final T oldItem,
          final T newItem, final AndersoniCatalogItem<T> oldWrapped,
          final AndersoniCatalogItem<T> newWrapped,
          final PatchScope scope) {

    final Map<String, Set<Object>> oldKeysByIndex = keysByIndexName(oldItem);
    final Map<String, Set<Object>> newKeysByIndex = keysByIndexName(newItem);
    final Map<String, Map<Object, List<AndersoniCatalogItem<T>>>>
        oldIndices = snapshot.indicesMap();
    final Map<String, Map<Object, List<AndersoniCatalogItem<T>>>>
        result = new HashMap<>(oldIndices);

    for (final String idx : oldKeysByIndex.keySet()) {
      final Set<Object> oldKeys = oldKeysByIndex.get(idx);
      final Set<Object> newKeys = newKeysByIndex.get(idx);
      if (oldKeys.equals(newKeys) && oldKeys.isEmpty()) {
        continue;
      }
      final Map<Object, List<AndersoniCatalogItem<T>>> rebuiltInner =
          patchInnerMap(oldIndices.get(idx), oldKeys, newKeys,
              oldWrapped, newWrapped, scope.claim(idx));
      result.put(idx, scope.freeze
          ? Collections.unmodifiableMap(rebuiltInner) : rebuiltInner);
    }
    return scope.freeze ? Collections.unmodifiableMap(result) : result;
  }

  /**
   * Returns a copy of {@code inner} with the relevant buckets updated to
   * reflect the swap of {@code oldWrapped} for {@code newWrapped}.
   */
  private Map<Object, List<AndersoniCatalogItem<T>>> patchInnerMap(
      final Map<Object, List<AndersoniCatalogItem<T>>> inner,
      final Set<Object> oldKeys, final Set<Object> newKeys,
      final AndersoniCatalogItem<T> oldWrapped,
      final AndersoniCatalogItem<T> newWrapped,
      final boolean owned) {

    final Map<Object, List<AndersoniCatalogItem<T>>> rebuilt =
        owned ? inner : new HashMap<>(inner);
    for (final Object key : oldKeys) {
      if (!newKeys.contains(key)) {
        rebuilt.put(key, removeFromBucket(rebuilt.get(key), oldWrapped));
      }
    }
    for (final Object key : newKeys) {
      if (!oldKeys.contains(key)) {
        rebuilt.put(key, addToBucket(rebuilt.get(key), newWrapped));
      }
    }
    for (final Object key : oldKeys) {
      if (newKeys.contains(key)) {
        rebuilt.put(key, swapInBucket(rebuilt.get(key),
            oldWrapped, newWrapped));
      }
    }
    rebuilt.entrySet().removeIf(e -> e.getValue().isEmpty());
    return rebuilt;
  }

  /**
   * Builds the patched {@code sortedIndices} map for sorted indexes.
   */
  private Map<String, NavigableMap<Comparable<?>,
      List<AndersoniCatalogItem<T>>>> patchSortedIndices(
          final Snapshot<T> snapshot, final T oldItem, final T newItem,
          final AndersoniCatalogItem<T> oldWrapped,
          final AndersoniCatalogItem<T> newWrapped,
          final PatchScope scope) {

    final Map<String, NavigableMap<Comparable<?>,
        List<AndersoniCatalogItem<T>>>> oldSorted =
            snapshot.sortedIndicesMap();
    if (sortedIndexDefinitions.isEmpty() || oldSorted.isEmpty()) {
      return oldSorted;
    }

    final Map<String, NavigableMap<Comparable<?>,
        List<AndersoniCatalogItem<T>>>> result = new HashMap<>(oldSorted);
    for (final SortedIndexDefinition<T> def : sortedIndexDefinitions) {
      final Object oldKey = sortedKeyOf(def, oldItem);
      final Object newKey = sortedKeyOf(def, newItem);
      if (Objects.equals(oldKey, newKey) && oldKey == null) {
        continue;
      }
      final NavigableMap<Comparable<?>, List<AndersoniCatalogItem<T>>>
          rebuilt = patchSortedInnerMap(oldSorted.get(def.name()),
          oldKey, newKey, oldWrapped, newWrapped,
          scope.claim("sorted:" + def.name()));
      result.put(def.name(), scope.freeze
          ? Collections.unmodifiableNavigableMap(rebuilt) : rebuilt);
    }
    return scope.freeze ? Collections.unmodifiableMap(result) : result;
  }

  /**
   * Returns a copy of {@code inner} (a sorted index's NavigableMap) with
   * the affected buckets updated. Null keys are excluded from sorted
   * navigable maps (per the build invariant) so they don't appear here.
   */
  private NavigableMap<Comparable<?>, List<AndersoniCatalogItem<T>>>
      patchSortedInnerMap(
          final NavigableMap<Comparable<?>,
              List<AndersoniCatalogItem<T>>> inner,
          final Object oldKey, final Object newKey,
          final AndersoniCatalogItem<T> oldWrapped,
          final AndersoniCatalogItem<T> newWrapped,
          final boolean owned) {

    final NavigableMap<Comparable<?>, List<AndersoniCatalogItem<T>>> rebuilt =
        owned ? inner : new TreeMap<>(inner);
    if (oldKey != null && Objects.equals(oldKey, newKey)) {
      rebuilt.put((Comparable<?>) oldKey,
          swapInBucket(rebuilt.get(oldKey), oldWrapped, newWrapped));
      return rebuilt;
    }
    if (oldKey != null) {
      final List<AndersoniCatalogItem<T>> bucket =
          removeFromBucket(rebuilt.get(oldKey), oldWrapped);
      if (bucket.isEmpty()) {
        rebuilt.remove(oldKey);
      } else {
        rebuilt.put((Comparable<?>) oldKey, bucket);
      }
    }
    if (newKey != null) {
      rebuilt.put((Comparable<?>) newKey,
          addToBucket(rebuilt.get(newKey), newWrapped));
    }
    return rebuilt;
  }

  /**
   * Builds the patched {@code reversedKeyIndices} map. Only sorted
   * indexes with String keys carry a reversed-key map.
   */
  private Map<String, NavigableMap<String, List<AndersoniCatalogItem<T>>>>
      patchReversedIndices(final Snapshot<T> snapshot, final T oldItem,
          final T newItem, final AndersoniCatalogItem<T> oldWrapped,
          final AndersoniCatalogItem<T> newWrapped,
          final PatchScope scope) {

    final Map<String, NavigableMap<String,
        List<AndersoniCatalogItem<T>>>> oldReversed =
            snapshot.reversedKeyIndicesMap();
    if (oldReversed.isEmpty()) {
      return oldReversed;
    }

    final Map<String, NavigableMap<String,
        List<AndersoniCatalogItem<T>>>> result = new HashMap<>(oldReversed);
    for (final SortedIndexDefinition<T> def : sortedIndexDefinitions) {
      if (!oldReversed.containsKey(def.name())) {
        continue;
      }
      final Object oldKey = sortedKeyOf(def, oldItem);
      final Object newKey = sortedKeyOf(def, newItem);
      if (!(oldKey == null || oldKey instanceof String)
          || !(newKey == null || newKey instanceof String)) {
        continue;
      }
      final String oldReversedKey = oldKey == null ? null
          : new StringBuilder((String) oldKey).reverse().toString();
      final String newReversedKey = newKey == null ? null
          : new StringBuilder((String) newKey).reverse().toString();
      if (Objects.equals(oldReversedKey, newReversedKey)
          && oldReversedKey == null) {
        continue;
      }
      final NavigableMap<String, List<AndersoniCatalogItem<T>>>
          rebuilt = patchReversedInnerMap(oldReversed.get(def.name()),
          oldReversedKey, newReversedKey, oldWrapped, newWrapped,
          scope.claim("reversed:" + def.name()));
      result.put(def.name(), scope.freeze
          ? Collections.unmodifiableNavigableMap(rebuilt) : rebuilt);
    }
    return scope.freeze ? Collections.unmodifiableMap(result) : result;
  }

  /**
   * Returns a copy of {@code inner} (the reversed-key TreeMap) with the
   * affected buckets updated.
   */
  private NavigableMap<String, List<AndersoniCatalogItem<T>>>
      patchReversedInnerMap(
          final NavigableMap<String,
              List<AndersoniCatalogItem<T>>> inner,
          final String oldReversedKey, final String newReversedKey,
          final AndersoniCatalogItem<T> oldWrapped,
          final AndersoniCatalogItem<T> newWrapped,
          final boolean owned) {

    final NavigableMap<String, List<AndersoniCatalogItem<T>>> rebuilt =
        owned ? inner : new TreeMap<>(inner);
    if (oldReversedKey != null
        && oldReversedKey.equals(newReversedKey)) {
      rebuilt.put(oldReversedKey,
          swapInBucket(rebuilt.get(oldReversedKey),
              oldWrapped, newWrapped));
      return rebuilt;
    }
    if (oldReversedKey != null) {
      final List<AndersoniCatalogItem<T>> bucket =
          removeFromBucket(rebuilt.get(oldReversedKey), oldWrapped);
      if (bucket.isEmpty()) {
        rebuilt.remove(oldReversedKey);
      } else {
        rebuilt.put(oldReversedKey, bucket);
      }
    }
    if (newReversedKey != null) {
      rebuilt.put(newReversedKey,
          addToBucket(rebuilt.get(newReversedKey), newWrapped));
    }
    return rebuilt;
  }

  private List<AndersoniCatalogItem<T>> removeFromBucket(
      final List<AndersoniCatalogItem<T>> bucket,
      final AndersoniCatalogItem<T> item) {
    if (bucket == null || bucket.isEmpty()) {
      return Collections.emptyList();
    }
    final List<AndersoniCatalogItem<T>> copy = new ArrayList<>(
        bucket.size());
    boolean removed = false;
    for (final AndersoniCatalogItem<T> existing : bucket) {
      if (!removed && existing == item) {
        removed = true;
        continue;
      }
      copy.add(existing);
    }
    return Collections.unmodifiableList(copy);
  }

  private List<AndersoniCatalogItem<T>> addToBucket(
      final List<AndersoniCatalogItem<T>> bucket,
      final AndersoniCatalogItem<T> item) {
    final int existingSize = bucket == null ? 0 : bucket.size();
    final List<AndersoniCatalogItem<T>> copy =
        new ArrayList<>(existingSize + 1);
    if (bucket != null) {
      copy.addAll(bucket);
    }
    copy.add(item);
    return Collections.unmodifiableList(copy);
  }

  private List<AndersoniCatalogItem<T>> swapInBucket(
      final List<AndersoniCatalogItem<T>> bucket,
      final AndersoniCatalogItem<T> oldItem,
      final AndersoniCatalogItem<T> newItem) {
    final List<AndersoniCatalogItem<T>> copy =
        new ArrayList<>(bucket.size());
    boolean swapped = false;
    for (final AndersoniCatalogItem<T> existing : bucket) {
      if (!swapped && existing == oldItem) {
        copy.add(newItem);
        swapped = true;
      } else {
        copy.add(existing);
      }
    }
    return Collections.unmodifiableList(copy);
  }
}
