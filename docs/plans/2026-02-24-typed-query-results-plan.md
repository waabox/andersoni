# Typed Query Results Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add typed overloads for `search` and `query` on `Andersoni` so callers get `List<T>` and `QueryStep<T>` without manual casting.

**Architecture:** Two new overloaded methods that accept `Class<T>`, delegate to the existing wildcard methods, and perform an unchecked cast. Zero structural changes. Existing methods untouched.

**Tech Stack:** Java 21, JUnit 5, EasyMock

---

### Task 1: Typed `search` overload - test

**Files:**
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java`

**Step 1: Write the failing test**

Add this test method to `AndersoniTest`:

```java
@Test
void whenSearching_givenExpectedType_shouldReturnTypedList() {
  final Sport football = new Sport("Football");
  final Venue maracana = new Venue("Maracana");
  final Event e1 = new Event("1", football, maracana);

  final Catalog<Event> catalog = Catalog.of(Event.class)
      .named("events")
      .data(List.of(e1))
      .index("by-sport").by(Event::sport, Sport::name)
      .build();

  final Andersoni andersoni = Andersoni.builder().build();
  andersoni.register(catalog);
  andersoni.start();

  final List<Event> results = andersoni.search(
      "events", "by-sport", "Football", Event.class);

  assertEquals(1, results.size());
  assertEquals(e1, results.get(0));

  andersoni.stop();
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest="AndersoniTest#whenSearching_givenExpectedType_shouldReturnTypedList" -f /Users/waabox/code/waabox/andersoni/pom.xml`

Expected: FAIL — method `search(String, String, Object, Class)` does not exist.

---

### Task 2: Typed `search` overload - implementation

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`

**Step 1: Add the typed search method**

Add immediately after the existing `search(String, String, Object)` method (after line 245):

```java
/**
 * Searches a catalog by name and delegates to the specified index,
 * returning a typed list for caller convenience.
 *
 * <p>This is a convenience overload that performs an unchecked cast
 * on the result of {@link #search(String, String, Object)}. The caller
 * is responsible for providing the correct type — the type that was
 * used when creating the catalog via {@link Catalog#of(Class)}.
 *
 * @param catalogName  the name of the catalog to search, never null
 * @param indexName    the name of the index within the catalog, never null
 * @param key          the key to look up, never null
 * @param type         the expected element type, never null
 * @param <T>          the expected element type
 *
 * @return an unmodifiable list of matching items, never null
 *
 * @throws IllegalArgumentException      if no catalog with the given name
 *                                       is registered
 * @throws CatalogNotAvailableException  if the catalog failed to bootstrap
 */
@SuppressWarnings("unchecked")
public <T> List<T> search(final String catalogName, final String indexName,
    final Object key, final Class<T> type) {
  Objects.requireNonNull(type, "type must not be null");
  return (List<T>) search(catalogName, indexName, key);
}
```

**Step 2: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest="AndersoniTest#whenSearching_givenExpectedType_shouldReturnTypedList" -f /Users/waabox/code/waabox/andersoni/pom.xml`

Expected: PASS

---

### Task 3: Typed `query` overload - test

**Files:**
- Test: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java`

**Step 1: Write the failing test**

Add this test method to `AndersoniTest`:

```java
@Test
void whenQuerying_givenExpectedType_shouldReturnTypedQueryStep() {
  final Sport football = new Sport("Football");
  final Venue maracana = new Venue("Maracana");
  final Event e1 = new Event("1", football, maracana);

  final Catalog<Event> catalog = Catalog.of(Event.class)
      .named("events")
      .data(List.of(e1))
      .index("by-sport").by(Event::sport, Sport::name)
      .build();

  final Andersoni andersoni = Andersoni.builder().build();
  andersoni.register(catalog);
  andersoni.start();

  final QueryStep<Event> queryStep = andersoni.query(
      "events", "by-sport", Event.class);

  final List<Event> results = queryStep.equalTo("Football");

  assertEquals(1, results.size());
  assertEquals(e1, results.get(0));

  andersoni.stop();
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest="AndersoniTest#whenQuerying_givenExpectedType_shouldReturnTypedQueryStep" -f /Users/waabox/code/waabox/andersoni/pom.xml`

Expected: FAIL — method `query(String, String, Class)` does not exist.

---

### Task 4: Typed `query` overload - implementation

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`

**Step 1: Add the typed query method**

Add immediately after the existing `query(String, String)` method (after line 279):

```java
/**
 * Returns a typed {@link QueryStep} for fluent querying of a catalog's
 * index.
 *
 * <p>This is a convenience overload that performs an unchecked cast
 * on the result of {@link #query(String, String)}. The caller is
 * responsible for providing the correct type — the type that was used
 * when creating the catalog via {@link Catalog#of(Class)}.
 *
 * <p>Usage:
 * <pre>{@code
 * andersoni.query("events", "by-date", Event.class).between(from, to);
 * andersoni.query("events", "by-venue", Event.class).equalTo("Maracana");
 * }</pre>
 *
 * @param catalogName the name of the catalog, never null
 * @param indexName   the name of the index, never null
 * @param type        the expected element type, never null
 * @param <T>         the expected element type
 *
 * @return a QueryStep for the specified catalog and index, never null
 *
 * @throws IllegalArgumentException     if no catalog with the given name
 *                                      is registered
 * @throws CatalogNotAvailableException if the catalog failed to bootstrap
 */
@SuppressWarnings("unchecked")
public <T> QueryStep<T> query(final String catalogName,
    final String indexName, final Class<T> type) {
  Objects.requireNonNull(type, "type must not be null");
  return (QueryStep<T>) query(catalogName, indexName);
}
```

**Step 2: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest="AndersoniTest#whenQuerying_givenExpectedType_shouldReturnTypedQueryStep" -f /Users/waabox/code/waabox/andersoni/pom.xml`

Expected: PASS

---

### Task 5: Update JavaDoc example in class header

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`

**Step 1: Update the class-level JavaDoc usage example**

In the class-level JavaDoc (around line 57), change:

```java
 * List<?> results = andersoni.search("events", "by-sport", "Football");
```

to:

```java
 * List<Event> results = andersoni.search("events", "by-sport", "Football", Event.class);
```

**Step 2: Run all tests to verify nothing broke**

Run: `mvn test -pl andersoni-core -f /Users/waabox/code/waabox/andersoni/pom.xml`

Expected: All tests PASS.

---

### Task 6: Commit

**Step 1: Commit all changes**

```bash
git add andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java
git add andersoni-core/src/test/java/org/waabox/andersoni/AndersoniTest.java
git commit -m "Add typed search and query overloads to Andersoni"
```
