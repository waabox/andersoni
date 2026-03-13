# Snapshot Patch Support Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add single-item and batch add/remove patching to Andersoni snapshots without full index rebuilds, with cross-node sync via a new PatchEvent.

**Architecture:** Introduce a `SyncEvent` visitor hierarchy (`RefreshEvent` + `PatchEvent`) in the sync layer, expose key extractors on index definitions, add patch methods to `Catalog` that surgically update index maps, and wire `Andersoni` to orchestrate local patching + event broadcast + snapshot persistence. All four sync modules updated for the new event model.

**Tech Stack:** Java 21, JUnit 5, EasyMock, Maven multi-module

**Spec:** `docs/superpowers/specs/2026-03-13-snapshot-patch-support-design.md`

**Branch:** `feature/3/snapshot-patch-support` (synced from `main`)

**Build command:** `mvn clean verify` (from project root)

---

## Chunk 1: Sync Event Model

### Task 1: Create SyncEvent interface

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncEvent.java`

- [ ] **Step 1: Create SyncEvent interface**

```java
package org.waabox.andersoni.sync;

import java.time.Instant;

/**
 * Base interface for all synchronization events broadcast across nodes.
 *
 * <p>Events are dispatched via the visitor pattern using
 * {@link SyncEventVisitor}. Implementations include {@link RefreshEvent}
 * for full catalog reloads and {@link PatchEvent} for incremental
 * add/remove operations.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SyncEvent {

  /**
   * Returns the name of the catalog this event targets.
   *
   * @return the catalog name, never null
   */
  String catalogName();

  /**
   * Returns the identifier of the node that originated this event.
   *
   * @return the source node identifier, never null
   */
  String sourceNodeId();

  /**
   * Returns the monotonically increasing version number.
   *
   * @return the version number
   */
  long version();

  /**
   * Returns the content hash of the snapshot after this event was applied.
   *
   * @return the hash string, never null
   */
  String hash();

  /**
   * Returns the instant at which this event was created.
   *
   * @return the timestamp, never null
   */
  Instant timestamp();

  /**
   * Dispatches this event to the appropriate visitor method.
   *
   * @param visitor the visitor to accept, never null
   */
  void accept(SyncEventVisitor visitor);
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn compile -pl andersoni-core -q`
Expected: BUILD SUCCESS (will fail until SyncEventVisitor exists, so create both together)

### Task 2: Create SyncEventVisitor interface

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncEventVisitor.java`

- [ ] **Step 1: Create SyncEventVisitor interface**

```java
package org.waabox.andersoni.sync;

/**
 * Visitor for {@link SyncEvent} subtypes, enabling type-safe dispatch
 * without instanceof checks.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SyncEventVisitor {

  /**
   * Visits a full refresh event.
   *
   * @param event the refresh event, never null
   */
  void visit(RefreshEvent event);

  /**
   * Visits a patch (add/remove) event.
   *
   * @param event the patch event, never null
   */
  void visit(PatchEvent event);
}
```

- [ ] **Step 2: Verify it compiles (will fail — PatchEvent doesn't exist yet, that's expected)**

### Task 3: Create PatchType enum

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/sync/PatchType.java`

- [ ] **Step 1: Create PatchType enum**

```java
package org.waabox.andersoni.sync;

/**
 * The type of patch operation carried by a {@link PatchEvent}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public enum PatchType {

  /** An item (or items) should be added to the snapshot. */
  ADD,

  /** An item (or items) should be removed from the snapshot. */
  REMOVE
}
```

### Task 4: Create PatchEvent record

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/sync/PatchEvent.java`

- [ ] **Step 1: Create PatchEvent record**

```java
package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Objects;

/**
 * An event carrying a patch (add or remove) to be applied to a catalog
 * snapshot.
 *
 * <p>The {@link #items()} field contains the affected items serialized via
 * the catalog's {@link org.waabox.andersoni.snapshot.SnapshotSerializer},
 * always as a list (even for single-item patches).
 *
 * <p><strong>Note:</strong> This record contains a {@code byte[]} field,
 * so the generated {@code equals()} and {@code hashCode()} use identity
 * comparison for that field. This is acceptable because {@code PatchEvent}
 * instances are not expected to be compared or stored in collections.
 *
 * @param catalogName   the catalog this patch targets, never null
 * @param sourceNodeId  the node that originated this patch, never null
 * @param version       the snapshot version after the patch
 * @param hash          the snapshot hash after the patch, never null
 * @param timestamp     the instant this event was created, never null
 * @param patchType     ADD or REMOVE, never null
 * @param items         the serialized items, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record PatchEvent(
    String catalogName,
    String sourceNodeId,
    long version,
    String hash,
    Instant timestamp,
    PatchType patchType,
    byte[] items
) implements SyncEvent {

  /** Validates all parameters are non-null. */
  public PatchEvent {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
    Objects.requireNonNull(hash, "hash must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
    Objects.requireNonNull(patchType, "patchType must not be null");
    Objects.requireNonNull(items, "items must not be null");
  }

  /** {@inheritDoc} */
  @Override
  public void accept(final SyncEventVisitor visitor) {
    Objects.requireNonNull(visitor, "visitor must not be null");
    visitor.visit(this);
  }
}
```

### Task 5: Modify RefreshEvent to implement SyncEvent

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/sync/RefreshEvent.java`

- [ ] **Step 1: Update RefreshEvent to implement SyncEvent**

The record already has the five common fields. Add `implements SyncEvent` and the `accept()` method:

```java
package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a catalog refresh event that is broadcast across nodes.
 *
 * <p>When a catalog is refreshed on one node, a {@code RefreshEvent} is
 * published so that other nodes in the cluster can synchronize their local
 * caches. The event carries enough metadata for receivers to decide whether
 * they need to reload their catalog (version comparison, hash verification).
 *
 * @param catalogName   the name of the catalog that was refreshed, never null
 * @param sourceNodeId  the identifier of the node that originated the refresh,
 *                      never null
 * @param version       a monotonically increasing version number for ordering
 * @param hash          a content hash (e.g. SHA-256) of the refreshed data,
 *                      used for integrity verification, never null
 * @param timestamp     the instant at which the refresh occurred, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record RefreshEvent(
    String catalogName,
    String sourceNodeId,
    long version,
    String hash,
    Instant timestamp
) implements SyncEvent {

  /** {@inheritDoc} */
  @Override
  public void accept(final SyncEventVisitor visitor) {
    Objects.requireNonNull(visitor, "visitor must not be null");
    visitor.visit(this);
  }
}
```

### Task 6: Create SyncEventListener (replaces RefreshListener)

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncEventListener.java`

- [ ] **Step 1: Create SyncEventListener**

```java
package org.waabox.andersoni.sync;

/**
 * A listener that is notified when a synchronization event is received.
 *
 * <p>Replaces {@link RefreshListener} to support both {@link RefreshEvent}
 * and {@link PatchEvent} types.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@FunctionalInterface
public interface SyncEventListener {

  /**
   * Called when a sync event is received from the synchronization layer.
   *
   * @param event the sync event, never null
   */
  void onEvent(SyncEvent event);
}
```

### Task 7: Update SyncStrategy interface

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncStrategy.java`

- [ ] **Step 1: Update method signatures**

Change `publish(RefreshEvent)` to `publish(SyncEvent)` and `subscribe(RefreshListener)` to `subscribe(SyncEventListener)`:

```java
package org.waabox.andersoni.sync;

/**
 * A strategy for synchronizing catalog events across nodes.
 *
 * <p>Implementations of this interface define how sync events are
 * distributed (e.g. via Kafka, HTTP, database polling) and manage the
 * lifecycle of the underlying transport.
 *
 * <p>Typical lifecycle:
 * <ol>
 *   <li>Register listeners via {@link #subscribe(SyncEventListener)}</li>
 *   <li>Call {@link #start()} to begin receiving events</li>
 *   <li>Publish events via {@link #publish(SyncEvent)}</li>
 *   <li>Call {@link #stop()} to shut down the transport</li>
 * </ol>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SyncStrategy {

  /**
   * Publishes a sync event to all subscribed nodes.
   *
   * @param event the sync event to broadcast, never null
   */
  void publish(SyncEvent event);

  /**
   * Registers a listener that will be notified of incoming sync events.
   *
   * <p>Listeners must be registered before calling {@link #start()}.
   *
   * @param listener the listener to register, never null
   */
  void subscribe(SyncEventListener listener);

  /**
   * Starts the synchronization transport, enabling event reception.
   */
  void start();

  /**
   * Stops the synchronization transport and releases associated resources.
   */
  void stop();
}
```

### Task 8: Delete RefreshListener

**Files:**
- Delete: `andersoni-core/src/main/java/org/waabox/andersoni/sync/RefreshListener.java`

- [ ] **Step 1: Delete RefreshListener.java**

```bash
rm andersoni-core/src/main/java/org/waabox/andersoni/sync/RefreshListener.java
```

### Task 9: Replace RefreshEventCodec with SyncEventCodec

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/sync/SyncEventCodec.java`
- Delete: `andersoni-core/src/main/java/org/waabox/andersoni/sync/RefreshEventCodec.java`

- [ ] **Step 1: Create SyncEventCodec with support for both event types**

New class replacing `RefreshEventCodec`. Adds a `type` discriminator field in JSON. For `PatchEvent`, encodes `items` as Base64. Backward-compatible: JSON without `type` field deserializes as `RefreshEvent`.

```java
package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Static utility class for serializing and deserializing
 * {@link SyncEvent} instances to and from JSON strings.
 *
 * <p>Uses a {@code "type"} discriminator field to distinguish between
 * {@link RefreshEvent} and {@link PatchEvent}. Uses {@link JSONObject}
 * from org.json for lightweight JSON processing. {@link Instant} values
 * are stored as ISO-8601 strings. {@code byte[]} fields are Base64-encoded.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class SyncEventCodec {

  /** Type discriminator for RefreshEvent. */
  private static final String TYPE_REFRESH = "refresh";

  /** Type discriminator for PatchEvent. */
  private static final String TYPE_PATCH = "patch";

  /** Private constructor to prevent instantiation. */
  private SyncEventCodec() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Serializes a {@link SyncEvent} into a JSON string.
   *
   * @param event the event to serialize, never null
   * @return the JSON representation, never null
   */
  public static String serialize(final SyncEvent event) {
    Objects.requireNonNull(event, "event cannot be null");

    final JSONObject node = new JSONObject();
    node.put("catalogName", event.catalogName());
    node.put("sourceNodeId", event.sourceNodeId());
    node.put("version", event.version());
    node.put("hash", event.hash());
    node.put("timestamp", event.timestamp().toString());

    if (event instanceof PatchEvent patch) {
      node.put("type", TYPE_PATCH);
      node.put("patchType", patch.patchType().name());
      node.put("items", Base64.getEncoder().encodeToString(patch.items()));
    } else {
      node.put("type", TYPE_REFRESH);
    }

    return node.toString();
  }

  /**
   * Deserializes a JSON string into a {@link SyncEvent}.
   *
   * <p>If the JSON has no {@code "type"} field, it is treated as a
   * {@link RefreshEvent} for backward compatibility.
   *
   * @param json the JSON string to parse, never null
   * @return the parsed event, never null
   * @throws IllegalArgumentException if the JSON is malformed
   */
  public static SyncEvent deserialize(final String json) {
    Objects.requireNonNull(json, "json cannot be null");

    try {
      final JSONObject node = new JSONObject(json);

      final String catalogName = requireString(node, "catalogName");
      final String sourceNodeId = requireString(node, "sourceNodeId");
      final long version = node.getLong("version");
      final String hash = requireString(node, "hash");
      final Instant timestamp = Instant.parse(
          requireString(node, "timestamp"));

      final String type = node.optString("type", TYPE_REFRESH);

      if (TYPE_PATCH.equals(type)) {
        final PatchType patchType = PatchType.valueOf(
            requireString(node, "patchType"));
        final byte[] items = Base64.getDecoder().decode(
            requireString(node, "items"));
        return new PatchEvent(catalogName, sourceNodeId, version, hash,
            timestamp, patchType, items);
      }

      return new RefreshEvent(
          catalogName, sourceNodeId, version, hash, timestamp);

    } catch (final IllegalArgumentException e) {
      throw e;
    } catch (final JSONException e) {
      throw new IllegalArgumentException(
          "Failed to deserialize SyncEvent from JSON: " + json, e);
    }
  }

  /**
   * Returns the string value for the given key or throws if missing.
   *
   * @param node  the JSON object
   * @param field the field name to look up
   * @return the string value, never null
   * @throws IllegalArgumentException if the field is missing
   */
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

- [ ] **Step 2: Delete the old RefreshEventCodec.java file**

```bash
rm andersoni-core/src/main/java/org/waabox/andersoni/sync/RefreshEventCodec.java
```

### Task 10: Update RefreshEventCodecTest to SyncEventCodecTest

**Files:**
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/sync/RefreshEventCodecTest.java`

- [ ] **Step 1: Rename and extend the test class**

Rename to `SyncEventCodecTest`. Keep existing RefreshEvent tests (update method calls). Add PatchEvent serialization/deserialization tests. Add backward-compatibility test (JSON without `type` field deserializes as RefreshEvent).

- [ ] **Step 2: Run tests**

Run: `mvn test -pl andersoni-core -Dtest=SyncEventCodecTest -q`
Expected: All tests pass

### Task 11: Update existing tests that reference RefreshListener

**Files:**
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniIntegrationTest.java`
- Modify: `andersoni-spring-boot-starter/src/test/java/org/waabox/andersoni/spring/AndersoniAutoConfigurationTest.java`

- [ ] **Step 1: Update AndersoniTest.java**

Replace all references:
- `import org.waabox.andersoni.sync.RefreshListener` → `import org.waabox.andersoni.sync.SyncEventListener`
- `anyObject(RefreshListener.class)` → `anyObject(SyncEventListener.class)`
- `Capture<RefreshListener>` → `Capture<SyncEventListener>`
- `capture(listenerCapture)` signature matches (parameter type changes from `RefreshListener` to `SyncEventListener`)
- `listener.onRefresh(event)` → `listener.onEvent(event)`
- Keep `RefreshEvent` imports and usage — `RefreshEvent` still exists, it just implements `SyncEvent` now
- `Capture<RefreshEvent>` → `Capture<SyncEvent>` for publish captures (since `publish` now takes `SyncEvent`)
- Cast the captured event: `final RefreshEvent published = (RefreshEvent) eventCapture.getValue()`

- [ ] **Step 2: Update AndersoniAutoConfigurationTest.java**

Replace:
- `import org.waabox.andersoni.sync.RefreshListener` → `import org.waabox.andersoni.sync.SyncEventListener`
- `import org.waabox.andersoni.sync.RefreshEvent` → `import org.waabox.andersoni.sync.SyncEvent`
- `publish(final RefreshEvent event)` → `publish(final SyncEvent event)`
- `subscribe(final RefreshListener listener)` → `subscribe(final SyncEventListener listener)`

- [ ] **Step 3: Check AndersoniIntegrationTest for any RefreshListener references and update if needed**

### Task 12: Compile and verify core module

- [ ] **Step 1: Compile core module and spring-boot-starter**

Run: `mvn compile -pl andersoni-core,andersoni-spring-boot-starter -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Run core and spring-boot-starter tests**

Run: `mvn test -pl andersoni-core,andersoni-spring-boot-starter -q`
Expected: All tests pass

- [ ] **Step 3: Commit the sync event model and test updates**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/sync/
git add andersoni-core/src/test/java/org/waabox/andersoni/
git add andersoni-spring-boot-starter/src/test/
git commit -m "feat: introduce SyncEvent hierarchy with visitor pattern

Add SyncEvent interface, SyncEventVisitor, PatchEvent, PatchType,
and SyncEventListener. Modify RefreshEvent to implement SyncEvent.
Replace RefreshListener with SyncEventListener. Rename
RefreshEventCodec to SyncEventCodec with support for both event types.
Update existing tests for new types.

Breaking change: SyncStrategy now uses SyncEvent/SyncEventListener."
```

---

## Chunk 2: Index Key Extraction + Snapshot.ofPreBuilt

### Task 13: Add extractKey to IndexDefinition

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/IndexDefinition.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/IndexDefinitionTest.java`

- [ ] **Step 1: Write the test**

In `IndexDefinitionTest.java`, add a test that verifies `extractKey` returns the correct key for a given item. Use the existing test domain objects.

```java
@Test
void whenExtractingKey_givenItem_shouldReturnCorrectKey() {
    final IndexDefinition<Event> def = IndexDefinition.<Event>named("by-venue")
        .by(Event::venue, Venue::name);
    final Event event = new Event("1", new Sport("Football"), new Venue("Maracana"));
    final Object key = def.extractKey(event);
    assertEquals("Maracana", key);
}

@Test
void whenExtractingKey_givenNullIntermediate_shouldReturnNull() {
    final IndexDefinition<Event> def = IndexDefinition.<Event>named("by-venue")
        .by(Event::venue, Venue::name);
    final Event event = new Event("1", new Sport("Football"), null);
    final Object key = def.extractKey(event);
    assertNull(key);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=IndexDefinitionTest#whenExtractingKey_givenItem_shouldReturnCorrectKey -q`
Expected: FAIL — `extractKey` method doesn't exist

- [ ] **Step 3: Add extractKey method to IndexDefinition**

Add this package-private method to `IndexDefinition.java`:

```java
/**
 * Extracts the index key from a single item.
 *
 * <p>Package-private: used by {@link Catalog} for patch operations.
 *
 * @param item the item to extract the key from, never null
 * @return the extracted key, may be null
 */
Object extractKey(final T item) {
    return keyExtractor.apply(item);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=IndexDefinitionTest -q`
Expected: All tests pass

### Task 14: Add extractKey to SortedIndexDefinition

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/SortedIndexDefinition.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/SortedIndexDefinitionTest.java`

- [ ] **Step 1: Write the test**

```java
@Test
void whenExtractingKey_givenItem_shouldReturnCorrectKey() {
    // Use existing test domain objects from SortedIndexDefinitionTest
    final SortedIndexDefinition<Event> def = SortedIndexDefinition.<Event>named("by-date")
        .by(Event::eventDate, EventDate::value);
    final Event event = new Event("1", new EventDate(LocalDate.of(2025, 1, 15)));
    final Object key = def.extractKey(event);
    assertEquals(LocalDate.of(2025, 1, 15), key);
}
```

(Use the actual domain objects from the existing `SortedIndexDefinitionTest` — read the file to get the exact record definitions.)

- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Add extractKey method** (same pattern as IndexDefinition)

```java
Object extractKey(final T item) {
    return keyExtractor.apply(item);
}
```

- [ ] **Step 4: Run test to verify it passes**

### Task 15: Add extractKeys to MultiKeyIndexDefinition

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/MultiKeyIndexDefinition.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/MultiKeyIndexDefinitionTest.java`

- [ ] **Step 1: Write the test**

```java
@Test
void whenExtractingKeys_givenItem_shouldReturnAllKeys() {
    // Use existing test domain from MultiKeyIndexDefinitionTest
    final MultiKeyIndexDefinition<Item> def = MultiKeyIndexDefinition.<Item>named("by-tags")
        .by(Item::tags);
    final Item item = new Item("1", List.of("java", "kotlin"));
    final List<?> keys = def.extractKeys(item);
    assertEquals(List.of("java", "kotlin"), keys);
}
```

- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Add extractKeys method**

```java
List<?> extractKeys(final T item) {
    return keysExtractor.apply(item);
}
```

- [ ] **Step 4: Run test to verify it passes**

### Task 16: Add Snapshot.ofPreBuilt factory

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Snapshot.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/SnapshotTest.java`

- [ ] **Step 1: Write the test**

Add a test in `SnapshotTest.java` that verifies `ofPreBuilt` creates a snapshot without defensive copying (i.e., the returned snapshot's index maps are the exact same references passed in):

```java
@Test
void whenCreatingViaOfPreBuilt_shouldNotDefensivelyCopy() {
    final List<String> data = Collections.unmodifiableList(List.of("a", "b"));
    final List<String> bucket = Collections.unmodifiableList(List.of("a"));
    final Map<Object, List<String>> innerIndex = Collections.unmodifiableMap(
        Map.of("key", bucket));
    final Map<String, Map<Object, List<String>>> indices =
        Collections.unmodifiableMap(Map.of("idx", innerIndex));

    final Snapshot<String> snapshot = Snapshot.ofPreBuilt(
        data, indices, Collections.emptyMap(), Collections.emptyMap(),
        1L, "abc123");

    assertSame(data, snapshot.data());
    assertSame(bucket, snapshot.search("idx", "key"));
}
```

- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Add ofPreBuilt factory method to Snapshot.java**

```java
/**
 * Creates a new snapshot from pre-built, already-immutable data structures.
 *
 * <p>Unlike {@link #of}, this factory does NOT perform defensive copying.
 * The caller is responsible for ensuring all provided collections are
 * properly constructed and immutable.
 *
 * <p>Package-private: used by {@link Catalog} for patch operations.
 *
 * @param data               the immutable data list, never null
 * @param indices            the immutable indices, never null
 * @param sortedIndices      the immutable sorted indices, never null
 * @param reversedKeyIndices the immutable reversed-key indices, never null
 * @param version            the version number
 * @param hash               the content hash, never null
 * @param <T>                the type of data items
 *
 * @return a new snapshot, never null
 */
static <T> Snapshot<T> ofPreBuilt(final List<T> data,
    final Map<String, Map<Object, List<T>>> indices,
    final Map<String, NavigableMap<Comparable<?>, List<T>>> sortedIndices,
    final Map<String, NavigableMap<String, List<T>>> reversedKeyIndices,
    final long version, final String hash) {
  Objects.requireNonNull(data, "data must not be null");
  Objects.requireNonNull(indices, "indices must not be null");
  Objects.requireNonNull(sortedIndices, "sortedIndices must not be null");
  Objects.requireNonNull(reversedKeyIndices,
      "reversedKeyIndices must not be null");
  Objects.requireNonNull(hash, "hash must not be null");
  return new Snapshot<>(data, indices, sortedIndices, reversedKeyIndices,
      version, hash, Instant.now());
}
```

- [ ] **Step 4: Run test to verify it passes**

### Task 17: Add package-private accessors to Snapshot

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Snapshot.java`

The `Catalog` patch methods need access to the existing index maps to copy and patch them. Add package-private accessors:

- [ ] **Step 1: Add accessors**

```java
/**
 * Returns the regular indices map. Package-private for Catalog patch use.
 *
 * @return the indices map, never null
 */
Map<String, Map<Object, List<T>>> indices() {
    return indices;
}

/**
 * Returns the sorted indices map. Package-private for Catalog patch use.
 *
 * @return the sorted indices map, never null
 */
Map<String, NavigableMap<Comparable<?>, List<T>>> sortedIndices() {
    return sortedIndices;
}

/**
 * Returns the reversed-key indices map. Package-private for Catalog
 * patch use.
 *
 * @return the reversed-key indices map, never null
 */
Map<String, NavigableMap<String, List<T>>> reversedKeyIndices() {
    return reversedKeyIndices;
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn compile -pl andersoni-core -q`

- [ ] **Step 3: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/IndexDefinition.java
git add andersoni-core/src/main/java/org/waabox/andersoni/SortedIndexDefinition.java
git add andersoni-core/src/main/java/org/waabox/andersoni/MultiKeyIndexDefinition.java
git add andersoni-core/src/main/java/org/waabox/andersoni/Snapshot.java
git add andersoni-core/src/test/java/org/waabox/andersoni/
git commit -m "feat: add key extraction methods, Snapshot.ofPreBuilt, and accessors

Expose package-private extractKey/extractKeys on index definitions
for use by Catalog patch operations. Add Snapshot.ofPreBuilt factory
that skips defensive copying. Add package-private accessors for
indices, sortedIndices, and reversedKeyIndices."
```

---

## Chunk 3: Catalog Patch Methods

### Task 18: Add Catalog.add(T) method

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/CatalogTest.java`

- [ ] **Step 1: Write the failing test**

Add to `CatalogTest.java`:

```java
@Test
void whenAddingItem_shouldUpdateDataAndIndices() {
    final Venue maracana = new Venue("Maracana");
    final Sport football = new Sport("Football");
    final Event e1 = new Event("1", football, maracana);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .serializer(new TestSerializer())
        .index("by-venue").by(Event::venue, Venue::name)
        .index("by-sport").by(Event::sport, Sport::name)
        .build();
    catalog.bootstrap();

    assertEquals(1, catalog.currentSnapshot().data().size());
    assertEquals(1, catalog.search("by-venue", "Maracana").size());

    final Venue wembley = new Venue("Wembley");
    final Sport rugby = new Sport("Rugby");
    final Event e2 = new Event("2", rugby, wembley);
    catalog.add(e2);

    assertEquals(2, catalog.currentSnapshot().data().size());
    assertEquals(1, catalog.search("by-venue", "Maracana").size());
    assertEquals(1, catalog.search("by-venue", "Wembley").size());
    assertEquals(1, catalog.search("by-sport", "Rugby").size());
    assertTrue(catalog.search("by-venue", "Wembley").contains(e2));
}

@Test
void whenAddingItem_shouldIncrementVersion() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(new Event("1", new Sport("Football"), new Venue("M"))))
        .serializer(new TestSerializer())
        .index("by-venue").by(Event::venue, Venue::name)
        .build();
    catalog.bootstrap();
    final long v1 = catalog.currentSnapshot().version();

    catalog.add(new Event("2", new Sport("Rugby"), new Venue("W")));
    final long v2 = catalog.currentSnapshot().version();

    assertEquals(v1 + 1, v2);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=CatalogTest#whenAddingItem_shouldUpdateDataAndIndices -q`
Expected: FAIL — `add` method doesn't exist

- [ ] **Step 3: Implement Catalog.add(T)**

Add the `add` method and private helper `patchAdd` to `Catalog.java`. The method:
1. Acquires refreshLock
2. Gets current snapshot data + indices
3. Creates new data list with item appended
4. For each IndexDefinition: extracts key, builds patched index map
5. For each SortedIndexDefinition: patches hash/sorted/reversed maps
6. For each MultiKeyIndexDefinition: patches multi-key map
7. Computes hash, increments version
8. Creates new Snapshot via ofPreBuilt, atomically swaps

```java
/**
 * Adds a single item to the current snapshot without rebuilding all indices.
 *
 * <p>The item is added to the data list and inserted into all index maps
 * at the appropriate keys. A new immutable snapshot is created and
 * atomically swapped.
 *
 * @param item the item to add, never null
 * @throws NullPointerException if item is null
 */
public void add(final T item) {
    Objects.requireNonNull(item, "item must not be null");
    addAll(List.of(item));
}

/**
 * Adds multiple items to the current snapshot without rebuilding all indices.
 *
 * @param items the items to add, never null or empty
 * @throws NullPointerException if items is null
 */
public void addAll(final List<T> items) {
    Objects.requireNonNull(items, "items must not be null");
    if (items.isEmpty()) {
        return;
    }
    refreshLock.lock();
    try {
        final Snapshot<T> snapshot = current.get();

        // Build new data list.
        final List<T> newData = new ArrayList<>(snapshot.data());
        newData.addAll(items);

        // Patch regular indices.
        final Map<String, Map<Object, List<T>>> newIndices = new HashMap<>();
        for (final IndexDefinition<T> indexDef : indexDefinitions) {
            newIndices.put(indexDef.name(),
                patchIndexAdd(snapshot, indexDef.name(), items,
                    item -> indexDef.extractKey(item)));
        }

        // Patch multi-key indices.
        for (final MultiKeyIndexDefinition<T> multiDef
            : multiKeyIndexDefinitions) {
            newIndices.put(multiDef.name(),
                patchMultiKeyIndexAdd(snapshot, multiDef.name(), items,
                    multiDef));
        }

        // Patch sorted indices.
        final Map<String, NavigableMap<Comparable<?>, List<T>>>
            newSortedIndices = new HashMap<>(snapshot.sortedIndices());
        final Map<String, NavigableMap<String, List<T>>>
            newReversedKeyIndices = new HashMap<>(
                snapshot.reversedKeyIndices());

        for (final SortedIndexDefinition<T> sortedDef
            : sortedIndexDefinitions) {
            patchSortedIndexAdd(snapshot, sortedDef, items,
                newIndices, newSortedIndices, newReversedKeyIndices);
        }

        final long version = versionCounter.incrementAndGet();
        final String hash = computeHash(newData);

        final Snapshot<T> patched = Snapshot.ofPreBuilt(
            Collections.unmodifiableList(newData),
            Collections.unmodifiableMap(newIndices),
            Collections.unmodifiableMap(newSortedIndices),
            Collections.unmodifiableMap(newReversedKeyIndices),
            version, hash);
        current.set(patched);
    } finally {
        refreshLock.unlock();
    }
}
```

Also add the private helper methods `patchIndexAdd`, `patchMultiKeyIndexAdd`, and `patchSortedIndexAdd`. These methods:
- Copy the existing index map from the snapshot
- For each item, extract its key and add it to the appropriate bucket
- Make the result unmodifiable

**Note:** The exact implementation of these helpers should handle:
- Creating new buckets when a key doesn't exist yet
- Appending to existing buckets (creating a new list since the old one is unmodifiable)
- For sorted indices: patching the hash, sorted, and (conditionally) reversed-key maps
- Checking `snapshot.reversedKeyIndices().containsKey(indexName)` to determine if reversed-key patching is needed

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=CatalogTest#whenAddingItem_shouldUpdateDataAndIndices+whenAddingItem_shouldIncrementVersion -q`
Expected: PASS

### Task 19: Add Catalog.remove(T) and removeAll(List<T>)

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/CatalogTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void whenRemovingItem_shouldUpdateDataAndIndices() {
    final Venue maracana = new Venue("Maracana");
    final Sport football = new Sport("Football");
    final Event e1 = new Event("1", football, maracana);
    final Event e2 = new Event("2", football, maracana);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1, e2))
        .serializer(new TestSerializer())
        .index("by-venue").by(Event::venue, Venue::name)
        .build();
    catalog.bootstrap();

    assertEquals(2, catalog.search("by-venue", "Maracana").size());

    catalog.remove(e1);

    assertEquals(1, catalog.currentSnapshot().data().size());
    assertEquals(1, catalog.search("by-venue", "Maracana").size());
    assertTrue(catalog.search("by-venue", "Maracana").contains(e2));
    assertFalse(catalog.search("by-venue", "Maracana").contains(e1));
}

@Test
void whenRemovingItem_givenLastItemInBucket_shouldRemoveKey() {
    final Venue maracana = new Venue("Maracana");
    final Venue wembley = new Venue("Wembley");
    final Sport football = new Sport("Football");
    final Event e1 = new Event("1", football, maracana);
    final Event e2 = new Event("2", football, wembley);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1, e2))
        .serializer(new TestSerializer())
        .index("by-venue").by(Event::venue, Venue::name)
        .build();
    catalog.bootstrap();

    catalog.remove(e2);

    assertEquals(1, catalog.currentSnapshot().data().size());
    assertTrue(catalog.search("by-venue", "Wembley").isEmpty());
    assertEquals(1, catalog.search("by-venue", "Maracana").size());
}

@Test
void whenRemovingItem_givenNotFound_shouldBeNoOp() {
    final Event e1 = new Event("1", new Sport("Football"), new Venue("M"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .serializer(new TestSerializer())
        .index("by-venue").by(Event::venue, Venue::name)
        .build();
    catalog.bootstrap();
    final long v1 = catalog.currentSnapshot().version();

    final Event nonExistent = new Event("999", new Sport("X"), new Venue("Y"));
    catalog.remove(nonExistent);

    assertEquals(v1, catalog.currentSnapshot().version());
    assertEquals(1, catalog.currentSnapshot().data().size());
}
```

- [ ] **Step 2: Run tests to verify they fail**
- [ ] **Step 3: Implement Catalog.remove(T) and removeAll(List<T>)**

Same pattern as add but in reverse:
- Build new data list without the item(s) (matched via `equals()`)
- If no items were actually removed from data, return early (no-op)
- For each index: extract key, copy index map, remove item from bucket, remove key if bucket becomes empty

```java
/**
 * Removes a single item from the current snapshot without rebuilding
 * all indices.
 *
 * <p>The item is matched via {@code equals()}. If the item is not found
 * in the data list, this method is a no-op.
 *
 * @param item the item to remove, never null
 * @throws NullPointerException if item is null
 */
public void remove(final T item) {
    Objects.requireNonNull(item, "item must not be null");
    removeAll(List.of(item));
}

/**
 * Removes multiple items from the current snapshot without rebuilding
 * all indices.
 *
 * <p>Items are matched via {@code equals()}. Items not found are ignored.
 * If no items are actually removed, this method is a no-op.
 *
 * @param items the items to remove, never null
 * @throws NullPointerException if items is null
 */
public void removeAll(final List<T> items) {
    Objects.requireNonNull(items, "items must not be null");
    if (items.isEmpty()) {
        return;
    }
    refreshLock.lock();
    try {
        final Snapshot<T> snapshot = current.get();
        final List<T> currentData = snapshot.data();

        // Build new data list without the removed items.
        final List<T> newData = new ArrayList<>(currentData);
        boolean anyRemoved = false;
        for (final T item : items) {
            anyRemoved |= newData.remove(item);
        }

        if (!anyRemoved) {
            return;
        }

        // Patch regular indices (remove items from buckets).
        final Map<String, Map<Object, List<T>>> newIndices = new HashMap<>();
        for (final IndexDefinition<T> indexDef : indexDefinitions) {
            newIndices.put(indexDef.name(),
                patchIndexRemove(snapshot, indexDef.name(), items,
                    item -> indexDef.extractKey(item)));
        }

        // Patch multi-key indices.
        for (final MultiKeyIndexDefinition<T> multiDef
            : multiKeyIndexDefinitions) {
            newIndices.put(multiDef.name(),
                patchMultiKeyIndexRemove(snapshot, multiDef.name(), items,
                    multiDef));
        }

        // Patch sorted indices.
        final Map<String, NavigableMap<Comparable<?>, List<T>>>
            newSortedIndices = new HashMap<>(snapshot.sortedIndices());
        final Map<String, NavigableMap<String, List<T>>>
            newReversedKeyIndices = new HashMap<>(
                snapshot.reversedKeyIndices());

        for (final SortedIndexDefinition<T> sortedDef
            : sortedIndexDefinitions) {
            patchSortedIndexRemove(snapshot, sortedDef, items,
                newIndices, newSortedIndices, newReversedKeyIndices);
        }

        final long version = versionCounter.incrementAndGet();
        final String hash = computeHash(newData);

        final Snapshot<T> patched = Snapshot.ofPreBuilt(
            Collections.unmodifiableList(newData),
            Collections.unmodifiableMap(newIndices),
            Collections.unmodifiableMap(newSortedIndices),
            Collections.unmodifiableMap(newReversedKeyIndices),
            version, hash);
        current.set(patched);
    } finally {
        refreshLock.unlock();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl andersoni-core -Dtest=CatalogTest -q`
Expected: All tests pass (including existing tests)

### Task 20: Test add/remove with sorted indices

**Files:**
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/CatalogTest.java`

- [ ] **Step 1: Write tests for sorted index patching**

```java
@Test
void whenAddingItem_givenSortedIndex_shouldPatchAllThreeMaps() {
    // Build a catalog with a sorted String index, bootstrap, add item,
    // verify search/searchStartsWith/searchEndsWith all work
}

@Test
void whenRemovingItem_givenSortedIndex_shouldPatchAllThreeMaps() {
    // Build a catalog with a sorted String index, bootstrap, remove item,
    // verify the removed item is gone from equality, range, and text queries
}
```

- [ ] **Step 2: Run tests, verify pass**

### Task 21: Test add/remove with multi-key indices

**Files:**
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/CatalogTest.java`

- [ ] **Step 1: Write tests**

```java
@Test
void whenAddingItem_givenMultiKeyIndex_shouldAddToAllKeys() {
    // Build a catalog with a multi-key index, bootstrap, add an item
    // with multiple keys, verify it appears under all keys
}

@Test
void whenRemovingItem_givenMultiKeyIndex_shouldRemoveFromAllKeys() {
    // Build a catalog with a multi-key index, bootstrap, remove item,
    // verify it's gone from all keys, empty keys are removed
}
```

- [ ] **Step 2: Run tests, verify pass**

### Task 22: Test addAll and removeAll batch operations

**Files:**
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/CatalogTest.java`

- [ ] **Step 1: Write batch tests**

```java
@Test
void whenAddingMultipleItems_shouldUpdateAllInSingleSnapshot() {
    // Add 3 items via addAll, verify version incremented only once,
    // all items searchable
}

@Test
void whenRemovingMultipleItems_shouldUpdateAllInSingleSnapshot() {
    // Remove 2 items via removeAll, verify version incremented only once,
    // removed items are gone
}
```

- [ ] **Step 2: Run tests, verify pass**

- [ ] **Step 3: Run full core test suite**

Run: `mvn test -pl andersoni-core -q`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java
git add andersoni-core/src/test/java/org/waabox/andersoni/CatalogTest.java
git commit -m "feat: add patch methods to Catalog (add/addAll/remove/removeAll)

Catalog can now add or remove items without rebuilding all indices.
Each patch operation extracts keys for the affected items only,
surgically updates the index maps, and atomically swaps a new
Snapshot via ofPreBuilt."
```

---

## Chunk 4: Andersoni Patch API + Sync Wiring

### Task 23: Update Andersoni sync listener to use visitor

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`

- [ ] **Step 1: Update wireSyncListener() to use SyncEventListener and SyncEventVisitor**

Change the lambda from `RefreshListener` to `SyncEventListener`, and dispatch via visitor:

```java
private void wireSyncListener() {
    if (syncStrategy == null) {
        return;
    }

    syncStrategy.subscribe(event -> {
        if (nodeId.equals(event.sourceNodeId())) {
            log.debug("Ignoring sync event from self for catalog '{}'",
                event.catalogName());
            return;
        }

        event.accept(new SyncEventVisitor() {
            @Override
            public void visit(final RefreshEvent refreshEvent) {
                handleRefreshEvent(refreshEvent);
            }

            @Override
            public void visit(final PatchEvent patchEvent) {
                handlePatchEvent(patchEvent);
            }
        });
    });

    syncStrategy.start();
}
```

- [ ] **Step 2: Extract handleRefreshEvent from existing refreshFromEvent**

Rename/refactor `refreshFromEvent` to `handleRefreshEvent`. Add hash check logic (existing).

- [ ] **Step 3: Implement handlePatchEvent**

```java
@SuppressWarnings("unchecked")
private void handlePatchEvent(final PatchEvent event) {
    final Catalog<?> catalog = catalogsByName.get(event.catalogName());
    if (catalog == null) {
        log.warn("Received patch event for unknown catalog '{}'",
            event.catalogName());
        return;
    }

    // Version validation: if not sequential, fall back to full refresh.
    final long localVersion = catalog.currentSnapshot().version();
    if (localVersion != event.version() - 1) {
        log.warn("Catalog '{}': version mismatch (local={}, event={}), "
            + "falling back to full refresh",
            event.catalogName(), localVersion, event.version());
        refreshFromEvent(event.catalogName(), catalog);
        return;
    }

    final Optional<? extends SnapshotSerializer<?>> serializerOpt =
        catalog.serializer();
    if (serializerOpt.isEmpty()) {
        log.error("Catalog '{}': cannot apply patch without serializer",
            event.catalogName());
        return;
    }

    final SnapshotSerializer<Object> serializer =
        (SnapshotSerializer<Object>) serializerOpt.get();
    final List<Object> items = serializer.deserialize(event.items());
    final Catalog<Object> typedCatalog = (Catalog<Object>) catalog;

    if (event.patchType() == PatchType.ADD) {
        typedCatalog.addAll(items);
    } else {
        typedCatalog.removeAll(items);
    }

    reportIndexSizes(catalog);
}
```

### Task 24: Add Andersoni patch API methods

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`

- [ ] **Step 1: Implement add/addAll/remove/removeAll/removeAll(Predicate)**

**Note:** Add these imports to `Andersoni.java`:
- `import java.util.function.Predicate;`
- `import org.waabox.andersoni.sync.PatchEvent;`
- `import org.waabox.andersoni.sync.PatchType;`
- `import org.waabox.andersoni.sync.SyncEvent;`
- `import org.waabox.andersoni.sync.SyncEventListener;`
- `import org.waabox.andersoni.sync.SyncEventVisitor;`

```java
@SuppressWarnings("unchecked")
@SuppressWarnings("unchecked")
public <T> void add(final String catalogName, final T item,
    final Class<T> type) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(item, "item must not be null");
    Objects.requireNonNull(type, "type must not be null");
    patchAndSync(catalogName, (List<Object>) (List<?>) List.of(item),
        PatchType.ADD);
}

@SuppressWarnings("unchecked")
public <T> void addAll(final String catalogName, final List<T> items,
    final Class<T> type) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(items, "items must not be null");
    Objects.requireNonNull(type, "type must not be null");
    patchAndSync(catalogName, (List<Object>) (List<?>) items, PatchType.ADD);
}

@SuppressWarnings("unchecked")
@SuppressWarnings("unchecked")
public <T> void remove(final String catalogName, final T item,
    final Class<T> type) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(item, "item must not be null");
    Objects.requireNonNull(type, "type must not be null");
    patchAndSync(catalogName, (List<Object>) (List<?>) List.of(item),
        PatchType.REMOVE);
}

@SuppressWarnings("unchecked")
public <T> void removeAll(final String catalogName, final List<T> items,
    final Class<T> type) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(items, "items must not be null");
    Objects.requireNonNull(type, "type must not be null");
    patchAndSync(catalogName, (List<Object>) (List<?>) items,
        PatchType.REMOVE);
}

@SuppressWarnings("unchecked")
public <T> void removeAll(final String catalogName,
    final Predicate<T> predicate, final Class<T> type) {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(predicate, "predicate must not be null");
    Objects.requireNonNull(type, "type must not be null");

    final Catalog<T> catalog = (Catalog<T>) requireCatalog(catalogName);
    final List<T> matching = catalog.currentSnapshot().data().stream()
        .filter(predicate)
        .toList();
    if (matching.isEmpty()) {
        return;
    }
    patchAndSync(catalogName, (List<Object>) (List<?>) matching,
        PatchType.REMOVE);
}
```

- [ ] **Step 2: Implement private patchAndSync helper**

```java
@SuppressWarnings("unchecked")
private void patchAndSync(final String catalogName,
    final List<Object> items, final PatchType patchType) {
    if (stopped.get()) {
        throw new IllegalStateException(
            "Cannot patch after stop() has been called");
    }
    final Catalog<Object> catalog =
        (Catalog<Object>) requireCatalog(catalogName);

    final SnapshotSerializer<Object> serializer =
        (SnapshotSerializer<Object>) catalog.serializer()
            .orElseThrow(() -> new IllegalStateException(
                "Catalog '" + catalogName + "' requires a "
                    + "SnapshotSerializer for patch operations"));

    // Apply patch locally.
    if (patchType == PatchType.ADD) {
        catalog.addAll(items);
    } else {
        catalog.removeAll(items);
    }

    reportIndexSizes(catalog);

    // Serialize items for the event.
    final byte[] serializedItems = serializer.serialize(items);

    // Publish patch event.
    if (syncStrategy != null) {
        final Snapshot<?> snapshot = catalog.currentSnapshot();
        final PatchEvent event = new PatchEvent(
            catalogName, nodeId, snapshot.version(), snapshot.hash(),
            Instant.now(), patchType, serializedItems);
        syncStrategy.publish(event);
    }

    // Save full updated snapshot.
    saveSnapshotIfPossible(catalog);
}
```

### Task 25: Update Andersoni.refreshAndSync to use SyncEvent

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`

- [ ] **Step 1: Update refreshAndSync publish call**

The existing code creates a `RefreshEvent` directly. This still works since `RefreshEvent` now implements `SyncEvent`. But update the `syncStrategy.publish()` call — it already accepts `SyncEvent`, so the existing code works without changes. Just update imports.

### Task 26: Write Andersoni patch tests

**Files:**
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java`

- [ ] **Step 1: Write test for add with sync**

Test that `andersoni.add()` patches the local catalog, publishes a `PatchEvent` via the sync strategy, and saves the snapshot.

```java
@Test
void whenAdding_shouldPatchLocallyAndPublishPatchEvent() {
    // Create mocks for SyncStrategy, SnapshotStore, LeaderElection, Metrics
    // Build Andersoni with a catalog that has a serializer
    // Call andersoni.add("events", newEvent, Event.class)
    // Verify: catalog.search finds the new item
    // Verify: syncStrategy.publish was called with a PatchEvent (ADD)
    // Verify: snapshotStore.save was called
}
```

- [ ] **Step 2: Write test for remove with sync**

```java
@Test
void whenRemoving_shouldPatchLocallyAndPublishPatchEvent() {
    // Similar to add test but with REMOVE
}
```

- [ ] **Step 3: Write test for removeAll with predicate**

```java
@Test
void whenRemovingWithPredicate_shouldResolveAndRemoveMatching() {
    // Bootstrap catalog with 3 events
    // removeAll("events", e -> e.sport().name().equals("Football"), Event.class)
    // Verify only non-Football events remain
}
```

- [ ] **Step 4: Write test for patch without serializer throws**

```java
@Test
void whenPatching_givenNoSerializer_shouldThrowIllegalState() {
    // Build catalog without serializer
    // Expect IllegalStateException from andersoni.add()
}
```

- [ ] **Step 5: Write test for handlePatchEvent on follower**

```java
@Test
void whenReceivingPatchEvent_shouldApplyPatchLocally() {
    // Bootstrap catalog, capture the SyncEventListener
    // Create a PatchEvent with serialized item
    // Call listener.onEvent(patchEvent)
    // Verify the item was added/removed from the catalog
}
```

- [ ] **Step 6: Write test for version mismatch fallback**

```java
@Test
void whenReceivingPatchEvent_givenVersionMismatch_shouldFallbackToRefresh() {
    // Bootstrap catalog (version 1)
    // Send PatchEvent with version 5 (mismatch)
    // Verify it falls back to full refresh (DataLoader.load called)
}
```

- [ ] **Step 7: Run all core tests**

Run: `mvn test -pl andersoni-core -q`
Expected: All tests pass

- [ ] **Step 8: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java
git add andersoni-core/src/main/java/org/waabox/andersoni/Snapshot.java
git add andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java
git commit -m "feat: add patch API to Andersoni with sync and persistence

Andersoni.add/remove/addAll/removeAll patch the local catalog,
publish a PatchEvent via SyncStrategy, and save the updated snapshot.
Follower nodes apply patches via visitor dispatch with version
validation and fallback to full refresh on mismatch."
```

---

## Chunk 5: Update Sync Modules

### Task 27: Update andersoni-sync-kafka

**Files:**
- Modify: `andersoni-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/KafkaSyncStrategy.java`
- Modify: `andersoni-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/KafkaSyncStrategyTest.java`

- [ ] **Step 1: Update KafkaSyncStrategy**

Changes:
- `List<RefreshListener>` → `List<SyncEventListener>`
- `publish(RefreshEvent)` → `publish(SyncEvent)`
- `subscribe(RefreshListener)` → `subscribe(SyncEventListener)`
- `notifyListeners(RefreshEvent)` → `notifyListeners(SyncEvent)`
- `RefreshEventCodec.serialize/deserialize` → `SyncEventCodec.serialize/deserialize`
- `pollLoop`: deserialize returns `SyncEvent` instead of `RefreshEvent`
- `notifyListeners`: call `listener.onEvent(event)` instead of `listener.onRefresh(event)`

- [ ] **Step 2: Update KafkaSyncStrategyTest**

Update test to use `SyncEventListener` instead of `RefreshListener`. Add a test for publishing/receiving a `PatchEvent`.

- [ ] **Step 3: Verify**

Run: `mvn test -pl andersoni-sync-kafka -q`
Expected: All tests pass

### Task 28: Update andersoni-spring-sync-kafka

**Files:**
- Modify: `andersoni-spring-sync-kafka/src/main/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncStrategy.java`
- Modify: `andersoni-spring-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncStrategyTest.java`
- Modify: `andersoni-spring-sync-kafka/src/test/java/org/waabox/andersoni/sync/kafka/spring/SpringKafkaSyncAutoConfigurationTest.java`

- [ ] **Step 1: Update SpringKafkaSyncStrategy**

Same changes as KafkaSyncStrategy:
- `List<RefreshListener>` → `List<SyncEventListener>`
- Method signatures updated
- `RefreshEventCodec` → `SyncEventCodec`
- `onMessage`: deserialize returns `SyncEvent`, call `listener.onEvent(event)`

- [ ] **Step 2: Update tests**
- [ ] **Step 3: Verify**

Run: `mvn test -pl andersoni-spring-sync-kafka -q`
Expected: All tests pass

### Task 29: Update andersoni-sync-http

**Files:**
- Modify: `andersoni-sync-http/src/main/java/org/waabox/andersoni/sync/http/HttpSyncStrategy.java`
- Modify: `andersoni-sync-http/src/test/java/org/waabox/andersoni/sync/http/HttpSyncStrategyTest.java`

- [ ] **Step 1: Update HttpSyncStrategy**

Same pattern:
- `List<RefreshListener>` → `List<SyncEventListener>`
- `publish(RefreshEvent)` → `publish(SyncEvent)`
- `subscribe(RefreshListener)` → `subscribe(SyncEventListener)`
- `RefreshEventCodec` → `SyncEventCodec`
- `handleRefresh`: deserialize returns `SyncEvent`, call `listener.onEvent(event)`

- [ ] **Step 2: Update HttpSyncStrategyTest**
- [ ] **Step 3: Verify**

Run: `mvn test -pl andersoni-sync-http -q`
Expected: All tests pass

### Task 30: Update andersoni-sync-db

**Files:**
- Modify: `andersoni-sync-db/src/main/java/org/waabox/andersoni/sync/db/DbPollingSyncStrategy.java`
- Modify: `andersoni-sync-db/src/test/java/org/waabox/andersoni/sync/db/DbPollingSyncStrategyTest.java`

- [ ] **Step 1: Update DbPollingSyncStrategy**

This module is special: it stores events in a database table. The current schema only supports `RefreshEvent` fields. For `PatchEvent`, the DB module has two options:

**Approach:** The DB polling strategy currently only detects *hash changes* and creates `RefreshEvent`s. It doesn't carry the full event payload. For `PatchEvent` support, the DB module would need a new table or extra columns for `patchType` and `items` (a BLOB).

**Pragmatic approach for this task:** Update the interface signatures (`SyncEvent`/`SyncEventListener`) so it compiles. The `publish` method should handle both event types:
- For `RefreshEvent`: existing upsert logic (unchanged)
- For `PatchEvent`: log a warning that DB sync doesn't support patch events, and fall back to writing a refresh-style row (the follower will do a full refresh via hash change detection)

The `poll` method continues to create `RefreshEvent`s from rows.

- [ ] **Step 2: Update tests**
- [ ] **Step 3: Verify**

Run: `mvn test -pl andersoni-sync-db -q`
Expected: All tests pass

### Task 31: Commit all sync module updates

**Note:** `AndersoniAutoConfigurationTest.java` was already updated in Task 11.

- [ ] **Step 1: Commit all sync module updates**

```bash
git add andersoni-sync-kafka/ andersoni-spring-sync-kafka/ andersoni-sync-http/ andersoni-sync-db/ andersoni-spring-boot-starter/
git commit -m "feat: update all sync modules for SyncEvent model

Update KafkaSyncStrategy, SpringKafkaSyncStrategy, HttpSyncStrategy,
and DbPollingSyncStrategy to use SyncEvent/SyncEventListener.
Replace RefreshEventCodec with SyncEventCodec. DB module falls back
to refresh-style events for PatchEvent (no native patch support)."
```

---

## Chunk 6: Full Build Verification

### Task 32: Full build and test

- [ ] **Step 1: Run full build from project root**

Run: `mvn clean verify`
Expected: BUILD SUCCESS for all modules

- [ ] **Step 2: Fix any compilation or test failures across modules**

- [ ] **Step 3: Final commit if any fixes were needed**

### Task 33: Commit spec and plan documents

- [ ] **Step 1: Add docs**

```bash
git add docs/superpowers/
git commit -m "docs: add snapshot patch support spec and implementation plan"
```
