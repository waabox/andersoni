# Andersoni - In-Memory Indexed Cache Library

## Overview

Andersoni is a Java 21 library (JAR) that provides an in-memory indexed cache system with a fluent DSL for defining search indices over domain objects. It supports pluggable synchronization between nodes, leader election, and snapshot persistence for fast cold starts.

Each microservice includes Andersoni as a Maven dependency. The cache lives inside the MS process.

### Root Package

`org.waabox.andersoni`

### Maven GroupId

`org.waabox`

## Architecture

### Deployment Model

- **Library JAR**: each MS includes Andersoni as a dependency
- **Multi-module Maven** project for minimal transitive dependencies
- **Spring Boot Starter** for auto-configuration

### Module Structure

```
andersoni/                          (parent POM)
├── andersoni-core/                 # Engine, DSL, Snapshot, interfaces (zero external deps)
├── andersoni-sync-kafka/           # SyncStrategy for Kafka
├── andersoni-sync-http/            # SyncStrategy for HTTP (java.net.http)
├── andersoni-sync-db/              # SyncStrategy for DB polling (JDBC)
├── andersoni-leader-k8s/           # LeaderElectionStrategy for K8s Lease API
├── andersoni-snapshot-s3/          # SnapshotStore for AWS S3
├── andersoni-snapshot-fs/          # SnapshotStore for local filesystem (dev/testing)
└── andersoni-spring-boot-starter/  # Spring Boot auto-configuration
```

### Module Dependencies

```
andersoni-core                  → none (pure Java 21)
andersoni-sync-kafka            → andersoni-core, kafka-clients
andersoni-sync-http             → andersoni-core, java.net.http (built-in)
andersoni-sync-db               → andersoni-core, javax.sql (JDBC)
andersoni-leader-k8s            → andersoni-core, kubernetes-client
andersoni-snapshot-s3           → andersoni-core, aws-sdk-s3
andersoni-snapshot-fs           → andersoni-core
andersoni-spring-boot-starter   → andersoni-core, spring-boot-autoconfigure
```

## Core Domain Model (andersoni-core)

### Key Concepts

**Andersoni** — The entry point. Singleton per MS. Contains all registered catalogs and orchestrates lifecycle (bootstrap, refresh, shutdown). Node ID is auto-generated as UUID by default, or user-provided.

**Catalog\<T\>** — Groups a data type with its DataLoader and all its indices. One query loads data, N indices are built from it.

**Index\<T, K\>** — A single index within a Catalog. Maps a key of type K to List\<T\>. Backed by an immutable Map inside a Snapshot.

**Snapshot\<T\>** — Immutable point-in-time view of all data and indices for a Catalog. Held via AtomicReference for lock-free reads.

**DataLoader\<T\>** — Functional interface the user implements to define how data is fetched.

**IndexDefinition\<T\>** — Immutable metadata produced by the fluent builder for constructing an Index.

### Fluent Builder DSL

```java
Andersoni andersoni = Andersoni.builder()
    .syncStrategy(new KafkaSyncStrategy(config))
    .leaderElection(new K8sLeaseStrategy(config))
    .snapshotStore(new S3SnapshotStore(s3Config))
    .retryPolicy(RetryPolicy.of(3, Duration.ofSeconds(2)))
    .metrics(new MicrometerAndersoniMetrics(meterRegistry))
    .build();

// nodeId auto-generated as UUID, or:
// .nodeId("my-custom-node-1")

andersoni.register(
    Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> eventRepository.findAll())
        .serializer(new EventParquetSerializer())     // optional, for snapshot store
        .refreshEvery(Duration.ofMinutes(5))          // optional, leader-only scheduled refresh
        .index("by-venue").by(Event::getVenue, Venue::getName)
        .index("by-sport").by(Event::getSport, Sport::getName)
        .index("by-home-team").by(Event::getHomeTeam, Team::getName)
        .build()
);

andersoni.start();
```

Alternative: pass data directly instead of a DataLoader:

```java
List<Event> events = eventRepository.findAll();

andersoni.register(
    Catalog.of(Event.class)
        .named("events")
        .data(events)
        .index("by-venue").by(Event::getVenue, Venue::getName)
        .index("by-sport").by(Event::getSport, Sport::getName)
        .build()
);
```

### Search API

```java
List<Event> events = andersoni.search("events", "by-venue", "Wembley");
```

### DataLoader Interface

```java
@FunctionalInterface
public interface DataLoader<T> {
    List<T> load();
}
```

## Concurrency Model: Immutable Snapshot + AtomicReference

### Core Principle

Each Catalog holds an `AtomicReference<Snapshot<T>>`. Snapshots are completely immutable. Reads are lock-free. Writes (refresh) do an atomic swap.

### Snapshot Structure

```java
public final class Snapshot<T> {

    private final List<T> data;                              // unmodifiable
    private final Map<String, Map<Object, List<T>>> indices; // unmodifiable
    private final long version;
    private final String hash;                               // SHA-256 of serialized content
    private final Instant createdAt;
}
```

All internal lists and maps are wrapped with `Collections.unmodifiable*` at construction time.

### Read Path (zero locks)

```java
public List<T> search(final String indexName, final Object key) {
    final Snapshot<T> snapshot = current.get();   // atomic read, no lock
    final Map<Object, List<T>> index = snapshot.index(indexName);
    return index.getOrDefault(key, List.of());
}
```

A reader that obtains a Snapshot reference works with that photo. Even if a refresh happens in parallel, the reader sees a consistent view. The old Snapshot lives until GC collects it.

### Write Path (refresh)

```java
public void refresh() {
    final List<T> freshData = dataLoader.load();
    final Snapshot<T> newSnapshot = buildSnapshot(freshData);
    current.set(newSnapshot);   // atomic swap
}
```

The write path can be serialized with a ReentrantLock to prevent concurrent refreshes on the same Catalog. Readers are never blocked.

### Snapshot Construction

```java
private Snapshot<T> buildSnapshot(final List<T> data) {
    final Map<String, Map<Object, List<T>>> indices = new HashMap<>();
    for (final IndexDef<T> def : indexDefinitions) {
        final Map<Object, List<T>> index = new HashMap<>();
        for (final T item : data) {
            final Object key = def.keyExtractor().apply(item);
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        index.replaceAll((k, v) -> Collections.unmodifiableList(v));
        indices.put(def.name(), Collections.unmodifiableMap(index));
    }
    return new Snapshot<>(
        Collections.unmodifiableList(data),
        Collections.unmodifiableMap(indices),
        versionCounter.incrementAndGet(),
        computeHash(data),
        Instant.now()
    );
}
```

### Concurrency Summary

| Operation | Lock? | What it sees |
|---|---|---|
| search() | No | Current snapshot (immutable) |
| User iterating results | No | The snapshot it obtained |
| refresh() | No lock on readers | Builds new snapshot, atomic swap |
| Multiple concurrent refreshes | Serialized with ReentrantLock on write path | Readers never blocked |

## Synchronization Between Nodes

### SyncStrategy Interface

```java
public interface SyncStrategy {
    void publish(RefreshEvent event);
    void subscribe(RefreshListener listener);
    void start();
    void stop();
}
```

```java
public record RefreshEvent(
    String catalogName,
    String sourceNodeId,
    long version,
    String hash,
    Instant timestamp
) {}
```

```java
@FunctionalInterface
public interface RefreshListener {
    void onRefresh(RefreshEvent event);
}
```

### Sync Flow

```
Node A (performs CRUD)
  -> andersoni.refreshAndSync("events")
    1. DataLoader.load()                    → fetch from DB
    2. buildSnapshot(data)                  → new immutable Snapshot
    3. current.set(newSnapshot)             → atomic swap local
    4. serialize to Parquet + compute hash
    5. snapshotStore.save(serialized)       → upload to S3
    6. syncStrategy.publish(RefreshEvent)   → propagate to other nodes

Node B (receives event)
  -> RefreshEvent(catalog="events", hash="abc123", version=42)
    If local hash == "abc123" → already up to date, ignore
    If local hash != "abc123":
      1. snapshotStore.load("events")       → download from S3
      2. deserialize + rebuild indices
      3. atomic swap
```

Followers never touch the DB. Only the master does queries, serializes, and uploads to S3.

### Implementations

**Kafka** (andersoni-sync-kafka): Publishes to topic `andersoni-sync`. Uses broadcast pattern (each instance has its own consumer group) so all nodes receive the message.

**HTTP** (andersoni-sync-http): The originating node calls `/andersoni/refresh` on other nodes. Requires service discovery (K8s DNS or pluggable registry).

**DB Polling** (andersoni-sync-db): Table `andersoni_sync_log(catalog_name, updated_at)`. Leader polls periodically. Simpler, more lag.

## Leader Election

### LeaderElectionStrategy Interface

```java
public interface LeaderElectionStrategy {
    void start();
    boolean isLeader();
    void onLeaderChange(LeaderChangeListener listener);
    void stop();
}
```

The leader handles: DB polling for change detection, scheduled refreshes (cron), snapshot publication to S3.

### Implementations

**K8s Lease** (andersoni-leader-k8s): Uses Kubernetes Lease API. Renewal every N seconds.

**Single Node** (in andersoni-core): Always returns `isLeader() = true`. For dev, testing, and single-instance deployments.

## Snapshot Persistence and Fast Bootstrap

### SnapshotStore Interface

```java
public interface SnapshotStore {
    void save(String catalogName, SerializedSnapshot snapshot);
    Optional<SerializedSnapshot> load(String catalogName);
}
```

```java
public record SerializedSnapshot(
    String catalogName,
    String hash,
    long version,
    Instant createdAt,
    byte[] data
) {}
```

### New Node Bootstrap Flow

```
New node: andersoni.start()
  1. snapshotStore.load("events")     → download Parquet from S3
  2. deserialize → List<T>
  3. buildSnapshot(data)              → build indices in memory
  4. current.set(snapshot)            → ready to serve queries
  5. NO DB query needed
```

Startup in seconds, not minutes.

### Serializer

User provides a `Serializer<T>` for Parquet format. Optional — if no SnapshotStore is configured, the system loads from DB as before (opt-in).

```java
Catalog.of(Event.class)
    .named("events")
    .loadWith(eventRepository::findAll)
    .serializer(new EventParquetSerializer())
    ...
```

### Fallback

If SnapshotStore fails to load (S3 down), fallback to DataLoader automatically. Logs warning.

## Error Handling and Resilience

**DataLoader fails at bootstrap:**
- Retry N times with exponential backoff (configurable via RetryPolicy)
- After N retries: catalog state = FAILED, searches throw CatalogNotAvailableException
- Does not block MS startup: other catalogs keep working

**SnapshotStore fails to download:**
- Automatic fallback: execute DataLoader against DB
- Log warning: "snapshot unavailable, falling back to DataLoader"

**SyncStrategy fails to publish:**
- Retry with backoff
- If fails definitively: log error, other nodes eventually update on next scheduled refresh

**Leader loses lease:**
- Another node takes leadership automatically (LeaderElectionStrategy handles it)
- New leader starts polling/publishing snapshots

### Retry Policy

```java
Andersoni.builder()
    .retryPolicy(RetryPolicy.of(3, Duration.ofSeconds(2)))
    ...
```

### Scheduled Refresh (safety net)

```java
Catalog.of(Event.class)
    .named("events")
    .loadWith(eventRepository::findAll)
    .refreshEvery(Duration.ofMinutes(5))    // leader-only
    ...
```

## Observability

### AndersoniMetrics Interface

```java
public interface AndersoniMetrics {
    void catalogRefreshed(String catalogName, long durationMs, long itemCount);
    void snapshotLoaded(String catalogName, String source);
    void searchExecuted(String catalogName, String indexName, long durationNs);
    void refreshFailed(String catalogName, Throwable cause);
}
```

Default implementation: no-op. The Spring Boot starter wires it to Micrometer automatically if on classpath.

## Spring Boot Integration

### User Configuration

```java
@Configuration
public class AndersoniConfig {

    @Bean
    public CatalogRegistrar eventCatalogs(final EventRepository eventRepository) {
        return andersoni -> {
            andersoni.register(
                Catalog.of(Event.class)
                    .named("events")
                    .loadWith(eventRepository::findAll)
                    .index("by-venue").by(Event::getVenue, Venue::getName)
                    .index("by-sport").by(Event::getSport, Sport::getName)
                    .build()
            );
        };
    }
}
```

```java
@FunctionalInterface
public interface CatalogRegistrar {
    void register(Andersoni andersoni);
}
```

The starter collects all CatalogRegistrar beans and executes them at startup, then calls `andersoni.start()`.

### application.yml

```yaml
andersoni:
  node-id: ${HOSTNAME:}                     # empty = auto UUID
  sync:
    kafka:
      topic: andersoni-sync
      bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
  leader:
    k8s:
      lease-name: andersoni-leader
      lease-namespace: ${K8S_NAMESPACE:default}
      renewal-seconds: 15
  snapshot:
    s3:
      bucket: andersoni-snapshots
      prefix: ${spring.application.name}/
      region: us-east-1
```

## Lifecycle Summary

1. **Register** — Define catalogs via fluent builder
2. **Start** — Bootstrap: load snapshots from S3 (or DataLoader fallback), build indices, start sync listeners, start leader election
3. **Search** — Lock-free queries on immutable Snapshots
4. **Refresh** — Master: DataLoader → Snapshot → S3 → publish event. Followers: download from S3 → rebuild → swap
5. **Shutdown** — Deregister listeners, stop leader election, cleanup
