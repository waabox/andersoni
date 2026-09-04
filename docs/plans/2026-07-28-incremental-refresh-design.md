# Incremental Refresh — Design

**Date:** 2026-07-28
**Status:** Draft, ready for implementation
**Scope:** `andersoni-core` (library) + `stadium-service` (adopter)

## 1. Overview & Goals

### Motivation

Today, every event in a producer service (e.g. `catalog-service`) triggers a full refresh of the consumer's catalog: all entities fetched again, the full snapshot Kryo-serialized, uploaded to S3, and then downloaded + deserialized in every follower. End-to-end latency in `stadium-service` today: 25–80s per change, depending on burst pattern. Most of that work is waste — typically only 1 entity changed.

### Goal

For N=1 (95% of real changes) and N≤5 (bursts), end-to-end propagation must drop to **~2–4 seconds** without sacrificing bootstrap correctness (new pods must start from an up-to-date S3 snapshot).

### Non-goals

- Not changing the upstream producer's Kafka contract (event/frame/layout topics stay as-is).
- Not moving snapshots into the Kafka sync topic (a separate future iteration).
- Not switching Andersoni to a streaming/event-sourced model. Snapshot + sync events model is preserved.
- Not supporting "entity ID changed" (assumed immutable, matches production reality).

### Constraints

- **Backward compatibility (source)**: catalogs that only implement `DataLoader.load()` keep working via full refresh path.
- **Backward compatibility (wire)**: mixed clusters (some pods on old Andersoni, others on new) work without corruption.
- **Self-healing**: if any incremental fails, fall back to full refresh.
- **No infrastructure changes**: same Kafka, same S3, same leader/follower model.

### Expected impact

| Metric | Before | After |
|---|---|---|
| End-to-end propagation (1 change) | 25–50s | **~2–4s** |
| End-to-end propagation (burst of 5) | 30–80s | **~4–6s** |
| DataLoader calls per refresh | N (all entities) | 1–5 |
| S3 writes/hour | ~60 | ~60–240 |
| Kafka sync bytes/event | ~1KB | ~1–2KB |

---

## 2. Architecture & Flow

### Roles (unchanged)

- **Leader** (one per catalog, K8s Lease): only pod that writes to S3 and publishes sync events.
- **Followers**: receive sync events, apply changes locally.
- **Sync topic** (Kafka): leader → followers channel.

### Happy-path flow (incremental)

```
Admin edits entity X in upstream service
     │
     ▼
Upstream publishes to Kafka: <upstream>.entity
     │
     ▼ (received by ALL N pods)
Consumer's message handler
     │
     ▼
Per-entity debouncer (1s window)
  · pendingUpserts.add(X)  |  pendingRemovals.add(X)
     │
     ▼
flush() → snapshotService.refreshEntities(pending)
     │
     ▼
andersoni.refreshEntities(catalogName, ids)
     │
     ├─── isLeader? NO → publishRefreshRequest(catalogName, ids) → return
     │
     └─── isLeader? YES:
              ▼
          parallel: for each id → dataLoader.loadOne(id)
              ▼
          snapshot rebuild in-place (structural sharing, only affected buckets)
              ▼
          AtomicReference.set(newSnapshot)  // atomic swap
              ▼
          saveSnapshotIfPossible()  // full snapshot to S3, sync
              ▼
          syncStrategy.publish(RefreshEvent{
              catalogName, sourceNodeId, version, hash, timestamp,
              upsertedIds: [X, Y], removedIds: []
          })
     │
     ▼ (followers receive via sync topic)
refreshFromEvent() — new path
     │
     ▼
  · event.upsertedIds non-empty?
        → parallel: for each id → dataLoader.loadOne(id)
        → apply upsert in-place
  · event.removedIds non-empty?
        → remove from snapshot in-place
  · verify hash matches (integrity check)
        · matches → done
        · mismatch → fallback to tryLoadFromSnapshotStore (S3)
```

### What disappears (for 95% of cases)

- Leader: `dataLoader.load()` for all entities (18s → **~500ms**)
- Followers: Kryo download+deserialize of full snapshot (~2–3s each → **~500ms** for `loadOne` + apply)

### What stays the same

- Leader writes full snapshot to S3 (for bootstrap correctness of new pods)
- Coordination via `RefreshEvent` in the sync topic

---

## 3. Andersoni Library — API Changes

### `DataLoader<T>` — opt-in method

```java
public interface DataLoader<T> {
  /** Full load — existing, unchanged. */
  List<T> load();

  /**
   * Fetch a single entity by ID. Returns Optional.empty() if the entity was
   * deleted or not found. Default throws UnsupportedOperationException;
   * implementing this method enables incremental refresh for this catalog.
   */
  default Optional<T> loadOne(String id) {
    throw new UnsupportedOperationException(
        "Incremental refresh not supported by this DataLoader");
  }
}
```

### `Catalog<T>` — new builder step + new APIs

Builder — new optional `identifiedBy` step:

```java
Catalog.of(Stadium.class)
    .named("stadiums")
    .loadWith(dataLoader)
    .identifiedBy(Stadium::getEventCode)   // NEW — optional
    .serializer(...)
    .index("by-code").by(Stadium::getCode, code -> code)
    ...
    .build();
```

If `identifiedBy` is not declared, `refreshEntity`/`removeEntity` throw `UnsupportedOperationException`. Legacy consumers keep working through full refresh only.

New methods on `Catalog<T>`:

```java
public void refreshEntity(String id);
public void refreshEntities(Collection<String> ids);
public void removeEntity(String id);
public void removeEntities(Collection<String> ids);
```

Internally batch-first: single-entity methods delegate to their batch counterparts.

### `Andersoni` — parallel entry points

```java
public void refreshEntity(String catalogName, String id);
public void refreshEntities(String catalogName, Collection<String> ids);
public void removeEntity(String catalogName, String id);
public void removeEntities(String catalogName, Collection<String> ids);
```

Semantics identical to `refreshAndSync`: check `isLeader`; if not, publish a refresh request to the sync topic with the IDs; if leader, execute locally.

### `Snapshot<T>` — internal derivation methods

```java
Snapshot<T> withUpserts(List<T> upserted, Function<T, ?> idFn);
Snapshot<T> withRemovals(Collection<String> ids, Function<T, ?> idFn);
```

Structural sharing: unchanged items share references with the previous snapshot; only affected index buckets are rebuilt. Avoids O(N) copying for O(1) changes.

---

## 4. Consumer (stadium-service) Changes

### 1. `StadiumDataLoader` — implement `loadOne`

```java
public class StadiumDataLoader implements DataLoader<Stadium> {
  @Override
  public List<Stadium> load() { ... existing ... }

  @Override
  public Optional<Stadium> loadOne(String eventCode) {
    try {
      Stadium stadium = assembler.assembleSingleStadium(eventCode);
      return Optional.ofNullable(stadium);
    } catch (StadiumNotFoundException e) {
      return Optional.empty();
    }
  }
}
```

Add `assembleSingleStadium(String eventCode)` to `StadiumSnapshotAssembler`. Most logic already exists in `getStadium(EventResponse)`; needs a single `EventResponse` fetch.

### 2. Register `identifiedBy` on the Catalog bean

In `StadiumServiceConfiguration.stadiumCatalog(...)`:

```java
return Catalog.of(Stadium.class)
    .named("stadiums")
    .loadWith(dataLoader)
    .identifiedBy(Stadium::getEventCode)   // added
    .serializer(serializer)
    .index(...)...
    .build();
```

### 3. `CatalogMessageHandler` — per-entity debouncer

Replace the global debouncer with per-entity coalescing. Two sets: `pendingUpserts` and `pendingRemovals`. Kafka handlers add to the appropriate set based on `action`/`type`. A single scheduled flush (1s window) drains both and calls `refreshEntities` / `removeEntities`.

Debounce window: **1 second** fixed. If it causes churn, raise to 2s.

`SnapshotService` gains two methods (`refreshEntities`, `removeEntities`) that delegate to the corresponding `Andersoni` method.

---

## 5. Wire Protocol & Backward Compatibility

### Extended `RefreshEvent`

```java
public final class RefreshEvent {
  private final String catalogName;
  private final String sourceNodeId;
  private final long version;
  private final String hash;
  private final Instant timestamp;
  private final boolean request;
  private final List<String> upsertedIds;   // NEW
  private final List<String> removedIds;    // NEW

  // Deserialization: new fields default to Collections.emptyList() if absent.
}
```

### Interpretation rules

| `request` | `upsertedIds` | `removedIds` | Meaning |
|:-:|:-:|:-:|---|
| `true` | (any) | (any) | Follower asking leader to refresh (legacy semantic; IDs indicate what to fetch) |
| `false` | empty | empty | **Full refresh** (legacy behavior — download from S3 and swap) |
| `false` | non-empty | (any) | Incremental: `loadOne` for each upserted ID |
| `false` | (any) | non-empty | Incremental: remove each removed ID |

### Compat matrix

| Leader | Follower | Behavior |
|---|---|---|
| Old | Old | Full refreshes as before |
| Old | New | Legacy events (no ID fields) → treated as full → OK |
| New | Old | New event fields ignored by old codec (`ignoreUnknown=true` default) → treated as full → correct but loses the incremental benefit |
| New | New | Full incremental as designed |

Rolling deploys are safe: no window of corruption.

### Codec

`RefreshEventCodec` uses Jackson JSON. Adding two fields is a constructor + getters change. No schema registry, no topic changes.

### Hash still meaningful

The `hash` remains the full-snapshot hash after the swap. Followers use it for integrity: after applying the incremental locally, they recompute the hash and compare. Mismatch → fallback to snapshot store.

---

## 6. Error Handling, Fallback, Concurrency

### Retry with backoff → full fallback

```java
for attempt in [1, 2, 3]:
    sleep(100ms * 2^(attempt-1))
    try loadOne(id) → break on success
if all failed:
    log.warn(...); metrics.incrementalFallback(...);
    catalog.refresh();   // full via existing DataLoader.load()
```

Rate-limit the full fallback: at most one per `refreshInterval`. If a second incremental fails inside the same window, skip the full (log + metric only). Prevents "fallback storm" when the upstream is intermittent.

### Version gap detection (followers)

```java
long localVersion = catalog.currentSnapshot().version();
if (event.version - localVersion > 1) {
    log.warn("Version gap detected: local={}, event={}, catching up via S3", ...);
    metrics.versionGap(...);
    tryLoadFromSnapshotStore(...);
    return;
}
// Normal path: apply incremental.
```

### Integrity check post-apply (followers)

```java
String newHash = catalog.currentSnapshot().hash();
if (!newHash.equals(event.hash())) {
    log.warn("Hash mismatch after incremental apply...");
    metrics.incrementalHashMismatch(...);
    tryLoadFromSnapshotStore(...);
}
```

### Concurrency

Andersoni's `refreshLock` (existing per-catalog `ReentrantLock`) protects the swap. `loadOne` calls happen **before** the lock (parallel I/O); only the snapshot swap is inside. Multiple `refreshEntities` calls in flight serialize on the lock — each sees the result of the previous. No lost updates.

### AsyncRefreshDispatcher

Unchanged for full refreshes (still coalesces). Incrementals bypass the dispatcher — each `refreshEntities` competes for `refreshLock` directly. Fast enough (~500ms critical section).

---

## 7. Testing & Rollout

### Library tests

**Unit:**
- `SnapshotTest.withUpserts_addsNewItem_reindexesAffectedBucketsOnly`
- `SnapshotTest.withUpserts_replacesExistingItem_removesOldBucketEntries`
- `SnapshotTest.withRemovals_removesFromAllIndexes`
- `SnapshotTest.withUpserts_structuralSharing_unchangedItemsSameReference`
- `CatalogTest.refreshEntities_leader_callsLoadOne_swapsSnapshot`
- `CatalogTest.refreshEntities_followerReceivesEvent_appliesLocally`
- `CatalogTest.refreshEntities_loadOneFails_retriesThenFallsBackToFull`
- `RefreshEventCodecTest.deserializeLegacyEvent_missingIdFields_treatsAsFullRefresh`

**Integration (`andersoni-cluster-it`):**
- 3-node cluster; leader `refreshEntities([X])`; followers converge to same hash without S3 download.
- Kill leader mid-refresh; new leader elected; completes the work.
- Follower version gap (2 events missed) → detects + falls back to S3.

### Consumer tests

- `CatalogMessageHandlerTest.upsertsAndRemovalsDebounced_flushedTogetherAfter1s`
- `StadiumDataLoaderTest.loadOne_notFound_returnsEmpty`
- `StadiumSnapshotAssemblerTest.assembleSingleStadium_matchesFullRefreshResult`
- Contract test: after N incrementals, `snapshot.data()` matches what a full `load()` would produce.

### Rollout order

No feature flag needed — wire compat is validated in §5.

1. **Andersoni library**: merge → publish `1.12.0`.
2. **stadium-service**: bump to `1.12.0`, add `.identifiedBy(...)`, add `loadOne`, refactor `CatalogMessageHandler`, but keep calling `refreshAndSync` (full). Deploy.
3. **Verify in dev/staging**: 24h, confirm full refreshes still work under new lib.
4. **Switch to incremental**: change `SnapshotService` call sites to `refreshEntities`/`removeEntities`. Deploy.
5. **Watch metrics** (below). Rollback = revert step 4.

### New metrics

- `andersoni.catalog.incremental.duration` (histogram): end-to-end from Kafka message to swap.
- `andersoni.catalog.incremental.load_one_duration` (histogram)
- `andersoni.catalog.incremental.fallback.count` (counter, tag `reason=load_error|version_gap|hash_mismatch`)
- `andersoni.catalog.incremental.batch_size` (histogram): IDs per `refreshEntities` call.

### Rollback

- **Consumer** (code): revert step 4 commit. Full refresh returns. Library 1.12.0 stays.
- **Library** (rare): downgrade to 1.11.0. All pods restart. Full pre-refactor behavior returns.
