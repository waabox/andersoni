# Compound Query & Multi-Key Index Design

## Date: 2026-03-06

## Context

Andersoni's current query API operates on a single index at a time. Real-world use cases
require filtering by multiple criteria simultaneously (e.g., country + category + organizer +
date range). Additionally, hierarchical data structures like composite categories (tree) need
to be searchable at any level of the hierarchy.

## Requirements

- Compound queries that intersect results from multiple indices
- Support all existing operations per condition: equalTo, between, greaterThan, greaterOrEqual,
  lessThan, lessOrEqual, startsWith, endsWith, contains
- Short-circuit evaluation: stop early when any condition yields empty results
- Multi-key indices: index a single item under multiple keys (for tree/ancestor lookups)
- Backwards compatible: no changes to existing API surface
- Available from both `Andersoni` and `Catalog` entry points

## Design

### New Classes

#### CompoundQuery<T>

Accumulates conditions and executes sequential intersection with short-circuit.

```java
public final class CompoundQuery<T> {

  private final Snapshot<T> snapshot;
  private final String catalogName;
  private final List<Condition> conditions;
  private boolean whereAdded;

  // Package-private, created by Catalog
  CompoundQuery(Snapshot<T> snapshot, String catalogName);

  // First condition (mandatory, exactly once)
  public CompoundConditionStep<T> where(String indexName);

  // Additional conditions (zero or more)
  public CompoundConditionStep<T> and(String indexName);

  // Executes all conditions with short-circuit intersection
  public List<T> execute();

  // Package-private: called by CompoundConditionStep to register conditions
  CompoundQuery<T> addCondition(Condition condition);
}
```

#### CompoundConditionStep<T>

Fluent step exposing all query operations. Each method registers a Condition and returns
the CompoundQuery for further chaining.

```java
public final class CompoundConditionStep<T> {

  private final CompoundQuery<T> query;
  private final String indexName;

  public CompoundQuery<T> equalTo(Object key);
  public CompoundQuery<T> between(Comparable<?> from, Comparable<?> to);
  public CompoundQuery<T> greaterThan(Comparable<?> key);
  public CompoundQuery<T> greaterOrEqual(Comparable<?> key);
  public CompoundQuery<T> lessThan(Comparable<?> key);
  public CompoundQuery<T> lessOrEqual(Comparable<?> key);
  public CompoundQuery<T> startsWith(String prefix);
  public CompoundQuery<T> endsWith(String suffix);
  public CompoundQuery<T> contains(String substring);
}
```

#### Condition (package-private)

```java
record Condition(String indexName, Operation operation, Object[] args) {

  enum Operation {
    EQUAL_TO, BETWEEN, GREATER_THAN, GREATER_OR_EQUAL,
    LESS_THAN, LESS_OR_EQUAL, STARTS_WITH, ENDS_WITH, CONTAINS
  }
}
```

#### MultiKeyIndexDefinition<T>

Indexes a single item under multiple keys. Produces the same `Map<Object, List<T>>` as
`IndexDefinition`, so `Snapshot` requires no changes.

```java
public final class MultiKeyIndexDefinition<T> {

  private final String name;
  private final Function<T, List<?>> keysExtractor;

  public Map<Object, List<T>> buildIndex(List<T> data) {
    Map<Object, List<T>> index = new HashMap<>();
    for (T item : data) {
      List<?> keys = keysExtractor.apply(item);
      for (Object key : keys) {
        index.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
      }
    }
    // Make each list unmodifiable
    // Return unmodifiable map
  }
}
```

### Modified Classes

#### Catalog<T>

- Add `compound()` method returning `CompoundQuery<T>`
- Add `indexMulti(name)` in builder returning `MultiKeyIndexStep<T>`

```java
// In Catalog
public CompoundQuery<T> compound() {
  return new CompoundQuery<>(current.get(), name);
}

// In Catalog.BuildStep
public MultiKeyIndexStep<T> indexMulti(String indexName) { ... }
```

#### Andersoni

- Add `compound(catalogName)` returning `CompoundQuery<?>`
- Add `compound(catalogName, type)` returning `CompoundQuery<T>`

```java
public CompoundQuery<?> compound(String catalogName) {
  Catalog<?> catalog = requireCatalog(catalogName);
  if (failedCatalogs.contains(catalogName)) {
    throw new CatalogNotAvailableException(catalogName);
  }
  return catalog.compound();
}

@SuppressWarnings("unchecked")
public <T> CompoundQuery<T> compound(String catalogName, Class<T> type) {
  return (CompoundQuery<T>) compound(catalogName);
}
```

### Intersection Algorithm

```
execute():
  1. Evaluate first condition → result
  2. If empty → return emptyList (short-circuit)
  3. For each subsequent condition:
     a. Evaluate condition → candidates
     b. If empty → return emptyList (short-circuit)
     c. Build IdentityHashSet from the SMALLER list
     d. Filter the LARGER list, retaining only items in the set
     e. If intersection empty → return emptyList (short-circuit)
  4. Return unmodifiable result
```

- **Identity-based intersection**: uses `IdentityHashMap` because all items are the same
  object instances from the snapshot.
- **Smallest-set optimization**: always builds the set from the smaller list for O(min(n,m)).
- **Lock-free**: operates on an immutable snapshot captured at CompoundQuery creation time.

### Error Handling

| Case | Behavior |
|---|---|
| `execute()` without `where()` | `IllegalStateException` |
| `where()` called twice | `IllegalStateException` |
| Index does not exist | `IndexNotFoundException` (existing) |
| Sorted operation on hash index | `UnsupportedIndexOperationException` (existing) |
| Catalog not registered | `IllegalArgumentException` (existing) |
| Catalog failed bootstrap | `CatalogNotAvailableException` (existing) |

No new exceptions. Null checks on all public method parameters.

### Usage Example

```java
// Catalog definition with multi-key index for category tree
Catalog<Publication> catalog = Catalog.of(Publication.class)
    .named("publications")
    .loadWith(() -> repo.findPublished())
    .index("by-country").by(Publication::country, Function.identity())
    .indexMulti("by-category").by(
        pub -> pub.category().ancestorIds(), Function.identity())
    .index("by-organizer").by(Publication::organizerId, Function.identity())
    .indexSorted("by-publish-date").by(
        Publication::publishDate, Function.identity())
    .build();

// Compound query: country + category + organizer + date range
List<Publication> results = andersoni.compound("publications", Publication.class)
    .where("by-country").equalTo("AR")
    .and("by-category").equalTo("cat-001")
    .and("by-organizer").equalTo("ORG-001")
    .and("by-publish-date").between(from, to)
    .execute();
```

## File Structure (new/modified)

```
andersoni-core/src/main/java/org/waabox/andersoni/
  CompoundQuery.java              NEW
  CompoundConditionStep.java      NEW
  Condition.java                  NEW (package-private)
  MultiKeyIndexDefinition.java    NEW
  Catalog.java                    MODIFIED (+compound, +indexMulti in builder)
  Andersoni.java                  MODIFIED (+compound)
  Snapshot.java                   UNCHANGED
  QueryStep.java                  UNCHANGED
  IndexDefinition.java            UNCHANGED
  SortedIndexDefinition.java      UNCHANGED
```

## Rejected Alternatives

- **Compound keys** (e.g., "AR:cat-001:ORG-001"): combinatorial explosion with 4+ dimensions
- **Eager evaluation in chain**: no ability to reorder conditions for optimization
- **Materialized path for trees**: requires knowing full path, O(log n) startsWith vs O(1) equalTo
- **OrderBy in compound query**: YAGNI, caller can sort the small result list
- **OR/union queries**: over-engineering for BFF cache use case
