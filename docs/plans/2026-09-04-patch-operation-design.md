# Patch Operation — Design and Status

**Date:** 2026-09-04
**Status:** Partly implemented on `feature/3/snapshot-patch-support`
**Scope:** `andersoni-core`, `andersoni-sync-*`, `andersoni-cluster-it`

This document describes the patch operation as it is actually built, and what
is left to finish. It replaces the design half of the earlier draft of the same
filename, which was written before the branch was inventoried and proposed
mechanisms the branch had already settled differently.

---

## 1. Relationship to `2026-07-28-incremental-refresh-design.md`

Both documents solve the same problem — surgical updates instead of full
reloads — and reach it differently. The differences are not cosmetic, and they
change what an adopter has to build, so they need resolving before either lands.

| | 2026-07-28 (incremental refresh) | This design |
|---|---|---|
| Locating the entity | `.identifiedBy(Stadium::getEventCode)` on the catalog | the caller passes an index name and key per call |
| Getting the entity to followers | `DataLoader.loadOne(id)` on every node | the entity travels in the sync message |
| New adopter code | an id extractor + a `loadOne` implementation + `assembleSingleStadium` | none |
| Upstream calls per change | one per pod | zero |
| Delta chaining | `event.version - localVersion` | content hashes |
| Changing an entity's key | explicitly unsupported | supported (it is just a patch) |

The consequence for the primary target adopter: **stadium-service needs neither
`identifiedBy` nor `loadOne`**. Sections 4.1 and 4.2 of the 2026-07-28 document
describe work that this design makes unnecessary. Its remaining sections — the
per-entity debounce in `CatalogMessageHandler`, the rollout order, the metric
list — stay valid and are not duplicated here.

The version-based chaining in that document (§6, "Version gap detection")
would not have worked as written, for the reason recorded in §4 below.

---

## 2. The shape of the operation

Patch's only real difficulty is identity: a new `Stadium(code=E-1234, …)` is a
different instance with different field values from the one in the snapshot, so
neither `equals` nor reference identity finds the item it supersedes.

The catalog already holds indices, and the caller already knows which one
identifies its entities. So the lookup is an argument, not a declaration:

```java
boolean replaced = catalog.replace("by-id", eventId, updatedEvent);
boolean replaced = andersoni.replaceAndSync("events", "by-id", eventId, updatedEvent);
```

Nothing is declared on the catalog, no DSL step is added, and no interface gains
a required method. The lookup must resolve to exactly one item; more than one
throws (§5.2).

Rejected alternatives, briefly: an **id function** duplicates an extractor the
catalog usually already has and needs a new id→item map in every snapshot; a
**designated identity index** fixes one identity per catalog and cannot express
a key change; **`equals` on the domain type** is not something a library can
require of `T` (§4); a **predicate or compound query** cannot cross the wire,
where an index name and a key can.

---

## 3. What is implemented

On `feature/3/snapshot-patch-support`, green across 518 tests:

- **`Catalog.replace(indexName, key, newItem)`** and the package-private
  `replaceAndCapture` / `replaceWithPrecondition` variants used by the cluster
  path. Serialized against `refresh()` by the existing refresh lock.
- **`CatalogPatcher`** — the copy-on-write snapshot derivation. It computes the
  old and new key sets per index and rewrites only their symmetric difference;
  every untouched bucket is shared by reference with the previous snapshot.
  Covers hash, multi-key, sorted (including the reversed-key map) and graph
  indices, recomputes views for the new item, and runs build hooks against it.
  Because it works from key *sets*, an item whose key changes moves between
  buckets correctly — renames need no special case.
- **`Andersoni.replaceAndSync(...)`** — leader-only: replaces locally, saves the
  full snapshot to the store, publishes a `PatchEvent`. `Andersoni.replace(...)`
  is the same operation without cluster coordination.
- **Wire** — `SyncMessage` sealed over `RefreshEvent` and `PatchEvent`, with
  `SyncMessageCodec`. `SyncStrategy` gains `publishPatch`, `subscribePatch` and
  `supportsPatches` as **default methods**, so existing custom transports
  compile and run unchanged. Kafka and HTTP carry patches; DB polling stays
  refresh-only (one row per catalog, no per-event log to deliver deltas over) —
  its followers converge through the hash change at the next poll.
- **Follower dispatch** — `PatchCoordinator`, hash-keyed (§4).
- **Metrics** — `patchApplied`, `patchDiscarded`, `patchFellBack`.
- **Payload ceiling** — `Andersoni.Builder.maxPatchPayloadBytes`, default
  256 KB. A patch whose encoded payload exceeds it is published as a plain
  refresh event instead. A message the transport refuses is worse than a slow
  one: the leader has applied the change and nothing would tell the followers,
  so they would stay silently stale.
- **Hook safety** — `SnapshotBuildHook.supportsPatch()`, default `false`. A
  patch runs hooks only for the item it changes, which is wrong for a hook
  that accumulates across a build, so a catalog holding any hook that has not
  opted in rebuilds fully on every patch instead. Correct either way;
  `Catalog.appliesPatchesSurgically()` reports which mode applies.

`AmbiguousPatchTargetException` is thrown when a lookup matches more than one item.

---

## 4. Preconditions are content hashes

A follower applies a delta only against the state the leader computed it from.
Three properties make that safe, and all three come from comparing **content
hashes**:

```
event.toHash   == localHash  → already applied; discard
event.fromHash == localHash  → apply surgically
otherwise                    → full reload from the snapshot store
```

**Snapshot versions cannot serve here.** `Catalog.versionCounter` is a per-node
`AtomicLong` that starts at zero in every process and is never adopted from
another node; it counts how many times *this pod* has rebuilt. Two nodes holding
identical data are only coincidentally at the same version. A follower whose
counter had run ahead of the leader's — after a leader pod restart, a late join,
or simply more frequent refreshes — read `toVersion <= localVersion` and
discarded live patches as stale, then served stale data indefinitely with
nothing to correct it. Hashes are derived from content, so they are comparable
across nodes by construction, and they are already the convergence signal for
refresh events. `fromVersion`/`toVersion` remain on `PatchEvent` as diagnostics.

**The apply-time check is a hash too, not `equals`.** It previously compared the
item deserialized off the wire against the local one with `.equals()`. A domain
class that does not override `equals` compares by identity, so that check could
never pass: followers would silently fall back to a full reload forever, and the
feature would look healthy while doing nothing. `replaceWithPrecondition`
re-checks the snapshot hash under the refresh lock instead. That requires
nothing of `T`, and is strictly stronger — it also rejects a snapshot that
drifted somewhere *other* than the patched item, which would otherwise apply
cleanly and leave the node diverged from the leader.

**The lookup key never crosses the wire.** `PatchEvent` carries the serialized
old and new items; the follower re-derives the key by feeding the old item
through its own index definition. No key codec, and no constraint on what an
index key may be.

**Patches are queued, not coalesced.** `AsyncRefreshDispatcher.dispatchOrdered`
gives each catalog a FIFO patch queue, separate from the coalescing path used
for full refreshes. Coalescing is correct for reloads, where a later one
subsumes an earlier one, and wrong for deltas. Sharing one slot meant a pending
patch could swallow the fallback reload a second patch had correctly requested,
losing that edit. Both paths still take the catalog's semaphore, so a patch and
a refresh never interleave. A saturated queue refuses the work and the caller
falls back to a reload rather than dropping it.

---

## 5. What is left to build

### 5.1 Operations

All of them are implemented, locally and across the cluster: `replace`,
`insert`, `remove`, `upsert`, and `apply(List<PatchOp<T>>)` for batches.

A batch is one snapshot swap, one version bump, one hash and one message.
Operations apply in order against the accumulating state, so a later one sees
what an earlier one did. Atomicity costs nothing to implement: each operation
is computed against a candidate snapshot that is never published, so abandoning
a batch means not swapping — there is nothing to unwind, and no reader can
observe a half-applied batch on any node.

The one contract that changes inside a batch is a `Replace` that matches
nothing: it throws rather than reporting `false`, because partial success is
not a state the caller can act on. A `Remove` that matches nothing is still
skipped, keeping removal idempotent.

`PatchEvent` carries a list of operations, so a single patch is a batch of one
and there is a single shape for the codec, the transports and the follower to
handle. **`PatchListener` stays single-method** — the operation is data on the
event, not a delivery channel. There is no `UPSERT` on the wire: an upsert
resolves on the leader into the concrete change that happened, so followers
never repeat a decision that depends on state they may not share.

The follower has one apply path, `Catalog.applyWithPrecondition`, used for one
operation and for many alike — so there is no second code path that could drift
from it.

### 5.2 Result types and the zero-match contract

**Done.** `PatchOutcome { APPLIED, NOT_FOUND, NOT_LEADER }` is returned by the
cluster-coordinated calls; the local ones keep `boolean`. The rest of this
section records the reasoning.

`replaceAndSync` returns `false` for three unrelated outcomes: not leader, no
match, and stopped. A caller cannot tell "I am a follower, this was someone
else's job" from "that entity is not in the catalog", and the second is usually
a bug worth alerting on. Proposed: keep `boolean` where only found/not-found is
possible (`Catalog.replace`, `Andersoni.replace`) and return an enum from the
cluster-coordinated entry points:

```java
enum PatchOutcome { APPLIED, NOT_FOUND, NOT_LEADER }
```

An earlier draft argued a zero match should *throw*. An explicit `NOT_FOUND` is
just as loud, and composes with batching in a way an exception does not: a batch
of fifty operations cannot throw on the thirty-seventh without leaving the
caller to guess which of the others applied.

For batches: validate every op's lookup up front, then apply all of them into a
single new snapshot, so the batch is one `fromHash → toHash` step on the wire
and one snapshot swap. An ambiguous lookup throws before anything is applied; a
missing target for a `replace` op fails the whole batch and names the key.

### 5.3 Naming

Done: `AmbiguousReplaceException` is now `AmbiguousPatchTargetException`, since
removals and upserts throw it too.

### 5.4 Target version

`README.md` marks the feature "Since 1.12.0", matching the release
`2026-07-28-incremental-refresh-design.md` targets. It was "Since 1.10.0",
written when the branch started in May; 1.10.0, 1.10.1, 1.10.2 and 1.11.0 have
all been released since, none carrying this feature. The number is a
placeholder until §1 is settled — if this design is not the one that ships, or
slips a release, it moves with it.

---

## 6. Costs

| Cost | Full sync | Patch |
|---|---|---|
| `DataLoader.load()` (leader) | all N entities, network-bound | **gone** |
| View + index build (leader) | N items × all indices | 1 item + O(K) map copies |
| Snapshot download + deserialize (followers) | full snapshot | **gone** |
| Upstream calls per change | N | **0** |
| Item list copy | rebuild | O(N) reference copy |
| Content hash | O(N) serialize | O(N) serialize — unchanged |
| Store write (leader) | O(N) serialize + upload | O(N) serialize + upload — unchanged |

### Measured

`PatchBenchmark` (in `andersoni-core` test sources) times one change against a
full rebuild from data already in memory. It excludes the DataLoader on
purpose: a real refresh also re-reads the whole dataset, which is the dominant
cost in production and which a patch avoids entirely, so including it would
flatter the patch without saying anything new. Milliseconds per operation,
median, on one developer machine:

| items | rebuild | patch | batch/10 | patch (hook opted out) | hash only |
|---|---|---|---|---|---|
| 10,000 | 5.3 | 1.0 | 2.6 | ~8 | 0.5 |
| 100,000 | ~110 | 27 | 50 | ~100–160 | 13 |
| 500,000 | ~575 | 130 | 305 | ~570 | 60 |

Run-to-run variance is wide at 10k and 100k — the harness runs in one JVM
without isolation, so a GC in the wrong place moves a row by half. The 500k
row is the most trustworthy, and ratios are more meaningful than absolutes.
The hook-opted-out column tracks the rebuild column within that variance,
which is what it should do: that path rebuilds.

Three things the numbers say that the table above does not.

**A patch is roughly 4–5× a rebuild, not orders of magnitude, and it scales
with N.** "Only the affected buckets are rebuilt" is true of the buckets and
false of the maps holding them: replacing one entry in an immutable map means
copying the map, so a `by-id` index over N items costs an N-entry `HashMap`
copy per patch. The surgical work is real — the extractors, views and bucket
lists are not rebuilt — but it does not make the operation sub-linear. Only a
persistent map would, and that is a much larger change.

**The hash is the floor.** It is about half a patch's cost at 10k and 45% at
500k. No amount of index cleverness gets below that line while the content
hash serializes the whole dataset, which makes incremental hashing (below) the
highest-value follow-up rather than a nicety.

**Batching is about twice as fast as rebuilding, but only after the map
copies were shared.** As first written, a batch of ten cost about what a full
rebuild cost: each operation was computed against an intermediate snapshot and
each paid the O(N) map copies again, so the batch saved the hash — one instead
of ten — and nothing else. Operations now share a `PatchScope`: the first one
to touch an index copies its map, the rest mutate that copy, and the batch
freezes once at the end. A batch of ten went from ~611 ms to ~305 ms at 500k
items, against ~575 ms to rebuild. The remaining cost is one map copy per
index plus the single hash, which is the floor for any number of operations.

The end-to-end picture in production is still strongly in the patch's favour,
because the cost this benchmark excludes — reloading the dataset from its
source, and every follower deserializing a snapshot — is the one that actually
dominates. But the in-memory win is a small multiple, not a transformation.

The residual O(N) work is CPU-bound serialization and map copying, not network.
Two follow-ups, both deferred because they change cluster-wide behaviour:

1. **Incremental hashing** — cache a per-item digest in `AndersoniCatalogItem`
   and hash the ordered digests. Changes the hash *value*, so mixed-version
   clusters would mismatch and churn; needs a scheme marker and a coordinated
   rollout.
2. **Debounced store writes** — persist at most every X seconds. Needs the
   bootstrap gap closed by a follower-issued full-refresh request when its
   precondition check fails, or a stale store causes reload churn.

---

## 7. Testing status

Covered: `CatalogReplaceTest` (16), `AndersoniPatchDispatchTest` (7),
`AndersoniReplaceTest`, `AndersoniReplaceAndSyncTest`, `SyncMessageCodecTest`
(9), plus the transport suites. Structural sharing, view recomputation, hook
execution, refresh-lock serialization, hash-keyed dispatch precedence, a domain
type without `equals`, drift elsewhere in the dataset, `RefreshKind` round-trip
and pre-patch wire payloads all have tests.

`ClusterPatchPropagationIT` boots Kafka, PostgreSQL and three nodes and proves
the multi-node behaviour: a replace, an insert, a removal and a mixed batch
each converging on all three nodes, a no-match leaving every hash untouched,
and a follower driven out of sync refusing the delta and reloading. The batch
asserts that every node's version advances by exactly one, which is what
applying it as a unit means where it counts. Every patch is applied to the
leader's memory only and never to the database, and no snapshot store is
configured, so the DataLoader cannot reproduce the patched state — a follower
that converges can only have applied the delta. Verified sensitive: with the
surgical apply path disabled the suite fails rather than passing anyway.

Not yet covered:

- **Leader failover mid-patch.** Leadership in the IT is a static environment
  variable, so promoting a new leader is not expressible without restarting a
  container with different configuration.
- **Performance.** Everything verified so far is correctness. The IT holds four
  items on three nodes, and nothing has been benchmarked, so the size of the
  end-to-end win is still an estimate.

Running them: `mvn verify -pl andersoni-cluster-it -am`. Without `-am` the
module resolves `andersoni-core` from the local repository — the released jar,
not the working tree — and the ITs silently verify the wrong code. Filtering
with `-Dtest` also pulls the ITs into the `test` phase, ahead of the shade
plugin that builds the jar the node image is made from; failsafe's filter is
`-Dit.test`.
