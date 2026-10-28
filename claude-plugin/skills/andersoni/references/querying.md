# Querying Andersoni catalogs

Reading data from a registered catalog: `search`, `query(...)` / `QueryStep`, `compound(...)` / `CompoundQuery`.
Graph indexes (`indexGraph`, `graphQuery()`) are covered in [graph-index.md](graph-index.md).

Verified against Andersoni 1.13.0 (`io.github.waabox`, package `org.waabox.andersoni`).

## Contents

1. [Entry points](#entry-points)
2. [search](#search)
3. [query / QueryStep](#query--querystep)
4. [compound / CompoundQuery](#compound--compoundquery)
5. [Which index type supports which operation](#which-index-type-supports-which-operation)
6. [Results: type, order, duplicates](#results-type-order-duplicates)
7. [Errors and availability](#errors-and-availability)
8. [Performance](#performance)
9. [Common mistakes](#common-mistakes)

## Entry points

The examples below use this catalog:

```java
record Venue(String name) {}
record Event(String id, String name, Venue venue, String sport, int rank, LocalDate date, List<String> tags) {}
record EventSummary(String id, String name) {}

final Catalog<Event> catalog = Catalog.of(Event.class)
    .named("events")
    .loadWith(() -> repository.findAll())
    .index("by-venue").by(Event::venue, Venue::name)          // hash index, key = String
    .index("by-sport").by(Event::sport, Function.identity())  // hash index, key = String
    .indexSorted("by-name").by(Event::name, Function.identity()) // sorted, String key
    .indexSorted("by-rank").by(Event::rank, Function.identity()) // sorted, Integer key
    .indexSorted("by-date").by(Event::date, Function.identity()) // sorted, LocalDate key
    .indexMulti("by-tag").by(Event::tags)                     // hash index, one entry per key
    .view(EventSummary.class, e -> new EventSummary(e.id(), e.name()))
    .build();
andersoni.register(catalog);
andersoni.start();
```

You can query in two ways. Both read the same lock-free `AtomicReference<Snapshot<T>>`.

| Via `Andersoni` (by catalog name) | Via `Catalog<T>` (direct reference) |
|---|---|
| `List<?> search(String catalogName, String indexName, Object key)` | `List<T> search(String indexName, Object key)` |
| `<T> List<T> search(String catalogName, String indexName, Object key, Class<T> type)` | `<V> List<V> search(String indexName, Object key, Class<V> viewType)` |
| `QueryStep<?> query(String catalogName, String indexName)` | `QueryStep<T> query(String indexName)` |
| `<T> QueryStep<T> query(String catalogName, String indexName, Class<T> type)` | |
| `CompoundQuery<?> compound(String catalogName)` | `CompoundQuery<T> compound()` |
| `<T> CompoundQuery<T> compound(String catalogName, Class<T> type)` | |
| `<T> Catalog<T> catalog(String catalogName, Class<T> type)` returns the catalog (unchecked cast) | `Snapshot<T> currentSnapshot()` |

Only the `Andersoni` methods check whether the catalog is FAILED and throw `CatalogNotAvailableException`. The `Catalog` methods (including a catalog obtained from `andersoni.catalog(...)`) never do.

## search

Equality lookup: one `HashMap.get` on the current snapshot.

```java
final List<Event> atWembley = andersoni.search("events", "by-venue", "Wembley", Event.class);
final List<EventSummary> summaries = andersoni.search("events", "by-venue", "Wembley", EventSummary.class);
final List<?> raw = andersoni.search("events", "by-venue", "Wembley");
```

How the `Class<T> type` argument of `Andersoni.search(..., type)` is used:

| `type` passed | Result |
|---|---|
| A view class registered with `.view(...)` | The pre-computed views (`List<V>`) |
| Any other class (the catalog's `T`, or anything else) | The catalog items, **unchecked-cast** to `List<T>`. An unrelated class such as `String.class` does not throw here. It throws `ClassCastException` later, when you read an element |

`Catalog.search(indexName, key, viewType)` always treats `viewType` as a view. An unregistered class throws `IllegalArgumentException("View X is not registered")`, but only if at least one item matches. An empty result returns `[]` without checking.

`search` never throws for an unknown index name. It returns an empty list. See [Errors](#errors-and-availability).

## query / QueryStep

`query(...)` returns a `QueryStep<T>` bound to the snapshot that is current **at the moment you call it**. A later refresh does not change a `QueryStep` you already hold, so create a new one for each read.

Every operation has an items overload and a view overload (add `Class<V> viewType` as the last argument):

| Operation | Items overload | View overload | Semantics | Needs |
|---|---|---|---|---|
| equalTo | `List<T> equalTo(Object key)` | `<V> List<V> equalTo(Object key, Class<V> viewType)` | key equals | any index |
| between | `List<T> between(Comparable<?> from, Comparable<?> to)` | `+ Class<V> viewType` | `from <= key <= to` (both inclusive) | sorted |
| greaterThan | `List<T> greaterThan(Comparable<?> key)` | `+ Class<V> viewType` | `key > x` | sorted |
| greaterOrEqual | `List<T> greaterOrEqual(Comparable<?> key)` | `+ Class<V> viewType` | `key >= x` | sorted |
| lessThan | `List<T> lessThan(Comparable<?> key)` | `+ Class<V> viewType` | `key < x` | sorted |
| lessOrEqual | `List<T> lessOrEqual(Comparable<?> key)` | `+ Class<V> viewType` | `key <= x` | sorted |
| startsWith | `List<T> startsWith(String prefix)` | `+ Class<V> viewType` | prefix match (TreeMap range) | sorted, **String keys** |
| endsWith | `List<T> endsWith(String suffix)` | `+ Class<V> viewType` | suffix match (reversed-key TreeMap) | sorted, **String keys** |
| contains | `List<T> contains(String substring)` | `+ Class<V> viewType` | `key.toString().contains(substring)`, scans every key | sorted (any key type) |

Arguments must be non-null (`NullPointerException`). An empty `prefix`, `suffix` or `substring` returns `[]` right away, before the index type is checked.

```java
final List<Event> top = andersoni.query("events", "by-rank", Event.class).lessOrEqual(10);
final List<Event> q1 = catalog.query("by-date").between(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
final List<EventSummary> champions = catalog.query("by-name").startsWith("Champions", EventSummary.class);
final List<?> untyped = andersoni.query("events", "by-name").endsWith("Final");
```

The untyped `QueryStep<?>` still returns a typed `List<V>` from the view overloads: `andersoni.query("events", "by-date").between(from, to, EventSummary.class)`.

## compound / CompoundQuery

AND across several indexes: each condition is evaluated separately and the results are **intersected by identity (`==`)**.

```java
final List<Event> hits = andersoni.compound("events", Event.class)
    .where("by-sport").equalTo("Football")
    .and("by-date").greaterThan(LocalDate.now())
    .and("by-rank").lessOrEqual(100)
    .execute();

final List<EventSummary> cards = catalog.compound()
    .where("by-venue").equalTo("Maracana")
    .and("by-name").startsWith("Copa")
    .execute(EventSummary.class);
```

| Method | Signature | Notes |
|---|---|---|
| where | `CompoundConditionStep<T> where(String indexName)` | First condition. A second call throws `IllegalStateException` |
| and | `CompoundConditionStep<T> and(String indexName)` | Each additional condition (AND). You can call `and` without a prior `where` |
| step ops | `equalTo(Object)`, `between(Comparable<?>, Comparable<?>)`, `greaterThan`, `greaterOrEqual`, `lessThan`, `lessOrEqual` (`Comparable<?>`), `startsWith`, `endsWith`, `contains` (`String`) | Each returns the parent `CompoundQuery<T>`. Null arguments throw NPE immediately |
| execute | `List<T> execute()` | Throws `IllegalStateException` if there are no conditions |
| execute | `<V> List<V> execute(Class<V> viewType)` | Throws `IllegalArgumentException` for an unregistered view, but only when the result is non-empty |

There is no OR, NOT or `in(...)`. For OR, run separate queries and merge the results yourself. For a multi-value match, use an `indexMulti` key.

How `execute()` runs:
- Conditions are evaluated **in the order you declare them**. Nothing is reordered.
- The query short-circuits. If a condition returns empty, or an intersection becomes empty, it returns `[]` immediately and **skips the remaining conditions, including their index validation**. So a misspelled index after an empty condition does not throw.
- Each condition's full match list is built before it is intersected. The intersection is O(a+b), using an identity set over the smaller list.
- A `CompoundQuery` is mutable and bound to the snapshot current at `compound()` time. Build it, execute it, then discard it. Do not share one across threads.

## Which index type supports which operation

| Index (builder) | equalTo / search | range ops | startsWith | endsWith | contains |
|---|---|---|---|---|---|
| `index(...)` hash | yes | `UnsupportedIndexOperationException` | `UnsupportedIndexOperationException` | `UnsupportedIndexOperationException` | `UnsupportedIndexOperationException` |
| `indexMulti(...)` | yes | same as hash | same | same | same |
| `indexSorted(...)` String key | yes | yes | yes | yes | yes |
| `indexSorted(...)` non-String key (Integer, LocalDate, ...) | yes | yes | `ClassCastException` (not the Andersoni exception) | `UnsupportedIndexOperationException` ("Key type must be String") | yes, matches on `key.toString()` |
| `indexGraph(...)` | a `CompositeKey`, see [graph-index.md](graph-index.md) | no | no | no | no |

Exception messages:
- Not sorted: `Index 'x' does not support range queries. Use indexSorted() to enable them`.
- endsWith on non-String keys: `Index 'x' does not support text queries. Key type must be String`.

A sorted index detects String keys from its first non-null key. If a sorted String catalog loads **zero items**, `endsWith` throws `UnsupportedIndexOperationException` until data arrives. `startsWith`, `contains`, range ops and `equalTo` return `[]` in that case.

## Results: type, order, duplicates

- Results are always non-null and **unmodifiable**, including empty results. `add` and `clear` throw `UnsupportedOperationException`. Copy them (`new ArrayList<>(list)`) before mutating.
- The elements are the same instances that live in the snapshot. Treat them as immutable (use records).
- Order for `equalTo` / `search`: items in data-loader order within the key's bucket.
- Order for range, `startsWith` and `contains`: ascending key order, and loader order within each key. `endsWith` orders by **reversed** key.
- Order for `compound`: the order of the larger of the two lists being intersected. Do not rely on it. Sort explicitly if you need a specific order.
- Items whose key is null are not indexed. You cannot query for null: a null key throws NPE.
- Duplicates are possible: if `indexMulti` returns the same key twice for one item (for example `List.of("x", "x")`), that item appears twice under `"x"`, and a compound query keeps both copies. Deduplicate the keys in your extractor, for example `e -> List.copyOf(new LinkedHashSet<>(e.tags()))`.
- View overloads return the view objects pre-computed at snapshot build time. There is no mapping cost at query time.

## Errors and availability

| Situation | `search` | `query(...).equalTo` | `query(...)` range / text op | `compound(...).execute()` |
|---|---|---|---|---|
| Unknown catalog name (`Andersoni` API) | `IllegalArgumentException("No catalog registered with name 'x'")` | same (thrown by `query(...)`) | same | same (thrown by `compound(...)`) |
| Catalog FAILED at bootstrap (`Andersoni` API) | `CatalogNotAvailableException` | same (thrown by `query(...)`) | same | same (thrown by `compound(...)`) |
| Unknown index name | `[]` (silent) | `IndexNotFoundException` | `UnsupportedIndexOperationException` | `IndexNotFoundException` (unless it short-circuits first) |
| Registered but `start()` / `bootstrap()` not run yet (empty snapshot) | `[]` | `IndexNotFoundException` | `UnsupportedIndexOperationException` | `IndexNotFoundException` |
| Key not present | `[]` | `[]` | `[]` | `[]` |
| Unregistered view class, non-empty result | typed `Andersoni.search`: silently returns items; `Catalog.search`: `IllegalArgumentException` | `IllegalArgumentException` | `IllegalArgumentException` | `IllegalArgumentException` |
| null argument | `NullPointerException` | NPE | NPE | NPE (thrown by the step op) |

- `CatalogNotAvailableException extends RuntimeException`. It is thrown only after bootstrap retries are exhausted (see the `RetryPolicy` reference). A catalog that has not started yet does **not** throw it: before `start()` you just get empty snapshots.
- `IndexNotFoundException` and `UnsupportedIndexOperationException` are `final` and extend `RuntimeException` (not `AndersoniException`).
- `andersoni.start()` bootstraps every catalog synchronously before it returns. Query after `start()`, or check `andersoni.status()` for per-catalog readiness.
- Reads keep working after `stop()`, served from the last snapshot.

## Performance

README benchmarks (Apple M-series, JDK 21, average latency per query, 100K iterations). These are README numbers, not guarantees:

| Operation | Structure | 10,000 items | 100,000 | 500,000 |
|---|---|---|---|---|
| `equalTo` / `search` | HashMap | ~90 ns | ~55 ns | ~28 ns |
| `between` | TreeMap subMap | ~1.4 us | ~3.5 us | ~10.5 us |
| `greaterThan` / `greaterOrEqual` | TreeMap tailMap | ~0.7-0.8 us | ~2.0-2.2 us | ~8.4-9.1 us |
| `lessThan` / `lessOrEqual` | TreeMap headMap | ~0.7-0.8 us | ~1.7-1.9 us | ~9.3-9.5 us |
| `startsWith` | TreeMap subMap | ~1.3 us | ~9.6 us | ~43 us |
| `endsWith` | reversed TreeMap | ~0.3 us | ~0.5 us | ~2.2 us |
| `contains` | full key scan | ~2.1 us | ~9.9 us | ~44 us |

What the code implies:
- Range and prefix ops cost O(log n + k) and copy k matches into a new list. Wide ranges are expensive, so narrow them.
- `contains` is O(distinct keys) and calls `toString()` on every key. Prefer `startsWith` or `endsWith`, or build a dedicated `indexMulti` token index.
- `compound`: put the **most selective condition first**. Every condition materializes its full match list, and short-circuiting only helps when a condition comes back empty.
- `CompoundQuery.execute(Class<V>)` builds an `IdentityHashMap` over **every item in the catalog** on each call, so it is O(catalog size). This differs from `QueryStep` view overloads, which read views directly. On a large catalog in a hot path, one alternative is a single-index `QueryStep` view query.

## Common mistakes

1. **Wrong key type.** The key must equal what the extractor returns: same class, `equals`/`hashCode` compatible. For an `int` extractor, `equalTo(1L)` returns `[]` silently, and `between(1L, 2L)` on an Integer sorted index throws `ClassCastException`.
2. **Date as String vs LocalDate.** A `LocalDate` sorted index needs `LocalDate` arguments. `greaterThan("2026-01-01")` throws `ClassCastException`. To use text ops on dates (`startsWith("2026-06")`), index an ISO string instead: `.indexSorted("by-day").by(Event::date, LocalDate::toString)`. Lexicographic order then matches chronological order.
3. **`between(from, to)` with `from > to`** throws `IllegalArgumentException("fromKey > toKey")` from `TreeMap`. Validate or swap user input first.
4. **Range or text op on `index(...)` or `indexMulti(...)`** throws `UnsupportedIndexOperationException`. Declare the index with `indexSorted(...)`.
5. **`startsWith` on a non-String sorted index** throws `ClassCastException`. Index a `String` key (for example via `String::valueOf`).
6. **Passing a view class to `query(cat, idx, View.class)` or `compound(cat, View.class)`.** The `type` there is an unchecked cast of `T`, so you get items typed as `View` and a `ClassCastException` on access. Pass the view class to the operation instead: `.equalTo(key, View.class)` / `.execute(View.class)`.
7. **Treating `search`'s silent `[]` as "no data".** A typo in the index name also returns `[]`. `query(...).equalTo(...)` validates the name and throws `IndexNotFoundException`.
8. **Holding a `QueryStep` or `CompoundQuery` in a field.** It is bound to an old snapshot and never sees refreshes.
9. **Mutating results.** They are unmodifiable (`UnsupportedOperationException`).
10. **Querying before `start()`.** You get empty results or exceptions, depending on the API (see the [Errors](#errors-and-availability) table), not `CatalogNotAvailableException`.
11. **Case sensitivity.** All matching is exact and case-sensitive. To search case-insensitively, normalize in the extractor (`name.toLowerCase(Locale.ROOT)`) and normalize the query argument the same way.
