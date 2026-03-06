# Compound Query & Multi-Key Index Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add compound query support (intersect results from multiple indices) and multi-key index support (index one item under multiple keys) to andersoni-core.

**Architecture:** New `CompoundQuery<T>` accumulates conditions and evaluates them lazily with short-circuit intersection using `IdentityHashMap`. New `MultiKeyIndexDefinition<T>` produces the same `Map<Object, List<T>>` as `IndexDefinition`, so `Snapshot` requires no changes. Entry points added to both `Catalog` and `Andersoni`.

**Tech Stack:** Java 21, JUnit 5, Maven. Project root: `/Users/waabox/code/waabox/andersoni`. Module: `andersoni-core`.

**Run tests:** `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core`

**Existing tests:** 210 (all pass). Must remain green after every task.

---

### Task 1: Condition record (package-private)

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/Condition.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/ConditionTest.java`

**Step 1: Write the test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class ConditionTest {

  @Test
  void whenCreating_givenValidArgs_shouldStoreFields() {
    final Condition condition = new Condition(
        "by-country", Condition.Operation.EQUAL_TO,
        new Object[]{"AR"});

    assertEquals("by-country", condition.indexName());
    assertEquals(Condition.Operation.EQUAL_TO, condition.operation());
    assertNotNull(condition.args());
    assertEquals("AR", condition.args()[0]);
  }

  @Test
  void whenCreating_givenBetween_shouldStoreTwoArgs() {
    final Condition condition = new Condition(
        "by-date", Condition.Operation.BETWEEN,
        new Object[]{"2026-01-01", "2026-12-31"});

    assertEquals(Condition.Operation.BETWEEN, condition.operation());
    assertEquals("2026-01-01", condition.args()[0]);
    assertEquals("2026-12-31", condition.args()[1]);
  }
}
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=ConditionTest -q`
Expected: FAIL — `Condition` class does not exist

**Step 3: Write the implementation**

```java
package org.waabox.andersoni;

import java.util.Objects;

/**
 * Represents a single query condition within a {@link CompoundQuery}.
 *
 * <p>Each condition targets a named index and specifies an operation with
 * its arguments. Conditions are accumulated by the compound query builder
 * and evaluated lazily during {@link CompoundQuery#execute()}.
 *
 * <p>This is a package-private implementation detail not exposed to
 * application code.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
record Condition(String indexName, Operation operation, Object[] args) {

  /**
   * The supported query operations.
   */
  enum Operation {
    EQUAL_TO,
    BETWEEN,
    GREATER_THAN,
    GREATER_OR_EQUAL,
    LESS_THAN,
    LESS_OR_EQUAL,
    STARTS_WITH,
    ENDS_WITH,
    CONTAINS
  }

  Condition {
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(args, "args must not be null");
  }
}
```

**Step 4: Run test to verify it passes**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=ConditionTest -q`
Expected: PASS

**Step 5: Run all tests**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -q`
Expected: 212 tests pass (210 existing + 2 new)

**Step 6: Commit**

```bash
cd /Users/waabox/code/waabox/andersoni
git add andersoni-core/src/main/java/org/waabox/andersoni/Condition.java \
        andersoni-core/src/test/java/org/waabox/andersoni/ConditionTest.java
git commit -m "Add Condition record for compound query operations"
```

---

### Task 2: CompoundConditionStep

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/CompoundConditionStep.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/CompoundConditionStepTest.java`

**Step 1: Write the test**

`CompoundConditionStep` needs a `CompoundQuery` to return to. Since `CompoundQuery` doesn't exist yet, create a minimal test that verifies each method registers the correct `Condition`. We can test this by making `CompoundQuery` a simple stub that captures conditions. But since they're tightly coupled, we'll build `CompoundQuery` skeleton first in this task (just the `addCondition` and `conditions()` accessor), then flesh it out in Task 3.

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CompoundConditionStepTest {

  private CompoundQuery<String> createQuery() {
    final Map<Object, List<String>> byCountry = new HashMap<>();
    byCountry.put("AR", List.of("item1", "item2"));
    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("by-country", byCountry);
    final Snapshot<String> snapshot = Snapshot.of(
        List.of("item1", "item2"), indices, 1L, "hash");
    return new CompoundQuery<>(snapshot, "test-catalog");
  }

  @Test
  void whenCallingEqualTo_shouldRegisterCondition() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step =
        new CompoundConditionStep<>(query, "by-country");

    final CompoundQuery<String> result = step.equalTo("AR");

    assertNotNull(result);
    assertEquals(1, query.conditions().size());
    assertEquals(Condition.Operation.EQUAL_TO,
        query.conditions().get(0).operation());
  }

  @Test
  void whenCallingBetween_shouldRegisterCondition() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step =
        new CompoundConditionStep<>(query, "by-country");

    step.between("A", "Z");

    assertEquals(Condition.Operation.BETWEEN,
        query.conditions().get(0).operation());
    assertEquals("A", query.conditions().get(0).args()[0]);
    assertEquals("Z", query.conditions().get(0).args()[1]);
  }

  @Test
  void whenCallingGreaterThan_shouldRegisterCondition() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step =
        new CompoundConditionStep<>(query, "by-country");

    step.greaterThan("M");

    assertEquals(Condition.Operation.GREATER_THAN,
        query.conditions().get(0).operation());
  }

  @Test
  void whenCallingGreaterOrEqual_shouldRegisterCondition() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step =
        new CompoundConditionStep<>(query, "by-country");

    step.greaterOrEqual("M");

    assertEquals(Condition.Operation.GREATER_OR_EQUAL,
        query.conditions().get(0).operation());
  }

  @Test
  void whenCallingLessThan_shouldRegisterCondition() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step =
        new CompoundConditionStep<>(query, "by-country");

    step.lessThan("M");

    assertEquals(Condition.Operation.LESS_THAN,
        query.conditions().get(0).operation());
  }

  @Test
  void whenCallingLessOrEqual_shouldRegisterCondition() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step =
        new CompoundConditionStep<>(query, "by-country");

    step.lessOrEqual("M");

    assertEquals(Condition.Operation.LESS_OR_EQUAL,
        query.conditions().get(0).operation());
  }

  @Test
  void whenCallingStartsWith_shouldRegisterCondition() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step =
        new CompoundConditionStep<>(query, "by-country");

    step.startsWith("A");

    assertEquals(Condition.Operation.STARTS_WITH,
        query.conditions().get(0).operation());
  }

  @Test
  void whenCallingEndsWith_shouldRegisterCondition() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step =
        new CompoundConditionStep<>(query, "by-country");

    step.endsWith("A");

    assertEquals(Condition.Operation.ENDS_WITH,
        query.conditions().get(0).operation());
  }

  @Test
  void whenCallingContains_shouldRegisterCondition() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step =
        new CompoundConditionStep<>(query, "by-country");

    step.contains("R");

    assertEquals(Condition.Operation.CONTAINS,
        query.conditions().get(0).operation());
  }

  @Test
  void whenCallingEqualTo_givenNull_shouldThrowNpe() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step =
        new CompoundConditionStep<>(query, "by-country");

    assertThrows(NullPointerException.class, () -> step.equalTo(null));
  }

  @Test
  void whenCallingBetween_givenNullFrom_shouldThrowNpe() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step =
        new CompoundConditionStep<>(query, "by-country");

    assertThrows(NullPointerException.class, () -> step.between(null, "Z"));
  }

  @Test
  void whenCallingBetween_givenNullTo_shouldThrowNpe() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step =
        new CompoundConditionStep<>(query, "by-country");

    assertThrows(NullPointerException.class, () -> step.between("A", null));
  }
}
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=CompoundConditionStepTest -q`
Expected: FAIL — classes do not exist

**Step 3: Write CompoundQuery skeleton (just condition accumulation)**

```java
package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A compound query that accumulates multiple conditions across different
 * indices and evaluates them with short-circuit intersection.
 *
 * <p>Instances are created via {@link Catalog#compound()} or
 * {@link Andersoni#compound(String, Class)} and are bound to the snapshot
 * at creation time.
 *
 * @param <T> the type of data items in the underlying snapshot
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class CompoundQuery<T> {

  private final Snapshot<T> snapshot;
  private final String catalogName;
  private final List<Condition> conditions;
  private boolean whereAdded;

  CompoundQuery(final Snapshot<T> snapshot, final String catalogName) {
    this.snapshot = Objects.requireNonNull(snapshot,
        "snapshot must not be null");
    this.catalogName = Objects.requireNonNull(catalogName,
        "catalogName must not be null");
    this.conditions = new ArrayList<>();
    this.whereAdded = false;
  }

  /**
   * Starts the first condition of the compound query.
   *
   * @param indexName the index to query, never null
   * @return a condition step for specifying the operation, never null
   * @throws IllegalStateException if where() was already called
   */
  public CompoundConditionStep<T> where(final String indexName) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    if (whereAdded) {
      throw new IllegalStateException(
          "where() has already been called. Use and() for additional conditions");
    }
    whereAdded = true;
    return new CompoundConditionStep<>(this, indexName);
  }

  /**
   * Adds an additional condition to the compound query.
   *
   * @param indexName the index to query, never null
   * @return a condition step for specifying the operation, never null
   */
  public CompoundConditionStep<T> and(final String indexName) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    return new CompoundConditionStep<>(this, indexName);
  }

  /**
   * Executes all conditions with short-circuit intersection.
   *
   * @return an unmodifiable list of matching items, never null
   * @throws IllegalStateException if no conditions were added
   */
  public List<T> execute() {
    // Will be implemented in Task 3
    throw new UnsupportedOperationException("Not yet implemented");
  }

  CompoundQuery<T> addCondition(final Condition condition) {
    conditions.add(condition);
    return this;
  }

  List<Condition> conditions() {
    return Collections.unmodifiableList(conditions);
  }
}
```

**Step 4: Write CompoundConditionStep**

```java
package org.waabox.andersoni;

import java.util.Objects;

/**
 * Fluent step within a {@link CompoundQuery} that specifies the query
 * operation for a single index condition.
 *
 * <p>Each method registers a {@link Condition} and returns the parent
 * {@link CompoundQuery} for further chaining.
 *
 * @param <T> the type of data items in the underlying snapshot
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class CompoundConditionStep<T> {

  private final CompoundQuery<T> query;
  private final String indexName;

  CompoundConditionStep(final CompoundQuery<T> query,
      final String indexName) {
    this.query = Objects.requireNonNull(query, "query must not be null");
    this.indexName = Objects.requireNonNull(indexName,
        "indexName must not be null");
  }

  /**
   * Matches items whose indexed key equals the given value.
   *
   * @param key the key to match, never null
   * @return the compound query for further chaining, never null
   */
  public CompoundQuery<T> equalTo(final Object key) {
    Objects.requireNonNull(key, "key must not be null");
    return query.addCondition(new Condition(
        indexName, Condition.Operation.EQUAL_TO, new Object[]{key}));
  }

  /**
   * Matches items whose indexed key falls within [from, to] inclusive.
   *
   * @param from the inclusive lower bound, never null
   * @param to   the inclusive upper bound, never null
   * @return the compound query for further chaining, never null
   */
  public CompoundQuery<T> between(final Comparable<?> from,
      final Comparable<?> to) {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    return query.addCondition(new Condition(
        indexName, Condition.Operation.BETWEEN, new Object[]{from, to}));
  }

  /**
   * Matches items whose indexed key is strictly greater than the value.
   *
   * @param key the exclusive lower bound, never null
   * @return the compound query for further chaining, never null
   */
  public CompoundQuery<T> greaterThan(final Comparable<?> key) {
    Objects.requireNonNull(key, "key must not be null");
    return query.addCondition(new Condition(
        indexName, Condition.Operation.GREATER_THAN, new Object[]{key}));
  }

  /**
   * Matches items whose indexed key is greater than or equal to the value.
   *
   * @param key the inclusive lower bound, never null
   * @return the compound query for further chaining, never null
   */
  public CompoundQuery<T> greaterOrEqual(final Comparable<?> key) {
    Objects.requireNonNull(key, "key must not be null");
    return query.addCondition(new Condition(
        indexName, Condition.Operation.GREATER_OR_EQUAL, new Object[]{key}));
  }

  /**
   * Matches items whose indexed key is strictly less than the value.
   *
   * @param key the exclusive upper bound, never null
   * @return the compound query for further chaining, never null
   */
  public CompoundQuery<T> lessThan(final Comparable<?> key) {
    Objects.requireNonNull(key, "key must not be null");
    return query.addCondition(new Condition(
        indexName, Condition.Operation.LESS_THAN, new Object[]{key}));
  }

  /**
   * Matches items whose indexed key is less than or equal to the value.
   *
   * @param key the inclusive upper bound, never null
   * @return the compound query for further chaining, never null
   */
  public CompoundQuery<T> lessOrEqual(final Comparable<?> key) {
    Objects.requireNonNull(key, "key must not be null");
    return query.addCondition(new Condition(
        indexName, Condition.Operation.LESS_OR_EQUAL, new Object[]{key}));
  }

  /**
   * Matches items whose String key starts with the given prefix.
   *
   * @param prefix the prefix to match, never null
   * @return the compound query for further chaining, never null
   */
  public CompoundQuery<T> startsWith(final String prefix) {
    Objects.requireNonNull(prefix, "prefix must not be null");
    return query.addCondition(new Condition(
        indexName, Condition.Operation.STARTS_WITH, new Object[]{prefix}));
  }

  /**
   * Matches items whose String key ends with the given suffix.
   *
   * @param suffix the suffix to match, never null
   * @return the compound query for further chaining, never null
   */
  public CompoundQuery<T> endsWith(final String suffix) {
    Objects.requireNonNull(suffix, "suffix must not be null");
    return query.addCondition(new Condition(
        indexName, Condition.Operation.ENDS_WITH, new Object[]{suffix}));
  }

  /**
   * Matches items whose String key contains the given substring.
   *
   * @param substring the substring to match, never null
   * @return the compound query for further chaining, never null
   */
  public CompoundQuery<T> contains(final String substring) {
    Objects.requireNonNull(substring, "substring must not be null");
    return query.addCondition(new Condition(
        indexName, Condition.Operation.CONTAINS, new Object[]{substring}));
  }
}
```

**Step 5: Run tests**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=CompoundConditionStepTest -q`
Expected: PASS (12 tests)

**Step 6: Run all tests**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -q`
Expected: 224 tests pass (210 + 2 + 12)

**Step 7: Commit**

```bash
cd /Users/waabox/code/waabox/andersoni
git add andersoni-core/src/main/java/org/waabox/andersoni/CompoundQuery.java \
        andersoni-core/src/main/java/org/waabox/andersoni/CompoundConditionStep.java \
        andersoni-core/src/test/java/org/waabox/andersoni/CompoundConditionStepTest.java
git commit -m "Add CompoundQuery skeleton and CompoundConditionStep"
```

---

### Task 3: CompoundQuery.execute() — intersection algorithm

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/CompoundQuery.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/CompoundQueryTest.java`

**Step 1: Write the tests**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class CompoundQueryTest {

  record Event(String country, String category, String organizer,
      LocalDate date) {}

  private Catalog<Event> buildCatalog(final List<Event> data) {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(data)
        .index("by-country").by(Event::country, Function.identity())
        .index("by-category").by(Event::category, Function.identity())
        .index("by-organizer").by(Event::organizer, Function.identity())
        .indexSorted("by-date").by(Event::date, Function.identity())
        .build();
    catalog.bootstrap();
    return catalog;
  }

  @Test
  void whenExecuting_givenSingleEqualTo_shouldReturnMatches() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Event e2 = new Event("BR", "SPORTS", "ORG-1", LocalDate.of(2026, 2, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1, e2));

    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .execute();

    assertEquals(1, results.size());
    assertTrue(results.contains(e1));
  }

  @Test
  void whenExecuting_givenTwoConditions_shouldIntersect() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Event e2 = new Event("AR", "MUSIC", "ORG-2", LocalDate.of(2026, 2, 1));
    final Event e3 = new Event("BR", "SPORTS", "ORG-1", LocalDate.of(2026, 3, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1, e2, e3));

    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .and("by-category").equalTo("SPORTS")
        .execute();

    assertEquals(1, results.size());
    assertTrue(results.contains(e1));
  }

  @Test
  void whenExecuting_givenThreeConditions_shouldIntersectAll() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Event e2 = new Event("AR", "SPORTS", "ORG-2", LocalDate.of(2026, 2, 1));
    final Event e3 = new Event("AR", "MUSIC", "ORG-1", LocalDate.of(2026, 3, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1, e2, e3));

    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .and("by-category").equalTo("SPORTS")
        .and("by-organizer").equalTo("ORG-1")
        .execute();

    assertEquals(1, results.size());
    assertTrue(results.contains(e1));
  }

  @Test
  void whenExecuting_givenNoMatch_shouldReturnEmpty() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1));

    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("CL")
        .execute();

    assertTrue(results.isEmpty());
  }

  @Test
  void whenExecuting_givenFirstConditionEmpty_shouldShortCircuit() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1));

    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("CL")
        .and("by-category").equalTo("SPORTS")
        .execute();

    assertTrue(results.isEmpty());
  }

  @Test
  void whenExecuting_givenIntersectionEmpty_shouldShortCircuit() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Event e2 = new Event("BR", "MUSIC", "ORG-2", LocalDate.of(2026, 2, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1, e2));

    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .and("by-category").equalTo("MUSIC")
        .execute();

    assertTrue(results.isEmpty());
  }

  @Test
  void whenExecuting_givenEqualToAndBetween_shouldIntersect() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 15));
    final Event e2 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 6, 15));
    final Event e3 = new Event("BR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 20));
    final Catalog<Event> catalog = buildCatalog(List.of(e1, e2, e3));

    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .and("by-date").between(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
        .execute();

    assertEquals(1, results.size());
    assertTrue(results.contains(e1));
  }

  @Test
  void whenExecuting_givenNoConditions_shouldThrowIllegalState() {
    final Catalog<Event> catalog = buildCatalog(List.of());

    final CompoundQuery<Event> query = catalog.compound();

    assertThrows(IllegalStateException.class, query::execute);
  }

  @Test
  void whenCallingWhereTwice_shouldThrowIllegalState() {
    final Catalog<Event> catalog = buildCatalog(List.of());

    final CompoundQuery<Event> query = catalog.compound();
    query.where("by-country").equalTo("AR");

    assertThrows(IllegalStateException.class,
        () -> query.where("by-category"));
  }

  @Test
  void whenExecuting_givenIndexNotFound_shouldThrowIndexNotFound() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1));

    assertThrows(IndexNotFoundException.class, () ->
        catalog.compound()
            .where("non-existent").equalTo("AR")
            .execute());
  }

  @Test
  void whenExecuting_shouldReturnUnmodifiableList() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1));

    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .execute();

    assertThrows(UnsupportedOperationException.class,
        () -> results.add(e1));
  }

  @Test
  void whenExecuting_givenMultipleMatches_shouldReturnAll() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Event e2 = new Event("AR", "SPORTS", "ORG-2", LocalDate.of(2026, 2, 1));
    final Event e3 = new Event("AR", "MUSIC", "ORG-1", LocalDate.of(2026, 3, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1, e2, e3));

    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .and("by-category").equalTo("SPORTS")
        .execute();

    assertEquals(2, results.size());
    assertTrue(results.contains(e1));
    assertTrue(results.contains(e2));
  }
}
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=CompoundQueryTest -q`
Expected: FAIL — `compound()` method doesn't exist on Catalog, `execute()` throws UnsupportedOperationException

**Step 3: Add `compound()` to Catalog**

In `Catalog.java`, add after the `query(indexName)` method:

```java
/**
 * Creates a compound query bound to the current snapshot.
 *
 * <p>The returned {@link CompoundQuery} allows chaining multiple
 * conditions across different indices and executing them with
 * short-circuit intersection.
 *
 * @return a CompoundQuery bound to the current snapshot, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public CompoundQuery<T> compound() {
  return new CompoundQuery<>(current.get(), name);
}
```

**Step 4: Implement `execute()` in CompoundQuery**

Replace the `execute()` stub with the full intersection algorithm:

```java
/**
 * Executes all conditions with short-circuit intersection.
 *
 * <p>Evaluates conditions sequentially. After the first condition,
 * each subsequent result is intersected with the accumulated result
 * using an identity-based set for O(min(n,m)) performance. If any
 * condition or intersection yields an empty result, execution stops
 * immediately.
 *
 * @return an unmodifiable list of matching items, never null
 *
 * @throws IllegalStateException if no conditions were added
 * @throws IndexNotFoundException if a referenced index does not exist
 * @throws UnsupportedIndexOperationException if a sorted operation is
 *         used on a non-sorted index
 */
public List<T> execute() {
  if (conditions.isEmpty()) {
    throw new IllegalStateException(
        "At least one condition is required. Call where() before execute()");
  }

  List<T> result = evaluateCondition(conditions.get(0));

  if (result.isEmpty()) {
    return Collections.emptyList();
  }

  for (int i = 1; i < conditions.size(); i++) {
    final List<T> candidates = evaluateCondition(conditions.get(i));

    if (candidates.isEmpty()) {
      return Collections.emptyList();
    }

    result = intersect(result, candidates);

    if (result.isEmpty()) {
      return Collections.emptyList();
    }
  }

  return Collections.unmodifiableList(result);
}

private List<T> evaluateCondition(final Condition condition) {
  final String idx = condition.indexName();
  final Object[] args = condition.args();

  if (!snapshot.hasIndex(idx)) {
    throw new IndexNotFoundException(idx, catalogName);
  }

  return switch (condition.operation()) {
    case EQUAL_TO -> snapshot.search(idx, args[0]);
    case BETWEEN -> snapshot.searchBetween(idx,
        (Comparable<?>) args[0], (Comparable<?>) args[1]);
    case GREATER_THAN -> snapshot.searchGreaterThan(idx,
        (Comparable<?>) args[0]);
    case GREATER_OR_EQUAL -> snapshot.searchGreaterOrEqual(idx,
        (Comparable<?>) args[0]);
    case LESS_THAN -> snapshot.searchLessThan(idx,
        (Comparable<?>) args[0]);
    case LESS_OR_EQUAL -> snapshot.searchLessOrEqual(idx,
        (Comparable<?>) args[0]);
    case STARTS_WITH -> snapshot.searchStartsWith(idx,
        (String) args[0]);
    case ENDS_WITH -> snapshot.searchEndsWith(idx,
        (String) args[0]);
    case CONTAINS -> snapshot.searchContains(idx,
        (String) args[0]);
  };
}

private List<T> intersect(final List<T> a, final List<T> b) {
  final List<T> smaller;
  final List<T> larger;

  if (a.size() <= b.size()) {
    smaller = a;
    larger = b;
  } else {
    smaller = b;
    larger = a;
  }

  final java.util.Set<T> retain = Collections.newSetFromMap(
      new java.util.IdentityHashMap<>(smaller.size()));
  retain.addAll(smaller);

  final List<T> result = new java.util.ArrayList<>();
  for (final T item : larger) {
    if (retain.contains(item)) {
      result.add(item);
    }
  }

  return result;
}
```

Add these imports to CompoundQuery.java:

```java
import java.util.IdentityHashMap;
import java.util.Set;
```

**Step 5: Run tests**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=CompoundQueryTest -q`
Expected: PASS (12 tests)

**Step 6: Run all tests**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -q`
Expected: All tests pass

**Step 7: Commit**

```bash
cd /Users/waabox/code/waabox/andersoni
git add andersoni-core/src/main/java/org/waabox/andersoni/CompoundQuery.java \
        andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java \
        andersoni-core/src/test/java/org/waabox/andersoni/CompoundQueryTest.java
git commit -m "Implement CompoundQuery.execute() with short-circuit intersection"
```

---

### Task 4: Andersoni compound() entry points

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniCompoundTest.java`

**Step 1: Write the test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class AndersoniCompoundTest {

  record Item(String country, String category) {}

  private Andersoni buildAndersoni(final List<Item> data) {
    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named("items")
        .data(data)
        .index("by-country").by(Item::country, Function.identity())
        .index("by-category").by(Item::category, Function.identity())
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("test-node")
        .build();

    andersoni.register(catalog);
    andersoni.start();
    return andersoni;
  }

  @Test
  void whenCompound_givenTyped_shouldReturnResults() {
    final Item i1 = new Item("AR", "SPORTS");
    final Item i2 = new Item("AR", "MUSIC");
    final Item i3 = new Item("BR", "SPORTS");
    final Andersoni andersoni = buildAndersoni(List.of(i1, i2, i3));

    final List<Item> results = andersoni.compound("items", Item.class)
        .where("by-country").equalTo("AR")
        .and("by-category").equalTo("SPORTS")
        .execute();

    assertEquals(1, results.size());
    assertTrue(results.contains(i1));
    andersoni.stop();
  }

  @Test
  void whenCompound_givenWildcard_shouldReturnResults() {
    final Item i1 = new Item("AR", "SPORTS");
    final Andersoni andersoni = buildAndersoni(List.of(i1));

    final List<?> results = andersoni.compound("items")
        .where("by-country").equalTo("AR")
        .execute();

    assertEquals(1, results.size());
    andersoni.stop();
  }

  @Test
  void whenCompound_givenUnknownCatalog_shouldThrow() {
    final Andersoni andersoni = buildAndersoni(List.of());

    assertThrows(IllegalArgumentException.class,
        () -> andersoni.compound("unknown", Item.class));
    andersoni.stop();
  }

  @Test
  void whenCompound_givenNullCatalogName_shouldThrowNpe() {
    final Andersoni andersoni = buildAndersoni(List.of());

    assertThrows(NullPointerException.class,
        () -> andersoni.compound(null, Item.class));
    andersoni.stop();
  }
}
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=AndersoniCompoundTest -q`
Expected: FAIL — `compound()` method doesn't exist on Andersoni

**Step 3: Add compound methods to Andersoni**

In `Andersoni.java`, add after the `query()` methods:

```java
/**
 * Creates a compound query for the specified catalog.
 *
 * @param catalogName the catalog name, never null
 * @return a CompoundQuery for the catalog, never null
 * @throws IllegalArgumentException if no catalog with the given name
 *                                  is registered
 * @throws CatalogNotAvailableException if the catalog failed to bootstrap
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public CompoundQuery<?> compound(final String catalogName) {
  Objects.requireNonNull(catalogName, "catalogName must not be null");
  final Catalog<?> catalog = requireCatalog(catalogName);
  if (failedCatalogs.contains(catalogName)) {
    throw new CatalogNotAvailableException(catalogName);
  }
  return catalog.compound();
}

/**
 * Creates a typed compound query for the specified catalog.
 *
 * @param catalogName the catalog name, never null
 * @param type        the expected element type, never null
 * @param <T>         the expected element type
 * @return a typed CompoundQuery for the catalog, never null
 * @throws IllegalArgumentException if no catalog with the given name
 *                                  is registered
 * @throws CatalogNotAvailableException if the catalog failed to bootstrap
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@SuppressWarnings("unchecked")
public <T> CompoundQuery<T> compound(final String catalogName,
    final Class<T> type) {
  Objects.requireNonNull(type, "type must not be null");
  return (CompoundQuery<T>) compound(catalogName);
}
```

**Step 4: Run tests**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=AndersoniCompoundTest -q`
Expected: PASS (4 tests)

**Step 5: Run all tests**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -q`
Expected: All tests pass

**Step 6: Commit**

```bash
cd /Users/waabox/code/waabox/andersoni
git add andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java \
        andersoni-core/src/test/java/org/waabox/andersoni/AndersoniCompoundTest.java
git commit -m "Add compound() entry points to Andersoni"
```

---

### Task 5: MultiKeyIndexDefinition

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/MultiKeyIndexDefinition.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/MultiKeyIndexDefinitionTest.java`

**Step 1: Write the test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MultiKeyIndexDefinitionTest {

  record Category(String id, String name, List<String> ancestorIds) {}

  record Publication(String id, Category category) {}

  @Test
  void whenBuilding_givenMultipleKeys_shouldIndexUnderEachKey() {
    final Category sports = new Category("cat-1", "Sports",
        List.of("cat-1"));
    final Category football = new Category("cat-2", "Football",
        List.of("cat-1", "cat-2"));

    final Publication p1 = new Publication("pub-1", football);
    final Publication p2 = new Publication("pub-2", sports);

    final MultiKeyIndexDefinition<Publication> index =
        MultiKeyIndexDefinition.<Publication>named("by-category")
            .by(pub -> pub.category().ancestorIds());

    final Map<Object, List<Publication>> result =
        index.buildIndex(List.of(p1, p2));

    // cat-1 should have both publications (p1 via ancestor, p2 directly)
    assertEquals(2, result.get("cat-1").size());
    assertTrue(result.get("cat-1").contains(p1));
    assertTrue(result.get("cat-1").contains(p2));

    // cat-2 should have only p1
    assertEquals(1, result.get("cat-2").size());
    assertTrue(result.get("cat-2").contains(p1));
  }

  @Test
  void whenBuilding_givenEmptyKeyList_shouldNotIndex() {
    final Category empty = new Category("cat-1", "Empty", List.of());
    final Publication p1 = new Publication("pub-1", empty);

    final MultiKeyIndexDefinition<Publication> index =
        MultiKeyIndexDefinition.<Publication>named("by-category")
            .by(pub -> pub.category().ancestorIds());

    final Map<Object, List<Publication>> result =
        index.buildIndex(List.of(p1));

    assertTrue(result.isEmpty());
  }

  @Test
  void whenBuilding_givenEmptyData_shouldReturnEmptyMap() {
    final MultiKeyIndexDefinition<Publication> index =
        MultiKeyIndexDefinition.<Publication>named("by-category")
            .by(pub -> pub.category().ancestorIds());

    final Map<Object, List<Publication>> result = index.buildIndex(List.of());

    assertTrue(result.isEmpty());
  }

  @Test
  void whenBuilding_shouldReturnUnmodifiableMap() {
    final Category cat = new Category("cat-1", "Sports", List.of("cat-1"));
    final Publication p1 = new Publication("pub-1", cat);

    final MultiKeyIndexDefinition<Publication> index =
        MultiKeyIndexDefinition.<Publication>named("by-category")
            .by(pub -> pub.category().ancestorIds());

    final Map<Object, List<Publication>> result =
        index.buildIndex(List.of(p1));

    assertThrows(UnsupportedOperationException.class,
        () -> result.put("injected", List.of()));

    assertThrows(UnsupportedOperationException.class,
        () -> result.get("cat-1").add(p1));
  }

  @Test
  void whenGettingName_shouldReturnConfiguredName() {
    final MultiKeyIndexDefinition<Publication> index =
        MultiKeyIndexDefinition.<Publication>named("by-category")
            .by(pub -> pub.category().ancestorIds());

    assertEquals("by-category", index.name());
  }

  @Test
  void whenCreating_givenNullName_shouldThrowNpe() {
    assertThrows(NullPointerException.class,
        () -> MultiKeyIndexDefinition.<Publication>named(null));
  }

  @Test
  void whenCreating_givenEmptyName_shouldThrowIllegalArgument() {
    assertThrows(IllegalArgumentException.class,
        () -> MultiKeyIndexDefinition.<Publication>named(""));
  }

  @Test
  void whenCreating_givenNullExtractor_shouldThrowNpe() {
    assertThrows(NullPointerException.class,
        () -> MultiKeyIndexDefinition.<Publication>named("test")
            .by(null));
  }

  @Test
  void whenBuilding_givenNullData_shouldThrowNpe() {
    final MultiKeyIndexDefinition<Publication> index =
        MultiKeyIndexDefinition.<Publication>named("test")
            .by(pub -> pub.category().ancestorIds());

    assertThrows(NullPointerException.class, () -> index.buildIndex(null));
  }
}
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=MultiKeyIndexDefinitionTest -q`
Expected: FAIL — class does not exist

**Step 3: Write the implementation**

```java
package org.waabox.andersoni;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Defines how to extract multiple index keys from a single domain object.
 *
 * <p>Unlike {@link IndexDefinition} which maps each item to a single key,
 * a {@code MultiKeyIndexDefinition} maps each item to a {@link List} of
 * keys. The item is indexed under every key in the list.
 *
 * <p>This is useful for hierarchical data where an item should be
 * discoverable from any ancestor in its hierarchy (e.g., a publication
 * indexed under all ancestor category IDs).
 *
 * <p>The output format is identical to {@link IndexDefinition#buildIndex},
 * so {@link Snapshot} requires no modifications.
 *
 * @param <T> the type of domain objects being indexed
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class MultiKeyIndexDefinition<T> {

  /** The name of this index. */
  private final String name;

  /** The function that extracts multiple keys from an item. */
  private final Function<T, List<?>> keysExtractor;

  private MultiKeyIndexDefinition(final String name,
      final Function<T, List<?>> keysExtractor) {
    this.name = name;
    this.keysExtractor = keysExtractor;
  }

  /**
   * Starts building a new multi-key index definition with the given name.
   *
   * @param name the name of the index, never null or empty
   * @param <T>  the type of domain objects being indexed
   * @return the next step of the builder, never null
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
   * Builds the multi-key index from the given data.
   *
   * <p>For each item, the keys extractor returns a list of keys. The item
   * is added to the index under every key. Items with empty key lists are
   * skipped.
   *
   * @param data the list of items to index, never null
   * @return an unmodifiable map from keys to lists of matching items,
   *         never null
   * @throws NullPointerException if data is null
   */
  public Map<Object, List<T>> buildIndex(final List<T> data) {
    Objects.requireNonNull(data, "data must not be null");

    if (data.isEmpty()) {
      return Collections.emptyMap();
    }

    final Map<Object, List<T>> index = new HashMap<>();

    for (final T item : data) {
      final List<?> keys = keysExtractor.apply(item);
      if (keys == null || keys.isEmpty()) {
        continue;
      }
      for (final Object key : keys) {
        index.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
      }
    }

    final Map<Object, List<T>> unmodifiable = new HashMap<>();
    for (final Map.Entry<Object, List<T>> entry : index.entrySet()) {
      unmodifiable.put(entry.getKey(),
          Collections.unmodifiableList(entry.getValue()));
    }

    return Collections.unmodifiableMap(unmodifiable);
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
   * Intermediate builder step for defining the key extraction function.
   *
   * @param <T> the type of domain objects being indexed
   */
  public static final class KeyStep<T> {

    private final String name;

    private KeyStep(final String name) {
      this.name = name;
    }

    /**
     * Defines the function that extracts multiple keys from an item.
     *
     * @param keysExtractor the function returning a list of keys,
     *                      never null
     * @return a fully configured multi-key index definition, never null
     * @throws NullPointerException if keysExtractor is null
     */
    public MultiKeyIndexDefinition<T> by(
        final Function<T, List<?>> keysExtractor) {
      Objects.requireNonNull(keysExtractor,
          "keysExtractor must not be null");
      return new MultiKeyIndexDefinition<>(name, keysExtractor);
    }
  }
}
```

**Step 4: Run tests**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=MultiKeyIndexDefinitionTest -q`
Expected: PASS (9 tests)

**Step 5: Run all tests**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -q`
Expected: All tests pass

**Step 6: Commit**

```bash
cd /Users/waabox/code/waabox/andersoni
git add andersoni-core/src/main/java/org/waabox/andersoni/MultiKeyIndexDefinition.java \
        andersoni-core/src/test/java/org/waabox/andersoni/MultiKeyIndexDefinitionTest.java
git commit -m "Add MultiKeyIndexDefinition for multi-key indexing"
```

---

### Task 6: Wire indexMulti() into Catalog builder

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java`
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/CatalogMultiKeyTest.java`

**Step 1: Write the test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class CatalogMultiKeyTest {

  record Category(String id, List<String> ancestorIds) {}

  record Publication(String id, String country, Category category) {}

  @Test
  void whenBootstrapping_givenMultiKeyIndex_shouldIndexCorrectly() {
    final Category sports = new Category("cat-1", List.of("cat-1"));
    final Category football = new Category("cat-2",
        List.of("cat-1", "cat-2"));

    final Publication p1 = new Publication("pub-1", "AR", football);
    final Publication p2 = new Publication("pub-2", "AR", sports);

    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of(p1, p2))
        .index("by-country").by(Publication::country, Function.identity())
        .indexMulti("by-category").by(
            pub -> pub.category().ancestorIds())
        .build();

    catalog.bootstrap();

    // Search by ancestor cat-1 should return both
    final List<Publication> byCat1 = catalog.search("by-category", "cat-1");
    assertEquals(2, byCat1.size());

    // Search by cat-2 should return only p1
    final List<Publication> byCat2 = catalog.search("by-category", "cat-2");
    assertEquals(1, byCat2.size());
    assertTrue(byCat2.contains(p1));
  }

  @Test
  void whenCompound_givenMultiKeyIndex_shouldIntersect() {
    final Category sports = new Category("cat-1", List.of("cat-1"));
    final Category football = new Category("cat-2",
        List.of("cat-1", "cat-2"));

    final Publication p1 = new Publication("pub-1", "AR", football);
    final Publication p2 = new Publication("pub-2", "BR", sports);
    final Publication p3 = new Publication("pub-3", "AR", sports);

    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of(p1, p2, p3))
        .index("by-country").by(Publication::country, Function.identity())
        .indexMulti("by-category").by(
            pub -> pub.category().ancestorIds())
        .build();

    catalog.bootstrap();

    // AR + cat-2 (FOOTBALL) should return only p1
    final List<Publication> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .and("by-category").equalTo("cat-2")
        .execute();

    assertEquals(1, results.size());
    assertTrue(results.contains(p1));
  }

  @Test
  void whenCompound_givenAncestorCategory_shouldReturnAllDescendants() {
    final Category sports = new Category("cat-1", List.of("cat-1"));
    final Category football = new Category("cat-2",
        List.of("cat-1", "cat-2"));

    final Publication p1 = new Publication("pub-1", "AR", football);
    final Publication p2 = new Publication("pub-2", "AR", sports);

    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of(p1, p2))
        .index("by-country").by(Publication::country, Function.identity())
        .indexMulti("by-category").by(
            pub -> pub.category().ancestorIds())
        .build();

    catalog.bootstrap();

    // AR + cat-1 (SPORTS ancestor) should return both
    final List<Publication> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .and("by-category").equalTo("cat-1")
        .execute();

    assertEquals(2, results.size());
    assertTrue(results.contains(p1));
    assertTrue(results.contains(p2));
  }

  @Test
  void whenBuilding_givenDuplicateMultiKeyName_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        Catalog.of(Publication.class)
            .named("publications")
            .data(List.of())
            .index("by-country").by(Publication::country, Function.identity())
            .indexMulti("by-country").by(
                pub -> pub.category().ancestorIds())
            .build());
  }
}
```

**Step 2: Run test to verify it fails**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=CatalogMultiKeyTest -q`
Expected: FAIL — `indexMulti()` does not exist

**Step 3: Modify Catalog to support multi-key indices**

In `Catalog.java`:

1. Add field to constructor and class:
```java
private final List<MultiKeyIndexDefinition<T>> multiKeyIndexDefinitions;
```

2. Update constructor to accept it:
```java
private Catalog(final String name,
    final DataLoader<T> dataLoader,
    final List<T> initialData,
    final List<IndexDefinition<T>> indexDefinitions,
    final List<SortedIndexDefinition<T>> sortedIndexDefinitions,
    final List<MultiKeyIndexDefinition<T>> multiKeyIndexDefinitions,
    final SnapshotSerializer<T> serializer,
    final Duration refreshInterval) {
  // ... existing fields ...
  this.multiKeyIndexDefinitions = Collections.unmodifiableList(
      new ArrayList<>(multiKeyIndexDefinitions));
}
```

3. Update `buildAndSwapSnapshot()` to include multi-key indices:
```java
// After building regular indices, add multi-key indices
for (final MultiKeyIndexDefinition<T> multiDef : multiKeyIndexDefinitions) {
  indices.put(multiDef.name(), multiDef.buildIndex(data));
}
```

4. Add to `BuildStep`:
```java
private final List<MultiKeyIndexDefinition<T>> multiKeyIndexDefinitions;

// Initialize in constructor:
this.multiKeyIndexDefinitions = new ArrayList<>();

// Add indexMulti method:
public MultiKeyIndexStep<T> indexMulti(final String indexName) {
  Objects.requireNonNull(indexName, "indexName must not be null");
  if (indexName.isEmpty()) {
    throw new IllegalArgumentException("indexName must not be empty");
  }
  return new MultiKeyIndexStep<>(this, indexName);
}

// Add addMultiKeyIndex:
void addMultiKeyIndex(final MultiKeyIndexDefinition<T> def) {
  if (!indexNames.add(def.name())) {
    throw new IllegalArgumentException(
        "Duplicate index name: '" + def.name() + "'");
  }
  multiKeyIndexDefinitions.add(def);
}
```

5. Update `build()` to include multi-key in the check and constructor call:
```java
public Catalog<T> build() {
  if (indexDefinitions.isEmpty() && sortedIndexDefinitions.isEmpty()
      && multiKeyIndexDefinitions.isEmpty()) {
    throw new IllegalStateException(
        "At least one index definition is required");
  }
  return new Catalog<>(name, dataLoader, initialData,
      indexDefinitions, sortedIndexDefinitions,
      multiKeyIndexDefinitions, serializer, refreshInterval);
}
```

6. Add `MultiKeyIndexStep` inner class:
```java
/**
 * Intermediate builder step for defining a multi-key index.
 *
 * @param <T> the type of data items
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public static final class MultiKeyIndexStep<T> {

  private final BuildStep<T> buildStep;
  private final String indexName;

  private MultiKeyIndexStep(final BuildStep<T> buildStep,
      final String indexName) {
    this.buildStep = buildStep;
    this.indexName = indexName;
  }

  /**
   * Defines the function that extracts multiple keys from each item.
   *
   * @param keysExtractor the function returning a list of keys,
   *                      never null
   * @return the parent BuildStep for further chaining, never null
   * @throws NullPointerException if keysExtractor is null
   */
  public BuildStep<T> by(final Function<T, List<?>> keysExtractor) {
    final MultiKeyIndexDefinition<T> def =
        MultiKeyIndexDefinition.<T>named(indexName).by(keysExtractor);
    buildStep.addMultiKeyIndex(def);
    return buildStep;
  }
}
```

**Step 4: Run tests**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=CatalogMultiKeyTest -q`
Expected: PASS (4 tests)

**Step 5: Run all tests**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -q`
Expected: All tests pass (existing CatalogTest should still pass since multi-key list is empty by default)

**Step 6: Commit**

```bash
cd /Users/waabox/code/waabox/andersoni
git add andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java \
        andersoni-core/src/test/java/org/waabox/andersoni/CatalogMultiKeyTest.java
git commit -m "Wire indexMulti() into Catalog builder for multi-key indices"
```

---

### Task 7: Final integration test and version bump

**Files:**
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/CompoundQueryIntegrationTest.java`

**Step 1: Write the full integration test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test for compound queries with multi-key
 * indices, simulating the publication-service use case.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class CompoundQueryIntegrationTest {

  record Category(String id, String name, List<String> ancestorIds) {}

  record Publication(String id, String country, Category category,
      String organizer, LocalDate publishDate) {}

  private Andersoni andersoni;

  @BeforeEach
  void setUp() {
    final Category entertainment = new Category("cat-1", "Entertainment",
        List.of("cat-1"));
    final Category sports = new Category("cat-2", "Sports",
        List.of("cat-1", "cat-2"));
    final Category football = new Category("cat-3", "Football",
        List.of("cat-1", "cat-2", "cat-3"));
    final Category baseball = new Category("cat-4", "Baseball",
        List.of("cat-1", "cat-2", "cat-4"));
    final Category music = new Category("cat-5", "Music",
        List.of("cat-1", "cat-5"));

    final Publication p1 = new Publication("pub-1", "AR", football,
        "ORG-1", LocalDate.of(2026, 1, 15));
    final Publication p2 = new Publication("pub-2", "AR", football,
        "ORG-2", LocalDate.of(2026, 2, 20));
    final Publication p3 = new Publication("pub-3", "AR", baseball,
        "ORG-1", LocalDate.of(2026, 3, 10));
    final Publication p4 = new Publication("pub-4", "BR", football,
        "ORG-1", LocalDate.of(2026, 1, 25));
    final Publication p5 = new Publication("pub-5", "AR", music,
        "ORG-3", LocalDate.of(2026, 4, 1));

    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of(p1, p2, p3, p4, p5))
        .index("by-country").by(
            Publication::country, Function.identity())
        .indexMulti("by-category").by(
            pub -> pub.category().ancestorIds())
        .index("by-organizer").by(
            Publication::organizer, Function.identity())
        .indexSorted("by-publish-date").by(
            Publication::publishDate, Function.identity())
        .build();

    andersoni = Andersoni.builder().nodeId("test").build();
    andersoni.register(catalog);
    andersoni.start();
  }

  @AfterEach
  void tearDown() {
    andersoni.stop();
  }

  @Test
  void whenSearching_givenCountryAndSportsAncestor_shouldReturnAll() {
    // AR + Sports (cat-2) = p1 (football), p2 (football), p3 (baseball)
    final List<Publication> results =
        andersoni.compound("publications", Publication.class)
            .where("by-country").equalTo("AR")
            .and("by-category").equalTo("cat-2")
            .execute();

    assertEquals(3, results.size());
  }

  @Test
  void whenSearching_givenCountryAndFootball_shouldReturnLeaf() {
    // AR + Football (cat-3) = p1, p2
    final List<Publication> results =
        andersoni.compound("publications", Publication.class)
            .where("by-country").equalTo("AR")
            .and("by-category").equalTo("cat-3")
            .execute();

    assertEquals(2, results.size());
  }

  @Test
  void whenSearching_givenCountryAndCategoryAndOrganizer_shouldIntersect() {
    // AR + Sports (cat-2) + ORG-1 = p1 (football), p3 (baseball)
    final List<Publication> results =
        andersoni.compound("publications", Publication.class)
            .where("by-country").equalTo("AR")
            .and("by-category").equalTo("cat-2")
            .and("by-organizer").equalTo("ORG-1")
            .execute();

    assertEquals(2, results.size());
  }

  @Test
  void whenSearching_givenCountryAndDateRange_shouldFilter() {
    // AR + Jan-Feb 2026
    final List<Publication> results =
        andersoni.compound("publications", Publication.class)
            .where("by-country").equalTo("AR")
            .and("by-publish-date").between(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 28))
            .execute();

    assertEquals(2, results.size());
  }

  @Test
  void whenSearching_givenAllFourCriteria_shouldNarrowDown() {
    // AR + Football (cat-3) + ORG-1 + Jan 2026
    final List<Publication> results =
        andersoni.compound("publications", Publication.class)
            .where("by-country").equalTo("AR")
            .and("by-category").equalTo("cat-3")
            .and("by-organizer").equalTo("ORG-1")
            .and("by-publish-date").between(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31))
            .execute();

    assertEquals(1, results.size());
    assertEquals("pub-1", results.get(0).id());
  }

  @Test
  void whenSearching_givenEntertainmentRoot_shouldReturnAll() {
    // AR + Entertainment (cat-1) = all AR publications
    final List<Publication> results =
        andersoni.compound("publications", Publication.class)
            .where("by-country").equalTo("AR")
            .and("by-category").equalTo("cat-1")
            .execute();

    assertEquals(4, results.size());
  }

  @Test
  void whenSearching_givenNoMatch_shouldReturnEmpty() {
    // CL + anything = empty
    final List<Publication> results =
        andersoni.compound("publications", Publication.class)
            .where("by-country").equalTo("CL")
            .and("by-category").equalTo("cat-1")
            .execute();

    assertTrue(results.isEmpty());
  }
}
```

**Step 2: Run tests**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -Dtest=CompoundQueryIntegrationTest -q`
Expected: PASS (7 tests)

**Step 3: Run all tests**

Run: `cd /Users/waabox/code/waabox/andersoni && mvn test -pl andersoni-core -q`
Expected: All tests pass

**Step 4: Commit**

```bash
cd /Users/waabox/code/waabox/andersoni
git add andersoni-core/src/test/java/org/waabox/andersoni/CompoundQueryIntegrationTest.java
git commit -m "Add compound query integration test with multi-key indices"
```
