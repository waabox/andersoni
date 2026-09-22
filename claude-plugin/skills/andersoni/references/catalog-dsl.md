# Catalog DSL Reference (Andersoni 1.12.1)

1. [Builder chain](#builder-chain) and [build() validation](#build-validation)
2. [Index types](#index-types): `index`, `indexSorted`, `indexMulti` (graph: see `graph-index.md`)
3. [Views](#views) and [build hooks](#build-hooks)
4. [Lifecycle, Snapshot, CatalogInfo](#lifecycle)
5. [Common mistakes](#common-mistakes)

All types live in `org.waabox.andersoni` unless noted. Maven: `io.github.waabox:andersoni-core:1.12.1`.

## Builder chain

```
Catalog.of(Class<T>)            -> Catalog.NameStep<T>
  .named(String)                -> Catalog.DataSourceStep<T>
  .loadWith(DataLoader<T>)      -> Catalog.BuildStep<T>     (or)
  .data(List<T>)                -> Catalog.BuildStep<T>
  BuildStep<T> (any order, repeatable):
    .serializer(SnapshotSerializer<T>)   .refreshEvery(Duration)
    .index(String).by(...)       .indexSorted(String).by(...)
    .indexMulti(String).by(...)  .indexGraph(String)...done()
    .view(Class<V>, Function<T,V>)
    .hook(SnapshotBuildHook<T>)  .hook(SnapshotBuildHook<T>, int)
    .build()                    -> Catalog<T>
```

| Method | Signature | Throws |
|---|---|---|
| `of` | `static <T> NameStep<T> of(Class<T> type)` | NPE if null |
| `named` | `DataSourceStep<T> named(String name)` | NPE if null, IAE if empty |
| `loadWith` | `BuildStep<T> loadWith(DataLoader<T> loader)` | NPE if null |
| `data` | `BuildStep<T> data(List<T> data)` | NPE if null list **or null element** (uses `List.copyOf`, so the list is defensively copied) |
| `serializer` | `BuildStep<T> serializer(SnapshotSerializer<T> s)` | NPE if null |
| `refreshEvery` | `BuildStep<T> refreshEvery(Duration interval)` | NPE if null |
| `build` | `Catalog<T> build()` | ISE `"At least one index definition is required"` |

- `serializer` is optional. It is used for snapshot persistence (`SnapshotStore`) **and** for the content hash. `SnapshotSerializer<T>` (package `org.waabox.andersoni.snapshot`) has `byte[] serialize(List<T> items)` and `List<T> deserialize(byte[] data)`. A Jackson implementation ships in `andersoni-json-serializer`.
- `refreshEvery` is only stored on the catalog. The scheduling happens in `Andersoni.start()`: one fixed-rate task per catalog (first run after one interval), which calls `refreshAndSync(name)` **on the leader only**.
- `DataLoader<T>` is a `@FunctionalInterface` with a single method, `List<T> load()`. It must return the **full** dataset (partial loads are not supported), never `null` (NPE `"DataLoader.load() must not return null for catalog '<name>'"`), and in a **deterministic order** (for example SQL `ORDER BY id`), because the hash depends on order.

```java
public record Venue(String id, String name) {}
public record Event(String id, String name, Venue venue, LocalDate date, List<String> tags) {}
public record EventCard(String id, String name) {}

final DataLoader<Event> loader = () -> eventRepository.findAllOrderedById();

final Catalog<Event> events = Catalog.of(Event.class)
    .named("events")
    .loadWith(loader)
    .serializer(eventSerializer)
    .refreshEvery(Duration.ofMinutes(5))
    .index("by-id").by(Event::id, Function.identity())
    .index("by-venue").by(Event::venue, Venue::name)
    .indexSorted("by-date").by(Event::date, Function.identity())
    .indexMulti("by-tag").by(Event::tags)
    .view(EventCard.class, e -> new EventCard(e.id(), e.name()))
    .build();
```

### build() validation

Everything is checked at the moment you call each method. `build()` itself only checks that there is at least one index.

| Rule | When | Exception |
|---|---|---|
| Catalog name non-null / non-empty | `named` | NPE / IAE `"name must not be empty"` |
| Index name non-null / non-empty | `index`, `indexSorted`, `indexMulti`, `indexGraph` | NPE / IAE `"indexName must not be empty"` |
| Index names unique **across all index types** (regular, sorted, multi, graph share one namespace) | `.by(...)` (or `indexGraph(name)`) | IAE `"Duplicate index name: '<name>'"` |
| View type unique per catalog | `view` | IAE `"Duplicate view type: <SimpleName>"` |
| At least one index of any type | `build` | ISE |
| Catalog name unique per `Andersoni` instance | `Andersoni.register` | IAE `"A catalog with name '<name>' is already registered"` |
| No registration after start | `Andersoni.register` | ISE |

Names are otherwise free-form. There is no character or length validation: whitespace, `/` and similar are accepted as-is.

## Index types

| DSL | Signature | Key extraction | Null handling |
|---|---|---|---|
| `index(n).by` | `<I> BuildStep<T> by(Function<T, I> first, Function<I, ?> second)` | `second(first(item))` | If `first` returns null, `second` is not called and the key is null. **Null keys are skipped** (the item is not in this index). |
| `indexSorted(n).by` | `<I, K extends Comparable<K>> BuildStep<T> by(Function<T, I> first, Function<I, K> second)` | same | A null key is stored only in the equality map (it cannot be searched, because search keys must be non-null) and is excluded from the range map. It still counts in `IndexInfo.uniqueKeys`. |
| `indexMulti(n).by` | `BuildStep<T> by(Function<T, List<?>> keysExtractor)` | item indexed under every element | A null or empty list means the item is skipped. Null elements are skipped. |
| `indexGraph(n)` | returns `GraphIndexStep<T>`, finish with `.done()` | composite keys | see `graph-index.md` |

- **There is no single-function `index(...).by(fn)`.** `by` always takes two functions. For a direct field, use `Function.identity()` as the second one: `.index("by-id").by(Event::id, Function.identity())`.
- **Key equality.** Lookups use `HashMap` semantics (`equals`/`hashCode`) on the **extracted key**, not on the item: `search("by-venue", "Maracana")`, not `search("by-venue", venue)`. Keys must be value types (String, boxed numbers, enums, records). Boxed types must match exactly: a `Long` key is **not** found with an `Integer` argument (`search("by-n", 1)` returns an empty list, `search("by-n", 1L)` finds it).
- **Sorted index.** The key type must satisfy `K extends Comparable<K>` at compile time. `String`, `Integer`, `Long`, `BigDecimal`, `LocalDate`, `Instant` and enums all compile. It builds a `HashMap` (equality), a `TreeMap` (ranges: `between`, `greaterThan`, `greaterOrEqual`, `lessThan`, `lessOrEqual`) and, when the keys are `String`, a reversed-key `TreeMap` (text: `startsWith`, `endsWith`, `contains`). Equality goes through `equals` and ranges through `compareTo`, so `BigDecimal("1.0")` and `("1.00")` are different keys for `equalTo`. A range bound of the wrong type throws `ClassCastException`.
- **Multi-key.** The extractor must be typed `Function<T, List<?>>`. A method reference returning `List<String>` compiles (`Event::tags`). A `Set` does not, so convert it: `e -> List.copyOf(e.regions())`. Duplicate keys in one item's list add that item twice to that bucket.
- Every index type supports equality: `catalog.search(index, key)` and `query(index).equalTo(key)`. Range and text queries require `indexSorted`; on any other index they throw `UnsupportedIndexOperationException`. On sorted indexes whose keys are not `String`, the text queries behave differently from each other: `endsWith` throws `UnsupportedIndexOperationException`, `startsWith` fails with `ClassCastException`, and `contains` matches against `key.toString()`. `contains` scans every key linearly, so it is O(n) over the keys. An empty prefix, suffix or substring returns an empty list.

## Views

`<V> BuildStep<T> view(Class<V> viewType, Function<T, V> mapper)`

- One mapper per view class. Registering the same class twice throws IAE. The view class is the lookup key, so it does not need to relate to `T`.
- Views are computed once per item at snapshot build time (on every bootstrap and refresh) and stored next to the item. Queries then return the precomputed instances, with no mapping at query time.
- **The mapper must not return null.** The stored views map is `Map.copyOf`, so a null result throws NPE and fails the whole build.
- Query with the view class as the last argument: `catalog.search("by-venue", "Maracana", EventCard.class)`, `catalog.query("by-date").between(from, to, EventCard.class)`, compound `.execute(EventCard.class)`.
- An unregistered view class throws IAE `"View <Name> is not registered"`, but **only when the lookup matches items**. An empty result returns an empty list without checking.
- The hash is computed over the raw items, not over the views.

## Build hooks

`@FunctionalInterface public interface SnapshotBuildHook<T> { T process(T item); }`

| Method | Priority |
|---|---|
| `hook(SnapshotBuildHook<T> hook, int priority)` | explicit (lower runs first) |
| `hook(SnapshotBuildHook<T> hook)` | `100` |

- Hooks are sorted by priority at `build()`. The sort is stable, so hooks with the same priority run in registration order.
- The build is one loop per item: views, then all indexes, then hooks. So hooks run **after** the item has been indexed and projected.
- **The returned value does not replace the stored item.** It is only passed to the next hook in the chain. Use hooks for side effects (metrics, logging, validation). Do not use them to transform data; do that in the `DataLoader` instead.
- If a hook throws, the exception propagates out of `bootstrap()`/`refresh()`. The new snapshot is **not** swapped in: the previous snapshot and version stay. Throwing from a validation hook therefore rejects the whole load.

```java
final Catalog<Event> catalog = Catalog.of(Event.class)
    .named("events")
    .loadWith(loader)
    .index("by-venue").by(Event::venue, Venue::name)
    .hook(e -> { counter.increment(); return e; }, 10)
    .hook(e -> { if (e.venue() == null) { throw new IllegalStateException("no venue: " + e.id()); } return e; })
    .build();
```

## Lifecycle

| `Catalog<T>` method | Behaviour |
|---|---|
| `void bootstrap()` | Loads from the `DataLoader` (or the static data), builds, and atomically swaps the snapshot. |
| `void refresh()` | Re-runs the `DataLoader` and swaps. ISE `"Cannot refresh a catalog created with static data..."` if the catalog was built with `.data(...)`. |
| `void refresh(List<T> newData)` | Rebuilds from the given list (works for both kinds of catalog). NPE if the list or an element is null. |
| `Snapshot<T> currentSnapshot()` | The current immutable snapshot. Before bootstrap this is `Snapshot.empty()` (version `0`, hash `""`). |
| `List<T> search(String index, Object key)` | NPE on null arguments. Returns an empty list for an unknown index or key. |
| `<V> List<V> search(String index, Object key, Class<V> view)` | View variant. |
| `QueryStep<T> query(String index)` | Fluent equality/range/text query. `equalTo` on an unknown index throws `IndexNotFoundException`. |
| `CompoundQuery<T> compound()` / `GraphQueryBuilder<T> graphQuery()` | Multi-index intersection / graph queries. |
| `String name()`, `boolean hasDataLoader()`, `Optional<SnapshotSerializer<T>> serializer()`, `Optional<Duration> refreshInterval()`, `List<ViewDefinition<T, ?>> viewDefinitions()` | Accessors. |
| `CatalogInfo info()` | Lock-free statistics taken from the current snapshot. |

- Writes (`bootstrap` and both `refresh` overloads) are serialized by a `ReentrantLock`. Reads never lock. A query holds on to the snapshot that was current when it was created.
- Every successful build increments the version, which is a **node-local** counter starting at 1. A failed load or build (loader throws, hook throws, null element) leaves the previous snapshot and version untouched.
- **Incremental or partial refresh is not implemented.** `docs/plans/2026-07-28-incremental-refresh-design.md` is only a draft. Every refresh is a full reload and rebuild.
- In an application you normally do not call these methods directly: `Andersoni.register(catalog)` then `Andersoni.start()` bootstraps every catalog (with a `RetryPolicy`), and `andersoni.refreshAndSync(name)` refreshes it cluster-wide. Calling `catalog.refresh()` directly skips the snapshot store and sync.

### Snapshot<T> (public, immutable)

| Method | Returns |
|---|---|
| `List<T> data()` | All items, unmodifiable, in loader order |
| `long version()` | Build counter (`0` = empty) |
| `String hash()` | Lowercase hex SHA-256 of `serializer.serialize(data)`, or, without a serializer, of each `item.toString()` joined with a `0x00` separator. `""` when empty. |
| `Instant createdAt()` | Build time |
| `boolean hasIndex(String)` / `boolean hasSortedIndex(String)` | Index existence |
| `List<IndexInfo> indexInfo()` | Per-index statistics |
| `search(...)`, `searchBetween/GreaterThan/GreaterOrEqual/LessThan/LessOrEqual(...)`, `searchStartsWith/EndsWith/Contains(...)` | Same queries as `QueryStep`, each also available with a `Class<V>` view overload |

### CatalogInfo / IndexInfo (records)

```java
public record CatalogInfo(String catalogName, int itemCount, List<IndexInfo> indices,
    long totalEstimatedSizeBytes, int viewCount, List<String> viewTypeNames) { double totalEstimatedSizeMB(); }
public record IndexInfo(String name, int uniqueKeys, int totalEntries, long estimatedSizeBytes) { double estimatedSizeMB(); }
```

- `viewTypeNames` holds the simple class names of the views. `totalEstimatedSizeBytes` is the sum of `IndexInfo.estimatedSizeBytes`.
- `IndexInfo` has one entry per index (regular, sorted, multi, graph). The estimate is a structural heuristic. It counts: the HashMap base (48 B), buckets (8 B each), a node plus a list header per key (32 + 40 B), the key size (String: 40 + length, Number: 16, CompositeKey: 40 + its components, other: 50) and 8 B per entry.
- The estimate **excludes** the items themselves, the materialized views, and the extra sorted/reversed `TreeMap`s. Use it to compare indexes, not as heap usage.

## Common mistakes

| Mistake | Consequence / fix |
|---|---|
| `build()` without any index | ISE. Add at least one index. |
| Same name used for `index` and `indexSorted` (or any other pair of index types) | IAE `Duplicate index name`. The namespace is shared. |
| `.index("x").by(Event::id)` | Does not compile. Use `.by(Event::id, Function.identity())`. |
| `.refreshEvery(...)` with `.data(...)` | The scheduled refresh calls `refresh()` and throws ISE, which is logged every interval. Use `loadWith`. |
| `catalog.refresh()` on a `.data(...)` catalog | ISE. Use `refresh(List<T>)`. |
| Loader returns `null`, or a list containing null items | NPE, and the load is rejected. Return an empty list instead. |
| Unordered loader (`HashSet`, parallel fetch, SQL without `ORDER BY`) | Nodes holding the same data compute different hashes, which causes endless re-sync or reconciliation churn. |
| No serializer and `T` without a value `toString()` | `Object.toString()` includes the identity hash, so the hash changes on every load. Use records or configure a serializer. |
| Mutable items or mutable key fields | Snapshots share the item references. Mutating an item after the load leaves it in the bucket of its old key, and the stored hash no longer matches the data. Use immutable records. |
| Heavy or slow loader | Every refresh is a full reload on the leader, holding the refresh lock. Keep the loader to one bulk query, with no N+1 queries. |
| Searching with the wrong boxed type (`1` vs `1L`) or with the parent object instead of the extracted key | Returns an empty list. Search with exactly the extracted key type. |
| Range or text query on an `index(...)` | `UnsupportedIndexOperationException`. Declare it with `indexSorted`. |
| `view(...)` mapper returns null | NPE, and the whole build fails. |
| Transforming items in a hook | Has no effect on what is stored or returned. Transform in the loader. |
| Null search key | NPE. Null keys are never searchable, so model "missing" as a sentinel value if you need to query it. |
