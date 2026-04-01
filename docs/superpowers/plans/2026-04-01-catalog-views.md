# Catalog Views Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add pre-computed, immutable views (projections) to Andersoni catalogs so queries can return typed projections `V` of domain objects `T` without runtime transformation.

**Architecture:** Introduce `AndersoniCatalogItem<T>` as an internal wrapper holding `T` plus a `Map<Class<?>, Object>` of pre-computed views. The wrapper is never exposed in the public API. Indexes operate on `T` extracted from the wrapper. All query methods gain an overload accepting `Class<V>` to return `List<V>` instead of `List<T>`. The `SnapshotSerializer<T>` interface is unchanged — serializers now serialize `AndersoniCatalogItem<T>` as `T` (the wrapper is transparent to the serialization layer internally).

**Tech Stack:** Java 21, JUnit 5, EasyMock

**Spec:** `docs/superpowers/specs/2026-04-01-catalog-views-design.md`

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `andersoni-core/src/main/java/org/waabox/andersoni/ViewDefinition.java` | Associates a view type `Class<V>` with a `Function<T, V>` mapper |
| Create | `andersoni-core/src/main/java/org/waabox/andersoni/AndersoniCatalogItem.java` | Internal wrapper: holds `T item` + `Map<Class<?>, Object> views` |
| Modify | `andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java` | Add `view()` to BuildStep, store ViewDefinitions, wrap items in `buildAndSwapSnapshot` |
| Modify | `andersoni-core/src/main/java/org/waabox/andersoni/Snapshot.java` | Store `List<AndersoniCatalogItem<T>>` internally, add view-aware query methods |
| Modify | `andersoni-core/src/main/java/org/waabox/andersoni/QueryStep.java` | Add view overloads for all query methods |
| Modify | `andersoni-core/src/main/java/org/waabox/andersoni/CompoundQuery.java` | Add `execute(Class<V>)` overload |
| Modify | `andersoni-core/src/main/java/org/waabox/andersoni/GraphQueryBuilder.java` | Add `execute(Class<V>)` overload |
| Modify | `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java` | Add view overloads for `search`, `query`, `compound`, `graphQuery` |
| Modify | `andersoni-core/src/main/java/org/waabox/andersoni/CatalogInfo.java` | Add view count and view type names |
| Create | `andersoni-core/src/test/java/org/waabox/andersoni/ViewDefinitionTest.java` | Unit tests for ViewDefinition |
| Create | `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniCatalogItemTest.java` | Unit tests for the wrapper |
| Create | `andersoni-core/src/test/java/org/waabox/andersoni/CatalogViewTest.java` | Integration tests for views through Catalog |
| Create | `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniViewTest.java` | End-to-end tests for views through Andersoni |

---

### Task 1: ViewDefinition

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/ViewDefinition.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/ViewDefinitionTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ViewDefinitionTest {

  record Event(String id, String name, String venue) {}

  record EventSummary(String id, String name) {}

  @Test
  void whenCreating_givenValidTypeAndMapper_shouldStoreTypeAndMapper() {
    final ViewDefinition<Event, EventSummary> view = ViewDefinition.of(
        EventSummary.class,
        e -> new EventSummary(e.id(), e.name())
    );

    assertEquals(EventSummary.class, view.viewType());
    assertNotNull(view.mapper());
  }

  @Test
  void whenApplyingMapper_givenAnEvent_shouldProduceCorrectView() {
    final ViewDefinition<Event, EventSummary> view = ViewDefinition.of(
        EventSummary.class,
        e -> new EventSummary(e.id(), e.name())
    );

    final Event event = new Event("1", "Final", "Maracana");
    final EventSummary summary = view.mapper().apply(event);

    assertEquals("1", summary.id());
    assertEquals("Final", summary.name());
  }

  @Test
  void whenCreating_givenNullViewType_shouldThrowNPE() {
    assertThrows(NullPointerException.class, () ->
        ViewDefinition.of(null, e -> e)
    );
  }

  @Test
  void whenCreating_givenNullMapper_shouldThrowNPE() {
    assertThrows(NullPointerException.class, () ->
        ViewDefinition.of(EventSummary.class, null)
    );
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=ViewDefinitionTest -DfailIfNoTests=false`
Expected: Compilation error — `ViewDefinition` does not exist yet.

- [ ] **Step 3: Write the implementation**

```java
package org.waabox.andersoni;

import java.util.Objects;
import java.util.function.Function;

/**
 * Defines a view (projection) of a catalog's domain object.
 *
 * <p>Associates a target type {@code V} with a stateless mapping function
 * that transforms the source object {@code T} into its projected form.
 * Views are pre-computed at snapshot build time and stored alongside the
 * original item in {@link AndersoniCatalogItem}.
 *
 * @param <T> the source domain type
 * @param <V> the view (projected) type
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class ViewDefinition<T, V> {

  /** The view type class, never null. */
  private final Class<V> viewType;

  /** The mapping function from T to V, never null. */
  private final Function<T, V> mapper;

  /**
   * Creates a new view definition.
   *
   * @param theViewType the view type class, never null
   * @param theMapper   the mapping function, never null
   */
  private ViewDefinition(final Class<V> theViewType,
      final Function<T, V> theMapper) {
    viewType = theViewType;
    mapper = theMapper;
  }

  /**
   * Creates a new view definition associating the given type with a
   * mapping function.
   *
   * @param viewType the view type class, never null
   * @param mapper   the mapping function from T to V, never null
   * @param <T>      the source domain type
   * @param <V>      the view type
   *
   * @return a new view definition, never null
   *
   * @throws NullPointerException if viewType or mapper is null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static <T, V> ViewDefinition<T, V> of(final Class<V> viewType,
      final Function<T, V> mapper) {
    Objects.requireNonNull(viewType, "viewType must not be null");
    Objects.requireNonNull(mapper, "mapper must not be null");
    return new ViewDefinition<>(viewType, mapper);
  }

  /**
   * Returns the view type class.
   *
   * @return the view type, never null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public Class<V> viewType() {
    return viewType;
  }

  /**
   * Returns the mapping function.
   *
   * @return the mapper from T to V, never null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public Function<T, V> mapper() {
    return mapper;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=ViewDefinitionTest`
Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```
feat: add ViewDefinition for catalog view projections
```

---

### Task 2: AndersoniCatalogItem

**Files:**
- Create: `andersoni-core/src/main/java/org/waabox/andersoni/AndersoniCatalogItem.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniCatalogItemTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class AndersoniCatalogItemTest {

  record Event(String id, String name) {}

  record EventSummary(String id) {}

  @Test
  void whenCreating_givenItemAndViews_shouldReturnItemAndViews() {
    final Event event = new Event("1", "Final");
    final EventSummary summary = new EventSummary("1");

    final AndersoniCatalogItem<Event> item = AndersoniCatalogItem.of(
        event, Map.of(EventSummary.class, summary)
    );

    assertSame(event, item.item());
    assertEquals(summary, item.view(EventSummary.class));
  }

  @Test
  void whenCreatingWithoutViews_givenOnlyItem_shouldReturnItem() {
    final Event event = new Event("1", "Final");

    final AndersoniCatalogItem<Event> item = AndersoniCatalogItem.of(
        event, Map.of()
    );

    assertSame(event, item.item());
    assertTrue(item.views().isEmpty());
  }

  @Test
  void whenRequestingUnregisteredView_shouldThrowIllegalArgument() {
    final Event event = new Event("1", "Final");

    final AndersoniCatalogItem<Event> item = AndersoniCatalogItem.of(
        event, Map.of()
    );

    assertThrows(IllegalArgumentException.class, () ->
        item.view(EventSummary.class)
    );
  }

  @Test
  void whenCreating_givenNullItem_shouldThrowNPE() {
    assertThrows(NullPointerException.class, () ->
        AndersoniCatalogItem.of(null, Map.of())
    );
  }

  @Test
  void whenCreating_givenNullViews_shouldThrowNPE() {
    assertThrows(NullPointerException.class, () ->
        AndersoniCatalogItem.of(new Event("1", "x"), null)
    );
  }

  @Test
  void whenAccessingViews_shouldReturnUnmodifiableMap() {
    final Event event = new Event("1", "Final");
    final EventSummary summary = new EventSummary("1");

    final AndersoniCatalogItem<Event> item = AndersoniCatalogItem.of(
        event, Map.of(EventSummary.class, summary)
    );

    assertThrows(UnsupportedOperationException.class, () ->
        item.views().put(String.class, "nope")
    );
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=AndersoniCatalogItemTest -DfailIfNoTests=false`
Expected: Compilation error — `AndersoniCatalogItem` does not exist yet.

- [ ] **Step 3: Write the implementation**

```java
package org.waabox.andersoni;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Internal wrapper that holds a domain object together with its
 * pre-computed views (projections).
 *
 * <p>This class is package-private and never exposed in the public API.
 * All public-facing methods return either {@code T} (the item) or
 * {@code V} (a view type) — never this wrapper.
 *
 * <p>Instances are immutable. Views are materialized at snapshot build
 * time and shared across all index lookups.
 *
 * @param <T> the domain object type
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
final class AndersoniCatalogItem<T> {

  /** The domain object, never null. */
  private final T item;

  /** Pre-computed views keyed by view type, never null. */
  private final Map<Class<?>, Object> views;

  /**
   * Creates a new catalog item wrapper.
   *
   * @param theItem  the domain object, never null
   * @param theViews the pre-computed views, never null
   */
  private AndersoniCatalogItem(final T theItem,
      final Map<Class<?>, Object> theViews) {
    item = theItem;
    views = theViews;
  }

  /**
   * Creates a new catalog item with the given domain object and views.
   *
   * @param item  the domain object, never null
   * @param views the pre-computed views keyed by type, never null
   * @param <T>   the domain object type
   *
   * @return a new immutable catalog item, never null
   *
   * @throws NullPointerException if item or views is null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  static <T> AndersoniCatalogItem<T> of(final T item,
      final Map<Class<?>, Object> views) {
    Objects.requireNonNull(item, "item must not be null");
    Objects.requireNonNull(views, "views must not be null");
    return new AndersoniCatalogItem<>(item,
        Collections.unmodifiableMap(Map.copyOf(views)));
  }

  /**
   * Returns the domain object.
   *
   * @return the item, never null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  T item() {
    return item;
  }

  /**
   * Returns the pre-computed view for the given type.
   *
   * @param viewType the view type class, never null
   * @param <V>      the view type
   *
   * @return the view instance, never null
   *
   * @throws IllegalArgumentException if the view type is not registered
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  @SuppressWarnings("unchecked")
  <V> V view(final Class<V> viewType) {
    final Object view = views.get(viewType);
    if (view == null) {
      throw new IllegalArgumentException(
          "View " + viewType.getSimpleName() + " is not registered");
    }
    return (V) view;
  }

  /**
   * Returns the unmodifiable map of all views.
   *
   * @return the views map, never null
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  Map<Class<?>, Object> views() {
    return views;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=AndersoniCatalogItemTest`
Expected: All 6 tests PASS.

- [ ] **Step 5: Commit**

```
feat: add AndersoniCatalogItem as internal view wrapper
```

---

### Task 3: Snapshot — internal storage with AndersoniCatalogItem

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Snapshot.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/SnapshotViewTest.java`

This task changes the Snapshot internals to store `List<AndersoniCatalogItem<T>>` alongside the existing `List<T> data`. The existing API (`search`, `data`, all range queries) continues returning `List<T>`. New view-aware methods are added.

- [ ] **Step 1: Write the failing test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SnapshotViewTest {

  record Event(String id, String name, String venue) {}

  record EventSummary(String id, String name) {}

  @Test
  void whenSearchingWithView_givenRegisteredView_shouldReturnViewList() {
    final Event e1 = new Event("1", "Final", "Maracana");
    final Event e2 = new Event("2", "Semi", "Wembley");
    final Event e3 = new Event("3", "Quarter", "Maracana");

    final EventSummary s1 = new EventSummary("1", "Final");
    final EventSummary s3 = new EventSummary("3", "Quarter");

    final AndersoniCatalogItem<Event> i1 = AndersoniCatalogItem.of(
        e1, Map.of(EventSummary.class, s1));
    final AndersoniCatalogItem<Event> i2 = AndersoniCatalogItem.of(
        e2, Map.of(EventSummary.class, new EventSummary("2", "Semi")));
    final AndersoniCatalogItem<Event> i3 = AndersoniCatalogItem.of(
        e3, Map.of(EventSummary.class, s3));

    final List<AndersoniCatalogItem<Event>> items = List.of(i1, i2, i3);

    final Map<String, Map<Object, List<AndersoniCatalogItem<Event>>>> indices =
        new HashMap<>();
    indices.put("by-venue", Map.of(
        "Maracana", List.of(i1, i3),
        "Wembley", List.of(i2)
    ));

    final Snapshot<Event> snapshot = Snapshot.ofWithItems(
        items, indices, Collections.emptyMap(), Collections.emptyMap(),
        1L, "hash123");

    final List<EventSummary> result = snapshot.search(
        "by-venue", "Maracana", EventSummary.class);

    assertEquals(2, result.size());
    assertEquals(s1, result.get(0));
    assertEquals(s3, result.get(1));
  }

  @Test
  void whenSearchingWithView_givenNoMatch_shouldReturnEmptyList() {
    final AndersoniCatalogItem<Event> i1 = AndersoniCatalogItem.of(
        new Event("1", "Final", "Maracana"),
        Map.of(EventSummary.class, new EventSummary("1", "Final")));

    final Map<String, Map<Object, List<AndersoniCatalogItem<Event>>>> indices =
        new HashMap<>();
    indices.put("by-venue", Map.of("Maracana", List.of(i1)));

    final Snapshot<Event> snapshot = Snapshot.ofWithItems(
        List.of(i1), indices, Collections.emptyMap(),
        Collections.emptyMap(), 1L, "hash");

    final List<EventSummary> result = snapshot.search(
        "by-venue", "Unknown", EventSummary.class);

    assertEquals(0, result.size());
  }

  @Test
  void whenSearchingWithView_givenUnregisteredView_shouldThrowIllegalArg() {
    final AndersoniCatalogItem<Event> i1 = AndersoniCatalogItem.of(
        new Event("1", "Final", "Maracana"), Map.of());

    final Map<String, Map<Object, List<AndersoniCatalogItem<Event>>>> indices =
        new HashMap<>();
    indices.put("by-venue", Map.of("Maracana", List.of(i1)));

    final Snapshot<Event> snapshot = Snapshot.ofWithItems(
        List.of(i1), indices, Collections.emptyMap(),
        Collections.emptyMap(), 1L, "hash");

    assertThrows(IllegalArgumentException.class, () ->
        snapshot.search("by-venue", "Maracana", EventSummary.class)
    );
  }

  @Test
  void whenSearchingWithoutView_shouldReturnItemsAsUsual() {
    final Event e1 = new Event("1", "Final", "Maracana");

    final AndersoniCatalogItem<Event> i1 = AndersoniCatalogItem.of(
        e1, Map.of(EventSummary.class, new EventSummary("1", "Final")));

    final Map<String, Map<Object, List<AndersoniCatalogItem<Event>>>> indices =
        new HashMap<>();
    indices.put("by-venue", Map.of("Maracana", List.of(i1)));

    final Snapshot<Event> snapshot = Snapshot.ofWithItems(
        List.of(i1), indices, Collections.emptyMap(),
        Collections.emptyMap(), 1L, "hash");

    final List<Event> result = snapshot.search("by-venue", "Maracana");

    assertEquals(1, result.size());
    assertEquals(e1, result.get(0));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=SnapshotViewTest -DfailIfNoTests=false`
Expected: Compilation error — `Snapshot.ofWithItems` and `snapshot.search(..., Class)` don't exist.

- [ ] **Step 3: Modify Snapshot internals**

The Snapshot class needs these changes:

1. **Add a new field** alongside existing `data`:
```java
/** The catalog items with views, null when no views are defined. */
private final List<AndersoniCatalogItem<T>> items;
```

2. **Change internal index storage** from `Map<String, Map<Object, List<T>>>` to `Map<String, Map<Object, List<AndersoniCatalogItem<T>>>>` in a new parallel structure. However, to maintain backward compatibility with the existing `of()` factory methods and minimize blast radius, we take a different approach:

   - Add a new factory method `ofWithItems` that accepts `List<AndersoniCatalogItem<T>>` and indices keyed on `AndersoniCatalogItem<T>`.
   - The existing `of()` methods remain unchanged for backward compatibility (they internally create items without views).
   - Internally, the Snapshot stores indices as `Map<String, Map<Object, List<AndersoniCatalogItem<T>>>>`.
   - The existing `search(indexName, key)` extracts items from the wrapper.
   - A new `search(indexName, key, viewClass)` extracts views from the wrapper.

3. **Add view-aware query methods** — for each existing query method, add an overload with `Class<V> viewType`:
   - `search(String indexName, Object key, Class<V> viewType)`
   - `searchBetween(String indexName, Comparable<?> from, Comparable<?> to, Class<V> viewType)`
   - `searchGreaterThan(String indexName, Comparable<?> key, Class<V> viewType)`
   - `searchGreaterOrEqual(String indexName, Comparable<?> key, Class<V> viewType)`
   - `searchLessThan(String indexName, Comparable<?> key, Class<V> viewType)`
   - `searchLessOrEqual(String indexName, Comparable<?> key, Class<V> viewType)`
   - `searchStartsWith(String indexName, String prefix, Class<V> viewType)`
   - `searchEndsWith(String indexName, String suffix, Class<V> viewType)`
   - `searchContains(String indexName, String substring, Class<V> viewType)`

4. **Add an internal helper** to extract views from a list of items:
```java
private <V> List<V> extractViews(
    final List<AndersoniCatalogItem<T>> results,
    final Class<V> viewType) {
  if (results.isEmpty()) {
    return Collections.emptyList();
  }
  final List<V> views = new ArrayList<>(results.size());
  for (final AndersoniCatalogItem<T> entry : results) {
    views.add(entry.view(viewType));
  }
  return Collections.unmodifiableList(views);
}
```

5. **Add an internal helper** to extract items from a list of wrappers:
```java
private List<T> extractItems(
    final List<AndersoniCatalogItem<T>> results) {
  if (results.isEmpty()) {
    return Collections.emptyList();
  }
  final List<T> extracted = new ArrayList<>(results.size());
  for (final AndersoniCatalogItem<T> entry : results) {
    extracted.add(entry.item());
  }
  return Collections.unmodifiableList(extracted);
}
```

6. **Refactor existing `search` and query methods** — they now call the internal index lookup (which returns `List<AndersoniCatalogItem<T>>`) and then extract items. The view overloads extract views instead.

7. **The `data()` method** continues returning `List<T>` (extracted from items). Add a new package-private `items()` method returning `List<AndersoniCatalogItem<T>>` for internal use.

8. **Update `indexInfo()`** — the memory estimation should account for the `AndersoniCatalogItem` wrapper overhead and view objects per item.

**Important:** The existing `of()` factory methods must continue working. They should internally wrap each `T` in an `AndersoniCatalogItem` with an empty views map, so the internal representation is always uniform.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=SnapshotViewTest`
Expected: All 4 tests PASS.

- [ ] **Step 5: Run all existing Snapshot tests to verify no regression**

Run: `mvn test -pl andersoni-core -Dtest="SnapshotTest,SnapshotHashTest,SnapshotCompositeKeyEstimationTest"`
Expected: All existing tests PASS.

- [ ] **Step 6: Commit**

```
feat: add view-aware storage and query methods to Snapshot
```

---

### Task 4: Catalog — view registration and snapshot build

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/CatalogViewTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class CatalogViewTest {

  record Sport(String name) {}

  record Venue(String name) {}

  record Event(String id, Sport sport, Venue venue) {}

  record EventSummary(String id, String sportName) {}

  record EventCard(String id, String venueName) {}

  @Test
  void whenSearchingWithView_givenRegisteredView_shouldReturnViews() {
    final Venue maracana = new Venue("Maracana");
    final Venue wembley = new Venue("Wembley");
    final Sport football = new Sport("Football");
    final Sport rugby = new Sport("Rugby");

    final Event e1 = new Event("1", football, maracana);
    final Event e2 = new Event("2", rugby, wembley);
    final Event e3 = new Event("3", football, maracana);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1, e2, e3))
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class,
            e -> new EventSummary(e.id(), e.sport().name()))
        .build();

    catalog.bootstrap();

    final List<EventSummary> result = catalog.search(
        "by-venue", "Maracana", EventSummary.class);

    assertEquals(2, result.size());
    assertEquals(new EventSummary("1", "Football"), result.get(0));
    assertEquals(new EventSummary("3", "Football"), result.get(1));
  }

  @Test
  void whenSearchingWithoutView_shouldReturnItemsAsUsual() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class,
            e -> new EventSummary(e.id(), e.sport().name()))
        .build();

    catalog.bootstrap();

    final List<Event> result = catalog.search("by-venue", "Maracana");

    assertEquals(1, result.size());
    assertEquals(e1, result.get(0));
  }

  @Test
  void whenSearchingWithUnregisteredView_shouldThrowIllegalArg() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    catalog.bootstrap();

    assertThrows(IllegalArgumentException.class, () ->
        catalog.search("by-venue", "Maracana", EventSummary.class)
    );
  }

  @Test
  void whenDefiningMultipleViews_shouldSupportAll() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class,
            e -> new EventSummary(e.id(), e.sport().name()))
        .view(EventCard.class,
            e -> new EventCard(e.id(), e.venue().name()))
        .build();

    catalog.bootstrap();

    final List<EventSummary> summaries = catalog.search(
        "by-venue", "Maracana", EventSummary.class);
    final List<EventCard> cards = catalog.search(
        "by-venue", "Maracana", EventCard.class);

    assertEquals(1, summaries.size());
    assertEquals(new EventSummary("1", "Football"), summaries.get(0));
    assertEquals(1, cards.size());
    assertEquals(new EventCard("1", "Maracana"), cards.get(0));
  }

  @Test
  void whenDefiningDuplicateViewType_shouldThrowIllegalArg() {
    assertThrows(IllegalArgumentException.class, () ->
        Catalog.of(Event.class)
            .named("events")
            .data(List.of())
            .index("by-venue").by(Event::venue, Venue::name)
            .view(EventSummary.class,
                e -> new EventSummary(e.id(), e.sport().name()))
            .view(EventSummary.class,
                e -> new EventSummary(e.id(), "other"))
            .build()
    );
  }

  @Test
  void whenRefreshing_givenViewsDefined_shouldRecomputeViews() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));
    final Event e2 = new Event("2", new Sport("Rugby"),
        new Venue("Wembley"));

    final List<List<Event>> dataSets = List.of(
        List.of(e1), List.of(e1, e2));
    final int[] callCount = {0};

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> dataSets.get(callCount[0]++))
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class,
            e -> new EventSummary(e.id(), e.sport().name()))
        .build();

    catalog.bootstrap();
    assertEquals(1, catalog.search(
        "by-venue", "Maracana", EventSummary.class).size());

    catalog.refresh();
    assertEquals(1, catalog.search(
        "by-venue", "Wembley", EventSummary.class).size());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=CatalogViewTest -DfailIfNoTests=false`
Expected: Compilation error — `Catalog.view()` and `Catalog.search(..., Class)` don't exist.

- [ ] **Step 3: Modify Catalog**

Changes to `Catalog.java`:

1. **Add field** to Catalog:
```java
/** The view definitions, never null. */
private final List<ViewDefinition<T, ?>> viewDefinitions;
```

2. **Update constructor** to accept `List<ViewDefinition<T, ?>> viewDefinitions` and store it.

3. **Add to BuildStep:**
   - New field: `private final List<ViewDefinition<T, ?>> viewDefinitions = new ArrayList<>();`
   - New field: `private final Set<Class<?>> viewTypes = new HashSet<>();`
   - New method:
```java
public <V> BuildStep<T> view(final Class<V> viewType,
    final Function<T, V> mapper) {
  Objects.requireNonNull(viewType, "viewType must not be null");
  Objects.requireNonNull(mapper, "mapper must not be null");
  if (!viewTypes.add(viewType)) {
    throw new IllegalArgumentException(
        "Duplicate view type: " + viewType.getSimpleName());
  }
  viewDefinitions.add(ViewDefinition.of(viewType, mapper));
  return this;
}
```
   - Update `build()` to pass `viewDefinitions` to the Catalog constructor.

4. **Update `buildAndSwapSnapshot(List<T> data)`:**
   - Wrap each `T` into `AndersoniCatalogItem<T>` applying all view mappers.
   - Build indices on items (using the wrapper, extracting `T` for key computation).
   - Create snapshot using `Snapshot.ofWithItems(...)`.

```java
private void buildAndSwapSnapshot(final List<T> data) {
  // Wrap items with views.
  final List<AndersoniCatalogItem<T>> wrappedItems = new ArrayList<>(data.size());
  for (final T datum : data) {
    final Map<Class<?>, Object> views = new HashMap<>();
    for (final ViewDefinition<T, ?> viewDef : viewDefinitions) {
      views.put(viewDef.viewType(), viewDef.mapper().apply(datum));
    }
    wrappedItems.add(AndersoniCatalogItem.of(datum, views));
  }

  // Build regular indices (keyed on T, values are AndersoniCatalogItem<T>).
  final Map<String, Map<Object, List<AndersoniCatalogItem<T>>>> indices =
      new HashMap<>();
  for (final IndexDefinition<T> indexDef : indexDefinitions) {
    indices.put(indexDef.name(),
        indexDef.buildIndexFromItems(wrappedItems));
  }

  // Build multi-key indices.
  for (final MultiKeyIndexDefinition<T> multiDef
      : multiKeyIndexDefinitions) {
    indices.put(multiDef.name(),
        multiDef.buildIndexFromItems(wrappedItems));
  }

  // Build graph indices.
  for (final GraphIndexDefinition<T> graphDef
      : graphIndexDefinitions) {
    indices.put(graphDef.name(),
        graphDef.buildIndexFromItems(wrappedItems));
  }

  // Build sorted indices.
  final Map<String, NavigableMap<Comparable<?>,
      List<AndersoniCatalogItem<T>>>> sortedIndices = new HashMap<>();
  final Map<String, NavigableMap<String,
      List<AndersoniCatalogItem<T>>>> reversedKeyIndices = new HashMap<>();

  for (final SortedIndexDefinition<T> sortedDef
      : sortedIndexDefinitions) {
    final SortedIndexDefinition.SortedIndexResult<
        AndersoniCatalogItem<T>> result =
        sortedDef.buildIndexFromItems(wrappedItems);
    indices.put(sortedDef.name(), result.hashIndex());
    sortedIndices.put(sortedDef.name(), result.sortedIndex());
    if (result.hasStringKeys() && result.reversedKeyIndex() != null) {
      reversedKeyIndices.put(sortedDef.name(),
          result.reversedKeyIndex());
    }
  }

  final long version = versionCounter.incrementAndGet();
  final String hash = computeHash(data);

  final Snapshot<T> snapshot = Snapshot.ofWithItems(wrappedItems,
      indices, sortedIndices, reversedKeyIndices, version, hash);
  current.set(snapshot);
}
```

**Note:** The index definition classes (`IndexDefinition`, `SortedIndexDefinition`, `MultiKeyIndexDefinition`, `GraphIndexDefinition`) need a new `buildIndexFromItems(List<AndersoniCatalogItem<T>>)` method that extracts `item()` for key computation but stores the full `AndersoniCatalogItem<T>` in the index. This is a package-private method added to each index definition class. Alternatively, a single utility method can be added. The simplest approach: add a `buildIndexFromItems` method to each index definition that delegates key extraction to the existing functions but groups `AndersoniCatalogItem<T>` wrappers.

5. **Add view-aware search** to Catalog:
```java
public <V> List<V> search(final String indexName, final Object key,
    final Class<V> viewType) {
  return current.get().search(indexName, key, viewType);
}
```

6. **Add view-aware query** to Catalog:
```java
public <V> QueryStep<T> query(final String indexName) {
  // unchanged — QueryStep gets view support in Task 5
}
```

- [ ] **Step 4: Add `buildIndexFromItems` to IndexDefinition, MultiKeyIndexDefinition, GraphIndexDefinition, SortedIndexDefinition**

Each index definition class gets a new package-private method. Example for `IndexDefinition`:

```java
Map<Object, List<AndersoniCatalogItem<T>>> buildIndexFromItems(
    final List<AndersoniCatalogItem<T>> items) {
  final Map<Object, List<AndersoniCatalogItem<T>>> index = new HashMap<>();
  for (final AndersoniCatalogItem<T> entry : items) {
    final Object key = keyExtractor.apply(entry.item());
    if (key != null) {
      index.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
    }
  }
  final Map<Object, List<AndersoniCatalogItem<T>>> unmodifiable =
      new HashMap<>();
  for (final Map.Entry<Object, List<AndersoniCatalogItem<T>>> e
      : index.entrySet()) {
    unmodifiable.put(e.getKey(),
        Collections.unmodifiableList(e.getValue()));
  }
  return Collections.unmodifiableMap(unmodifiable);
}
```

Similar pattern for `MultiKeyIndexDefinition`, `GraphIndexDefinition`, and `SortedIndexDefinition` — extract `item()` for key computation, store the full wrapper.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=CatalogViewTest`
Expected: All 6 tests PASS.

- [ ] **Step 6: Run all existing Catalog tests to verify no regression**

Run: `mvn test -pl andersoni-core -Dtest="CatalogTest,CatalogMultiKeyTest,CatalogGraphIndexTest,CatalogInfoTest,CatalogConcurrencyTest"`
Expected: All existing tests PASS.

- [ ] **Step 7: Commit**

```
feat: add view support to Catalog DSL and snapshot build
```

---

### Task 5: QueryStep — view overloads

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/QueryStep.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/QueryStepViewTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class QueryStepViewTest {

  record Event(String id, String name, int rank) {}

  record EventSummary(String id) {}

  @Test
  void whenQueryingEqualToWithView_shouldReturnViews() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(
            new Event("1", "Alpha", 10),
            new Event("2", "Beta", 20)))
        .index("by-name").by(Event::name, n -> n)
        .view(EventSummary.class, e -> new EventSummary(e.id()))
        .build();

    catalog.bootstrap();

    final List<EventSummary> result = catalog.query("by-name")
        .equalTo("Alpha", EventSummary.class);

    assertEquals(1, result.size());
    assertEquals(new EventSummary("1"), result.get(0));
  }

  @Test
  void whenQueryingBetweenWithView_shouldReturnViews() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(
            new Event("1", "A", 10),
            new Event("2", "B", 20),
            new Event("3", "C", 30)))
        .indexSorted("by-rank").by(Event::rank, r -> r)
        .view(EventSummary.class, e -> new EventSummary(e.id()))
        .build();

    catalog.bootstrap();

    final List<EventSummary> result = catalog.query("by-rank")
        .between(10, 25, EventSummary.class);

    assertEquals(2, result.size());
  }

  @Test
  void whenQueryingStartsWithWithView_shouldReturnViews() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(
            new Event("1", "Alpha", 10),
            new Event("2", "Alpine", 20),
            new Event("3", "Beta", 30)))
        .indexSorted("by-name").by(Event::name, n -> n)
        .view(EventSummary.class, e -> new EventSummary(e.id()))
        .build();

    catalog.bootstrap();

    final List<EventSummary> result = catalog.query("by-name")
        .startsWith("Alp", EventSummary.class);

    assertEquals(2, result.size());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=QueryStepViewTest -DfailIfNoTests=false`
Expected: Compilation error — view overloads don't exist on QueryStep.

- [ ] **Step 3: Add view overloads to QueryStep**

For each existing query method, add a view overload. Pattern:

```java
public <V> List<V> equalTo(final Object key, final Class<V> viewType) {
  Objects.requireNonNull(key, "key must not be null");
  Objects.requireNonNull(viewType, "viewType must not be null");
  if (!snapshot.hasIndex(indexName)) {
    throw new IndexNotFoundException(indexName, catalogName);
  }
  return snapshot.search(indexName, key, viewType);
}

public <V> List<V> between(final Comparable<?> from,
    final Comparable<?> to, final Class<V> viewType) {
  Objects.requireNonNull(from, "from must not be null");
  Objects.requireNonNull(to, "to must not be null");
  Objects.requireNonNull(viewType, "viewType must not be null");
  return snapshot.searchBetween(indexName, from, to, viewType);
}

public <V> List<V> greaterThan(final Comparable<?> key,
    final Class<V> viewType) {
  Objects.requireNonNull(key, "key must not be null");
  Objects.requireNonNull(viewType, "viewType must not be null");
  return snapshot.searchGreaterThan(indexName, key, viewType);
}

public <V> List<V> greaterOrEqual(final Comparable<?> key,
    final Class<V> viewType) {
  Objects.requireNonNull(key, "key must not be null");
  Objects.requireNonNull(viewType, "viewType must not be null");
  return snapshot.searchGreaterOrEqual(indexName, key, viewType);
}

public <V> List<V> lessThan(final Comparable<?> key,
    final Class<V> viewType) {
  Objects.requireNonNull(key, "key must not be null");
  Objects.requireNonNull(viewType, "viewType must not be null");
  return snapshot.searchLessThan(indexName, key, viewType);
}

public <V> List<V> lessOrEqual(final Comparable<?> key,
    final Class<V> viewType) {
  Objects.requireNonNull(key, "key must not be null");
  Objects.requireNonNull(viewType, "viewType must not be null");
  return snapshot.searchLessOrEqual(indexName, key, viewType);
}

public <V> List<V> startsWith(final String prefix,
    final Class<V> viewType) {
  Objects.requireNonNull(prefix, "prefix must not be null");
  Objects.requireNonNull(viewType, "viewType must not be null");
  return snapshot.searchStartsWith(indexName, prefix, viewType);
}

public <V> List<V> endsWith(final String suffix,
    final Class<V> viewType) {
  Objects.requireNonNull(suffix, "suffix must not be null");
  Objects.requireNonNull(viewType, "viewType must not be null");
  return snapshot.searchEndsWith(indexName, suffix, viewType);
}

public <V> List<V> contains(final String substring,
    final Class<V> viewType) {
  Objects.requireNonNull(substring, "substring must not be null");
  Objects.requireNonNull(viewType, "viewType must not be null");
  return snapshot.searchContains(indexName, substring, viewType);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=QueryStepViewTest`
Expected: All 3 tests PASS.

- [ ] **Step 5: Run existing QueryStep tests**

Run: `mvn test -pl andersoni-core -Dtest=QueryStepTest`
Expected: All existing tests PASS.

- [ ] **Step 6: Commit**

```
feat: add view overloads to QueryStep
```

---

### Task 6: CompoundQuery and GraphQueryBuilder — view overloads

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/CompoundQuery.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/GraphQueryBuilder.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/CompoundQueryViewTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class CompoundQueryViewTest {

  record Sport(String name) {}

  record Venue(String name) {}

  record Event(String id, Sport sport, Venue venue) {}

  record EventSummary(String id) {}

  @Test
  void whenExecutingCompoundWithView_shouldReturnViews() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));
    final Event e2 = new Event("2", new Sport("Rugby"),
        new Venue("Wembley"));
    final Event e3 = new Event("3", new Sport("Football"),
        new Venue("Wembley"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1, e2, e3))
        .index("by-sport").by(Event::sport, Sport::name)
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class, e -> new EventSummary(e.id()))
        .build();

    catalog.bootstrap();

    final List<EventSummary> result = catalog.compound()
        .where("by-sport").equalTo("Football")
        .and("by-venue").equalTo("Wembley")
        .execute(EventSummary.class);

    assertEquals(1, result.size());
    assertEquals(new EventSummary("3"), result.get(0));
  }

  @Test
  void whenExecutingCompoundWithoutView_shouldReturnItemsAsUsual() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-sport").by(Event::sport, Sport::name)
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class, e -> new EventSummary(e.id()))
        .build();

    catalog.bootstrap();

    final List<Event> result = catalog.compound()
        .where("by-sport").equalTo("Football")
        .execute();

    assertEquals(1, result.size());
    assertEquals(e1, result.get(0));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=CompoundQueryViewTest -DfailIfNoTests=false`
Expected: Compilation error — `execute(Class)` doesn't exist.

- [ ] **Step 3: Add `execute(Class<V>)` to CompoundQuery**

The CompoundQuery's `execute()` currently returns `List<T>`. It internally works with `List<T>` from snapshot searches. Since the snapshot now stores `AndersoniCatalogItem<T>`, the CompoundQuery needs access to the view extraction.

Add a new method:

```java
public <V> List<V> execute(final Class<V> viewType) {
  Objects.requireNonNull(viewType, "viewType must not be null");
  if (conditions.isEmpty()) {
    throw new IllegalStateException(
        "At least one condition is required. "
            + "Call where() before execute()");
  }

  List<AndersoniCatalogItem<T>> result =
      evaluateConditionItems(conditions.get(0));

  if (result.isEmpty()) {
    return Collections.emptyList();
  }

  for (int i = 1; i < conditions.size(); i++) {
    final List<AndersoniCatalogItem<T>> candidates =
        evaluateConditionItems(conditions.get(i));

    if (candidates.isEmpty()) {
      return Collections.emptyList();
    }

    result = intersectItems(result, candidates);

    if (result.isEmpty()) {
      return Collections.emptyList();
    }
  }

  final List<V> views = new ArrayList<>(result.size());
  for (final AndersoniCatalogItem<T> entry : result) {
    views.add(entry.view(viewType));
  }
  return Collections.unmodifiableList(views);
}
```

**Note:** This requires the CompoundQuery to work internally with `AndersoniCatalogItem<T>`. The cleanest approach is to refactor the existing `execute()` to also work with items internally and then extract `T` at the end, keeping the `execute(Class<V>)` as a view extraction variant.

- [ ] **Step 4: Add `execute(Class<V>)` to GraphQueryBuilder**

```java
public <V> List<V> execute(final Class<V> viewType) {
  Objects.requireNonNull(viewType, "viewType must not be null");
  if (conditions.isEmpty()) {
    return Collections.emptyList();
  }

  final QueryPlanner.Plan<T> plan =
      QueryPlanner.plan(graphIndexes, conditions);

  if (plan == null) {
    return Collections.emptyList();
  }

  if (!plan.postFilterConditions().isEmpty()) {
    final String uncovered = plan.postFilterConditions().stream()
        .map(GraphQueryCondition::fieldName)
        .collect(Collectors.joining(", "));
    throw new UnsupportedOperationException(
        "Graph query has conditions not covered by any hotpath: ["
            + uncovered + "]. Define a graph index that covers all"
            + " query fields, or use compound() for uncovered fields.");
  }

  return snapshot.search(plan.graphIndexName(), plan.key(), viewType);
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=CompoundQueryViewTest`
Expected: All 2 tests PASS.

- [ ] **Step 6: Run existing compound and graph query tests**

Run: `mvn test -pl andersoni-core -Dtest="CompoundQueryTest,CompoundQueryIntegrationTest,GraphQueryBuilderTest"`
Expected: All existing tests PASS.

- [ ] **Step 7: Commit**

```
feat: add view overloads to CompoundQuery and GraphQueryBuilder
```

---

### Task 7: Andersoni — view overloads on the entry point

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Andersoni.java`
- Create: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniViewTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class AndersoniViewTest {

  record Sport(String name) {}

  record Venue(String name) {}

  record Event(String id, Sport sport, Venue venue) {}

  record EventSummary(String id, String sportName) {}

  @Test
  void whenSearchingWithView_shouldReturnViews() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));
    final Event e2 = new Event("2", new Sport("Rugby"),
        new Venue("Wembley"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1, e2))
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class,
            e -> new EventSummary(e.id(), e.sport().name()))
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .build();
    andersoni.register(catalog);
    andersoni.start();

    final List<EventSummary> result = andersoni.search(
        "events", "by-venue", "Maracana", EventSummary.class);

    assertEquals(1, result.size());
    assertEquals(new EventSummary("1", "Football"), result.get(0));
  }

  @Test
  void whenQueryingWithView_shouldReturnViews() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class,
            e -> new EventSummary(e.id(), e.sport().name()))
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .build();
    andersoni.register(catalog);
    andersoni.start();

    final List<EventSummary> result = andersoni.query(
        "events", "by-venue").equalTo("Maracana", EventSummary.class);

    assertEquals(1, result.size());
    assertEquals(new EventSummary("1", "Football"), result.get(0));
  }

  @Test
  void whenCompoundWithView_shouldReturnViews() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));
    final Event e2 = new Event("2", new Sport("Rugby"),
        new Venue("Maracana"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1, e2))
        .index("by-sport").by(Event::sport, Sport::name)
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class,
            e -> new EventSummary(e.id(), e.sport().name()))
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .build();
    andersoni.register(catalog);
    andersoni.start();

    final List<EventSummary> result = andersoni.compound("events")
        .where("by-sport").equalTo("Football")
        .and("by-venue").equalTo("Maracana")
        .execute(EventSummary.class);

    assertEquals(1, result.size());
    assertEquals(new EventSummary("1", "Football"), result.get(0));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=AndersoniViewTest -DfailIfNoTests=false`
Expected: The existing `search(catalogName, indexName, key, Class<T> type)` method on Andersoni does an unchecked cast. The new view overload needs a different signature to distinguish "I want a view" from "I want a typed cast." 

**Important design decision:** The existing `search(String, String, Object, Class<T>)` method on Andersoni is a typed convenience cast. The view variant has the same signature shape. We need to differentiate them. The approach: the existing typed method remains as-is (it casts to the catalog's `T` type). For views, we add a separate method name or change the existing typed method to also support views transparently.

Since the view system works through the Catalog and Snapshot, and the Catalog's `search(indexName, key, viewClass)` throws `IllegalArgumentException` for unregistered views, the simplest approach is: **the existing typed `search` method on Andersoni already delegates to `catalog.search(indexName, key)` and casts**. We change it to delegate to `catalog.search(indexName, key, type)` which first checks if `type` is a registered view. If it is, it returns the view. If not, it returns the items cast to `type`. This makes the API unified.

However, this could break the existing behavior where `type` is `T` (the catalog's own type). The Catalog needs to distinguish: "Is this a view type or the item type?"

**Cleaner approach:** Add a `searchView` method to Andersoni (and similar for query/compound), or make the Catalog's `search(indexName, key, viewType)` handle both cases — if `viewType` matches `T`, return items; if it's a registered view, return views; if neither, throw.

**Simplest approach that doesn't break anything:** Keep the existing typed methods as-is. Add new overloads that explicitly state they are view queries. Since the method signatures would collide (same parameter types), we use the same method but add logic in Catalog to handle both cases.

Let me revise: In Catalog, the `search(indexName, key, viewType)` method checks if `viewType` is a registered view. If yes, return views. If the catalog has no views registered for that type, fall back to the existing behavior (unchecked cast to T). This way, the existing `Andersoni.search(catalogName, indexName, key, Class<T>)` can simply delegate to `catalog.search(indexName, key, type)` and it works for both scenarios.

- [ ] **Step 3: Modify Andersoni's typed search to delegate with view support**

Update `Andersoni.search(String, String, Object, Class<T>)`:

```java
@SuppressWarnings("unchecked")
public <T> List<T> search(final String catalogName, final String indexName,
    final Object key, final Class<T> type) {
  Objects.requireNonNull(type, "type must not be null");
  Objects.requireNonNull(catalogName, "catalogName must not be null");
  Objects.requireNonNull(indexName, "indexName must not be null");
  Objects.requireNonNull(key, "key must not be null");

  final Catalog<?> catalog = requireCatalog(catalogName);

  if (failedCatalogs.contains(catalogName)) {
    throw new CatalogNotAvailableException(catalogName);
  }

  return catalog.searchWithType(indexName, key, type);
}
```

In Catalog, add:
```java
@SuppressWarnings("unchecked")
<V> List<V> searchWithType(final String indexName, final Object key,
    final Class<V> type) {
  if (hasView(type)) {
    return current.get().search(indexName, key, type);
  }
  return (List<V>) current.get().search(indexName, key);
}

boolean hasView(final Class<?> type) {
  for (final ViewDefinition<T, ?> viewDef : viewDefinitions) {
    if (viewDef.viewType().equals(type)) {
      return true;
    }
  }
  return false;
}
```

Same pattern applies to query/compound/graphQuery — the existing typed methods already work because the QueryStep/CompoundQuery/GraphQueryBuilder view overloads are separate methods with the extra `Class<V>` parameter.

For `compound` and `graphQuery`, the existing typed methods return typed builders. The view overload is `execute(Class<V>)` on the builder itself, so no changes needed on Andersoni — the user calls `andersoni.compound("events").where(...).execute(EventSummary.class)`.

For `query`, the existing typed method returns `QueryStep<T>`. The view overload is on QueryStep methods like `equalTo(key, viewClass)`, so no changes needed on Andersoni.

So the only change on Andersoni is in the `search` method.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest=AndersoniViewTest`
Expected: All 3 tests PASS.

- [ ] **Step 5: Run all existing Andersoni tests**

Run: `mvn test -pl andersoni-core -Dtest="AndersoniTest,AndersoniIntegrationTest,AndersoniCompoundTest"`
Expected: All existing tests PASS.

- [ ] **Step 6: Commit**

```
feat: add view support to Andersoni entry point
```

---

### Task 8: CatalogInfo — view metadata

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/CatalogInfo.java`
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/Catalog.java` (info method)

- [ ] **Step 1: Write the failing test**

```java
// Add to CatalogViewTest.java

@Test
void whenGettingInfo_givenViewsDefined_shouldIncludeViewMetadata() {
  final Event e1 = new Event("1", new Sport("Football"),
      new Venue("Maracana"));

  final Catalog<Event> catalog = Catalog.of(Event.class)
      .named("events")
      .data(List.of(e1))
      .index("by-venue").by(Event::venue, Venue::name)
      .view(EventSummary.class,
          e -> new EventSummary(e.id(), e.sport().name()))
      .view(EventCard.class,
          e -> new EventCard(e.id(), e.venue().name()))
      .build();

  catalog.bootstrap();

  final CatalogInfo info = catalog.info();

  assertEquals(2, info.viewCount());
  assertTrue(info.viewTypeNames().contains("EventSummary"));
  assertTrue(info.viewTypeNames().contains("EventCard"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl andersoni-core -Dtest=CatalogViewTest#whenGettingInfo_givenViewsDefined_shouldIncludeViewMetadata`
Expected: Compilation error — `viewCount()` and `viewTypeNames()` don't exist on CatalogInfo.

- [ ] **Step 3: Update CatalogInfo**

```java
public record CatalogInfo(
    String catalogName,
    int itemCount,
    List<IndexInfo> indices,
    long totalEstimatedSizeBytes,
    int viewCount,
    List<String> viewTypeNames
) {
  public CatalogInfo {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(indices, "indices must not be null");
    Objects.requireNonNull(viewTypeNames,
        "viewTypeNames must not be null");
    indices = List.copyOf(indices);
    viewTypeNames = List.copyOf(viewTypeNames);
  }

  public double totalEstimatedSizeMB() {
    return totalEstimatedSizeBytes / (1024.0 * 1024.0);
  }
}
```

Update `Catalog.info()` to pass view count and view type names:

```java
public CatalogInfo info() {
  final Snapshot<T> snapshot = current.get();
  final List<IndexInfo> indexInfos = snapshot.indexInfo();
  final long totalSize = indexInfos.stream()
      .mapToLong(IndexInfo::estimatedSizeBytes).sum();

  final List<String> viewTypeNames = viewDefinitions.stream()
      .map(v -> v.viewType().getSimpleName())
      .toList();

  return new CatalogInfo(name, snapshot.data().size(), indexInfos,
      totalSize, viewDefinitions.size(), viewTypeNames);
}
```

- [ ] **Step 4: Fix all existing CatalogInfo usages** to include the new fields.

Update existing tests and any code that constructs `CatalogInfo` to include `viewCount` (0) and `viewTypeNames` (empty list) for backward compatibility.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -pl andersoni-core -Dtest="CatalogViewTest,CatalogInfoTest"`
Expected: All tests PASS.

- [ ] **Step 6: Commit**

```
feat: add view metadata to CatalogInfo
```

---

### Task 9: Full integration test and regression suite

**Files:**
- Modify: `andersoni-core/src/test/java/org/waabox/andersoni/AndersoniViewTest.java`

- [ ] **Step 1: Add comprehensive integration tests**

```java
@Test
void whenUsingViewsWithSortedIndex_shouldSupportAllRangeQueries() {
  final Event e1 = new Event("1", new Sport("A"), new Venue("V1"));
  final Event e2 = new Event("2", new Sport("B"), new Venue("V2"));
  final Event e3 = new Event("3", new Sport("C"), new Venue("V3"));
  final Event e4 = new Event("4", new Sport("D"), new Venue("V4"));

  final Catalog<Event> catalog = Catalog.of(Event.class)
      .named("events")
      .data(List.of(e1, e2, e3, e4))
      .indexSorted("by-sport").by(Event::sport, Sport::name)
      .view(EventSummary.class,
          e -> new EventSummary(e.id(), e.sport().name()))
      .build();

  final Andersoni andersoni = Andersoni.builder()
      .nodeId("node-1")
      .build();
  andersoni.register(catalog);
  andersoni.start();

  // between
  List<EventSummary> result = andersoni.query("events", "by-sport")
      .between("A", "C", EventSummary.class);
  assertEquals(3, result.size());

  // greaterThan
  result = andersoni.query("events", "by-sport")
      .greaterThan("B", EventSummary.class);
  assertEquals(2, result.size());

  // lessThan
  result = andersoni.query("events", "by-sport")
      .lessThan("C", EventSummary.class);
  assertEquals(2, result.size());

  // startsWith
  result = andersoni.query("events", "by-sport")
      .startsWith("A", EventSummary.class);
  assertEquals(1, result.size());
  assertEquals(new EventSummary("1", "A"), result.get(0));
}

@Test
void whenUsingViewsWithNoViewsDefined_shouldWorkNormally() {
  final Event e1 = new Event("1", new Sport("Football"),
      new Venue("Maracana"));

  final Catalog<Event> catalog = Catalog.of(Event.class)
      .named("events")
      .data(List.of(e1))
      .index("by-venue").by(Event::venue, Venue::name)
      .build();

  final Andersoni andersoni = Andersoni.builder()
      .nodeId("node-1")
      .build();
  andersoni.register(catalog);
  andersoni.start();

  final List<Event> result = andersoni.search(
      "events", "by-venue", "Maracana", Event.class);

  assertEquals(1, result.size());
  assertEquals(e1, result.get(0));
}
```

- [ ] **Step 2: Run the full test suite**

Run: `mvn clean verify -pl andersoni-core`
Expected: All tests PASS, including all existing tests and all new view tests.

- [ ] **Step 3: Commit**

```
test: add comprehensive integration tests for catalog views
```

---

### Task 10: Serialization compatibility

**Files:**
- Modify: `andersoni-core/src/main/java/org/waabox/andersoni/AndersoniCatalogItem.java` (make public if needed for Jackson)
- Modify: `andersoni-json-serializer/src/main/java/org/waabox/andersoni/snapshot/json/JacksonSnapshotSerializer.java` (if changes needed)

The `SnapshotSerializer<T>` interface is unchanged. However, when a catalog has views defined, Andersoni internally works with `AndersoniCatalogItem<T>`. The `computeHash` method in Catalog calls `serializer.serialize(data)` where `data` is `List<T>`. Since the serializer is typed as `SnapshotSerializer<T>` (where `T` is the user's domain type, e.g., `Event`), the hash computation and snapshot persistence must serialize `List<T>` (the raw items, not the wrapper).

- [ ] **Step 1: Verify that `computeHash` in Catalog extracts raw items for serialization**

The `computeHash(List<T> data)` method already receives `List<T>` (raw items), not the wrapped items. Verify this is still the case after the Task 4 refactor. If `buildAndSwapSnapshot` was changed to work with wrapped items, ensure `computeHash` is called with the original raw `data` list, not the wrapped list.

- [ ] **Step 2: Verify snapshot store integration**

Check that `Andersoni.refreshAndSync` and any snapshot store persistence code serializes the raw `List<T>` items, not the `AndersoniCatalogItem<T>` wrappers. The serializer the user provides only knows about `T`.

- [ ] **Step 3: Test serialization round-trip with views**

```java
// Add to CatalogViewTest.java

@Test
void whenSerializingAndDeserializing_givenViews_shouldPreserveItems() {
  final Event e1 = new Event("1", new Sport("Football"),
      new Venue("Maracana"));

  final TestSerializer serializer = new TestSerializer();

  final Catalog<Event> catalog = Catalog.of(Event.class)
      .named("events")
      .data(List.of(e1))
      .index("by-venue").by(Event::venue, Venue::name)
      .serializer(serializer)
      .view(EventSummary.class,
          e -> new EventSummary(e.id(), e.sport().name()))
      .build();

  catalog.bootstrap();

  // Verify hash is computed from raw items (not wrapped)
  final Snapshot<Event> snapshot = catalog.currentSnapshot();
  assertNotNull(snapshot.hash());
  assertFalse(snapshot.hash().isEmpty());
}
```

- [ ] **Step 4: Commit if changes needed**

```
fix: ensure serialization uses raw items, not view wrappers
```

---

### Task 11: Run full project build

- [ ] **Step 1: Run full project build**

Run: `mvn clean verify`
Expected: Full build passes. All modules compile and all tests pass.

- [ ] **Step 2: Fix any compilation issues in other modules**

If other modules depend on `CatalogInfo` (which now has new fields), update them.

- [ ] **Step 3: Final commit if any fixes needed**

```
fix: update CatalogInfo usages across modules for view fields
```
