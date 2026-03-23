# Patch Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add granular add/update/upsert/remove patch operations to Andersoni catalogs with identity-based item tracking and visitor-based sync event broadcasting.

**Architecture:** Catalog gains an optional identity function and patch methods that validate against an identity map, then rebuild the snapshot. The SyncStrategy interface evolves from RefreshEvent-only to a polymorphic SyncEvent hierarchy (RefreshEvent + PatchEvent) using the visitor pattern. Andersoni orchestrates sync via *AndSync methods mirroring the existing refreshAndSync pattern.

**Tech Stack:** Java 21, JUnit 5, EasyMock, org.json, Maven

**Spec:** `docs/superpowers/specs/2026-03-23-patch-operations-design.md`

---

## File Structure

### New Files (andersoni-core)

| File | Responsibility |
|------|----------------|
| `andersoni-core/src/main/java/org/waabox/andersoni/PatchOperation.java` | Enum: ADD, UPDATE, UPSERT, REMOVE |
| `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncEvent.java` | Interface: common event fields + accept(handler) |
| `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncEventHandler.java` | Visitor interface: onRefresh, onPatch |
| `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncListener.java` | Replaces RefreshListener |
| `andersoni-core/src/main/java/org/waabox/andersoni/sync/PatchEvent.java` | Record implementing SyncEvent |
| `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncEventCodec.java` | Replaces RefreshEventCodec, handles both types |
| `andersoni-core/src/test/java/org/waabox/andersoni/CatalogPatchTest.java` | Tests for all patch operations on Catalog |
| `andersoni-core/src/test/java/org/waabox/andersoni/sync/SyncEventCodecTest.java` | Tests for codec serialization/deserialization |
| `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniPatchSyncTest.java` | Tests for Andersoni *AndSync methods and receiving-side dispatch |

### Modified Files (andersoni-core)

| File | Changes |
|------|---------|
| `andersoni-core/.../Snapshot.java` | Add identityMap field, identity-aware factory method, findById method |
| `andersoni-core/.../Catalog.java` | Add identityFunction field, identifiedBy() on BuildStep, patch methods (add/update/upsert/remove), findById, update constructor and build() |
| `andersoni-core/.../sync/RefreshEvent.java` | Implement SyncEvent interface, add accept() method |
| `andersoni-core/.../sync/SyncStrategy.java` | Change publish/subscribe to use SyncEvent/SyncListener |
| `andersoni-core/.../Andersoni.java` | Implement SyncEventHandler, add *AndSync methods, rework wireSyncListener, add dispatchPatch to AsyncRefreshDispatcher, bootstrap validation |
| `andersoni-core/.../metrics/AndersoniMetrics.java` | Add default patch metric methods |
| `andersoni-core/.../metrics/NoopAndersoniMetrics.java` | No changes needed (defaults from interface) |

### Modified Files (sync modules)

| File | Changes |
|------|---------|
| `andersoni-sync-kafka/.../KafkaSyncStrategy.java` | RefreshEvent→SyncEvent, RefreshListener→SyncListener, RefreshEventCodec→SyncEventCodec |
| `andersoni-sync-kafka/...test.../KafkaSyncStrategyTest.java` | Update to new types |
| `andersoni-spring-sync-kafka/.../SpringKafkaSyncStrategy.java` | Same type updates |
| `andersoni-spring-sync-kafka/...test.../SpringKafkaSyncStrategyTest.java` | Update to new types |
| `andersoni-sync-http/.../HttpSyncStrategy.java` | Same type updates |
| `andersoni-sync-http/...test.../HttpSyncStrategyTest.java` | Update to new types |
| `andersoni-sync-db/.../DbPollingSyncStrategy.java` | Structural change: store serialized JSON via SyncEventCodec instead of unpacking fields into columns. Schema migration (add `event_json` TEXT column, drop `hash` column, add `event_type` column). Rework `publish()` and `poll()` methods. |
| `andersoni-sync-db/.../DbPollingSyncConfig.java` | No changes expected |
| `andersoni-sync-db/...test.../DbPollingSyncStrategyTest.java` | Update to new types + test PatchEvent round-trip via DB |

### Modified Files (other modules)

| File | Changes |
|------|---------|
| `andersoni-metrics-datadog/.../DatadogAndersoniMetrics.java` | Implement new patch metric methods |
| `andersoni-metrics-datadog/...test.../DatadogAndersoniMetricsTest.java` | Test patch metrics |
| `andersoni-spring-boot-starter/.../AndersoniAutoConfiguration.java` | No changes expected (uses SyncStrategy by type) |
| `andersoni-spring-boot-starter/...test.../AndersoniAutoConfigurationTest.java` | Update TestSyncStrategy to new interface |

---

## Task 1: PatchOperation Enum and SyncEvent Hierarchy

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/PatchOperation.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncEvent.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncEventHandler.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncListener.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/sync/PatchEvent.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/sync/RefreshEvent.java`

- [ ] **Step 1: Create PatchOperation enum**

```java
package org.waabox.andersoni;

/** Enumerates the types of patch operations that can be applied to a catalog.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public enum PatchOperation {
  /** Adds new items; fails if any identity key already exists. */
  ADD,
  /** Updates existing items; fails if any identity key is not found. */
  UPDATE,
  /** Adds or replaces items by identity key. */
  UPSERT,
  /** Removes items; fails if any identity key is not found. */
  REMOVE
}
```

- [ ] **Step 2: Create SyncEvent interface**

```java
package org.waabox.andersoni.sync;

import java.time.Instant;

/** Common interface for all synchronization events broadcast across nodes.
 *
 * <p>Implementations use the visitor pattern via {@link #accept(SyncEventHandler)}
 * to enable type-safe dispatch without instanceof checks.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SyncEvent {
  /** Returns the name of the catalog this event relates to. */
  String catalogName();
  /** Returns the identifier of the node that originated this event. */
  String sourceNodeId();
  /** Returns the snapshot version after the operation was applied. */
  long version();
  /** Returns the instant at which the event was created. */
  Instant timestamp();
  /** Dispatches this event to the appropriate handler method.
   * @param handler the handler to dispatch to, never null
   */
  void accept(SyncEventHandler handler);
}
```

- [ ] **Step 3: Create SyncEventHandler interface**

```java
package org.waabox.andersoni.sync;

/** Visitor interface for handling different types of {@link SyncEvent}s.
 *
 * <p>Implementors provide type-specific handling for each event subtype,
 * avoiding instanceof/switch chains.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SyncEventHandler {
  /** Handles a full refresh event.
   * @param event the refresh event, never null
   */
  void onRefresh(RefreshEvent event);
  /** Handles a patch event.
   * @param event the patch event, never null
   */
  void onPatch(PatchEvent event);
}
```

- [ ] **Step 4: Create SyncListener interface**

```java
package org.waabox.andersoni.sync;

/** A listener for synchronization events received from other nodes.
 *
 * <p>Replaces {@code RefreshListener}. Implementations receive all event
 * types and typically delegate to {@link SyncEventHandler} via
 * {@link SyncEvent#accept(SyncEventHandler)}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@FunctionalInterface
public interface SyncListener {
  /** Called when a synchronization event is received.
   * @param event the event, never null
   */
  void onEvent(SyncEvent event);
}
```

- [ ] **Step 5: Create PatchEvent record**

```java
package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Objects;
import org.waabox.andersoni.PatchOperation;

/** A synchronization event representing a granular patch operation.
 *
 * <p>The {@code payload} contains serialized items (always a {@code List<T>})
 * using the catalog's {@code SnapshotSerializer}.
 *
 * @param catalogName   the name of the catalog, never null
 * @param sourceNodeId  the node that originated this patch, never null
 * @param version       the snapshot version after the patch was applied
 * @param operationType the type of patch operation, never null
 * @param payload       the serialized items, never null
 * @param timestamp     the instant at which the patch occurred, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record PatchEvent(
    String catalogName,
    String sourceNodeId,
    long version,
    PatchOperation operationType,
    byte[] payload,
    Instant timestamp
) implements SyncEvent {

  /** Compact constructor with null checks. */
  public PatchEvent {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
    Objects.requireNonNull(operationType, "operationType must not be null");
    Objects.requireNonNull(payload, "payload must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
  }

  /** {@inheritDoc} */
  @Override
  public void accept(final SyncEventHandler handler) {
    handler.onPatch(this);
  }
}
```

- [ ] **Step 6: Update RefreshEvent to implement SyncEvent**

Modify `andersoni-core/src/main/java/org/waabox/andersoni/sync/RefreshEvent.java`:
- Add `implements SyncEvent` to the record declaration
- Add `accept(SyncEventHandler)` method
- Add compact constructor with null checks (matching PatchEvent style)

```java
public record RefreshEvent(
    String catalogName,
    String sourceNodeId,
    long version,
    String hash,
    Instant timestamp
) implements SyncEvent {

  public RefreshEvent {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
    Objects.requireNonNull(hash, "hash must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
  }

  @Override
  public void accept(final SyncEventHandler handler) {
    handler.onRefresh(this);
  }
}
```

- [ ] **Step 7: Run `mvn compile -pl andersoni-core` to verify compilation**

Run: `mvn compile -pl andersoni-core`
Expected: BUILD SUCCESS (compile only, tests may fail due to RefreshListener references)

- [ ] **Step 8: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/PatchOperation.java \
  andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncEvent.java \
  andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncEventHandler.java \
  andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncListener.java \
  andersoni-core/src/main/java/org/waabox/andersoni/sync/PatchEvent.java \
  andersoni-core/src/main/java/org/waabox/andersoni/sync/RefreshEvent.java
git commit -m "Add SyncEvent hierarchy with visitor pattern and PatchOperation enum"
```

---

## Task 2: Update SyncStrategy Interface and SyncEventCodec

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncStrategy.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncEventCodec.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/sync/SyncEventCodecTest.java`
- Delete: `andersoni-core/src/main/java/org/waabox/andersoni/sync/RefreshListener.java` (after updating all references)

- [ ] **Step 1: Write failing tests for SyncEventCodec**

Create `andersoni-core/src/test/java/org/waabox/andersoni/sync/SyncEventCodecTest.java`:

```java
package org.waabox.andersoni.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.waabox.andersoni.PatchOperation;

class SyncEventCodecTest {

  @Test
  void whenSerializing_givenRefreshEvent_shouldRoundTrip() {
    final RefreshEvent original = new RefreshEvent(
        "events", "node-1", 42, "abc123", Instant.parse("2026-01-01T00:00:00Z"));
    final String json = SyncEventCodec.serialize(original);
    final SyncEvent deserialized = SyncEventCodec.deserialize(json);

    assertEquals(RefreshEvent.class, deserialized.getClass());
    final RefreshEvent result = (RefreshEvent) deserialized;
    assertEquals("events", result.catalogName());
    assertEquals("node-1", result.sourceNodeId());
    assertEquals(42, result.version());
    assertEquals("abc123", result.hash());
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), result.timestamp());
  }

  @Test
  void whenSerializing_givenPatchEvent_shouldRoundTrip() {
    final byte[] payload = "test-payload".getBytes();
    final PatchEvent original = new PatchEvent(
        "events", "node-2", 7, PatchOperation.ADD, payload,
        Instant.parse("2026-02-15T12:00:00Z"));
    final String json = SyncEventCodec.serialize(original);
    final SyncEvent deserialized = SyncEventCodec.deserialize(json);

    assertEquals(PatchEvent.class, deserialized.getClass());
    final PatchEvent result = (PatchEvent) deserialized;
    assertEquals("events", result.catalogName());
    assertEquals("node-2", result.sourceNodeId());
    assertEquals(7, result.version());
    assertEquals(PatchOperation.ADD, result.operationType());
    assertArrayEquals(payload, result.payload());
    assertEquals(Instant.parse("2026-02-15T12:00:00Z"), result.timestamp());
  }

  @Test
  void whenDeserializing_givenNullJson_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> SyncEventCodec.deserialize(null));
  }

  @Test
  void whenDeserializing_givenInvalidJson_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> SyncEventCodec.deserialize("not-json"));
  }

  @Test
  void whenDeserializing_givenUnknownType_shouldThrow() {
    final String json = "{\"type\":\"UNKNOWN\",\"catalogName\":\"x\","
        + "\"sourceNodeId\":\"n\",\"version\":1,\"timestamp\":\"2026-01-01T00:00:00Z\"}";
    assertThrows(IllegalArgumentException.class,
        () -> SyncEventCodec.deserialize(json));
  }

  @Test
  void whenSerializing_givenNullEvent_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> SyncEventCodec.serialize(null));
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl andersoni-core -Dtest=SyncEventCodecTest`
Expected: FAIL (SyncEventCodec class does not exist)

- [ ] **Step 3: Implement SyncEventCodec**

Create `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncEventCodec.java`:

```java
package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

import org.json.JSONException;
import org.json.JSONObject;
import org.waabox.andersoni.PatchOperation;

/** Static utility class for serializing and deserializing {@link SyncEvent}
 * instances to and from JSON strings.
 *
 * <p>Uses a {@code "type"} discriminator field to distinguish between
 * {@link RefreshEvent} ({@code "REFRESH"}) and {@link PatchEvent}
 * ({@code "PATCH"}).
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class SyncEventCodec {

  private static final String TYPE_REFRESH = "REFRESH";
  private static final String TYPE_PATCH = "PATCH";

  private SyncEventCodec() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Serializes a {@link SyncEvent} into a JSON string.
   * @param event the event to serialize, never null
   * @return the JSON representation, never null
   */
  public static String serialize(final SyncEvent event) {
    Objects.requireNonNull(event, "event must not be null");

    final JSONObject json = new JSONObject();
    json.put("catalogName", event.catalogName());
    json.put("sourceNodeId", event.sourceNodeId());
    json.put("version", event.version());
    json.put("timestamp", event.timestamp().toString());

    if (event instanceof RefreshEvent r) {
      json.put("type", TYPE_REFRESH);
      json.put("hash", r.hash());
    } else if (event instanceof PatchEvent p) {
      json.put("type", TYPE_PATCH);
      json.put("operationType", p.operationType().name());
      json.put("payload", Base64.getEncoder().encodeToString(p.payload()));
    } else {
      throw new IllegalArgumentException(
          "Unknown SyncEvent type: " + event.getClass().getName());
    }

    return json.toString();
  }

  /** Deserializes a JSON string into a {@link SyncEvent}.
   * @param json the JSON string, never null
   * @return the parsed event, never null
   * @throws IllegalArgumentException if the JSON is malformed or has unknown type
   */
  public static SyncEvent deserialize(final String json) {
    Objects.requireNonNull(json, "json must not be null");

    try {
      final JSONObject node = new JSONObject(json);

      final String type = requireString(node, "type");
      final String catalogName = requireString(node, "catalogName");
      final String sourceNodeId = requireString(node, "sourceNodeId");
      final long version = node.getLong("version");
      final Instant timestamp = Instant.parse(
          requireString(node, "timestamp"));

      return switch (type) {
        case TYPE_REFRESH -> new RefreshEvent(
            catalogName, sourceNodeId, version,
            requireString(node, "hash"), timestamp);
        case TYPE_PATCH -> new PatchEvent(
            catalogName, sourceNodeId, version,
            PatchOperation.valueOf(requireString(node, "operationType")),
            Base64.getDecoder().decode(requireString(node, "payload")),
            timestamp);
        default -> throw new IllegalArgumentException(
            "Unknown event type: " + type);
      };
    } catch (final IllegalArgumentException e) {
      throw e;
    } catch (final JSONException e) {
      throw new IllegalArgumentException(
          "Failed to deserialize SyncEvent from JSON: " + json, e);
    }
  }

  private static String requireString(final JSONObject node,
      final String field) {
    if (!node.has(field) || node.isNull(field)) {
      throw new IllegalArgumentException(
          "Missing field: " + field + " in JSON: " + node);
    }
    return node.getString(field);
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl andersoni-core -Dtest=SyncEventCodecTest`
Expected: PASS

- [ ] **Step 5: Update SyncStrategy interface**

Modify `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncStrategy.java`:
- Change `publish(RefreshEvent event)` → `publish(SyncEvent event)`
- Change `subscribe(RefreshListener listener)` → `subscribe(SyncListener listener)`
- Update javadoc accordingly

**Note:** Do NOT delete `RefreshListener.java` or `RefreshEventCodec.java` yet. They are still referenced by sync modules (Kafka, HTTP, DB, Spring Kafka). Deletions are deferred to Task 9 (cleanup) after all modules have been updated.

- [ ] **Step 6: Run `mvn compile -pl andersoni-core` to verify compilation**

Run: `mvn compile -pl andersoni-core`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncStrategy.java \
  andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncEventCodec.java \
  andersoni-core/src/test/java/org/waabox/andersoni/sync/SyncEventCodecTest.java
git commit -m "Add SyncEventCodec and update SyncStrategy to use SyncEvent/SyncListener"
```

---

## Task 3: Snapshot Identity Map and findById

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Snapshot.java`

- [ ] **Step 1: Write failing tests for identity-aware Snapshot**

Add tests to a new file `andersoni-core/src/test/java/org/waabox/andersoni/SnapshotIdentityTest.java`:

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class SnapshotIdentityTest {

  record Item(String id, String name) {}

  @Test
  void whenCreating_givenIdentityFunction_shouldBuildIdentityMap() {
    final Item a = new Item("1", "Alpha");
    final Item b = new Item("2", "Beta");

    final Snapshot<Item> snapshot = Snapshot.of(
        List.of(a, b), Collections.emptyMap(), 1L, "hash",
        (Function<Item, Object>) Item::id);

    assertEquals(a, snapshot.findById("1").orElse(null));
    assertEquals(b, snapshot.findById("2").orElse(null));
    assertTrue(snapshot.findById("3").isEmpty());
  }

  @Test
  void whenCreating_givenNoIdentityFunction_shouldHaveNoIdentityMap() {
    final Item a = new Item("1", "Alpha");
    final Snapshot<Item> snapshot = Snapshot.of(
        List.of(a), Collections.emptyMap(), 1L, "hash");

    assertTrue(snapshot.findById("1").isEmpty());
  }

  @Test
  void whenCreating_givenDuplicateKeys_shouldLastWriteWin() {
    final Item a1 = new Item("1", "First");
    final Item a2 = new Item("1", "Second");

    final Snapshot<Item> snapshot = Snapshot.of(
        List.of(a1, a2), Collections.emptyMap(), 1L, "hash",
        (Function<Item, Object>) Item::id);

    assertEquals("Second", ((Item) snapshot.findById("1").orElse(null)).name());
  }

  @Test
  void whenCreating_givenSixArgOverloadWithIdentity_shouldBuildIdentityMap() {
    final Item a = new Item("1", "Alpha");

    final Snapshot<Item> snapshot = Snapshot.of(
        List.of(a), Collections.emptyMap(),
        Collections.emptyNavigableMap(), Collections.emptyNavigableMap(),
        1L, "hash", (Function<Item, Object>) Item::id);

    assertEquals(a, snapshot.findById("1").orElse(null));
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl andersoni-core -Dtest=SnapshotIdentityTest`
Expected: FAIL (no identity-aware factory method exists)

- [ ] **Step 3: Implement Snapshot identity map support**

Modify `andersoni-core/src/main/java/org/waabox/andersoni/Snapshot.java`:

1. Add new field: `private final Map<Object, T> identityMap;`
2. Update private constructor to accept identityMap parameter (can be null)
3. Add new `of()` factory method overloads that accept `Function<T, Object> identityFunction` — both the 4-arg variant (data, indices, version, hash, identityFn) and the 6-arg variant (data, indices, sortedIndices, reversedKeyIndices, version, hash, identityFn). The 6-arg variant is critical because `Catalog.buildAndSwapSnapshot()` calls the 6-arg overload at line 424.
4. Build identity map in factory: iterate data, extract keys, put into HashMap (last-write-wins), wrap in unmodifiableMap
5. Add `findById(Object key)` method returning `Optional<T>` — returns empty if identityMap is null
6. Add `identityMap()` accessor returning unmodifiable map or null
7. Update existing `of()` overloads and `empty()` to pass `null` as identityMap

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl andersoni-core -Dtest=SnapshotIdentityTest`
Expected: PASS

- [ ] **Step 5: Run all existing Snapshot-related tests**

Run: `mvn test -pl andersoni-core`
Expected: PASS (existing tests should not break; they use `of()` overloads without identity function)

- [ ] **Step 6: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Snapshot.java \
  andersoni-core/src/test/java/org/waabox/andersoni/SnapshotIdentityTest.java
git commit -m "Add identity map support to Snapshot with findById"
```

---

## Task 4: Catalog Identity Function and Patch Methods

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/CatalogPatchTest.java`

- [ ] **Step 1: Write failing tests for Catalog patch operations**

Create `andersoni-core/src/test/java/org/waabox/andersoni/CatalogPatchTest.java`:

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class CatalogPatchTest {

  record Item(String id, String value) {}

  private Catalog<Item> buildCatalog(final List<Item> data) {
    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named("items")
        .data(data)
        .identifiedBy(Item::id)
        .index("by-value").by(Item::value)
        .build();
    catalog.bootstrap();
    return catalog;
  }

  private Catalog<Item> buildCatalogWithoutIdentity(final List<Item> data) {
    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named("items")
        .data(data)
        .index("by-value").by(Item::value)
        .build();
    catalog.bootstrap();
    return catalog;
  }

  // --- add ---

  @Test
  void whenAdding_givenNewItem_shouldAddToSnapshot() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    catalog.add(new Item("2", "b"));

    assertEquals(2, catalog.currentSnapshot().data().size());
    assertTrue(catalog.findById("2").isPresent());
  }

  @Test
  void whenAdding_givenDuplicateKey_shouldThrow() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    assertThrows(IllegalArgumentException.class,
        () -> catalog.add(new Item("1", "b")));
  }

  @Test
  void whenAdding_givenNoIdentityFunction_shouldThrow() {
    final Catalog<Item> catalog = buildCatalogWithoutIdentity(
        List.of(new Item("1", "a")));
    assertThrows(IllegalStateException.class,
        () -> catalog.add(new Item("2", "b")));
  }

  @Test
  void whenAddingCollection_givenAllNew_shouldAddAll() {
    final Catalog<Item> catalog = buildCatalog(List.of(new Item("1", "a")));
    catalog.add(List.of(new Item("2", "b"), new Item("3", "c")));
    assertEquals(3, catalog.currentSnapshot().data().size());
  }

  @Test
  void whenAddingCollection_givenOneDuplicate_shouldThrowAndNotApply() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    assertThrows(IllegalArgumentException.class,
        () -> catalog.add(List.of(new Item("2", "b"), new Item("1", "dup"))));
    // all-or-nothing: nothing was added
    assertEquals(1, catalog.currentSnapshot().data().size());
  }

  // --- update ---

  @Test
  void whenUpdating_givenExistingKey_shouldReplace() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "old")));
    catalog.update(new Item("1", "new"));

    assertEquals(1, catalog.currentSnapshot().data().size());
    assertEquals("new", catalog.findById("1").map(Item::value).orElse(null));
  }

  @Test
  void whenUpdating_givenMissingKey_shouldThrow() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    assertThrows(IllegalArgumentException.class,
        () -> catalog.update(new Item("2", "b")));
  }

  @Test
  void whenUpdatingCollection_givenAllExist_shouldUpdateAll() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a"), new Item("2", "b")));
    catalog.update(List.of(new Item("1", "x"), new Item("2", "y")));
    assertEquals("x", catalog.findById("1").map(Item::value).orElse(null));
    assertEquals("y", catalog.findById("2").map(Item::value).orElse(null));
  }

  @Test
  void whenUpdatingCollection_givenOneMissing_shouldThrowAndNotApply() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    assertThrows(IllegalArgumentException.class,
        () -> catalog.update(List.of(new Item("1", "x"), new Item("99", "z"))));
    assertEquals("a", catalog.findById("1").map(Item::value).orElse(null));
  }

  // --- upsert ---

  @Test
  void whenUpserting_givenNewItem_shouldAdd() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    catalog.upsert(new Item("2", "b"));
    assertEquals(2, catalog.currentSnapshot().data().size());
  }

  @Test
  void whenUpserting_givenExistingKey_shouldReplace() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "old")));
    catalog.upsert(new Item("1", "new"));

    assertEquals(1, catalog.currentSnapshot().data().size());
    assertEquals("new", catalog.findById("1").map(Item::value).orElse(null));
  }

  @Test
  void whenUpsertingCollection_givenMixOfNewAndExisting_shouldAddAndReplace() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "old")));
    catalog.upsert(List.of(new Item("1", "updated"), new Item("2", "new")));

    assertEquals(2, catalog.currentSnapshot().data().size());
    assertEquals("updated", catalog.findById("1").map(Item::value).orElse(null));
    assertEquals("new", catalog.findById("2").map(Item::value).orElse(null));
  }

  // --- remove ---

  @Test
  void whenRemoving_givenExistingKey_shouldRemove() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a"), new Item("2", "b")));
    catalog.remove(new Item("1", "a"));

    assertEquals(1, catalog.currentSnapshot().data().size());
    assertTrue(catalog.findById("1").isEmpty());
  }

  @Test
  void whenRemoving_givenMissingKey_shouldThrow() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    assertThrows(IllegalArgumentException.class,
        () -> catalog.remove(new Item("99", "x")));
  }

  @Test
  void whenRemovingCollection_givenAllExist_shouldRemoveAll() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a"), new Item("2", "b"), new Item("3", "c")));
    catalog.remove(List.of(new Item("1", "a"), new Item("2", "b")));
    assertEquals(1, catalog.currentSnapshot().data().size());
    assertTrue(catalog.findById("3").isPresent());
  }

  @Test
  void whenRemovingCollection_givenOneMissing_shouldThrowAndNotApply() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a"), new Item("2", "b")));
    assertThrows(IllegalArgumentException.class,
        () -> catalog.remove(List.of(new Item("1", "a"), new Item("99", "x"))));
    assertEquals(2, catalog.currentSnapshot().data().size());
  }

  // --- findById ---

  @Test
  void whenFindingById_givenExistingKey_shouldReturn() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    assertEquals("a", catalog.findById("1").map(Item::value).orElse(null));
  }

  @Test
  void whenFindingById_givenNoIdentity_shouldReturnEmpty() {
    final Catalog<Item> catalog = buildCatalogWithoutIdentity(
        List.of(new Item("1", "a")));
    assertTrue(catalog.findById("1").isEmpty());
  }

  // --- index integrity after patch ---

  @Test
  void whenAdding_givenIndex_shouldUpdateIndex() {
    final Catalog<Item> catalog = buildCatalog(
        List.of(new Item("1", "a")));
    catalog.add(new Item("2", "b"));

    final List<Item> results = catalog.search("by-value", "b");
    assertEquals(1, results.size());
    assertEquals("2", results.get(0).id());
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl andersoni-core -Dtest=CatalogPatchTest`
Expected: FAIL (identifiedBy method does not exist)

- [ ] **Step 3: Implement Catalog changes**

Modify `andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java`:

1. Add field: `private final Function<T, Object> identityFunction;` (line ~75 area)
2. Update private constructor (line 123) to accept the identity function parameter
3. Add `identifiedBy(Function<T, Object>)` method to `BuildStep` (line ~642 area, alongside serializer)
4. Add `identityFunction` field to `BuildStep` (line ~588 area)
5. Update `build()` method (line 767) to pass identityFunction to constructor
6. Update `buildAndSwapSnapshot()` (line 385) to pass identityFunction to `Snapshot.of()` when present
7. Add `requireIdentityFunction()` private helper that throws `IllegalStateException` if null
8. Add `add(T item)` and `add(Collection<T> items)` methods
9. Add `update(T item)` and `update(Collection<T> items)` methods
10. Add `upsert(T item)` and `upsert(Collection<T> items)` methods
11. Add `remove(T item)` and `remove(Collection<T> items)` methods
12. Add `findById(Object key)` returning `Optional<T>` — delegates to current snapshot
13. Add `identityFunction()` accessor returning `Optional<Function<T, Object>>`

Patch method implementation pattern (example for `add`):
```java
public void add(final T item) {
  add(List.of(Objects.requireNonNull(item, "item must not be null")));
}

public void add(final Collection<T> items) {
  Objects.requireNonNull(items, "items must not be null");
  requireIdentityFunction();
  refreshLock.lock();
  try {
    final Snapshot<T> snapshot = current.get();
    final Map<Object, T> identityMap = snapshot.identityMap();
    // Validate: none of the keys should exist
    for (final T item : items) {
      final Object key = identityFunction.apply(item);
      if (identityMap.containsKey(key)) {
        throw new IllegalArgumentException(
            "Item with identity key '" + key + "' already exists in catalog '"
            + name + "'");
      }
    }
    // Build new data list
    final List<T> newData = new ArrayList<>(snapshot.data());
    newData.addAll(items);
    buildAndSwapSnapshot(newData);
  } finally {
    refreshLock.unlock();
  }
}
```

Similar pattern for update (replace), upsert (add or replace), remove (filter out by key).

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl andersoni-core -Dtest=CatalogPatchTest`
Expected: PASS

- [ ] **Step 5: Run full core test suite**

Run: `mvn test -pl andersoni-core`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java \
  andersoni-core/src/test/java/org/waabox/andersoni/CatalogPatchTest.java
git commit -m "Add identity function and patch operations to Catalog"
```

---

## Task 5: Metrics Interface Updates

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/metrics/AndersoniMetrics.java`

- [ ] **Step 1: Add patch metric methods to AndersoniMetrics interface**

Add the following default methods to `andersoni-core/src/main/java/org/waabox/andersoni/metrics/AndersoniMetrics.java` (after the existing sync methods, before `start()`):

```java
  /** Records a successful local patch operation.
   * @param catalogName the name of the catalog, never null
   * @param operation   the type of patch operation, never null
   */
  default void patchApplied(final String catalogName,
      final PatchOperation operation) {
  }

  /** Records a failed local patch operation.
   * @param catalogName the name of the catalog, never null
   * @param operation   the type of patch operation, never null
   * @param cause       the failure cause, never null
   */
  default void patchFailed(final String catalogName,
      final PatchOperation operation, final Throwable cause) {
  }

  /** Records a patch event published to the cluster.
   * @param catalogName the name of the catalog, never null
   * @param operation   the type of patch operation, never null
   */
  default void patchPublished(final String catalogName,
      final PatchOperation operation) {
  }

  /** Records a patch publish failure.
   * @param catalogName the name of the catalog, never null
   * @param operation   the type of patch operation, never null
   * @param cause       the failure cause, never null
   */
  default void patchPublishFailed(final String catalogName,
      final PatchOperation operation, final Throwable cause) {
  }

  /** Records a patch event received from another node.
   * @param catalogName the name of the catalog, never null
   * @param operation   the type of patch operation, never null
   */
  default void patchReceived(final String catalogName,
      final PatchOperation operation) {
  }
```

Import: `import org.waabox.andersoni.PatchOperation;`

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -pl andersoni-core`
Expected: BUILD SUCCESS (all default methods, no implementations need changes)

- [ ] **Step 3: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/metrics/AndersoniMetrics.java
git commit -m "Add patch metric methods to AndersoniMetrics interface"
```

---

## Task 6: Andersoni Orchestration (*AndSync, wireSyncListener, AsyncRefreshDispatcher)

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniPatchSyncTest.java`

- [ ] **Step 1: Write failing tests for Andersoni patch sync**

Create `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniPatchSyncTest.java`:

```java
package org.waabox.andersoni;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.easymock.Capture;
import org.junit.jupiter.api.Test;
import org.waabox.andersoni.snapshot.SnapshotSerializer;
import org.waabox.andersoni.sync.PatchEvent;
import org.waabox.andersoni.sync.SyncEvent;
import org.waabox.andersoni.sync.SyncListener;
import org.waabox.andersoni.sync.SyncStrategy;

class AndersoniPatchSyncTest {

  record Item(String id, String value) {}

  static final class ItemSerializer implements SnapshotSerializer<Item> {
    @Override
    public byte[] serialize(final List<Item> items) {
      final StringBuilder sb = new StringBuilder();
      for (final Item i : items) {
        sb.append(i.id()).append(":").append(i.value()).append(";");
      }
      return sb.toString().getBytes();
    }

    @Override
    public List<Item> deserialize(final byte[] data) {
      final String str = new String(data);
      return java.util.Arrays.stream(str.split(";"))
          .filter(s -> !s.isEmpty())
          .map(s -> {
            final String[] parts = s.split(":");
            return new Item(parts[0], parts[1]);
          })
          .toList();
    }
  }

  private Catalog<Item> buildCatalog(final List<Item> data) {
    return Catalog.of(Item.class)
        .named("items")
        .data(data)
        .identifiedBy(Item::id)
        .serializer(new ItemSerializer())
        .index("by-value").by(Item::value)
        .build();
  }

  @Test
  void whenAddAndSync_givenSyncStrategy_shouldPublishPatchEvent() {
    final SyncStrategy sync = createMock(SyncStrategy.class);
    final Capture<SyncEvent> captured = newCapture();

    sync.subscribe(anyObject(SyncListener.class));
    expectLastCall();
    sync.start();
    expectLastCall();
    sync.publish(capture(captured));
    expectLastCall();
    replay(sync);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("test-node")
        .syncStrategy(sync)
        .build();
    andersoni.register(buildCatalog(List.of(new Item("1", "a"))));
    andersoni.start();

    andersoni.addAndSync("items", new Item("2", "b"));

    assertTrue(captured.hasCaptured());
    final PatchEvent event = (PatchEvent) captured.getValue();
    assertEquals("items", event.catalogName());
    assertEquals(PatchOperation.ADD, event.operationType());
    assertEquals("test-node", event.sourceNodeId());

    andersoni.stop();
    verify(sync);
  }

  @Test
  void whenAddAndSync_givenNoSyncStrategy_shouldWorkLocally() {
    final Andersoni andersoni = Andersoni.builder()
        .nodeId("test-node")
        .build();
    andersoni.register(buildCatalog(List.of(new Item("1", "a"))));
    andersoni.start();

    andersoni.addAndSync("items", new Item("2", "b"));

    final Catalog<?> catalog = andersoni.catalog("items");
    assertEquals(2, catalog.currentSnapshot().data().size());

    andersoni.stop();
  }

  @Test
  void whenBootstrapping_givenIdentityAndSyncButNoSerializer_shouldFail() {
    final SyncStrategy sync = createMock(SyncStrategy.class);
    sync.subscribe(anyObject(SyncListener.class));
    expectLastCall();
    sync.start();
    expectLastCall();
    replay(sync);

    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named("items")
        .data(List.of(new Item("1", "a")))
        .identifiedBy(Item::id)
        .index("by-value").by(Item::value)
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("test-node")
        .syncStrategy(sync)
        .build();
    andersoni.register(catalog);

    assertThrows(IllegalStateException.class, andersoni::start);
  }
}
```

**Note:** The test for receiving-side error swallowing and receiving PatchEvent from self are complex (require wiring a listener and dispatching). Implement these tests after the main patch sync code is in place — they can be added as additional test methods in the same file during Step 7.

Additional test to add during Step 7 (after implementation):
- Receiving-side error swallowing: publish a PatchEvent with ADD for an item that already exists locally. Verify no exception propagates and the catalog state is unchanged.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl andersoni-core -Dtest=AndersoniPatchSyncTest`
Expected: FAIL (addAndSync method does not exist)

- [ ] **Step 3: Add dispatchPatch to AsyncRefreshDispatcher**

In `Andersoni.java`, add `dispatchPatch(String catalogName, Runnable patchTask)` to the `AsyncRefreshDispatcher` inner class (after the existing `dispatch` method, ~line 1097):

```java
void dispatchPatch(final String catalogName, final Runnable patchTask) {
  final Semaphore semaphore = semaphores.get(catalogName);
  if (semaphore == null) {
    log.warn("No dispatcher configured for catalog '{}'", catalogName);
    return;
  }

  Thread.startVirtualThread(() -> {
    try {
      semaphore.acquire();
      try {
        patchTask.run();
      } finally {
        semaphore.release();
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Patch dispatch interrupted for catalog '{}'", catalogName);
    }
  });
}
```

No coalescing — every patch task is executed. Shares the same per-catalog semaphore for serialization.

- [ ] **Step 4: Rework wireSyncListener to use visitor pattern**

Replace the current `wireSyncListener()` (lines 811-843) with:

```java
private void wireSyncListener() {
  if (syncStrategy == null) {
    return;
  }

  syncStrategy.subscribe(event -> event.accept(syncEventHandler));

  syncStrategy.start();
}
```

Add a private field `syncEventHandler` (of type `SyncEventHandler`) initialized in the constructor or as an anonymous class/lambda in the field declaration, implementing:
- `onRefresh(RefreshEvent)`: existing logic (skip self, hash check, dispatch refresh)
- `onPatch(PatchEvent)`: skip self, deserialize items, dispatch patch via `asyncRefreshDispatcher.dispatchPatch()`

- [ ] **Step 5: Add *AndSync methods**

Add the following methods to Andersoni (after `refreshAndSync`, ~line 493). Follow the same pattern: local apply → publish SyncEvent → metrics.

```java
public <T> void addAndSync(final String catalogName, final T item) {
  addAndSync(catalogName, List.of(
      Objects.requireNonNull(item, "item must not be null")));
}

@SuppressWarnings("unchecked")
public <T> void addAndSync(final String catalogName,
    final Collection<T> items) {
  Objects.requireNonNull(catalogName, "catalogName must not be null");
  Objects.requireNonNull(items, "items must not be null");
  requireNotStopped();

  final Catalog<T> catalog = (Catalog<T>) requireCatalog(catalogName);
  catalog.add(items);

  metrics.patchApplied(catalogName, PatchOperation.ADD);
  publishPatchEvent(catalogName, PatchOperation.ADD, catalog, items);
}
```

Similar for `updateAndSync`, `upsertAndSync`, `removeAndSync`.

Add private helper:
```java
private <T> void publishPatchEvent(final String catalogName,
    final PatchOperation operation, final Catalog<T> catalog,
    final Collection<T> items) {
  if (syncStrategy == null) {
    return;
  }
  final SnapshotSerializer<T> serializer = catalog.serializer()
      .orElseThrow(() -> new IllegalStateException(
          "Catalog '" + catalogName + "' requires a serializer for sync"));
  final byte[] payload = serializer.serialize(List.copyOf(items));
  final Snapshot<?> snapshot = catalog.currentSnapshot();
  final PatchEvent event = new PatchEvent(
      catalogName, nodeId, snapshot.version(), operation,
      payload, Instant.now());
  try {
    syncStrategy.publish(event);
    metrics.patchPublished(catalogName, operation);
  } catch (final Exception e) {
    log.error("Failed to publish patch event for catalog '{}': {}",
        catalogName, e.getMessage(), e);
    metrics.patchPublishFailed(catalogName, operation, e);
  }
}
```

- [ ] **Step 6: Add bootstrap validation**

In the bootstrap logic, after all catalogs are bootstrapped, validate:
- For each catalog with an identity function and a configured SyncStrategy, ensure a serializer is present. If not, throw `IllegalStateException` with a clear message.

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn test -pl andersoni-core -Dtest=AndersoniPatchSyncTest`
Expected: PASS

- [ ] **Step 8: Run full core test suite**

Run: `mvn test -pl andersoni-core`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java \
  andersoni-core/src/test/java/org/waabox/andersoni/AndersoniPatchSyncTest.java
git commit -m "Add patch sync orchestration to Andersoni with visitor-based dispatch"
```

---

## Task 7: Update Sync Module Implementations

**Files:**
- Modify: `andersoni-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/KafkaSyncStrategy.java`
- Modify: `andersoni-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/KafkaSyncStrategyTest.java`
- Modify: `andersoni-spring-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncStrategy.java`
- Modify: `andersoni-spring-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncStrategyTest.java`
- Modify: `andersoni-sync-http/src/main/java/org/waabox/andersoni/sync/http/HttpSyncStrategy.java`
- Modify: `andersoni-sync-http/src/test/java/org/waabox/andersoni/sync/http/HttpSyncStrategyTest.java`
- Modify: `andersoni-sync-db/src/main/java/org/waabox/andersoni/sync/db/DbPollingSyncStrategy.java`
- Modify: `andersoni-sync-db/src/test/java/org/waabox/andersoni/sync/db/DbPollingSyncStrategyTest.java`

All four modules need the same type substitutions. They never inspect the event type — they just serialize/transport/deserialize.

- [ ] **Step 1: Update KafkaSyncStrategy**

In `KafkaSyncStrategy.java`:
- Replace `import ...RefreshListener` → `import ...SyncListener`
- Replace `import ...RefreshEvent` → `import ...SyncEvent`
- Replace `import ...RefreshEventCodec` → `import ...SyncEventCodec`
- Change `List<RefreshListener>` → `List<SyncListener>`
- Change `publish(final RefreshEvent event)` → `publish(final SyncEvent event)`
- Change `subscribe(final RefreshListener listener)` → `subscribe(final SyncListener listener)`
- Change `notifyListeners(final RefreshEvent event)` → `notifyListeners(final SyncEvent event)`
- Change `listener.onRefresh(event)` → `listener.onEvent(event)`
- Change `RefreshEventCodec.serialize(event)` → `SyncEventCodec.serialize(event)`
- Change `RefreshEventCodec.deserialize(...)` → `SyncEventCodec.deserialize(...)`
- Update javadoc references

- [ ] **Step 2: Update KafkaSyncStrategyTest**

Apply same type substitutions in the test file. Update any RefreshEvent construction to still work (it still exists, just implements SyncEvent now).

- [ ] **Step 3: Run Kafka module tests**

Run: `mvn test -pl andersoni-sync-kafka`
Expected: PASS

- [ ] **Step 4: Update SpringKafkaSyncStrategy + test**

Same substitutions as Kafka.

- [ ] **Step 5: Run Spring Kafka module tests**

Run: `mvn test -pl andersoni-spring-sync-kafka`
Expected: PASS

- [ ] **Step 6: Update HttpSyncStrategy + test**

Same substitutions.

- [ ] **Step 7: Run HTTP module tests**

Run: `mvn test -pl andersoni-sync-http`
Expected: PASS

- [ ] **Step 8: Update DbPollingSyncStrategy (structural change)**

**Important:** Unlike the other sync modules, `DbPollingSyncStrategy` is NOT a simple type substitution. It unpacks `RefreshEvent`-specific fields (`hash`) into database columns and reconstructs `RefreshEvent` objects in the `poll()` method. It must be reworked to store serialized JSON.

Modify `andersoni-sync-db/src/main/java/org/waabox/andersoni/sync/db/DbPollingSyncStrategy.java`:

1. Replace all `RefreshEvent` → `SyncEvent`, `RefreshListener` → `SyncListener`, `RefreshEventCodec` → `SyncEventCodec`
2. **Rework `createTableIfNotExists()`** — new schema:
   ```sql
   CREATE TABLE IF NOT EXISTS <tableName> (
     catalog_name VARCHAR(255) NOT NULL,
     source_node_id VARCHAR(255) NOT NULL,
     version BIGINT NOT NULL,
     event_type VARCHAR(20) NOT NULL,
     event_json TEXT NOT NULL,
     updated_at TIMESTAMP NOT NULL,
     PRIMARY KEY (catalog_name)
   )
   ```
   Note: For the DB polling strategy, only the latest event per catalog matters (UPSERT semantics via UPDATE-then-INSERT). The `event_json` column stores the full serialized event via `SyncEventCodec.serialize()`.

3. **Rework `publish(SyncEvent event)`** — serialize the entire event to JSON and store in `event_json` column:
   ```java
   public void publish(final SyncEvent event) {
     final String eventJson = SyncEventCodec.serialize(event);
     // UPDATE ... SET source_node_id=?, version=?, event_type=?, event_json=?, updated_at=? WHERE catalog_name=?
     // INSERT if 0 rows affected
   }
   ```

4. **Rework `poll()`** — deserialize from `event_json` column:
   ```java
   private void poll() {
     // SELECT catalog_name, version, event_type, event_json, updated_at FROM <table>
     // For each row:
     final String eventJson = rs.getString("event_json");
     final long version = rs.getLong("version");
     final long previousVersion = lastKnownVersions.getOrDefault(catalogName, -1L);
     if (version != previousVersion) {
       lastKnownVersions.put(catalogName, version);
       final SyncEvent event = SyncEventCodec.deserialize(eventJson);
       notifyListeners(event);
     }
   }
   ```

5. **Change detection**: Replace hash-based change detection (`lastKnownHashes`) with version-based detection (`lastKnownVersions` as `ConcurrentHashMap<String, Long>`). Version increases on every operation (refresh or patch), so this is a reliable change indicator that works for both event types.

- [ ] **Step 9: Update DbPollingSyncStrategyTest**

Update test to verify both `RefreshEvent` and `PatchEvent` round-trip through the database.

- [ ] **Step 10: Run DB module tests**

Run: `mvn test -pl andersoni-sync-db`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add andersoni-sync-kafka/ andersoni-spring-sync-kafka/ \
  andersoni-sync-http/ andersoni-sync-db/
git commit -m "Update all sync modules to use SyncEvent/SyncListener/SyncEventCodec"
```

---

## Task 8: Update Spring Boot Starter and Datadog Metrics

**Files:**
- Modify: `andersoni-spring-boot-starter/src/test/java/org/waabox/andersoni/spring/AndersoniAutoConfigurationTest.java`
- Modify: `andersoni-metrics-datadog/src/main/java/org/waabox/andersoni/metrics/datadog/DatadogAndersoniMetrics.java`
- Modify: `andersoni-metrics-datadog/src/test/java/org/waabox/andersoni/metrics/datadog/DatadogAndersoniMetricsTest.java`

- [ ] **Step 1: Update Spring Boot auto-config test**

In `AndersoniAutoConfigurationTest.java`, update the inner `TestSyncStrategy` class to implement the new interface signatures (SyncEvent/SyncListener instead of RefreshEvent/RefreshListener).

- [ ] **Step 2: Run Spring Boot starter tests**

Run: `mvn test -pl andersoni-spring-boot-starter`
Expected: PASS

- [ ] **Step 3: Add patch metrics to DatadogAndersoniMetrics**

Implement the new `patchApplied`, `patchFailed`, `patchPublished`, `patchPublishFailed`, `patchReceived` methods in `DatadogAndersoniMetrics.java`. Follow the existing pattern — these methods should send DogStatsD metrics (counters with tags for catalog name and operation type).

- [ ] **Step 4: Write tests for Datadog patch metrics**

Add test methods to `DatadogAndersoniMetricsTest.java` verifying the new patch metric methods send the correct DogStatsD counters.

- [ ] **Step 5: Run Datadog metrics tests**

Run: `mvn test -pl andersoni-metrics-datadog`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add andersoni-spring-boot-starter/ andersoni-metrics-datadog/
git commit -m "Update Spring Boot starter and Datadog metrics for patch operations"
```

---

## Task 9: Cleanup Deprecated Files and Full Build Verification

**Files:**
- Delete: `andersoni-core/src/main/java/org/waabox/andersoni/sync/RefreshListener.java`
- Delete: `andersoni-core/src/main/java/org/waabox/andersoni/sync/RefreshEventCodec.java`
- Delete: `andersoni-core/src/test/java/org/waabox/andersoni/sync/RefreshEventCodecTest.java`

- [ ] **Step 1: Delete RefreshListener.java**

Delete `andersoni-core/src/main/java/org/waabox/andersoni/sync/RefreshListener.java` — replaced by `SyncListener.java`. All references should have been updated in Tasks 2 and 7.

- [ ] **Step 2: Delete RefreshEventCodec.java and its test**

Delete `andersoni-core/src/main/java/org/waabox/andersoni/sync/RefreshEventCodec.java` — replaced by `SyncEventCodec.java`.
Delete `andersoni-core/src/test/java/org/waabox/andersoni/sync/RefreshEventCodecTest.java` — replaced by `SyncEventCodecTest.java`.

- [ ] **Step 3: Verify no remaining references to deleted classes**

Run: `grep -r "RefreshListener\|RefreshEventCodec" andersoni-*/src/ --include="*.java"`
Expected: No matches (only comments or imports that should have been cleaned up)

- [ ] **Step 4: Run full build**

Run: `mvn clean verify`
Expected: BUILD SUCCESS across all modules

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Remove deprecated RefreshListener, RefreshEventCodec, and test"
```

---

## Task 10: Version Bump

**Files:**
- Modify: `pom.xml` (parent)

- [ ] **Step 1: Bump version**

This is a breaking change release. Bump the version in the parent `pom.xml` from `1.7.1` to `2.0.0` (or `1.8.0` if the project prefers minor bumps for breaking changes — check with the project owner).

- [ ] **Step 2: Run full build with new version**

Run: `mvn clean verify`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "Bump version to 2.0.0 for patch operations breaking changes"
```
