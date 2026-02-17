# Core Concepts

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                        Andersoni                            │
│                                                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                  │
│  │ Catalog A │  │ Catalog B │  │ Catalog C │  ...            │
│  │           │  │           │  │           │                  │
│  │ Snapshot ◄┤  │ Snapshot ◄┤  │ Snapshot ◄┤                  │
│  │ (atomic)  │  │ (atomic)  │  │ (atomic)  │                  │
│  └──────────┘  └──────────┘  └──────────┘                  │
│                                                             │
│  ┌───────────────┐  ┌────────────────┐  ┌───────────────┐  │
│  │ SyncStrategy   │  │ LeaderElection │  │ SnapshotStore │  │
│  │ (Kafka/HTTP/DB)│  │ (K8s/Single)   │  │ (S3/FS)       │  │
│  └───────────────┘  └────────────────┘  └───────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Andersoni

The main entry point and orchestrator. It manages the lifecycle of all registered catalogs and coordinates sync, leader election, and snapshot persistence.

**Responsibilities:**
- Register and hold catalogs
- Bootstrap all catalogs on `start()`
- Wire sync listeners for cross-node events
- Schedule periodic refreshes (leader-only)
- Provide the `search()` API

```java
Andersoni andersoni = Andersoni.builder()
    .nodeId("node-1")
    .syncStrategy(kafkaSync)
    .leaderElection(k8sLease)
    .snapshotStore(s3Store)
    .retryPolicy(RetryPolicy.of(3, Duration.ofSeconds(2)))
    .metrics(customMetrics)
    .build();
```

All builder options are optional with sensible defaults. Without any strategies, Andersoni runs as a single-node in-memory cache.

## Catalog

A Catalog groups a dataset with one or more indices. It holds an `AtomicReference<Snapshot<T>>` that is atomically swapped on every refresh. Readers always see a consistent, immutable view.

```java
Catalog<Event> catalog = Catalog.of(Event.class)
    .named("events")
    .loadWith(() -> repository.findAll())
    .serializer(new EventSnapshotSerializer())
    .refreshEvery(Duration.ofMinutes(5))
    .index("by-sport").by(Event::sport)
    .index("by-venue").by(Event::venue, Venue::name)
    .build();
```

**Key behaviors:**
- `bootstrap()` — loads data for the first time (called by `Andersoni.start()`)
- `refresh()` — reloads from the DataLoader, rebuilds all indices, atomically swaps
- `refresh(List<T>)` — accepts explicit new data (used when restoring from a snapshot)
- `search(indexName, key)` — lock-free lookup, returns an unmodifiable list

## Snapshot

An immutable, point-in-time view of a catalog's data and indices. Once created, a Snapshot is never modified.

**Properties:**
- `data()` — the full dataset (unmodifiable list)
- `version()` — monotonically increasing version number
- `hash()` — SHA-256 hash of the serialized data (or `toString()` fallback)
- `createdAt()` — timestamp
- `search(indexName, key)` — index lookup

The immutable snapshot pattern is what makes lock-free reads possible. Multiple threads can read the same snapshot concurrently without any synchronization.

## IndexDefinition

Defines how to extract a search key from each item. Supports composed functions for navigating nested objects.

```java
// Simple key
.index("by-sport").by(Event::sport)

// Composed key (Event → Venue → name)
.index("by-venue").by(Event::venue, Venue::name)
```

At build time, each `IndexDefinition` creates a `Map<String, List<T>>` — a HashMap where keys are the extracted values and values are the matching items.

## DataLoader

A functional interface that returns the full dataset. It is called during bootstrap and on every refresh.

```java
@FunctionalInterface
public interface DataLoader<T> {
    List<T> load();
}
```

The DataLoader must return a complete, non-null list. Andersoni does not support partial/incremental updates — every refresh is a full snapshot rebuild.

## RefreshEvent

A record that represents a catalog refresh notification. Published via the SyncStrategy and received by other nodes.

```java
public record RefreshEvent(
    String catalogName,
    String sourceNodeId,
    long version,
    String hash,
    Instant timestamp
) {}
```

When a node receives a RefreshEvent:
1. If `sourceNodeId` matches the local node → ignored (self-originated)
2. If `hash` matches the local snapshot → already up-to-date, ignored
3. Otherwise → try loading from SnapshotStore, fall back to DataLoader

## SyncStrategy

Pluggable interface for broadcasting refresh events across nodes.

```java
public interface SyncStrategy {
    void publish(RefreshEvent event);
    void subscribe(RefreshListener listener);
    void start();
    void stop();
}
```

Three implementations: [[Sync Strategies|Kafka, HTTP, DB Polling]].

## LeaderElectionStrategy

Determines which node is the leader. The leader is responsible for:
- Executing DataLoaders during bootstrap
- Running scheduled refreshes
- Uploading snapshots to the SnapshotStore

```java
public interface LeaderElectionStrategy {
    void start();
    boolean isLeader();
    void onLeaderChange(LeaderChangeListener listener);
    void stop();
}
```

Two implementations: [[Leader Election|K8s Lease, Single Node]].

## SnapshotStore

Persists serialized snapshots for fast cold-start recovery. New nodes restore from the snapshot instead of hitting the database.

```java
public interface SnapshotStore {
    void save(String catalogName, SerializedSnapshot snapshot);
    Optional<SerializedSnapshot> load(String catalogName);
}
```

Two implementations: [[Snapshot Persistence|S3, Filesystem]].

## SnapshotSerializer

Converts a list of items to/from bytes. Required for snapshot persistence and deterministic hash computation.

```java
public interface SnapshotSerializer<T> {
    byte[] serialize(List<T> items);
    List<T> deserialize(byte[] data);
}
```

Without a serializer, hash computation falls back to `toString()` which may produce unreliable results. Always provide a serializer in production.

## Bootstrap Flow

```
Andersoni.start()
  │
  ├─ Start leader election
  │
  ├─ For each catalog:
  │   ├─ Try load from SnapshotStore (if configured)
  │   │   ├─ Success → done
  │   │   └─ Fail ↓
  │   ├─ If leader:
  │   │   ├─ Call DataLoader (with retry)
  │   │   └─ Save snapshot to SnapshotStore
  │   └─ If follower:
  │       ├─ Retry SnapshotStore (10x, waiting for leader upload)
  │       ├─ If promoted to leader mid-bootstrap → switch to DataLoader
  │       └─ After all retries exhausted → catalog marked FAILED
  │
  ├─ Wire sync listener (subscribe to RefreshEvents)
  │
  └─ Schedule periodic refreshes (leader-only)
```

## Refresh & Sync Flow

```
Node A calls refreshAndSync("events")
  │
  ├─ catalog.refresh() → DataLoader.load() → build Snapshot → atomic swap
  ├─ Save snapshot to SnapshotStore (if configured)
  └─ Publish RefreshEvent via SyncStrategy

Nodes B, C receive RefreshEvent
  │
  ├─ sourceNodeId == self? → ignore
  ├─ hash == local hash? → ignore (already up-to-date)
  └─ Otherwise:
      ├─ Try load from SnapshotStore
      └─ Fall back to DataLoader
```
