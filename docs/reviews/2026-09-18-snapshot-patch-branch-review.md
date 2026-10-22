# Code review: `feature/3/snapshot-patch-support` vs `main`

**Date:** 2026-09-18
**Branch:** `feature/3/snapshot-patch-support` (head `0ae91ca`, merge-base with main `8fb83a1`)
**Scope:** 23 commits, 49 files, ~8,600 lines added. Adds the snapshot patch feature
(insert / remove / replace / upsert / batch apply), cluster propagation via
`PatchEvent`, `SyncMessageCodec`, patch support in the Kafka, Spring Kafka and HTTP
sync strategies, and a three-node cluster IT (`ClusterPatchPropagationIT`).
**Status:** review done, nothing fixed yet. This file exists so the work can be
resumed in a later session.

Line numbers refer to the branch at `0ae91ca`. Findings 1 to 5 and 9 to 10 were
verified by reading the branch source. Findings 6 to 8 follow from the code paths
read but were not traced line by line.

---

## Correctness (fix before merge)

### 1. Patches on DB polling leave followers stale indefinitely
- **Where:** `andersoni-core/.../PatchCoordinator.java` ~line 371, `publish(...)`.
- **What:** the leader applies the patch and saves the snapshot to the store, then
  returns early because `syncStrategy.supportsPatches()` is false for
  `DbPollingSyncStrategy` (default from `SyncStrategy`). Nothing updates the DB hash
  row, so followers polling it never reload. The debug log claims they "converge via
  the next periodic refresh", but a catalog with no refresh interval has none.
- **Fix:** mirror the oversized-payload branch a few lines below, which publishes a
  `RefreshEvent` (DB polling can carry those) instead of returning.

### 2. Patching a built-but-never-refreshed catalog throws NPE
- **Where:** `CatalogPatcher.java` ~line 505 (`patchIndices`) → ~line 527
  (`patchInnerMap`).
- **What:** `Snapshot.empty()` uses `Collections.emptyMap()` for indices, so
  `oldIndices.get(idx)` is null. Line 501 only skips when both old and new key sets
  are empty; an insert has non-empty new keys, so it reaches
  `new HashMap<>(inner)` with `inner == null`. No guard exists upstream in `Catalog`.
- **Repro:** `Catalog.of(Item.class).named("x").withData(List.of()).index("id").by(...).build()`
  then `catalog.insert(item)` with no prior refresh.
- **Fix:** treat a missing inner map as empty, or fall back to a rebuild when the
  current snapshot is the empty sentinel.

### 3. `fromHash` precondition checked at receive time, not apply time
- **Where:** `PatchCoordinator.java` ~line 454, `onPatch(...)`; apply-time recheck at
  ~line 506 (`catalog.applyWithPrecondition(ops, event.fromHash())`).
- **What:** the event's `fromHash` is compared against the live snapshot before the
  event is enqueued on the ordered dispatcher. Two patches in flight (P1 h0→h1, P2
  h1→h2) delivered before P1 is applied make P2 fall back to a full reload
  (`metrics.patchFellBack` + `refreshFromEvent`). If that reload wins the semaphore
  before P1, P1's apply-time precondition fails too → second reload. Kafka batch
  polling and `HttpSyncStrategy` async POSTs both produce this ordering. The FIFO
  `dispatchOrdered` queue is effectively defeated under any burst.
- **Fix:** defer the precondition to apply time only, or compare against the expected
  post-queue hash (tail of the queue) rather than the live snapshot.

### 4. Multi-key / graph index patches always fall back on followers
- **Where:** `PatchCoordinator.java` ~line 552, `toPatchOps(...)`.
- **What:** the follower re-derives the key via `catalog.extractKeyFor(indexName, oldItem)`.
  For multi-key indexes that returns an arbitrary key (`keys.iterator().next()` over
  a `HashSet`), which is usually not the singleton bucket the leader used.
  `resolveSingle` then throws `AmbiguousPatchTargetException`, caught in
  `applyPatchLocally` → full S3 reload on every follower for every such patch, and
  without the `patchFellBack` metric.
- **Fix:** carry the leader's actual key on `PatchEvent.Op`, or try each derived key
  and pick the one whose bucket is a singleton.

### 5. Reversed-key (text query) indices never created by surgical inserts
- **Where:** `CatalogPatcher.java` ~lines 631 and 638, `patchReversedIndices`.
- **What:** reversed indices are only created during a full build when the first
  non-null key is a `String`. A catalog bootstrapped from an empty loader keeps an
  empty reversed map; `patchReversedIndices` returns early on `oldReversed.isEmpty()`
  and skips the definition. Suffix-style queries on that index then hit
  `requireReversedKeyIndex` → `UnsupportedIndexOperationException` until the next
  full refresh.
- **Fix:** create the reversed index lazily on the first String-keyed insert, or
  decide String-keyedness from the index definition rather than from data.

## Performance (all under `refreshLock`)

### 6. Batch apply is O(K·n), not O(n)
- **Where:** `Catalog.java` ~line 603, `applyAndCapture` / batch path;
  `CatalogPatcher.patchInnerMap` ~line 542 (`removeIf` over the whole inner map).
- **What:** each op rebuilds an intermediate `Snapshot` (item list copy via
  `swapItemInList` plus `extractItems` in `Snapshot.ofPatched`) and prunes the entire
  owned inner map. K ops on n items ≈ K·n work while holding the write lock, which
  undermines the `PatchScope` "copy each index once" optimisation.
- **Fix:** thread the working item list and only touched keys through the batch;
  prune only keys that were emptied.

### 7. Hook-forced rebuilds still compute every surgical patch first
- **Where:** `Catalog.java` ~line 619 (batch path) and the insert branch of `upsert`
  (~lines 418 / 488).
- **What:** with a hook that has not opted into single-item patching,
  `applyAndCapture` runs `computeOne` for every op (index copies, sorted / reversed
  TreeMap copies, freezing) and then `rebuildAfterPatch` discards all of it. Only the
  single-op replace / remove / insert paths got the fix from commit `1fc331f`
  ("stop patches doing work they throw away").
- **Fix:** check the hook decision before computing, as the single-op paths do.

### 8. Every `*AndSync` call uploads the full snapshot synchronously
- **Where:** `PatchCoordinator.java` ~lines 369 and 378, `publish(...)`.
- **What:** `callbacks.saveSnapshotIfPossible(catalog)` serialises and uploads the
  whole catalog on the caller's thread before the patch is published. Then the
  `PatchEvent` is serialised once just to measure `payloadBytes`, and the transport
  serialises it again. Patch latency is bounded by snapshot upload time, so a
  "surgical" patch costs O(N) per call.
- **Fix:** serialise once and hand the bytes to `publishPatch`; consider debouncing or
  making the store save asynchronous.

## Documentation residue

### 9. Stray unterminated `/**` in `Andersoni.java`
- **Where:** `Andersoni.java` lines 1486–1488.
- **What:** a leftover `/**` from the `AsyncRefreshDispatcher` extraction opens a
  comment that swallows the Builder's own `/**`, so the Builder Javadoc is malformed.
- **Fix:** delete the stray opener.

### 10. Stale boolean Javadoc on `replaceAndSync` / `removeAndSync`
- **Where:** `Andersoni.java` lines 618 and 691.
- **What:** both now return `PatchOutcome` (can be `NOT_LEADER` / `NOT_FOUND`) but the
  `@return` still documents `true` / `false`. That is the exact ambiguity
  `PatchOutcome` was introduced to remove.
- **Fix:** mirror the enum-based `@return` used by `insertAndSync` / `upsertAndSync`.

---

## Housekeeping
- The branch's linked worktree at
  `/tmp/claude-1000/-home-rafa-repos-andersoni/034228c1-.../scratchpad/patch-wt`
  is gone; `git worktree list` shows it as prunable. Run `git worktree prune`.
- Pre-existing EasyMock proxy failures in `SpringKafkaSyncStrategyTest` and
  `ClusterControllerTest` are an environment issue, not a regression from this
  branch.

## Suggested order to resume
1. Fix 2 (NPE) and 9/10 (docs) first: small, isolated, easy to test.
2. Fix 1 (DB polling fallback) by reusing the oversized-payload branch.
3. Fix 4 (ship the leader's key on the wire) and 3 (precondition timing) together,
   since both touch `PatchCoordinator` receive/apply and the cluster IT.
4. Fix 5 (reversed index creation).
5. Revisit 6/7/8 as a performance pass; `PatchBenchmark` on the branch can measure
   before/after.
