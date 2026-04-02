# Single-Loop Snapshot Build with Hooks — Design Spec

## Context

Andersoni's `buildAndSwapSnapshot` currently iterates the data list multiple times: once to wrap items with views, and once per index definition to build each index. All index definitions process items one by one with no inter-item dependencies, making this naturally suited to a single-loop approach.

## Problem

1. **Multiple passes over data:** 1 + N loops (wrapping + one per index) wastes CPU cache locality on large datasets.
2. **No extensibility point:** There is no way for users to execute custom logic per item during snapshot build (e.g., validation, metrics, enrichment, logging).

## Solution

Refactor `buildAndSwapSnapshot` into a single loop over the data. Within each iteration: compute views, index the item across all indices, and execute user-registered hooks. Expose a `SnapshotBuildHook<T>` interface in the public API with priority-based ordering.

## Design

### SnapshotBuildHook\<T\>

Public interface for user-defined per-item logic during snapshot build:

```java
public interface SnapshotBuildHook<T> {
    T process(T item);
}
```

- Receives the original item `T`.
- Returns `T` (same or transformed). The return value is not used by indexation or views — they operate on the original item. The return value is passed to the next hook in the chain.
- Hooks are for side-effects: logging, metrics, validation, enrichment of external state, etc.
- Hooks execute **after** indexation and **after** view computation for each item.

### Priority

Hooks are ordered by an `int` priority. Lower number executes first. Default priority is `100`.

### Catalog DSL

```java
Catalog<Event> catalog = Catalog.of(Event.class)
    .named("events")
    .loadWith(() -> repository.findAll())
    .index("by-venue").by(Event::venue, Venue::name)
    .view(EventSummary.class, e -> new EventSummary(e.id(), e.name()))
    .hook(myMetricsHook, 10)
    .hook(myValidationHook, 20)
    .hook(myLoggingHook)          // default priority 100
    .build();
```

The `BuildStep` stores hooks as a list, sorted by priority at build time.

### Single-Loop Build Flow

```
buildAndSwapSnapshot(List<T> data):

  Initialize empty accumulator maps for all index types.
  Initialize wrappedItems list.

  for each item in data:

    // 1. Compute views → create immutable wrapper
    Map<Class<?>, Object> views = {}
    for each ViewDefinition:
      views.put(viewType, mapper.apply(item))
    wrappedItem = AndersoniCatalogItem.of(item, views)
    wrappedItems.add(wrappedItem)

    // 2. Index the wrapper across all indices
    for each IndexDefinition:
      indexDef.accumulate(wrappedItem, indicesMap)
    for each MultiKeyIndexDefinition:
      multiDef.accumulate(wrappedItem, indicesMap)
    for each GraphIndexDefinition:
      graphDef.accumulate(wrappedItem, indicesMap)
    for each SortedIndexDefinition:
      sortedDef.accumulate(wrappedItem, hashMap, sortedMap, reversedMap)

    // 3. Execute hooks in priority order
    T processed = item
    for each hook (sorted by priority asc):
      processed = hook.process(processed)

  // Finalize: make all index maps unmodifiable
  // Compute hash from raw data (unchanged)
  // Create Snapshot.ofWithItems(wrappedItems, indices, sortedIndices, reversedKeyIndices, version, hash)
  // Atomic swap
```

### Index Definition Changes: accumulate()

Each index definition class replaces `buildIndex(List<T>)` and `buildIndexFromItems(List<AndersoniCatalogItem<T>>)` with a single `accumulate` method that processes one item at a time:

#### IndexDefinition

```java
void accumulate(final AndersoniCatalogItem<T> item,
    final Map<Object, List<AndersoniCatalogItem<T>>> index) {
  final Object key = keyExtractor.apply(item.item());
  if (key != null) {
    index.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
  }
}
```

#### MultiKeyIndexDefinition

```java
void accumulate(final AndersoniCatalogItem<T> item,
    final Map<Object, List<AndersoniCatalogItem<T>>> index) {
  final List<?> keys = keyExtractor.apply(item.item());
  if (keys != null) {
    for (final Object key : keys) {
      if (key != null) {
        index.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
      }
    }
  }
}
```

#### GraphIndexDefinition

```java
void accumulate(final AndersoniCatalogItem<T> item,
    final Map<Object, List<AndersoniCatalogItem<T>>> index) {
  // Evaluate hotpaths and traversals on item.item()
  // Generate composite keys (cartesian product, prefix keys)
  // For each key: index.computeIfAbsent(key, k -> new ArrayList<>()).add(item)
  // Enforce maxKeysPerItem limit
}
```

#### SortedIndexDefinition

```java
void accumulate(final AndersoniCatalogItem<T> item,
    final Map<Object, List<AndersoniCatalogItem<T>>> hashIndex,
    final NavigableMap<Comparable<?>, List<AndersoniCatalogItem<T>>> sortedIndex,
    final NavigableMap<String, List<AndersoniCatalogItem<T>>> reversedKeyIndex) {
  final Comparable<?> key = keyExtractor.apply(item.item());
  // Add to hashIndex (always)
  // Add to sortedIndex (if key non-null)
  // Add to reversedKeyIndex (if key is String and non-null)
  // Share same list bucket across all three maps
}
```

### Finalization

After the loop, all mutable maps are wrapped in unmodifiable collections (same as today's `buildIndex` return). This is a single pass over the maps, not over the items.

### What Changes

- `buildAndSwapSnapshot`: refactored from 1 + N loops to 1 loop
- `IndexDefinition`: `buildIndex` / `buildIndexFromItems` → `accumulate`
- `SortedIndexDefinition`: `buildIndex` / `buildIndexFromItems` → `accumulate`
- `MultiKeyIndexDefinition`: `buildIndex` / `buildIndexFromItems` → `accumulate`
- `GraphIndexDefinition`: `buildIndex` / `buildIndexFromItems` → `accumulate`
- `Catalog.BuildStep`: new `hook(SnapshotBuildHook<T>, int)` and `hook(SnapshotBuildHook<T>)` methods
- `Catalog`: new `hooks` field, hooks passed from builder

### What Does NOT Change

- `SnapshotSerializer<T>` — unchanged
- `Snapshot` — unchanged
- `AndersoniCatalogItem` — unchanged
- `ViewDefinition` — unchanged
- Query API (search, QueryStep, CompoundQuery, GraphQueryBuilder) — unchanged
- `computeHash` — still receives raw `List<T>`, unchanged
- `CatalogInfo` — unchanged (hook count could be added later if needed)

## Constraints

- Hooks execute after indexation and after view computation.
- Hooks receive the original `T`, not the `AndersoniCatalogItem` wrapper.
- Hook return value is chained to the next hook but does not affect indexation or views.
- Hooks must not throw checked exceptions. Runtime exceptions propagate and abort the build.
- Priority is `int` — lower executes first. Default is `100`.
- The `accumulate` methods are package-private.
- Old `buildIndex` / `buildIndexFromItems` methods are removed.

## Backward Compatibility

Fully backward compatible. Without hooks registered, the loop does views + indexation exactly as before, just in a single pass instead of multiple. The public API for defining catalogs, querying, and serialization is unchanged.

## Rejected Alternatives

### Hooks as a separate loop after the main build
Keeps the current multi-loop structure and adds another loop for hooks. Rejected because it misses the performance goal of a single pass and adds yet another iteration.

### Hooks before indexation
Hooks transforming items before indexation means indices reflect transformed data, not the original. Rejected because indexes should reflect the real data source.

### Mutable AndersoniCatalogItem for deferred view computation
Create wrapper with empty views, index it, compute views later. Rejected because it breaks immutability guarantees.

### Expose indexation as a hook
Making indexation a user-extensible hook risks users breaking index integrity. Rejected — indexation is an internal fixed step.
