# Query DSL Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add sorted indexes and a fluent Query DSL to Andersoni supporting equalTo, between, range, and text pattern queries.

**Architecture:** Two index types (HashMap-only via `index()`, dual HashMap+TreeMap via `indexSorted()`). A single `QueryStep<T>` exposes all operations with runtime checks for index capability. Reversed TreeMap for efficient `endsWith`. Zero breaking changes to existing API.

**Tech Stack:** Java 21, JUnit 5, EasyMock. No new dependencies.

---

## Base paths

- Source: `andersoni-core/src/main/java/org/waabox/andersoni/`
- Tests: `andersoni-core/src/test/java/org/waabox/andersoni/`

---

### Task 1: Create exception classes

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/IndexNotFoundException.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/UnsupportedIndexOperationException.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/IndexNotFoundExceptionTest.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/UnsupportedIndexOperationExceptionTest.java`

**Step 1: Write failing tests for IndexNotFoundException**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

class IndexNotFoundExceptionTest {

  @Test
  void whenCreating_givenIndexAndCatalog_shouldFormatMessage() {
    final IndexNotFoundException ex =
        new IndexNotFoundException("by-venue", "events");
    assertEquals(
        "Index 'by-venue' not found in catalog 'events'",
        ex.getMessage());
  }

  @Test
  void whenCreating_shouldBeRuntimeException() {
    final IndexNotFoundException ex =
        new IndexNotFoundException("idx", "cat");
    assertInstanceOf(RuntimeException.class, ex);
  }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=IndexNotFoundExceptionTest -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: FAIL — class does not exist

**Step 3: Implement IndexNotFoundException**

```java
package org.waabox.andersoni;

/**
 * Thrown when a query references an index name that does not exist
 * in the catalog.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class IndexNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a new exception for a missing index.
   *
   * @param indexName   the index name that was not found, never null
   * @param catalogName the catalog that was searched, never null
   */
  public IndexNotFoundException(final String indexName,
      final String catalogName) {
    super("Index '" + indexName + "' not found in catalog '"
        + catalogName + "'");
  }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=IndexNotFoundExceptionTest -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: PASS

**Step 5: Write failing tests for UnsupportedIndexOperationException**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

class UnsupportedIndexOperationExceptionTest {

  @Test
  void whenCreating_givenMessage_shouldRetainIt() {
    final UnsupportedIndexOperationException ex =
        new UnsupportedIndexOperationException(
            "Index 'by-venue' does not support range queries."
            + " Use indexSorted() to enable them");
    assertEquals(
        "Index 'by-venue' does not support range queries."
        + " Use indexSorted() to enable them",
        ex.getMessage());
  }

  @Test
  void whenCreating_shouldBeRuntimeException() {
    final UnsupportedIndexOperationException ex =
        new UnsupportedIndexOperationException("msg");
    assertInstanceOf(RuntimeException.class, ex);
  }
}
```

**Step 6: Implement UnsupportedIndexOperationException**

```java
package org.waabox.andersoni;

/**
 * Thrown when a query operation is not supported by the target index type.
 *
 * <p>For example, calling {@code between()} on a non-sorted index, or
 * calling {@code startsWith()} on an index whose key type is not String.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class UnsupportedIndexOperationException
    extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a new exception with the given message.
   *
   * @param message the detail message, never null
   */
  public UnsupportedIndexOperationException(final String message) {
    super(message);
  }
}
```

**Step 7: Run both tests**

Run: `mvn test -pl andersoni-core -Dtest="IndexNotFoundExceptionTest,UnsupportedIndexOperationExceptionTest" -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: PASS

**Step 8: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/IndexNotFoundException.java \
       andersoni-core/src/main/java/org/waabox/andersoni/UnsupportedIndexOperationException.java \
       andersoni-core/src/test/java/org/waabox/andersoni/IndexNotFoundExceptionTest.java \
       andersoni-core/src/test/java/org/waabox/andersoni/UnsupportedIndexOperationExceptionTest.java
git commit -m "Add IndexNotFoundException and UnsupportedIndexOperationException"
```

---

### Task 2: Create SortedIndexDefinition

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/SortedIndexDefinition.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/SortedIndexDefinitionTest.java`

**Step 1: Write failing tests**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

class SortedIndexDefinitionTest {

  record EventDate(LocalDate value) {}

  record Venue(String name) {}

  record Event(String id, EventDate eventDate, Venue venue) {}

  @Test
  void whenBuildingIndex_givenData_shouldBuildHashAndTreeMaps() {
    final LocalDate d1 = LocalDate.of(2024, 1, 10);
    final LocalDate d2 = LocalDate.of(2024, 3, 20);

    final Event e1 = new Event("1", new EventDate(d1), new Venue("A"));
    final Event e2 = new Event("2", new EventDate(d2), new Venue("B"));
    final Event e3 = new Event("3", new EventDate(d1), new Venue("C"));

    final SortedIndexDefinition<Event> index =
        SortedIndexDefinition.<Event>named("by-date")
            .by(Event::eventDate, EventDate::value);

    final SortedIndexDefinition.SortedIndexResult<Event> result =
        index.buildIndex(List.of(e1, e2, e3));

    // HashMap
    final Map<Object, List<Event>> hash = result.hashIndex();
    assertEquals(2, hash.size());
    assertEquals(2, hash.get(d1).size());
    assertEquals(1, hash.get(d2).size());

    // TreeMap
    final TreeMap<Comparable<?>, List<Event>> sorted = result.sortedIndex();
    assertEquals(2, sorted.size());
    assertEquals(d1, sorted.firstKey());
    assertEquals(d2, sorted.lastKey());

    // Same List instances shared
    assertTrue(hash.get(d1) == sorted.get(d1));
  }

  @Test
  void whenBuildingIndex_givenStringKeys_shouldBuildReversedTreeMap() {
    final Event e1 = new Event("1", new EventDate(LocalDate.now()),
        new Venue("Maracana"));
    final Event e2 = new Event("2", new EventDate(LocalDate.now()),
        new Venue("Wembley"));

    final SortedIndexDefinition<Event> index =
        SortedIndexDefinition.<Event>named("by-venue")
            .by(Event::venue, Venue::name);

    final SortedIndexDefinition.SortedIndexResult<Event> result =
        index.buildIndex(List.of(e1, e2));

    assertNotNull(result.reversedKeyIndex());
    assertTrue(result.reversedKeyIndex().containsKey(
        new StringBuilder("Maracana").reverse().toString()));
    assertTrue(result.reversedKeyIndex().containsKey(
        new StringBuilder("Wembley").reverse().toString()));
    assertTrue(result.hasStringKeys());
  }

  @Test
  void whenBuildingIndex_givenNonStringKeys_shouldNotBuildReversedTreeMap() {
    final Event e1 = new Event("1",
        new EventDate(LocalDate.of(2024, 1, 1)), new Venue("A"));

    final SortedIndexDefinition<Event> index =
        SortedIndexDefinition.<Event>named("by-date")
            .by(Event::eventDate, EventDate::value);

    final SortedIndexDefinition.SortedIndexResult<Event> result =
        index.buildIndex(List.of(e1));

    assertNull(result.reversedKeyIndex());
    assertFalse(result.hasStringKeys());
  }

  @Test
  void whenBuildingIndex_givenEmptyData_shouldReturnEmptyMaps() {
    final SortedIndexDefinition<Event> index =
        SortedIndexDefinition.<Event>named("by-date")
            .by(Event::eventDate, EventDate::value);

    final SortedIndexDefinition.SortedIndexResult<Event> result =
        index.buildIndex(List.of());

    assertTrue(result.hashIndex().isEmpty());
    assertTrue(result.sortedIndex().isEmpty());
  }

  @Test
  void whenBuildingIndex_givenNullIntermediateValue_shouldIndexUnderNull() {
    final Event e1 = new Event("1",
        new EventDate(LocalDate.of(2024, 1, 1)), new Venue("A"));
    final Event e2 = new Event("2", null, new Venue("B"));

    final SortedIndexDefinition<Event> index =
        SortedIndexDefinition.<Event>named("by-date")
            .by(Event::eventDate, EventDate::value);

    final SortedIndexDefinition.SortedIndexResult<Event> result =
        index.buildIndex(List.of(e1, e2));

    // null key goes in hashIndex but NOT in sortedIndex (TreeMap can't hold null)
    assertEquals(2, result.hashIndex().size());
    assertNotNull(result.hashIndex().get(null));
    assertEquals(1, result.sortedIndex().size());
  }

  @Test
  void whenBuildingIndex_givenResults_shouldBeUnmodifiable() {
    final Event e1 = new Event("1",
        new EventDate(LocalDate.of(2024, 1, 1)), new Venue("A"));

    final SortedIndexDefinition<Event> index =
        SortedIndexDefinition.<Event>named("by-date")
            .by(Event::eventDate, EventDate::value);

    final SortedIndexDefinition.SortedIndexResult<Event> result =
        index.buildIndex(List.of(e1));

    assertThrows(UnsupportedOperationException.class,
        () -> result.hashIndex().put("x", List.of()));
    assertThrows(UnsupportedOperationException.class,
        () -> result.sortedIndex().put(LocalDate.now(), List.of()));
  }

  @Test
  void whenGettingName_shouldReturnConfiguredName() {
    final SortedIndexDefinition<Event> index =
        SortedIndexDefinition.<Event>named("by-date")
            .by(Event::eventDate, EventDate::value);
    assertEquals("by-date", index.name());
  }

  @Test
  void whenCreating_givenNullName_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        SortedIndexDefinition.<Event>named(null));
  }

  @Test
  void whenCreating_givenEmptyName_shouldThrowIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () ->
        SortedIndexDefinition.<Event>named(""));
  }

  @Test
  void whenCreating_givenNullFirstFunction_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        SortedIndexDefinition.<Event>named("idx")
            .by(null, EventDate::value));
  }

  @Test
  void whenCreating_givenNullSecondFunction_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        SortedIndexDefinition.<Event>named("idx")
            .by(Event::eventDate, null));
  }

  @Test
  void whenBuildingIndex_givenNullData_shouldThrowNpe() {
    final SortedIndexDefinition<Event> index =
        SortedIndexDefinition.<Event>named("by-date")
            .by(Event::eventDate, EventDate::value);
    assertThrows(NullPointerException.class, () ->
        index.buildIndex(null));
  }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=SortedIndexDefinitionTest -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: FAIL — class does not exist

**Step 3: Implement SortedIndexDefinition**

```java
package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Defines a sorted index that extracts a {@link Comparable} key from domain
 * objects and builds both a {@link HashMap} (for O(1) equality lookups) and a
 * {@link TreeMap} (for O(log n + k) range queries).
 *
 * <p>When the key type is {@link String}, an additional reversed-key
 * {@link TreeMap} is built to support efficient {@code endsWith} queries.
 *
 * <p>Instances are created using the fluent builder starting with
 * {@link #named(String)}.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @param <T> the type of domain objects being indexed
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class SortedIndexDefinition<T> {

  /** The name of this index. */
  private final String name;

  /** The composed function that extracts the index key from an item. */
  private final Function<T, ?> keyExtractor;

  /** Whether the key type is String (determined at first non-null key). */
  private final boolean expectStringKeys;

  /**
   * Creates a new sorted index definition.
   *
   * @param name            the index name, never null
   * @param keyExtractor    the composed key extraction function, never null
   * @param expectStringKeys true if the key type is String
   */
  private SortedIndexDefinition(final String name,
      final Function<T, ?> keyExtractor,
      final boolean expectStringKeys) {
    this.name = name;
    this.keyExtractor = keyExtractor;
    this.expectStringKeys = expectStringKeys;
  }

  /**
   * Starts building a new sorted index definition with the given name.
   *
   * @param name the name of the index, never null or empty
   * @param <T>  the type of domain objects being indexed
   *
   * @return the next step of the builder, never null
   *
   * @throws NullPointerException     if name is null
   * @throws IllegalArgumentException if name is empty
   */
  public static <T> KeyStep<T> named(final String name) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isEmpty()) {
      throw new IllegalArgumentException("name must not be empty");
    }
    return new KeyStep<>(name);
  }

  /**
   * Builds a sorted index from the given data.
   *
   * <p>Produces a {@link SortedIndexResult} containing:
   * <ul>
   *   <li>A {@link HashMap} for O(1) equality lookups</li>
   *   <li>A {@link TreeMap} for range queries</li>
   *   <li>A reversed-key {@link TreeMap} if the key type is String</li>
   * </ul>
   *
   * <p>All three structures share the same {@link List} instances.
   *
   * @param data the list of items to index, never null
   *
   * @return the built index structures, never null
   *
   * @throws NullPointerException if data is null
   */
  @SuppressWarnings("unchecked")
  public SortedIndexResult<T> buildIndex(final List<T> data) {
    Objects.requireNonNull(data, "data must not be null");

    if (data.isEmpty()) {
      return new SortedIndexResult<>(
          Collections.emptyMap(),
          new TreeMap<>(),
          expectStringKeys ? new TreeMap<>() : null,
          expectStringKeys);
    }

    final Map<Object, List<T>> hash = new HashMap<>();
    final TreeMap<Comparable<?>, List<T>> sorted = new TreeMap<>();
    final TreeMap<String, List<T>> reversed =
        expectStringKeys ? new TreeMap<>() : null;

    for (final T item : data) {
      final Object key = keyExtractor.apply(item);

      final List<T> bucket = hash
          .computeIfAbsent(key, k -> new ArrayList<>());
      bucket.add(item);

      if (key != null) {
        final Comparable<?> comparableKey = (Comparable<?>) key;
        sorted.computeIfAbsent(comparableKey, k -> bucket);

        if (reversed != null) {
          final String reversedKey = new StringBuilder((String) key)
              .reverse().toString();
          reversed.computeIfAbsent(reversedKey, k -> bucket);
        }
      }
    }

    // Make lists unmodifiable
    final Map<Object, List<T>> unmodHash = new HashMap<>();
    for (final Map.Entry<Object, List<T>> entry : hash.entrySet()) {
      final List<T> unmodList =
          Collections.unmodifiableList(entry.getValue());
      unmodHash.put(entry.getKey(), unmodList);

      // Update sorted and reversed to point to the same unmod list
      if (entry.getKey() != null) {
        sorted.put((Comparable<?>) entry.getKey(), unmodList);
        if (reversed != null) {
          final String revKey = new StringBuilder((String) entry.getKey())
              .reverse().toString();
          reversed.put(revKey, unmodList);
        }
      }
    }

    return new SortedIndexResult<>(
        Collections.unmodifiableMap(unmodHash),
        sorted,
        reversed,
        expectStringKeys);
  }

  /**
   * Returns the name of this index.
   *
   * @return the index name, never null
   */
  public String name() {
    return name;
  }

  /**
   * The result of building a sorted index, containing all three
   * index structures.
   *
   * @param <T> the type of domain objects
   */
  public static final class SortedIndexResult<T> {

    private final Map<Object, List<T>> hashIndex;
    private final TreeMap<Comparable<?>, List<T>> sortedIndex;
    private final TreeMap<String, List<T>> reversedKeyIndex;
    private final boolean stringKeys;

    SortedIndexResult(
        final Map<Object, List<T>> hashIndex,
        final TreeMap<Comparable<?>, List<T>> sortedIndex,
        final TreeMap<String, List<T>> reversedKeyIndex,
        final boolean stringKeys) {
      this.hashIndex = hashIndex;
      this.sortedIndex = sortedIndex;
      this.reversedKeyIndex = reversedKeyIndex;
      this.stringKeys = stringKeys;
    }

    /** Returns the HashMap for O(1) equality lookups. */
    public Map<Object, List<T>> hashIndex() {
      return hashIndex;
    }

    /** Returns the TreeMap for range queries. */
    public TreeMap<Comparable<?>, List<T>> sortedIndex() {
      return sortedIndex;
    }

    /** Returns the reversed-key TreeMap, or null if keys are not String. */
    public TreeMap<String, List<T>> reversedKeyIndex() {
      return reversedKeyIndex;
    }

    /** Returns true if the index keys are String. */
    public boolean hasStringKeys() {
      return stringKeys;
    }
  }

  /**
   * Intermediate builder step that collects the key extraction functions.
   *
   * @param <T> the type of domain objects being indexed
   */
  public static final class KeyStep<T> {

    private final String name;

    private KeyStep(final String name) {
      this.name = name;
    }

    /**
     * Defines the two-step key extraction, requiring the final key to be
     * {@link Comparable}.
     *
     * @param first  extracts intermediate value from domain object
     * @param second extracts the Comparable key from intermediate
     * @param <I>    intermediate type
     * @param <K>    key type, must be Comparable
     *
     * @return a fully configured sorted index definition, never null
     *
     * @throws NullPointerException if first or second is null
     */
    public <I, K extends Comparable<K>> SortedIndexDefinition<T> by(
        final Function<T, I> first, final Function<I, K> second) {

      Objects.requireNonNull(first, "first function must not be null");
      Objects.requireNonNull(second, "second function must not be null");

      // Detect if key type is String by checking second's return type
      // We do this by probing: the actual detection happens at build time
      // based on the first non-null key value. But for the builder, we
      // need to know at definition time. We use a probe approach.
      final boolean isString = isStringFunction(second);

      final Function<T, ?> composed = item -> {
        final I intermediate = first.apply(item);
        if (intermediate == null) {
          return null;
        }
        return second.apply(intermediate);
      };

      return new SortedIndexDefinition<>(name, composed, isString);
    }

    /**
     * Checks if the function returns String by examining its generic type.
     * Falls back to assuming non-String.
     */
    @SuppressWarnings("unchecked")
    private <I, K extends Comparable<K>> boolean isStringFunction(
        final Function<I, K> function) {
      // We can detect String at build time by checking the first
      // non-null result. But for a cleaner approach, we check if K is
      // String via a type probe.
      try {
        // Try to cast — if the function class gives us String, great.
        // This is a best-effort detection. The actual detection at
        // buildIndex time handles the rest.
        return false; // Will be detected at build time from actual keys
      } catch (final Exception e) {
        return false;
      }
    }
  }
}
```

> **Note to implementer:** The `isStringFunction` approach above is not ideal. A better approach: detect String keys at `buildIndex` time from the first non-null key. Update the implementation to detect at build time:
> - In `buildIndex`, after extracting the first non-null key, check `key instanceof String`
> - Set a local `boolean detectedStringKeys` flag
> - Only create the reversed TreeMap if detected

**Step 4: Run tests to verify they pass**

Run: `mvn test -pl andersoni-core -Dtest=SortedIndexDefinitionTest -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: PASS (fix any issues — the String key detection needs runtime detection, not compile-time)

**Step 5: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/SortedIndexDefinition.java \
       andersoni-core/src/test/java/org/waabox/andersoni/SortedIndexDefinitionTest.java
git commit -m "Add SortedIndexDefinition with HashMap, TreeMap, and reversed TreeMap"
```

---

### Task 3: Extend Snapshot with sorted index support

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Snapshot.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/SnapshotTest.java`

**Step 1: Write failing tests for new Snapshot methods**

Add the following tests to `SnapshotTest.java`:

```java
// --- Sorted index tests ---

@Test
void whenSearchBetween_givenSortedIndex_shouldReturnRange() {
  final List<String> data = List.of("a", "b", "c", "d", "e");

  final Map<Object, List<String>> hash = new HashMap<>();
  hash.put(1, List.of("a"));
  hash.put(2, List.of("b"));
  hash.put(3, List.of("c"));
  hash.put(4, List.of("d"));
  hash.put(5, List.of("e"));

  final TreeMap<Comparable<?>, List<String>> sorted = new TreeMap<>();
  sorted.put(1, hash.get(1));
  sorted.put(2, hash.get(2));
  sorted.put(3, hash.get(3));
  sorted.put(4, hash.get(4));
  sorted.put(5, hash.get(5));

  final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
  indices.put("byNum", hash);

  final Map<String, TreeMap<Comparable<?>, List<String>>> sortedIndices =
      new HashMap<>();
  sortedIndices.put("byNum", sorted);

  final Snapshot<String> snapshot = Snapshot.of(data, indices,
      sortedIndices, null, 1L, "hash");

  final List<String> result = snapshot.searchBetween("byNum", 2, 4);
  assertEquals(3, result.size());
  assertTrue(result.contains("b"));
  assertTrue(result.contains("c"));
  assertTrue(result.contains("d"));
}

@Test
void whenSearchGreaterThan_givenSortedIndex_shouldReturnMatches() {
  final List<String> data = List.of("a", "b", "c");

  final TreeMap<Comparable<?>, List<String>> sorted = new TreeMap<>();
  sorted.put(1, List.of("a"));
  sorted.put(2, List.of("b"));
  sorted.put(3, List.of("c"));

  final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
  final Map<String, TreeMap<Comparable<?>, List<String>>> sortedIndices =
      new HashMap<>();
  sortedIndices.put("byNum", sorted);

  final Snapshot<String> snapshot = Snapshot.of(data, indices,
      sortedIndices, null, 1L, "hash");

  final List<String> result = snapshot.searchGreaterThan("byNum", 1);
  assertEquals(2, result.size());
  assertTrue(result.contains("b"));
  assertTrue(result.contains("c"));
}

@Test
void whenSearchLessThan_givenSortedIndex_shouldReturnMatches() {
  final List<String> data = List.of("a", "b", "c");

  final TreeMap<Comparable<?>, List<String>> sorted = new TreeMap<>();
  sorted.put(1, List.of("a"));
  sorted.put(2, List.of("b"));
  sorted.put(3, List.of("c"));

  final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
  final Map<String, TreeMap<Comparable<?>, List<String>>> sortedIndices =
      new HashMap<>();
  sortedIndices.put("byNum", sorted);

  final Snapshot<String> snapshot = Snapshot.of(data, indices,
      sortedIndices, null, 1L, "hash");

  final List<String> result = snapshot.searchLessThan("byNum", 3);
  assertEquals(2, result.size());
  assertTrue(result.contains("a"));
  assertTrue(result.contains("b"));
}

@Test
void whenSearchStartsWith_givenStringKeySortedIndex_shouldReturnMatches() {
  final List<String> data = List.of("x", "y", "z");

  final Map<Object, List<String>> hash = new HashMap<>();
  hash.put("Maracana", List.of("x"));
  hash.put("Madrid", List.of("y"));
  hash.put("Wembley", List.of("z"));

  final TreeMap<Comparable<?>, List<String>> sorted = new TreeMap<>();
  sorted.put("Maracana", hash.get("Maracana"));
  sorted.put("Madrid", hash.get("Madrid"));
  sorted.put("Wembley", hash.get("Wembley"));

  final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
  indices.put("byVenue", hash);

  final Map<String, TreeMap<Comparable<?>, List<String>>> sortedIndices =
      new HashMap<>();
  sortedIndices.put("byVenue", sorted);

  final Map<String, TreeMap<String, List<String>>> reversedIndices =
      new HashMap<>();
  final TreeMap<String, List<String>> reversed = new TreeMap<>();
  reversed.put(new StringBuilder("Maracana").reverse().toString(),
      hash.get("Maracana"));
  reversed.put(new StringBuilder("Madrid").reverse().toString(),
      hash.get("Madrid"));
  reversed.put(new StringBuilder("Wembley").reverse().toString(),
      hash.get("Wembley"));
  reversedIndices.put("byVenue", reversed);

  final Snapshot<String> snapshot = Snapshot.of(data, indices,
      sortedIndices, reversedIndices, 1L, "hash");

  final List<String> result = snapshot.searchStartsWith("byVenue", "Mar");
  assertEquals(2, result.size());
  assertTrue(result.contains("x"));
  assertTrue(result.contains("y"));
}

@Test
void whenSearchEndsWith_givenStringKeySortedIndex_shouldReturnMatches() {
  final List<String> data = List.of("x", "y", "z");

  final Map<Object, List<String>> hash = new HashMap<>();
  hash.put("Maracana", List.of("x"));
  hash.put("Banana", List.of("y"));
  hash.put("Wembley", List.of("z"));

  final TreeMap<Comparable<?>, List<String>> sorted = new TreeMap<>();
  sorted.put("Maracana", hash.get("Maracana"));
  sorted.put("Banana", hash.get("Banana"));
  sorted.put("Wembley", hash.get("Wembley"));

  final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
  indices.put("byVenue", hash);

  final Map<String, TreeMap<Comparable<?>, List<String>>> sortedIndices =
      new HashMap<>();
  sortedIndices.put("byVenue", sorted);

  final Map<String, TreeMap<String, List<String>>> reversedIndices =
      new HashMap<>();
  final TreeMap<String, List<String>> reversed = new TreeMap<>();
  reversed.put(new StringBuilder("Maracana").reverse().toString(),
      hash.get("Maracana"));
  reversed.put(new StringBuilder("Banana").reverse().toString(),
      hash.get("Banana"));
  reversed.put(new StringBuilder("Wembley").reverse().toString(),
      hash.get("Wembley"));
  reversedIndices.put("byVenue", reversed);

  final Snapshot<String> snapshot = Snapshot.of(data, indices,
      sortedIndices, reversedIndices, 1L, "hash");

  final List<String> result = snapshot.searchEndsWith("byVenue", "ana");
  assertEquals(2, result.size());
  assertTrue(result.contains("x")); // Maracana
  assertTrue(result.contains("y")); // Banana
}

@Test
void whenSearchContains_givenStringKeySortedIndex_shouldReturnMatches() {
  final List<String> data = List.of("x", "y", "z");

  final Map<Object, List<String>> hash = new HashMap<>();
  hash.put("Maracana", List.of("x"));
  hash.put("Banana", List.of("y"));
  hash.put("Wembley", List.of("z"));

  final TreeMap<Comparable<?>, List<String>> sorted = new TreeMap<>();
  sorted.put("Maracana", hash.get("Maracana"));
  sorted.put("Banana", hash.get("Banana"));
  sorted.put("Wembley", hash.get("Wembley"));

  final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
  indices.put("byVenue", hash);

  final Map<String, TreeMap<Comparable<?>, List<String>>> sortedIndices =
      new HashMap<>();
  sortedIndices.put("byVenue", sorted);

  final Snapshot<String> snapshot = Snapshot.of(data, indices,
      sortedIndices, null, 1L, "hash");

  final List<String> result = snapshot.searchContains("byVenue", "ana");
  assertEquals(2, result.size());
  assertTrue(result.contains("x")); // Maracana
  assertTrue(result.contains("y")); // Banana
}

@Test
void whenSearchBetween_givenNonSortedIndex_shouldThrowException() {
  final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
  indices.put("byLen", new HashMap<>());

  final Snapshot<String> snapshot = Snapshot.of(
      List.of("a"), indices, 1L, "hash");

  assertThrows(UnsupportedIndexOperationException.class,
      () -> snapshot.searchBetween("byLen", 1, 5));
}

@Test
void whenSearchBetween_givenNonExistentIndex_shouldThrowException() {
  final Snapshot<String> snapshot = Snapshot.of(
      List.of("a"), new HashMap<>(), 1L, "hash");

  assertThrows(IndexNotFoundException.class,
      () -> snapshot.searchBetween("nope", 1, 5));
}

@Test
void whenSearchStartsWith_givenNonStringKeySortedIndex_shouldThrowException() {
  final TreeMap<Comparable<?>, List<String>> sorted = new TreeMap<>();
  sorted.put(1, List.of("a"));

  final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
  final Map<String, TreeMap<Comparable<?>, List<String>>> sortedIndices =
      new HashMap<>();
  sortedIndices.put("byNum", sorted);

  final Snapshot<String> snapshot = Snapshot.of(
      List.of("a"), indices, sortedIndices, null, 1L, "hash");

  assertThrows(UnsupportedIndexOperationException.class,
      () -> snapshot.searchStartsWith("byNum", "abc"));
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn test -pl andersoni-core -Dtest=SnapshotTest -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: FAIL — new methods and overloaded `of()` do not exist

**Step 3: Implement Snapshot changes**

Add to `Snapshot.java`:
1. New fields: `sortedIndices`, `reversedKeyIndices`, `catalogName`
2. Overloaded `of()` factory that accepts sorted indices
3. All new search methods: `searchBetween`, `searchGreaterThan`, `searchGreaterOrEqual`, `searchLessThan`, `searchLessOrEqual`, `searchStartsWith`, `searchEndsWith`, `searchContains`
4. Private helpers: `requireSortedIndex(indexName)`, `requireReversedKeyIndex(indexName)`, `flattenValues(SortedMap)`, `computePrefixEnd(prefix)`

Key implementation details:
- `searchBetween(name, from, to)`: `sorted.subMap(from, true, to, true)` → flatten values
- `searchGreaterThan(name, key)`: `sorted.tailMap(key, false)` → flatten
- `searchGreaterOrEqual(name, key)`: `sorted.tailMap(key, true)` → flatten
- `searchLessThan(name, key)`: `sorted.headMap(key, false)` → flatten
- `searchLessOrEqual(name, key)`: `sorted.headMap(key, true)` → flatten
- `searchStartsWith(name, prefix)`: `sorted.subMap(prefix, true, prefixEnd, false)` where `prefixEnd` is prefix with last char incremented
- `searchEndsWith(name, suffix)`: reverse the suffix, do `startsWith` on reversed TreeMap
- `searchContains(name, substring)`: iterate all keys in sorted TreeMap, filter by `key.toString().contains(substring)`

The existing `of()` method must remain unchanged. Add a new overloaded `of()`:

```java
public static <T> Snapshot<T> of(
    final List<T> data,
    final Map<String, Map<Object, List<T>>> indices,
    final Map<String, TreeMap<Comparable<?>, List<T>>> sortedIndices,
    final Map<String, TreeMap<String, List<T>>> reversedKeyIndices,
    final long version,
    final String hash)
```

**Step 4: Run tests to verify they pass**

Run: `mvn test -pl andersoni-core -Dtest=SnapshotTest -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: PASS

**Step 5: Run all existing tests to verify no regressions**

Run: `mvn test -pl andersoni-core -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: ALL PASS

**Step 6: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Snapshot.java \
       andersoni-core/src/test/java/org/waabox/andersoni/SnapshotTest.java
git commit -m "Add sorted index support to Snapshot with range and text queries"
```

---

### Task 4: Create QueryStep

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/QueryStep.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/QueryStepTest.java`

**Step 1: Write failing tests**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

class QueryStepTest {

  @Test
  void whenEqualTo_givenHashIndex_shouldReturnMatches() {
    final Map<Object, List<String>> hash = new HashMap<>();
    hash.put("key1", List.of("a", "b"));

    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("idx", hash);

    final Snapshot<String> snapshot = Snapshot.of(
        List.of("a", "b"), indices, 1L, "hash");

    final QueryStep<String> query = new QueryStep<>(snapshot, "idx");

    final List<String> result = query.equalTo("key1");
    assertEquals(2, result.size());
    assertTrue(result.contains("a"));
    assertTrue(result.contains("b"));
  }

  @Test
  void whenEqualTo_givenMissingKey_shouldReturnEmpty() {
    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("idx", new HashMap<>());

    final Snapshot<String> snapshot = Snapshot.of(
        List.of("a"), indices, 1L, "hash");

    final QueryStep<String> query = new QueryStep<>(snapshot, "idx");
    assertTrue(query.equalTo("nope").isEmpty());
  }

  @Test
  void whenBetween_givenSortedIndex_shouldReturnRange() {
    final TreeMap<Comparable<?>, List<String>> sorted = new TreeMap<>();
    sorted.put(1, List.of("a"));
    sorted.put(2, List.of("b"));
    sorted.put(3, List.of("c"));
    sorted.put(4, List.of("d"));

    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    final Map<String, TreeMap<Comparable<?>, List<String>>> sortedIndices =
        new HashMap<>();
    sortedIndices.put("idx", sorted);

    final Snapshot<String> snapshot = Snapshot.of(
        List.of("a", "b", "c", "d"), indices,
        sortedIndices, null, 1L, "hash");

    final QueryStep<String> query = new QueryStep<>(snapshot, "idx");
    final List<String> result = query.between(2, 3);
    assertEquals(2, result.size());
    assertTrue(result.contains("b"));
    assertTrue(result.contains("c"));
  }

  @Test
  void whenBetween_givenNonSortedIndex_shouldThrowException() {
    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("idx", new HashMap<>());

    final Snapshot<String> snapshot = Snapshot.of(
        List.of("a"), indices, 1L, "hash");

    final QueryStep<String> query = new QueryStep<>(snapshot, "idx");
    assertThrows(UnsupportedIndexOperationException.class,
        () -> query.between(1, 5));
  }

  @Test
  void whenStartsWith_givenStringKeySortedIndex_shouldReturnMatches() {
    final Map<Object, List<String>> hash = new HashMap<>();
    hash.put("Maracana", List.of("x"));
    hash.put("Madrid", List.of("y"));
    hash.put("Wembley", List.of("z"));

    final TreeMap<Comparable<?>, List<String>> sorted = new TreeMap<>();
    sorted.put("Maracana", hash.get("Maracana"));
    sorted.put("Madrid", hash.get("Madrid"));
    sorted.put("Wembley", hash.get("Wembley"));

    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("idx", hash);

    final Map<String, TreeMap<Comparable<?>, List<String>>> sortedIndices =
        new HashMap<>();
    sortedIndices.put("idx", sorted);

    final Map<String, TreeMap<String, List<String>>> reversedIndices =
        new HashMap<>();
    final TreeMap<String, List<String>> rev = new TreeMap<>();
    rev.put(new StringBuilder("Maracana").reverse().toString(),
        hash.get("Maracana"));
    rev.put(new StringBuilder("Madrid").reverse().toString(),
        hash.get("Madrid"));
    rev.put(new StringBuilder("Wembley").reverse().toString(),
        hash.get("Wembley"));
    reversedIndices.put("idx", rev);

    final Snapshot<String> snapshot = Snapshot.of(
        List.of("x", "y", "z"), indices,
        sortedIndices, reversedIndices, 1L, "hash");

    final QueryStep<String> query = new QueryStep<>(snapshot, "idx");
    final List<String> result = query.startsWith("Mar");
    assertEquals(2, result.size());
  }

  @Test
  void whenQueryingNonExistentIndex_shouldThrowIndexNotFound() {
    final Snapshot<String> snapshot = Snapshot.of(
        List.of("a"), new HashMap<>(), 1L, "hash");

    final QueryStep<String> query = new QueryStep<>(snapshot, "nope");
    assertThrows(IndexNotFoundException.class,
        () -> query.equalTo("key"));
  }
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn test -pl andersoni-core -Dtest=QueryStepTest -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: FAIL — class does not exist

**Step 3: Implement QueryStep**

```java
package org.waabox.andersoni;

import java.util.List;
import java.util.Objects;

/**
 * Fluent query API for searching indexed data in a {@link Snapshot}.
 *
 * <p>Created via {@link Catalog#query(String)} or
 * {@link Andersoni#query(String, String)}. Each method returns an
 * unmodifiable {@link List} of matching items.
 *
 * <p>Operations that require sorted index support will throw
 * {@link UnsupportedIndexOperationException} if the target index was
 * not created with {@code indexSorted()}.
 *
 * <p>This class is stateless and created on every {@code query()} call.
 *
 * @param <T> the type of data items
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class QueryStep<T> {

  /** The snapshot to query against. */
  private final Snapshot<T> snapshot;

  /** The index name to query. */
  private final String indexName;

  /**
   * Creates a new QueryStep.
   *
   * @param snapshot  the snapshot to query, never null
   * @param indexName the index name, never null
   */
  public QueryStep(final Snapshot<T> snapshot, final String indexName) {
    this.snapshot = Objects.requireNonNull(snapshot);
    this.indexName = Objects.requireNonNull(indexName);
  }

  /** Exact-match lookup. Works on any index type. */
  public List<T> equalTo(final Object key) {
    return snapshot.search(indexName, key);
  }

  /** Range query (inclusive both ends). Requires sorted index. */
  public List<T> between(final Comparable<?> from, final Comparable<?> to) {
    return snapshot.searchBetween(indexName, from, to);
  }

  /** Strict greater-than. Requires sorted index. */
  public List<T> greaterThan(final Comparable<?> key) {
    return snapshot.searchGreaterThan(indexName, key);
  }

  /** Greater-than or equal. Requires sorted index. */
  public List<T> greaterOrEqual(final Comparable<?> key) {
    return snapshot.searchGreaterOrEqual(indexName, key);
  }

  /** Strict less-than. Requires sorted index. */
  public List<T> lessThan(final Comparable<?> key) {
    return snapshot.searchLessThan(indexName, key);
  }

  /** Less-than or equal. Requires sorted index. */
  public List<T> lessOrEqual(final Comparable<?> key) {
    return snapshot.searchLessOrEqual(indexName, key);
  }

  /** Prefix match. Requires sorted index with String keys. */
  public List<T> startsWith(final String prefix) {
    return snapshot.searchStartsWith(indexName, prefix);
  }

  /** Suffix match. Requires sorted index with String keys. */
  public List<T> endsWith(final String suffix) {
    return snapshot.searchEndsWith(indexName, suffix);
  }

  /** Substring match. Requires sorted index with String keys. */
  public List<T> contains(final String substring) {
    return snapshot.searchContains(indexName, substring);
  }
}
```

**Step 4: Run tests**

Run: `mvn test -pl andersoni-core -Dtest=QueryStepTest -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: PASS

**Step 5: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/QueryStep.java \
       andersoni-core/src/test/java/org/waabox/andersoni/QueryStepTest.java
git commit -m "Add QueryStep with fluent query API for all search operations"
```

---

### Task 5: Extend Catalog with indexSorted() and query()

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/CatalogTest.java`

**Step 1: Write failing tests**

Add to `CatalogTest.java`:

```java
// Add these imports
import java.time.LocalDate;

// Add these test records
record EventDate(LocalDate value) {}
record DatedEvent(String id, EventDate eventDate, Venue venue) {}

@Test
void whenBuilding_givenIndexSorted_shouldBuildCorrectly() {
  final Catalog<DatedEvent> catalog = Catalog.of(DatedEvent.class)
      .named("events")
      .data(List.of())
      .indexSorted("by-date").by(DatedEvent::eventDate, EventDate::value)
      .build();
  assertNotNull(catalog);
  assertEquals("events", catalog.name());
}

@Test
void whenQuery_givenIndexSorted_shouldSupportBetween() {
  final LocalDate d1 = LocalDate.of(2024, 1, 10);
  final LocalDate d2 = LocalDate.of(2024, 3, 20);
  final LocalDate d3 = LocalDate.of(2024, 6, 15);

  final DatedEvent e1 = new DatedEvent("1", new EventDate(d1), new Venue("A"));
  final DatedEvent e2 = new DatedEvent("2", new EventDate(d2), new Venue("B"));
  final DatedEvent e3 = new DatedEvent("3", new EventDate(d3), new Venue("C"));

  final Catalog<DatedEvent> catalog = Catalog.of(DatedEvent.class)
      .named("events")
      .data(List.of(e1, e2, e3))
      .indexSorted("by-date").by(DatedEvent::eventDate, EventDate::value)
      .build();

  catalog.bootstrap();

  final List<DatedEvent> result = catalog.query("by-date")
      .between(d1, d2);
  assertEquals(2, result.size());
  assertTrue(result.contains(e1));
  assertTrue(result.contains(e2));
}

@Test
void whenQuery_givenIndexSorted_shouldSupportEqualTo() {
  final LocalDate d1 = LocalDate.of(2024, 1, 10);
  final DatedEvent e1 = new DatedEvent("1", new EventDate(d1), new Venue("A"));

  final Catalog<DatedEvent> catalog = Catalog.of(DatedEvent.class)
      .named("events")
      .data(List.of(e1))
      .indexSorted("by-date").by(DatedEvent::eventDate, EventDate::value)
      .build();

  catalog.bootstrap();

  final List<DatedEvent> result = catalog.query("by-date").equalTo(d1);
  assertEquals(1, result.size());
  assertTrue(result.contains(e1));
}

@Test
void whenQuery_givenRegularIndex_shouldSupportEqualTo() {
  final Event e1 = new Event("1", new Sport("Football"), new Venue("Maracana"));

  final Catalog<Event> catalog = Catalog.of(Event.class)
      .named("events")
      .data(List.of(e1))
      .index("by-venue").by(Event::venue, Venue::name)
      .build();

  catalog.bootstrap();

  final List<Event> result = catalog.query("by-venue").equalTo("Maracana");
  assertEquals(1, result.size());
  assertTrue(result.contains(e1));
}

@Test
void whenSearchShortcut_givenIndexSorted_shouldStillWork() {
  final LocalDate d1 = LocalDate.of(2024, 1, 10);
  final DatedEvent e1 = new DatedEvent("1", new EventDate(d1), new Venue("A"));

  final Catalog<DatedEvent> catalog = Catalog.of(DatedEvent.class)
      .named("events")
      .data(List.of(e1))
      .indexSorted("by-date").by(DatedEvent::eventDate, EventDate::value)
      .build();

  catalog.bootstrap();

  final List<DatedEvent> result = catalog.search("by-date", d1);
  assertEquals(1, result.size());
  assertTrue(result.contains(e1));
}

@Test
void whenBuilding_givenMixOfIndexAndIndexSorted_shouldWorkCorrectly() {
  final LocalDate d1 = LocalDate.of(2024, 1, 10);
  final DatedEvent e1 = new DatedEvent("1", new EventDate(d1),
      new Venue("Maracana"));

  final Catalog<DatedEvent> catalog = Catalog.of(DatedEvent.class)
      .named("events")
      .data(List.of(e1))
      .index("by-venue").by(DatedEvent::venue, Venue::name)
      .indexSorted("by-date").by(DatedEvent::eventDate, EventDate::value)
      .build();

  catalog.bootstrap();

  assertEquals(1, catalog.search("by-venue", "Maracana").size());
  assertEquals(1, catalog.query("by-date").equalTo(d1).size());
}

@Test
void whenBuilding_givenOnlyIndexSorted_shouldBuild() {
  final Catalog<DatedEvent> catalog = Catalog.of(DatedEvent.class)
      .named("events")
      .data(List.of())
      .indexSorted("by-date").by(DatedEvent::eventDate, EventDate::value)
      .build();
  assertNotNull(catalog);
}

@Test
void whenBuilding_givenDuplicateNameBetweenIndexAndIndexSorted_shouldThrow() {
  assertThrows(IllegalArgumentException.class, () ->
      Catalog.of(DatedEvent.class)
          .named("events")
          .data(List.of())
          .index("by-venue").by(DatedEvent::venue, Venue::name)
          .indexSorted("by-venue").by(DatedEvent::eventDate, EventDate::value)
          .build());
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn test -pl andersoni-core -Dtest=CatalogTest -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: FAIL — `indexSorted()` and `query()` methods do not exist

**Step 3: Implement Catalog changes**

Modify `Catalog.java`:
1. Add field: `List<SortedIndexDefinition<T>> sortedIndexDefinitions`
2. Update constructor to accept sorted definitions
3. Add `SortedIndexStep<T>` inner class in `BuildStep`
4. Add `indexSorted(String)` to `BuildStep`
5. Add `query(String)` method to Catalog
6. Update `buildAndSwapSnapshot()` to build sorted indices and pass them to the new `Snapshot.of()` overload
7. Update `build()` to accept at least one index of any type (not just `indexDefinitions`)
8. Add `addSortedIndex(SortedIndexDefinition)` to BuildStep with duplicate name detection

**Step 4: Run tests**

Run: `mvn test -pl andersoni-core -Dtest=CatalogTest -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: PASS

**Step 5: Run all tests**

Run: `mvn test -pl andersoni-core -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: ALL PASS

**Step 6: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java \
       andersoni-core/src/test/java/org/waabox/andersoni/CatalogTest.java
git commit -m "Add indexSorted() and query() to Catalog builder DSL"
```

---

### Task 6: Add query() to Andersoni

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java`

**Step 1: Write failing test**

Add to `AndersoniTest.java`:

```java
@Test
void whenQuery_givenRegisteredCatalog_shouldReturnQueryStep() {
  // Use a catalog with sorted index
  final Catalog<Event> catalog = Catalog.of(Event.class)
      .named("events")
      .data(List.of(
          new Event("1", new Sport("Football"), new Venue("Maracana")),
          new Event("2", new Sport("Rugby"), new Venue("Wembley"))))
      .indexSorted("by-venue").by(Event::venue, Venue::name)
      .build();

  final Andersoni andersoni = Andersoni.builder().build();
  andersoni.register(catalog);
  andersoni.start();

  final QueryStep<?> query = andersoni.query("events", "by-venue");
  assertNotNull(query);

  final List<?> result = query.startsWith("Mar");
  assertEquals(1, result.size());

  andersoni.stop();
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest="AndersoniTest#whenQuery_givenRegisteredCatalog_shouldReturnQueryStep" -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: FAIL — `query()` method does not exist on Andersoni

**Step 3: Implement query() on Andersoni**

Add to `Andersoni.java`:

```java
/**
 * Returns a {@link QueryStep} for fluent querying of a catalog's index.
 *
 * @param catalogName the name of the catalog, never null
 * @param indexName   the name of the index, never null
 *
 * @return a QueryStep for the specified catalog and index, never null
 *
 * @throws IllegalArgumentException     if no catalog with the given name
 *                                      is registered
 * @throws CatalogNotAvailableException if the catalog failed to bootstrap
 */
public QueryStep<?> query(final String catalogName,
    final String indexName) {
  Objects.requireNonNull(catalogName, "catalogName must not be null");
  Objects.requireNonNull(indexName, "indexName must not be null");

  final Catalog<?> catalog = requireCatalog(catalogName);

  if (failedCatalogs.contains(catalogName)) {
    throw new CatalogNotAvailableException(catalogName);
  }

  return catalog.query(indexName);
}
```

**Step 4: Run test**

Run: `mvn test -pl andersoni-core -Dtest="AndersoniTest#whenQuery_givenRegisteredCatalog_shouldReturnQueryStep" -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: PASS

**Step 5: Run all tests in the module**

Run: `mvn test -pl andersoni-core -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: ALL PASS

**Step 6: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java \
       andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java
git commit -m "Add query() to Andersoni for fluent querying across catalogs"
```

---

### Task 7: Full integration verification

**Step 1: Run the complete test suite**

Run: `mvn clean verify -f /Users/waabox/code/waabox/andersoni/pom.xml`
Expected: BUILD SUCCESS

**Step 2: Verify no regressions in other modules**

Check that `andersoni-spring-boot-starter`, `andersoni-sync-kafka`, etc. still compile and test correctly since they depend on `andersoni-core`.

**Step 3: Final commit if any cleanup needed**

Only if adjustments are required.
