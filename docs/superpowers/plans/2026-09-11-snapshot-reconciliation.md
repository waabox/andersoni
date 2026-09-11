# Snapshot Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every node periodically compares itself against the snapshot store and repairs drift automatically, so a missed sync event, a failed reload, a leader change or a failed leader save no longer requires a manual refresh.

**Architecture:** A package-private `SnapshotStoreBridge` centralizes load/save against the `SnapshotStore` and tracks, per catalog, the store hash this node last applied. A package-private `SnapshotReconciler` runs on its own scheduler: followers reload from the store when its hash differs from the applied hash, the leader re-saves and re-publishes when its applied hash is missing or differs, and a newly promoted leader runs a pass immediately. Repairs go through the existing `AsyncRefreshDispatcher`, which is extracted from `Andersoni` into its own file. `SnapshotStore` gains a cheap `describe` (metadata only), implemented with `HeadObject` on S3 and a header read on the filesystem store.

**Tech Stack:** Java 21, Maven multi-module, JUnit 5, EasyMock, AWS SDK v2 (S3), Spring Boot (starter), DogStatsD client (Datadog), Testcontainers (cluster IT).

**Spec:** `docs/superpowers/specs/2026-09-11-snapshot-reconciliation-design.md`

## Global Constraints

- Java 21, no Lombok, explicit types (never `var`), all parameters and locals `final`.
- Static factories + private constructors for value classes; `Objects.requireNonNull` on public APIs, always on one line.
- Google Java Style, max line width 120 columns (the codebase mostly wraps at 80; keep new code consistent with the file you edit).
- JavaDoc on every public method with `@author waabox(waabox[at]gmail[dot]com)` on every new public type.
- Tests: JUnit 5 + EasyMock, naming `whenDoingSomething_givenSomeScenario_shouldDoOrHappenSomething()`, no given/when/then comments, each test builds its own objects, prefer real objects over mocks.
- Commit messages: professional, what + why, **no** `Co-Authored-By` and **no** `Claude-Session` trailers, never mention Claude.
- Never push.
- Package for new core types: `org.waabox.andersoni` (engine) and `org.waabox.andersoni.snapshot` (store contract).
- Build/test commands: `mvn -q -pl andersoni-core test -Dtest=<Class>` for a single class; `mvn clean verify` for the full build. The cluster IT (`andersoni-cluster-it`) needs Docker and runs under `mvn verify` in that module.
- Default reconciliation interval: 30 seconds; jitter up to 20 %.
- Behavior change to keep: with a snapshot store configured and no explicit policy, reconciliation is **on** by default.

---

## File Structure

**Create**
- `andersoni-core/src/main/java/org/waabox/andersoni/snapshot/SnapshotMetadata.java` — hash/version/createdAt of a stored snapshot without the bytes.
- `andersoni-core/src/main/java/org/waabox/andersoni/ReconciliationPolicy.java` — enabled flag + interval value class.
- `andersoni-core/src/main/java/org/waabox/andersoni/SyncState.java` — `IN_SYNC`, `DRIFTED`, `UNKNOWN`.
- `andersoni-core/src/main/java/org/waabox/andersoni/AsyncRefreshDispatcher.java` — extracted, unchanged behavior.
- `andersoni-core/src/main/java/org/waabox/andersoni/SnapshotStoreBridge.java` — load/save/describe against the store + applied-hash bookkeeping.
- `andersoni-core/src/main/java/org/waabox/andersoni/SnapshotReconciler.java` — the periodic anti-entropy loop.
- `andersoni-core/src/test/java/org/waabox/andersoni/InMemorySnapshotStore.java` — shared test store.
- `andersoni-core/src/test/java/org/waabox/andersoni/snapshot/SnapshotMetadataTest.java`
- `andersoni-core/src/test/java/org/waabox/andersoni/snapshot/SnapshotStoreDescribeTest.java`
- `andersoni-core/src/test/java/org/waabox/andersoni/ReconciliationPolicyTest.java`
- `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniStatusTest.java`
- `andersoni-core/src/test/java/org/waabox/andersoni/SnapshotStoreBridgeTest.java`
- `andersoni-core/src/test/java/org/waabox/andersoni/SnapshotReconcilerTest.java`
- `andersoni-cluster-it/src/main/java/org/waabox/andersoni/it/ItemSerializer.java`
- `andersoni-cluster-it/src/test/java/org/waabox/andersoni/it/ClusterReconciliationIT.java`

**Modify**
- `andersoni-core/src/main/java/org/waabox/andersoni/snapshot/SnapshotStore.java` — add `describe` default.
- `andersoni-core/src/main/java/org/waabox/andersoni/metrics/AndersoniMetrics.java` — three default methods.
- `andersoni-core/src/main/java/org/waabox/andersoni/AndersoniStatus.java` — `syncState`, `lastReconciledAt`, `inSync()`.
- `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java` — use the bridge, wire the reconciler, `reconcile()`, builder option, status.
- `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java` — strict-mock expectations + new tests.
- `andersoni-snapshot-fs/src/main/java/org/waabox/andersoni/snapshot/fs/FileSystemSnapshotStore.java` + test.
- `andersoni-snapshot-s3/src/main/java/org/waabox/andersoni/snapshot/s3/S3SnapshotStore.java` + test.
- `andersoni-metrics-datadog/src/main/java/org/waabox/andersoni/metrics/datadog/DatadogAndersoniMetrics.java` + test.
- `andersoni-spring-boot-starter/src/main/java/org/waabox/andersoni/spring/AndersoniProperties.java`, `AndersoniAutoConfiguration.java` + test.
- `andersoni-cluster-it/pom.xml`, `ClusterNode.java`.
- `CLAUDE.md`, `README.md`.

---

### Task 1: `SnapshotMetadata` and `SnapshotStore.describe`

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/snapshot/SnapshotMetadata.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/snapshot/SnapshotStore.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/snapshot/SnapshotMetadataTest.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/snapshot/SnapshotStoreDescribeTest.java`

**Interfaces:**
- Produces: `record SnapshotMetadata(String catalogName, String hash, long version, Instant createdAt)` with `static SnapshotMetadata of(SerializedSnapshot)`.
- Produces: `default Optional<SnapshotMetadata> SnapshotStore.describe(String catalogName)`.

- [ ] **Step 1: Write the failing tests**

`andersoni-core/src/test/java/org/waabox/andersoni/snapshot/SnapshotMetadataTest.java`:

```java
package org.waabox.andersoni.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SnapshotMetadata}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SnapshotMetadataTest {

  @Test
  void whenCreatingFromSnapshot_givenSerializedSnapshot_shouldCopyMetadataOnly() {
    final Instant createdAt = Instant.parse("2026-09-11T10:00:00Z");
    final SerializedSnapshot snapshot = new SerializedSnapshot(
        "events", "abc", 7L, createdAt, new byte[] {1, 2, 3});

    final SnapshotMetadata metadata = SnapshotMetadata.of(snapshot);

    assertEquals("events", metadata.catalogName());
    assertEquals("abc", metadata.hash());
    assertEquals(7L, metadata.version());
    assertEquals(createdAt, metadata.createdAt());
  }

  @Test
  void whenCreating_givenNullHash_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        new SnapshotMetadata("events", null, 1L, Instant.EPOCH));
  }

  @Test
  void whenCreatingFromSnapshot_givenNull_shouldThrow() {
    assertThrows(NullPointerException.class, () -> SnapshotMetadata.of(null));
  }
}
```

`andersoni-core/src/test/java/org/waabox/andersoni/snapshot/SnapshotStoreDescribeTest.java`:

```java
package org.waabox.andersoni.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Tests for the default {@link SnapshotStore#describe(String)}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SnapshotStoreDescribeTest {

  /** A store that only implements the two abstract methods. */
  private static final class LoadOnlyStore implements SnapshotStore {

    private final SerializedSnapshot stored;

    private LoadOnlyStore(final SerializedSnapshot theStored) {
      stored = theStored;
    }

    @Override
    public void save(final String catalogName, final SerializedSnapshot snapshot) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<SerializedSnapshot> load(final String catalogName) {
      return Optional.ofNullable(stored);
    }
  }

  @Test
  void whenDescribing_givenDefaultImplementationAndSnapshot_shouldDelegateToLoad() {
    final Instant createdAt = Instant.parse("2026-09-11T10:00:00Z");
    final SnapshotStore store = new LoadOnlyStore(new SerializedSnapshot(
        "events", "hash-1", 3L, createdAt, new byte[] {9}));

    final Optional<SnapshotMetadata> metadata = store.describe("events");

    assertTrue(metadata.isPresent());
    assertEquals("hash-1", metadata.get().hash());
    assertEquals(3L, metadata.get().version());
    assertEquals(createdAt, metadata.get().createdAt());
  }

  @Test
  void whenDescribing_givenDefaultImplementationAndNoSnapshot_shouldReturnEmpty() {
    final SnapshotStore store = new LoadOnlyStore(null);

    assertTrue(store.describe("events").isEmpty());
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -pl andersoni-core test -Dtest='SnapshotMetadataTest,SnapshotStoreDescribeTest'`
Expected: compilation failure (`SnapshotMetadata` does not exist, `describe` undefined).

- [ ] **Step 3: Implement**

`andersoni-core/src/main/java/org/waabox/andersoni/snapshot/SnapshotMetadata.java`:

```java
package org.waabox.andersoni.snapshot;

import java.time.Instant;
import java.util.Objects;

/**
 * The metadata of a stored snapshot: everything a {@link SerializedSnapshot}
 * carries except the serialized bytes.
 *
 * <p>Returned by {@link SnapshotStore#describe(String)} so a node can compare
 * the store's content hash with its own without downloading the snapshot.
 *
 * @param catalogName the catalog the snapshot belongs to, never null
 * @param hash        the content hash of the stored bytes, never null
 * @param version     the snapshot version recorded by the writing node
 * @param createdAt   the instant the snapshot was created, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record SnapshotMetadata(
    String catalogName,
    String hash,
    long version,
    Instant createdAt) {

  /**
   * Canonical constructor validating inputs.
   *
   * @param catalogName the catalog name, never null.
   * @param hash        the content hash, never null.
   * @param version     the snapshot version.
   * @param createdAt   the creation instant, never null.
   */
  public SnapshotMetadata {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(hash, "hash must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  /**
   * Extracts the metadata of a serialized snapshot.
   *
   * @param snapshot the snapshot to describe, never null
   *
   * @return the metadata, never null
   */
  public static SnapshotMetadata of(final SerializedSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    return new SnapshotMetadata(snapshot.catalogName(), snapshot.hash(),
        snapshot.version(), snapshot.createdAt());
  }
}
```

Add to `SnapshotStore.java` after `load`:

```java
  /**
   * Describes the most recent snapshot for the given catalog without
   * loading its bytes.
   *
   * <p>Used by the reconciliation loop to detect drift cheaply. The default
   * implementation is correct but expensive: it loads the whole snapshot and
   * discards the data. Implementations backed by a store that can serve
   * metadata separately (object metadata headers, a file header) should
   * override it.
   *
   * <p>Returns {@link Optional#empty()} when no snapshot exists, exactly as
   * {@link #load(String)} does. Transport or I/O failures propagate as
   * runtime exceptions.
   *
   * @param catalogName the name of the catalog, never null
   * @return the snapshot metadata if a snapshot exists, or empty
   */
  default Optional<SnapshotMetadata> describe(final String catalogName) {
    return load(catalogName).map(SnapshotMetadata::of);
  }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl andersoni-core test -Dtest='SnapshotMetadataTest,SnapshotStoreDescribeTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/snapshot/SnapshotMetadata.java \
        andersoni-core/src/main/java/org/waabox/andersoni/snapshot/SnapshotStore.java \
        andersoni-core/src/test/java/org/waabox/andersoni/snapshot/SnapshotMetadataTest.java \
        andersoni-core/src/test/java/org/waabox/andersoni/snapshot/SnapshotStoreDescribeTest.java
git commit -m "Add SnapshotStore.describe for metadata-only snapshot lookups

Reconciliation needs to compare the store's content hash with the local
one on every cycle; downloading the whole snapshot for that would be
wasteful. The default delegates to load so existing stores keep working."
```

---

### Task 2: `ReconciliationPolicy`

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/ReconciliationPolicy.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/ReconciliationPolicyTest.java`

**Interfaces:**
- Produces: `ReconciliationPolicy.of(Duration)`, `disabled()`, `defaultPolicy()`, `boolean enabled()`, `Duration interval()`.

- [ ] **Step 1: Write the failing test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReconciliationPolicy}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class ReconciliationPolicyTest {

  @Test
  void whenCreating_givenPositiveInterval_shouldBeEnabledWithThatInterval() {
    final ReconciliationPolicy policy = ReconciliationPolicy.of(Duration.ofSeconds(10));

    assertTrue(policy.enabled());
    assertEquals(Duration.ofSeconds(10), policy.interval());
  }

  @Test
  void whenCreating_givenZeroInterval_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        ReconciliationPolicy.of(Duration.ZERO));
  }

  @Test
  void whenCreating_givenNegativeInterval_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        ReconciliationPolicy.of(Duration.ofSeconds(-1)));
  }

  @Test
  void whenCreating_givenNullInterval_shouldThrow() {
    assertThrows(NullPointerException.class, () -> ReconciliationPolicy.of(null));
  }

  @Test
  void whenUsingDisabled_shouldNotBeEnabled() {
    final ReconciliationPolicy policy = ReconciliationPolicy.disabled();

    assertFalse(policy.enabled());
    assertEquals(Duration.ZERO, policy.interval());
  }

  @Test
  void whenUsingDefault_shouldBeEnabledEveryThirtySeconds() {
    final ReconciliationPolicy policy = ReconciliationPolicy.defaultPolicy();

    assertTrue(policy.enabled());
    assertEquals(Duration.ofSeconds(30), policy.interval());
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl andersoni-core test -Dtest=ReconciliationPolicyTest`
Expected: compilation failure (`ReconciliationPolicy` missing).

- [ ] **Step 3: Implement**

```java
package org.waabox.andersoni;

import java.time.Duration;
import java.util.Objects;

/**
 * Configures the snapshot reconciliation loop: whether it runs and how often.
 *
 * <p>Reconciliation is the cluster's anti-entropy mechanism. On every
 * interval each node compares the snapshot store's content hash with the
 * hash it last applied; followers reload on drift and the leader re-saves
 * and re-publishes. It only runs when a
 * {@link org.waabox.andersoni.snapshot.SnapshotStore} is configured.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class ReconciliationPolicy {

  /** The default interval between reconciliation passes. */
  private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(30);

  /** Whether reconciliation runs at all. */
  private final boolean enabled;

  /** The base interval between passes; zero when disabled. */
  private final Duration interval;

  private ReconciliationPolicy(final boolean enabled, final Duration interval) {
    this.enabled = enabled;
    this.interval = interval;
  }

  /**
   * Creates an enabled policy with the given interval between passes.
   *
   * @param interval the base interval between passes, never null, positive
   *
   * @return the policy, never null
   *
   * @throws IllegalArgumentException if the interval is zero or negative
   */
  public static ReconciliationPolicy of(final Duration interval) {
    Objects.requireNonNull(interval, "interval must not be null");
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException(
          "interval must be a positive duration, got: " + interval);
    }
    return new ReconciliationPolicy(true, interval);
  }

  /**
   * Creates a policy that turns reconciliation off.
   *
   * @return the disabled policy, never null
   */
  public static ReconciliationPolicy disabled() {
    return new ReconciliationPolicy(false, Duration.ZERO);
  }

  /**
   * Creates the default policy: enabled, one pass every 30 seconds.
   *
   * @return the default policy, never null
   */
  public static ReconciliationPolicy defaultPolicy() {
    return new ReconciliationPolicy(true, DEFAULT_INTERVAL);
  }

  /**
   * Returns whether reconciliation is enabled.
   *
   * @return true if passes should run
   */
  public boolean enabled() {
    return enabled;
  }

  /**
   * Returns the base interval between passes.
   *
   * @return the interval, {@link Duration#ZERO} when disabled, never null
   */
  public Duration interval() {
    return interval;
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl andersoni-core test -Dtest=ReconciliationPolicyTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/ReconciliationPolicy.java \
        andersoni-core/src/test/java/org/waabox/andersoni/ReconciliationPolicyTest.java
git commit -m "Add ReconciliationPolicy value class

Holds the enabled flag and pass interval for the upcoming snapshot
reconciliation loop, mirroring RetryPolicy."
```

---

### Task 3: `SyncState`, `AndersoniStatus` additions and `AndersoniMetrics` defaults

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/SyncState.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/AndersoniStatus.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/metrics/AndersoniMetrics.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java` (only `buildCatalogStatus`, to keep compiling)
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniStatusTest.java`

**Interfaces:**
- Produces: `enum SyncState { IN_SYNC, DRIFTED, UNKNOWN }`.
- Produces: `CatalogStatus(..., SyncState syncState, Optional<Instant> lastReconciledAt)` and `boolean AndersoniStatus.inSync()`.
- Produces: `AndersoniMetrics.driftDetected(String)`, `driftRepaired(String)`, `reconcileFailed(String, Throwable)` as default no-ops.

- [ ] **Step 1: Write the failing test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AndersoniStatus}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class AndersoniStatusTest {

  private static AndersoniStatus.CatalogStatus status(final String name,
      final SyncState syncState) {
    return new AndersoniStatus.CatalogStatus(name, true, 1L, "h", true, 1, 0.1,
        syncState, Optional.of(Instant.EPOCH));
  }

  @Test
  void whenCheckingInSync_givenNoDriftedCatalog_shouldBeTrue() {
    final AndersoniStatus status = new AndersoniStatus("node-1", true,
        List.of(status("a", SyncState.IN_SYNC), status("b", SyncState.UNKNOWN)));

    assertTrue(status.inSync());
  }

  @Test
  void whenCheckingInSync_givenOneDriftedCatalog_shouldBeFalse() {
    final AndersoniStatus status = new AndersoniStatus("node-1", true,
        List.of(status("a", SyncState.IN_SYNC), status("b", SyncState.DRIFTED)));

    assertFalse(status.inSync());
  }

  @Test
  void whenCreatingCatalogStatus_givenNullSyncState_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        new AndersoniStatus.CatalogStatus("a", true, 1L, "h", true, 1, 0.1,
            null, Optional.empty()));
  }

  @Test
  void whenCreatingCatalogStatus_givenNullLastReconciledAt_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        new AndersoniStatus.CatalogStatus("a", true, 1L, "h", true, 1, 0.1,
            SyncState.UNKNOWN, null));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl andersoni-core test -Dtest=AndersoniStatusTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`SyncState.java`:

```java
package org.waabox.andersoni;

/**
 * Whether a catalog on this node matches the cluster's authoritative state
 * (the snapshot store) as of the last reconciliation pass.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public enum SyncState {

  /** The last pass found this node at the store's snapshot. */
  IN_SYNC,

  /** The last pass found a mismatch; a repair was dispatched or is pending. */
  DRIFTED,

  /** Reconciliation is inactive for this catalog (no snapshot store, no
   *  serializer, disabled policy) or no pass has completed yet. */
  UNKNOWN
}
```

`AndersoniStatus.java`: add imports `java.time.Instant` and `java.util.Optional`; add the two record components and `inSync()`:

```java
  /**
   * Returns whether every catalog on this node is at the store's snapshot.
   *
   * <p>{@link SyncState#UNKNOWN} does not count as drift, so a node without
   * reconciliation reports {@code true}.
   *
   * @return false if any catalog is {@link SyncState#DRIFTED}
   */
  public boolean inSync() {
    return catalogs.stream().noneMatch(c -> c.syncState() == SyncState.DRIFTED);
  }
```

Record becomes (update the JavaDoc `@param` list accordingly, adding `syncState` and `lastReconciledAt`):

```java
  public record CatalogStatus(
      String catalogName,
      boolean available,
      long version,
      String hash,
      boolean hashComparable,
      int itemCount,
      double estimatedSizeMB,
      SyncState syncState,
      Optional<Instant> lastReconciledAt) {

    public CatalogStatus {
      Objects.requireNonNull(catalogName, "catalogName must not be null");
      Objects.requireNonNull(hash, "hash must not be null");
      Objects.requireNonNull(syncState, "syncState must not be null");
      Objects.requireNonNull(lastReconciledAt, "lastReconciledAt must not be null");
    }
  }
```

JavaDoc for the two new params:

```
   * @param syncState        whether this catalog matched the snapshot store at
   *                         the last reconciliation pass; {@code UNKNOWN} when
   *                         reconciliation is inactive for it
   * @param lastReconciledAt when the last reconciliation pass checked this
   *                         catalog, or empty if none has, never null
```

`Andersoni.buildCatalogStatus` (temporary, replaced in Task 7): pass `SyncState.UNKNOWN, Optional.empty()` in both constructor calls so the core compiles.

`AndersoniMetrics.java`: add after `syncReceiveFailed`:

```java
  /**
   * Records that a reconciliation pass found this node's catalog differing
   * from the snapshot store.
   *
   * @param catalogName the name of the catalog, never null
   */
  default void driftDetected(final String catalogName) {
  }

  /**
   * Records that a reconciliation repair brought the catalog back in sync
   * with the snapshot store (a follower reloaded, or the leader re-saved).
   *
   * @param catalogName the name of the catalog, never null
   */
  default void driftRepaired(final String catalogName) {
  }

  /**
   * Records that a reconciliation step failed: the store could not be
   * described, or a repair threw. The next pass retries.
   *
   * @param catalogName the name of the catalog, never null
   * @param cause       the failure, never null
   */
  default void reconcileFailed(final String catalogName, final Throwable cause) {
  }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl andersoni-core test -Dtest='AndersoniStatusTest,AndersoniTest'`
Expected: PASS (existing status tests only read fields).

- [ ] **Step 5: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/SyncState.java \
        andersoni-core/src/main/java/org/waabox/andersoni/AndersoniStatus.java \
        andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java \
        andersoni-core/src/main/java/org/waabox/andersoni/metrics/AndersoniMetrics.java \
        andersoni-core/src/test/java/org/waabox/andersoni/AndersoniStatusTest.java
git commit -m "Expose per-catalog sync state in AndersoniStatus and drift metrics

Adds SyncState and lastReconciledAt to CatalogStatus, an inSync()
aggregate, and default no-op drift metrics so dashboards and alerts can
see whether a node matches the snapshot store."
```

---

### Task 4: Extract `AsyncRefreshDispatcher` to its own file

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/AsyncRefreshDispatcher.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java` (remove the nested class, lines ~1208-1305)

**Interfaces:**
- Produces: package-private `final class AsyncRefreshDispatcher` with `AsyncRefreshDispatcher(Set<String> catalogNames)` and `void dispatch(String catalogName, Runnable refreshTask)`. Behavior unchanged.

- [ ] **Step 1: Create the new file**

Copy the nested class body verbatim into `AsyncRefreshDispatcher.java`, top-level and package-private:

```java
package org.waabox.andersoni;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dispatches catalog refresh operations to virtual threads with
 * per-catalog serialization and event coalescing.
 * ... (keep the existing class JavaDoc) ...
 */
final class AsyncRefreshDispatcher {
  // existing fields, constructor and dispatch() verbatim
}
```

- [ ] **Step 2: Remove the nested class from `Andersoni.java`**

Delete the `private static final class AsyncRefreshDispatcher { ... }` block. Remove the imports that become unused in `Andersoni.java` (`Semaphore`, `HashMap` if nothing else uses it). Keep `AtomicBoolean` (used by `started`/`stopped`).

- [ ] **Step 3: Run the core test suite**

Run: `mvn -q -pl andersoni-core test`
Expected: PASS, no behavior change.

- [ ] **Step 4: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/AsyncRefreshDispatcher.java \
        andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java
git commit -m "Extract AsyncRefreshDispatcher into its own file

The reconciliation loop will share the dispatcher with the sync-event
path, so it can no longer be a private nested class of Andersoni."
```

---

### Task 5: `SnapshotStoreBridge` and applied-hash bookkeeping

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/SnapshotStoreBridge.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/InMemorySnapshotStore.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/SnapshotStoreBridgeTest.java`

**Interfaces:**
- Produces (package-private):
  - `SnapshotStoreBridge(SnapshotStore store)` — `store` may be null.
  - `boolean isConfigured()`
  - `boolean supports(Catalog<?> catalog)` — store present and catalog has a serializer.
  - `Optional<SnapshotMetadata> describe(String catalogName)`
  - `boolean load(Catalog<?> catalog)` — true when loaded; sets applied hash.
  - `void save(Catalog<?> catalog)` — no-op when unsupported; sets applied hash.
  - `void markUnknown(String catalogName)` — clears applied hash.
  - `Optional<String> appliedStoreHash(String catalogName)`
  - `static String sha256Hex(byte[] bytes)`
- Produces (test helper): `InMemorySnapshotStore` with `put`, `get`, `clear`, `failNextSave`, `failDescribe`, `failLoad`, `describeCalls()`, `loadCalls()`, `saveCalls()`.

- [ ] **Step 1: Write the test helper**

`andersoni-core/src/test/java/org/waabox/andersoni/InMemorySnapshotStore.java`:

```java
package org.waabox.andersoni;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.waabox.andersoni.snapshot.SerializedSnapshot;
import org.waabox.andersoni.snapshot.SnapshotMetadata;
import org.waabox.andersoni.snapshot.SnapshotStore;

/**
 * An in-memory {@link SnapshotStore} for tests, with failure switches and
 * call counters.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
final class InMemorySnapshotStore implements SnapshotStore {

  private final Map<String, SerializedSnapshot> snapshots = new ConcurrentHashMap<>();
  private final AtomicInteger describeCalls = new AtomicInteger();
  private final AtomicInteger loadCalls = new AtomicInteger();
  private final AtomicInteger saveCalls = new AtomicInteger();

  /** When true, the next save throws and the flag resets. */
  volatile boolean failNextSave;

  /** When true, every describe throws. */
  volatile boolean failDescribe;

  /** When true, every load throws. */
  volatile boolean failLoad;

  @Override
  public void save(final String catalogName, final SerializedSnapshot snapshot) {
    saveCalls.incrementAndGet();
    if (failNextSave) {
      failNextSave = false;
      throw new IllegalStateException("simulated save failure");
    }
    snapshots.put(catalogName, snapshot);
  }

  @Override
  public Optional<SerializedSnapshot> load(final String catalogName) {
    loadCalls.incrementAndGet();
    if (failLoad) {
      throw new IllegalStateException("simulated load failure");
    }
    return Optional.ofNullable(snapshots.get(catalogName));
  }

  @Override
  public Optional<SnapshotMetadata> describe(final String catalogName) {
    describeCalls.incrementAndGet();
    if (failDescribe) {
      throw new IllegalStateException("simulated describe failure");
    }
    return Optional.ofNullable(snapshots.get(catalogName)).map(SnapshotMetadata::of);
  }

  void put(final String catalogName, final SerializedSnapshot snapshot) {
    snapshots.put(catalogName, snapshot);
  }

  Optional<SerializedSnapshot> get(final String catalogName) {
    return Optional.ofNullable(snapshots.get(catalogName));
  }

  void clear() {
    snapshots.clear();
  }

  int describeCalls() {
    return describeCalls.get();
  }

  int loadCalls() {
    return loadCalls.get();
  }

  int saveCalls() {
    return saveCalls.get();
  }
}
```

- [ ] **Step 2: Write the failing test**

`andersoni-core/src/test/java/org/waabox/andersoni/SnapshotStoreBridgeTest.java`:

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.waabox.andersoni.snapshot.SerializedSnapshot;
import org.waabox.andersoni.snapshot.SnapshotSerializer;

/**
 * Tests for {@link SnapshotStoreBridge}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SnapshotStoreBridgeTest {

  /** Serializes strings one per line; deterministic round trip. */
  static final class LinesSerializer implements SnapshotSerializer<String> {

    @Override
    public byte[] serialize(final List<String> items) {
      return String.join("\n", items).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public List<String> deserialize(final byte[] data) {
      final String text = new String(data, StandardCharsets.UTF_8);
      return text.isEmpty() ? List.of() : Arrays.asList(text.split("\n"));
    }
  }

  private static Catalog<String> catalogWithSerializer(final List<String> data) {
    return Catalog.of(String.class)
        .named("cities")
        .data(data)
        .serializer(new LinesSerializer())
        .index("by-self").by(s -> s, Function.identity())
        .build();
  }

  private static SerializedSnapshot snapshotOf(final List<String> items,
      final long version) {
    final byte[] bytes = new LinesSerializer().serialize(items);
    return new SerializedSnapshot("cities", SnapshotStoreBridge.sha256Hex(bytes),
        version, Instant.parse("2026-09-11T10:00:00Z"), bytes);
  }

  @Test
  void whenLoading_givenStoredSnapshot_shouldRefreshCatalogAndRecordAppliedHash() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SerializedSnapshot stored = snapshotOf(List.of("Madrid", "Tokyo"), 5L);
    store.put("cities", stored);
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> catalog = catalogWithSerializer(List.of());

    final boolean loaded = bridge.load(catalog);

    assertTrue(loaded);
    assertEquals(2, catalog.currentSnapshot().data().size());
    assertEquals(stored.hash(), bridge.appliedStoreHash("cities").orElseThrow());
  }

  @Test
  void whenLoading_givenEmptyStore_shouldReturnFalseAndLeaveAppliedHashEmpty() {
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(new InMemorySnapshotStore());
    final Catalog<String> catalog = catalogWithSerializer(List.of());

    assertFalse(bridge.load(catalog));
    assertTrue(bridge.appliedStoreHash("cities").isEmpty());
  }

  @Test
  void whenSaving_givenBootstrappedCatalog_shouldStoreBytesAndRecordHashOfStoredBytes() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> catalog = catalogWithSerializer(List.of("Madrid"));
    catalog.bootstrap();

    bridge.save(catalog);

    final SerializedSnapshot saved = store.get("cities").orElseThrow();
    assertEquals(SnapshotStoreBridge.sha256Hex(saved.data()), saved.hash());
    assertEquals(saved.hash(), bridge.appliedStoreHash("cities").orElseThrow());
  }

  @Test
  void whenMarkingUnknown_givenAppliedHash_shouldClearIt() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    store.put("cities", snapshotOf(List.of("Madrid"), 1L));
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    bridge.load(catalogWithSerializer(List.of()));

    bridge.markUnknown("cities");

    assertTrue(bridge.appliedStoreHash("cities").isEmpty());
  }

  @Test
  void whenCheckingSupport_givenCatalogWithoutSerializer_shouldBeFalse() {
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(new InMemorySnapshotStore());
    final Catalog<String> catalog = Catalog.of(String.class)
        .named("plain")
        .data(List.of("a"))
        .index("by-self").by(s -> s, Function.identity())
        .build();

    assertFalse(bridge.supports(catalog));
  }

  @Test
  void whenUsingBridge_givenNoStore_shouldBeInertEverywhere() {
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(null);
    final Catalog<String> catalog = catalogWithSerializer(List.of("Madrid"));
    catalog.bootstrap();

    assertFalse(bridge.isConfigured());
    assertFalse(bridge.supports(catalog));
    assertFalse(bridge.load(catalog));
    assertTrue(bridge.describe("cities").isEmpty());
    bridge.save(catalog);
    assertTrue(bridge.appliedStoreHash("cities").isEmpty());
  }

  @Test
  void whenDescribing_givenStoredSnapshot_shouldReturnItsMetadata() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SerializedSnapshot stored = snapshotOf(List.of("Madrid"), 3L);
    store.put("cities", stored);
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);

    assertEquals(stored.hash(), bridge.describe("cities").orElseThrow().hash());
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -q -pl andersoni-core test -Dtest=SnapshotStoreBridgeTest`
Expected: compilation failure (`SnapshotStoreBridge` missing).

- [ ] **Step 4: Implement the bridge**

`andersoni-core/src/main/java/org/waabox/andersoni/SnapshotStoreBridge.java`:

```java
package org.waabox.andersoni;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.waabox.andersoni.snapshot.SerializedSnapshot;
import org.waabox.andersoni.snapshot.SnapshotMetadata;
import org.waabox.andersoni.snapshot.SnapshotSerializer;
import org.waabox.andersoni.snapshot.SnapshotStore;

/**
 * The single place where the engine reads and writes the
 * {@link SnapshotStore}, and the owner of the per-catalog
 * <em>applied store hash</em>.
 *
 * <p>The applied store hash is the hash of the store object this node last
 * loaded or wrote for a catalog. Reconciliation compares the store's current
 * hash against it, never against the in-memory snapshot hash, because the
 * in-memory hash is recomputed after deserializing and may differ from the
 * stored one when a serializer's round trip is not byte-stable. An absent
 * applied hash always means "this node must reconcile".
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
final class SnapshotStoreBridge {

  /** The class logger. */
  private static final Logger log = LoggerFactory.getLogger(SnapshotStoreBridge.class);

  /** The configured store, or null when none. */
  private final SnapshotStore store;

  /** The store hash last applied or written per catalog name. */
  private final Map<String, String> appliedStoreHash = new ConcurrentHashMap<>();

  /**
   * Creates a bridge over the given store.
   *
   * @param store the store, may be null when snapshots are not persisted
   */
  SnapshotStoreBridge(final SnapshotStore store) {
    this.store = store;
  }

  /** @return true if a store is configured. */
  boolean isConfigured() {
    return store != null;
  }

  /**
   * @param catalog the catalog, never null
   * @return true if the catalog can be persisted: a store is configured and
   *         the catalog has a serializer
   */
  boolean supports(final Catalog<?> catalog) {
    return store != null && catalog.serializer().isPresent();
  }

  /**
   * Describes the stored snapshot of a catalog without loading it.
   *
   * @param catalogName the catalog name, never null
   * @return the metadata, or empty if no store or no snapshot
   */
  Optional<SnapshotMetadata> describe(final String catalogName) {
    if (store == null) {
      return Optional.empty();
    }
    return store.describe(catalogName);
  }

  /**
   * @param catalogName the catalog name, never null
   * @return the store hash this node last applied or wrote, or empty
   */
  Optional<String> appliedStoreHash(final String catalogName) {
    return Optional.ofNullable(appliedStoreHash.get(catalogName));
  }

  /**
   * Forgets the applied hash: the local state changed from a source other
   * than the store (a local refresh, a DataLoader fallback), so the next
   * reconciliation pass must act.
   *
   * @param catalogName the catalog name, never null
   */
  void markUnknown(final String catalogName) {
    appliedStoreHash.remove(catalogName);
  }

  /**
   * Loads the stored snapshot into the catalog.
   *
   * @param catalog the catalog to load, never null
   * @return true if a snapshot was loaded; false if no store, no serializer
   *         or no snapshot exists
   */
  @SuppressWarnings("unchecked")
  boolean load(final Catalog<?> catalog) {
    if (!supports(catalog)) {
      return false;
    }
    final Optional<SerializedSnapshot> snapshotOpt = store.load(catalog.name());
    if (snapshotOpt.isEmpty()) {
      return false;
    }
    final SerializedSnapshot serialized = snapshotOpt.get();
    final SnapshotSerializer<Object> serializer =
        (SnapshotSerializer<Object>) catalog.serializer().get();
    final List<Object> data = serializer.deserialize(serialized.data());
    final Catalog<Object> typedCatalog = (Catalog<Object>) catalog;
    typedCatalog.refresh(data);
    appliedStoreHash.put(catalog.name(), serialized.hash());
    log.debug("Applied store snapshot for catalog '{}' (hash={})",
        catalog.name(), serialized.hash());
    return true;
  }

  /**
   * Serializes and saves the catalog's current snapshot.
   *
   * <p>The stored hash is the SHA-256 of the bytes actually written, so a
   * store can verify integrity on load without depending on two separate
   * {@code serialize()} calls agreeing.
   *
   * @param catalog the catalog to save, never null
   */
  @SuppressWarnings("unchecked")
  void save(final Catalog<?> catalog) {
    if (!supports(catalog)) {
      return;
    }
    final SnapshotSerializer<Object> serializer =
        (SnapshotSerializer<Object>) catalog.serializer().get();
    final Snapshot<?> snapshot = catalog.currentSnapshot();
    final List<Object> data = (List<Object>) snapshot.data();
    final byte[] bytes = serializer.serialize(data);
    final String hash = sha256Hex(bytes);
    final SerializedSnapshot serialized = new SerializedSnapshot(
        catalog.name(), hash, snapshot.version(), snapshot.createdAt(), bytes);
    store.save(catalog.name(), serialized);
    appliedStoreHash.put(catalog.name(), hash);
    log.debug("Saved store snapshot for catalog '{}' (hash={})", catalog.name(), hash);
  }

  /**
   * Returns the lowercase hex SHA-256 digest of the given bytes.
   *
   * @param bytes the bytes to digest, never null
   * @return the hex-encoded digest, never null
   */
  static String sha256Hex(final byte[] bytes) {
    try {
      final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      final StringBuilder builder = new StringBuilder(digest.length * 2);
      for (final byte b : digest) {
        builder.append(Character.forDigit((b >> 4) & 0xF, 16));
        builder.append(Character.forDigit(b & 0xF, 16));
      }
      return builder.toString();
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }
}
```

- [ ] **Step 5: Run the bridge test to verify it passes**

Run: `mvn -q -pl andersoni-core test -Dtest=SnapshotStoreBridgeTest`
Expected: PASS.

- [ ] **Step 6: Route `Andersoni` through the bridge**

In `Andersoni.java`:

1. Replace the field `private final SnapshotStore snapshotStore;` with `private final SnapshotStoreBridge storeBridge;`. In the constructor: `this.storeBridge = new SnapshotStoreBridge(snapshotStore);` (keep the constructor parameter).
2. Delete `tryLoadFromSnapshotStore`, `saveSnapshotIfPossible` and `sha256Hex`; remove the now-unused imports (`MessageDigest`, `NoSuchAlgorithmException`, `SerializedSnapshot`, `SnapshotSerializer`).
3. `bootstrapWithRetry` step 1: replace `tryLoadFromSnapshotStore(name, catalog)` with `storeBridge.load(catalog)`.
4. `bootstrapAsLeader`, inside the `try`: 
   ```java
   catalog.bootstrap();
   storeBridge.markUnknown(name);
   metrics.snapshotLoaded(name, "dataLoader");
   storeBridge.save(catalog);
   reportIndexSizes(catalog);
   return;
   ```
5. `bootstrapAsFollower`: replace `tryLoadFromSnapshotStore(name, catalog)` with `storeBridge.load(catalog)`.
6. `tryDataLoaderAsFallback`, inside the `try`:
   ```java
   catalog.bootstrap();
   storeBridge.markUnknown(name);
   storeBridge.save(catalog);
   metrics.snapshotLoaded(name, "followerDataLoaderFallback");
   reportIndexSizes(catalog);
   ```
7. `refreshAndSync`, leader path:
   ```java
   catalog.refresh();
   storeBridge.markUnknown(catalogName);
   reportIndexSizes(catalog);
   storeBridge.save(catalog);
   publishRefreshEvent(catalog);
   ```
   and extract the publish block into:
   ```java
   /**
    * Broadcasts the catalog's current snapshot as a result
    * {@link org.waabox.andersoni.sync.RefreshKind#EVENT}, if a sync
    * strategy is configured. Publish failures are logged and reported, never
    * thrown: the reconciliation loop is the retry path.
    *
    * @param catalog the catalog whose snapshot was just refreshed, never null
    */
   private void publishRefreshEvent(final Catalog<?> catalog) {
     if (syncStrategy == null) {
       return;
     }
     final Snapshot<?> snapshot = catalog.currentSnapshot();
     final RefreshEvent event = new RefreshEvent(
         catalog.name(), nodeId, snapshot.version(), snapshot.hash(), Instant.now());
     try {
       syncStrategy.publish(event);
       metrics.syncPublished(catalog.name());
     } catch (final Exception e) {
       log.error("Failed to publish sync event for catalog '{}': {}",
           catalog.name(), e.getMessage(), e);
       metrics.syncPublishFailed(catalog.name(), e);
     }
   }
   ```
8. `refresh(String)`: after `catalog.refresh();` add `storeBridge.markUnknown(catalogName);`. Extend its JavaDoc with: "With reconciliation active, a follower's local refresh is overwritten by the store's snapshot on the next pass; on the leader, the next pass re-saves and re-publishes the refreshed data."
9. `refreshFromEvent`:
   ```java
   try {
     if (storeBridge.load(catalog)) {
       log.info("Refreshed catalog '{}' from snapshot store", catalogName);
       reportIndexSizes(catalog);
       return;
     }
     catalog.refresh();
     storeBridge.markUnknown(catalogName);
     log.info("Refreshed catalog '{}' from DataLoader", catalogName);
     reportIndexSizes(catalog);
   } catch (final Exception e) {
     ... unchanged ...
   }
   ```

- [ ] **Step 7: Run the core suite**

Run: `mvn -q -pl andersoni-core test`
Expected: PASS. The existing `AndersoniTest` expectations (`serializer.serialize` called twice on leader bootstrap, stored hash equal to the digest of stored bytes) still hold.

- [ ] **Step 8: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/SnapshotStoreBridge.java \
        andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java \
        andersoni-core/src/test/java/org/waabox/andersoni/InMemorySnapshotStore.java \
        andersoni-core/src/test/java/org/waabox/andersoni/SnapshotStoreBridgeTest.java
git commit -m "Centralize snapshot store access in SnapshotStoreBridge

Moves load/save out of Andersoni and records, per catalog, the store
hash this node last applied or wrote. That bookkeeping is what the
reconciliation loop will compare against, independent of whether the
serializer round trip is byte-stable."
```

---

### Task 6: `SnapshotReconciler`

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/SnapshotReconciler.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/SnapshotReconcilerTest.java`

**Interfaces:**
- Consumes: `SnapshotStoreBridge`, `ReconciliationPolicy`, `SyncState`, `AndersoniMetrics` drift methods, `LeaderElectionStrategy.onLeaderChange`.
- Produces (package-private):
  ```java
  SnapshotReconciler(
      Map<String, Catalog<?>> catalogsByName,
      SnapshotStoreBridge bridge,
      LeaderElectionStrategy leaderElection,
      BiConsumer<String, Runnable> dispatcher,
      AndersoniMetrics metrics,
      ReconciliationPolicy policy,
      Set<String> failedCatalogs,
      Consumer<Catalog<?>> followerRepair,
      Consumer<Catalog<?>> leaderRepair)
  void start(); void stop(); void requestPass(); void runPass();
  SyncState syncState(String catalogName); Optional<Instant> lastReconciledAt(String catalogName);
  ```

- [ ] **Step 1: Write the failing test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.waabox.andersoni.leader.LeaderChangeListener;
import org.waabox.andersoni.leader.LeaderElectionStrategy;
import org.waabox.andersoni.metrics.AndersoniMetrics;
import org.waabox.andersoni.snapshot.SerializedSnapshot;
import org.waabox.andersoni.snapshot.SnapshotSerializer;

/**
 * Tests for {@link SnapshotReconciler}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SnapshotReconcilerTest {

  /** Runs repairs on the calling thread so tests are deterministic. */
  private static final BiConsumer<String, Runnable> INLINE = (name, task) -> task.run();

  /** Serializes strings one per line; deterministic round trip. */
  static final class LinesSerializer implements SnapshotSerializer<String> {

    @Override
    public byte[] serialize(final List<String> items) {
      return String.join("\n", items).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public List<String> deserialize(final byte[] data) {
      final String text = new String(data, StandardCharsets.UTF_8);
      return text.isEmpty() ? List.of() : Arrays.asList(text.split("\n"));
    }
  }

  /** Deserializes in reverse order: the in-memory hash never equals the
   *  stored one, which must not cause a reload loop. */
  static final class ReversingSerializer extends LinesSerializer {

    @Override
    public List<String> deserialize(final byte[] data) {
      final List<String> items = new ArrayList<>(super.deserialize(data));
      java.util.Collections.reverse(items);
      return items;
    }
  }

  /** Leader election whose role tests flip at will. */
  static final class ToggleLeaderElection implements LeaderElectionStrategy {

    private volatile boolean leader;
    private final List<LeaderChangeListener> listeners = new CopyOnWriteArrayList<>();

    ToggleLeaderElection(final boolean initiallyLeader) {
      leader = initiallyLeader;
    }

    void become(final boolean isLeader) {
      leader = isLeader;
      for (final LeaderChangeListener listener : listeners) {
        listener.onLeaderChange(isLeader);
      }
    }

    @Override
    public void start() {
    }

    @Override
    public boolean isLeader() {
      return leader;
    }

    @Override
    public void onLeaderChange(final LeaderChangeListener listener) {
      listeners.add(listener);
    }

    @Override
    public void stop() {
    }
  }

  /** Collects drift metric calls. */
  static final class RecordingMetrics implements AndersoniMetrics {

    final List<String> detected = new CopyOnWriteArrayList<>();
    final List<String> repaired = new CopyOnWriteArrayList<>();
    final List<String> failed = new CopyOnWriteArrayList<>();

    @Override
    public void snapshotLoaded(final String catalogName, final String source) {
    }

    @Override
    public void refreshFailed(final String catalogName, final Throwable cause) {
    }

    @Override
    public void indexSizeReported(final String catalogName, final String indexName,
        final long estimatedSizeBytes) {
    }

    @Override
    public void driftDetected(final String catalogName) {
      detected.add(catalogName);
    }

    @Override
    public void driftRepaired(final String catalogName) {
      repaired.add(catalogName);
    }

    @Override
    public void reconcileFailed(final String catalogName, final Throwable cause) {
      failed.add(catalogName);
    }
  }

  private static Catalog<String> catalog(final String name,
      final SnapshotSerializer<String> serializer, final List<String> data) {
    return Catalog.of(String.class)
        .named(name)
        .data(data)
        .serializer(serializer)
        .index("by-self").by(s -> s, Function.identity())
        .build();
  }

  private static SerializedSnapshot snapshotOf(final String name,
      final List<String> items, final long version) {
    final byte[] bytes = new LinesSerializer().serialize(items);
    return new SerializedSnapshot(name, SnapshotStoreBridge.sha256Hex(bytes),
        version, Instant.parse("2026-09-11T10:00:00Z"), bytes);
  }

  private static void awaitUntil(final BooleanSupplier condition,
      final Duration timeout) throws InterruptedException {
    final long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Condition not met within " + timeout);
      }
      Thread.sleep(20);
    }
  }

  /** Builds a reconciler with inline dispatch and repairs wired to the bridge. */
  private static SnapshotReconciler reconciler(final Map<String, Catalog<?>> catalogs,
      final SnapshotStoreBridge bridge, final LeaderElectionStrategy election,
      final RecordingMetrics metrics, final Set<String> failed,
      final List<String> followerRepairs, final List<String> leaderRepairs) {
    final Consumer<Catalog<?>> followerRepair = c -> {
      followerRepairs.add(c.name());
      if (!bridge.load(c)) {
        throw new IllegalStateException("nothing to load");
      }
    };
    final Consumer<Catalog<?>> leaderRepair = c -> {
      leaderRepairs.add(c.name());
      bridge.save(c);
    };
    return new SnapshotReconciler(catalogs, bridge, election, INLINE, metrics,
        ReconciliationPolicy.of(Duration.ofMillis(50)), failed, followerRepair,
        leaderRepair);
  }

  @Test
  void whenRunningPass_givenFollowerBehindStore_shouldRepairAndReportInSync() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of());
    store.put("cities", snapshotOf("cities", List.of("Madrid"), 1L));
    bridge.load(cities);
    store.put("cities", snapshotOf("cities", List.of("Madrid", "Tokyo"), 2L));
    final RecordingMetrics metrics = new RecordingMetrics();
    final List<String> followerRepairs = new ArrayList<>();
    final List<String> leaderRepairs = new ArrayList<>();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(false), metrics, ConcurrentHashMap.newKeySet(),
        followerRepairs, leaderRepairs);

    reconciler.runPass();

    assertEquals(List.of("cities"), followerRepairs);
    assertTrue(leaderRepairs.isEmpty());
    assertEquals(2, cities.currentSnapshot().data().size());
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("cities"));
    assertTrue(reconciler.lastReconciledAt("cities").isPresent());
    assertEquals(List.of("cities"), metrics.detected);
    assertEquals(List.of("cities"), metrics.repaired);
  }

  @Test
  void whenRunningPass_givenFollowerAtStoreHash_shouldNotRepair() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of());
    store.put("cities", snapshotOf("cities", List.of("Madrid"), 1L));
    bridge.load(cities);
    final RecordingMetrics metrics = new RecordingMetrics();
    final List<String> followerRepairs = new ArrayList<>();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(false), metrics, ConcurrentHashMap.newKeySet(),
        followerRepairs, new ArrayList<>());

    reconciler.runPass();

    assertTrue(followerRepairs.isEmpty());
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("cities"));
    assertTrue(metrics.detected.isEmpty());
  }

  @Test
  void whenRunningPass_givenFollowerAndEmptyStore_shouldDoNothing() {
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(new InMemorySnapshotStore());
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of("x"));
    cities.bootstrap();
    final List<String> followerRepairs = new ArrayList<>();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(false), new RecordingMetrics(),
        ConcurrentHashMap.newKeySet(), followerRepairs, new ArrayList<>());

    reconciler.runPass();

    assertTrue(followerRepairs.isEmpty());
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("cities"));
  }

  @Test
  void whenRunningPass_givenLeaderWithoutAppliedHash_shouldSaveAndReportInSync() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of("Madrid"));
    cities.bootstrap();
    final RecordingMetrics metrics = new RecordingMetrics();
    final List<String> leaderRepairs = new ArrayList<>();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(true), metrics, ConcurrentHashMap.newKeySet(),
        new ArrayList<>(), leaderRepairs);

    reconciler.runPass();

    assertEquals(List.of("cities"), leaderRepairs);
    assertTrue(store.get("cities").isPresent());
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("cities"));
    assertEquals(List.of("cities"), metrics.repaired);
  }

  @Test
  void whenRunningPass_givenLeaderAtStoreHash_shouldNotSave() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of());
    store.put("cities", snapshotOf("cities", List.of("Madrid"), 1L));
    bridge.load(cities);
    final List<String> leaderRepairs = new ArrayList<>();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(true), new RecordingMetrics(),
        ConcurrentHashMap.newKeySet(), new ArrayList<>(), leaderRepairs);

    reconciler.runPass();

    assertTrue(leaderRepairs.isEmpty());
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("cities"));
  }

  @Test
  void whenRunningPass_givenLeaderWithFailedCatalog_shouldStayDriftedWithoutRepair() {
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(new InMemorySnapshotStore());
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of());
    final Set<String> failed = ConcurrentHashMap.newKeySet();
    failed.add("cities");
    final List<String> leaderRepairs = new ArrayList<>();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(true), new RecordingMetrics(), failed,
        new ArrayList<>(), leaderRepairs);

    reconciler.runPass();

    assertTrue(leaderRepairs.isEmpty());
    assertEquals(SyncState.DRIFTED, reconciler.syncState("cities"));
  }

  @Test
  void whenRunningPass_givenDescribeThrows_shouldReportFailureAndContinueWithOtherCatalogs() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> a = catalog("a", new LinesSerializer(), List.of("1"));
    final Catalog<String> b = catalog("b", new LinesSerializer(), List.of("2"));
    a.bootstrap();
    b.bootstrap();
    final RecordingMetrics metrics = new RecordingMetrics();
    final List<String> leaderRepairs = new ArrayList<>();
    final Map<String, Catalog<?>> catalogs = new java.util.LinkedHashMap<>();
    catalogs.put("a", a);
    catalogs.put("b", b);
    final SnapshotReconciler reconciler = reconciler(catalogs, bridge,
        new ToggleLeaderElection(true), metrics, ConcurrentHashMap.newKeySet(),
        new ArrayList<>(), leaderRepairs);
    store.failDescribe = true;

    reconciler.runPass();

    assertEquals(List.of("a", "b"), metrics.failed);
    assertTrue(leaderRepairs.isEmpty());
    assertEquals(SyncState.UNKNOWN, reconciler.syncState("a"));
  }

  @Test
  void whenRunningPass_givenRepairThrows_shouldReportFailureAndStayDrifted() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of());
    store.put("cities", snapshotOf("cities", List.of("Madrid"), 1L));
    final RecordingMetrics metrics = new RecordingMetrics();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(false), metrics, ConcurrentHashMap.newKeySet(),
        new ArrayList<>(), new ArrayList<>());
    store.failLoad = true;

    reconciler.runPass();

    assertEquals(SyncState.DRIFTED, reconciler.syncState("cities"));
    assertEquals(List.of("cities"), metrics.failed);
    assertTrue(metrics.repaired.isEmpty());
  }

  @Test
  void whenRunningPass_givenCatalogWithoutSerializer_shouldReportUnknown() {
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(new InMemorySnapshotStore());
    final Catalog<String> plain = Catalog.of(String.class)
        .named("plain")
        .data(List.of("a"))
        .index("by-self").by(s -> s, Function.identity())
        .build();
    plain.bootstrap();
    final SnapshotReconciler reconciler = reconciler(Map.of("plain", plain), bridge,
        new ToggleLeaderElection(false), new RecordingMetrics(),
        ConcurrentHashMap.newKeySet(), new ArrayList<>(), new ArrayList<>());

    reconciler.runPass();

    assertEquals(SyncState.UNKNOWN, reconciler.syncState("plain"));
    assertTrue(reconciler.lastReconciledAt("plain").isEmpty());
  }

  @Test
  void whenRunningPasses_givenUnstableSerializerRoundTrip_shouldNotRepairRepeatedly() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new ReversingSerializer(), List.of());
    store.put("cities", snapshotOf("cities", List.of("Madrid", "Tokyo"), 1L));
    final List<String> followerRepairs = new ArrayList<>();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(false), new RecordingMetrics(),
        ConcurrentHashMap.newKeySet(), followerRepairs, new ArrayList<>());

    reconciler.runPass();
    reconciler.runPass();
    reconciler.runPass();

    assertEquals(1, followerRepairs.size());
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("cities"));
  }

  @Test
  void whenPromotedToLeader_givenStartedReconciler_shouldRunPassImmediately()
      throws InterruptedException {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of("Madrid"));
    cities.bootstrap();
    final ToggleLeaderElection election = new ToggleLeaderElection(false);
    final List<String> leaderRepairs = new CopyOnWriteArrayList<>();
    final SnapshotReconciler reconciler = new SnapshotReconciler(Map.of("cities", cities),
        bridge, election, INLINE, new RecordingMetrics(),
        ReconciliationPolicy.of(Duration.ofHours(1)), ConcurrentHashMap.newKeySet(),
        c -> bridge.load(c), c -> {
          leaderRepairs.add(c.name());
          bridge.save(c);
        });
    reconciler.start();
    try {
      election.become(true);

      awaitUntil(() -> !leaderRepairs.isEmpty(), Duration.ofSeconds(5));
      assertEquals(List.of("cities"), leaderRepairs);
    } finally {
      reconciler.stop();
    }
  }

  @Test
  void whenStarted_givenShortInterval_shouldRunPassesPeriodically()
      throws InterruptedException {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of("Madrid"));
    cities.bootstrap();
    final SnapshotReconciler reconciler = new SnapshotReconciler(Map.of("cities", cities),
        bridge, new ToggleLeaderElection(false), INLINE, new RecordingMetrics(),
        ReconciliationPolicy.of(Duration.ofMillis(30)), ConcurrentHashMap.newKeySet(),
        c -> bridge.load(c), c -> bridge.save(c));
    reconciler.start();
    try {
      awaitUntil(() -> store.describeCalls() >= 3, Duration.ofSeconds(5));
    } finally {
      reconciler.stop();
    }
    final int callsAtStop = store.describeCalls();
    Thread.sleep(150);
    assertTrue(store.describeCalls() <= callsAtStop + 1,
        "No passes must run after stop()");
  }

  @Test
  void whenStopping_givenNeverStarted_shouldNotThrow() {
    final SnapshotReconciler reconciler = new SnapshotReconciler(Map.of(),
        new SnapshotStoreBridge(null), new ToggleLeaderElection(false), INLINE,
        new RecordingMetrics(), ReconciliationPolicy.defaultPolicy(),
        ConcurrentHashMap.newKeySet(), c -> { }, c -> { });

    reconciler.stop();
    reconciler.requestPass();
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl andersoni-core test -Dtest=SnapshotReconcilerTest`
Expected: compilation failure (`SnapshotReconciler` missing).

- [ ] **Step 3: Implement**

`andersoni-core/src/main/java/org/waabox/andersoni/SnapshotReconciler.java`:

```java
package org.waabox.andersoni;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.waabox.andersoni.leader.LeaderElectionStrategy;
import org.waabox.andersoni.metrics.AndersoniMetrics;
import org.waabox.andersoni.snapshot.SnapshotMetadata;

/**
 * The cluster's anti-entropy loop.
 *
 * <p>On every pass, for each catalog with a serializer, the store's snapshot
 * is described (metadata only) and its hash compared with the hash this node
 * last applied (see {@link SnapshotStoreBridge}):
 * <ul>
 *   <li>a <b>follower</b> whose applied hash differs from the store's reloads
 *       from the store (never from the DataLoader: the store is the
 *       authority);</li>
 *   <li>the <b>leader</b> whose applied hash is missing or differs re-saves
 *       and re-publishes, which also covers a save that failed during
 *       {@code refreshAndSync};</li>
 *   <li>a node promoted to leader runs a pass immediately.</li>
 * </ul>
 *
 * <p>Repairs run through the shared dispatcher so they serialize with
 * sync-event reloads per catalog. A failed step is reported and simply
 * retried on the next pass; the loop is the retry mechanism.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
final class SnapshotReconciler {

  /** The class logger. */
  private static final Logger log = LoggerFactory.getLogger(SnapshotReconciler.class);

  /** Maximum random jitter added to each interval, as a ratio of it. */
  private static final double MAX_JITTER_RATIO = 0.2;

  private final Map<String, Catalog<?>> catalogsByName;
  private final SnapshotStoreBridge bridge;
  private final LeaderElectionStrategy leaderElection;
  private final BiConsumer<String, Runnable> dispatcher;
  private final AndersoniMetrics metrics;
  private final ReconciliationPolicy policy;
  private final Set<String> failedCatalogs;
  private final Consumer<Catalog<?>> followerRepair;
  private final Consumer<Catalog<?>> leaderRepair;

  private final Map<String, SyncState> syncStates = new ConcurrentHashMap<>();
  private final Map<String, Instant> lastReconciledAt = new ConcurrentHashMap<>();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile ScheduledExecutorService scheduler;

  /**
   * Creates a reconciler. Nothing runs until {@link #start()}.
   *
   * @param catalogsByName the registered catalogs, never null
   * @param bridge         the store bridge, never null
   * @param leaderElection the leader election, never null
   * @param dispatcher     runs a repair for a catalog name, never null
   * @param metrics        the metrics sink, never null
   * @param policy         the pass interval, never null, must be enabled
   * @param failedCatalogs the catalogs whose bootstrap failed, never null
   * @param followerRepair reloads a catalog from the store, never null
   * @param leaderRepair   re-saves and re-publishes a catalog, never null
   */
  SnapshotReconciler(final Map<String, Catalog<?>> catalogsByName,
      final SnapshotStoreBridge bridge,
      final LeaderElectionStrategy leaderElection,
      final BiConsumer<String, Runnable> dispatcher,
      final AndersoniMetrics metrics,
      final ReconciliationPolicy policy,
      final Set<String> failedCatalogs,
      final Consumer<Catalog<?>> followerRepair,
      final Consumer<Catalog<?>> leaderRepair) {
    this.catalogsByName = catalogsByName;
    this.bridge = bridge;
    this.leaderElection = leaderElection;
    this.dispatcher = dispatcher;
    this.metrics = metrics;
    this.policy = policy;
    this.failedCatalogs = failedCatalogs;
    this.followerRepair = followerRepair;
    this.leaderRepair = leaderRepair;
  }

  /** Starts the scheduler, registers for leader changes, schedules the first pass. */
  void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      final Thread thread = new Thread(r, "andersoni-reconciler");
      thread.setDaemon(true);
      return thread;
    });
    leaderElection.onLeaderChange(isLeader -> {
      if (isLeader) {
        log.info("Promoted to leader; running an immediate reconciliation pass");
        requestPass();
      }
    });
    scheduleNext();
    log.info("Snapshot reconciliation started, interval {} (+ up to {}% jitter)",
        policy.interval(), (int) (MAX_JITTER_RATIO * 100));
  }

  /** Stops the scheduler. Idempotent; safe when never started. */
  void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    final ScheduledExecutorService current = scheduler;
    scheduler = null;
    if (current != null) {
      current.shutdownNow();
    }
    log.info("Snapshot reconciliation stopped");
  }

  /** Runs a pass on the reconciler thread as soon as possible. No-op when stopped. */
  void requestPass() {
    final ScheduledExecutorService current = scheduler;
    if (current == null) {
      return;
    }
    current.execute(this::runPassSafely);
  }

  /**
   * Runs one pass on the calling thread. Repairs are handed to the
   * dispatcher, so they may complete after this method returns.
   */
  void runPass() {
    final boolean leader = leaderElection.isLeader();
    for (final Map.Entry<String, Catalog<?>> entry : catalogsByName.entrySet()) {
      reconcileCatalog(entry.getKey(), entry.getValue(), leader);
    }
  }

  /**
   * @param catalogName the catalog name, never null
   * @return the state recorded by the last pass, {@link SyncState#UNKNOWN} if none
   */
  SyncState syncState(final String catalogName) {
    return syncStates.getOrDefault(catalogName, SyncState.UNKNOWN);
  }

  /**
   * @param catalogName the catalog name, never null
   * @return when the last pass checked this catalog, or empty
   */
  Optional<Instant> lastReconciledAt(final String catalogName) {
    return Optional.ofNullable(lastReconciledAt.get(catalogName));
  }

  private void scheduleNext() {
    final ScheduledExecutorService current = scheduler;
    if (current == null || !running.get()) {
      return;
    }
    current.schedule(() -> {
      runPassSafely();
      scheduleNext();
    }, nextDelayMillis(), TimeUnit.MILLISECONDS);
  }

  private long nextDelayMillis() {
    final long base = policy.interval().toMillis();
    final long jitter = (long) (base * MAX_JITTER_RATIO * ThreadLocalRandom.current().nextDouble());
    return base + jitter;
  }

  private void runPassSafely() {
    try {
      runPass();
    } catch (final RuntimeException e) {
      log.error("Reconciliation pass failed: {}", e.getMessage(), e);
    }
  }

  private void reconcileCatalog(final String name, final Catalog<?> catalog,
      final boolean leader) {
    if (!bridge.supports(catalog)) {
      syncStates.put(name, SyncState.UNKNOWN);
      return;
    }

    final Optional<SnapshotMetadata> metadata;
    try {
      metadata = bridge.describe(name);
    } catch (final RuntimeException e) {
      log.warn("Reconciliation could not describe catalog '{}' in the snapshot store: {}",
          name, e.getMessage());
      metrics.reconcileFailed(name, e);
      return;
    }

    final Optional<String> storeHash = metadata.map(SnapshotMetadata::hash);
    final Optional<String> applied = bridge.appliedStoreHash(name);
    lastReconciledAt.put(name, Instant.now());

    final boolean inSync = leader
        ? applied.isPresent() && applied.equals(storeHash)
        : storeHash.isEmpty() || storeHash.equals(applied);

    if (inSync) {
      syncStates.put(name, SyncState.IN_SYNC);
      return;
    }

    syncStates.put(name, SyncState.DRIFTED);
    metrics.driftDetected(name);

    if (leader && failedCatalogs.contains(name)) {
      log.warn("Catalog '{}' drifted from the snapshot store but this leader has no"
          + " snapshot to publish (bootstrap failed); waiting for bootstrap", name);
      return;
    }

    log.info("Catalog '{}' drifted from the snapshot store (role={}, store={}, applied={});"
        + " repairing", name, leader ? "leader" : "follower",
        storeHash.orElse("<none>"), applied.orElse("<none>"));

    final Consumer<Catalog<?>> repair = leader ? leaderRepair : followerRepair;
    dispatcher.accept(name, () -> {
      try {
        repair.accept(catalog);
        syncStates.put(name, SyncState.IN_SYNC);
        metrics.driftRepaired(name);
        log.info("Catalog '{}' reconciled with the snapshot store", name);
      } catch (final RuntimeException e) {
        log.warn("Reconciliation repair failed for catalog '{}': {}", name, e.getMessage(), e);
        metrics.reconcileFailed(name, e);
      }
    });
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl andersoni-core test -Dtest=SnapshotReconcilerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/SnapshotReconciler.java \
        andersoni-core/src/test/java/org/waabox/andersoni/SnapshotReconcilerTest.java
git commit -m "Add SnapshotReconciler anti-entropy loop

Periodically compares each catalog's applied store hash with the
snapshot store: followers reload on drift, the leader re-saves and
re-publishes, and a newly promoted leader runs a pass at once. Not yet
wired into Andersoni."
```

---

### Task 7: Wire the reconciler into `Andersoni`

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java`

**Interfaces:**
- Produces: `Andersoni.Builder.reconciliation(ReconciliationPolicy)`; public `void Andersoni.reconcile()`; package-private `void Andersoni.reconcileNow()` (synchronous pass for tests); `status()` now reports `syncState` and `lastReconciledAt`.

- [ ] **Step 1: Update strict-mock expectations in existing tests**

The reconciler registers a `LeaderChangeListener` at start whenever a snapshot store is configured. These `AndersoniTest` methods mock `LeaderElectionStrategy` **and** configure a snapshot store, so each needs, right after its `leaderElection.start(); expectLastCall().once();` lines:

```java
    leaderElection.onLeaderChange(anyObject(LeaderChangeListener.class));
    expectLastCall().once();
```

Methods (verify with `grep -n "createMock(LeaderElectionStrategy.class)" AndersoniTest.java` and check each for `.snapshotStore(`):
- `whenBootstrap_givenLeaderAndS3Fails_shouldFallbackToDataLoaderAndSaveToS3`
- `whenBootstrap_givenFollower_shouldRetryS3UntilSuccess`
- `whenBootstrap_givenFollowerPromotedToLeader_shouldSwitchToDataLoader`
- `whenBootstrap_givenFollowerWaiting_shouldFallbackToDataLoader`
- `whenBootstrap_givenFollowerDataLoaderFallbackFails_shouldMarkAsFailed`
- `whenBootstrap_givenSnapshotStoreThrowsOnLoad_shouldFallbackToDataLoader`

Add `import org.waabox.andersoni.leader.LeaderChangeListener;`. If the suite in Step 6 reports another test failing with "Unexpected method call onLeaderChange", apply the same two lines there.

- [ ] **Step 2: Write the failing tests**

Add to `AndersoniTest.java` the helpers and tests below (place the helpers next to `CapturingSnapshotStore`).

Helpers:

```java
  /** A sync strategy that records what was published and never delivers. */
  static final class RecordingSyncStrategy implements SyncStrategy {

    final List<RefreshEvent> published = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public void publish(final RefreshEvent event) {
      published.add(event);
    }

    @Override
    public void subscribe(final RefreshListener listener) {
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }
  }

  /** A round-trip serializer for Event: id|sport|venue per line. */
  static final class EventCodec implements SnapshotSerializer<Event> {

    @Override
    public byte[] serialize(final List<Event> items) {
      final StringBuilder builder = new StringBuilder();
      for (final Event event : items) {
        builder.append(event.id()).append('|')
            .append(event.sport().name()).append('|')
            .append(event.venue().name()).append('\n');
      }
      return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public List<Event> deserialize(final byte[] data) {
      final List<Event> events = new java.util.ArrayList<>();
      for (final String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
        if (line.isBlank()) {
          continue;
        }
        final String[] parts = line.split("\\|");
        events.add(new Event(parts[0], new Sport(parts[1]), new Venue(parts[2])));
      }
      return events;
    }
  }

  private static SerializedSnapshot eventsSnapshot(final List<Event> events,
      final long version) {
    final byte[] bytes = new EventCodec().serialize(events);
    return new SerializedSnapshot("events", SnapshotStoreBridge.sha256Hex(bytes),
        version, Instant.parse("2026-09-11T10:00:00Z"), bytes);
  }

  private static AndersoniStatus.CatalogStatus catalogStatus(final Andersoni andersoni,
      final String name) {
    return andersoni.status().catalogs().stream()
        .filter(c -> c.catalogName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static void awaitUntil(final java.util.function.BooleanSupplier condition,
      final Duration timeout) throws InterruptedException {
    final long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Condition not met within " + timeout);
      }
      Thread.sleep(20);
    }
  }

  /** Leader election fixed to a follower role. */
  static final class FollowerElection implements LeaderElectionStrategy {

    @Override
    public void start() {
    }

    @Override
    public boolean isLeader() {
      return false;
    }

    @Override
    public void onLeaderChange(final LeaderChangeListener listener) {
    }

    @Override
    public void stop() {
    }
  }
```

Tests:

```java
  @Test
  void whenReconciling_givenLeaderSaveFailedDuringRefresh_shouldResaveAndPublish()
      throws InterruptedException {
    final Event e1 = new Event("1", new Sport("Football"), new Venue("Maracana"));
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final RecordingSyncStrategy sync = new RecordingSyncStrategy();
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of(e1))
        .serializer(new EventCodec())
        .index("by-sport").by(Event::sport, Sport::name)
        .build();
    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .snapshotStore(store)
        .syncStrategy(sync)
        .reconciliation(ReconciliationPolicy.of(Duration.ofHours(1)))
        .build();
    andersoni.register(catalog);
    andersoni.start();
    store.failNextSave = true;
    assertThrows(IllegalStateException.class, () -> andersoni.refreshAndSync("events"));
    assertTrue(sync.published.isEmpty(), "A failed save must not publish");

    andersoni.reconcileNow();

    awaitUntil(() -> sync.published.size() == 1, Duration.ofSeconds(5));
    assertEquals(catalog.currentSnapshot().hash(), sync.published.get(0).hash());
    awaitUntil(() -> catalogStatus(andersoni, "events").syncState() == SyncState.IN_SYNC,
        Duration.ofSeconds(5));
    assertEquals(store.get("events").orElseThrow().hash(),
        sync.published.get(0).hash());

    andersoni.stop();
  }

  @Test
  void whenReconciling_givenFollowerBehindStore_shouldReloadFromStore()
      throws InterruptedException {
    final Event e1 = new Event("1", new Sport("Football"), new Venue("Maracana"));
    final Event e2 = new Event("2", new Sport("Tennis"), new Venue("Wimbledon"));
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    store.put("events", eventsSnapshot(List.of(e1), 1L));
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> {
          throw new IllegalStateException("followers must not query the source");
        })
        .serializer(new EventCodec())
        .index("by-sport").by(Event::sport, Sport::name)
        .build();
    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-2")
        .snapshotStore(store)
        .leaderElection(new FollowerElection())
        .reconciliation(ReconciliationPolicy.of(Duration.ofHours(1)))
        .build();
    andersoni.register(catalog);
    andersoni.start();
    assertEquals(1, andersoni.search("events", "by-sport", "Football").size());
    store.put("events", eventsSnapshot(List.of(e1, e2), 2L));

    andersoni.reconcileNow();

    awaitUntil(() -> andersoni.search("events", "by-sport", "Tennis").size() == 1,
        Duration.ofSeconds(5));
    awaitUntil(() -> catalogStatus(andersoni, "events").syncState() == SyncState.IN_SYNC,
        Duration.ofSeconds(5));
    assertTrue(catalogStatus(andersoni, "events").lastReconciledAt().isPresent());

    andersoni.stop();
  }

  @Test
  void whenReconciling_givenFollowerWhoseBootstrapFailed_shouldRecoverFromStore()
      throws InterruptedException {
    final Event e1 = new Event("1", new Sport("Football"), new Venue("Maracana"));
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> {
          throw new IllegalStateException("source down");
        })
        .serializer(new EventCodec())
        .index("by-sport").by(Event::sport, Sport::name)
        .build();
    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-2")
        .snapshotStore(store)
        .leaderElection(new FollowerElection())
        .retryPolicy(RetryPolicy.of(1, Duration.ofMillis(5)))
        .reconciliation(ReconciliationPolicy.of(Duration.ofHours(1)))
        .build();
    andersoni.register(catalog);
    andersoni.start();
    assertThrows(CatalogNotAvailableException.class,
        () -> andersoni.search("events", "by-sport", "Football"));
    store.put("events", eventsSnapshot(List.of(e1), 1L));

    andersoni.reconcileNow();

    awaitUntil(() -> catalogStatus(andersoni, "events").available(), Duration.ofSeconds(5));
    assertEquals(1, andersoni.search("events", "by-sport", "Football").size());
    awaitUntil(() -> catalogStatus(andersoni, "events").syncState() == SyncState.IN_SYNC,
        Duration.ofSeconds(5));

    andersoni.stop();
  }

  @Test
  void whenStatus_givenReconciliationDisabled_shouldReportUnknown() {
    final Event e1 = new Event("1", new Sport("Football"), new Venue("Maracana"));
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .serializer(new EventCodec())
        .index("by-sport").by(Event::sport, Sport::name)
        .build();
    final Andersoni andersoni = Andersoni.builder()
        .snapshotStore(new InMemorySnapshotStore())
        .reconciliation(ReconciliationPolicy.disabled())
        .build();
    andersoni.register(catalog);
    andersoni.start();

    final AndersoniStatus.CatalogStatus status = catalogStatus(andersoni, "events");

    assertEquals(SyncState.UNKNOWN, status.syncState());
    assertTrue(status.lastReconciledAt().isEmpty());
    assertTrue(andersoni.status().inSync());

    andersoni.stop();
  }

  @Test
  void whenReconcile_givenStopped_shouldThrow() {
    final Andersoni andersoni = Andersoni.builder()
        .snapshotStore(new InMemorySnapshotStore())
        .build();
    andersoni.start();
    andersoni.stop();

    assertThrows(IllegalStateException.class, andersoni::reconcile);
  }

  @Test
  void whenReconcile_givenNoSnapshotStore_shouldBeNoOp() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.start();

    assertDoesNotThrow(andersoni::reconcile);

    andersoni.stop();
  }
```

`Andersoni.search` on a failed catalog throws `CatalogNotAvailableException` (the `failedCatalogs` guard at the top of `search`), which is what the recovery test asserts before the store gets a snapshot.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn -q -pl andersoni-core test -Dtest=AndersoniTest`
Expected: compilation failure (`reconciliation`, `reconcile`, `reconcileNow` missing).

- [ ] **Step 4: Implement in `Andersoni.java`**

1. Fields:
   ```java
   /** The reconciliation policy. */
   private final ReconciliationPolicy reconciliationPolicy;

   /** The reconciler, or null when reconciliation is inactive. Written in
    *  start() and read by status()/reconcile(). */
   private volatile SnapshotReconciler reconciler;
   ```
   Constructor gains `final ReconciliationPolicy reconciliationPolicy` (last parameter) and assigns it.

2. `start()` becomes:
   ```java
   leaderElection.start();
   bootstrapAllCatalogs();
   asyncRefreshDispatcher = new AsyncRefreshDispatcher(catalogsByName.keySet());
   wireSyncListener();
   schedulePeriodicRefreshes();
   startReconciler();
   metrics.start(Collections.unmodifiableCollection(catalogsByName.values()), nodeId);
   ```
   with
   ```java
   /**
    * Starts the reconciliation loop when the policy is enabled and a
    * snapshot store is configured; otherwise logs why it is inactive.
    */
   private void startReconciler() {
     if (!reconciliationPolicy.enabled() || !storeBridge.isConfigured()) {
       log.info("Snapshot reconciliation inactive (enabled={}, snapshotStore={})",
           reconciliationPolicy.enabled(), storeBridge.isConfigured());
       return;
     }
     final SnapshotReconciler created = new SnapshotReconciler(
         catalogsByName,
         storeBridge,
         leaderElection,
         asyncRefreshDispatcher::dispatch,
         metrics,
         reconciliationPolicy,
         failedCatalogs,
         this::repairFollower,
         this::repairLeader);
     created.start();
     reconciler = created;
   }

   /**
    * Follower repair: reload the catalog from the store, which is the
    * authority. Never falls back to the DataLoader.
    *
    * @param catalog the drifted catalog, never null
    * @throws IllegalStateException if the store has no snapshot any more
    */
   private void repairFollower(final Catalog<?> catalog) {
     if (!storeBridge.load(catalog)) {
       throw new IllegalStateException("Snapshot for catalog '" + catalog.name()
           + "' disappeared from the store before it could be loaded");
     }
     failedCatalogs.remove(catalog.name());
     reportIndexSizes(catalog);
   }

   /**
    * Leader repair: re-save the current snapshot and re-publish it.
    *
    * @param catalog the drifted catalog, never null
    */
   private void repairLeader(final Catalog<?> catalog) {
     storeBridge.save(catalog);
     publishRefreshEvent(catalog);
   }
   ```

3. `stop()`: after `cancelScheduledRefreshes();` add
   ```java
   final SnapshotReconciler current = reconciler;
   if (current != null) {
     current.stop();
   }
   ```

4. Public and package-private entry points (place after `refresh(String)`):
   ```java
   /**
    * Runs a reconciliation pass as soon as possible on the reconciler thread
    * and returns without waiting.
    *
    * <p>This is the operational replacement for a manual refresh: it never
    * queries the DataLoader. Followers reload from the snapshot store if it
    * moved; the leader re-saves and re-publishes if the store is behind. It
    * is a no-op when reconciliation is inactive (disabled policy or no
    * snapshot store).
    *
    * @throws IllegalStateException if {@link #stop()} has been called
    */
   public void reconcile() {
     if (stopped.get()) {
       throw new IllegalStateException("Cannot reconcile after stop() has been called");
     }
     final SnapshotReconciler current = reconciler;
     if (current == null) {
       log.debug("reconcile() ignored: reconciliation is inactive");
       return;
     }
     current.requestPass();
   }

   /**
    * Runs a reconciliation pass on the calling thread. Repairs are still
    * dispatched asynchronously. Intended for tests.
    */
   void reconcileNow() {
     final SnapshotReconciler current = reconciler;
     if (current != null) {
       current.runPass();
     }
   }
   ```

5. `status()` / `buildCatalogStatus`: make `buildCatalogStatus` an instance method and fill the new components:
   ```java
   private AndersoniStatus.CatalogStatus buildCatalogStatus(final Catalog<?> catalog) {
     final SnapshotReconciler current = reconciler;
     final SyncState syncState = current == null
         ? SyncState.UNKNOWN : current.syncState(catalog.name());
     final Optional<Instant> lastReconciledAt = current == null
         ? Optional.empty() : current.lastReconciledAt(catalog.name());
     try {
       final Snapshot<?> snapshot = catalog.currentSnapshot();
       final CatalogInfo info = catalog.info();
       return new AndersoniStatus.CatalogStatus(
           catalog.name(), !failedCatalogs.contains(catalog.name()), snapshot.version(),
           snapshot.hash(), catalog.serializer().isPresent(), info.itemCount(),
           info.totalEstimatedSizeMB(), syncState, lastReconciledAt);
     } catch (final RuntimeException e) {
       return new AndersoniStatus.CatalogStatus(
           catalog.name(), false, 0L, "", false, 0, 0.0, syncState, lastReconciledAt);
     }
   }
   ```
   The `available` flag now reflects `failedCatalogs`. Today `Catalog.currentSnapshot()` never throws (it starts as `Snapshot.empty()`), so `available` was always `true`, contradicting its own JavaDoc ("false if it failed to load"). No existing test asserts `available()` on a failed catalog; the three existing `whenStatus_*` tests use bootstrapped catalogs and keep passing.

6. Builder: field `private ReconciliationPolicy reconciliation;`, method
   ```java
   /**
    * Sets the reconciliation policy. Defaults to
    * {@link ReconciliationPolicy#defaultPolicy()}: enabled, every 30
    * seconds, active only when a snapshot store is configured.
    *
    * @param thePolicy the policy, never null
    * @return this builder, never null
    */
   public Builder reconciliation(final ReconciliationPolicy thePolicy) {
     Objects.requireNonNull(thePolicy, "reconciliation policy must not be null");
     this.reconciliation = thePolicy;
     return this;
   }
   ```
   and in `build()`:
   ```java
   final ReconciliationPolicy resolvedReconciliation = reconciliation != null
       ? reconciliation : ReconciliationPolicy.defaultPolicy();
   ```
   passed as the last constructor argument. Update the Builder class JavaDoc defaults list with `reconciliation: ReconciliationPolicy.defaultPolicy()`.

7. Class JavaDoc of `Andersoni`: add one paragraph describing reconciliation (store is the authority; followers pull, leader pushes; `reconcile()` for on-demand).

- [ ] **Step 5: Run the new tests**

Run: `mvn -q -pl andersoni-core test -Dtest=AndersoniTest`
Expected: PASS.

- [ ] **Step 6: Run the full core suite**

Run: `mvn -q -pl andersoni-core test`
Expected: PASS. If a strict mock reports `Unexpected method call LeaderElectionStrategy.onLeaderChange(...)`, add the expectation from Step 1 to that test.

- [ ] **Step 7: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java \
        andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java
git commit -m "Run snapshot reconciliation inside Andersoni

Starts the reconciler when a snapshot store is configured (on by
default, every 30s), exposes reconcile() for on-demand passes, and
reports per-catalog sync state in status(). A follower whose bootstrap
failed now recovers as soon as the store has a snapshot."
```

---

### Task 8: `FileSystemSnapshotStore.describe`

**Files:**
- Modify: `andersoni-snapshot-fs/src/main/java/org/waabox/andersoni/snapshot/fs/FileSystemSnapshotStore.java`
- Test: `andersoni-snapshot-fs/src/test/java/org/waabox/andersoni/snapshot/fs/FileSystemSnapshotStoreTest.java`

**Interfaces:**
- Consumes: `SnapshotMetadata`, `SnapshotStore.describe`.

- [ ] **Step 1: Write the failing tests** (append to the existing test class; add imports for `SnapshotMetadata` and `java.nio.charset.StandardCharsets`)

```java
  @Test
  void whenDescribing_givenSavedSnapshot_shouldReturnMetadataWithoutData(
      @TempDir final Path tempDir) {
    final FileSystemSnapshotStore store = new FileSystemSnapshotStore(tempDir);
    final Instant createdAt = Instant.parse("2026-09-11T10:30:00Z");
    store.save("events", new SerializedSnapshot(
        "events", "hash-42", 42L, createdAt, "payload\n\nwith blank line".getBytes()));

    final Optional<SnapshotMetadata> metadata = store.describe("events");

    assertTrue(metadata.isPresent());
    assertEquals("events", metadata.get().catalogName());
    assertEquals("hash-42", metadata.get().hash());
    assertEquals(42L, metadata.get().version());
    assertEquals(createdAt, metadata.get().createdAt());
  }

  @Test
  void whenDescribing_givenNoSnapshot_shouldReturnEmpty(@TempDir final Path tempDir) {
    final FileSystemSnapshotStore store = new FileSystemSnapshotStore(tempDir);

    assertTrue(store.describe("missing").isEmpty());
  }

  @Test
  void whenDescribing_givenLegacyTwoFileLayout_shouldReturnMetadata(
      @TempDir final Path tempDir) throws Exception {
    final Path catalogDir = tempDir.resolve("events");
    Files.createDirectories(catalogDir);
    Files.write(catalogDir.resolve("snapshot.dat"), "legacy".getBytes());
    Files.writeString(catalogDir.resolve("snapshot.meta"),
        "hash=legacy-hash\nversion=7\ncreatedAt=2026-01-15T10:30:00Z\n");
    final FileSystemSnapshotStore store = new FileSystemSnapshotStore(tempDir);

    final Optional<SnapshotMetadata> metadata = store.describe("events");

    assertTrue(metadata.isPresent());
    assertEquals("legacy-hash", metadata.get().hash());
    assertEquals(7L, metadata.get().version());
  }

  @Test
  void whenDescribing_givenTraversalCatalogName_shouldThrow(@TempDir final Path tempDir) {
    final FileSystemSnapshotStore store = new FileSystemSnapshotStore(tempDir);

    assertThrows(IllegalArgumentException.class, () -> store.describe("../escape"));
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -pl andersoni-snapshot-fs -am test -Dtest=FileSystemSnapshotStoreTest -DfailIfNoTests=false`
Expected: PASS already. These are characterization tests: the inherited default `describe` delegates to `load`, so behavior is identical before and after the override. The override changes cost (header only, never the data bytes), which a unit test cannot observe; the reviewer checks Step 3 for that. The tests exist so the override cannot regress the legacy layout or the traversal guard.

- [ ] **Step 3: Implement the override**

Add imports `java.io.BufferedInputStream`, `java.io.ByteArrayOutputStream`, `java.io.InputStream`, `org.waabox.andersoni.snapshot.SnapshotMetadata`. Add after `load`:

```java
  /**
   * {@inheritDoc}
   *
   * <p>Reads only the metadata header of {@code snapshot.bin} (up to the
   * blank line), or the legacy {@code snapshot.meta} file, never the data
   * bytes.
   *
   * @throws UncheckedIOException if reading from the filesystem fails
   */
  @Override
  public Optional<SnapshotMetadata> describe(final String catalogName) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    validateCatalogName(catalogName);

    final Path catalogDir = baseDir.resolve(catalogName);
    final Path snapshotFile = catalogDir.resolve(SNAPSHOT_FILE);

    try {
      if (Files.exists(snapshotFile)) {
        final String header = readHeader(catalogName, snapshotFile);
        return Optional.of(SnapshotMetadata.of(
            parseSnapshot(catalogName, new byte[0], header)));
      }
      final Path dataFile = catalogDir.resolve(DATA_FILE);
      final Path metaFile = catalogDir.resolve(META_FILE);
      if (Files.exists(dataFile) && Files.exists(metaFile)) {
        return Optional.of(SnapshotMetadata.of(
            parseSnapshot(catalogName, new byte[0], Files.readString(metaFile))));
      }
      return Optional.empty();
    } catch (final IOException e) {
      throw new UncheckedIOException(
          "Failed to describe snapshot for catalog: " + catalogName, e);
    }
  }

  /**
   * Reads the metadata header of a single-file snapshot, stopping at the
   * blank line that separates it from the data.
   *
   * @param catalogName  the catalog name for error messages, never null
   * @param snapshotFile the file to read, never null
   *
   * @return the header text, never null
   *
   * @throws IOException           if reading fails
   * @throws IllegalStateException if the separator is missing
   */
  private static String readHeader(final String catalogName, final Path snapshotFile)
      throws IOException {
    try (InputStream in = new BufferedInputStream(Files.newInputStream(snapshotFile))) {
      final ByteArrayOutputStream header = new ByteArrayOutputStream();
      int previous = -1;
      int current;
      while ((current = in.read()) >= 0) {
        if (previous == '\n' && current == '\n') {
          return header.toString(StandardCharsets.UTF_8);
        }
        header.write(current);
        previous = current;
      }
    }
    throw new IllegalStateException(
        "Malformed snapshot file for catalog: " + catalogName
            + ". Missing the blank line separating header from data.");
  }
```

- [ ] **Step 4: Run the tests**

Run: `mvn -q -pl andersoni-snapshot-fs -am test -Dtest=FileSystemSnapshotStoreTest -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add andersoni-snapshot-fs
git commit -m "Describe filesystem snapshots by reading only the header

Reconciliation calls describe on every pass; reading just the metadata
header keeps that cheap regardless of snapshot size."
```

---

### Task 9: `S3SnapshotStore.describe` with `HeadObject`

**Files:**
- Modify: `andersoni-snapshot-s3/src/main/java/org/waabox/andersoni/snapshot/s3/S3SnapshotStore.java`
- Test: `andersoni-snapshot-s3/src/test/java/org/waabox/andersoni/snapshot/s3/S3SnapshotStoreTest.java`

- [ ] **Step 1: Write the failing tests** (append to the existing test class; add imports `HeadObjectRequest`, `HeadObjectResponse`, `S3Exception`, `SnapshotMetadata`)

```java
  @Test
  void whenDescribing_givenExistingObject_shouldReturnMetadataFromHeadRequest() {
    final S3Client s3Client = createMock(S3Client.class);
    final S3SnapshotConfig config = S3SnapshotConfig.builder()
        .bucket("my-bucket")
        .region(Region.US_EAST_1)
        .s3Client(s3Client)
        .build();
    final HeadObjectResponse response = HeadObjectResponse.builder()
        .metadata(Map.of(
            "hash", "abc123",
            "version", "42",
            "created-at", "2026-01-15T10:30:00Z",
            "catalog-name", "events"))
        .build();
    final Capture<HeadObjectRequest> requestCapture = newCapture();
    expect(s3Client.headObject(capture(requestCapture))).andReturn(response);
    replay(s3Client);

    final S3SnapshotStore store = new S3SnapshotStore(config);
    final Optional<SnapshotMetadata> metadata = store.describe("events");

    verify(s3Client);
    assertEquals("my-bucket", requestCapture.getValue().bucket());
    assertEquals("andersoni/events/snapshot.dat", requestCapture.getValue().key());
    assertTrue(metadata.isPresent());
    assertEquals("abc123", metadata.get().hash());
    assertEquals(42L, metadata.get().version());
    assertEquals(Instant.parse("2026-01-15T10:30:00Z"), metadata.get().createdAt());
    assertEquals("events", metadata.get().catalogName());
  }

  @Test
  void whenDescribing_givenNoSuchKey_shouldReturnEmpty() {
    final S3Client s3Client = createMock(S3Client.class);
    final S3SnapshotConfig config = S3SnapshotConfig.builder()
        .bucket("my-bucket")
        .region(Region.US_EAST_1)
        .s3Client(s3Client)
        .build();
    expect(s3Client.headObject(anyObject(HeadObjectRequest.class)))
        .andThrow(NoSuchKeyException.builder().message("missing").build());
    replay(s3Client);

    final S3SnapshotStore store = new S3SnapshotStore(config);

    assertTrue(store.describe("events").isEmpty());
    verify(s3Client);
  }

  @Test
  void whenDescribing_givenGeneric404_shouldReturnEmpty() {
    final S3Client s3Client = createMock(S3Client.class);
    final S3SnapshotConfig config = S3SnapshotConfig.builder()
        .bucket("my-bucket")
        .region(Region.US_EAST_1)
        .s3Client(s3Client)
        .build();
    expect(s3Client.headObject(anyObject(HeadObjectRequest.class)))
        .andThrow((S3Exception) S3Exception.builder().statusCode(404)
            .message("Not Found").build());
    replay(s3Client);

    final S3SnapshotStore store = new S3SnapshotStore(config);

    assertTrue(store.describe("events").isEmpty());
    verify(s3Client);
  }

  @Test
  void whenDescribing_givenObjectWithoutMetadata_shouldReturnEmpty() {
    final S3Client s3Client = createMock(S3Client.class);
    final S3SnapshotConfig config = S3SnapshotConfig.builder()
        .bucket("my-bucket")
        .region(Region.US_EAST_1)
        .s3Client(s3Client)
        .build();
    expect(s3Client.headObject(anyObject(HeadObjectRequest.class)))
        .andReturn(HeadObjectResponse.builder().metadata(Map.of()).build());
    replay(s3Client);

    final S3SnapshotStore store = new S3SnapshotStore(config);

    assertTrue(store.describe("events").isEmpty());
    verify(s3Client);
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -pl andersoni-snapshot-s3 -am test -Dtest=S3SnapshotStoreTest -DfailIfNoTests=false`
Expected: the first test fails (`getObject` called instead of `headObject`: EasyMock reports an unexpected call).

- [ ] **Step 3: Implement**

Add imports `software.amazon.awssdk.services.s3.model.HeadObjectRequest`, `HeadObjectResponse`, `S3Exception`, `org.waabox.andersoni.snapshot.SnapshotMetadata`, and a constant `private static final int HTTP_NOT_FOUND = 404;`. Add after `load`:

```java
  /**
   * {@inheritDoc}
   *
   * <p>Issues a {@code HeadObject} request and reads the user metadata
   * headers written by {@link #save}. An object without the expected
   * headers is reported as absent so the leader re-saves it on its next
   * reconciliation pass.
   */
  @Override
  public Optional<SnapshotMetadata> describe(final String catalogName) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    final String key = buildKey(catalogName);

    final HeadObjectRequest request = HeadObjectRequest.builder()
        .bucket(bucket)
        .key(key)
        .build();
    try {
      final HeadObjectResponse response = s3Client.headObject(request);
      final Map<String, String> metadata = response.metadata();
      final String hash = metadata.get(META_HASH);
      final String version = metadata.get(META_VERSION);
      final String createdAt = metadata.get(META_CREATED_AT);
      if (hash == null || version == null || createdAt == null) {
        log.warn("Snapshot object s3://{}/{} lacks Andersoni metadata headers;"
            + " treating catalog '{}' as having no snapshot", bucket, key, catalogName);
        return Optional.empty();
      }
      return Optional.of(new SnapshotMetadata(catalogName, hash,
          Long.parseLong(version), Instant.parse(createdAt)));
    } catch (final NoSuchKeyException e) {
      log.debug("No snapshot found for catalog '{}' at s3://{}/{}", catalogName, bucket, key);
      return Optional.empty();
    } catch (final S3Exception e) {
      if (e.statusCode() == HTTP_NOT_FOUND) {
        log.debug("No snapshot found for catalog '{}' at s3://{}/{}", catalogName, bucket, key);
        return Optional.empty();
      }
      throw e;
    }
  }
```

Update the class JavaDoc: mention that `describe` uses `HeadObject`.

- [ ] **Step 4: Run the tests**

Run: `mvn -q -pl andersoni-snapshot-s3 -am test -Dtest=S3SnapshotStoreTest -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add andersoni-snapshot-s3
git commit -m "Describe S3 snapshots with HeadObject

Reconciliation compares the stored hash on every pass; a HEAD request
reads the metadata headers without downloading the object."
```

---

### Task 10: Datadog drift metrics and `catalog.in_sync` gauge

**Files:**
- Modify: `andersoni-metrics-datadog/src/main/java/org/waabox/andersoni/metrics/datadog/DatadogAndersoniMetrics.java`
- Test: `andersoni-metrics-datadog/src/test/java/org/waabox/andersoni/metrics/datadog/DatadogAndersoniMetricsTest.java`

- [ ] **Step 1: Write the failing tests** (append to the existing class; `org.easymock.EasyMock.anyLong` is already referenced fully qualified there)

```java
  @Test
  void whenDriftDetected_givenAfterStart_shouldIncrementCounterWithNodeTag() {
    final StatsDClient client = createMock(StatsDClient.class);
    client.count(eq("reconcile.drift_detected"), eq(1L),
        eq("catalog:products"), eq("node:node-1"));
    expectLastCall().once();
    replay(client);

    final DatadogAndersoniMetrics metrics = DatadogAndersoniMetrics.create(client);
    metrics.start(List.of(), "node-1");
    metrics.driftDetected("products");
    metrics.stop();

    verify(client);
  }

  @Test
  void whenDriftRepaired_givenBeforeStart_shouldIncrementCounterWithoutNodeTag() {
    final StatsDClient client = createMock(StatsDClient.class);
    client.count(eq("reconcile.drift_repaired"), eq(1L), eq("catalog:products"));
    expectLastCall().once();
    replay(client);

    final DatadogAndersoniMetrics metrics = DatadogAndersoniMetrics.create(client);
    metrics.driftRepaired("products");

    verify(client);
  }

  @Test
  void whenReconcileFailed_givenAfterStart_shouldIncrementCounterWithNodeTag() {
    final StatsDClient client = createMock(StatsDClient.class);
    client.count(eq("reconcile.failed"), eq(1L),
        eq("catalog:products"), eq("node:node-1"));
    expectLastCall().once();
    replay(client);

    final DatadogAndersoniMetrics metrics = DatadogAndersoniMetrics.create(client);
    metrics.start(List.of(), "node-1");
    metrics.reconcileFailed("products", new RuntimeException("boom"));
    metrics.stop();

    verify(client);
  }

  @Test
  void whenReportingGauges_givenDriftDetectedAndNotRepaired_shouldReportInSyncZero() {
    final StatsDClient client = createMock(StatsDClient.class);
    final Catalog<String> catalog = Catalog.of(String.class)
        .named("cities")
        .data(List.of("Madrid"))
        .index("by-length").by(s -> s, String::length)
        .build();
    catalog.bootstrap();
    client.count(eq("reconcile.drift_detected"), eq(1L),
        eq("catalog:cities"), eq("node:node-1"));
    expectLastCall().once();
    client.gauge(eq("catalog.items"), org.easymock.EasyMock.anyLong(),
        eq("catalog:cities"), eq("node:node-1"));
    expectLastCall().once();
    client.gauge(eq("catalog.memory.bytes"), org.easymock.EasyMock.anyLong(),
        eq("catalog:cities"), eq("node:node-1"));
    expectLastCall().once();
    client.gauge(eq("catalog.version"), org.easymock.EasyMock.anyLong(),
        eq("catalog:cities"), eq("node:node-1"));
    expectLastCall().once();
    client.gauge(eq("catalog.in_sync"), eq(0L),
        eq("catalog:cities"), eq("node:node-1"));
    expectLastCall().once();
    client.gauge(eq("index.memory.bytes"), org.easymock.EasyMock.anyLong(),
        eq("catalog:cities"), eq("index:by-length"), eq("node:node-1"));
    expectLastCall().once();
    client.gauge(eq("index.keys"), org.easymock.EasyMock.anyLong(),
        eq("catalog:cities"), eq("index:by-length"), eq("node:node-1"));
    expectLastCall().once();
    replay(client);

    final DatadogAndersoniMetrics metrics = DatadogAndersoniMetrics.create(client);
    metrics.start(List.of(catalog), "node-1");
    metrics.driftDetected("cities");
    metrics.reportGauges();
    metrics.stop();

    verify(client);
  }
```

Also update the existing `whenReportingGauges_givenCatalogWithIndex_shouldReportAllGauges` to expect the new gauge with value `1L` (a catalog that never drifted reports in sync):

```java
    client.gauge(eq("catalog.in_sync"), eq(1L),
        eq("catalog:cities"), eq("node:node-1"));
    expectLastCall().once();
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -pl andersoni-metrics-datadog -am test -Dtest=DatadogAndersoniMetricsTest -DfailIfNoTests=false`
Expected: the drift tests fail (no counter emitted), gauge tests fail (missing `catalog.in_sync`).

- [ ] **Step 3: Implement**

Add imports `java.util.Map`, `java.util.concurrent.ConcurrentHashMap`. Add field:

```java
  /** Per-catalog in-sync flag derived from drift events; absent means never drifted. */
  private final Map<String, Boolean> inSyncByCatalog = new ConcurrentHashMap<>();
```

Add the three overrides (same tagging convention as `syncPublished`):

```java
  @Override
  public void driftDetected(final String catalogName) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    inSyncByCatalog.put(catalogName, Boolean.FALSE);
    safely(() -> {
      final String node = this.nodeId;
      if (node != null) {
        client.count("reconcile.drift_detected", 1, "catalog:" + catalogName, "node:" + node);
      } else {
        client.count("reconcile.drift_detected", 1, "catalog:" + catalogName);
      }
    });
  }

  @Override
  public void driftRepaired(final String catalogName) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    inSyncByCatalog.put(catalogName, Boolean.TRUE);
    safely(() -> {
      final String node = this.nodeId;
      if (node != null) {
        client.count("reconcile.drift_repaired", 1, "catalog:" + catalogName, "node:" + node);
      } else {
        client.count("reconcile.drift_repaired", 1, "catalog:" + catalogName);
      }
    });
  }

  @Override
  public void reconcileFailed(final String catalogName, final Throwable cause) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    safely(() -> {
      final String node = this.nodeId;
      if (node != null) {
        client.count("reconcile.failed", 1, "catalog:" + catalogName, "node:" + node);
      } else {
        client.count("reconcile.failed", 1, "catalog:" + catalogName);
      }
    });
  }
```

In `reportCatalogGauges`, after the `catalog.version` gauge:

```java
    final boolean inSync = inSyncByCatalog.getOrDefault(catalog.name(), Boolean.TRUE);
    client.gauge("catalog.in_sync", inSync ? 1L : 0L, catalogTag, nodeTag);
```

Update the class JavaDoc metric list with the three counters and the gauge.

- [ ] **Step 4: Run the tests**

Run: `mvn -q -pl andersoni-metrics-datadog -am test -Dtest=DatadogAndersoniMetricsTest -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add andersoni-metrics-datadog
git commit -m "Emit reconciliation drift metrics and catalog.in_sync gauge to Datadog

The gauge is the alerting signal for a node that diverged from the
snapshot store and has not been repaired yet."
```

---

### Task 11: Spring Boot starter properties

**Files:**
- Modify: `andersoni-spring-boot-starter/src/main/java/org/waabox/andersoni/spring/AndersoniProperties.java`
- Modify: `andersoni-spring-boot-starter/src/main/java/org/waabox/andersoni/spring/AndersoniAutoConfiguration.java`
- Test: `andersoni-spring-boot-starter/src/test/java/org/waabox/andersoni/spring/AndersoniAutoConfigurationTest.java`

- [ ] **Step 1: Write the failing tests** (append; import `java.time.Duration` and `org.waabox.andersoni.ReconciliationPolicy` if used)

```java
  @Test
  void whenContextLoads_givenNoReconciliationProperties_shouldDefaultToEnabledEveryThirtySeconds() {
    runner.run(context -> {
      final AndersoniProperties properties = context.getBean(AndersoniProperties.class);
      assertTrue(properties.getReconciliation().isEnabled());
      assertEquals(Duration.ofSeconds(30), properties.getReconciliation().getInterval());
    });
  }

  @Test
  void whenContextLoads_givenReconciliationDisabled_shouldBindFalse() {
    runner.withPropertyValues("andersoni.reconciliation.enabled=false")
        .run(context -> {
          final AndersoniProperties properties = context.getBean(AndersoniProperties.class);
          assertFalse(properties.getReconciliation().isEnabled());
          assertNotNull(context.getBean(Andersoni.class));
        });
  }

  @Test
  void whenContextLoads_givenReconciliationInterval_shouldBindDuration() {
    runner.withPropertyValues("andersoni.reconciliation.interval=10s")
        .run(context -> {
          final AndersoniProperties properties = context.getBean(AndersoniProperties.class);
          assertEquals(Duration.ofSeconds(10), properties.getReconciliation().getInterval());
          assertNotNull(context.getBean(Andersoni.class));
        });
  }
```

Add the `assertTrue`/`assertFalse`/`assertEquals` static imports if missing.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -pl andersoni-spring-boot-starter -am test -Dtest=AndersoniAutoConfigurationTest -DfailIfNoTests=false`
Expected: compilation failure (`getReconciliation` missing).

- [ ] **Step 3: Implement**

`AndersoniProperties.java`:

```java
  /** Reconciliation settings. */
  private final Reconciliation reconciliation = new Reconciliation();

  public Reconciliation getReconciliation() {
    return reconciliation;
  }

  /**
   * Snapshot reconciliation (cluster anti-entropy) settings, bound from
   * {@code andersoni.reconciliation.*}.
   */
  public static class Reconciliation {

    /** Whether the reconciliation loop runs. Only effective with a snapshot store. */
    private boolean enabled = true;

    /** Base interval between passes. */
    private Duration interval = Duration.ofSeconds(30);

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(final boolean enabled) {
      this.enabled = enabled;
    }

    public Duration getInterval() {
      return interval;
    }

    public void setInterval(final Duration interval) {
      this.interval = interval;
    }
  }
```

(Add `import java.time.Duration;` and JavaDoc on the public getters/setters following the file's style.)

`AndersoniAutoConfiguration.andersoni(...)`, after the `retryPolicyProvider` block:

```java
    final AndersoniProperties.Reconciliation reconciliation = properties.getReconciliation();
    if (reconciliation.isEnabled()) {
      builder.reconciliation(ReconciliationPolicy.of(reconciliation.getInterval()));
      log.info("Andersoni reconciliation enabled every {}", reconciliation.getInterval());
    } else {
      builder.reconciliation(ReconciliationPolicy.disabled());
      log.info("Andersoni reconciliation disabled by configuration");
    }
```

with `import org.waabox.andersoni.ReconciliationPolicy;`.

- [ ] **Step 4: Run the tests**

Run: `mvn -q -pl andersoni-spring-boot-starter -am test -Dtest=AndersoniAutoConfigurationTest -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add andersoni-spring-boot-starter
git commit -m "Expose reconciliation settings in the Spring Boot starter

andersoni.reconciliation.enabled (default true) and
andersoni.reconciliation.interval (default 30s)."
```

---

### Task 12: Cluster integration test: convergence through the store during a Kafka outage

**Files:**
- Modify: `andersoni-cluster-it/pom.xml` (add `andersoni-snapshot-fs` dependency)
- Create: `andersoni-cluster-it/src/main/java/org/waabox/andersoni/it/ItemSerializer.java`
- Modify: `andersoni-cluster-it/src/main/java/org/waabox/andersoni/it/ClusterNode.java`
- Create: `andersoni-cluster-it/src/test/java/org/waabox/andersoni/it/ClusterReconciliationIT.java`

**Scenario:** three nodes share a filesystem snapshot store through a bind-mounted host directory and reconcile every 2 seconds. After baseline convergence the Kafka container is stopped, a row is inserted and the leader is asked to refresh. The leader's publish cannot reach anyone, yet every follower converges through the store within the reconciliation window and reports `IN_SYNC`.

- [ ] **Step 1: Add the dependency**

In `andersoni-cluster-it/pom.xml`, after the `andersoni-sync-kafka` dependency:

```xml
    <!-- Shared filesystem snapshot store (bind-mounted dir) for reconciliation. -->
    <dependency>
      <groupId>io.github.waabox</groupId>
      <artifactId>andersoni-snapshot-fs</artifactId>
      <version>${project.version}</version>
    </dependency>
```

- [ ] **Step 2: Add the serializer**

`ItemSerializer.java`:

```java
package org.waabox.andersoni.it;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.waabox.andersoni.snapshot.SnapshotSerializer;

/**
 * Line-based, deterministic serializer for {@link Item}: {@code id|name}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class ItemSerializer implements SnapshotSerializer<Item> {

  @Override
  public byte[] serialize(final List<Item> items) {
    final StringBuilder builder = new StringBuilder();
    for (final Item item : items) {
      builder.append(item.id()).append('|').append(item.name()).append('\n');
    }
    return builder.toString().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public List<Item> deserialize(final byte[] data) {
    final List<Item> items = new ArrayList<>();
    for (final String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      final int separator = line.indexOf('|');
      items.add(new Item(Integer.parseInt(line.substring(0, separator)),
          line.substring(separator + 1)));
    }
    return items;
  }
}
```

- [ ] **Step 3: Extend `ClusterNode`**

- Read two new env vars: `SNAPSHOT_DIR` (optional; when set, configure `new FileSystemSnapshotStore(Paths.get(dir))`) and `RECONCILE_INTERVAL_MS` (optional; when set, `ReconciliationPolicy.of(Duration.ofMillis(...))`).
- Add `.serializer(new ItemSerializer())` to the catalog builder.
- Builder: `.snapshotStore(store)` when configured and `.reconciliation(policy)` when configured.
- `/state`: add `"syncState"` from `andersoni.status()`:

```java
      final AndersoniStatus status = andersoni.status();
      final String syncState = status.catalogs().stream()
          .filter(c -> c.catalogName().equals(CATALOG))
          .map(c -> c.syncState().name())
          .findFirst()
          .orElse("UNKNOWN");
      json.put("syncState", syncState);
```

Imports: `java.nio.file.Paths`, `org.waabox.andersoni.AndersoniStatus`, `org.waabox.andersoni.ReconciliationPolicy`, `org.waabox.andersoni.snapshot.fs.FileSystemSnapshotStore`.

- [ ] **Step 4: Write the IT**

`ClusterReconciliationIT.java` reuses the helper style of `ClusterRefreshPropagationIT` (copy `state`, `post`, `baseUrl`, `await`, `awaitQuietly`, `quietStop`, `initSchemaAndSeed`, `insertItem`, `hostConnection`, `allConverged` verbatim from that class):

```java
package org.waabox.andersoni.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Verifies that followers converge through the snapshot store when the sync
 * channel is down: reconciliation repairs what a lost event would leave
 * stale.
 */
class ClusterReconciliationIT {

  private static final Logger LOG = LoggerFactory.getLogger(ClusterReconciliationIT.class);
  private static final int NODE_COUNT = 3;
  private static final String KAFKA_INTERNAL = "kafka:19092";

  // ... copy the http field and helper methods from ClusterRefreshPropagationIT ...

  @Test
  void whenLeaderRefreshes_givenKafkaDown_shouldConvergeThroughSnapshotStore()
      throws Exception {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
        "Docker is required for this integration test");

    final Path snapshotDir = Paths.get("target", "it-snapshots-" + UUID.randomUUID())
        .toAbsolutePath();
    Files.createDirectories(snapshotDir);

    final Network network = Network.newNetwork();
    final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1")
        .withNetwork(network)
        .withNetworkAliases("kafka")
        .withListener(KAFKA_INTERNAL);
    final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withNetwork(network)
        .withNetworkAliases("postgres")
        .withDatabaseName("andersoni")
        .withUsername("test")
        .withPassword("test");
    final List<GenericContainer<?>> nodes = new ArrayList<>();

    try {
      kafka.start();
      postgres.start();
      initSchemaAndSeed(postgres);

      final Future<String> image = new ImageFromDockerfile("andersoni-cluster-node:it", false)
          .withFileFromPath("Dockerfile", Paths.get("Dockerfile"))
          .withFileFromPath("app.jar", Paths.get("target/app.jar"));

      for (int i = 0; i < NODE_COUNT; i++) {
        final String nodeId = "node-" + i;
        final GenericContainer<?> node = new GenericContainer<>(image)
            .withNetwork(network)
            .withNetworkAliases(nodeId)
            .dependsOn(kafka, postgres)
            .withEnv("NODE_ID", nodeId)
            .withEnv("LEADER", Boolean.toString(i == 0))
            .withEnv("KAFKA_BOOTSTRAP", KAFKA_INTERNAL)
            .withEnv("KAFKA_TOPIC", "andersoni-reconcile-it")
            .withEnv("JDBC_URL", "jdbc:postgresql://postgres:5432/andersoni")
            .withEnv("JDBC_USER", "test")
            .withEnv("JDBC_PASSWORD", "test")
            .withEnv("HTTP_PORT", "8080")
            .withEnv("SNAPSHOT_DIR", "/snapshots")
            .withEnv("RECONCILE_INTERVAL_MS", "2000")
            .withFileSystemBind(snapshotDir.toString(), "/snapshots", BindMode.READ_WRITE)
            .withExposedPorts(8080)
            .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix(nodeId))
            .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))
            .withStartupTimeout(Duration.ofSeconds(150));
        node.start();
        nodes.add(node);
      }

      // 1) Baseline: everyone at the 3 seeded rows, same hash.
      await(Duration.ofSeconds(60), () -> allConverged(nodes, 3));
      LOG.info("Baseline converged at 3 items");

      // 2) Sync channel outage.
      kafka.stop();
      LOG.info("Kafka stopped; the leader's next publish cannot reach anyone");

      // 3) Leader refreshes: saves to the store, publish fails silently.
      insertItem(postgres, 4, "item-4");
      post(nodes.get(0), "/refresh");

      // 4) Followers must converge through the store within a few passes.
      await(Duration.ofSeconds(30), () -> allConverged(nodes, 4));
      for (final GenericContainer<?> node : nodes) {
        final JSONObject state = state(node);
        assertEquals(4, state.getInt("itemCount"));
      }
      await(Duration.ofSeconds(15), () -> nodes.stream().allMatch(n -> {
        try {
          return "IN_SYNC".equals(state(n).getString("syncState"));
        } catch (final Exception e) {
          return false;
        }
      }));
      LOG.info("All {} nodes converged and report IN_SYNC without Kafka", NODE_COUNT);

      // 5) Quiescence: versions stable once converged (no repair loop).
      final List<Long> versions = versionsOf(nodes);
      Thread.sleep(6000);
      assertEquals(versions, versionsOf(nodes),
          "Versions must be stable after convergence (no reconciliation loop)");
    } finally {
      for (final GenericContainer<?> node : nodes) {
        quietStop(node);
      }
      quietStop(postgres);
      quietStop(kafka);
      network.close();
    }
  }
}
```

Copy `versionsOf` too. The `/refresh` POST on the leader returns after the local refresh and store save; the Kafka producer buffers the failed send and logs after its delivery timeout, which does not block the request.

- [ ] **Step 5: Run the IT**

Run: `mvn -q -pl andersoni-cluster-it -am verify -DskipTests=false` (needs Docker; the module builds the shaded `target/app.jar` first). If your environment cannot run Docker, run `mvn -q -pl andersoni-cluster-it -am package -DskipTests` to at least compile, and report the IT as not executed.
Expected: both ITs PASS.

If the bind mount is refused by Docker Desktop (macOS file sharing), `target/` under the repository lives under `/Users`, which is shared by default; otherwise add the repository path to Docker Desktop's file sharing settings.

- [ ] **Step 6: Commit**

```bash
git add andersoni-cluster-it
git commit -m "Add cluster IT proving convergence through the snapshot store without Kafka

Nodes share a filesystem snapshot store; after Kafka is stopped a leader
refresh still reaches every follower via reconciliation."
```

---

### Task 13: Documentation and full build

**Files:**
- Modify: `CLAUDE.md` (Architecture section)
- Modify: `README.md` (new short section before "## How It Compares")

- [ ] **Step 1: `CLAUDE.md`**

Add under `## Architecture`, after the "Refresh request propagation" bullet:

```markdown
- **Snapshot reconciliation (anti-entropy)**: the `SnapshotStore` is the cluster's authoritative state. `SnapshotReconciler` runs every `ReconciliationPolicy.interval()` (default 30s, on by default when a store is configured): followers reload from the store when its hash differs from the hash they last applied (`SnapshotStoreBridge.appliedStoreHash`), the leader re-saves and re-publishes when its applied hash is missing or differs, a promoted leader runs a pass immediately. Repairs go through `AsyncRefreshDispatcher`. `Andersoni.reconcile()` forces a pass; `status()` exposes `syncState` per catalog. Design: `docs/superpowers/specs/2026-09-11-snapshot-reconciliation-design.md`
```

- [ ] **Step 2: `README.md`**

Insert before `## How It Compares`:

```markdown
## Cluster Self-Healing

With a `SnapshotStore` configured, every node runs a reconciliation loop (default: every 30 seconds):

- **Followers** compare the store's snapshot hash with the one they last applied and reload from the store on any difference. A missed sync event, a failed reload or a restart during a broadcast is repaired within one interval.
- **The leader** re-uploads and re-broadcasts its snapshot if the store is behind (for example after a failed upload).
- **A newly elected leader** reconciles immediately.

```java
Andersoni andersoni = Andersoni.builder()
    .snapshotStore(s3Store)
    .reconciliation(ReconciliationPolicy.of(Duration.ofSeconds(15)))  // or .disabled()
    .build();

andersoni.reconcile();          // force a pass now (never hits the DataLoader)
andersoni.status().inSync();    // false if any catalog drifted
```

Spring Boot: `andersoni.reconciliation.enabled=true`, `andersoni.reconciliation.interval=30s`.
Datadog: counters `reconcile.drift_detected`, `reconcile.drift_repaired`, `reconcile.failed` and gauge `catalog.in_sync`.
```

- [ ] **Step 3: Full build**

Run: `mvn clean verify`
Expected: BUILD SUCCESS (the cluster IT is skipped automatically when Docker is unavailable via `assumeTrue`).

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "Document snapshot reconciliation

Explains the anti-entropy loop, its defaults, the on-demand reconcile()
entry point and the metrics to alert on."
```

---

## Self-Review Notes

- **Spec coverage:** §1 (`describe`, `SnapshotMetadata`) → Task 1, 8, 9. §2 (bridge, applied hash transitions) → Task 5 (all five transitions are in Step 6). §3 (reconciler rules, dispatcher, leader change, jitter, failed catalogs, no DataLoader fallback) → Task 6 and 7. §4 (`ReconciliationPolicy`, builder default, `reconcile()`, Spring properties) → Tasks 2, 7, 11. §5 (status, metrics, Datadog gauge) → Tasks 3, 7, 10. Testing section → Tasks 6, 7, 8, 9, 10, 11, 12. Docs → Task 13.
- **Type consistency:** `SnapshotStoreBridge.load/save/markUnknown/appliedStoreHash/describe/supports/isConfigured/sha256Hex` are used with those exact names in Tasks 5, 6, 7. `SnapshotReconciler.runPass/requestPass/start/stop/syncState/lastReconciledAt` in Tasks 6, 7. `ReconciliationPolicy.of/disabled/defaultPolicy/enabled/interval` in Tasks 2, 7, 11, 12. `SyncState.IN_SYNC/DRIFTED/UNKNOWN` in Tasks 3, 6, 7, 12.
- **Behavior changes beyond the spec, both deliberate:** `CatalogStatus.available` becomes `false` for a catalog in `failedCatalogs` (it was always `true`, contradicting its JavaDoc); and a follower's local `refresh(name)` is overwritten by the store on the next pass, which is the "store is the authority" rule applied consistently (Task 5 Step 6.8 documents it in the JavaDoc).
