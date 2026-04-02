# Andersoni

<p align="left">
  <!-- Build -->
  <a href="https://github.com/waabox/andersoni/actions">
    <img src="https://github.com/waabox/andersoni/actions/workflows/ci.yml/badge.svg" alt="Build Status" />
  </a>

  <!-- Java -->
  <img src="https://img.shields.io/badge/Java-21-orange?logo=java" alt="Java 21" />

  <!-- Spring Boot -->
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen?logo=springboot" alt="Spring Boot" />

  <!-- Maven Central -->
  <a href="https://central.sonatype.com/search?q=io.github.waabox.andersoni">
    <img src="https://img.shields.io/maven-central/v/io.github.waabox/andersoni-core?color=blue" alt="Maven Central" />
  </a>

  <!-- Kafka -->
  <img src="https://img.shields.io/badge/Kafka-sync-black?logo=apachekafka" alt="Kafka Sync" />

  <!-- Release -->
  <a href="https://github.com/waabox/andersoni/releases">
    <img src="https://img.shields.io/github/v/release/waabox/andersoni?color=blue" alt="Latest Release" />
  </a>

  <!-- License -->
  <img src="https://img.shields.io/badge/License-Apache%202.0-yellow" alt="Apache 2.0 License" />

  <!-- Wiki -->
  <a href="https://github.com/waabox/andersoni/wiki">
    <img src="https://img.shields.io/badge/Wiki-Documentation-8A2BE2?logo=bookstack&logoColor=white" alt="Wiki" />
  </a>
</p>

<p align="center">
  <img src="./assets/logo-tr.png" width="300"/>
</p>

**Nanosecond-latency, lock-free reads.** In-memory indexed cache for Java 21 with a fluent DSL — regular, sorted, multi-key, and graph-based indexes, compound queries, pre-computed views (projections), build hooks, immutable snapshots, pluggable cross-node sync, and built-in observability.

> **[Read the full documentation on the Wiki](https://github.com/waabox/andersoni/wiki)** — getting started, core concepts, catalog DSL, Spring Boot integration, sync strategies, snapshot persistence, leader election, observability, DevOps & Kubernetes, deployment guide, and FAQ.

## Performance

Benchmarked on Apple M-series, JDK 21, 8 threads. Catalog with 3 regular indices + 3 sorted indices (score, category, date):

| Items | Build Time | Avg Search Latency | Concurrent Throughput | Memory |
|---|---|---|---|---|
| 10,000 | 37 ms | ~65 ns | ~16M ops/s | ~13 MB |
| 100,000 | 120 ms | ~34 ns | ~67M ops/s | ~25 MB |
| 500,000 | 492 ms | ~32 ns | ~176M ops/s | ~14 MB |

Search latency is a `HashMap.get()` on an immutable snapshot — no locks, no synchronization, no copying. Throughput scales linearly with cores because readers never contend with each other.

Build time is the cost of a full refresh: iterate all items, extract keys, populate index maps (HashMap + TreeMap for sorted indexes), wrap in unmodifiable collections, and atomic swap. This happens in a background thread; readers are never blocked during a rebuild.

**Index build strategy:** since v1.9.0, the snapshot build uses a single loop over the data. For each item: views are computed, the item is indexed across all indices, and build hooks execute — all in one pass. The dominant cost is HashMap/TreeMap insertions (especially TreeMap at O(log n) per entry), not the iteration itself.

### Query DSL

Sorted indexes (`indexSorted()`) enable range queries and text pattern matching via a fluent Query DSL. Average latency per query (100K iterations):

| Operation | Structure | 10,000 | 100,000 | 500,000 |
|---|---|---|---|---|
| `equalTo` | HashMap | ~90 ns | ~55 ns | ~28 ns |
| `between` | TreeMap subMap | ~1.4 us | ~3.5 us | ~10.5 us |
| `greaterThan` | TreeMap tailMap | ~0.7 us | ~2.0 us | ~8.4 us |
| `greaterOrEqual` | TreeMap tailMap | ~0.8 us | ~2.2 us | ~9.1 us |
| `lessThan` | TreeMap headMap | ~0.7 us | ~1.7 us | ~9.3 us |
| `lessOrEqual` | TreeMap headMap | ~0.8 us | ~1.9 us | ~9.5 us |
| `startsWith` | TreeMap subMap | ~1.3 us | ~9.6 us | ~43 us |
| `endsWith` | reversed TreeMap | ~0.3 us | ~0.5 us | ~2.2 us |
| `contains` | key scan | ~2.1 us | ~9.9 us | ~44 us |

### Date Queries (ISO String index)

Dates indexed as ISO strings (`"2024-01-15"`) enable date range and pattern queries. Lexicographic ordering matches chronological ordering, so all sorted index operations work naturally:

| Operation | Example | 10,000 | 100,000 | 500,000 |
|---|---|---|---|---|
| `between` | `"2024-01-01"` to `"2024-03-31"` | ~3.5 us | ~7.2 us | ~19 us |
| `startsWith` | `"2024-06"` (all June 2024) | ~1.3 us | ~2.7 us | ~7.2 us |
| `contains` | `"-15"` (all 15th of month) | ~30 us | ~38 us | ~59 us |

Range operations (`between`, `greaterThan`, etc.) are O(log n + k) where k is the number of matching entries. Text operations (`startsWith`, `endsWith`) use TreeMap prefix scans; `contains` performs a full key scan O(keys). Latency scales with result set size — queries returning fewer items are proportionally faster.

### Graph Index

Graph indexes (`indexGraph()`) navigate entity relationships and pre-compute composite keys via hotpaths. Benchmarked with 10,000 publications, 10 countries, 5 categories x 10 subcategories, ~2 events per publication:

| Metric | Graph Index | indexMulti (manual keys) |
|---|---|---|
| Indexation time | ~23 ms | ~7 ms |
| Keys generated | 560 unique, ~57K entries | - |
| Memory | ~564 KB | - |

| Query | Graph Index | indexMulti |
|---|---|---|
| Country only | ~729 ns | ~34 ns |
| Country + top category | ~605 ns | ~146 ns |
| Country + full path | ~303 ns | - |

Graph indexes trade raw query speed for a **clean domain model**: the entity no longer generates index keys manually. All queries remain sub-microsecond. The query planner overhead is ~500-700ns per query (hotpath selection + CompositeKey construction). The actual HashMap lookup is identical in both cases.

**When to use graph indexes:** when your index keys cross entity relationships (e.g., `Publication → Event → Country`) and you want to keep indexing logic out of the domain model. For simple single-field indexes, regular `index()` or `indexMulti()` is faster.

## Graph Index & Query Planner

Graph indexes define **traversals** over entity relationships and **hotpaths** that declare which traversal combinations to pre-compute as composite keys at indexing time. All lookups are O(1) via HashMap.

### Defining a Graph Index

```java
Catalog<Publication> catalog = Catalog.of(Publication.class)
    .named("publications")
    .loadWith(() -> repository.findAll())
    .indexGraph("by-country-category")
        // Fan-out traversal: Publication -> events[] -> country code
        .traverseMany("country", Publication::events,
            event -> event.country().code())
        // Path traversal: splits "deportes/futbol/liga" into prefix keys
        .traversePath("category", "/", Publication::categoryPath)
        // Pre-compute keys for (country, category) combinations
        .hotpath("country", "category")
        .done()
    .build();
```

### Traversal Types

| Type | DSL | Produces | Example |
|---|---|---|---|
| **Simple** | `.traverse("name", T::value)` | 1 value | `Publication::slug` → `"my-event"` |
| **Fan-out** | `.traverseMany("name", T::collection, C::value)` | N distinct values | `Publication::events` → `["AR", "MX"]` |
| **Path** | `.traversePath("name", "/", T::path)` | N prefix keys | `"deportes/futbol"` → `["deportes", "deportes/futbol"]` |

### Hotpaths & Key Generation

A hotpath declares which traversals to combine. The engine generates the **cartesian product** of traversal values, plus **prefix keys** at every depth level:

```
Publication with country=["AR","MX"] and categoryPath="deportes/futbol"

Hotpath ("country", "category") generates:
  CompositeKey("AR")                        ← prefix (country only)
  CompositeKey("MX")                        ← prefix (country only)
  CompositeKey("AR", "deportes")            ← country + top category
  CompositeKey("AR", "deportes/futbol")     ← country + full path
  CompositeKey("MX", "deportes")            ← country + top category
  CompositeKey("MX", "deportes/futbol")     ← country + full path
```

This enables progressive drill-down queries: by country only, by country + top category, by country + full subcategory path — all O(1).

### Querying with the Query Planner

The `graphQuery()` DSL accepts conditions by traversal field name. The **query planner** automatically selects the best hotpath based on longest usable prefix:

```java
// From Andersoni (typed) — since 1.5.1
andersoni.graphQuery("publications", Publication.class)
    .where("country").eq("AR")
    .execute();

// Country + category drill-down
andersoni.graphQuery("publications", Publication.class)
    .where("country").eq("AR")
    .and("category").eq("deportes/futbol")
    .execute();

// From Catalog directly
catalog.graphQuery()
    .where("country").eq("AR")
    .and("category").eq("deportes/futbol")
    .execute();
```

The planner evaluates all hotpaths across all graph indexes, picks the one with the highest field coverage, and performs a single HashMap lookup. If conditions reference fields not covered by any hotpath, the query throws `UnsupportedOperationException` — this forces explicit index design rather than silently degrading to a full scan.

### Safety Limits

To prevent combinatorial explosion when fan-out traversals produce many values, set `maxKeysPerItem`:

```java
.indexGraph("by-country-category")
    .traverseMany("country", Publication::events, e -> e.country().code())
    .traversePath("category", "/", Publication::categoryPath)
    .hotpath("country", "category")
    .maxKeysPerItem(50)  // default: 100
    .done()
```

If any item generates more keys than the limit during indexation, `IndexKeyLimitExceededException` is thrown immediately (fail-fast).

### Mixing Index Types

Graph indexes coexist with all other index types. Use the right tool for each access pattern:

```java
Catalog.of(Publication.class)
    .named("publications")
    .loadWith(loader)
    // Regular index for simple lookups
    .index("by-venue").by(Publication::venue, Venue::name)
    // Multi-key index for ancestry
    .indexMulti("by-organizer").by(Publication::organizerIds)
    // Graph index for cross-entity composite queries
    .indexGraph("publications-graph")
        .traverseMany("country", Publication::events,
            event -> event.country().code())
        .traversePath("category", "/", Publication::categoryPath)
        .traverse("slug", Publication::slug)
        .hotpath("country", "category")
        .hotpath("country", "slug")
        .done()
    .build();

// Each index type has its own query API
andersoni.search("publications", "by-venue", "Wembley");   // regular
andersoni.search("publications", "by-organizer", orgId);    // multi-key
andersoni.graphQuery("publications", Publication.class)      // graph
    .where("country").eq("AR")
    .and("category").eq("deportes")
    .execute();
```

## Catalog Views (Projections)

Views let you define pre-computed projections of your domain objects. Instead of always getting the full object `T` from a query, you can request a smaller typed view `V` — without any runtime transformation.

Views are materialized at snapshot build time and stored alongside each item. Querying with a view class is zero-overhead: the projection is already computed.

### Defining Views

```java
record EventSummary(String id, String name) {}
record EventCard(String id, String name, String imageUrl) {}

Catalog<Event> catalog = Catalog.of(Event.class)
    .named("events")
    .loadWith(() -> repository.findAll())
    .index("by-venue").by(Event::venue, Venue::name)
    .indexSorted("by-date").by(Event::eventDate, EventDate::value)
    .view(EventSummary.class, e -> new EventSummary(e.id(), e.name()))
    .view(EventCard.class, e -> new EventCard(e.id(), e.name(), e.imageUrl()))
    .build();
```

### Querying with Views

Pass the view class as the last parameter to any query operation:

```java
// Direct search
List<EventSummary> summaries = andersoni.search("events", "by-venue", "Maracana", EventSummary.class);

// Range queries
List<EventSummary> upcoming = andersoni.query("events", "by-date")
    .between(startDate, endDate, EventSummary.class);

// Text pattern queries
List<EventSummary> matching = andersoni.query("events", "by-name")
    .startsWith("Champions", EventSummary.class);

// Compound queries
List<EventCard> cards = andersoni.compound("events")
    .where("by-venue").equalTo("Maracana")
    .and("by-sport").equalTo("Football")
    .execute(EventCard.class);

// Graph queries
List<EventSummary> results = andersoni.graphQuery("events", Event.class)
    .where("country").eq("AR")
    .execute(EventSummary.class);
```

Without a view class, all queries return the full object `T` as before — fully backward compatible.

## Build Hooks

> **Since 1.9.0**

Register per-item hooks that execute during snapshot build — for metrics, validation, logging, or any custom side-effect. Hooks are ordered by priority (lower executes first, default 100).

```java
Catalog<Event> catalog = Catalog.of(Event.class)
    .named("events")
    .loadWith(() -> repository.findAll())
    .index("by-venue").by(Event::venue, Venue::name)
    .hook(item -> { metrics.count("indexed"); return item; }, 10)
    .hook(item -> { validate(item); return item; }, 20)
    .hook(item -> { log.debug("Built: {}", item); return item; })  // default priority 100
    .build();
```

Hooks execute after indexation and view computation for each item. The `SnapshotBuildHook<T>` interface is a `@FunctionalInterface`:

```java
@FunctionalInterface
public interface SnapshotBuildHook<T> {
    T process(T item);
}
```

## How It Compares

Andersoni is **not a general-purpose cache**. It solves a specific problem: multi-index search over domain datasets with consistent, lock-free reads.

### vs Redis / Hazelcast / Infinispan

These provide distributed shared state. Andersoni provides **local read-only views** with optional sync. The difference matters: Redis gives you a single mutable store that all nodes read/write over the network. Andersoni gives each node its own immutable snapshot — reads are a pointer dereference (~30ns), not a network call (~0.5-1ms). Within a single node, Andersoni is **fully consistent**: all indices reflect the same snapshot at all times. Cross-node consistency depends on the sync strategy configured (Kafka ~ms, HTTP ~ms, DB polling ~seconds).

Note: Hazelcast and Infinispan support embedded mode with near-cache, which reduces read latency significantly. If you already run one of these, adding Andersoni may not be justified.

| | Redis / Hazelcast | Andersoni |
|---|---|---|
| Deployment | External or embedded | In-process JAR |
| Read latency | ~0.5-1ms (remote) | ~30ns (local) |
| Write model | Mutable shared state | Immutable snapshots |
| Consistency | Strong (single source) | Snapshot-consistent per node; cross-node depends on sync strategy |
| Infrastructure | Servers to manage | None |

**They win at**: mutable shared state, strong consistency, operational tooling, large-scale clusters.

**Andersoni wins at**: read latency, operational simplicity, zero infrastructure overhead.

### vs Spring Cache (@Cacheable)

Spring Cache is annotation-based: it caches method return values by key. Under the hood it delegates to Caffeine or Redis, inheriting their trade-offs. It has no concept of indexing a dataset or maintaining a consistent view across multiple search criteria. Andersoni is complementary — you can use Spring Cache for method-level caching and Andersoni for indexed domain data.

| | Spring Cache | Andersoni |
|---|---|---|
| Approach | Cache method results | Index domain data |
| Multi-index | No | Yes, N indices per catalog |
| Consistency | Per method/key | Per snapshot (all indices) |
| Provider | Wraps Caffeine/Redis | Standalone engine |

## When to Use It

Andersoni fits when you have **read-heavy reference data** queried by multiple criteria:

- Product catalogs, event listings, pricing tables, sports fixtures
- Configuration data that changes infrequently (minutes/hours, not seconds)
- Datasets that fit in memory (tens of thousands to low millions of items)
- Systems where cross-index consistency matters (no stale index A while index B is fresh)

## When NOT to Use It

- **You need per-entry TTL or size-bounded eviction** — use Caffeine. Andersoni refreshes entire snapshots; there is no per-item expiration.
- **Your data changes every few seconds** — full snapshot rebuilds on every change are wasteful. Andersoni is designed for data that refreshes on the order of minutes, not seconds.
- **You already have Redis/Hazelcast running and it works** — adding another caching layer introduces complexity. If your current setup meets latency requirements, keep it.
- **You need strong consistency across nodes** — within each node, Andersoni is fully snapshot-consistent. But cross-node sync depends on the strategy configured (Kafka is near-realtime, DB polling can be seconds). If your use case requires all nodes to see the exact same data at the exact same time, Andersoni is not the right fit.
- **Your dataset is very large (tens of millions+)** — snapshots live fully in heap. At 500K items with 3 indices, expect ~70MB. Scale accordingly and consider GC pressure from snapshot swaps on very large datasets.

## Quick Start

```xml
<dependency>
    <groupId>io.github.waabox</groupId>
    <artifactId>andersoni-core</artifactId>
    <version>1.9.0</version>
</dependency>
```

```java
// Domain
record Sport(String name) {}
record Venue(String name) {}
record Event(String id, Sport sport, Venue venue, LocalDate date) {}
record EventSummary(String id, String sportName) {}

// Define catalog with indexes, views, and hooks
Catalog<Event> catalog = Catalog.of(Event.class)
    .named("events")
    .loadWith(() -> eventRepository.findAll())
    .index("by-venue").by(Event::venue, Venue::name)
    .index("by-sport").by(Event::sport, Sport::name)
    .indexSorted("by-date").by(Event::date, Function.identity())
    .view(EventSummary.class, e -> new EventSummary(e.id(), e.sport().name()))
    .hook(item -> { log.debug("Indexed: {}", item.id()); return item; })
    .build();

Andersoni andersoni = Andersoni.builder().nodeId("node-1").build();
andersoni.register(catalog);
andersoni.start();

// Simple index lookup
List<Event> events = andersoni.search("events", "by-venue", "Wembley", Event.class);

// View projection — pre-computed, zero runtime transformation
List<EventSummary> summaries = andersoni.search("events", "by-venue", "Wembley", EventSummary.class);

// Range query on sorted index
List<Event> upcoming = andersoni.query("events", "by-date", Event.class)
    .greaterThan(LocalDate.now());

// Compound query — multi-index intersection
List<EventSummary> results = andersoni.compound("events")
    .where("by-sport").equalTo("Football")
    .and("by-date").greaterThan(LocalDate.now())
    .execute(EventSummary.class);
```

For graph indexes, Spring Boot, sync strategies, snapshot persistence, K8s deployment, and more — see the **[Wiki](https://github.com/waabox/andersoni/wiki)**.

## Modules

| Module | Purpose |
|---|---|
| `andersoni-core` | Engine, DSL, Snapshot, Graph Index, Query Planner, Views, Build Hooks (zero dependencies) |
| `andersoni-json-serializer` | Jackson-based snapshot serializer |
| `andersoni-sync-kafka` | Kafka broadcast sync |
| `andersoni-spring-sync-kafka` | Spring Kafka auto-configured sync |
| `andersoni-sync-http` | HTTP peer-to-peer sync |
| `andersoni-sync-db` | JDBC polling sync |
| `andersoni-leader-k8s` | K8s Lease leader election |
| `andersoni-snapshot-s3` | S3 snapshot persistence |
| `andersoni-snapshot-fs` | Filesystem snapshot (dev/test) |
| `andersoni-spring-boot-starter` | Spring Boot auto-configuration |
| `andersoni-metrics-datadog` | Datadog DogStatsD metrics |
| `andersoni-admin` | K8s admin console |

## Building

```bash
mvn clean test       # run all tests
mvn clean install    # install to local Maven repo
```

Requires Java 21.

## License

[Apache License 2.0](LICENSE)
