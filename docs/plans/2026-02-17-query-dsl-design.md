# Query DSL with Sorted Indexes

## Summary

Add a Query DSL to Andersoni that supports range queries, pattern matching, and
exact-match lookups over indexed in-memory data. Introduces a new `indexSorted()`
builder method that creates dual-structure indexes (HashMap + TreeMap) and a
`query()` entry point that returns a fluent `QueryStep<T>`.

## Design Decisions

- **Approach A (single `query()` with runtime check)**: one entry point, all
  operations available. If an operation is not supported by the index type, a
  clear runtime exception is thrown.
- **Backwards compatible**: existing `search(indexName, key)` is preserved as a
  shortcut for `equalTo`.
- **Return type**: all query operations return `List<T>`.
- **Type safety at build time**: `indexSorted()` requires `<K extends Comparable<K>>`
  via the compiler. Range operations on non-sorted indexes fail at runtime with
  clear messages.

## Index Types

### `index()` (existing, unchanged)

- Backing structure: `HashMap<Object, List<T>>`
- Supports: `equalTo` only
- Complexity: O(1)

### `indexSorted()` (new)

- Backing structures:
  - `HashMap<Object, List<T>>` for `equalTo` — O(1)
  - `TreeMap<Comparable<?>, List<T>>` for range queries — O(log n + k)
  - `TreeMap<String, List<T>>` reversed keys (only when key is String) for
    `endsWith` — O(log n + k)
- All three structures share the same `List<T>` instances (no data duplication)
- The `by()` method enforces `<K extends Comparable<K>>` at compile time

## Catalog Builder DSL

```java
Catalog.of(Event.class)
    .named("events")
    .loadWith(loader::findAll)
    .index("by-venue").by(Event::venue, Venue::name)
    .indexSorted("by-date").by(Event::eventDate, EventDate::value)
    .build();
```

`BuildStep` gains a new method:

```java
public SortedIndexStep<T> indexSorted(final String indexName)
```

`SortedIndexStep<T>` has:

```java
public <I, K extends Comparable<K>> BuildStep<T> by(
    final Function<T, I> first,
    final Function<I, K> second
)
```

The `build()` method accepts at least one index of any type.

## Snapshot Changes

Two maps of indices:

```java
private final Map<String, Map<Object, List<T>>> indices;                // index()
private final Map<String, TreeMap<Comparable<?>, List<T>>> sortedIndices; // indexSorted()
```

Sorted indexes have entries in both maps (HashMap in `indices`, TreeMap in
`sortedIndices`). The `List<T>` instances are shared.

When the key type is `String`, a third structure is maintained:

```java
private final Map<String, TreeMap<String, List<T>>> reversedKeyIndices;
```

Keys are stored reversed (e.g., `"Maracana"` → `"anacaraM"`).

New methods on `Snapshot`:

```java
List<T> searchBetween(String indexName, Comparable<?> from, Comparable<?> to)
List<T> searchGreaterThan(String indexName, Comparable<?> key)
List<T> searchGreaterOrEqual(String indexName, Comparable<?> key)
List<T> searchLessThan(String indexName, Comparable<?> key)
List<T> searchLessOrEqual(String indexName, Comparable<?> key)
List<T> searchStartsWith(String indexName, String prefix)
List<T> searchEndsWith(String indexName, String suffix)
List<T> searchContains(String indexName, String substring)
```

Existing `search(indexName, key)` is unchanged.

## Query DSL

### Entry Points

```java
// On Catalog
public QueryStep<T> query(final String indexName)

// On Andersoni
public QueryStep<?> query(final String catalogName, final String indexName)
```

### QueryStep

```java
public final class QueryStep<T> {

    // Works on any index (HashMap)
    List<T> equalTo(Object key);

    // Requires sorted index (TreeMap)
    List<T> between(Comparable<?> from, Comparable<?> to);
    List<T> greaterThan(Comparable<?> key);
    List<T> greaterOrEqual(Comparable<?> key);
    List<T> lessThan(Comparable<?> key);
    List<T> lessOrEqual(Comparable<?> key);

    // Requires sorted index with String key
    List<T> startsWith(String prefix);
    List<T> endsWith(String suffix);
    List<T> contains(String substring);
}
```

`QueryStep` is stateless — created on each `query()` call, holds a reference to
the current `Snapshot` and the index name.

## Query Complexity

| Operation | Structure | Complexity |
|-----------|-----------|------------|
| `equalTo` | HashMap | O(1) |
| `between`, `>`, `<`, `>=`, `<=` | TreeMap | O(log n + k) |
| `startsWith` | TreeMap subMap | O(log n + k) |
| `endsWith` | Reversed TreeMap subMap | O(log n + k) |
| `contains` | Key scan | O(keys) |

## Error Handling

All exceptions are unchecked (extend `RuntimeException`).

### IndexNotFoundException

Thrown when the index name does not exist in the catalog.

```
Index 'no-existe' not found in catalog 'events'
```

### UnsupportedIndexOperationException

Thrown in two cases:

1. Range/pattern operation on a non-sorted index:
   ```
   Index 'by-venue' does not support range queries. Use indexSorted() to enable them
   ```

2. Text pattern operation on a non-String key:
   ```
   Index 'by-date' does not support text queries. Key type must be String
   ```

## Backwards Compatibility

- `catalog.search(indexName, key)` is unchanged and works as before
- `catalog.query(indexName).equalTo(key)` is the DSL equivalent
- `Andersoni.search(catalogName, indexName, key)` is unchanged
- Existing `IndexDefinition` is unchanged
- Existing `Snapshot.search()` is unchanged
- No breaking changes to any public API

## Testing

### SortedIndexDefinitionTest
- buildIndex constructs HashMap + TreeMap + reversed TreeMap
- keys that are not Comparable do not compile
- String keys generate reversed TreeMap, non-String keys do not
- null keys handled correctly
- returned collections are immutable

### SnapshotTest (extend)
- searchBetween returns correct range
- searchGreaterThan, searchLessThan, searchGreaterOrEqual, searchLessOrEqual
- startsWith uses subMap efficiently
- endsWith uses reversed TreeMap
- contains scans keys correctly
- range operation on non-sorted index throws exception
- text operation on non-String key throws exception
- non-existent index throws exception

### QueryStepTest
- equalTo delegates to HashMap (works on both index types)
- all range operations on sorted index
- all text operations on sorted index with String key
- each error case with corresponding exception

### CatalogTest (extend)
- indexSorted() in builder DSL
- query() returns functional QueryStep
- search() still works (backwards compatible)
- mix of index() and indexSorted() in same catalog

### CatalogConcurrencyTest (extend)
- readers using query().between() never see partial snapshot
