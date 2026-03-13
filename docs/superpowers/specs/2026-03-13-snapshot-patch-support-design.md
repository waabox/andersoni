# Snapshot Patch Support

**Branch:** `feature/3/snapshot-patch-support`
**Date:** 2026-03-13

## Problem

Currently, every data change in Andersoni triggers a full snapshot rebuild: all
indices are recomputed from scratch, every node calls its DataLoader (or reloads
from the SnapshotStore), and the entire data set is re-indexed. For large
catalogs, this is wasteful when only a single item is added or removed.

## Goal

Allow any node to add or remove individual items (or small batches) from a
catalog snapshot without rebuilding all indices. In practice, the leader
typically initiates patches, but the API does not enforce leader-only access.
The patch is applied locally, broadcast to other nodes (who also apply it
without a full rebuild), and the originating node persists the updated full
snapshot to the SnapshotStore for bootstrap purposes.

## Design

### 1. Core Event Model

#### SyncEvent Interface (new)

```java
package org.waabox.andersoni.sync;

public interface SyncEvent {
    String catalogName();
    String sourceNodeId();
    long version();
    String hash();
    Instant timestamp();
    void accept(SyncEventVisitor visitor);
}
```

Common fields are accessible via interface methods. Each event type implements
`accept()` to dispatch via the visitor pattern.

#### SyncEventVisitor Interface (new)

```java
package org.waabox.andersoni.sync;

public interface SyncEventVisitor {
    void visit(RefreshEvent event);
    void visit(PatchEvent event);
}
```

#### RefreshEvent (modified)

Changes from a standalone record to a record implementing `SyncEvent`. Same
fields as today, adds `accept(SyncEventVisitor)` implementation:

```java
public record RefreshEvent(
    String catalogName,
    String sourceNodeId,
    long version,
    String hash,
    Instant timestamp
) implements SyncEvent {

    @Override
    public void accept(SyncEventVisitor visitor) {
        visitor.visit(this);
    }
}
```

#### PatchType Enum (new)

```java
package org.waabox.andersoni.sync;

public enum PatchType {
    ADD,
    REMOVE
}
```

#### PatchEvent Record (new)

```java
package org.waabox.andersoni.sync;

public record PatchEvent(
    String catalogName,
    String sourceNodeId,
    long version,
    String hash,
    Instant timestamp,
    PatchType patchType,
    byte[] items
) implements SyncEvent {

    @Override
    public void accept(SyncEventVisitor visitor) {
        visitor.visit(this);
    }
}
```

The `items` field contains the affected items serialized via the catalog's
`SnapshotSerializer` (always as a list, even for single items).

**Note:** `PatchEvent` contains a `byte[]` field, which means the
record-generated `equals()` and `hashCode()` use identity comparison for that
field. This is acceptable because `PatchEvent` instances are not expected to be
compared, stored in sets, or used as map keys.

#### SyncEventListener (new, replaces RefreshListener)

```java
package org.waabox.andersoni.sync;

@FunctionalInterface
public interface SyncEventListener {
    void onEvent(SyncEvent event);
}
```

#### SyncStrategy (modified)

Breaking changes:

- `publish(RefreshEvent)` becomes `publish(SyncEvent)`
- `subscribe(RefreshListener)` becomes `subscribe(SyncEventListener)`

```java
public interface SyncStrategy {
    void publish(SyncEvent event);
    void subscribe(SyncEventListener listener);
    void start();
    void stop();
}
```

`RefreshListener` is removed.

### 2. Index Key Extraction

Each index definition type exposes a package-private method for extracting the
key(s) from a single item. This allows `Catalog` to patch individual index
entries without rebuilding the entire index.

- `IndexDefinition<T>` adds: `Object extractKey(T item)`
- `SortedIndexDefinition<T>` adds: `Object extractKey(T item)`
- `MultiKeyIndexDefinition<T>` adds: `List<?> extractKeys(T item)`

These methods delegate to the existing internal `keyExtractor` /
`keysExtractor` functions.

### 3. Catalog Patch API

New methods on `Catalog<T>`:

```java
public void add(T item)
public void addAll(List<T> items)
public void remove(T item)
public void removeAll(List<T> items)
```

No predicate-based methods on `Catalog`. Predicate resolution is handled at the
`Andersoni` level.

#### Patch Logic (add)

1. Acquire `refreshLock`
2. Get current snapshot data list
3. Build new data list: `newData = currentData + item(s)`
4. For each `IndexDefinition`: extract key, copy existing index map, append
   item to the list at that key (or create a new entry)
5. For each `SortedIndexDefinition`: same as above but patches up to three
   maps: hash index (always), sorted index (if key is non-null), and
   reversed-key index (only when `hasStringKeys()` is true)
6. For each `MultiKeyIndexDefinition`: extract multiple keys, patch each key
   entry
7. Increment version, compute new hash (see Known Trade-offs)
8. Create new immutable `Snapshot`, atomically swap
9. Release `refreshLock`

#### Patch Logic (remove)

1. Acquire `refreshLock`
2. Get current snapshot data list
3. Build new data list: `newData = currentData - item(s)` (matched via
   `equals()`)
4. For each index: extract key, copy index map, remove item from the list at
   that key. If the list becomes empty, remove the key entry entirely.
5. Same for sorted and multi-key indices
6. Increment version, compute new hash
7. Create new immutable `Snapshot`, atomically swap
8. Release `refreshLock`

Unmodified index entries are shared references from the previous snapshot (no
unnecessary copying).

#### Snapshot Factory for Patching

The current `Snapshot.of()` performs deep defensive copies of all index maps
and the data list. This would negate the performance benefit of patching since
every unmodified entry would be copied unnecessarily.

A new package-private factory method is added to `Snapshot`:

```java
static <T> Snapshot<T> ofPreBuilt(
    List<T> data,
    Map<String, Map<Object, List<T>>> indices,
    Map<String, NavigableMap<Comparable<?>, List<T>>> sortedIndices,
    Map<String, NavigableMap<String, List<T>>> reversedKeyIndices,
    long version, String hash)
```

This factory accepts pre-built, already-immutable maps and wraps them without
defensive copying. It is the caller's responsibility (i.e., `Catalog`'s patch
methods) to ensure the maps are properly constructed and immutable. This method
is package-private to prevent misuse from outside the core package.

### 4. Andersoni Patch API

New methods on `Andersoni`:

```java
public <T> void add(String catalogName, T item, Class<T> type)
public <T> void addAll(String catalogName, List<T> items, Class<T> type)
public <T> void remove(String catalogName, T item, Class<T> type)
public <T> void removeAll(String catalogName, List<T> items, Class<T> type)
public <T> void removeAll(String catalogName, Predicate<T> predicate, Class<T> type)
```

#### Leader Flow (e.g., `add`)

1. Get the catalog, require a `SnapshotSerializer` (throw
   `IllegalStateException` if absent)
2. Patch local snapshot via `catalog.add(item)`
3. Serialize the item: `serializer.serialize(List.of(item))`
4. Publish `PatchEvent(catalogName, nodeId, version, hash, timestamp, ADD,
   serializedBytes)`
5. Save full updated snapshot to `SnapshotStore`

#### Leader Flow (predicate removal)

1. Scan `catalog.currentSnapshot().data()`, collect items matching the
   predicate
2. If no matches, no-op
3. Call `catalog.removeAll(matchingItems)`
4. Serialize matching items, publish `PatchEvent` with `REMOVE`
5. Save full updated snapshot to `SnapshotStore`

#### Follower Flow (receiving PatchEvent)

1. Validate version: if `catalog.currentSnapshot().version()` is not
   `event.version() - 1`, the follower is out of sync. Fall back to a full
   refresh (load from snapshot store or DataLoader), same as `RefreshEvent`
   handling.
2. Deserialize the items: `serializer.deserialize(event.items())`
3. Based on `patchType`: call `catalog.add/addAll` or `catalog.remove/removeAll`
4. No snapshot store interaction, no DataLoader call

#### Sync Listener Update

The existing `wireSyncListener()` in `Andersoni` changes from a lambda
receiving `RefreshEvent` to an implementation using `SyncEventVisitor`:

- `visit(RefreshEvent)`: same logic as today (load from snapshot store or
  DataLoader)
- `visit(PatchEvent)`: deserialize items, apply patch to local catalog

### 5. Error Handling & Edge Cases

- **Serializer required:** `Andersoni.add/remove` throws
  `IllegalStateException` if the catalog has no `SnapshotSerializer`
  configured.
- **Remove item not found:** No-op. No snapshot swap, no event published.
- **Predicate matches nothing:** No-op.
- **Idempotent application on followers:** Remove is a no-op for items not
  found (matched via `equals()`). Add does not check for duplicates — it is
  the caller's responsibility to avoid adding items that already exist.
- **Follower version mismatch:** If a follower receives a `PatchEvent` and
  its current snapshot version is not `event.version() - 1`, it falls back
  to a full refresh (load from snapshot store or DataLoader). This handles
  missed patches and out-of-order delivery.
- **Follower falls behind on RefreshEvent:** The hash comparison on
  `RefreshEvent` handling covers this, same as today.
- **Single-node mode (no SyncStrategy):** Patching works locally, no event
  published. Same pattern as `refreshAndSync` today.

### 6. Known Trade-offs

- **Hash computation remains O(n):** After a patch, `computeHash(data)` still
  serializes or iterates the full data list. This is needed for correctness —
  the hash must be deterministic and match across all nodes regardless of how
  the snapshot was constructed (full rebuild vs patch). For most catalogs this
  is fast, but for very large catalogs it remains the dominant cost of a patch
  operation.
- **Snapshot store save is synchronous:** After patching, the full snapshot is
  saved to the `SnapshotStore` synchronously on the caller's thread, same as
  `refreshAndSync` today. For large catalogs with slow stores (e.g., S3), this
  adds latency to the patch call.

### 7. Breaking Changes

This feature introduces breaking changes to the `SyncStrategy` interface and
related types. All sync modules must be updated:

- `andersoni-sync-kafka`
- `andersoni-spring-sync-kafka`
- `andersoni-sync-http`
- `andersoni-sync-db`

Each module needs to:

1. Update `publish` and `subscribe` method signatures
2. Handle serialization/deserialization of both `RefreshEvent` and `PatchEvent`
   over their transport

### 8. Files Changed Summary

**New files:**

| File | Package | Description |
|------|---------|-------------|
| `SyncEvent` | `o.w.a.sync` | Interface with common fields + visitor accept |
| `SyncEventVisitor` | `o.w.a.sync` | Visitor with `visit(RefreshEvent)` and `visit(PatchEvent)` |
| `PatchEvent` | `o.w.a.sync` | Record: patch type + serialized items |
| `PatchType` | `o.w.a.sync` | Enum: ADD, REMOVE |
| `SyncEventListener` | `o.w.a.sync` | Functional interface replacing RefreshListener |

**Modified files:**

| File | Change |
|------|--------|
| `RefreshEvent` | Implements `SyncEvent`, adds `accept()` |
| `RefreshListener` | Removed (replaced by `SyncEventListener`) |
| `SyncStrategy` | Method signatures use `SyncEvent` / `SyncEventListener` |
| `IndexDefinition` | Add package-private `extractKey(T)` |
| `SortedIndexDefinition` | Add package-private `extractKey(T)` |
| `MultiKeyIndexDefinition` | Add package-private `extractKeys(T)` |
| `Catalog` | Add `add(T)`, `addAll(List<T>)`, `remove(T)`, `removeAll(List<T>)` |
| `Andersoni` | Add patch API, visitor-based event dispatch, predicate resolution |
| `Snapshot` | Add package-private `ofPreBuilt()` factory |
| All sync modules | Update to new `SyncStrategy` signatures, handle both event types |

**Unchanged:**

| File | Reason |
| `SnapshotSerializer` | Reused via `List.of(item)` |
| `SnapshotStore` | Leader saves full snapshots as before |
