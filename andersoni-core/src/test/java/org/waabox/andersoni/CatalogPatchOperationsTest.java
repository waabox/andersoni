package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Catalog#insert(Object)},
 * {@link Catalog#remove(String, Object)} and
 * {@link Catalog#upsert(String, Object, Object)}.
 */
class CatalogPatchOperationsTest {

  record Venue(String name) {}

  record EventDate(LocalDate value) implements Comparable<EventDate> {
    @Override
    public int compareTo(final EventDate other) {
      return value.compareTo(other.value);
    }
  }

  record Event(String id, Venue venue, EventDate date, List<String> tags) {
    static Event of(final String id, final String venue, final String day,
        final String... tags) {
      return new Event(id, new Venue(venue),
          new EventDate(LocalDate.parse(day)), List.of(tags));
    }
  }

  record EventSummary(String id, String venueName) {
    static EventSummary from(final Event event) {
      return new EventSummary(event.id(), event.venue().name());
    }
  }

  private static Catalog<Event> catalogOf(final List<Event> data) {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(data)
        .index("by-id").by(Event::id, id -> id)
        .index("by-venue").by(Event::venue, Venue::name)
        .indexSorted("by-date").by(Event::date, EventDate::value)
        .indexMulti("by-tag").by(Event::tags)
        .view(EventSummary.class, EventSummary::from)
        .build();
    catalog.bootstrap();
    return catalog;
  }

  private static final Event A = Event.of("a", "Wembley", "2026-01-01", "x");
  private static final Event B = Event.of("b", "Camp Nou", "2026-02-01", "y");

  @Test
  void whenInsert_givenNewItem_shouldAppearInEveryIndex() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A, B)));
    final Event c = Event.of("c", "Anfield", "2026-03-01", "x", "z");

    catalog.insert(c);

    assertEquals(List.of(c), catalog.search("by-id", "c"));
    assertEquals(List.of(c), catalog.search("by-venue", "Anfield"));
    assertEquals(List.of(A, c), catalog.search("by-tag", "x"));
    assertEquals(List.of(c), catalog.search("by-tag", "z"));
    assertEquals(List.of(c), catalog.query("by-date")
        .greaterThan(LocalDate.parse("2026-02-15")));
    assertEquals(3, catalog.currentSnapshot().data().size());
  }

  @Test
  void whenInsert_shouldMatchAFullRebuildOfTheSameItems() {
    final Catalog<Event> patched = catalogOf(new ArrayList<>(List.of(A, B)));
    final Event c = Event.of("c", "Anfield", "2026-03-01", "z");

    patched.insert(c);

    final Catalog<Event> rebuilt =
        catalogOf(new ArrayList<>(List.of(A, B, c)));
    assertEquals(rebuilt.currentSnapshot().hash(),
        patched.currentSnapshot().hash());
    assertEquals(rebuilt.currentSnapshot().data(),
        patched.currentSnapshot().data());
    assertEquals(rebuilt.search("by-tag", "z"), patched.search("by-tag", "z"));
  }

  @Test
  void whenInsert_shouldComputeViewsAndRunHooksForTheNewItemOnly() {
    final AtomicInteger hookCalls = new AtomicInteger();
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(new ArrayList<>(List.of(A, B)))
        .index("by-id").by(Event::id, id -> id)
        .view(EventSummary.class, EventSummary::from)
        .hook(new SnapshotBuildHook<Event>() {
          @Override
          public Event process(final Event item) {
            hookCalls.incrementAndGet();
            return item;
          }

          @Override
          public boolean supportsPatch() {
            return true;
          }
        })
        .build();
    catalog.bootstrap();
    final int afterBuild = hookCalls.get();

    final Event c = Event.of("c", "Anfield", "2026-03-01");
    catalog.insert(c);

    assertEquals(afterBuild + 1, hookCalls.get());
    assertEquals(List.of(new EventSummary("c", "Anfield")),
        catalog.search("by-id", "c", EventSummary.class));
  }

  @Test
  void whenInsert_shouldShareUntouchedBucketsWithThePreviousSnapshot() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A, B)));
    final Snapshot<Event> before = catalog.currentSnapshot();

    catalog.insert(Event.of("c", "Anfield", "2026-03-01", "z"));

    final Snapshot<Event> after = catalog.currentSnapshot();
    assertSame(before.indicesMap().get("by-id").get("a"),
        after.indicesMap().get("by-id").get("a"),
        "A bucket the insert does not touch must be shared, not rebuilt");
    assertEquals(List.of(A, B), before.data(),
        "The previous snapshot must be unchanged by the insert");
  }

  @Test
  void whenRemove_givenMatch_shouldDropItFromEveryIndex() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A, B)));

    assertTrue(catalog.remove("by-id", "a"));

    assertEquals(List.of(), catalog.search("by-id", "a"));
    assertEquals(List.of(), catalog.search("by-venue", "Wembley"));
    assertEquals(List.of(), catalog.search("by-tag", "x"));
    assertEquals(List.of(), catalog.query("by-date")
        .lessThan(LocalDate.parse("2026-01-15")));
    assertEquals(List.of(B), catalog.currentSnapshot().data());
  }

  @Test
  void whenRemove_shouldDropTheEmptiedKeyRatherThanLeaveAnEmptyBucket() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A, B)));

    catalog.remove("by-id", "a");

    final Snapshot<Event> after = catalog.currentSnapshot();
    assertFalse(after.indicesMap().get("by-id").containsKey("a"),
        "An emptied bucket must be removed, or indices leak keys");
    assertFalse(after.indicesMap().get("by-venue").containsKey("Wembley"));
    assertFalse(after.sortedIndicesMap().get("by-date")
        .containsKey(LocalDate.parse("2026-01-01")));
  }

  @Test
  void whenRemove_shouldMatchAFullRebuildOfTheRemainingItems() {
    final Catalog<Event> patched = catalogOf(new ArrayList<>(List.of(A, B)));

    patched.remove("by-id", "a");

    final Catalog<Event> rebuilt = catalogOf(new ArrayList<>(List.of(B)));
    assertEquals(rebuilt.currentSnapshot().hash(),
        patched.currentSnapshot().hash());
    assertEquals(rebuilt.currentSnapshot().data(),
        patched.currentSnapshot().data());
  }

  @Test
  void whenRemove_givenNoMatch_shouldBeANoOp() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A, B)));
    final Snapshot<Event> before = catalog.currentSnapshot();

    assertFalse(catalog.remove("by-id", "does-not-exist"));

    assertSame(before, catalog.currentSnapshot(),
        "A removal that matches nothing must not swap the snapshot");
  }

  @Test
  void whenRemove_givenAmbiguousLookup_shouldThrowAndLeaveTheSnapshot() {
    final Event sameVenue = Event.of("c", "Wembley", "2026-03-01");
    final Catalog<Event> catalog =
        catalogOf(new ArrayList<>(List.of(A, B, sameVenue)));
    final Snapshot<Event> before = catalog.currentSnapshot();

    assertThrows(AmbiguousPatchTargetException.class,
        () -> catalog.remove("by-venue", "Wembley"));

    assertSame(before, catalog.currentSnapshot(),
        "An ambiguous removal must not mutate anything");
  }

  @Test
  void whenRemove_givenUnknownIndex_shouldThrowIndexNotFound() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A, B)));

    assertThrows(IndexNotFoundException.class,
        () -> catalog.remove("by-nothing", "a"));
  }

  @Test
  void whenUpsert_givenExistingItem_shouldReplaceAndReportReplaced() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A, B)));
    final Event moved = Event.of("a", "Anfield", "2026-01-01", "x");

    assertTrue(catalog.upsert("by-id", "a", moved));

    assertEquals(List.of(moved), catalog.search("by-id", "a"));
    assertEquals(List.of(), catalog.search("by-venue", "Wembley"));
    assertEquals(List.of(moved), catalog.search("by-venue", "Anfield"));
    assertEquals(2, catalog.currentSnapshot().data().size());
  }

  @Test
  void whenUpsert_givenAbsentItem_shouldInsertAndReportInserted() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A, B)));
    final Event c = Event.of("c", "Anfield", "2026-03-01", "z");

    assertFalse(catalog.upsert("by-id", "c", c));

    assertEquals(List.of(c), catalog.search("by-id", "c"));
    assertEquals(3, catalog.currentSnapshot().data().size());
  }

  @Test
  void whenUpsert_givenAmbiguousLookup_shouldThrowRatherThanInsert() {
    final Event sameVenue = Event.of("c", "Wembley", "2026-03-01");
    final Catalog<Event> catalog =
        catalogOf(new ArrayList<>(List.of(A, B, sameVenue)));
    final Snapshot<Event> before = catalog.currentSnapshot();

    assertThrows(AmbiguousPatchTargetException.class,
        () -> catalog.upsert("by-venue", "Wembley",
            Event.of("d", "Wembley", "2026-04-01")));

    assertSame(before, catalog.currentSnapshot(),
        "An ambiguous upsert must not fall through to an insert");
  }

  @Test
  void whenOperationsInterleave_shouldKeepVersionAndHashMovingForward() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A, B)));
    final long v0 = catalog.currentSnapshot().version();
    final String h0 = catalog.currentSnapshot().hash();

    catalog.insert(Event.of("c", "Anfield", "2026-03-01", "z"));
    final long v1 = catalog.currentSnapshot().version();
    final String h1 = catalog.currentSnapshot().hash();
    assertTrue(v1 > v0);
    assertNotEquals(h0, h1);

    catalog.remove("by-id", "c");
    final Snapshot<Event> after = catalog.currentSnapshot();
    assertTrue(after.version() > v1);
    assertEquals(h0, after.hash(),
        "Inserting then removing the same item must return to the "
            + "original content hash");
  }
}
