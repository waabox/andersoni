# Andersoni engine and operations (core, 1.12.2)

Artifact `io.github.waabox:andersoni-core`. Engine class `org.waabox.andersoni.Andersoni`.
Concrete sync / leader / snapshot modules are in `cluster-modules.md`; this file covers the core API they plug into.

## Contents

1. [Builder](#1-builder)
2. [Engine API](#2-engine-api)
3. [Lifecycle](#3-lifecycle)
4. [Refresh semantics](#4-refresh-semantics)
5. [RetryPolicy and ReconciliationPolicy](#5-retrypolicy-and-reconciliationpolicy)
6. [Reconciliation (anti-entropy)](#6-reconciliation-anti-entropy)
7. [Observability](#7-observability)
8. [Extension interfaces](#8-extension-interfaces)
9. [Exceptions](#9-exceptions)
10. [Troubleshooting](#10-troubleshooting)

## 1. Builder

`Andersoni.builder()` returns `Andersoni.Builder`. Every setter rejects `null` with `NullPointerException`. All are optional.

| Method | Type | Default when not called |
|---|---|---|
| `nodeId(String)` | node id; empty string -> `IllegalArgumentException` | env `HOSTNAME` if non-blank (pod name in K8s), else random UUID |
| `syncStrategy(SyncStrategy)` | `org.waabox.andersoni.sync.SyncStrategy` | none -> single-node mode, no events |
| `leaderElection(LeaderElectionStrategy)` | `org.waabox.andersoni.leader.LeaderElectionStrategy` | `new SingleNodeLeaderElection()` (always leader) |
| `snapshotStore(SnapshotStore)` | `org.waabox.andersoni.snapshot.SnapshotStore` | none -> no persistence, no reconciliation |
| `retryPolicy(RetryPolicy)` | bootstrap retry | `RetryPolicy.defaultPolicy()` (3 attempts, 2s) |
| `metrics(AndersoniMetrics)` | `org.waabox.andersoni.metrics.AndersoniMetrics` | `new NoopAndersoniMetrics()` |
| `reconciliation(ReconciliationPolicy)` | anti-entropy loop | `ReconciliationPolicy.defaultPolicy()` (on, 30s; active only with a store) |
| `maxPatchPayloadBytes(int)` | since 1.13.0; encoded-size ceiling above which a patch is published as a full-refresh `EVENT` instead; `<= 0` -> `IllegalArgumentException` | `262144` (256 KB) |
| `build()` | returns `Andersoni` | |

There are no executor/thread-pool setters. Threads are internal (see section 3).

```java
final Andersoni andersoni = Andersoni.builder()
    .nodeId("orders-api-0")
    .syncStrategy(syncStrategy)
    .leaderElection(leaderElection)
    .snapshotStore(snapshotStore)
    .retryPolicy(RetryPolicy.of(5, Duration.ofSeconds(3)))
    .reconciliation(ReconciliationPolicy.of(Duration.ofSeconds(15)))
    .metrics(metrics)
    .build();
andersoni.register(eventsCatalog);
andersoni.start();
```

Set an explicit, stable, unique `nodeId` per node: self-originated `EVENT`s are ignored by comparing `nodeId`, so two nodes with the same id ignore each other's events.

## 2. Engine API

| Method | Notes |
|---|---|
| `String nodeId()` | resolved id |
| `void register(Catalog<?>)` | before `start()` only |
| `void start()` / `void stop()` | see section 3. No `close()`; `Andersoni` is not `AutoCloseable` |
| `List<?> search(String catalog, String index, Object key)` | lock-free |
| `<T> List<T> search(String catalog, String index, Object key, Class<T> type)` | `type` may be the item type or a registered view type |
| `QueryStep<?> query(String catalog, String index)` / `<T> QueryStep<T> query(String, String, Class<T>)` | fluent query |
| `CompoundQuery<?> compound(String)` / `<T> CompoundQuery<T> compound(String, Class<T>)` | |
| `GraphQueryBuilder<?> graphQuery(String)` / `<T> GraphQueryBuilder<T> graphQuery(String, Class<T>)` | |
| `void refresh(String)` | local reload, no sync |
| `void refreshAndSync(String)` | cluster-aware refresh |
| `PatchOutcome replaceAndSync(String catalog, String index, Object key, Object newItem)` | since 1.13.0; leader-only single-item replace + broadcast (section 4, Patches) |
| `PatchOutcome insertAndSync(String catalog, Object newItem)` | since 1.13.0; leader-only insert + broadcast |
| `PatchOutcome removeAndSync(String catalog, String index, Object key)` | since 1.13.0; leader-only remove + broadcast |
| `PatchOutcome upsertAndSync(String catalog, String index, Object key, Object newItem)` | since 1.13.0; leader-only replace-or-insert + broadcast |
| `<T> PatchOutcome applyAndSync(String catalog, List<PatchOp<T>> ops)` | since 1.13.0; leader-only atomic batch + one broadcast |
| `void insert(String catalog, Object item)` / `boolean remove(String catalog, String index, Object key)` / `boolean replace(String catalog, String index, Object key, Object newItem)` | since 1.13.0; local-only patches, no save, no publish; `CatalogNotAvailableException` on a FAILED catalog. There is no local `upsert` on `Andersoni` (use `catalog(name, T.class).upsert(...)`) |
| `void reconcile()` | async on-demand reconciliation pass |
| `AndersoniStatus status()` | never throws for an unhealthy catalog |
| `CatalogInfo info(String)` | stats per catalog |
| `<T> Catalog<T> catalog(String, Class<T>)` | unchecked cast |
| `Collection<Catalog<?>> catalogs()` | unmodifiable |

Unknown catalog name -> `IllegalArgumentException("No catalog registered with name '...'")` on every name-taking method.
`search`/`query`/`compound`/`graphQuery` throw `CatalogNotAvailableException` for a catalog whose bootstrap failed.

## 3. Lifecycle

### register(catalog)
- After `start()` -> `IllegalStateException("Cannot register catalogs after start() has been called")`.
- Duplicate `catalog.name()` -> `IllegalArgumentException`.

### start() — synchronous, runs on the calling thread
Second call -> `IllegalStateException("Andersoni has already been started")`. A stopped instance cannot be restarted (build a new one).

Order:
1. `leaderElection.start()`.
2. Bootstrap every catalog, one after another (blocking, see below).
3. Create the `AsyncRefreshDispatcher`.
4. Register the promotion listener (`leaderElection.onLeaderChange`).
5. If a sync strategy is set: `syncStrategy.subscribe(listener)` then `syncStrategy.start()`.
6. Schedule `refreshEvery` catalogs (only if at least one has an interval).
7. Start the reconciler (only if policy enabled AND a store is configured).
8. `metrics.start(catalogs, nodeId)`.

Per-catalog bootstrap (store is used only when a store is configured AND the catalog has a `serializer(...)`):

| Step | Leader | Follower |
|---|---|---|
| 1 | Try store once (`load`). Hit -> done (`snapshotLoaded(name, "snapshotStore")`). Exception -> logged, continue | same |
| 2 | `catalog.bootstrap()` (DataLoader or static `data`) up to `maxRetries()` attempts, `backoff()` sleep between; success -> save to store, `snapshotLoaded(name, "dataLoader")` | retry store up to `maxRetries() * 10` attempts with `backoff()` sleep; re-checks leadership each attempt and switches to the leader path if promoted |
| 3 | all attempts failed -> catalog FAILED, `refreshFailed` | store attempts exhausted -> one DataLoader call; success -> saves to store, `snapshotLoaded(name, "followerDataLoaderFallback")`; failure -> FAILED |

- A leader with a snapshot already in the store loads it and does NOT query the DataLoader.
- Worst-case follower wait with defaults: 30 attempts x 2s = ~60s per catalog, sequential across catalogs, before the DataLoader fallback.
- Interrupt during backoff -> catalog marked FAILED, interrupt flag restored.
- A FAILED catalog does not abort `start()`; other catalogs continue.
- A FAILED catalog leaves the FAILED set on any later successful reload (`refresh`, leader `refreshAndSync`, EVENT reload, promotion refresh, follower store repair). In 1.12.0 only promotion refresh and follower store repair cleared it.
- Before `start()` a catalog holds an empty snapshot (version 0, hash `""`): queries return empty results, not exceptions.

### Threads

| Thread | Purpose |
|---|---|
| caller of `start()` | bootstrap (blocking) |
| `andersoni-refresh-scheduler` (daemon, single) | `refreshEvery` ticks |
| `andersoni-reconciler` (daemon, single) | reconciliation passes |
| virtual threads (`AsyncRefreshDispatcher`) | sync-event reloads, leader request handling, reconciliation repairs, promotion refreshes |
| transport threads | deliver `RefreshEvent`s to the listener (must return fast; Andersoni offloads work) |

`AsyncRefreshDispatcher` (package-private): one permit per catalog (refreshes of one catalog never overlap); a dispatch while one is already queued-but-not-started is dropped (coalesced); a dispatch while one is running queues exactly one follow-up.

### Scheduled refresh (`Catalog...refreshEvery(Duration)`)
`scheduleAtFixedRate(interval, interval)`. Each tick runs `refreshAndSync(name)` only if `leaderElection.isLeader()`; followers do nothing (no `REQUEST` storm). Exceptions are logged + `metrics.refreshFailed`, the schedule continues.

### stop()
Idempotent. Order: cancel scheduled refreshes -> stop reconciler -> `syncStrategy.stop()` -> `leaderElection.stop()` -> `metrics.stop()`.
After `stop()`, `refresh`, `refreshAndSync` and `reconcile` throw `IllegalStateException`. Queries keep working on the last snapshot.

## 4. Refresh semantics

| Call | Leader | Follower |
|---|---|---|
| `refresh(name)` | `catalog.refresh()` (DataLoader), clears applied store hash, reports index sizes. No save, no publish | same, local only |
| `refreshAndSync(name)` | `catalog.refresh()` -> clear applied hash -> save to store (if store + serializer) -> publish `EVENT` (if sync strategy) | publish `REQUEST` and return; local data untouched. No sync strategy -> silent no-op |

Details:
- Leader `refreshAndSync`: DataLoader and store-save exceptions propagate to the caller (the refreshed snapshot is already live in memory). Publish failures are logged + `syncPublishFailed`, never thrown. Reconciliation repairs both later.
- With reconciliation active, a follower's `refresh(name)` is overwritten by the store on the next pass; a leader's `refresh(name)` is re-saved and re-published on the next pass.
- Catalogs built with `.data(List)` (no DataLoader): `catalog.refresh()` throws `IllegalStateException("Cannot refresh a catalog created with static data...")`, so `refresh(name)` and leader `refreshAndSync(name)` throw it. Replace data with `andersoni.catalog(name, T.class).refresh(newList)` — this swaps the snapshot only; it does not save, publish, or clear the applied hash.

### RefreshEvent / RefreshKind (`org.waabox.andersoni.sync`)

```java
public record RefreshEvent(String catalogName, String sourceNodeId, long version, String hash,
    Instant timestamp, RefreshKind kind) { ... }
// convenience ctor without kind -> RefreshKind.EVENT
// static RefreshEvent request(String catalogName, String sourceNodeId, Instant timestamp) -> version 0, hash ""
// boolean isRequest()
public enum RefreshKind { EVENT, REQUEST }
```

Incoming message handling (the single listener Andersoni subscribes):

| Message | Receiver | Action |
|---|---|---|
| any | unknown catalog name | warn, ignore |
| `REQUEST` | leader | dispatch `refreshAndSync(name)` async -> produces an `EVENT` |
| `REQUEST` | follower | ignore (never re-emitted -> no loop) |
| `EVENT` | `sourceNodeId == nodeId` | ignore |
| `EVENT` | local snapshot hash equals `event.hash()` | ignore |
| `EVENT` | otherwise | `syncReceived`; dispatch async: load from store, else `catalog.refresh()` (DataLoader); failure -> `refreshFailed` |

- `RefreshEventCodec.serialize(RefreshEvent)` / `deserialize(String)` is the JSON wire format (six fields; missing/unknown `kind` -> `EVENT`). Use it in custom transports.
- Rolling upgrade: nodes older than 1.10 decode `REQUEST` as an `EVENT` with empty hash (one spurious refresh, no loop).
- DB polling sync drops `REQUEST`s: follower `refreshAndSync` is a no-op there.

### Patches (since 1.13.0)

A patch changes one item (or a batch) without re-running the `DataLoader`. Local, uncoordinated patches live on `Catalog` (`catalog-dsl.md`, Lifecycle) and on `Andersoni` (`insert`/`remove`/`replace`); the cluster-coordinated ones are the `...AndSync` methods.

Leader path of every `...AndSync` call (synchronous on the calling thread):
1. Not leader -> return `PatchOutcome.NOT_LEADER` without touching anything and **without forwarding to the leader** (unlike `refreshAndSync`, no `REQUEST` is published). Stopped -> `IllegalStateException`; unknown catalog -> `IllegalArgumentException`; FAILED catalog -> `CatalogNotAvailableException`; catalog without `.serializer(...)` -> `IllegalStateException("... has no SnapshotSerializer; cross-node patches require one")`.
2. Apply on the local catalog under its refresh lock (`AmbiguousPatchTargetException` / `PatchTargetNotFoundException` propagate; a lookup miss on replace/remove returns `NOT_FOUND`, nothing is saved or published).
3. Save the full snapshot to the `SnapshotStore` (if configured) and record its hash as applied. A save failure propagates to the caller like `refreshAndSync`; the change stays live locally and the next reconciliation pass re-saves and re-publishes it.
4. Publish: no sync strategy or `!syncStrategy.supportsPatches()` -> skip (followers converge through `refreshEvery`/reconciliation). Otherwise encode a `PatchEvent`; if its JSON is larger than `maxPatchPayloadBytes` publish a plain full-refresh `EVENT` instead (`patchOversized` metric, followers reload from the store just saved). Publish failures are logged + `syncPublishFailed`, never thrown. `syncPublished` on success.

```java
public enum PatchOutcome { APPLIED, NOT_FOUND, NOT_LEADER; public boolean applied(); }
// NOT_FOUND: leader, lookup matched nothing (never from insert/upsert). NOT_LEADER: follower, nothing done.
public sealed interface PatchOp<T> {
  record Replace<T>(String indexName, Object key, T item) implements PatchOp<T> {}
  record Insert<T>(T item) implements PatchOp<T> {}
  record Remove<T>(String indexName, Object key) implements PatchOp<T> {}
  record Upsert<T>(String indexName, Object key, T item) implements PatchOp<T> {}
}
```

Lookups (`replace`/`remove`/`upsert` and their `PatchOp`s) go through a plain `.index(...)` whose key matches exactly one item. An `indexGraph` name is accepted, but graph keys are internal composite keys that no caller-supplied key matches, so the lookup silently comes back `NOT_FOUND` (and an upsert inserts a duplicate): give a graph-only catalog a plain `by-id` index for patching.

A follower gets `NOT_LEADER` and nothing is forwarded. When the write can land on any node (an HTTP request behind a load balancer, a Kafka consumer in every pod), fall back to `refreshAndSync(name)` on `NOT_LEADER`: on a follower it publishes a `REQUEST`, and the leader runs a full reload and broadcasts it. The change still reaches every node, at the cost of one full reload; patches pay off when the leader takes the write.

`upsertAndSync` resolves on the leader into the replace or insert that actually happened; there is no `UPSERT` on the wire, so followers never re-decide. A batch is one swap, one hash, one message: followers apply all of it or none.

Follower handling of an incoming `PatchEvent` (keyed on content hashes; `version`s are node-local and never preconditions):

| Condition | Action |
|---|---|
| `sourceNodeId == nodeId` or unknown catalog | ignore |
| `event.toHash == local hash` | discard, `patchDiscarded` (already applied) |
| `event.fromHash == local hash` | `syncReceived`; apply surgically on a per-catalog ordered queue (arrival order, never coalesced); on success the applied store hash is set to `toHash`, so reconciliation sees no drift; `patchApplied` |
| otherwise, or the patch queue cannot accept the event | `syncReceived`, `patchFellBack`; full reload through the coalescing dispatcher (store first, `DataLoader` last resort) |

A follower without a serializer on that catalog cannot apply a patch and falls back to a reload as well.

Wire format and transports: `PatchEvent(catalogName, sourceNodeId, fromVersion, toVersion, fromHash, toHash, List<Op> operations, Instant timestamp)` implements the sealed `SyncMessage` together with `RefreshEvent`; `SyncMessageCodec.serialize/deserialize` writes a JSON envelope with a `"type"` discriminator (a payload without `type` decodes as a `RefreshEvent`, so pre-1.13.0 refresh messages still parse). Kafka (`KafkaSyncStrategy`, `SpringKafkaSyncStrategy`) and HTTP (`HttpSyncStrategy`) carry patches (`supportsPatches()` = `true`); `DbPollingSyncStrategy` does not: with it, or with no sync strategy, a leader patch publishes nothing (no `EVENT` either), and followers converge through reconciliation from the store or the next full refresh. HTTP peers reject bodies over 64 KiB with `413`, which is below the 256 KB default ceiling: over HTTP, set `maxPatchPayloadBytes(64 * 1024)` or lower, otherwise a patch between 64 KiB and 256 KB is refused by every peer (logged only) and followers stay stale until reconciliation.

**Rolling upgrade.** A node on 1.12.2 or earlier cannot decode a `PatchEvent` (its codec requires `version`/`hash`): Kafka logs an error per record and keeps polling, HTTP answers `500`, and the node keeps serving stale data with no other symptom. Installing 1.13.0 changes nothing until a patch method is called, so: deploy everywhere with call sites still on `refreshAndSync`, confirm the fleet, then switch call sites.

### Leader promotion
On `onLeaderChange(true)`, Andersoni dispatches an authoritative `refreshAndSync` for every catalog (with or without a store). Skipped if demoted again before it runs. Success removes the catalog from the FAILED set; failure -> logged + `refreshFailed`. A promotion that happened during `start()` is not replayed (the first reconciliation pass covers it).

## 5. RetryPolicy and ReconciliationPolicy

Both in `org.waabox.andersoni`, immutable, static factories.

| Factory | Result | Validation |
|---|---|---|
| `RetryPolicy.of(int maxRetries, Duration backoff)` | custom | `maxRetries <= 0` or non-positive backoff -> `IllegalArgumentException`; null backoff -> NPE |
| `RetryPolicy.defaultPolicy()` | 3, 2s | |
| accessors | `int maxRetries()`, `Duration backoff()` | |
| `ReconciliationPolicy.of(Duration interval)` | enabled, given interval | non-positive -> `IllegalArgumentException`; null -> NPE |
| `ReconciliationPolicy.disabled()` | off, interval `Duration.ZERO` | |
| `ReconciliationPolicy.defaultPolicy()` | enabled, 30s | |
| accessors | `boolean enabled()`, `Duration interval()` | |

`maxRetries` is the total number of leader bootstrap attempts (not retries after a first try). Fixed backoff, no exponential growth. Retry applies to bootstrap only, not to later refreshes.

## 6. Reconciliation (anti-entropy)

Active only when `reconciliation.enabled()` AND a `SnapshotStore` is configured. Each pass runs after `interval + random(0..20%)`. Only catalogs with a serializer take part; others are `SyncState.UNKNOWN`.

Each node keeps an *applied store hash* per catalog: hash of the store object it last loaded or wrote. Cleared by any non-store change (local refresh, DataLoader fallback). Drift = store hash (`SnapshotStore.describe`) vs applied hash, never vs the in-memory hash.

| Role | Store | Condition | Outcome |
|---|---|---|---|
| Follower | empty | - | `IN_SYNC` (leader has not uploaded yet) |
| Follower | hash H | H == applied | `IN_SYNC` |
| Follower | hash H | otherwise | `DRIFTED` -> reload from store (never DataLoader); clears FAILED |
| Leader | empty or H | applied present and == H | `IN_SYNC` |
| Leader | empty or H | otherwise | `DRIFTED` -> re-save current snapshot + publish `EVENT` |
| Leader | any | catalog FAILED | `DRIFTED`, no repair (nothing to publish) |

- Repairs go through the dispatcher (serialized with event reloads). Any failure (`describe`, load, save) -> `reconcileFailed`; the next pass retries. Leader repairs never publish a `REQUEST`.
- `andersoni.reconcile()`: queues a pass on the reconciler thread and returns; never queries the DataLoader; no-op when inactive; `IllegalStateException` after `stop()`. Use it instead of a manual refresh to fix a lagging node.

## 7. Observability

### status()

```java
public record AndersoniStatus(String nodeId, boolean leader, List<CatalogStatus> catalogs) {
  public boolean inSync(); // false only if some catalog is DRIFTED (UNKNOWN counts as in sync)
  public record CatalogStatus(String catalogName, boolean available, long version, String hash,
      boolean hashComparable, int itemCount, double estimatedSizeMB, SyncState syncState,
      Optional<Instant> lastReconciledAt) { }
}
public enum SyncState { IN_SYNC, DRIFTED, UNKNOWN }
```

| Field | Meaning |
|---|---|
| `available` | false if bootstrap failed (or snapshot unreadable; then version 0, hash `""`) |
| `version` | node-local counter incremented per snapshot build. Do not compare across nodes |
| `hash` | SHA-256 of the data. Compare across nodes only when `hashComparable` |
| `hashComparable` | true when the catalog has a serializer; without one the hash is built from `toString()` and is node-local |
| `syncState` | `IN_SYNC` last pass matched store; `DRIFTED` mismatch, repair dispatched/pending; `UNKNOWN` reconciliation inactive for it or no pass yet |
| `lastReconciledAt` | last pass that checked the catalog |

```java
final AndersoniStatus status = andersoni.status();
for (final AndersoniStatus.CatalogStatus c : status.catalogs()) {
  if (!c.available() || c.syncState() == SyncState.DRIFTED) {
    log.warn("catalog {} available={} state={}", c.catalogName(), c.available(), c.syncState());
  }
}
```

### CatalogInfo / IndexInfo (`andersoni.info(name)`)
`record CatalogInfo(String catalogName, int itemCount, List<IndexInfo> indices, long totalEstimatedSizeBytes, int viewCount, List<String> viewTypeNames)` + `double totalEstimatedSizeMB()`.
`record IndexInfo(String name, int uniqueKeys, int totalEntries, long estimatedSizeBytes)` + `double estimatedSizeMB()`.

### AndersoniMetrics (`org.waabox.andersoni.metrics`)

| Method | Abstract/default | Called when |
|---|---|---|
| `void snapshotLoaded(String catalogName, String source)` | abstract | bootstrap success; source is `"snapshotStore"`, `"dataLoader"` or `"followerDataLoaderFallback"` |
| `void refreshFailed(String catalogName, Throwable cause)` | abstract | bootstrap FAILED, event reload failed, scheduled refresh failed, promotion refresh failed |
| `void indexSizeReported(String catalogName, String indexName, long estimatedSizeBytes)` | abstract | per index after every bootstrap/refresh/reload |
| `void syncPublished(String catalogName)` | default no-op | leader published an `EVENT` |
| `void syncRequested(String catalogName)` | default | follower published a `REQUEST` |
| `void syncReceived(String catalogName)` | default | `EVENT` accepted for reload |
| `void syncPublishFailed(String catalogName, Throwable cause)` | default | publish of `EVENT` or `REQUEST` threw |
| `void syncReceiveFailed(Throwable cause)` | default | for transports; core never calls it |
| `void driftDetected(String catalogName)` | default | once per drifted catalog per pass |
| `void driftRepaired(String catalogName)` | default | repair succeeded |
| `void reconcileFailed(String catalogName, Throwable cause)` | default | describe or repair failed |
| `void patchApplied(String catalogName)` | default | since 1.13.0; follower applied a `PatchEvent` surgically |
| `void patchDiscarded(String catalogName)` | default | since 1.13.0; incoming patch already applied (`toHash` matched) |
| `void patchFellBack(String catalogName)` | default | since 1.13.0; patch precondition mismatch or saturated queue -> full reload |
| `void patchOversized(String catalogName, int payloadBytes)` | default | since 1.13.0; leader published a full-refresh `EVENT` because the patch exceeded `maxPatchPayloadBytes` |
| `void start(Collection<Catalog<?>> catalogs, String nodeId)` | default | end of `start()`; catalogs can be read lock-free for gauges |
| `void stop()` | default | end of `stop()` |

`NoopAndersoniMetrics` is a non-final public class implementing the 3 abstract methods empty; extend it to override only what you need:

```java
public final class LoggingMetrics extends NoopAndersoniMetrics {
  private static final Logger log = LoggerFactory.getLogger(LoggingMetrics.class);
  @Override
  public void refreshFailed(final String catalogName, final Throwable cause) {
    log.error("refresh failed for {}", catalogName, cause);
  }
  @Override
  public void driftDetected(final String catalogName) {
    log.warn("drift on {}", catalogName);
  }
}
```

## 8. Extension interfaces

```java
// org.waabox.andersoni.sync
public interface SyncStrategy {
  void publish(RefreshEvent event);          // thread-safe; must broadcast to ALL nodes incl. leader
  void subscribe(RefreshListener listener);  // called exactly once, before start()
  void start();
  void stop();
  // since 1.13.0, all defaulted: supportsPatches() -> false; publishPatch throws UnsupportedOperationException;
  // subscribePatch is a no-op. Andersoni calls subscribePatch (before start()) and publishPatch only when supportsPatches() is true.
  default boolean supportsPatches();
  default void publishPatch(PatchEvent event);
  default void subscribePatch(PatchListener listener);
}
@FunctionalInterface public interface RefreshListener { void onRefresh(RefreshEvent event); }
@FunctionalInterface public interface PatchListener { void onPatch(PatchEvent event); }   // since 1.13.0
public sealed interface SyncMessage permits RefreshEvent, PatchEvent { }                 // since 1.13.0
// SyncMessageCodec.serialize(SyncMessage) / deserialize(String): JSON with a "type" discriminator; RefreshEventCodec remains for refresh-only transports

// org.waabox.andersoni.leader
public interface LeaderElectionStrategy {
  void start();
  boolean isLeader();                              // cheap, called often
  void onLeaderChange(LeaderChangeListener listener);
  void stop();
}
@FunctionalInterface public interface LeaderChangeListener { void onLeaderChange(boolean isLeader); }
// SingleNodeLeaderElection: isLeader() always true; start() notifies listeners registered so far with true.

// org.waabox.andersoni.snapshot
public interface SnapshotStore {
  void save(String catalogName, SerializedSnapshot snapshot);   // replace existing
  Optional<SerializedSnapshot> load(String catalogName);        // empty = no snapshot
  default Optional<SnapshotMetadata> describe(String catalogName) {
    return load(catalogName).map(SnapshotMetadata::of);          // correct but downloads everything
  }
}
public interface SnapshotSerializer<T> { byte[] serialize(List<T> items); List<T> deserialize(byte[] data); }
public record SerializedSnapshot(String catalogName, String hash, long version, Instant createdAt, byte[] data) { }
public record SnapshotMetadata(String catalogName, String hash, long version, Instant createdAt) {
  public static SnapshotMetadata of(SerializedSnapshot snapshot);
}
```

Implementation rules:
- `SyncStrategy`: duplicates and redelivery are safe (self and same-hash events are ignored). Listener runs on your transport thread; it returns fast. Serialize with `SyncMessageCodec` (or `RefreshEventCodec` for a refresh-only transport). A transport that cannot carry commands must drop `isRequest()` events in `publish`. To carry patches, override `supportsPatches()` to `true`, keep per-catalog ordering between a `PatchEvent` and the events around it, and deliver both message kinds from one decoder (`SyncMessageCodec.deserialize` + `instanceof`).
- `LeaderElectionStrategy`: at most one leader; notify listeners only on real transitions; `onLeaderChange` may be called after `start()` (Andersoni registers its listener after `start()`).
- `SnapshotStore`: **override `describe`** with a metadata-only read (object headers, file header, a DB row without the blob). It runs once per catalog per node per reconciliation interval. Return the exact `hash` that was saved (Andersoni writes SHA-256 hex of the stored bytes). I/O failures: throw a `RuntimeException`; do not return empty.
- `SerializedSnapshot` defensively copies `data` on construction and on `data()`; equality is content-based.
- `SnapshotSerializer` MUST be deterministic (same items -> identical bytes on every JVM: sort map/set entries, fixed property order, no timestamps). It also produces the catalog hash. The `DataLoader` must return items in a stable order (e.g. `ORDER BY` a key) for the same reason.

Minimal custom store (compiles against core only):

```java
public final class InMemorySnapshotStore implements SnapshotStore {
  private final Map<String, SerializedSnapshot> snapshots = new ConcurrentHashMap<>();
  @Override
  public void save(final String catalogName, final SerializedSnapshot snapshot) {
    snapshots.put(catalogName, snapshot);
  }
  @Override
  public Optional<SerializedSnapshot> load(final String catalogName) {
    return Optional.ofNullable(snapshots.get(catalogName));
  }
  @Override
  public Optional<SnapshotMetadata> describe(final String catalogName) {
    return load(catalogName).map(SnapshotMetadata::of);
  }
}
```

## 9. Exceptions

All unchecked. Package `org.waabox.andersoni`.

| Exception | Parent | Thrown when |
|---|---|---|
| `CatalogNotAvailableException` | `RuntimeException` | `search`/`query`/`compound`/`graphQuery` on a catalog whose bootstrap FAILED |
| `IndexNotFoundException` | `RuntimeException` | `QueryStep` / `CompoundQuery` operation on an unknown index name. Plain `search(...)` on an unknown index returns an empty list instead |
| `UnsupportedIndexOperationException` | `RuntimeException` | range op on a non-sorted index, or text pattern op on a non-`String` key |
| `IndexKeyLimitExceededException` | `AndersoniException` | graph index produced more keys per item than `maxKeysPerItem` (during snapshot build) |
| `AmbiguousPatchTargetException` | `RuntimeException` | since 1.13.0; a patch lookup (`replace`/`remove`/`upsert`, local or `...AndSync`) matched more than one item. Nothing is changed |
| `PatchTargetNotFoundException` | `RuntimeException` | since 1.13.0; a `PatchOp.Replace` inside `apply`/`applyAndSync` matched nothing. The whole batch is abandoned |
| `AndersoniException` | `RuntimeException` | base class for infrastructure/config failures |
| `IllegalArgumentException` | JDK | unknown catalog name; duplicate `register`; invalid policy values; empty `nodeId` |
| `IllegalStateException` | JDK | `register` after `start`; second `start`; `refresh`/`refreshAndSync`/`reconcile`/patch methods after `stop`; `refresh` on a `.data(List)` catalog; `...AndSync` patch on a catalog without a serializer |

## 10. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `refreshAndSync` on a pod "does nothing" locally | Node is a follower: it only publishes a `REQUEST` | Expected; converges when the leader's `EVENT` arrives. Check `status().leader()` |
| Follower `refreshAndSync` never reaches the leader | No `syncStrategy`, or DB polling sync (drops requests), or non-broadcast transport | Use Kafka/HTTP sync, or call `refreshAndSync` on the leader, or `reconcile()` |
| `refreshEvery` never fires on some pods | Scheduled refresh is leader-only by design | Expected. Followers converge via `EVENT` / reconciliation |
| Follower's `refresh(name)` reverts after ~30s | Reconciliation restores the store's snapshot | Use `refreshAndSync` so the leader updates the store |
| `CatalogNotAvailableException` right after deploy | Bootstrap exhausted retries (DataLoader down, or follower waited `maxRetries*10` and fallback failed) | Check logs for "Marking as FAILED". Follower recovers once the store has a snapshot (reconciliation). Any successful reload clears FAILED: `refresh(name)`, leader `refreshAndSync(name)`, an EVENT-triggered reload, promotion refresh or follower store repair. In 1.12.0 only the last two (or a restart) clear it. Raise `RetryPolicy` to ride out short outages |
| `start()` blocks for a long time on followers | Follower waits for the leader's upload: `maxRetries*10` attempts x `backoff` per catalog, sequential | Ensure the leader starts/uploads; tune `RetryPolicy`; pre-seed the store |
| `status().inSync()` false / `syncState=DRIFTED` persists | Repair keeps failing (store unreachable, deserialize error) or leader catalog FAILED | Look for `reconcileFailed` metric and "repair failed" logs; fix store/serializer |
| Reconcile repairs every pass | Store `describe` returns a hash different from the saved one; or two leaders re-saving during lease handover | `describe` must return the stored `SerializedSnapshot.hash()`; ensure single leader |
| Every event triggers a reload; hashes differ across nodes with identical data | Non-deterministic serializer or unstable DataLoader order; or no serializer (`hashComparable=false`) | Deterministic serializer + stable `ORDER BY`; configure a serializer |
| Nodes ignore each other's events | Same `nodeId` on several nodes | Unique stable `nodeId` (default uses `HOSTNAME`) |
| High store traffic/cost | Custom `SnapshotStore` without `describe` override downloads full snapshots every interval | Override `describe`; or raise `ReconciliationPolicy.of(...)` interval |
| `syncState` always `UNKNOWN` | No store, `ReconciliationPolicy.disabled()`, catalog has no serializer, or first pass not run yet | Configure store + serializer |
| `IllegalStateException` "Cannot refresh a catalog created with static data" | Catalog built with `.data(List)` | Use `.loadWith(loader)`, or `catalog(name, T.class).refresh(list)` |
| `replaceAndSync`/`insertAndSync`/... returns `NOT_LEADER` and nothing changes | Node is a follower. Patches are not forwarded to the leader (no `REQUEST`) | Route the write to the leader (`status().leader()`), publish a domain event the leader consumes, or call `refreshAndSync(name)` on `NOT_LEADER` (a follower publishes a `REQUEST`; the leader reloads fully) |
| `NOT_FOUND` from `replaceAndSync`/`removeAndSync` | Lookup key/index matched no item on the leader (wrong index, wrong key type, or the item was never inserted) | Query the leader with `search(catalog, index, key)`; use `upsertAndSync` when create-or-update is meant. An `indexGraph` name never matches: patch through a plain `.index(...)` |
| Some pods stay stale after a patch; their logs show deserialization errors on sync messages | Those pods run 1.12.2 or earlier and cannot decode `PatchEvent` | Upgrade every node before calling any patch method; until then keep call sites on `refreshAndSync` |
| Every patch rebuilds the whole snapshot (`appliesPatchesSurgically()` false) | A registered `SnapshotBuildHook` (any lambda hook) has `supportsPatch()` = `false` | Override `supportsPatch()` to `true` on per-item hooks |
| Patches over HTTP sync never reach peers, peers log `413` | Body > 64 KiB rejected by `HttpSyncStrategy`, below the 256 KB default `maxPatchPayloadBytes` | `maxPatchPayloadBytes(64 * 1024)` so oversized patches degrade to a full-refresh `EVENT` |
| `refreshAndSync` slow/blocking with broker down | Transport `publish` blocks (e.g. Kafka producer metadata wait) | Raw `KafkaSyncStrategy` does not expose producer timeouts in 1.12.2 (Kafka default `max.block.ms` = 60s applies). With Spring Kafka sync, define your own `ProducerFactory<String, String>` bean named `andersoniProducerFactory` (the auto-config backs off by name) and set `max.block.ms` / `delivery.timeout.ms` there. Call `refreshAndSync` off the request thread. |
