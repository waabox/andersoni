# Graph-Based Composite Index Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a graph-based composite index to andersoni-core that navigates entity relationships, pre-computes hotpath keys, and provides O(1) lookups via a query planner.

**Architecture:** New `GraphIndexDefinition<T>` defines traversals (simple, fan-out, path) and hotpaths. At indexing time it generates `CompositeKey` entries into a standard `Map<Object, List<T>>`, requiring zero changes to `Snapshot`. A `QueryPlanner` selects the best hotpath, and a `GraphQueryBuilder` provides the fluent query DSL.

**Tech Stack:** Java 21, JUnit 5, EasyMock. Zero external dependencies (andersoni-core convention).

---

## Chunk 1: Foundation Types

### Task 1: CompositeKey

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/CompositeKey.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/CompositeKeyTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class CompositeKeyTest {

  @Test
  void whenCreating_givenTwoComponents_shouldReturnCorrectComponents() {
    final CompositeKey key = CompositeKey.of("AR", "deportes");
    assertEquals(List.of("AR", "deportes"), key.components());
    assertEquals(2, key.size());
  }

  @Test
  void whenCreating_givenSingleComponent_shouldReturnCorrectComponents() {
    final CompositeKey key = CompositeKey.of("AR");
    assertEquals(List.of("AR"), key.components());
    assertEquals(1, key.size());
  }

  @Test
  void whenComparing_givenEqualKeys_shouldBeEqual() {
    final CompositeKey k1 = CompositeKey.of("AR", "deportes");
    final CompositeKey k2 = CompositeKey.of("AR", "deportes");
    assertEquals(k1, k2);
    assertEquals(k1.hashCode(), k2.hashCode());
  }

  @Test
  void whenComparing_givenDifferentKeys_shouldNotBeEqual() {
    final CompositeKey k1 = CompositeKey.of("AR", "deportes");
    final CompositeKey k2 = CompositeKey.of("AR", "futbol");
    assertNotEquals(k1, k2);
  }

  @Test
  void whenComparing_givenDifferentSizes_shouldNotBeEqual() {
    final CompositeKey k1 = CompositeKey.of("AR");
    final CompositeKey k2 = CompositeKey.of("AR", "deportes");
    assertNotEquals(k1, k2);
  }

  @Test
  void whenCreating_givenNullArray_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> CompositeKey.of((Object[]) null));
  }

  @Test
  void whenCreating_givenEmptyArray_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> CompositeKey.of());
  }

  @Test
  void whenCreating_givenNullComponent_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> CompositeKey.of("AR", null));
  }

  @Test
  void whenCreatingFromList_givenList_shouldBeEqualToVarargs() {
    final CompositeKey k1 = CompositeKey.of("AR", "deportes");
    final CompositeKey k2 = CompositeKey.ofList(List.of("AR", "deportes"));
    assertEquals(k1, k2);
  }

  @Test
  void whenUsingAsHashMapKey_givenEqualKeys_shouldFindValue() {
    final java.util.Map<CompositeKey, String> map = new java.util.HashMap<>();
    map.put(CompositeKey.of("AR", "deportes"), "found");
    assertEquals("found", map.get(CompositeKey.of("AR", "deportes")));
  }

  @Test
  void whenCallingToString_shouldShowComponents() {
    final CompositeKey key = CompositeKey.of("AR", "deportes");
    assertEquals("CompositeKey[AR, deportes]", key.toString());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=CompositeKeyTest -q`
Expected: FAIL -- `CompositeKey` class does not exist.

- [ ] **Step 3: Write the implementation**

```java
package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Immutable value object representing a multi-component key for composite
 * index lookups.
 *
 * <p>Components are compared using {@link Object#equals(Object)} and
 * {@link Object#hashCode()}. All lookups use {@link java.util.HashMap},
 * so this class does not implement {@link Comparable}.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class CompositeKey {

  /** The components of this composite key. */
  private final List<Object> components;

  /** The pre-computed hash code. */
  private final int hash;

  /**
   * Creates a new composite key.
   *
   * @param components the components, never null, never empty
   */
  private CompositeKey(final List<Object> components) {
    this.components = components;
    this.hash = components.hashCode();
  }

  /**
   * Creates a new composite key from the given components.
   *
   * @param components the components, never null, must have at least one
   *                   element, no null elements allowed
   *
   * @return a new composite key, never null
   *
   * @throws NullPointerException     if components array is null or any
   *                                  component is null
   * @throws IllegalArgumentException if components is empty
   */
  public static CompositeKey of(final Object... components) {
    Objects.requireNonNull(components, "components must not be null");
    if (components.length == 0) {
      throw new IllegalArgumentException("components must not be empty");
    }
    final List<Object> list = new ArrayList<>(components.length);
    for (final Object c : components) {
      Objects.requireNonNull(c, "component must not be null");
      list.add(c);
    }
    return new CompositeKey(Collections.unmodifiableList(list));
  }

  /**
   * Creates a new composite key from the given list of components.
   *
   * @param components the components, never null, must have at least one
   *                   element, no null elements allowed
   *
   * @return a new composite key, never null
   *
   * @throws NullPointerException     if components list is null or any
   *                                  component is null
   * @throws IllegalArgumentException if components is empty
   */
  public static CompositeKey ofList(final List<Object> components) {
    Objects.requireNonNull(components, "components must not be null");
    if (components.isEmpty()) {
      throw new IllegalArgumentException("components must not be empty");
    }
    final List<Object> list = new ArrayList<>(components.size());
    for (final Object c : components) {
      Objects.requireNonNull(c, "component must not be null");
      list.add(c);
    }
    return new CompositeKey(Collections.unmodifiableList(list));
  }

  /**
   * Returns the unmodifiable list of components.
   *
   * @return the components, never null, never empty
   */
  public List<Object> components() {
    return components;
  }

  /**
   * Returns the number of components.
   *
   * @return the size, always >= 1
   */
  public int size() {
    return components.size();
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CompositeKey other)) {
      return false;
    }
    return components.equals(other.components);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public String toString() {
    final StringJoiner joiner = new StringJoiner(", ",
        "CompositeKey[", "]");
    for (final Object c : components) {
      joiner.add(c.toString());
    }
    return joiner.toString();
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=CompositeKeyTest -q`
Expected: PASS (all 11 tests).

- [ ] **Step 5: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/CompositeKey.java \
        andersoni-core/src/test/java/org/waabox/andersoni/CompositeKeyTest.java
git commit -m "Add CompositeKey value object for composite index lookups"
```

---

### Task 2: Traversal sealed interface and implementations

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/Traversal.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/TraversalTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TraversalTest {

  record Country(String code) {}
  record Event(Country country) {}
  record Publication(String categoryPath, List<Event> events, Event mainEvent) {}

  // --- SimpleTraversal ---

  @Test
  void whenEvaluatingSimple_givenValidExtractor_shouldReturnSingleValue() {
    final Traversal<Publication> t = Traversal.simple("main-country",
        pub -> pub.mainEvent().country().code());
    final Publication pub = new Publication("deportes",
        List.of(), new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertEquals(Set.of("AR"), result);
  }

  @Test
  void whenEvaluatingSimple_givenNullResult_shouldReturnEmpty() {
    final Traversal<Publication> t = Traversal.simple("main-country",
        pub -> null);
    final Publication pub = new Publication("deportes",
        List.of(), new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertTrue(result.isEmpty());
  }

  // --- FanOutTraversal ---

  @Test
  void whenEvaluatingFanOut_givenMultipleEvents_shouldReturnDistinctValues() {
    final Traversal<Publication> t = Traversal.fanOut("country",
        Publication::events, event -> event.country().code());
    final Publication pub = new Publication("deportes",
        List.of(new Event(new Country("AR")), new Event(new Country("MX")),
            new Event(new Country("AR"))),
        new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertEquals(Set.of("AR", "MX"), result);
  }

  @Test
  void whenEvaluatingFanOut_givenNullCollection_shouldReturnEmpty() {
    final Traversal<Publication> t = Traversal.fanOut("country",
        pub -> null, event -> "X");
    final Publication pub = new Publication("deportes", null,
        new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenEvaluatingFanOut_givenEmptyCollection_shouldReturnEmpty() {
    final Traversal<Publication> t = Traversal.fanOut("country",
        Publication::events, event -> event.country().code());
    final Publication pub = new Publication("deportes", List.of(),
        new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenEvaluatingFanOut_givenNullElement_shouldSkipIt() {
    final Traversal<Publication> t = Traversal.fanOut("country",
        Publication::events, event -> event.country().code());
    final List<Event> events = new java.util.ArrayList<>();
    events.add(new Event(new Country("AR")));
    events.add(null);
    events.add(new Event(new Country("MX")));
    final Publication pub = new Publication("deportes", events,
        new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertEquals(Set.of("AR", "MX"), result);
  }

  @Test
  void whenEvaluatingFanOut_givenNullExtractedValue_shouldSkipIt() {
    final Traversal<Publication> t = Traversal.fanOut("country",
        Publication::events, event -> event.country() != null
            ? event.country().code() : null);
    final Publication pub = new Publication("deportes",
        List.of(new Event(new Country("AR")), new Event(null)),
        new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertEquals(Set.of("AR"), result);
  }

  // --- PathTraversal ---

  @Test
  void whenEvaluatingPath_givenMultiSegmentPath_shouldReturnAllPrefixes() {
    final Traversal<Publication> t = Traversal.path("category", "/",
        Publication::categoryPath);
    final Publication pub = new Publication("deportes/futbol/liga",
        List.of(), new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertEquals(Set.of("deportes", "deportes/futbol",
        "deportes/futbol/liga"), result);
  }

  @Test
  void whenEvaluatingPath_givenSingleSegment_shouldReturnOneValue() {
    final Traversal<Publication> t = Traversal.path("category", "/",
        Publication::categoryPath);
    final Publication pub = new Publication("deportes",
        List.of(), new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertEquals(Set.of("deportes"), result);
  }

  @Test
  void whenEvaluatingPath_givenNullPath_shouldReturnEmpty() {
    final Traversal<Publication> t = Traversal.path("category", "/",
        Publication::categoryPath);
    final Publication pub = new Publication(null,
        List.of(), new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenEvaluatingPath_givenEmptyPath_shouldReturnEmpty() {
    final Traversal<Publication> t = Traversal.path("category", "/",
        Publication::categoryPath);
    final Publication pub = new Publication("",
        List.of(), new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenEvaluatingPath_givenLeadingTrailingSeparators_shouldFilterEmptySegments() {
    final Traversal<Publication> t = Traversal.path("category", "/",
        Publication::categoryPath);
    final Publication pub = new Publication("/deportes//futbol/",
        List.of(), new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertEquals(Set.of("deportes", "deportes/futbol"), result);
  }

  // --- Name accessor ---

  @Test
  void whenGettingName_shouldReturnConfiguredName() {
    final Traversal<Publication> t = Traversal.simple("my-field",
        pub -> "value");
    assertEquals("my-field", t.name());
  }

  // --- Validation ---

  @Test
  void whenCreating_givenNullName_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> Traversal.simple(null, pub -> "value"));
  }

  @Test
  void whenCreating_givenEmptyName_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> Traversal.simple("", pub -> "value"));
  }

  @Test
  void whenCreating_givenNullExtractor_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> Traversal.simple("name", null));
  }

  @Test
  void whenCreatingPath_givenNullSeparator_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> Traversal.path("name", null, pub -> "path"));
  }

  @Test
  void whenCreatingPath_givenEmptySeparator_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> Traversal.path("name", "", pub -> "path"));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=TraversalTest -q`
Expected: FAIL -- `Traversal` class does not exist.

- [ ] **Step 3: Write the implementation**

```java
package org.waabox.andersoni;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Defines how to extract values from a root entity by navigating its
 * relationships.
 *
 * <p>A traversal produces a set of distinct values for a given item.
 * These values become components of {@link CompositeKey}s when combined
 * in a hotpath.
 *
 * <p>This is a sealed interface with three permitted implementations:
 * {@link Simple}, {@link FanOut}, and {@link Path}.
 *
 * @param <T> the type of root entity being traversed
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public sealed interface Traversal<T> {

  /**
   * Returns the name of this traversal.
   *
   * @return the name, never null
   */
  String name();

  /**
   * Evaluates this traversal for the given item, returning the set of
   * distinct values extracted.
   *
   * @param item the root entity to traverse, never null
   *
   * @return an unmodifiable set of distinct values, never null
   *         (may be empty if the traversal produces no values)
   */
  Set<?> evaluate(T item);

  /**
   * Creates a simple traversal that extracts a single value.
   *
   * @param name      the traversal name, never null or empty
   * @param extractor the value extractor, never null
   * @param <T>       the root entity type
   *
   * @return a new simple traversal, never null
   */
  static <T> Traversal<T> simple(final String name,
      final Function<T, ?> extractor) {
    return new Simple<>(name, extractor);
  }

  /**
   * Creates a fan-out traversal that navigates a one-to-many relationship.
   *
   * <p>Values are automatically deduplicated. Null elements in the
   * collection and null extracted values are skipped.
   *
   * @param name               the traversal name, never null or empty
   * @param collectionAccessor the function returning the collection,
   *                           never null
   * @param valueExtractor     the function extracting a value from each
   *                           element, never null
   * @param <T>                the root entity type
   * @param <C>                the collection element type
   *
   * @return a new fan-out traversal, never null
   */
  static <T, C> Traversal<T> fanOut(final String name,
      final Function<T, ? extends Collection<C>> collectionAccessor,
      final Function<C, ?> valueExtractor) {
    return new FanOut<>(name, collectionAccessor, valueExtractor);
  }

  /**
   * Creates a path traversal that splits a string by a separator and
   * generates prefix keys at every segment level.
   *
   * <p>Empty segments (from leading/trailing/consecutive separators)
   * are filtered out. Match is always by complete segments.
   *
   * @param name      the traversal name, never null or empty
   * @param separator the path separator, never null or empty
   * @param extractor the path string extractor, never null
   * @param <T>       the root entity type
   *
   * @return a new path traversal, never null
   */
  static <T> Traversal<T> path(final String name, final String separator,
      final Function<T, String> extractor) {
    return new Path<>(name, separator, extractor);
  }

  /**
   * Validates common traversal parameters.
   *
   * @param name the traversal name
   * @param extractor the extractor function
   */
  private static void validateParams(final String name,
      final Object extractor) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isEmpty()) {
      throw new IllegalArgumentException("name must not be empty");
    }
    Objects.requireNonNull(extractor, "extractor must not be null");
  }

  /**
   * Extracts a single value from the root entity.
   *
   * @param <T> the root entity type
   */
  record Simple<T>(String name, Function<T, ?> extractor)
      implements Traversal<T> {

    Simple {
      validateParams(name, extractor);
    }

    @Override
    public Set<?> evaluate(final T item) {
      final Object value = extractor.apply(item);
      if (value == null) {
        return Set.of();
      }
      return Set.of(value);
    }
  }

  /**
   * Navigates a one-to-many relationship, producing deduplicated values.
   *
   * @param <T> the root entity type
   * @param <C> the collection element type
   */
  record FanOut<T, C>(String name,
      Function<T, ? extends Collection<C>> collectionAccessor,
      Function<C, ?> valueExtractor) implements Traversal<T> {

    FanOut {
      validateParams(name, collectionAccessor);
      Objects.requireNonNull(valueExtractor,
          "valueExtractor must not be null");
    }

    @Override
    public Set<?> evaluate(final T item) {
      final Collection<C> collection = collectionAccessor.apply(item);
      if (collection == null || collection.isEmpty()) {
        return Set.of();
      }
      final Set<Object> result = new LinkedHashSet<>();
      for (final C element : collection) {
        if (element == null) {
          continue;
        }
        final Object value = valueExtractor.apply(element);
        if (value != null) {
          result.add(value);
        }
      }
      return Set.copyOf(result);
    }
  }

  /**
   * Splits a path string by separator and generates prefix keys at
   * every segment level. Empty segments are filtered out.
   *
   * @param <T> the root entity type
   */
  record Path<T>(String name, String separator,
      Function<T, String> extractor) implements Traversal<T> {

    Path {
      validateParams(name, extractor);
      Objects.requireNonNull(separator, "separator must not be null");
      if (separator.isEmpty()) {
        throw new IllegalArgumentException(
            "separator must not be empty");
      }
    }

    @Override
    public Set<?> evaluate(final T item) {
      final String path = extractor.apply(item);
      if (path == null || path.isEmpty()) {
        return Set.of();
      }
      final String[] segments = path.split(
          java.util.regex.Pattern.quote(separator), -1);
      final Set<Object> result = new LinkedHashSet<>();
      final StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (final String segment : segments) {
        if (segment.isEmpty()) {
          continue;
        }
        if (!first) {
          sb.append(separator);
        }
        sb.append(segment);
        result.add(sb.toString());
        first = false;
      }
      return Set.copyOf(result);
    }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=TraversalTest -q`
Expected: PASS (all 18 tests).

- [ ] **Step 5: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Traversal.java \
        andersoni-core/src/test/java/org/waabox/andersoni/TraversalTest.java
git commit -m "Add Traversal sealed interface with Simple, FanOut, and Path implementations"
```

---

### Task 3: Hotpath and IndexKeyLimitExceededException

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/Hotpath.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/IndexKeyLimitExceededException.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/HotpathTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class HotpathTest {

  @Test
  void whenCreating_givenFieldNames_shouldReturnThem() {
    final Hotpath hotpath = Hotpath.of("country", "category");
    assertEquals(List.of("country", "category"), hotpath.fieldNames());
    assertEquals(2, hotpath.size());
  }

  @Test
  void whenCreating_givenSingleField_shouldReturnIt() {
    final Hotpath hotpath = Hotpath.of("country");
    assertEquals(List.of("country"), hotpath.fieldNames());
    assertEquals(1, hotpath.size());
  }

  @Test
  void whenCreating_givenNullArray_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> Hotpath.of((String[]) null));
  }

  @Test
  void whenCreating_givenEmptyArray_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> Hotpath.of());
  }

  @Test
  void whenCreating_givenNullFieldName_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> Hotpath.of("country", null));
  }

  @Test
  void whenCreating_givenEmptyFieldName_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> Hotpath.of("country", ""));
  }

  @Test
  void whenCreating_givenDuplicateFieldName_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> Hotpath.of("country", "country"));
  }

  @Test
  void whenGettingFieldNames_shouldReturnUnmodifiableList() {
    final Hotpath hotpath = Hotpath.of("country", "category");
    assertThrows(UnsupportedOperationException.class,
        () -> hotpath.fieldNames().add("extra"));
  }

  @Test
  void whenCreating_givenSpecificOrder_shouldPreserveOrder() {
    final Hotpath hotpath = Hotpath.of("beta", "alpha");
    assertEquals(List.of("beta", "alpha"), hotpath.fieldNames());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=HotpathTest -q`
Expected: FAIL -- `Hotpath` class does not exist.

- [ ] **Step 3: Write the implementations**

`Hotpath.java`:
```java
package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Declares an ordered combination of traversal field names to pre-compute
 * as composite keys at indexing time.
 *
 * <p>The field order defines the left-to-right prefix order, analogous to
 * a composite B-tree index in a relational database.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class Hotpath {

  /** The ordered field names. */
  private final List<String> fieldNames;

  /**
   * Creates a new hotpath.
   *
   * @param fieldNames the ordered field names, never null
   */
  private Hotpath(final List<String> fieldNames) {
    this.fieldNames = fieldNames;
  }

  /**
   * Creates a new hotpath from the given field names.
   *
   * @param fieldNames the ordered field names, never null, at least one,
   *                   no nulls, no empty strings, no duplicates
   *
   * @return a new hotpath, never null
   *
   * @throws NullPointerException     if fieldNames is null or any name
   *                                  is null
   * @throws IllegalArgumentException if empty, any name is empty, or
   *                                  duplicates exist
   */
  public static Hotpath of(final String... fieldNames) {
    Objects.requireNonNull(fieldNames, "fieldNames must not be null");
    if (fieldNames.length == 0) {
      throw new IllegalArgumentException("fieldNames must not be empty");
    }
    final Set<String> seen = new HashSet<>();
    final List<String> list = new ArrayList<>(fieldNames.length);
    for (final String name : fieldNames) {
      Objects.requireNonNull(name, "fieldName must not be null");
      if (name.isEmpty()) {
        throw new IllegalArgumentException("fieldName must not be empty");
      }
      if (!seen.add(name)) {
        throw new IllegalArgumentException(
            "Duplicate field name: '" + name + "'");
      }
      list.add(name);
    }
    return new Hotpath(Collections.unmodifiableList(list));
  }

  /**
   * Returns the ordered list of field names.
   *
   * @return unmodifiable list of field names, never null
   */
  public List<String> fieldNames() {
    return fieldNames;
  }

  /**
   * Returns the number of fields in this hotpath.
   *
   * @return the size, always >= 1
   */
  public int size() {
    return fieldNames.size();
  }
}
```

`IndexKeyLimitExceededException.java`:
```java
package org.waabox.andersoni;

/**
 * Thrown when the number of composite keys generated for a single item
 * exceeds the configured {@code maxKeysPerItem} safety limit.
 *
 * <p>This is a fail-fast mechanism to prevent combinatorial explosion
 * during indexing. Users should set the limit generously based on
 * expected data characteristics.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class IndexKeyLimitExceededException extends AndersoniException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a new exception.
   *
   * @param indexName      the graph index name
   * @param itemToString   the item's toString representation
   * @param keyCount       the number of keys generated
   * @param maxKeysPerItem the configured limit
   */
  public IndexKeyLimitExceededException(final String indexName,
      final String itemToString, final int keyCount,
      final int maxKeysPerItem) {
    super("Graph index '" + indexName + "' generated " + keyCount
        + " keys for item [" + itemToString + "], exceeding limit of "
        + maxKeysPerItem + ". Increase maxKeysPerItem or reduce data"
        + " cardinality.");
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=HotpathTest -q`
Expected: PASS (all 9 tests).

- [ ] **Step 5: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Hotpath.java \
        andersoni-core/src/main/java/org/waabox/andersoni/IndexKeyLimitExceededException.java \
        andersoni-core/src/test/java/org/waabox/andersoni/HotpathTest.java
git commit -m "Add Hotpath declaration and IndexKeyLimitExceededException"
```

---

## Chunk 2: GraphIndexDefinition

### Task 4: GraphIndexDefinition -- core indexing engine

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/GraphIndexDefinition.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/GraphIndexDefinitionTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GraphIndexDefinitionTest {

  record Country(String code) {}
  record Event(Country country) {}
  record Publication(String categoryPath, List<Event> events) {}

  private GraphIndexDefinition<Publication> buildStandardIndex() {
    return GraphIndexDefinition.<Publication>named("by-country-category")
        .traverseMany("country", Publication::events,
            event -> event.country().code())
        .traversePath("category", "/", Publication::categoryPath)
        .hotpath("country", "category")
        .build();
  }

  @Test
  void whenBuilding_givenTraversalsAndHotpath_shouldGenerateCorrectKeys() {
    final GraphIndexDefinition<Publication> def = buildStandardIndex();

    final Publication pub = new Publication("deportes/futbol",
        List.of(new Event(new Country("AR"))));

    final Map<Object, List<Publication>> index =
        def.buildIndex(List.of(pub));

    // Prefix key: CompositeKey("AR")
    assertEquals(List.of(pub),
        index.get(CompositeKey.of("AR")));

    // Full keys: CompositeKey("AR", "deportes"), CompositeKey("AR", "deportes/futbol")
    assertEquals(List.of(pub),
        index.get(CompositeKey.of("AR", "deportes")));
    assertEquals(List.of(pub),
        index.get(CompositeKey.of("AR", "deportes/futbol")));
  }

  @Test
  void whenBuilding_givenFanOut_shouldGenerateKeysForEachValue() {
    final GraphIndexDefinition<Publication> def = buildStandardIndex();

    final Publication pub = new Publication("deportes",
        List.of(new Event(new Country("AR")),
            new Event(new Country("MX"))));

    final Map<Object, List<Publication>> index =
        def.buildIndex(List.of(pub));

    assertEquals(List.of(pub), index.get(CompositeKey.of("AR")));
    assertEquals(List.of(pub), index.get(CompositeKey.of("MX")));
    assertEquals(List.of(pub),
        index.get(CompositeKey.of("AR", "deportes")));
    assertEquals(List.of(pub),
        index.get(CompositeKey.of("MX", "deportes")));
  }

  @Test
  void whenBuilding_givenDuplicateFanOutValues_shouldDeduplicateKeys() {
    final GraphIndexDefinition<Publication> def = buildStandardIndex();

    final Publication pub = new Publication("deportes",
        List.of(new Event(new Country("AR")),
            new Event(new Country("AR"))));

    final Map<Object, List<Publication>> index =
        def.buildIndex(List.of(pub));

    // Should appear once, not twice
    assertEquals(1, index.get(CompositeKey.of("AR")).size());
  }

  @Test
  void whenBuilding_givenNullTraversalResult_shouldExcludeItem() {
    final GraphIndexDefinition<Publication> def = buildStandardIndex();

    final Publication pub = new Publication(null,
        List.of(new Event(new Country("AR"))));

    final Map<Object, List<Publication>> index =
        def.buildIndex(List.of(pub));

    // Only prefix key, no category keys
    assertEquals(List.of(pub), index.get(CompositeKey.of("AR")));
    // No 2-component keys since categoryPath is null
    assertEquals(1, index.size());
  }

  @Test
  void whenBuilding_givenMultipleItems_shouldGroupByKey() {
    final GraphIndexDefinition<Publication> def = buildStandardIndex();

    final Publication pub1 = new Publication("deportes",
        List.of(new Event(new Country("AR"))));
    final Publication pub2 = new Publication("deportes",
        List.of(new Event(new Country("AR"))));

    final Map<Object, List<Publication>> index =
        def.buildIndex(List.of(pub1, pub2));

    assertEquals(2, index.get(CompositeKey.of("AR", "deportes")).size());
    assertTrue(index.get(CompositeKey.of("AR", "deportes"))
        .contains(pub1));
    assertTrue(index.get(CompositeKey.of("AR", "deportes"))
        .contains(pub2));
  }

  @Test
  void whenBuilding_givenEmptyData_shouldReturnEmptyMap() {
    final GraphIndexDefinition<Publication> def = buildStandardIndex();
    final Map<Object, List<Publication>> index =
        def.buildIndex(List.of());
    assertTrue(index.isEmpty());
  }

  @Test
  void whenBuilding_givenMaxKeysExceeded_shouldThrow() {
    final GraphIndexDefinition<Publication> def =
        GraphIndexDefinition.<Publication>named("test")
            .traverseMany("country", Publication::events,
                event -> event.country().code())
            .traversePath("category", "/", Publication::categoryPath)
            .hotpath("country", "category")
            .maxKeysPerItem(2)
            .build();

    // AR x (deportes, deportes/futbol) = 2 full keys + 1 prefix = 3 > limit 2
    final Publication pub = new Publication("deportes/futbol",
        List.of(new Event(new Country("AR"))));

    assertThrows(IndexKeyLimitExceededException.class,
        () -> def.buildIndex(List.of(pub)));
  }

  @Test
  void whenBuilding_givenMultipleHotpaths_shouldMergeIntoSingleMap() {
    final GraphIndexDefinition<Publication> def =
        GraphIndexDefinition.<Publication>named("test")
            .traverse("first-country",
                pub -> pub.events().isEmpty() ? null
                    : pub.events().getFirst().country().code())
            .traverseMany("all-countries", Publication::events,
                event -> event.country().code())
            .traversePath("category", "/", Publication::categoryPath)
            .hotpath("first-country", "category")
            .hotpath("all-countries")
            .build();

    final Publication pub = new Publication("deportes",
        List.of(new Event(new Country("AR")),
            new Event(new Country("MX"))));

    final Map<Object, List<Publication>> index =
        def.buildIndex(List.of(pub));

    // From hotpath 1: CompositeKey("AR"), CompositeKey("AR", "deportes")
    assertEquals(List.of(pub),
        index.get(CompositeKey.of("AR", "deportes")));
    // From hotpath 2: CompositeKey("AR"), CompositeKey("MX")
    assertEquals(List.of(pub), index.get(CompositeKey.of("MX")));
  }

  @Test
  void whenBuilding_givenItemDuplicateAcrossHotpaths_shouldDeduplicateByIdentity() {
    final GraphIndexDefinition<Publication> def =
        GraphIndexDefinition.<Publication>named("test")
            .traverse("country",
                pub -> pub.events().getFirst().country().code())
            .traverseMany("countries", Publication::events,
                event -> event.country().code())
            .hotpath("country")
            .hotpath("countries")
            .build();

    final Publication pub = new Publication("deportes",
        List.of(new Event(new Country("AR"))));

    final Map<Object, List<Publication>> index =
        def.buildIndex(List.of(pub));

    // Both hotpaths generate CompositeKey("AR"), item should appear once
    assertEquals(1, index.get(CompositeKey.of("AR")).size());
  }

  // --- Validation ---

  @Test
  void whenBuilding_givenNoTraversals_shouldThrow() {
    assertThrows(IllegalStateException.class,
        () -> GraphIndexDefinition.<Publication>named("test")
            .hotpath("country")
            .build());
  }

  @Test
  void whenBuilding_givenNoHotpaths_shouldThrow() {
    assertThrows(IllegalStateException.class,
        () -> GraphIndexDefinition.<Publication>named("test")
            .traverse("country", pub -> "AR")
            .build());
  }

  @Test
  void whenBuilding_givenHotpathReferencesUnknownTraversal_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> GraphIndexDefinition.<Publication>named("test")
            .traverse("country", pub -> "AR")
            .hotpath("country", "unknown")
            .build());
  }

  @Test
  void whenBuilding_givenDuplicateTraversalName_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> GraphIndexDefinition.<Publication>named("test")
            .traverse("country", pub -> "AR")
            .traverse("country", pub -> "MX"));
  }

  @Test
  void whenGettingName_shouldReturnConfiguredName() {
    final GraphIndexDefinition<Publication> def = buildStandardIndex();
    assertEquals("by-country-category", def.name());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=GraphIndexDefinitionTest -q`
Expected: FAIL -- `GraphIndexDefinition` class does not exist.

- [ ] **Step 3: Write the implementation**

```java
package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Defines a graph-based composite index that navigates entity relationships
 * via traversals and pre-computes hotpath keys for O(1) lookups.
 *
 * <p>Each traversal extracts named values from the root entity. Hotpaths
 * declare which traversal combinations to pre-compute as
 * {@link CompositeKey} entries. The output is a standard
 * {@code Map<Object, List<T>>} compatible with {@link Snapshot}.
 *
 * <p>Instances are created using the fluent builder starting with
 * {@link #named(String)}.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @param <T> the type of root entity being indexed
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class GraphIndexDefinition<T> {

  /** The name of this graph index. */
  private final String name;

  /** The traversals by name. */
  private final Map<String, Traversal<T>> traversals;

  /** The hotpath declarations. */
  private final List<Hotpath> hotpaths;

  /** The maximum number of keys per item. */
  private final int maxKeysPerItem;

  /**
   * Creates a new graph index definition.
   *
   * @param name          the index name, never null
   * @param traversals    the traversals, never null
   * @param hotpaths      the hotpaths, never null
   * @param maxKeysPerItem the max keys per item
   */
  private GraphIndexDefinition(final String name,
      final Map<String, Traversal<T>> traversals,
      final List<Hotpath> hotpaths, final int maxKeysPerItem) {
    this.name = name;
    this.traversals = traversals;
    this.hotpaths = hotpaths;
    this.maxKeysPerItem = maxKeysPerItem;
  }

  /**
   * Starts building a new graph index definition with the given name.
   *
   * @param name the index name, never null or empty
   * @param <T>  the root entity type
   *
   * @return the builder, never null
   *
   * @throws NullPointerException     if name is null
   * @throws IllegalArgumentException if name is empty
   */
  public static <T> Builder<T> named(final String name) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isEmpty()) {
      throw new IllegalArgumentException("name must not be empty");
    }
    return new Builder<>(name);
  }

  /**
   * Returns the name of this graph index.
   *
   * @return the name, never null
   */
  public String name() {
    return name;
  }

  /**
   * Returns the hotpath declarations.
   *
   * @return unmodifiable list of hotpaths, never null
   */
  public List<Hotpath> hotpaths() {
    return hotpaths;
  }

  /**
   * Returns the traversals by name.
   *
   * @return unmodifiable map of traversals, never null
   */
  public Map<String, Traversal<T>> traversals() {
    return traversals;
  }

  /**
   * Builds the index from the given data by evaluating all hotpaths and
   * generating composite keys.
   *
   * <p>Items are deduplicated per key using identity comparison.
   *
   * @param data the data to index, never null
   *
   * @return an unmodifiable map from composite keys to item lists,
   *         never null
   *
   * @throws NullPointerException             if data is null
   * @throws IndexKeyLimitExceededException   if any item exceeds
   *                                          maxKeysPerItem
   */
  public Map<Object, List<T>> buildIndex(final List<T> data) {
    Objects.requireNonNull(data, "data must not be null");

    if (data.isEmpty()) {
      return Collections.emptyMap();
    }

    // key -> identity set of items (for dedup)
    final Map<CompositeKey, Set<T>> keyToItems = new HashMap<>();

    for (final T item : data) {
      final Set<CompositeKey> allKeys = new LinkedHashSet<>();

      for (final Hotpath hotpath : hotpaths) {
        final List<Set<?>> traversalValues = new ArrayList<>();
        boolean hasNullTraversal = false;

        for (final String fieldName : hotpath.fieldNames()) {
          final Traversal<T> traversal = traversals.get(fieldName);
          final Set<?> values = traversal.evaluate(item);
          if (values.isEmpty()) {
            hasNullTraversal = true;
            break;
          }
          traversalValues.add(values);
        }

        if (hasNullTraversal && traversalValues.isEmpty()) {
          continue;
        }

        // Generate prefix keys for each prefix length
        for (int prefixLen = 1; prefixLen <= traversalValues.size();
            prefixLen++) {
          final List<Set<?>> prefixValues =
              traversalValues.subList(0, prefixLen);
          generateCartesianKeys(prefixValues, allKeys);
        }
      }

      if (allKeys.size() > maxKeysPerItem) {
        throw new IndexKeyLimitExceededException(name, item.toString(),
            allKeys.size(), maxKeysPerItem);
      }

      for (final CompositeKey key : allKeys) {
        keyToItems.computeIfAbsent(key,
            k -> Collections.newSetFromMap(new IdentityHashMap<>()))
            .add(item);
      }
    }

    // Convert identity sets to unmodifiable lists
    final Map<Object, List<T>> result = new HashMap<>();
    for (final Map.Entry<CompositeKey, Set<T>> entry
        : keyToItems.entrySet()) {
      result.put(entry.getKey(),
          Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
    }

    return Collections.unmodifiableMap(result);
  }

  /**
   * Generates cartesian product keys from the given traversal value sets
   * and adds them to the target set.
   *
   * @param valueSets the traversal value sets, one per field
   * @param target    the set to add generated keys to
   */
  private void generateCartesianKeys(final List<Set<?>> valueSets,
      final Set<CompositeKey> target) {
    generateCartesianKeysRecursive(valueSets, 0, new ArrayList<>(),
        target);
  }

  /**
   * Recursive cartesian product generation.
   *
   * @param valueSets the traversal value sets
   * @param depth     the current depth
   * @param current   the current components being built
   * @param target    the set to add generated keys to
   */
  private void generateCartesianKeysRecursive(
      final List<Set<?>> valueSets, final int depth,
      final List<Object> current, final Set<CompositeKey> target) {
    if (depth == valueSets.size()) {
      target.add(CompositeKey.ofList(current));
      return;
    }
    for (final Object value : valueSets.get(depth)) {
      final List<Object> next = new ArrayList<>(current);
      next.add(value);
      generateCartesianKeysRecursive(valueSets, depth + 1, next, target);
    }
  }

  /**
   * Builder for {@link GraphIndexDefinition}.
   *
   * @param <T> the root entity type
   */
  public static final class Builder<T> {

    /** The index name. */
    private final String name;

    /** The traversals by name. */
    private final Map<String, Traversal<T>> traversals;

    /** The hotpath declarations. */
    private final List<Hotpath> hotpaths;

    /** The max keys per item. */
    private int maxKeysPerItem = 100;

    /**
     * Creates a new builder.
     *
     * @param name the index name, never null
     */
    private Builder(final String name) {
      this.name = name;
      this.traversals = new LinkedHashMap<>();
      this.hotpaths = new ArrayList<>();
    }

    /**
     * Adds a simple traversal.
     *
     * @param traversalName the traversal name, never null or empty
     * @param extractor     the value extractor, never null
     *
     * @return this builder for chaining, never null
     *
     * @throws IllegalArgumentException if name is already used
     */
    public Builder<T> traverse(final String traversalName,
        final Function<T, ?> extractor) {
      addTraversal(Traversal.simple(traversalName, extractor));
      return this;
    }

    /**
     * Adds a fan-out traversal.
     *
     * @param traversalName      the traversal name, never null or empty
     * @param collectionAccessor the collection accessor, never null
     * @param valueExtractor     the value extractor, never null
     * @param <C>                the collection element type
     *
     * @return this builder for chaining, never null
     *
     * @throws IllegalArgumentException if name is already used
     */
    public <C> Builder<T> traverseMany(final String traversalName,
        final Function<T, ? extends Collection<C>> collectionAccessor,
        final Function<C, ?> valueExtractor) {
      addTraversal(Traversal.fanOut(traversalName, collectionAccessor,
          valueExtractor));
      return this;
    }

    /**
     * Adds a path traversal.
     *
     * @param traversalName the traversal name, never null or empty
     * @param separator     the path separator, never null or empty
     * @param extractor     the path string extractor, never null
     *
     * @return this builder for chaining, never null
     *
     * @throws IllegalArgumentException if name is already used
     */
    public Builder<T> traversePath(final String traversalName,
        final String separator, final Function<T, String> extractor) {
      addTraversal(Traversal.path(traversalName, separator, extractor));
      return this;
    }

    /**
     * Declares a hotpath to pre-compute.
     *
     * @param fieldNames the ordered traversal names, never null, at least
     *                   one, all must reference defined traversals
     *
     * @return this builder for chaining, never null
     */
    public Builder<T> hotpath(final String... fieldNames) {
      hotpaths.add(Hotpath.of(fieldNames));
      return this;
    }

    /**
     * Sets the maximum number of keys per item.
     *
     * @param max the max, must be positive
     *
     * @return this builder for chaining, never null
     *
     * @throws IllegalArgumentException if max is not positive
     */
    public Builder<T> maxKeysPerItem(final int max) {
      if (max <= 0) {
        throw new IllegalArgumentException(
            "maxKeysPerItem must be positive, got: " + max);
      }
      this.maxKeysPerItem = max;
      return this;
    }

    /**
     * Builds the graph index definition.
     *
     * @return a new graph index definition, never null
     *
     * @throws IllegalStateException    if no traversals or no hotpaths
     * @throws IllegalArgumentException if any hotpath references an
     *                                  unknown traversal
     */
    public GraphIndexDefinition<T> build() {
      if (traversals.isEmpty()) {
        throw new IllegalStateException(
            "At least one traversal is required");
      }
      if (hotpaths.isEmpty()) {
        throw new IllegalStateException(
            "At least one hotpath is required");
      }
      for (final Hotpath hp : hotpaths) {
        for (final String fieldName : hp.fieldNames()) {
          if (!traversals.containsKey(fieldName)) {
            throw new IllegalArgumentException(
                "Hotpath references unknown traversal: '"
                    + fieldName + "'");
          }
        }
      }
      return new GraphIndexDefinition<>(name,
          Collections.unmodifiableMap(new LinkedHashMap<>(traversals)),
          Collections.unmodifiableList(new ArrayList<>(hotpaths)),
          maxKeysPerItem);
    }

    /**
     * Adds a traversal, checking for duplicate names.
     *
     * @param traversal the traversal to add
     */
    private void addTraversal(final Traversal<T> traversal) {
      if (traversals.containsKey(traversal.name())) {
        throw new IllegalArgumentException(
            "Duplicate traversal name: '" + traversal.name() + "'");
      }
      traversals.put(traversal.name(), traversal);
    }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=GraphIndexDefinitionTest -q`
Expected: PASS (all 16 tests).

- [ ] **Step 5: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/GraphIndexDefinition.java \
        andersoni-core/src/test/java/org/waabox/andersoni/GraphIndexDefinitionTest.java
git commit -m "Add GraphIndexDefinition with traversal navigation and hotpath key generation"
```

---

## Chunk 3: Query Planner and GraphQueryBuilder

### Task 5: GraphQueryCondition and QueryPlanner

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/GraphQueryCondition.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/QueryPlanner.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/QueryPlannerTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class QueryPlannerTest {

  record Country(String code) {}
  record Event(Country country) {}
  record Pub(String categoryPath, List<Event> events) {}

  private GraphIndexDefinition<Pub> twoFieldIndex() {
    return GraphIndexDefinition.<Pub>named("by-country-cat")
        .traverseMany("country", Pub::events,
            event -> event.country().code())
        .traversePath("category", "/", Pub::categoryPath)
        .hotpath("country", "category")
        .build();
  }

  @Test
  void whenPlanning_givenTwoMatchingFields_shouldSelectFullHotpath() {
    final var index = twoFieldIndex();
    final Map<String, GraphQueryCondition> conditions = new LinkedHashMap<>();
    conditions.put("country", new GraphQueryCondition("country",
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{"AR"}));
    conditions.put("category", new GraphQueryCondition("category",
        GraphQueryCondition.Operation.EQUAL_TO,
        new Object[]{"deportes/futbol"}));

    final QueryPlanner.Plan<Pub> plan =
        QueryPlanner.plan(List.of(index), conditions);

    assertEquals("by-country-cat", plan.graphIndexName());
    assertEquals(CompositeKey.of("AR", "deportes/futbol"), plan.key());
    assertTrue(plan.postFilterConditions().isEmpty());
  }

  @Test
  void whenPlanning_givenOneMatchingField_shouldSelectPrefixHotpath() {
    final var index = twoFieldIndex();
    final Map<String, GraphQueryCondition> conditions = new LinkedHashMap<>();
    conditions.put("country", new GraphQueryCondition("country",
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{"AR"}));

    final QueryPlanner.Plan<Pub> plan =
        QueryPlanner.plan(List.of(index), conditions);

    assertEquals(CompositeKey.of("AR"), plan.key());
    assertTrue(plan.postFilterConditions().isEmpty());
  }

  @Test
  void whenPlanning_givenNoMatchingFields_shouldReturnNull() {
    final var index = twoFieldIndex();
    final Map<String, GraphQueryCondition> conditions = new LinkedHashMap<>();
    conditions.put("unknown", new GraphQueryCondition("unknown",
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{"X"}));

    final QueryPlanner.Plan<Pub> plan =
        QueryPlanner.plan(List.of(index), conditions);

    assertNull(plan);
  }

  @Test
  void whenPlanning_givenPartialMatch_shouldPostFilter() {
    final var index = twoFieldIndex();
    final Map<String, GraphQueryCondition> conditions = new LinkedHashMap<>();
    conditions.put("country", new GraphQueryCondition("country",
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{"AR"}));
    conditions.put("organizer", new GraphQueryCondition("organizer",
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{"AFA"}));

    final QueryPlanner.Plan<Pub> plan =
        QueryPlanner.plan(List.of(index), conditions);

    assertEquals(CompositeKey.of("AR"), plan.key());
    assertEquals(1, plan.postFilterConditions().size());
    assertEquals("organizer",
        plan.postFilterConditions().getFirst().fieldName());
  }

  @Test
  void whenPlanning_givenMultipleIndexes_shouldSelectBestCoverage() {
    final var index1 = twoFieldIndex();
    final var index2 = GraphIndexDefinition.<Pub>named("by-country-only")
        .traverse("country",
            pub -> pub.events().getFirst().country().code())
        .hotpath("country")
        .build();

    final Map<String, GraphQueryCondition> conditions = new LinkedHashMap<>();
    conditions.put("country", new GraphQueryCondition("country",
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{"AR"}));
    conditions.put("category", new GraphQueryCondition("category",
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{"deportes"}));

    final QueryPlanner.Plan<Pub> plan =
        QueryPlanner.plan(List.of(index1, index2), conditions);

    // Should pick index1 (covers 2 fields) over index2 (covers 1)
    assertEquals("by-country-cat", plan.graphIndexName());
    assertEquals(CompositeKey.of("AR", "deportes"), plan.key());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=QueryPlannerTest -q`
Expected: FAIL -- `QueryPlanner` class does not exist.

- [ ] **Step 3: Write the implementations**

`GraphQueryCondition.java`:
```java
package org.waabox.andersoni;

import java.util.Objects;

/**
 * Represents a query condition for graph-based composite index queries.
 *
 * <p>References traversal field names (e.g., "country", "category") rather
 * than index names. This is distinct from the existing {@link Condition}
 * used by {@link CompoundQuery}.
 *
 * @param fieldName the traversal field name, never null
 * @param operation the operation to apply, never null
 * @param args      the operation arguments, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
record GraphQueryCondition(String fieldName, Operation operation,
    Object[] args) {

  enum Operation {
    EQUAL_TO
  }

  GraphQueryCondition {
    Objects.requireNonNull(fieldName, "fieldName must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(args, "args must not be null");
  }
}
```

`QueryPlanner.java`:
```java
package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Selects the best graph index and hotpath for a set of query conditions.
 *
 * <p>The planner evaluates each hotpath in each graph index, computing the
 * longest usable prefix (contiguous fields from the left covered by eq
 * conditions). It picks the hotpath with the highest score (most fields
 * covered), builds the {@link CompositeKey}, and returns the plan.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class QueryPlanner {

  /** Not instantiable. */
  private QueryPlanner() {
  }

  /**
   * The result of query planning: which index/hotpath to use, the
   * composite key to look up, and any conditions not covered by the
   * hotpath (to apply as post-filter).
   *
   * @param <T> the item type
   */
  public static final class Plan<T> {

    /** The graph index name. */
    private final String graphIndexName;

    /** The composite key for HashMap lookup. */
    private final CompositeKey key;

    /** Conditions not covered by the hotpath. */
    private final List<GraphQueryCondition> postFilterConditions;

    /**
     * Creates a new plan.
     *
     * @param graphIndexName       the selected graph index name
     * @param key                  the composite key to look up
     * @param postFilterConditions uncovered conditions for post-filter
     */
    Plan(final String graphIndexName, final CompositeKey key,
        final List<GraphQueryCondition> postFilterConditions) {
      this.graphIndexName = graphIndexName;
      this.key = key;
      this.postFilterConditions = Collections.unmodifiableList(
          postFilterConditions);
    }

    /**
     * Returns the selected graph index name.
     *
     * @return the index name, never null
     */
    public String graphIndexName() {
      return graphIndexName;
    }

    /**
     * Returns the composite key to look up.
     *
     * @return the key, never null
     */
    public CompositeKey key() {
      return key;
    }

    /**
     * Returns the conditions not covered by the hotpath, to be applied
     * as post-filter.
     *
     * @return unmodifiable list, never null
     */
    public List<GraphQueryCondition> postFilterConditions() {
      return postFilterConditions;
    }
  }

  /**
   * Plans the query execution by selecting the best index and hotpath.
   *
   * @param graphIndexes the available graph indexes, never null
   * @param conditions   the query conditions by field name, never null
   * @param <T>          the item type
   *
   * @return the plan, or null if no graph index covers any condition
   */
  public static <T> Plan<T> plan(
      final List<GraphIndexDefinition<T>> graphIndexes,
      final Map<String, GraphQueryCondition> conditions) {
    Objects.requireNonNull(graphIndexes, "graphIndexes must not be null");
    Objects.requireNonNull(conditions, "conditions must not be null");

    String bestIndexName = null;
    List<String> bestCoveredFields = null;
    int bestScore = 0;

    for (final GraphIndexDefinition<T> graphIndex : graphIndexes) {
      for (final Hotpath hotpath : graphIndex.hotpaths()) {
        final List<String> covered = new ArrayList<>();
        for (final String fieldName : hotpath.fieldNames()) {
          final GraphQueryCondition condition =
              conditions.get(fieldName);
          if (condition == null) {
            break;
          }
          if (condition.operation()
              != GraphQueryCondition.Operation.EQUAL_TO) {
            break;
          }
          covered.add(fieldName);
        }
        if (covered.size() > bestScore) {
          bestScore = covered.size();
          bestIndexName = graphIndex.name();
          bestCoveredFields = covered;
        }
      }
    }

    if (bestScore == 0) {
      return null;
    }

    // Build composite key from covered fields
    final Object[] components = new Object[bestCoveredFields.size()];
    for (int i = 0; i < bestCoveredFields.size(); i++) {
      final GraphQueryCondition cond =
          conditions.get(bestCoveredFields.get(i));
      components[i] = cond.args()[0];
    }
    final CompositeKey key = CompositeKey.of(components);

    // Collect uncovered conditions for post-filter
    final List<GraphQueryCondition> postFilter = new ArrayList<>();
    for (final Map.Entry<String, GraphQueryCondition> entry
        : conditions.entrySet()) {
      if (!bestCoveredFields.contains(entry.getKey())) {
        postFilter.add(entry.getValue());
      }
    }

    return new Plan<>(bestIndexName, key, postFilter);
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=QueryPlannerTest -q`
Expected: PASS (all 5 tests).

- [ ] **Step 5: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/GraphQueryCondition.java \
        andersoni-core/src/main/java/org/waabox/andersoni/QueryPlanner.java \
        andersoni-core/src/test/java/org/waabox/andersoni/QueryPlannerTest.java
git commit -m "Add QueryPlanner and GraphQueryCondition for graph index query resolution"
```

---

### Task 6: GraphQueryBuilder and GraphQueryConditionStep

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/GraphQueryBuilder.java`
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/GraphQueryConditionStep.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/GraphQueryBuilderTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GraphQueryBuilderTest {

  record Country(String code) {}
  record Event(Country country) {}
  record Pub(String name, String categoryPath, List<Event> events) {}

  private GraphIndexDefinition<Pub> buildIndex() {
    return GraphIndexDefinition.<Pub>named("by-country-cat")
        .traverseMany("country", Pub::events,
            event -> event.country().code())
        .traversePath("category", "/", Pub::categoryPath)
        .hotpath("country", "category")
        .build();
  }

  private Snapshot<Pub> buildSnapshot(final List<Pub> data,
      final GraphIndexDefinition<Pub> index) {
    final Map<Object, List<Pub>> builtIndex = index.buildIndex(data);
    final Map<String, Map<Object, List<Pub>>> indices =
        Map.of(index.name(), builtIndex);
    return Snapshot.of(data, indices, 1L, "test-hash");
  }

  @Test
  void whenQuerying_givenCountryOnly_shouldReturnMatches() {
    final var index = buildIndex();
    final Pub pub1 = new Pub("p1", "deportes",
        List.of(new Event(new Country("AR"))));
    final Pub pub2 = new Pub("p2", "deportes",
        List.of(new Event(new Country("MX"))));

    final Snapshot<Pub> snapshot = buildSnapshot(
        List.of(pub1, pub2), index);

    final GraphQueryBuilder<Pub> builder = new GraphQueryBuilder<>(
        snapshot, "test", List.of(index));
    final List<Pub> result = builder
        .where("country").eq("AR")
        .execute();

    assertEquals(1, result.size());
    assertTrue(result.contains(pub1));
  }

  @Test
  void whenQuerying_givenCountryAndCategory_shouldReturnMatches() {
    final var index = buildIndex();
    final Pub pub1 = new Pub("p1", "deportes/futbol",
        List.of(new Event(new Country("AR"))));
    final Pub pub2 = new Pub("p2", "deportes/basket",
        List.of(new Event(new Country("AR"))));

    final Snapshot<Pub> snapshot = buildSnapshot(
        List.of(pub1, pub2), index);

    final GraphQueryBuilder<Pub> builder = new GraphQueryBuilder<>(
        snapshot, "test", List.of(index));
    final List<Pub> result = builder
        .where("country").eq("AR")
        .and("category").eq("deportes/futbol")
        .execute();

    assertEquals(1, result.size());
    assertTrue(result.contains(pub1));
  }

  @Test
  void whenQuerying_givenCategoryPrefix_shouldReturnMatchesAtThatLevel() {
    final var index = buildIndex();
    final Pub pub1 = new Pub("p1", "deportes/futbol",
        List.of(new Event(new Country("AR"))));
    final Pub pub2 = new Pub("p2", "deportes/basket",
        List.of(new Event(new Country("AR"))));
    final Pub pub3 = new Pub("p3", "musica/rock",
        List.of(new Event(new Country("AR"))));

    final Snapshot<Pub> snapshot = buildSnapshot(
        List.of(pub1, pub2, pub3), index);

    final GraphQueryBuilder<Pub> builder = new GraphQueryBuilder<>(
        snapshot, "test", List.of(index));
    final List<Pub> result = builder
        .where("country").eq("AR")
        .and("category").eq("deportes")
        .execute();

    assertEquals(2, result.size());
    assertTrue(result.contains(pub1));
    assertTrue(result.contains(pub2));
  }

  @Test
  void whenQuerying_givenNoMatchingIndex_shouldReturnEmptyList() {
    final var index = buildIndex();
    final Pub pub1 = new Pub("p1", "deportes",
        List.of(new Event(new Country("AR"))));
    final Snapshot<Pub> snapshot = buildSnapshot(
        List.of(pub1), index);

    final GraphQueryBuilder<Pub> builder = new GraphQueryBuilder<>(
        snapshot, "test", List.of(index));
    final List<Pub> result = builder
        .where("unknown").eq("X")
        .execute();

    assertTrue(result.isEmpty());
  }

  @Test
  void whenQuerying_givenUncoveredConditions_shouldThrow() {
    final var index = buildIndex();
    final Pub pub1 = new Pub("p1", "deportes",
        List.of(new Event(new Country("AR"))));
    final Snapshot<Pub> snapshot = buildSnapshot(
        List.of(pub1), index);

    final GraphQueryBuilder<Pub> builder = new GraphQueryBuilder<>(
        snapshot, "test", List.of(index));

    assertThrows(UnsupportedOperationException.class, () -> builder
        .where("country").eq("AR")
        .and("organizer").eq("AFA")
        .execute());
  }

  @Test
  void whenQuerying_givenEmptyConditions_shouldReturnEmptyList() {
    final var index = buildIndex();
    final Pub pub1 = new Pub("p1", "deportes",
        List.of(new Event(new Country("AR"))));
    final Snapshot<Pub> snapshot = buildSnapshot(
        List.of(pub1), index);

    final GraphQueryBuilder<Pub> builder = new GraphQueryBuilder<>(
        snapshot, "test", List.of(index));
    final List<Pub> result = builder.execute();

    assertTrue(result.isEmpty());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=GraphQueryBuilderTest -q`
Expected: FAIL -- `GraphQueryBuilder` class does not exist.

- [ ] **Step 3: Write the implementations**

`GraphQueryConditionStep.java`:
```java
package org.waabox.andersoni;

import java.util.Objects;

/**
 * Intermediate step for specifying the operation on a graph query condition.
 *
 * @param <T> the item type
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class GraphQueryConditionStep<T> {

  /** The parent builder. */
  private final GraphQueryBuilder<T> builder;

  /** The field name for this condition. */
  private final String fieldName;

  /**
   * Creates a new condition step.
   *
   * @param builder   the parent builder, never null
   * @param fieldName the field name, never null
   */
  GraphQueryConditionStep(final GraphQueryBuilder<T> builder,
      final String fieldName) {
    this.builder = builder;
    this.fieldName = fieldName;
  }

  /**
   * Sets the condition to equality.
   *
   * @param value the value to match, never null
   *
   * @return the parent builder for chaining, never null
   */
  public GraphQueryBuilder<T> eq(final Object value) {
    Objects.requireNonNull(value, "value must not be null");
    builder.addCondition(new GraphQueryCondition(fieldName,
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{value}));
    return builder;
  }
}
```

`GraphQueryBuilder.java`:
```java
package org.waabox.andersoni;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Fluent DSL for building and executing graph-based composite queries.
 *
 * <p>Instances are created via {@code catalog.graphQuery()} and are bound
 * to the snapshot at creation time.
 *
 * @param <T> the item type
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class GraphQueryBuilder<T> {

  /** The snapshot to query against. */
  private final Snapshot<T> snapshot;

  /** The catalog name. */
  private final String catalogName;

  /** The available graph indexes. */
  private final List<GraphIndexDefinition<T>> graphIndexes;

  /** The accumulated conditions. */
  private final Map<String, GraphQueryCondition> conditions;

  /**
   * Creates a new query builder.
   *
   * @param snapshot     the snapshot to query, never null
   * @param catalogName  the catalog name, never null
   * @param graphIndexes the available graph indexes, never null
   */
  GraphQueryBuilder(final Snapshot<T> snapshot, final String catalogName,
      final List<GraphIndexDefinition<T>> graphIndexes) {
    this.snapshot = Objects.requireNonNull(snapshot,
        "snapshot must not be null");
    this.catalogName = Objects.requireNonNull(catalogName,
        "catalogName must not be null");
    this.graphIndexes = Objects.requireNonNull(graphIndexes,
        "graphIndexes must not be null");
    this.conditions = new LinkedHashMap<>();
  }

  /**
   * Begins the query with the first condition.
   *
   * @param fieldName the traversal field name, never null
   *
   * @return the condition step, never null
   */
  public GraphQueryConditionStep<T> where(final String fieldName) {
    Objects.requireNonNull(fieldName, "fieldName must not be null");
    return new GraphQueryConditionStep<>(this, fieldName);
  }

  /**
   * Adds an additional condition.
   *
   * @param fieldName the traversal field name, never null
   *
   * @return the condition step, never null
   */
  public GraphQueryConditionStep<T> and(final String fieldName) {
    Objects.requireNonNull(fieldName, "fieldName must not be null");
    return new GraphQueryConditionStep<>(this, fieldName);
  }

  /**
   * Executes the query by delegating to the query planner.
   *
   * @return an unmodifiable list of matching items, never null
   */
  public List<T> execute() {
    if (conditions.isEmpty()) {
      return Collections.emptyList();
    }

    final QueryPlanner.Plan<T> plan =
        QueryPlanner.plan(graphIndexes, conditions);

    if (plan == null) {
      return Collections.emptyList();
    }

    final List<T> result =
        snapshot.search(plan.graphIndexName(), plan.key());

    if (!plan.postFilterConditions().isEmpty()) {
      final String uncovered = plan.postFilterConditions().stream()
          .map(GraphQueryCondition::fieldName)
          .collect(java.util.stream.Collectors.joining(", "));
      throw new UnsupportedOperationException(
          "Graph query has conditions not covered by any hotpath: ["
              + uncovered + "]. Define a graph index that covers all"
              + " query fields, or use compound() for uncovered fields.");
    }

    return result;
  }

  /**
   * Adds a condition. Package-private, called by
   * {@link GraphQueryConditionStep}.
   *
   * @param condition the condition to add
   */
  void addCondition(final GraphQueryCondition condition) {
    conditions.put(condition.fieldName(), condition);
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=GraphQueryBuilderTest -q`
Expected: PASS (all 6 tests).

- [ ] **Step 5: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/GraphQueryBuilder.java \
        andersoni-core/src/main/java/org/waabox/andersoni/GraphQueryConditionStep.java \
        andersoni-core/src/test/java/org/waabox/andersoni/GraphQueryBuilderTest.java
git commit -m "Add GraphQueryBuilder and GraphQueryConditionStep for graph query DSL"
```

---

## Chunk 4: Catalog Integration

### Task 7: Integrate GraphIndexDefinition into Catalog

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java`
  - Constructor (lines 119-141): add `graphIndexDefinitions` parameter
  - `buildAndSwapSnapshot` (lines 363-400): iterate graph indexes
  - `BuildStep` (lines 546-782): add `graphIndexDefinitions` list,
    `indexGraph()` method, `GraphIndexStep` inner class, `addGraphIndex()`
  - `build()` (lines 715-724): include `graphIndexDefinitions` in validation
  - New method: `graphQuery()` entry point
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/CatalogGraphIndexTest.java`

- [ ] **Step 1: Write the failing integration test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CatalogGraphIndexTest {

  record Country(String code) {}
  record Event(Country country) {}
  record Publication(String name, String categoryPath, List<Event> events) {}

  @Test
  void whenBootstrapping_givenGraphIndex_shouldBuildAndSearch() {
    final Publication pub1 = new Publication("p1", "deportes/futbol",
        List.of(new Event(new Country("AR"))));
    final Publication pub2 = new Publication("p2", "deportes/basket",
        List.of(new Event(new Country("AR"))));
    final Publication pub3 = new Publication("p3", "musica",
        List.of(new Event(new Country("MX"))));

    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of(pub1, pub2, pub3))
        .indexGraph("by-country-cat")
            .traverseMany("country", Publication::events,
                event -> event.country().code())
            .traversePath("category", "/", Publication::categoryPath)
            .hotpath("country", "category")
            .done()
        .build();

    catalog.bootstrap();

    // Query by country only
    final List<Publication> arPubs = catalog.graphQuery()
        .where("country").eq("AR")
        .execute();
    assertEquals(2, arPubs.size());
    assertTrue(arPubs.contains(pub1));
    assertTrue(arPubs.contains(pub2));

    // Query by country + category
    final List<Publication> arDeportes = catalog.graphQuery()
        .where("country").eq("AR")
        .and("category").eq("deportes")
        .execute();
    assertEquals(2, arDeportes.size());

    // Query by country + category/subcategory
    final List<Publication> arFutbol = catalog.graphQuery()
        .where("country").eq("AR")
        .and("category").eq("deportes/futbol")
        .execute();
    assertEquals(1, arFutbol.size());
    assertTrue(arFutbol.contains(pub1));

    // Query by MX
    final List<Publication> mxPubs = catalog.graphQuery()
        .where("country").eq("MX")
        .execute();
    assertEquals(1, mxPubs.size());
    assertTrue(mxPubs.contains(pub3));
  }

  @Test
  void whenBootstrapping_givenGraphIndexWithRegularIndex_shouldBothWork() {
    final Publication pub1 = new Publication("p1", "deportes",
        List.of(new Event(new Country("AR"))));

    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of(pub1))
        .index("by-name").by(Publication::name, java.util.function.Function.identity())
        .indexGraph("by-country-cat")
            .traverseMany("country", Publication::events,
                event -> event.country().code())
            .traversePath("category", "/", Publication::categoryPath)
            .hotpath("country", "category")
            .done()
        .build();

    catalog.bootstrap();

    // Regular index still works
    assertEquals(List.of(pub1), catalog.search("by-name", "p1"));

    // Graph index works
    final List<Publication> result = catalog.graphQuery()
        .where("country").eq("AR")
        .execute();
    assertEquals(List.of(pub1), result);
  }

  @Test
  void whenBuilding_givenOnlyGraphIndex_shouldSucceed() {
    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of())
        .indexGraph("by-country")
            .traverse("country",
                pub -> pub.events().isEmpty() ? null
                    : pub.events().getFirst().country().code())
            .hotpath("country")
            .done()
        .build();

    catalog.bootstrap();
    assertTrue(catalog.graphQuery().where("country").eq("AR")
        .execute().isEmpty());
  }

  @Test
  void whenRefreshing_givenNewData_shouldUpdateGraphIndex() {
    final Publication pub1 = new Publication("p1", "deportes",
        List.of(new Event(new Country("AR"))));

    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of(pub1))
        .indexGraph("by-country-cat")
            .traverseMany("country", Publication::events,
                event -> event.country().code())
            .traversePath("category", "/", Publication::categoryPath)
            .hotpath("country", "category")
            .done()
        .build();

    catalog.bootstrap();

    assertEquals(1, catalog.graphQuery()
        .where("country").eq("AR").execute().size());

    // Refresh with new data
    final Publication pub2 = new Publication("p2", "deportes",
        List.of(new Event(new Country("AR"))));
    catalog.refresh(List.of(pub1, pub2));

    assertEquals(2, catalog.graphQuery()
        .where("country").eq("AR").execute().size());
  }

  @Test
  void whenBuilding_givenDuplicateGraphIndexName_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        Catalog.of(Publication.class)
            .named("publications")
            .data(List.of())
            .indexGraph("same-name")
                .traverse("country", pub -> "AR")
                .hotpath("country")
                .done()
            .indexGraph("same-name")
                .traverse("x", pub -> "X")
                .hotpath("x")
                .done()
            .build());
  }

  @Test
  void whenBuilding_givenGraphIndexNameCollidesWithRegularIndex_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        Catalog.of(Publication.class)
            .named("publications")
            .data(List.of())
            .index("by-country").by(Publication::name,
                java.util.function.Function.identity())
            .indexGraph("by-country")
                .traverse("country", pub -> "AR")
                .hotpath("country")
                .done()
            .build());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=CatalogGraphIndexTest -q`
Expected: FAIL -- `indexGraph()` method does not exist on `BuildStep`.

- [ ] **Step 3: Modify Catalog.java**

Apply these changes to `Catalog.java`:

**3a. Add field to Catalog (after line 86):**
```java
  /** The graph index definitions for this catalog. */
  private final List<GraphIndexDefinition<T>> graphIndexDefinitions;
```

**3b. Update constructor signature and body (lines 119-141):**

Add `final List<GraphIndexDefinition<T>> graphIndexDefinitions` parameter after `multiKeyIndexDefinitions`. Add:
```java
    this.graphIndexDefinitions = Collections.unmodifiableList(
        new ArrayList<>(graphIndexDefinitions));
```

**3c. Add graphQuery() method (after compound() method, after line 288):**
```java
  /**
   * Creates a graph query builder bound to the current snapshot.
   *
   * <p>The returned {@link GraphQueryBuilder} uses the query planner to
   * select the best graph index and hotpath for the given conditions.
   * The query is bound to the snapshot that is current at invocation time.
   *
   * @return a GraphQueryBuilder bound to the current snapshot, never null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public GraphQueryBuilder<T> graphQuery() {
    return new GraphQueryBuilder<>(current.get(), name,
        graphIndexDefinitions);
  }
```

**3d. Update buildAndSwapSnapshot (after line 392, before computing version):**
```java
    // Build graph indices.
    for (final GraphIndexDefinition<T> graphDef : graphIndexDefinitions) {
      indices.put(graphDef.name(), graphDef.buildIndex(data));
    }
```

**3e. Add field to BuildStep (after line 570):**
```java
    /** The accumulated graph index definitions. */
    private final List<GraphIndexDefinition<T>> graphIndexDefinitions;
```

**3f. Initialize in BuildStep constructor (after line 591):**
```java
      this.graphIndexDefinitions = new ArrayList<>();
```

**3g. Add indexGraph() method to BuildStep (after indexMulti method, after line 703):**
```java
    /**
     * Starts defining a new graph-based composite index with the given
     * name.
     *
     * <p>Returns a {@link GraphIndexStep} for defining traversals and
     * hotpaths. Call {@link GraphIndexStep#done()} to return to this
     * builder.
     *
     * @param indexName the graph index name, never null or empty
     *
     * @return a GraphIndexStep for defining the graph index, never null
     *
     * @throws NullPointerException     if indexName is null
     * @throws IllegalArgumentException if indexName is empty or duplicate
     *
     * @author waabox(waabox[at]gmail[dot]com)
     */
    public GraphIndexStep<T> indexGraph(final String indexName) {
      Objects.requireNonNull(indexName, "indexName must not be null");
      if (indexName.isEmpty()) {
        throw new IllegalArgumentException("indexName must not be empty");
      }
      if (!indexNames.add(indexName)) {
        throw new IllegalArgumentException(
            "Duplicate index name: '" + indexName + "'");
      }
      return new GraphIndexStep<>(this, indexName);
    }
```

**3h. Update build() validation (line 716-717) to include graphIndexDefinitions:**
```java
      if (indexDefinitions.isEmpty() && sortedIndexDefinitions.isEmpty()
          && multiKeyIndexDefinitions.isEmpty()
          && graphIndexDefinitions.isEmpty()) {
```

**3i. Update build() return (line 721-723) to pass graphIndexDefinitions:**
```java
      return new Catalog<>(name, dataLoader, initialData,
          indexDefinitions, sortedIndexDefinitions,
          multiKeyIndexDefinitions, graphIndexDefinitions,
          serializer, refreshInterval);
```

**3j. Add addGraphIndex method to BuildStep (after addMultiKeyIndex):**
```java
    /**
     * Adds a graph index definition to the builder. Package-private,
     * called by {@link GraphIndexStep}.
     *
     * @param graphIndexDefinition the definition to add, never null
     */
    void addGraphIndex(
        final GraphIndexDefinition<T> graphIndexDefinition) {
      graphIndexDefinitions.add(graphIndexDefinition);
    }
```

**3k. Add GraphIndexStep inner class (after MultiKeyIndexStep, before closing brace of Catalog):**
```java
  /**
   * Intermediate builder step for defining a graph-based composite index.
   *
   * <p>Accumulates traversals and hotpaths, then returns control to the
   * parent {@link BuildStep} via {@link #done()}.
   *
   * @param <T> the type of data items
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static final class GraphIndexStep<T> {

    /** The parent builder step. */
    private final BuildStep<T> buildStep;

    /** The underlying GraphIndexDefinition builder. */
    private final GraphIndexDefinition.Builder<T> graphBuilder;

    /**
     * Creates a new GraphIndexStep.
     *
     * @param buildStep the parent builder step, never null
     * @param indexName the graph index name, never null
     */
    private GraphIndexStep(final BuildStep<T> buildStep,
        final String indexName) {
      this.buildStep = buildStep;
      this.graphBuilder = GraphIndexDefinition.named(indexName);
    }

    /**
     * Adds a simple traversal.
     *
     * @param traversalName the traversal name, never null or empty
     * @param extractor     the value extractor, never null
     *
     * @return this step for chaining, never null
     */
    public GraphIndexStep<T> traverse(final String traversalName,
        final java.util.function.Function<T, ?> extractor) {
      graphBuilder.traverse(traversalName, extractor);
      return this;
    }

    /**
     * Adds a fan-out traversal.
     *
     * @param traversalName      the traversal name, never null or empty
     * @param collectionAccessor the collection accessor, never null
     * @param valueExtractor     the value extractor, never null
     * @param <C>                the collection element type
     *
     * @return this step for chaining, never null
     */
    public <C> GraphIndexStep<T> traverseMany(
        final String traversalName,
        final java.util.function.Function<T,
            ? extends java.util.Collection<C>> collectionAccessor,
        final java.util.function.Function<C, ?> valueExtractor) {
      graphBuilder.traverseMany(traversalName, collectionAccessor,
          valueExtractor);
      return this;
    }

    /**
     * Adds a path traversal.
     *
     * @param traversalName the traversal name, never null or empty
     * @param separator     the path separator, never null or empty
     * @param extractor     the path string extractor, never null
     *
     * @return this step for chaining, never null
     */
    public GraphIndexStep<T> traversePath(final String traversalName,
        final String separator,
        final java.util.function.Function<T, String> extractor) {
      graphBuilder.traversePath(traversalName, separator, extractor);
      return this;
    }

    /**
     * Declares a hotpath to pre-compute.
     *
     * @param fieldNames the ordered traversal names
     *
     * @return this step for chaining, never null
     */
    public GraphIndexStep<T> hotpath(final String... fieldNames) {
      graphBuilder.hotpath(fieldNames);
      return this;
    }

    /**
     * Sets the maximum number of keys per item.
     *
     * @param max the max, must be positive
     *
     * @return this step for chaining, never null
     */
    public GraphIndexStep<T> maxKeysPerItem(final int max) {
      graphBuilder.maxKeysPerItem(max);
      return this;
    }

    /**
     * Finalizes the graph index definition and returns to the parent
     * builder.
     *
     * @return the parent BuildStep for further chaining, never null
     */
    public BuildStep<T> done() {
      buildStep.addGraphIndex(graphBuilder.build());
      return buildStep;
    }
  }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=CatalogGraphIndexTest -q`
Expected: PASS (all 6 tests).

- [ ] **Step 5: Run full test suite to verify no regressions**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -q`
Expected: PASS (all existing tests + new tests).

- [ ] **Step 6: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java \
        andersoni-core/src/test/java/org/waabox/andersoni/CatalogGraphIndexTest.java
git commit -m "Integrate GraphIndexDefinition into Catalog with graphQuery() DSL"
```

---

## Chunk 5: Memory Estimation Update

### Task 8: Update Snapshot.estimateKeySize for CompositeKey

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Snapshot.java:783-790`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/SnapshotCompositeKeyEstimationTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SnapshotCompositeKeyEstimationTest {

  @Test
  void whenEstimatingIndexInfo_givenCompositeKeys_shouldEstimateAccurately() {
    final Map<Object, List<String>> indexMap = Map.of(
        CompositeKey.of("AR", "deportes"), List.of("item1"),
        CompositeKey.of("AR", "futbol"), List.of("item2"));

    final Snapshot<String> snapshot = Snapshot.of(
        List.of("item1", "item2"),
        Map.of("test-index", indexMap),
        1L, "hash");

    final List<IndexInfo> infos = snapshot.indexInfo();
    final IndexInfo info = infos.getFirst();

    // CompositeKey with 2 string components ("AR" len=2, "deportes" len=8):
    // Each key: 40 (CK overhead) + (40+2) + (40+8) = 130 bytes
    // With 2 keys: total key size > 260
    // DEFAULT_KEY_SIZE (50) * 2 = 100 would be the old underestimate
    // So estimated bytes should be meaningfully larger than with default
    assertTrue(info.estimatedSizeBytes() > 200,
        "CompositeKey estimation should be > 200 bytes for 2 keys"
            + " with string components, got: "
            + info.estimatedSizeBytes());
  }
}
```

- [ ] **Step 2: Run test to verify current behavior (baseline)**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=SnapshotCompositeKeyEstimationTest -q`
Expected: PASS (but with underestimated values -- this establishes the baseline).

- [ ] **Step 3: Update estimateKeySize in Snapshot.java**

Add this case before the `Number` check in `estimateKeySize` (line 784):

```java
    if (key instanceof CompositeKey ck) {
      long size = 40L; // object header + List overhead + hash field
      for (final Object component : ck.components()) {
        size += estimateKeySize(component);
      }
      return size;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=SnapshotCompositeKeyEstimationTest -q`
Expected: PASS.

- [ ] **Step 5: Run full test suite**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -q`
Expected: PASS (all tests).

- [ ] **Step 6: Commit**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Snapshot.java \
        andersoni-core/src/test/java/org/waabox/andersoni/SnapshotCompositeKeyEstimationTest.java
git commit -m "Update Snapshot memory estimation to handle CompositeKey"
```

---

## Chunk 6: Final Verification

### Task 9: Full build verification

- [ ] **Step 1: Run full project build**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn clean verify -q`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Verify all new files are committed**

Run: `git status`
Expected: clean working tree.

---

## File Map Summary

### New Files (10)

| File | Purpose |
|------|---------|
| `CompositeKey.java` | Multi-component HashMap key |
| `CompositeKeyTest.java` | Tests for CompositeKey |
| `Traversal.java` | Sealed interface: Simple, FanOut, Path |
| `TraversalTest.java` | Tests for all traversal types |
| `Hotpath.java` | Ordered field name declaration |
| `HotpathTest.java` | Tests for Hotpath |
| `IndexKeyLimitExceededException.java` | Safety limit exception |
| `GraphIndexDefinition.java` | Core index engine with builder |
| `GraphIndexDefinitionTest.java` | Tests for graph index building |
| `GraphQueryCondition.java` | Condition record for graph queries |
| `QueryPlanner.java` | Selects best hotpath for conditions |
| `QueryPlannerTest.java` | Tests for query planning |
| `GraphQueryBuilder.java` | Fluent query DSL |
| `GraphQueryConditionStep.java` | Intermediate DSL step |
| `GraphQueryBuilderTest.java` | Tests for query DSL |
| `CatalogGraphIndexTest.java` | Integration tests |
| `SnapshotCompositeKeyEstimationTest.java` | Memory estimation test |

### Modified Files (2)

| File | Change |
|------|--------|
| `Catalog.java` | `graphIndexDefinitions` field, `graphQuery()`, `indexGraph()` DSL, `GraphIndexStep` |
| `Snapshot.java` | `estimateKeySize` handles `CompositeKey` |
