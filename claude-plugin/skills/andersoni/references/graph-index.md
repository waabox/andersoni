# Graph Indexes and Graph Queries (Andersoni 1.13.0)

All types are in package `org.waabox.andersoni` (artifact `io.github.waabox:andersoni-core`).

## Contents

1. [When to use](#1-when-to-use)
2. [Defining a graph index](#2-defining-a-graph-index)
3. [Build-time validation](#3-build-time-validation)
4. [Key generation](#4-key-generation)
5. [Querying](#5-querying)
6. [Query planner rules](#6-query-planner-rules)
7. [Design recipe](#7-design-recipe)
8. [Worked example](#8-worked-example)
9. [Common mistakes](#9-common-mistakes)

## 1. When to use

| Situation | Use |
|---|---|
| Key crosses a relationship (`Event -> teams[] -> code`), multi-field drill-down (country -> category -> subcategory) | `indexGraph` |
| Single-field equality lookup | `index(...)`: faster |
| Item has N keys you can compute yourself | `indexMulti(...)`: faster |
| Range / prefix / contains queries | `indexSorted(...)` + `compound()`. Graph queries only support `eq` and `in` |

README benchmark (10,000 publications, 10 countries, 5x10 categories, about 2 events each). These are the published numbers, not guarantees:

| Metric | Graph index | indexMulti (manual keys) |
|---|---|---|
| Indexing time | ~23 ms | ~7 ms |
| Keys | 560 unique, ~57K entries | - |
| Memory | ~564 KB | - |
| Query: country only | ~729 ns | ~34 ns |
| Query: country + top category | ~605 ns | ~146 ns |
| Query: country + full path | ~303 ns | - |

Planner overhead is about 500-700 ns per query (hotpath selection plus `CompositeKey` construction). The HashMap lookup itself costs the same for both. The trade-off: you get a clean domain model (the entity does not build keys) in exchange for slower (still sub-microsecond) queries and more memory.

## 2. Defining a graph index

Entry point: `Catalog.BuildStep<T>.indexGraph(String indexName)` returns `Catalog.GraphIndexStep<T>`.

| Method on `GraphIndexStep<T>` | Signature | Produces per item |
|---|---|---|
| `traverse` | `traverse(String traversalName, Function<T, ?> extractor)` | 0 or 1 value. `null` gives 0 |
| `traverseMany` | `<C> traverseMany(String traversalName, Function<T, ? extends Collection<C>> collectionAccessor, Function<C, ?> valueExtractor)` | N distinct values. A null or empty collection, null elements and null extracted values are skipped |
| `traversePath` | `traversePath(String traversalName, String separator, Function<T, String> extractor)` | every prefix: `"a/b/c"` gives `"a"`, `"a/b"`, `"a/b/c"`. A null or `""` path gives 0 values. Empty segments are dropped, so `"/a//b/"` gives `"a"`, `"a/b"` |
| `hotpath` | `hotpath(String... fieldNames)` | declares one ordered combination of traversal names. Call it again to add more |
| `maxKeysPerItem` | `maxKeysPerItem(int max)` | per-item key cap. **Default 100** |
| `done` | `done()` returns `Catalog.BuildStep<T>` | validates and registers the index |

Values are compared with `equals`/`hashCode`, so use `String`, `Long`, enums or records. Query values must be equal to the extracted values: `eq(1)` (an Integer) does not match `1L` (a Long).

```java
final Catalog<Event> catalog = Catalog.of(Event.class)
    .named("events")
    .loadWith(repository::findAll)
    .indexGraph("event-graph")
        .traverse("country", Event::country)
        .traversePath("competition", "/", Event::competitionPath)
        .traverse("status", Event::status)
        .hotpath("country", "competition", "status")
        .maxKeysPerItem(200)
        .done()
    .build();
```

A standalone definition also exists: `GraphIndexDefinition.<T>named(name)`, with the same builder methods, and it ends with `build()` instead of `done()`. `catalog.graphQuery()` only uses graph indexes registered through `indexGraph`.

## 3. Build-time validation

| Rule | Exception | When |
|---|---|---|
| Index name null / empty | `NullPointerException` / `IllegalArgumentException` | `indexGraph(...)` |
| Index name already used by **any** index in the catalog (regular, sorted, multi, graph) | `IllegalArgumentException("Duplicate index name: 'x'")` | `indexGraph(...)` |
| Traversal name null / empty, extractor null | `NullPointerException` / `IllegalArgumentException` | `traverse*` |
| Duplicate traversal name in the same graph index | `IllegalArgumentException("Duplicate traversal name: 'x'")` | `traverse*` |
| Path separator null / empty | `NullPointerException` / `IllegalArgumentException` | `traversePath` |
| `hotpath()` with no args, a null/empty field, or the same field twice | `IllegalArgumentException` (the null case: `NullPointerException`) | `hotpath` |
| `maxKeysPerItem <= 0` | `IllegalArgumentException` | `maxKeysPerItem` |
| No traversal / no hotpath | `IllegalStateException` | `done()` |
| Hotpath names an unknown traversal | `IllegalArgumentException("Hotpath references unknown traversal: 'x'")` | `done()` |

Traversals do not have to be used by any hotpath. A catalog with only a graph index is valid.

## 4. Key generation

For each item and each hotpath `(f1..fn)`, the index adds the **cartesian product for every prefix length** k = 1..n as a key of the values `(v1..vk)` **qualified by the field names `(f1..fk)`**. Keys from all hotpaths of one index go into one map and are de-duplicated per item: two hotpaths sharing a leading prefix (e.g. `(country, category)` and `(country, slug)`) share the `(country)` keys, while equal values on different fields (`country=AR` vs `slug=AR`) are different keys.

```
country={"AR"}, competition="football/liga-pro" -> {"football","football/liga-pro"}, status={"LIVE"}
hotpath("country","competition","status") produces 5 keys:
  (AR)  (AR,football)  (AR,football/liga-pro)  (AR,football,LIVE)  (AR,football/liga-pro,LIVE)
```

- **Empty traversal** (null, empty collection, null/empty path): key generation for that hotpath stops at that field. Prefix keys for the earlier fields are still generated. If the **first** field is empty, the hotpath adds nothing for that item.
- **Keys per item** = |union over hotpaths of sum over k of product(|v1|..|vk|)|. Example: country 1, competition path depth 2, teams 2 gives 1 + 2 + 4 = 7 keys.
- **Limit**: if the count is greater than `maxKeysPerItem` (default 100), the build throws `IndexKeyLimitExceededException` (extends `AndersoniException`, which extends `RuntimeException`). The message is `Graph index 'g' generated 7 keys for item [<toString>], exceeding limit of 4. ...`. The build fails fast: `catalog.bootstrap()` throws it. Under `Andersoni.start()` the retry policy runs, then the catalog is marked FAILED and queries throw `CatalogNotAvailableException`.
- Graph keys are internal. Query a graph index only through `graphQuery()`. A lookup with a public `CompositeKey.of(...)` / `ofList(...)` key (`search("event-graph", key)`, `compound().where("event-graph").equalTo(key)`) finds nothing since 1.12.2, because those keys carry no field names; up to 1.12.1 it matched, ambiguously across fields.

## 5. Querying

| Entry point | Returns |
|---|---|
| `catalog.graphQuery()` | `GraphQueryBuilder<T>` |
| `andersoni.graphQuery(String catalogName)` | `GraphQueryBuilder<?>` |
| `andersoni.graphQuery(String catalogName, Class<T> type)` | `GraphQueryBuilder<T>` (unchecked cast, the type is not verified) |

The Andersoni variants throw `IllegalArgumentException` for an unknown catalog and `CatalogNotAvailableException` if the catalog failed to bootstrap. A builder is **bound to the snapshot current at creation**, is not thread-safe, and serves one query only.

| Method | Signature | Notes |
|---|---|---|
| `where` / `and` | `GraphQueryConditionStep<T> where(String fieldName)` / `and(String fieldName)` | Identical behaviour. The field name is a **traversal name**, not an index name |
| `eq` | `GraphQueryBuilder<T> eq(Object value)` | `EQUAL_TO`. A null value throws `NullPointerException` |
| `in` | `in(Collection<?> values)`, `in(Object... values)` | `IN_LIST`, OR semantics. Empty throws `IllegalArgumentException`. A null element throws `NullPointerException` |
| `execute` | `List<T> execute()` | unmodifiable |
| `execute` | `<V> List<V> execute(Class<V> viewType)` | view registered with `.view(V.class, mapper)`. An unregistered view throws `IllegalArgumentException`, but only if something matched |

There are **no other operators**: no range, `not`, `or` across fields, or startsWith. For a prefix on a path, pass the prefix value itself (`eq("football")`). The condition model is `GraphQueryCondition(String fieldName, Operation operation, Object[] args)` with `enum Operation { EQUAL_TO, IN_LIST }`.

- Declaring the same field twice keeps the **last** condition.
- `in` on several covered fields: the planner builds one key per combination (cartesian product), looks each one up, and de-duplicates by identity. Result order is not guaranteed. Sort the results yourself.

## 6. Query planner rules

`QueryPlanner.plan(List<GraphIndexDefinition<T>>, Map<String, GraphQueryCondition>)` returns `Plan<T>(String graphIndexName, List<CompositeKey> keys, List<GraphQueryCondition> postFilterConditions)` or `null`.

1. For every hotpath of every graph index in the catalog, **coverage** = the number of leading hotpath fields that have a condition. Counting stops at the first field without one.
2. The highest coverage wins. On a tie, the **first declared** (index order, then hotpath order) wins.
3. Keys are built from the covered fields, in hotpath order.
4. Conditions not in the covered prefix become `postFilterConditions`. **Post-filtering is not implemented.**

| Outcome | `execute()` result |
|---|---|
| No conditions | empty list |
| No hotpath's **first** field has a condition (`plan == null`) | **empty list, silently.** No exception |
| Best hotpath covers every condition | results |
| Best hotpath leaves any condition uncovered | `UnsupportedOperationException("Graph query has conditions not covered by any hotpath: [f]. ...")` |

Consequences:

- **The order of `where`/`and` calls does not matter.** Only the hotpath field order matters.
- **You cannot skip a middle field.** With `hotpath("country","competition","status")`, the query `country + status` gets coverage 1, `status` is left uncovered, and the query throws.
- **Only a hotpath's leading field can start a query.** `status` alone matches no leading field, so the query returns empty.
- The query must be answerable by **one** hotpath. The planner never combines two hotpaths or two indexes.

## 7. Design recipe

1. List every query as a set of equality/IN fields: Q1 `{country}`, Q2 `{country, competition, status}`, Q3 `{team, status}`, Q4 `{status}`.
2. Define one traversal per field.
3. Cover each query with a hotpath whose **first k fields are exactly that query's field set**, in any order. Merge queries that are prefixes of each other into one hotpath, most selective or always-present field first: Q1 and Q2 give `("country","competition","status")`.
4. Queries that cannot share a prefix get their own hotpath: Q3 `("team","status")`, Q4 `("status")`.
5. Several hotpaths can share one graph index: keys are qualified by field, so equal values on different fields never mix (since 1.12.2; see mistake 1 for older versions).
6. Put high-fan-out traversals (`traverseMany`, deep paths) **late** in a hotpath. Their product multiplies every later field.
7. Estimate keys per item with the formula in section 4. Set `maxKeysPerItem` above the worst real item, with headroom.
8. Rough memory cost: every unique key adds a map entry, plus one reference per item per key. Deep paths multiplied by fan-out grow fast. Check with `catalog.info()` / index sizes (CompositeKey sizes are estimated per component).
9. Fields that need ranges (dates, prices): run the `graphQuery()` for the equality fields and filter the (usually small) result in Java, or query the range on an `indexSorted` and intersect the two lists by identity.

## 8. Worked example

Verified against 1.13.0.

```java
import java.util.List;
import java.util.function.Function;
import org.waabox.andersoni.Catalog;

public final class EventsCatalog {

  public record Team(String code) {}
  public record Event(String id, String country, String competitionPath, List<Team> teams, String status) {}
  public record EventCard(String id, String status) {}

  public static Catalog<Event> create(final List<Event> events) {
    return Catalog.of(Event.class)
        .named("events")
        .data(events)
        .index("by-status").by(Event::status, Function.identity())
        .view(EventCard.class, e -> new EventCard(e.id(), e.status()))
        .indexGraph("event-graph")
            .traverse("country", Event::country)
            .traversePath("competition", "/", Event::competitionPath)
            .traverse("status", Event::status)
            .hotpath("country", "competition", "status")
            .hotpath("country", "status")
            .done()
        .indexGraph("team-graph")
            .traverseMany("team", Event::teams, Team::code)
            .traverse("status", Event::status)
            .hotpath("team", "status")
            .done()
        .build();
  }

  public static void queries(final Catalog<Event> catalog) {
    // Prefix 1 of event-graph
    final List<Event> ar = catalog.graphQuery().where("country").eq("AR").execute();
    // Full hotpath: path prefix + IN (2 keys looked up, de-duplicated)
    final List<Event> arFootball = catalog.graphQuery()
        .where("country").eq("AR")
        .and("competition").eq("football")
        .and("status").in("LIVE", "SCHEDULED")
        .execute();
    // team-graph chosen; condition order is irrelevant; projected to a view
    final List<EventCard> bocaLive = catalog.graphQuery()
        .where("status").eq("LIVE")
        .and("team").eq("BOC")
        .execute(EventCard.class);
    // country + status: served by the second hotpath of event-graph
    final List<Event> arLive = catalog.graphQuery()
        .where("country").eq("AR")
        .and("status").eq("LIVE")
        .execute();
  }
}
```

With Andersoni: `andersoni.graphQuery("events", Event.class).where("country").eq("AR").execute();`

Without the `("country", "status")` hotpath, `where("country").eq("AR").and("status").eq("LIVE")` would throw `UnsupportedOperationException` (it skips `competition`). `where("status").eq("LIVE")` alone returns `[]`, because no hotpath of either index starts with `status`.

## 9. Common mistakes

1. **On 1.12.1 or earlier: key collisions between hotpaths of one index.** Keys held only values, so `hotpath("country")` + `hotpath("team")`, or `("country","category")` + `("country","slug")`, mixed items when values were equal across fields. Fixed in 1.12.2. On older versions, put such hotpaths in separate `indexGraph`s.
2. Expecting a non-leading field to work alone. It returns an **empty list**, not an error. Add a hotpath that starts with that field.
3. Skipping a middle hotpath field. The query throws `UnsupportedOperationException`. Reorder the fields or add a hotpath that covers exactly those fields.
4. Expecting post-filtering of uncovered fields. It is not implemented.
5. Querying a path with a partial segment (`eq("foot")`). Only whole-segment prefixes are keys.
6. Type mismatches (`Integer` vs `Long`, enum vs `String`). The lookup silently finds nothing.
7. An item whose first hotpath field is null is invisible to that hotpath. It is not an error.
8. A fan-out that is too large in a long hotpath, which throws `IndexKeyLimitExceededException` and fails bootstrap or refresh for the **whole catalog**. Size `maxKeysPerItem` from data.
9. Using the index name in `where(...)`. `where` takes **traversal names**. Index names are used only with `search` / `compound`.
10. Reusing a `GraphQueryBuilder` across refreshes. It keeps reading the old snapshot, so call `graphQuery()` again for each query.
11. Relying on result order. It is not guaranteed.
12. Naming a graph index the same as another index in the catalog. The build throws `IllegalArgumentException`.
