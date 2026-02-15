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
</p>

In-memory indexed cache library for Java 21. Define search indices over your domain objects with a fluent DSL, get lock-free reads via immutable snapshots, and sync across nodes with pluggable strategies.

## Why Andersoni?

Andersoni solves a problem that existing caching libraries don't address: **multi-index search over domain objects, in-process, with zero-lock reads**.

### vs Caffeine

Caffeine is a key-value cache. One key, one value. If you need to find events by venue, by sport, and by team, you maintain three separate caches and three separate loading strategies. Andersoni loads your data **once** and builds all indices from the same dataset. No redundant queries, no inconsistency between caches, no manual invalidation of N entries across N caches.

| | Caffeine | Andersoni |
|---|---|---|
| Model | key → value | dataset → N indices |
| Load data | Per entry, per cache | Once, all indices built |
| Search | Single key lookup | Multi-index search |
| Consistency | Manual across caches | Guaranteed (single snapshot) |
| Eviction | Per entry (size/time) | Full snapshot swap |

### vs Redis / Hazelcast / Infinispan

These are **external infrastructure**: a separate process, a separate cluster, network hops, serialization on every read. Andersoni lives **inside your JVM**. Reads are a pointer dereference to an immutable object — zero network latency, zero serialization, zero locks. No infrastructure to deploy, monitor, or maintain. When you need sync between nodes, plug in Kafka, HTTP, or DB polling — but reads are always local.

| | Redis / Hazelcast | Andersoni |
|---|---|---|
| Deployment | External cluster | In-process (JAR) |
| Read latency | Network + deserialization | Pointer dereference |
| Locks on read | Depends on mode | Never |
| Infrastructure | Dedicated servers | None |
| Sync | Built into cluster | Pluggable (Kafka, HTTP, DB) |

### vs Spring Cache (@Cacheable)

Spring Cache is an **annotation-based per-method cache**. It caches the return value of a method call for a given key. It has no concept of indexing a dataset, searching by multiple criteria, or maintaining a consistent view across indices. Under the hood, it delegates to Caffeine or Redis — inheriting their limitations. Andersoni is **domain-oriented**: you model your data with indices, not cache individual method calls.

| | Spring Cache | Andersoni |
|---|---|---|
| Approach | Cache method results | Index domain data |
| Multi-index | No | Yes, N indices per catalog |
| Consistency | Per method/key | Per snapshot (all indices) |
| DSL | Annotations | Fluent builder |
| Provider | Wraps Caffeine/Redis | Standalone engine |

### When to use Andersoni

Andersoni is the right choice when you have **reference data or read-heavy datasets** that you need to query by multiple criteria with sub-microsecond latency: product catalogs, event listings, configuration data, sports fixtures, pricing tables. If you need single key-value caching with TTL eviction, use Caffeine. If you need a shared mutable store across services, use Redis.

## Quick Start

Add the starter and your preferred sync strategy:

```xml
<dependency>
    <groupId>io.github.waabox</groupId>
    <artifactId>andersoni-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>io.github.waabox</groupId>
    <artifactId>andersoni-sync-kafka</artifactId>
    <version>1.0.0</version>
</dependency>
```

Register your catalogs:

```java
@Configuration
public class CacheConfig {

    @Bean
    CatalogRegistrar eventCatalogs(EventRepository repo) {
        return andersoni -> andersoni.register(
            Catalog.of(Event.class)
                .named("events")
                .loadWith(repo::findAll)
                .refreshEvery(Duration.ofMinutes(5))
                .index("by-venue").by(Event::venue, Venue::name)
                .index("by-sport").by(Event::sport, Sport::name)
                .index("by-home-team").by(Event::homeTeam, Team::name)
                .build()
        );
    }
}
```

Search:

```java
List<Event> events = andersoni.search("events", "by-venue", "Wembley");
```

## How It Works

Each `Catalog` holds an `AtomicReference<Snapshot>`. A Snapshot is a fully immutable, point-in-time view of all data and indices. Reads are lock-free. On refresh, a new Snapshot is built and atomically swapped in. Readers holding a reference to the old Snapshot continue working with consistent data until GC collects it.

```
Reader Thread A ──> snapshot.search("by-venue", "Wembley") ──> [Event1, Event2]
Reader Thread B ──> snapshot.search("by-sport", "Football") ──> [Event1, Event3]
Writer Thread   ──> dataLoader.load() ──> buildSnapshot() ──> atomicSwap()
```

No locks on reads. Ever.

## Fluent DSL

### With a DataLoader (for DB queries)

```java
Catalog.of(Event.class)
    .named("events")
    .loadWith(() -> eventRepository.findAll())
    .serializer(new EventParquetSerializer())     // optional, for snapshot persistence
    .refreshEvery(Duration.ofMinutes(5))          // optional, leader-only scheduled refresh
    .index("by-venue").by(Event::venue, Venue::name)
    .index("by-sport").by(Event::sport, Sport::name)
    .build()
```

### With static data

```java
Catalog.of(Event.class)
    .named("events")
    .data(List.of(e1, e2, e3))
    .index("by-venue").by(Event::venue, Venue::name)
    .build()
```

One `DataLoader.load()` call fetches the data once. All indices are built from that same dataset. No redundant queries.

## Standalone Usage (no Spring)

```java
Andersoni andersoni = Andersoni.builder()
    .nodeId("node-1")                                      // optional, default UUID
    .syncStrategy(new KafkaSyncStrategy(kafkaConfig))      // optional
    .leaderElection(new K8sLeaseLeaderElection(k8sConfig)) // optional, default SingleNode
    .snapshotStore(new S3SnapshotStore(s3Config))           // optional
    .retryPolicy(RetryPolicy.of(3, Duration.ofSeconds(2))) // optional
    .build();

andersoni.register(
    Catalog.of(Event.class)
        .named("events")
        .loadWith(eventRepository::findAll)
        .index("by-venue").by(Event::venue, Venue::name)
        .build()
);

andersoni.start();

// Search (lock-free)
List<Event> events = andersoni.search("events", "by-venue", "Wembley");

// Refresh and propagate to other nodes
andersoni.refreshAndSync("events");

andersoni.stop();
```

## Modules

| Module | Description | Dependencies |
|---|---|---|
| `andersoni-core` | Engine, DSL, Snapshot, interfaces | None (pure Java 21) |
| `andersoni-sync-kafka` | Kafka broadcast sync | kafka-clients |
| `andersoni-sync-http` | HTTP peer-to-peer sync | java.net.http (built-in) |
| `andersoni-sync-db` | JDBC polling sync | javax.sql |
| `andersoni-leader-k8s` | K8s Lease leader election | kubernetes-client |
| `andersoni-snapshot-s3` | S3 snapshot persistence | aws-sdk-s3 |
| `andersoni-snapshot-fs` | Filesystem snapshot (dev/test) | None |
| `andersoni-spring-boot-starter` | Spring Boot auto-configuration | spring-boot-autoconfigure |

Pick only what you need. The core has zero external dependencies.

## Sync Between Nodes

When a node refreshes a catalog, it can propagate the change to all other nodes:

```
Node A: CRUD operation
  -> andersoni.refreshAndSync("events")
    1. DataLoader.load()           -> fetch from DB
    2. Build new Snapshot          -> all indices rebuilt
    3. Atomic swap                 -> local readers see new data
    4. Serialize + upload to S3    -> if SnapshotStore configured
    5. Publish RefreshEvent        -> via SyncStrategy

Node B: receives RefreshEvent
    1. Compare hash                -> skip if already up to date
    2. Download from S3            -> if SnapshotStore configured
    3. Rebuild indices + swap      -> followers never touch the DB
```

Three sync strategies out of the box:

**Kafka** (`andersoni-sync-kafka`): Broadcast pattern. Each node gets its own consumer group so all nodes receive every message.

**HTTP** (`andersoni-sync-http`): Direct peer-to-peer. The originating node POSTs to all configured peer URLs. Uses `java.net.http` (zero dependencies).

**DB Polling** (`andersoni-sync-db`): A shared `andersoni_sync_log` table. The leader polls periodically. Simpler setup, more lag.

## Leader Election

The leader handles scheduled refreshes, DB polling, and snapshot publication. Two strategies:

**K8s Lease** (`andersoni-leader-k8s`): Uses the Kubernetes Lease API. Automatic failover.

**Single Node** (in `andersoni-core`): Always leader. For dev, testing, and single-instance deployments. This is the default.

## Snapshot Persistence (Fast Cold Start)

Instead of querying the DB on every new node startup, the leader serializes snapshots and uploads them to storage. New nodes download the snapshot and are ready in seconds.

**S3** (`andersoni-snapshot-s3`): Production-grade. Stores data + metadata in S3 objects.

**Filesystem** (`andersoni-snapshot-fs`): For development and testing. Atomic writes via temp file + rename.

## Spring Boot Configuration

```yaml
andersoni:
  node-id: ${HOSTNAME:}    # empty = auto UUID
```

Register sync, leader, and snapshot beans in your Spring config:

```java
@Configuration
public class AndersoniInfraConfig {

    @Bean
    SyncStrategy syncStrategy() {
        return new KafkaSyncStrategy(
            KafkaSyncConfig.create("localhost:9092"));
    }

    @Bean
    SnapshotStore snapshotStore() {
        return new S3SnapshotStore(S3SnapshotConfig.builder()
            .bucket("my-snapshots")
            .region(Region.US_EAST_1)
            .build());
    }
}
```

The starter auto-detects these beans and wires them into Andersoni.

## Observability

Implement `AndersoniMetrics` and register it as a Spring bean:

```java
public interface AndersoniMetrics {
    void catalogRefreshed(String catalogName, long durationMs, long itemCount);
    void snapshotLoaded(String catalogName, String source);
    void searchExecuted(String catalogName, String indexName, long durationNs);
    void refreshFailed(String catalogName, Throwable cause);
}
```

Default is no-op. Wire it to Micrometer, Datadog, or whatever you use.

## Error Handling

- **DataLoader fails at bootstrap**: Retries with exponential backoff (configurable via `RetryPolicy`). After exhausting retries, the catalog is marked as FAILED. Searches throw `CatalogNotAvailableException`. Other catalogs keep working.
- **SnapshotStore fails**: Automatic fallback to DataLoader.
- **SyncStrategy fails to publish**: Retry with backoff. Other nodes catch up on the next scheduled refresh.
- **Leader loses lease**: Another node takes over automatically.

## Building

```bash
mvn clean test       # run all tests
mvn clean install    # install to local Maven repo
```

Requires Java 21.

## License

[Apache License 2.0](LICENSE)
