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

In-memory indexed cache library for Java 21. Define search indices over your domain objects with a fluent DSL, get lock-free reads via immutable snapshots, and sync across nodes with pluggable strategies.

> **[Read the full documentation on the Wiki](https://github.com/waabox/andersoni/wiki)** — getting started, core concepts, catalog DSL, Spring Boot integration, sync strategies, snapshot persistence, leader election, observability, DevOps & Kubernetes, deployment guide, and FAQ.

## Performance

Benchmarked on Apple M-series, JDK 21, 8 threads. Catalog with 3 regular indices + 3 sorted indices (score, category, date):

| Items | Build Time | Avg Search Latency | Concurrent Throughput | Memory |
|---|---|---|---|---|
| 10,000 | 35 ms | ~65 ns | ~12M ops/s | ~14 MB |
| 100,000 | 130 ms | ~33 ns | ~83M ops/s | ~25 MB |
| 500,000 | 490 ms | ~30 ns | ~130M ops/s | ~13 MB |

Search latency is a `HashMap.get()` on an immutable snapshot — no locks, no synchronization, no copying. Throughput scales linearly with cores because readers never contend with each other.

Build time is the cost of a full refresh: iterate all items, extract keys, populate index maps (HashMap + TreeMap for sorted indexes), wrap in unmodifiable collections, and atomic swap. This happens in a background thread; readers are never blocked during a rebuild.

**Index build strategy:** each index definition iterates the dataset independently — N indices means N passes over the data. This is a deliberate design choice: each `IndexDefinition` and `SortedIndexDefinition` encapsulates its own build logic, keeping the index abstraction self-contained. The dominant cost is not the iteration itself but the HashMap/TreeMap insertions (especially TreeMap at O(log n) per entry), which are identical regardless of pass count. After the first pass, the dataset is warm in L2/L3 CPU cache, so subsequent passes read from cache rather than main memory.

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

## How It Compares

Andersoni is **not a general-purpose cache**. It solves a specific problem: multi-index search over domain datasets with consistent, lock-free reads.

### vs Redis / Hazelcast / Infinispan

These provide distributed shared state. Andersoni provides **local read-only views** with optional sync. The difference matters: Redis gives you a single mutable store that all nodes read/write over the network. Andersoni gives each node its own immutable snapshot — reads are a pointer dereference (~30ns), not a network call (~0.5-1ms). The tradeoff is that Andersoni data is eventually consistent across nodes (sync delay depends on strategy: Kafka ~ms, HTTP ~ms, DB polling ~seconds).

Note: Hazelcast and Infinispan support embedded mode with near-cache, which reduces read latency significantly. If you already run one of these, adding Andersoni may not be justified.

| | Redis / Hazelcast | Andersoni |
|---|---|---|
| Deployment | External or embedded | In-process JAR |
| Read latency | ~0.5-1ms (remote) | ~30ns (local) |
| Write model | Mutable shared state | Immutable snapshots |
| Consistency | Strong (single source) | Eventually consistent |
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
- **You need strong consistency across nodes** — Andersoni syncs are eventually consistent. The lag depends on your sync strategy (Kafka is near-realtime, DB polling can be seconds).
- **Your dataset is very large (tens of millions+)** — snapshots live fully in heap. At 500K items with 3 indices, expect ~70MB. Scale accordingly and consider GC pressure from snapshot swaps on very large datasets.

## Quick Start

```xml
<dependency>
    <groupId>io.github.waabox</groupId>
    <artifactId>andersoni-core</artifactId>
    <version>1.2.0</version>
</dependency>
```

```java
Catalog<Event> catalog = Catalog.of(Event.class)
    .named("events")
    .loadWith(() -> eventRepository.findAll())
    .index("by-venue").by(Event::venue, Venue::name)
    .index("by-sport").by(Event::sport)
    .build();

Andersoni andersoni = Andersoni.builder().build();
andersoni.register(catalog);
andersoni.start();

List<Event> events = andersoni.search("events", "by-venue", "Wembley");
```

For Spring Boot, sync strategies, snapshot persistence, K8s deployment, and more — see the **[Wiki](https://github.com/waabox/andersoni/wiki)**.

## Modules

| Module | Purpose |
|---|---|
| `andersoni-core` | Engine, DSL, Snapshot, core interfaces (zero dependencies) |
| `andersoni-sync-kafka` | Kafka broadcast sync |
| `andersoni-spring-sync-kafka` | Spring Kafka auto-configured sync |
| `andersoni-sync-http` | HTTP peer-to-peer sync |
| `andersoni-sync-db` | JDBC polling sync |
| `andersoni-leader-k8s` | K8s Lease leader election |
| `andersoni-snapshot-s3` | S3 snapshot persistence |
| `andersoni-snapshot-fs` | Filesystem snapshot (dev/test) |
| `andersoni-spring-boot-starter` | Spring Boot auto-configuration |
| `andersoni-admin` | K8s admin console |

## Building

```bash
mvn clean test       # run all tests
mvn clean install    # install to local Maven repo
```

Requires Java 21.

## License

[Apache License 2.0](LICENSE)
