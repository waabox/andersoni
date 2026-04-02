# Single-Loop Snapshot Build with Hooks — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `buildAndSwapSnapshot` into a single loop and expose a public `SnapshotBuildHook<T>` API for per-item processing during snapshot build.

**Architecture:** Replace `buildIndex`/`buildIndexFromItems` (full-list methods) with `accumulate` (single-item methods) on each index definition. The single loop: compute views → index → execute hooks. The `SnapshotBuildHook<T>` interface is public, registered via `.hook()` in the Catalog DSL with int-based priority ordering.

**Tech Stack:** Java 21, JUnit 5, EasyMock

**Spec:** `docs/superpowers/specs/2026-04-02-single-loop-build-hooks-design.md`

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `andersoni-core/.../SnapshotBuildHook.java` | Public interface: `T process(T item)` |
| Modify | `andersoni-core/.../IndexDefinition.java` | Add `accumulate()`, remove `buildIndexFromItems()` |
| Modify | `andersoni-core/.../MultiKeyIndexDefinition.java` | Add `accumulate()`, remove `buildIndexFromItems()` |
| Modify | `andersoni-core/.../GraphIndexDefinition.java` | Add `accumulate()`, remove `buildIndexFromItems()` |
| Modify | `andersoni-core/.../SortedIndexDefinition.java` | Add `accumulate()`, remove `buildIndexFromItems()` |
| Modify | `andersoni-core/.../Catalog.java` | Add hooks to BuildStep + constructor, refactor `buildAndSwapSnapshot` to single loop |
| Create | `andersoni-core/.../test/.../SnapshotBuildHookTest.java` | Tests for hook interface and priority |
| Create | `andersoni-core/.../test/.../SingleLoopBuildTest.java` | Integration tests for single-loop build with hooks |

(All paths under `andersoni-core/src/main/java/org/waabox/andersoni/` and `andersoni-core/src/test/java/org/waabox/andersoni/`)

---

### Task 1: SnapshotBuildHook interface

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/SnapshotBuildHook.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/SnapshotBuildHookTest.java`

- [ ] **Step 1: Write the test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SnapshotBuildHookTest {

  record Event(String id, String name) {}

  @Test
  void whenProcessing_givenIdentityHook_shouldReturnSameItem() {
    final SnapshotBuildHook<Event> hook = item -> item;
    final Event event = new Event("1", "Final");
    assertEquals(event, hook.process(event));
  }

  @Test
  void whenProcessing_givenTransformHook_shouldReturnTransformed() {
    final SnapshotBuildHook<Event> hook =
        item -> new Event(item.id(), item.name().toUpperCase());
    final Event event = new Event("1", "final");
    final Event result = hook.process(event);
    assertEquals("FINAL", result.name());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=SnapshotBuildHookTest -DfailIfNoTests=false`
Expected: Compilation error — `SnapshotBuildHook` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package org.waabox.andersoni;

/**
 * Hook for per-item processing during snapshot build.
 *
 * <p>Hooks are executed after indexation and view computation for each
 * item. They receive the original domain object and return a value
 * (same or transformed) that is passed to the next hook in the chain.
 *
 * <p>Hooks are registered via
 * {@link Catalog.BuildStep#hook(SnapshotBuildHook, int)} in the
 * catalog DSL and ordered by priority (lower executes first).
 *
 * @param <T> the domain object type
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@FunctionalInterface
public interface SnapshotBuildHook<T> {

  /**
   * Processes a single item during snapshot build.
   *
   * @param item the domain object, never null
   * @return the processed item, never null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  T process(T item);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=SnapshotBuildHookTest`
Expected: All 2 tests PASS.

- [ ] **Step 5: Commit**

```
feat: add SnapshotBuildHook interface
```

---

### Task 2: accumulate() on IndexDefinition

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/IndexDefinition.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/IndexDefinitionTest.java`

- [ ] **Step 1: Write the test**

Add to `IndexDefinitionTest.java`:

```java
@Test
void whenAccumulating_givenSingleItem_shouldAddToIndex() {
  final IndexDefinition<String> indexDef =
      IndexDefinition.named("by-length").by(s -> s, String::length);

  final AndersoniCatalogItem<String> item =
      AndersoniCatalogItem.of("hello", Map.of());

  final Map<Object, List<AndersoniCatalogItem<String>>> index =
      new HashMap<>();
  indexDef.accumulate(item, index);

  assertEquals(1, index.size());
  assertEquals(1, index.get(5).size());
  assertSame(item, index.get(5).get(0));
}

@Test
void whenAccumulating_givenNullKey_shouldSkipItem() {
  final IndexDefinition<String> indexDef =
      IndexDefinition.named("by-null").by(s -> s, s -> null);

  final AndersoniCatalogItem<String> item =
      AndersoniCatalogItem.of("hello", Map.of());

  final Map<Object, List<AndersoniCatalogItem<String>>> index =
      new HashMap<>();
  indexDef.accumulate(item, index);

  assertTrue(index.isEmpty());
}
```

Add imports: `java.util.HashMap`, `java.util.Map`, `org.junit.jupiter.api.Assertions.assertSame`, `org.junit.jupiter.api.Assertions.assertTrue`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=IndexDefinitionTest -DfailIfNoTests=false`
Expected: Compilation error — `accumulate` does not exist.

- [ ] **Step 3: Add accumulate() to IndexDefinition**

Add this method to `IndexDefinition.java` (package-private):

```java
/**
 * Accumulates a single catalog item into the given index map.
 *
 * <p>Extracts the key from the item's domain object and adds the
 * catalog item to the corresponding bucket. If the key is null,
 * the item is skipped.
 *
 * @param item  the catalog item to index, never null
 * @param index the mutable index map to accumulate into, never null
 */
void accumulate(final AndersoniCatalogItem<T> item,
    final Map<Object, List<AndersoniCatalogItem<T>>> index) {
  final Object key = keyExtractor.apply(item.item());
  if (key != null) {
    index.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=IndexDefinitionTest`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```
feat: add accumulate() to IndexDefinition
```

---

### Task 3: accumulate() on MultiKeyIndexDefinition

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/MultiKeyIndexDefinition.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/MultiKeyIndexDefinitionTest.java`

- [ ] **Step 1: Write the test**

Add to `MultiKeyIndexDefinitionTest.java`:

```java
@Test
void whenAccumulating_givenMultipleKeys_shouldAddToAllBuckets() {
  final MultiKeyIndexDefinition<List<String>> indexDef =
      MultiKeyIndexDefinition.named("by-values")
          .by(list -> new ArrayList<>(list));

  final List<String> data = List.of("a", "b", "c");
  final AndersoniCatalogItem<List<String>> item =
      AndersoniCatalogItem.of(data, Map.of());

  final Map<Object, List<AndersoniCatalogItem<List<String>>>> index =
      new HashMap<>();
  indexDef.accumulate(item, index);

  assertEquals(3, index.size());
  assertSame(item, index.get("a").get(0));
  assertSame(item, index.get("b").get(0));
  assertSame(item, index.get("c").get(0));
}

@Test
void whenAccumulating_givenNullKeys_shouldSkipItem() {
  final MultiKeyIndexDefinition<String> indexDef =
      MultiKeyIndexDefinition.named("by-null").by(s -> null);

  final AndersoniCatalogItem<String> item =
      AndersoniCatalogItem.of("hello", Map.of());

  final Map<Object, List<AndersoniCatalogItem<String>>> index =
      new HashMap<>();
  indexDef.accumulate(item, index);

  assertTrue(index.isEmpty());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=MultiKeyIndexDefinitionTest -DfailIfNoTests=false`
Expected: Compilation error — `accumulate` does not exist.

- [ ] **Step 3: Add accumulate() to MultiKeyIndexDefinition**

```java
/**
 * Accumulates a single catalog item into the given index map under
 * all keys returned by the multi-key extractor.
 *
 * @param item  the catalog item to index, never null
 * @param index the mutable index map to accumulate into, never null
 */
void accumulate(final AndersoniCatalogItem<T> item,
    final Map<Object, List<AndersoniCatalogItem<T>>> index) {
  final List<?> keys = keysExtractor.apply(item.item());
  if (keys == null || keys.isEmpty()) {
    return;
  }
  for (final Object key : keys) {
    if (key != null) {
      index.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=MultiKeyIndexDefinitionTest`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```
feat: add accumulate() to MultiKeyIndexDefinition
```

---

### Task 4: accumulate() on SortedIndexDefinition

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/SortedIndexDefinition.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/SortedIndexDefinitionTest.java`

This is the most complex accumulator — it must populate three maps simultaneously (hash, sorted, reversed) with shared buckets.

- [ ] **Step 1: Write the test**

Add to `SortedIndexDefinitionTest.java`:

```java
@Test
void whenAccumulating_givenStringKey_shouldPopulateAllThreeMaps() {
  final SortedIndexDefinition<String> indexDef =
      SortedIndexDefinition.named("by-value")
          .by(s -> s, Function.identity());

  final AndersoniCatalogItem<String> item =
      AndersoniCatalogItem.of("hello", Map.of());

  final Map<Object, List<AndersoniCatalogItem<String>>> hashIndex =
      new HashMap<>();
  final TreeMap<Comparable<?>, List<AndersoniCatalogItem<String>>>
      sortedIndex = new TreeMap<>();
  final TreeMap<String, List<AndersoniCatalogItem<String>>>
      reversedKeyIndex = new TreeMap<>();

  indexDef.accumulate(item, hashIndex, sortedIndex, reversedKeyIndex,
      true);

  assertEquals(1, hashIndex.size());
  assertEquals(1, sortedIndex.size());
  assertEquals(1, reversedKeyIndex.size());
  assertSame(hashIndex.get("hello"), sortedIndex.get("hello"));
  assertTrue(reversedKeyIndex.containsKey("olleh"));
}

@Test
void whenAccumulating_givenNonStringKey_shouldSkipReversedMap() {
  final SortedIndexDefinition<Integer> indexDef =
      SortedIndexDefinition.named("by-num")
          .by(i -> i, Function.identity());

  final AndersoniCatalogItem<Integer> item =
      AndersoniCatalogItem.of(42, Map.of());

  final Map<Object, List<AndersoniCatalogItem<Integer>>> hashIndex =
      new HashMap<>();
  final TreeMap<Comparable<?>, List<AndersoniCatalogItem<Integer>>>
      sortedIndex = new TreeMap<>();
  final TreeMap<String, List<AndersoniCatalogItem<Integer>>>
      reversedKeyIndex = new TreeMap<>();

  indexDef.accumulate(item, hashIndex, sortedIndex, reversedKeyIndex,
      false);

  assertEquals(1, hashIndex.size());
  assertEquals(1, sortedIndex.size());
  assertTrue(reversedKeyIndex.isEmpty());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=SortedIndexDefinitionTest -DfailIfNoTests=false`
Expected: Compilation error — `accumulate` does not exist.

- [ ] **Step 3: Add accumulate() to SortedIndexDefinition**

```java
/**
 * Accumulates a single catalog item into the hash, sorted, and
 * reversed-key index maps.
 *
 * <p>The same list bucket is shared across all three maps for the
 * same key to minimize memory usage.
 *
 * @param item             the catalog item to index, never null
 * @param hashIndex        the mutable hash index map, never null
 * @param sortedIndex      the mutable sorted index map, never null
 * @param reversedKeyIndex the mutable reversed-key index map,
 *                         never null
 * @param isStringKeys     whether keys are Strings (enables reversed
 *                         key index)
 */
@SuppressWarnings("unchecked")
void accumulate(final AndersoniCatalogItem<T> item,
    final Map<Object, List<AndersoniCatalogItem<T>>> hashIndex,
    final NavigableMap<Comparable<?>,
        List<AndersoniCatalogItem<T>>> sortedIndex,
    final NavigableMap<String,
        List<AndersoniCatalogItem<T>>> reversedKeyIndex,
    final boolean isStringKeys) {

  final Object key = keyExtractor.apply(item.item());

  // Get or create shared bucket from hashIndex.
  List<AndersoniCatalogItem<T>> bucket = hashIndex.get(key);
  if (bucket == null) {
    bucket = new ArrayList<>();
    hashIndex.put(key, bucket);
  }
  bucket.add(item);

  if (key != null) {
    // Add to sorted index (share same bucket).
    if (!sortedIndex.containsKey((Comparable<?>) key)) {
      sortedIndex.put((Comparable<?>) key, bucket);
    }

    // Add reversed key for String keys.
    if (isStringKeys) {
      final String reversed = new StringBuilder((String) key)
          .reverse().toString();
      if (!reversedKeyIndex.containsKey(reversed)) {
        reversedKeyIndex.put(reversed, bucket);
      }
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=SortedIndexDefinitionTest`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```
feat: add accumulate() to SortedIndexDefinition
```

---

### Task 5: accumulate() on GraphIndexDefinition

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/GraphIndexDefinition.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/GraphIndexDefinitionTest.java`

The graph index accumulator is the most complex. It evaluates hotpaths and traversals on a single item, generates composite keys, and adds the item to each key's bucket.

- [ ] **Step 1: Write the test**

Add to `GraphIndexDefinitionTest.java`. Read the existing test file to understand the test domain model (Pub, Event, Country, etc.) and reuse it. The test should verify that accumulating a single item produces the expected composite keys.

```java
@Test
void whenAccumulating_givenSingleItem_shouldGenerateCompositeKeys() {
  final GraphIndexDefinition<Pub> index = buildIndex();
  final Pub pub = new Pub("p1", "deportes/futbol",
      List.of(new Event(new Country("AR"))));

  final AndersoniCatalogItem<Pub> item =
      AndersoniCatalogItem.of(pub, Map.of());

  final Map<Object, Set<AndersoniCatalogItem<Pub>>> accumulator =
      new HashMap<>();
  index.accumulate(item, accumulator);

  // Should have keys for: (AR), (AR, deportes), (AR, deportes/futbol)
  assertEquals(3, accumulator.size());
  assertTrue(accumulator.containsKey(CompositeKey.of("AR")));
  assertTrue(accumulator.containsKey(
      CompositeKey.of("AR", "deportes")));
  assertTrue(accumulator.containsKey(
      CompositeKey.of("AR", "deportes/futbol")));
}
```

Note: Read the existing `buildIndex()` helper method in the test file to understand the graph index configuration. The accumulator for graph indexes uses `Map<Object, Set<AndersoniCatalogItem<T>>>` (with IdentityHashMap-backed sets for dedup) — matching the current `buildIndexFromItems` logic. After the loop completes, Catalog converts this to `Map<Object, List<AndersoniCatalogItem<T>>>`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=GraphIndexDefinitionTest -DfailIfNoTests=false`
Expected: Compilation error — `accumulate` does not exist.

- [ ] **Step 3: Add accumulate() to GraphIndexDefinition**

```java
/**
 * Accumulates a single catalog item into the given graph index
 * accumulator by evaluating all hotpaths and traversals.
 *
 * @param item        the catalog item to index, never null
 * @param accumulator the mutable accumulator map (CompositeKey to
 *                    identity-based Set), never null
 *
 * @throws IndexKeyLimitExceededException if the item generates more
 *         keys than the configured maxKeysPerItem
 */
void accumulate(final AndersoniCatalogItem<T> item,
    final Map<Object, Set<AndersoniCatalogItem<T>>> accumulator) {
  final T domainItem = item.item();
  final Set<CompositeKey> allKeys = new LinkedHashSet<>();
  for (final Hotpath hotpath : hotpaths) {
    final List<Set<?>> traversalValues = new ArrayList<>();
    boolean hasNullTraversal = false;
    for (final String fieldName : hotpath.fieldNames()) {
      final Traversal<T> traversal = traversals.get(fieldName);
      final Set<?> values = traversal.evaluate(domainItem);
      if (values.isEmpty()) {
        hasNullTraversal = true;
        break;
      }
      traversalValues.add(values);
    }
    if (hasNullTraversal && traversalValues.isEmpty()) {
      continue;
    }
    for (int prefixLen = 1; prefixLen <= traversalValues.size();
        prefixLen++) {
      final List<Set<?>> prefixValues =
          traversalValues.subList(0, prefixLen);
      generateCartesianKeys(prefixValues, allKeys);
    }
  }
  if (allKeys.size() > maxKeysPerItem) {
    throw new IndexKeyLimitExceededException(name,
        domainItem.toString(), allKeys.size(), maxKeysPerItem);
  }
  for (final CompositeKey key : allKeys) {
    accumulator.computeIfAbsent(key,
        k -> Collections.newSetFromMap(new IdentityHashMap<>()))
        .add(item);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=GraphIndexDefinitionTest`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```
feat: add accumulate() to GraphIndexDefinition
```

---

### Task 6: Catalog — hooks in BuildStep + constructor

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java`

- [ ] **Step 1: Add hook storage to Catalog and BuildStep**

In `Catalog.java`, add a new record to hold a hook with its priority:

```java
/**
 * A hook with its priority for ordering.
 *
 * @param <T> the domain object type
 */
record PrioritizedHook<T>(SnapshotBuildHook<T> hook, int priority)
    implements Comparable<PrioritizedHook<T>> {

  @Override
  public int compareTo(final PrioritizedHook<T> other) {
    return Integer.compare(priority, other.priority);
  }
}
```

Add field to Catalog:
```java
/** The build hooks sorted by priority, never null. */
private final List<PrioritizedHook<T>> hooks;
```

Update Catalog constructor to accept and store `List<PrioritizedHook<T>> hooks`.

In `BuildStep`, add:
```java
/** The accumulated build hooks. */
private final List<PrioritizedHook<T>> hooks = new ArrayList<>();
```

Add two methods:
```java
public BuildStep<T> hook(final SnapshotBuildHook<T> hook,
    final int priority) {
  Objects.requireNonNull(hook, "hook must not be null");
  hooks.add(new PrioritizedHook<>(hook, priority));
  return this;
}

public BuildStep<T> hook(final SnapshotBuildHook<T> hook) {
  return hook(hook, 100);
}
```

Update `build()` to sort hooks and pass to constructor:
```java
final List<PrioritizedHook<T>> sortedHooks = new ArrayList<>(hooks);
Collections.sort(sortedHooks);
```

- [ ] **Step 2: Run all existing tests to verify no regression**

Run: `mvn test -pl andersoni-core -q`
Expected: All tests PASS. No behavior change yet.

- [ ] **Step 3: Commit**

```
feat: add hook registration to Catalog BuildStep
```

---

### Task 7: Refactor buildAndSwapSnapshot to single loop

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/SingleLoopBuildTest.java`

This is the core task — rewrite `buildAndSwapSnapshot` from multiple loops to a single loop.

- [ ] **Step 1: Write the integration test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SingleLoopBuildTest {

  record Sport(String name) {}
  record Venue(String name) {}
  record Event(String id, Sport sport, Venue venue) {}
  record EventSummary(String id) {}

  @Test
  void whenBuilding_givenHooksRegistered_shouldExecuteInPriorityOrder() {
    final List<String> executionOrder = new ArrayList<>();

    final SnapshotBuildHook<Event> hookA = item -> {
      executionOrder.add("A");
      return item;
    };
    final SnapshotBuildHook<Event> hookB = item -> {
      executionOrder.add("B");
      return item;
    };

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(new Event("1", new Sport("Football"),
            new Venue("Maracana"))))
        .index("by-venue").by(Event::venue, Venue::name)
        .hook(hookB, 20)
        .hook(hookA, 10)
        .build();

    catalog.bootstrap();

    assertEquals(List.of("A", "B"), executionOrder);
  }

  @Test
  void whenBuilding_givenHooksAndViews_shouldIndexAndComputeViews() {
    final AtomicInteger hookCallCount = new AtomicInteger(0);

    final SnapshotBuildHook<Event> countingHook = item -> {
      hookCallCount.incrementAndGet();
      return item;
    };

    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));
    final Event e2 = new Event("2", new Sport("Rugby"),
        new Venue("Wembley"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1, e2))
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class, e -> new EventSummary(e.id()))
        .hook(countingHook, 10)
        .build();

    catalog.bootstrap();

    // Hooks called once per item
    assertEquals(2, hookCallCount.get());

    // Index works
    final List<Event> maracana = catalog.search("by-venue", "Maracana");
    assertEquals(1, maracana.size());

    // Views work
    final List<EventSummary> summaries = catalog.search(
        "by-venue", "Maracana", EventSummary.class);
    assertEquals(1, summaries.size());
    assertEquals(new EventSummary("1"), summaries.get(0));
  }

  @Test
  void whenBuilding_givenNoHooks_shouldWorkAsUsual() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    catalog.bootstrap();

    final List<Event> result = catalog.search("by-venue", "Maracana");
    assertEquals(1, result.size());
    assertEquals(e1, result.get(0));
  }

  @Test
  void whenBuilding_givenDefaultPriority_shouldUse100() {
    final List<String> order = new ArrayList<>();

    final SnapshotBuildHook<Event> explicit = item -> {
      order.add("explicit-50");
      return item;
    };
    final SnapshotBuildHook<Event> defaultP = item -> {
      order.add("default-100");
      return item;
    };

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(new Event("1", new Sport("F"),
            new Venue("V"))))
        .index("by-venue").by(Event::venue, Venue::name)
        .hook(defaultP)
        .hook(explicit, 50)
        .build();

    catalog.bootstrap();

    assertEquals(List.of("explicit-50", "default-100"), order);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=SingleLoopBuildTest -DfailIfNoTests=false`
Expected: Test fails because `buildAndSwapSnapshot` doesn't execute hooks yet.

- [ ] **Step 3: Rewrite buildAndSwapSnapshot**

Replace the current `buildAndSwapSnapshot` in `Catalog.java`:

```java
private void buildAndSwapSnapshot(final List<T> data) {
  final List<AndersoniCatalogItem<T>> wrappedItems =
      new ArrayList<>(data.size());

  // Initialize mutable index accumulators.
  final Map<String, Map<Object, List<AndersoniCatalogItem<T>>>> indices =
      new HashMap<>();
  for (final IndexDefinition<T> indexDef : indexDefinitions) {
    indices.put(indexDef.name(), new HashMap<>());
  }
  for (final MultiKeyIndexDefinition<T> multiDef
      : multiKeyIndexDefinitions) {
    indices.put(multiDef.name(), new HashMap<>());
  }

  final Map<String, Map<Object,
      Set<AndersoniCatalogItem<T>>>> graphAccumulators = new HashMap<>();
  for (final GraphIndexDefinition<T> graphDef
      : graphIndexDefinitions) {
    graphAccumulators.put(graphDef.name(), new HashMap<>());
  }

  final Map<String, Map<Object,
      List<AndersoniCatalogItem<T>>>> sortedHashIndices = new HashMap<>();
  final Map<String, NavigableMap<Comparable<?>,
      List<AndersoniCatalogItem<T>>>> sortedIndices = new HashMap<>();
  final Map<String, NavigableMap<String,
      List<AndersoniCatalogItem<T>>>> reversedKeyIndices = new HashMap<>();
  final Map<String, Boolean> sortedStringKeyFlags = new HashMap<>();

  for (final SortedIndexDefinition<T> sortedDef
      : sortedIndexDefinitions) {
    sortedHashIndices.put(sortedDef.name(), new HashMap<>());
    sortedIndices.put(sortedDef.name(), new TreeMap<>());
    reversedKeyIndices.put(sortedDef.name(), new TreeMap<>());
    sortedStringKeyFlags.put(sortedDef.name(), false);
  }

  // Single loop over data.
  for (final T datum : data) {

    // 1. Compute views and create wrapper.
    final Map<Class<?>, Object> views = new HashMap<>();
    for (final ViewDefinition<T, ?> viewDef : viewDefinitions) {
      views.put(viewDef.viewType(), viewDef.mapper().apply(datum));
    }
    final AndersoniCatalogItem<T> wrappedItem =
        AndersoniCatalogItem.of(datum, views);
    wrappedItems.add(wrappedItem);

    // 2. Index across all index types.
    for (final IndexDefinition<T> indexDef : indexDefinitions) {
      indexDef.accumulate(wrappedItem, indices.get(indexDef.name()));
    }
    for (final MultiKeyIndexDefinition<T> multiDef
        : multiKeyIndexDefinitions) {
      multiDef.accumulate(wrappedItem, indices.get(multiDef.name()));
    }
    for (final GraphIndexDefinition<T> graphDef
        : graphIndexDefinitions) {
      graphDef.accumulate(wrappedItem,
          graphAccumulators.get(graphDef.name()));
    }
    for (final SortedIndexDefinition<T> sortedDef
        : sortedIndexDefinitions) {
      // Detect string keys on first non-null key.
      final String sName = sortedDef.name();
      if (!sortedStringKeyFlags.get(sName)) {
        final Object key = sortedDef.extractKey(datum);
        if (key instanceof String) {
          sortedStringKeyFlags.put(sName, true);
        }
      }
      sortedDef.accumulate(wrappedItem,
          sortedHashIndices.get(sName),
          sortedIndices.get(sName),
          reversedKeyIndices.get(sName),
          sortedStringKeyFlags.get(sName));
    }

    // 3. Execute hooks in priority order.
    T processed = datum;
    for (final PrioritizedHook<T> hook : hooks) {
      processed = hook.hook().process(processed);
    }
  }

  // Finalize: make all maps unmodifiable.
  final Map<String, Map<Object, List<AndersoniCatalogItem<T>>>>
      finalIndices = new HashMap<>();

  // Regular and multi-key indices.
  for (final Map.Entry<String, Map<Object,
      List<AndersoniCatalogItem<T>>>> entry : indices.entrySet()) {
    finalIndices.put(entry.getKey(), makeUnmodifiable(entry.getValue()));
  }

  // Graph indices: convert Set -> List.
  for (final Map.Entry<String, Map<Object,
      Set<AndersoniCatalogItem<T>>>> entry
      : graphAccumulators.entrySet()) {
    final Map<Object, List<AndersoniCatalogItem<T>>> converted =
        new HashMap<>();
    for (final Map.Entry<Object, Set<AndersoniCatalogItem<T>>> e
        : entry.getValue().entrySet()) {
      converted.put(e.getKey(),
          Collections.unmodifiableList(new ArrayList<>(e.getValue())));
    }
    finalIndices.put(entry.getKey(),
        Collections.unmodifiableMap(converted));
  }

  // Sorted indices: add hash part to finalIndices.
  final Map<String, NavigableMap<Comparable<?>,
      List<AndersoniCatalogItem<T>>>> finalSortedIndices =
      new HashMap<>();
  final Map<String, NavigableMap<String,
      List<AndersoniCatalogItem<T>>>> finalReversedKeyIndices =
      new HashMap<>();

  for (final SortedIndexDefinition<T> sortedDef
      : sortedIndexDefinitions) {
    final String sName = sortedDef.name();
    finalIndices.put(sName,
        makeUnmodifiable(sortedHashIndices.get(sName)));
    finalSortedIndices.put(sName,
        makeSortedUnmodifiable(sortedIndices.get(sName)));
    if (sortedStringKeyFlags.get(sName)) {
      finalReversedKeyIndices.put(sName,
          makeSortedUnmodifiable(reversedKeyIndices.get(sName)));
    }
  }

  final long version = versionCounter.incrementAndGet();
  final String hash = computeHash(data);

  final Snapshot<T> snapshot = Snapshot.ofWithItems(wrappedItems,
      finalIndices, finalSortedIndices, finalReversedKeyIndices,
      version, hash);
  current.set(snapshot);
}
```

Add helper methods to Catalog:

```java
private static <T> Map<Object, List<T>> makeUnmodifiable(
    final Map<Object, List<T>> map) {
  final Map<Object, List<T>> result = new HashMap<>();
  for (final Map.Entry<Object, List<T>> entry : map.entrySet()) {
    result.put(entry.getKey(),
        Collections.unmodifiableList(entry.getValue()));
  }
  return Collections.unmodifiableMap(result);
}

@SuppressWarnings("unchecked")
private static <K, V> NavigableMap<K, V> makeSortedUnmodifiable(
    final NavigableMap<K, V> map) {
  return Collections.unmodifiableNavigableMap(map);
}
```

Also add a package-private `extractKey` method to `SortedIndexDefinition`:
```java
Object extractKey(final T item) {
  return keyExtractor.apply(item);
}
```

**Note:** The string key detection for sorted indexes needs to happen before the first `accumulate` call for that index. A simpler approach: detect it in the first accumulate call when the first non-null key is encountered, and flip the flag. However, since the flag is external to the accumulate method, pass it as a parameter. The first item with a non-null String key sets the flag to true for subsequent items.

- [ ] **Step 4: Run all tests**

Run: `mvn test -pl andersoni-core -q`
Expected: ALL tests PASS — including SingleLoopBuildTest and all existing tests.

- [ ] **Step 5: Commit**

```
feat: refactor buildAndSwapSnapshot to single loop with hooks
```

---

### Task 8: Remove old buildIndex/buildIndexFromItems methods

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/IndexDefinition.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/MultiKeyIndexDefinition.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/GraphIndexDefinition.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/SortedIndexDefinition.java`

- [ ] **Step 1: Remove `buildIndexFromItems` from all four classes**

Remove the `buildIndexFromItems(List<AndersoniCatalogItem<T>>)` method from each class. These are no longer called by `buildAndSwapSnapshot`.

Keep the public `buildIndex(List<T>)` method — it's part of the public API and may be used by tests or external code.

- [ ] **Step 2: Run all tests**

Run: `mvn test -pl andersoni-core -q`
Expected: All tests PASS. If any test used `buildIndexFromItems` directly, update it to use `accumulate` or `buildIndex`.

- [ ] **Step 3: Run full project build**

Run: `mvn clean verify`
Expected: BUILD SUCCESS across all modules.

- [ ] **Step 4: Commit**

```
refactor: remove unused buildIndexFromItems methods
```

---

### Task 9: Full regression + integration

- [ ] **Step 1: Run full project build**

Run: `mvn clean verify`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Verify all view tests still pass**

Run: `mvn test -pl andersoni-core -Dtest="CatalogViewTest,SnapshotViewTest,QueryStepViewTest,CompoundQueryViewTest,AndersoniViewTest"`
Expected: All view tests PASS.

- [ ] **Step 3: Verify all index tests still pass**

Run: `mvn test -pl andersoni-core -Dtest="IndexDefinitionTest,SortedIndexDefinitionTest,MultiKeyIndexDefinitionTest,GraphIndexDefinitionTest,CatalogGraphIndexTest,CatalogMultiKeyTest"`
Expected: All index tests PASS.

- [ ] **Step 4: Commit if any fixes were needed**

```
fix: address regression issues from single-loop refactor
```
