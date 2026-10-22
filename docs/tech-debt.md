# Technical Debt

Known issues that are intentionally deferred. Each entry should describe the
problem, why it matters, and a proposed direction for the fix. Resolve in
dedicated MRs rather than bundling into feature work.

---

## Double serialization on every leader-side mutation

**Status:** open
**Discovered:** 2026-05-14 (during snapshot-patch design)
**Affected:** `andersoni-core` — `Catalog`, `Andersoni`

### Problem

On every leader-side mutation (`refreshAndSync`, and any future write path like
`replaceAndSync`), the full catalog dataset is serialized **twice**:

1. **Pass #1** — inside `Catalog.computeHash` (`Catalog.java:655`), called from
   `Catalog.buildAndSwapSnapshot` (`Catalog.java:611`) while holding
   `refreshLock`. The bytes are fed to a SHA-256 digest and immediately
   discarded.
2. **Pass #2** — inside `Andersoni.saveSnapshotIfPossible` (`Andersoni.java:943`),
   outside the refresh lock. The bytes are uploaded to the `SnapshotStore`.

Both passes produce a byte-identical `byte[]` (the serializer must be
deterministic for cross-node hash agreement to work). One of them is pure
waste.

### Why it matters

- **CPU and GC pressure scale with catalog size × mutation frequency.** For a
  100 MB catalog, every leader mutation allocates two 100 MB `byte[]`s and
  runs two full serialization passes. The cost is invisible on small catalogs
  and painful on large ones.
- **Pass #1 happens inside `refreshLock`.** Doubling the serialize work
  inside the lock extends the write-serialization window. Other writes —
  including subsequent patches — wait longer than necessary.
- **The cost is paid on every mutation, including snapshot patches.** Patches
  were designed with copy-on-write semantics to be O(log N) on the index
  side, but the leader publish path remains O(N) on serialization. The
  patch optimization is partially negated before it leaves the node.

This issue **predates** the snapshot-patch feature. Patches inherit it; they
do not cause it.

### Proposed fix

Serialize the dataset **once** during the snapshot build, hash those bytes,
and reuse the same `byte[]` for the `SnapshotStore` upload.

API sketch (option B from the design discussion):

```java
// Catalog: package-private overload that exposes the serialized bytes.
record BuiltSnapshot<T>(Snapshot<T> snapshot, byte[] serializedBytes) {}

BuiltSnapshot<T> refreshAndCapture();   // internal API for Andersoni
BuiltSnapshot<T> replaceAndCapture(...); // internal API for Andersoni
```

`Andersoni.refreshAndSync` and the future `replaceAndSync` would thread the
captured bytes into `saveSnapshotIfPossible` instead of re-serializing.

Properties of the fix:

- No behavior change. Hash values and S3 contents stay byte-identical.
- No public API change. The capturing variant is package-private and only
  used internally by `Andersoni`.
- No steady-state memory growth — the bytes are produced and consumed in
  the same operation, exactly as today, just without the duplicate
  allocation.
- The lock-held portion of `buildAndSwapSnapshot` shrinks by one
  serialization pass.

### Out of scope for this entry

- Caching serialized bytes on `Snapshot` for later reuse (would inflate
  steady-state snapshot memory).
- Making the hash lazy. The fix above keeps the hash eager, which preserves
  `Snapshot`'s "immutable everything" invariant.
- Compressing the serialized payload, or switching to a streaming hash that
  doesn't require holding the full byte array in memory. Worth considering
  separately for very large catalogs.
