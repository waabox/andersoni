# Graph-Based Composite Index Design

**Date:** 2026-03-15
**Author:** waabox(waabox[at]gmail[dot]com)
**Status:** Draft (reviewed)

## Context

Andersoni users currently simulate composite indexes by hand: domain objects
generate prefix keys (e.g., `countryCategoryPathKeys()`) and register them
via `indexMulti`. This leaks indexing logic into the domain model and forces
the user to manage cartesian product generation, path splitting, and key
formatting manually.

The goal is to provide a graph-based composite index that:

- Keeps the domain model clean -- no indexing logic in entities.
- Navigates entity relationships as a graph rooted at the catalog item.
- Pre-computes only the declared hotpaths (not all possible combinations).
- Maintains O(1) lookup at query time via HashMap.
- Requires zero changes to `Snapshot`, `CompoundQuery`, or existing index types.

## Domain Example (Fanki)

```
Publication (root)
  |-- events[] --> Event
  |                 |-- country --> Country (code: "AR")
  |                 |-- organizer --> Organizer
  |-- category --> Category
                     |-- parent --> Category (recursive)
                     |-- buildPath() --> "deportes/futbol/primera-division"
```

Access patterns:
1. All publications for country "AR"
2. Publications for country "AR" + category startsWith "deportes"
3. Publications for country "AR" + category startsWith "deportes/futbol"

Today this is solved with `Publication::countryCategoryPathKeys` which
generates `["AR::deportes", "AR::deportes/futbol", ...]` manually.

## Design

### Overview

A new index type `GraphIndexDefinition<T>` that:

1. Defines **traversals** -- named navigation paths from the root entity to a
   value, following relationships (including one-to-many).
2. Defines **hotpaths** -- combinations of traversals to pre-compute as
   `CompositeKey` entries in the standard `Map<Object, List<T>>`.
3. At indexing time, navigates the graph per item, generates keys only for
   declared hotpaths, and stores them in the existing Snapshot index structure.

### Components

#### 1. `CompositeKey`

Immutable value object that wraps N components. Implements `equals` and
`hashCode` based on the components list.

`CompositeKey` does **not** implement `Comparable`. All lookups are via
`HashMap.get()` (O(1)). If sorted composite queries are needed in the future,
`Comparable` can be added then with proper type constraints.

```java
public final class CompositeKey {

  private final List<Object> components;

  public static CompositeKey of(final Object... components) { ... }

  // equals/hashCode based on components list
}
```

**Complexity:** `equals`/`hashCode` is O(k) where k = number of components
(typically 2-3, effectively constant).

#### 2. Traversal Types

A traversal defines how to extract a named value from the root entity by
navigating its relationships.

`Traversal<T>` is a sealed interface with three permitted implementations.

##### Null Handling Contract

All traversal types follow the `MultiKeyIndexDefinition` precedent:
- If a traversal returns `null` or empty, **prefix keys for prior fields
  in the hotpath are still generated**. Only the full-length key and keys
  beyond the null field are excluded. For example, if hotpath is
  `("country", "category")` and `category` returns null for an item with
  `country = "AR"`, the engine still generates `CompositeKey("AR")` (the
  prefix), but not `CompositeKey("AR", <anything>)`.
- `FanOutTraversal`: if the collection accessor returns `null` or empty list,
  treated as producing no values. Null elements within the collection are
  skipped. Null extracted values are skipped.
- `PathTraversal`: if the extractor returns `null` or empty string, treated
  as producing no values.

##### `SimpleTraversal`

Extracts a single value through a chain of accessors.

```java
// country: Publication -> events[0] -> country -> code
.traverse("country", pub -> pub.events().getFirst().country().code())
```

Produces: one value per item.

##### `FanOutTraversal`

Navigates a one-to-many relationship, producing multiple values.
**Values are automatically deduplicated** using `equals` at evaluation time
(e.g., if two events have the same country code, one key is generated).

```java
// country: Publication -> events[] -> country -> code (fan-out over events)
.traverseMany("country", Publication::events, event -> event.country().code())
```

Produces: N distinct values per item (one per unique element in the collection).

##### `PathTraversal`

Extracts a path string and splits it by a separator, producing prefix keys
at every segment level. Match is always by **complete segments** (not
arbitrary string prefix).

**Empty segments** resulting from leading/trailing separators or consecutive
separators are filtered out during split. E.g., `"/deportes//futbol/"` with
separator `"/"` produces `["deportes", "deportes/futbol"]`, not
`["", "deportes", "", "deportes/futbol", ""]`.

```java
// category: "deportes/futbol/liga" -> ["deportes", "deportes/futbol", "deportes/futbol/liga"]
.traversePath("category", "/", Publication::categoryPath)
```

Produces: N values per item (one per path depth level).

#### 3. Hotpath Declaration

A hotpath declares which traversals to combine into pre-computed composite
keys. The order matters -- it defines the left-to-right prefix order
(like a B-tree composite index).

```java
.hotpath("country", "category")
```

For an item with `country` traversal producing `["AR", "MX"]` (fan-out)
and `category` path traversal producing `["deportes", "deportes/futbol"]`:

Generated keys:
- `CompositeKey("AR", "deportes")`
- `CompositeKey("AR", "deportes/futbol")`
- `CompositeKey("MX", "deportes")`
- `CompositeKey("MX", "deportes/futbol")`

Additionally, **prefix keys** for each hotpath are generated automatically.
A hotpath `("country", "category")` also generates single-component keys:
- `CompositeKey("AR")`
- `CompositeKey("MX")`

This enables prefix queries (e.g., only by country) using the same index.

**Multiple hotpaths** can be declared per graph index. All hotpaths within
one `GraphIndexDefinition` contribute to a **single** `Map<Object, List<T>>`.
Overlapping prefix keys are naturally deduplicated -- items appear once per
key, not duplicated per hotpath. Item deduplication per key uses **identity
comparison** (consistent with `CompoundQuery.intersect`).

#### 4. `GraphIndexDefinition<T>`

The index definition that holds traversals, hotpaths, and builds the index.

```java
public final class GraphIndexDefinition<T> {

  private final String name;
  private final Map<String, Traversal<T>> traversals;
  private final List<Hotpath> hotpaths;
  private final int maxKeysPerItem;  // safety limit, default 100

  public Map<Object, List<T>> buildIndex(final List<T> data) { ... }
}
```

`buildIndex` iterates each item, evaluates each hotpath by resolving its
traversals, computes the cartesian product across traversal results
(including prefix generation for path traversals and prefix keys for the
hotpath itself), deduplicates items per key using identity comparison, and
groups items by `CompositeKey`.

The output is `Map<Object, List<T>>` -- identical to `IndexDefinition` and
`MultiKeyIndexDefinition`. **No changes to `Snapshot` required.**

#### 5. Safety Limits

If the cartesian product for a single item exceeds `maxKeysPerItem`, the
engine throws `IndexKeyLimitExceededException` at indexing time. This is
**fail-fast** behavior that applies in all environments including production.

Users should set the limit generously based on expected data characteristics.
If data changes in production cause an item to exceed the limit, the catalog
will fail to refresh -- this is intentional to surface data anomalies early.

```java
.indexGraph("by-country-category")
    .traverseMany("country", Publication::events, e -> e.country().code())
    .traversePath("category", "/", Publication::categoryPath)
    .hotpath("country", "category")
    .maxKeysPerItem(50)  // optional, default 100
```

#### 6. Query Planner

The query planner receives field conditions and selects the best hotpath.

##### Input

```java
catalog.graphQuery()
    .where("country").eq("AR")
    .and("category").eq("deportes/futbol")
    .execute();
```

Note: the entry point is `catalog.graphQuery()` (not `catalog.query()`) to
avoid collision with the existing `Catalog.query(String indexName)` method
that returns `QueryStep<T>`.

##### GraphQueryCondition

The query planner uses its own condition type `GraphQueryCondition`
(distinct from the existing package-private `Condition` used by
`CompoundQuery`). `GraphQueryCondition` references **traversal field names**
(e.g., "country", "category") rather than index names.

```java
record GraphQueryCondition(String fieldName, Operation operation, Object[] args) {}
```

##### Resolution Algorithm

1. Collect query conditions as `Map<String, GraphQueryCondition>`.
2. For each graph index in the catalog:
   a. For each hotpath in the index:
      - Compute the **longest usable prefix**: contiguous fields from the
        left where all conditions are `eq`. Path fields resolve via
        pre-generated prefix keys, so `eq` on a path field matches the
        exact path prefix -- still O(1).
      - Score = number of fields covered by the prefix.
   b. Track the hotpath with the highest score.
3. Pick the graph index + hotpath with the highest score across all indexes.
4. Build the `CompositeKey` from the covered fields.
5. HashMap lookup: O(1).
6. If there are remaining conditions not covered by the hotpath, throw
   `UnsupportedOperationException` -- the user must define a graph index
   that covers all query fields, or use `compound()` for uncovered fields.
   Post-filtering may be added in a future version.

##### Fallback

If no graph index covers any field, `graphQuery().execute()` returns an
empty list. This is intentional: if the user queries by fields not covered
by any graph index, the result is empty rather than a silent full scan.
This forces explicit index design. For queries on regular indexes, users
should use `search()`, `query()`, or `compound()`.

#### 7. DSL Integration in Catalog Builder

##### `GraphIndexStep<T>`

The builder uses a `GraphIndexStep<T>` inner class that accumulates
traversals and hotpaths. It holds a back-reference to the parent
`BuildStep<T>`.

- `.traverse(...)`, `.traverseMany(...)`, `.traversePath(...)` return
  `GraphIndexStep<T>` (accumulate traversals).
- `.hotpath(...)` returns `GraphIndexStep<T>` (allows multiple hotpaths).
- `.maxKeysPerItem(int)` returns `GraphIndexStep<T>`.
- `.done()` finalizes the graph index definition and returns `BuildStep<T>`,
  re-entering the main builder chain.

```java
public static final class GraphIndexStep<T> {

  private final BuildStep<T> buildStep;
  private final String indexName;
  private final Map<String, Traversal<T>> traversals;
  private final List<Hotpath> hotpaths;
  private int maxKeysPerItem = 100;

  public GraphIndexStep<T> traverse(String name, Function<T, ?> extractor) { ... }

  public <C> GraphIndexStep<T> traverseMany(String name,
      Function<T, ? extends Collection<C>> collectionAccessor,
      Function<C, ?> valueExtractor) { ... }

  public GraphIndexStep<T> traversePath(String name, String separator,
      Function<T, String> extractor) { ... }

  public GraphIndexStep<T> hotpath(String... fieldNames) { ... }

  public GraphIndexStep<T> maxKeysPerItem(int max) { ... }

  public BuildStep<T> done() { ... }
}
```

##### Full DSL Example

```java
Catalog.of(Publication.class)
    .named("publications")
    .loadWith(loader)
    // Existing indexes still work
    .index("by-slug").by(Publication::slug, Function.identity())
    .indexMulti("by-event").by(Publication::eventCodes)
    // New graph index
    .indexGraph("by-country-category")
        .traverseMany("country", Publication::events,
            event -> event.country().code())
        .traversePath("category", "/", Publication::categoryPath)
        .hotpath("country", "category")
        .hotpath("country")  // explicit single-field hotpath (optional,
                              // already generated as prefix of the above)
        .maxKeysPerItem(50)
        .done()
    .build();
```

Query:

```java
// By country only -- uses prefix key CompositeKey("AR")
catalog.graphQuery()
    .where("country").eq("AR")
    .execute();

// By country + category drill-down -- uses CompositeKey("AR", "deportes/futbol")
catalog.graphQuery()
    .where("country").eq("AR")
    .and("category").eq("deportes/futbol")
    .execute();
```

Note: for path fields, `eq("deportes/futbol")` means "match this exact
path prefix" -- the engine already generated that as a key. The user does
not need to use `startsWith`; `eq` with a path segment is the natural
query for drill-down.

#### 8. Integration into `Catalog` Internals

##### Catalog Constructor

`Catalog` receives a new field:
```java
private final List<GraphIndexDefinition<T>> graphIndexDefinitions;
```

Passed from `BuildStep` alongside existing `indexDefinitions`,
`multiKeyIndexDefinitions`, etc.

##### `buildAndSwapSnapshot`

In the `buildAndSwapSnapshot` method, after building regular and multi-key
indices, iterate `graphIndexDefinitions`:

```java
for (final GraphIndexDefinition<T> graphDef : graphIndexDefinitions) {
    indices.put(graphDef.name(), graphDef.buildIndex(data));
}
```

The output merges into the same `Map<String, Map<Object, List<T>>> indices`
map that existing indexes use. Snapshot receives it unchanged.

##### `graphQuery()` Entry Point

New method on `Catalog`:
```java
public GraphQueryBuilder<T> graphQuery() {
    return new GraphQueryBuilder<>(snapshot.get(), name, graphIndexDefinitions);
}
```

### Full Example: Fanki Publication Service

Before (today):

```java
final Catalog<Publication> publications = Catalog.of(Publication.class)
    .named("publications")
    .loadWith(() -> thePublicationRepository.findPublished())
    .serializer(new JacksonSnapshotSerializer<>(...))
    .indexMulti("by-event").by(Publication::eventCodes)
    .indexMulti("by-category").by(pub ->
        pub.category() != null ? pub.category().ancestorIds() : List.of())
    .index("by-country").by(Publication::countryCode, Function.identity())
    .indexMulti("by-country-category-path").by(Publication::countryCategoryPathKeys)
    .indexMulti("by-organizer").by(Publication::organizerIds)
    .index("by-slug").by(Publication::slug, Function.identity())
    .build();
```

After:

```java
final Catalog<Publication> publications = Catalog.of(Publication.class)
    .named("publications")
    .loadWith(() -> thePublicationRepository.findPublished())
    .serializer(new JacksonSnapshotSerializer<>(...))
    .indexMulti("by-event").by(Publication::eventCodes)
    .indexMulti("by-category").by(pub ->
        pub.category() != null ? pub.category().ancestorIds() : List.of())
    .indexMulti("by-organizer").by(Publication::organizerIds)
    .index("by-slug").by(Publication::slug, Function.identity())
    .indexGraph("by-country-category")
        .traverseMany("country", Publication::events,
            event -> event.country().code())
        .traversePath("category", "/", Publication::categoryPath)
        .hotpath("country", "category")
        .done()
    .build();
```

Changes:
- Removed `index("by-country")` -- covered by hotpath prefix key.
- Removed `indexMulti("by-country-category-path")` -- covered by hotpath.
- Removed `Publication::countryCategoryPathKeys` from domain model.
- Domain is clean: no indexing logic in Publication.

## Time Complexity

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| **Bootstrap (indexing)** | O(n x h x f x d) | n=items, h=hotpaths, f=max fan-out, d=max path depth |
| **Query with hotpath (any)** | **O(1)** | HashMap get with CompositeKey |
| **Query planner selection** | O(g x h) | g=graph indexes, h=hotpaths per index |
| **Post-filter (uncovered fields)** | O(k) | k=results from index lookup |
| **Query without any index** | O(n) | Full scan (fallback) |

## Space Complexity

Per item, per hotpath: O(f x d) keys generated, where f = fan-out of
multiField traversals and d = depth of path traversals. Each key is a
`CompositeKey` entry in the HashMap pointing to a shared reference of the
item (not a copy).

The `maxKeysPerItem` limit prevents accidental explosion.

## Memory Estimation

`Snapshot.estimateKeySize` must be updated to recognize `CompositeKey` and
sum its component sizes, rather than falling through to the
`DEFAULT_KEY_SIZE = 50` estimate. A `CompositeKey` with 3 String components
of average length 15 would be ~160 bytes (object header + list overhead +
3 x String), not 50.

```java
if (key instanceof CompositeKey ck) {
    long size = 40L; // object header + List overhead
    for (final Object component : ck.components()) {
        size += estimateKeySize(component);
    }
    return size;
}
```

## New Classes

| Class | Purpose |
|-------|---------|
| `CompositeKey` | Immutable value object for multi-component keys |
| `GraphIndexDefinition<T>` | Holds traversals + hotpaths, builds the index |
| `Traversal<T>` | Sealed interface: Simple, FanOut, Path |
| `Hotpath` | Named ordered list of traversal names |
| `QueryPlanner<T>` | Resolves query conditions to best index + hotpath |
| `GraphQueryBuilder<T>` | Fluent DSL for field-based queries on a catalog |
| `GraphQueryCondition` | Condition type for graph queries (field-name based) |
| `GraphQueryConditionStep<T>` | Intermediate step for specifying condition operation |
| `IndexKeyLimitExceededException` | Thrown when maxKeysPerItem is exceeded |
| `Catalog.GraphIndexStep<T>` | Builder step for graph index DSL |

## Classes Modified

| Class | Change |
|-------|--------|
| `Catalog` | Add `graphQuery()` entry point, store `graphIndexDefinitions` |
| `Catalog.BuildStep` | Add `indexGraph()`, store `GraphIndexDefinition` list, pass to Catalog |
| `Catalog.buildAndSwapSnapshot` | Iterate `graphIndexDefinitions` and merge into indices map |
| `Snapshot` | Update `estimateKeySize` to handle `CompositeKey` |

## Classes NOT Modified

| Class | Why |
|-------|-----|
| `Snapshot` (structure) | Graph index outputs `Map<Object, List<T>>` like any other index |
| `CompoundQuery` | Unrelated, works on existing indexes |
| `IndexDefinition` | Untouched, still works as before |
| `MultiKeyIndexDefinition` | Untouched, still works as before |
| `Condition` | Used by CompoundQuery only; graph queries use GraphQueryCondition |

## Constraints

- Graph traversal is from the root entity only -- no cross-catalog joins.
- Hotpaths must reference defined traversals.
- Traversal names must be unique within a graph index.
- Hotpath field order defines the prefix order (left-to-right).
- `maxKeysPerItem` default: 100. Configurable per graph index.
- Path field separator must be non-empty.
- Path field match is always by complete segments, never partial.
- Fan-out traversal values are deduplicated using `equals` at evaluation time.
- Items are deduplicated per CompositeKey using identity comparison.

## Decisions

1. `catalog.graphQuery()` coexists with `catalog.compound()` -- compound works
   on existing indexes, graphQuery works with the planner and graph indexes.
2. v1: the planner picks one graph index OR falls back to simple indexes,
   not a mix of both in the same query.
3. Fan-out value deduplication is automatic using a `LinkedHashSet` at
   traversal evaluation time.
