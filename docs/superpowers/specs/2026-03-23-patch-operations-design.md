# Patch Operations Design

**Date**: 2026-03-23
**Branch**: `feature/3/patch-support`
**Status**: Draft

## Overview

Add granular patch operations (add, update, upsert, remove) to Andersoni catalogs, enabling element-level mutations without full data reloads. Patches are synced across nodes via the existing `SyncStrategy` interface, extended to carry both refresh and patch events through a visitor-based event hierarchy.

## Goals

1. Support add, update, upsert, and remove operations on individual catalog items
2. Broadcast patch operations to other nodes via the sync layer (granular sync)
3. Evolve the `SyncStrategy` interface to handle multiple event types without instanceof/switch chains
4. Maintain backwards compatibility with full refresh operations

## Non-Goals (Future Work)

- WAL (Write-Ahead Log) for crash recovery and follower catch-up
- Strict ordering of patch events (sequence numbers, buffering, gap detection)
- Incremental index updates (surgical index mutations instead of full rebuild)
- Leader-only patch enforcement (follower rejection or forwarding)
- Optimized remove-by-key-only (patch events carry full objects for now)

## Breaking Changes

This design introduces the following breaking changes:

1. **`SyncStrategy` interface** — method signatures change from `RefreshEvent`/`RefreshListener` to `SyncEvent`/`SyncListener`. All implementations must be updated.
2. **`RefreshEvent` record** — now implements `SyncEvent` and adds an `accept()` method. This is a binary-incompatible change; compiled code referencing the old `RefreshEvent` will fail at runtime.
3. **`RefreshListener` interface** — replaced by `SyncListener`. Existing listener implementations must be updated.
4. **`RefreshEventCodec`** — replaced by `SyncEventCodec`. The JSON format adds a `"type"` discriminator field (see Section 5).

These changes require a major or minor version bump.

## Consistency Model

Patch operations across nodes provide **best-effort eventual consistency**. Without strict ordering:
- Patches are applied as they arrive, in whatever order the transport delivers them.
- Add/remove/update are close to idempotent by identity, but drift can occur (e.g., a `REMOVE` arrives at a node that never received the preceding `ADD`).
- Receiving-side errors (duplicate on `add`, missing on `remove`) are swallowed and logged — they indicate drift, not bugs.
- **Periodic full refreshes are the reconciliation mechanism.** Scheduled refreshes (which already exist) will bring all nodes back to a consistent state.

Users must understand that between full refreshes, node snapshots may diverge slightly.

## Design

### 1. Identity Function

An optional `Function<T, Object>` on Catalog that extracts a unique key from each item.

**DSL** (placed on `BuildStep`, alongside `serializer()` and `refreshEvery()`):
```java
Catalog.of(Event.class)
  .named("events")
  .loadWith(loader)
  .identifiedBy(Event::getId)   // optional, on BuildStep
  .serializer(new EventSerializer())
  .index("by-venue").by(Event::getVenue, Venue::getName)
  .build()
```

**Behavior**:
- If set: Snapshot maintains an internal `Map<Object, T>` (identity map) for O(1) lookups by key, built during `buildAndSwapSnapshot`
- If not set: full refresh works as today; calling any patch method throws `IllegalStateException`
- Identity keys must be non-null (enforced at snapshot build time)
- Duplicate identity keys: last-write-wins (later item in the list replaces earlier)

### 2. Patch API on Catalog

Local-only operations. All require the identity function to be configured.

```java
// Add — throws IllegalArgumentException if identity key already exists
catalog.add(item)
catalog.add(Collection<T> items)

// Update — throws IllegalArgumentException if identity key not found
catalog.update(item)
catalog.update(Collection<T> items)

// Upsert — adds or replaces by identity key, no precondition
catalog.upsert(item)
catalog.upsert(Collection<T> items)

// Remove — throws IllegalArgumentException if identity key not found
catalog.remove(item)
catalog.remove(Collection<T> items)

// Lookup by identity key — returns Optional<T>
catalog.findById(key)
```

**Semantics**:
- All operations are atomic (all-or-nothing): validation runs against all items before applying any
- All operations acquire the existing `refreshLock`
- Application flow: validate preconditions against identity map → build new data list → call `buildAndSwapSnapshot(newData)` (full index rebuild)
- `findById(key)`: lock-free read from the current snapshot's identity map. Returns `Optional.empty()` if not found or if no identity function is configured.

### 3. Snapshot Changes

New fields when identity function is present:
- `Map<Object, T> identityMap` — keyed by identity function output, for O(1) lookups and validation
- `Function<T, Object> identityFunction` — stored so patch operations can extract keys from incoming items

The identity map is `null` when no identity function is configured. Built during `buildAndSwapSnapshot` alongside existing indices.

### 4. SyncEvent Hierarchy

Replace the standalone `RefreshEvent` with a polymorphic event hierarchy using the visitor pattern.

**SyncEvent interface**:
```java
public interface SyncEvent {
    String catalogName();
    String sourceNodeId();
    long version();
    Instant timestamp();

    void accept(SyncEventHandler handler);
}
```

**SyncEventHandler interface**:
```java
public interface SyncEventHandler {
    void onRefresh(RefreshEvent event);
    void onPatch(PatchEvent event);
}
```

**RefreshEvent** (updated from current standalone record):
```java
public record RefreshEvent(
    String catalogName,
    String sourceNodeId,
    long version,
    String hash,
    Instant timestamp
) implements SyncEvent {

    @Override
    public void accept(SyncEventHandler handler) {
        handler.onRefresh(this);
    }
}
```

**PatchEvent** (new):
```java
public record PatchEvent(
    String catalogName,
    String sourceNodeId,
    long version,
    PatchOperation operationType,
    byte[] payload,
    Instant timestamp
) implements SyncEvent {

    @Override
    public void accept(SyncEventHandler handler) {
        handler.onPatch(this);
    }
}
```

**PatchOperation enum**:
```java
public enum PatchOperation {
    ADD, UPDATE, UPSERT, REMOVE
}
```

**Serialization note**: Single-item patch operations are wrapped into a singleton list before serialization. The `payload` field always contains a serialized `List<T>`, using the catalog's `SnapshotSerializer`. Receivers always deserialize as `List<T>` and apply accordingly.

### 5. SyncStrategy Interface Changes

**Breaking change**: The `SyncStrategy` interface is updated to use `SyncEvent` and `SyncListener` instead of `RefreshEvent` and `RefreshListener`. The old interface is removed.

**Updated SyncStrategy**:
```java
public interface SyncStrategy {
    void publish(SyncEvent event);
    void subscribe(SyncListener listener);
    void start();
    void stop();
}
```

**SyncListener** (replaces RefreshListener):
```java
public interface SyncListener {
    void onEvent(SyncEvent event);
}
```

**SyncEventCodec** (replaces RefreshEventCodec): Handles serialization/deserialization of both event types.

- **Discriminator field**: `"type"` at the root of the JSON object
- **Values**: `"REFRESH"` for `RefreshEvent`, `"PATCH"` for `PatchEvent`
- **Rolling upgrade**: The new codec is NOT backwards-compatible with the old `RefreshEventCodec` JSON format (which has no `"type"` field). All nodes must be upgraded simultaneously. This is acceptable given the breaking nature of this release.

### 6. SyncStrategy Implementations

All four existing implementations are updated (same find-and-replace change):
- `RefreshListener` → `SyncListener`
- `RefreshEvent` → `SyncEvent`
- `RefreshEventCodec` → `SyncEventCodec`
- `listener.onRefresh(event)` → `listener.onEvent(event)`

The implementations are dumb pipes — they serialize, transport, deserialize, and notify. They never inspect the event type. Routing happens in Andersoni via the visitor pattern.

| Module | Class |
|--------|-------|
| `andersoni-sync-kafka` | `KafkaSyncStrategy` |
| `andersoni-spring-sync-kafka` | `SpringKafkaSyncStrategy` |
| `andersoni-sync-http` | `HttpSyncStrategy` |
| `andersoni-sync-db` | `DbPollingSyncStrategy` |

### 7. Andersoni Orchestration

**New methods on Andersoni** (mirror `refreshAndSync` pattern):
```java
andersoni.addAndSync(catalogName, item)
andersoni.addAndSync(catalogName, Collection<T> items)

andersoni.updateAndSync(catalogName, item)
andersoni.updateAndSync(catalogName, Collection<T> items)

andersoni.upsertAndSync(catalogName, item)
andersoni.upsertAndSync(catalogName, Collection<T> items)

andersoni.removeAndSync(catalogName, item)
andersoni.removeAndSync(catalogName, Collection<T> items)
```

**Flow** (same pattern as `refreshAndSync`):
1. Call the corresponding `catalog.add/update/upsert/remove(item)` (local apply)
2. If `SyncStrategy` is configured, serialize items using the catalog's `SnapshotSerializer` and publish a `PatchEvent`. Single items are wrapped in a singleton list for serialization.
3. No leadership check (matches current `refreshAndSync` behavior)

**Receiving side**: Andersoni implements `SyncEventHandler`. The current `wireSyncListener` method is reworked to subscribe using the visitor pattern:

```java
// In wireSyncListener():
syncStrategy.subscribe(event -> event.accept(syncEventHandler));

// Andersoni implements SyncEventHandler:
@Override
public void onRefresh(RefreshEvent event) {
    // existing logic: skip if from self, hash check, dispatch full refresh
}

@Override
public void onPatch(PatchEvent event) {
    // skip if from self, deserialize items, apply patch locally
}
```

**Serializer requirement**: If `SyncStrategy` is configured and a catalog has an identity function, the catalog must have a `SnapshotSerializer`. Validated at bootstrap time — fail fast with a clear error.

### 8. Async Dispatch for Patches

Incoming patch events are dispatched to virtual threads with per-catalog semaphore (serial execution per catalog), but **no coalescing**. Every patch event gets applied.

The existing `AsyncRefreshDispatcher` gains a new `dispatchPatch(catalogName, Runnable)` method that uses the same per-catalog semaphore but bypasses the coalescing `AtomicBoolean`. This ensures:
- Patches and refreshes for the same catalog are serialized (share the semaphore)
- Patches are never coalesced (every patch is applied)
- Refreshes continue to coalesce as today

**Refresh/patch interaction**: If a full refresh coalesces away while a patch is being applied, this is acceptable — the full refresh would have overwritten the patch result anyway. If a patch arrives while a full refresh is in progress, the patch waits on the semaphore and applies after the refresh completes. The patch may then conflict (e.g., adding an item the refresh already included), which is handled by the receiving-side error policy (swallow and log).

### 9. Hash Divergence

After a local patch, `buildAndSwapSnapshot` recomputes the SHA-256 hash over the entire dataset. This changes the snapshot hash. If a `RefreshEvent` arrives with a hash matching the pre-patch state, the hash comparison will fail and trigger an unnecessary full refresh. This is expected and acceptable — full refreshes are idempotent and the reconciliation is correct.

### 10. Error Handling

**Local patch errors** (on Catalog):
- No identity function configured → `IllegalStateException`
- `add` with duplicate key → `IllegalArgumentException`
- `update` with missing key → `IllegalArgumentException`
- `remove` with missing key → `IllegalArgumentException`
- All-or-nothing: validation runs against all items before applying any

**Sync publish errors** (on Andersoni):
- Same pattern as `refreshAndSync`: log the error, report to metrics, don't rethrow. The local patch already succeeded.

**Receiving side errors**:
- Deserialization fails → log + metrics, skip the event
- Patch application fails (e.g., `add` but item already exists locally due to drift) → swallow and log. See Consistency Model section.

### 11. Metrics

The `AndersoniMetrics` interface gains new methods for patch operations:
- `patchApplied(String catalogName, PatchOperation operation)` — local patch succeeded
- `patchFailed(String catalogName, PatchOperation operation, Exception e)` — local patch failed
- `patchPublished(String catalogName, PatchOperation operation)` — patch event published to sync
- `patchPublishFailed(String catalogName, PatchOperation operation, Exception e)` — publish failed
- `patchReceived(String catalogName, PatchOperation operation)` — patch event received from sync

The `NoopAndersoniMetrics` implementation provides empty defaults.

### 12. Builder DSL Changes

**Catalog builder** — one new optional step on `BuildStep`:
```java
Catalog.of(Event.class)
  .named("events")
  .loadWith(loader)
  .identifiedBy(Event::getId)      // new, optional, on BuildStep
  .serializer(new EventSerializer())
  .build()
```

**Andersoni builder** — no changes. The existing `syncStrategy(...)` now handles both refresh and patch events through the updated interface.

## Modules Affected

| Module | Changes |
|--------|---------|
| `andersoni-core` | Identity function, patch API, findById, SyncEvent hierarchy, SyncEventHandler, SyncListener, SyncEventCodec, PatchOperation, updated SyncStrategy interface, Andersoni orchestration methods, async dispatch, metrics interface |
| `andersoni-sync-kafka` | Update to new SyncStrategy interface (SyncEvent/SyncListener/SyncEventCodec) |
| `andersoni-spring-sync-kafka` | Update to new SyncStrategy interface + auto-configuration |
| `andersoni-sync-http` | Update to new SyncStrategy interface |
| `andersoni-sync-db` | Update to new SyncStrategy interface |
| `andersoni-spring-boot-starter` | Auto-configuration for identity function (if applicable) |
| `andersoni-metrics-datadog` | New patch metrics methods |

## Forward Compatibility

The design supports future evolution without API or protocol changes:
- **Incremental index updates**: Replace `buildAndSwapSnapshot` with surgical index mutations inside Catalog — no API change
- **WAL**: Append patch operations to a durable log before applying — transparent to callers
- **Strict ordering**: Events already carry version numbers; add buffering and gap detection at the Andersoni level
- **Leader-only patches**: Change Andersoni's `*AndSync` methods to check leadership and reject/forward — one-line policy change
- **Optimized remove by key only**: Change `PatchEvent` payload format — codec change only
- **Codec versioning**: If `PatchEvent` evolves (e.g., adding a sequence field), a codec version field can be added to the JSON format for schema evolution
