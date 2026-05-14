package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Catalog#replace(String, Object, Object)}.
 */
class CatalogReplaceTest {

  record Sport(String name) {}
  record Venue(String name) {}
  record Event(String id, Sport sport, Venue venue) {}

  record EventDate(LocalDate value) implements Comparable<EventDate> {
    @Override
    public int compareTo(final EventDate other) {
      return value.compareTo(other.value);
    }
  }

  record DatedEvent(String id, EventDate date) {}

  record TaggedEvent(String id, List<String> tags) {}

  record EventSummary(String id, String venueName) {
    static EventSummary from(final Event e) {
      return new EventSummary(e.id(), e.venue().name());
    }
  }

  private static Catalog<Event> buildEventCatalog(final List<Event> data) {
    return Catalog.of(Event.class)
        .named("events")
        .data(data)
        .index("by-id").by(Event::id, id -> id)
        .index("by-venue").by(Event::venue, Venue::name)
        .index("by-sport").by(Event::sport, Sport::name)
        .build();
  }

  @Test
  void whenReplace_givenSingletonBucket_shouldReplaceAndReturnTrue() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Event b = new Event("b", new Sport("G"), new Venue("V2"));
    final Catalog<Event> catalog = buildEventCatalog(new ArrayList<>(
        List.of(a, b)));
    catalog.bootstrap();

    final Event updated = new Event("a", new Sport("F"), new Venue("V3"));
    final boolean replaced = catalog.replace("by-id", "a", updated);

    assertTrue(replaced);
    assertEquals(List.of(updated), catalog.search("by-id", "a"));
    assertEquals(List.of(updated), catalog.search("by-venue", "V3"));
    assertTrue(catalog.search("by-venue", "V1").isEmpty());
    assertEquals(List.of(b), catalog.search("by-venue", "V2"));
  }

  @Test
  void whenReplace_givenEmptyBucket_shouldReturnFalse() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Catalog<Event> catalog = buildEventCatalog(new ArrayList<>(
        List.of(a)));
    catalog.bootstrap();

    final long version = catalog.currentSnapshot().version();
    final Event newItem = new Event("zzz", new Sport("F"),
        new Venue("V1"));
    final boolean replaced = catalog.replace("by-id", "zzz", newItem);

    assertFalse(replaced);
    assertEquals(version, catalog.currentSnapshot().version(),
        "version must not bump on a no-op replace");
  }

  @Test
  void whenReplace_givenMultiElementBucket_shouldThrowAmbiguous() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Event b = new Event("b", new Sport("F"), new Venue("V1"));
    final Catalog<Event> catalog = buildEventCatalog(new ArrayList<>(
        List.of(a, b)));
    catalog.bootstrap();

    final Event replacement = new Event("c", new Sport("F"),
        new Venue("V2"));
    final AmbiguousReplaceException ex = assertThrows(
        AmbiguousReplaceException.class,
        () -> catalog.replace("by-venue", "V1", replacement));

    assertTrue(ex.getMessage().contains("events"));
    assertTrue(ex.getMessage().contains("by-venue"));
    assertTrue(ex.getMessage().contains("V1"));
    assertTrue(ex.getMessage().contains("2"));
  }

  @Test
  void whenReplace_givenUnknownIndex_shouldThrowIndexNotFound() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Catalog<Event> catalog = buildEventCatalog(new ArrayList<>(
        List.of(a)));
    catalog.bootstrap();

    assertThrows(IndexNotFoundException.class,
        () -> catalog.replace("nope", "x", a));
  }

  @Test
  void whenReplace_givenNullArgs_shouldThrowNpe() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Catalog<Event> catalog = buildEventCatalog(new ArrayList<>(
        List.of(a)));
    catalog.bootstrap();

    assertThrows(NullPointerException.class,
        () -> catalog.replace(null, "a", a));
    assertThrows(NullPointerException.class,
        () -> catalog.replace("by-id", null, a));
    assertThrows(NullPointerException.class,
        () -> catalog.replace("by-id", "a", null));
  }

  @Test
  void whenReplace_givenSameKey_shouldKeepIndexConsistent() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Catalog<Event> catalog = buildEventCatalog(new ArrayList<>(
        List.of(a)));
    catalog.bootstrap();

    final Event updated = new Event("a", new Sport("F"), new Venue("V1"));
    assertTrue(catalog.replace("by-id", "a", updated));

    assertEquals(List.of(updated), catalog.search("by-id", "a"));
    assertEquals(List.of(updated), catalog.search("by-venue", "V1"));
    assertEquals(List.of(updated), catalog.search("by-sport", "F"));
  }

  @Test
  void whenReplace_givenSortedIndex_shouldUpdateNavigableMap() {
    final EventDate d1 = new EventDate(LocalDate.of(2026, 5, 14));
    final EventDate d2 = new EventDate(LocalDate.of(2026, 5, 15));
    final DatedEvent a = new DatedEvent("a", d1);
    final DatedEvent b = new DatedEvent("b", d2);
    final Catalog<DatedEvent> catalog = Catalog.of(DatedEvent.class)
        .named("dated")
        .data(new ArrayList<>(List.of(a, b)))
        .index("by-id").by(DatedEvent::id, id -> id)
        .indexSorted("by-date").by(DatedEvent::date, EventDate::value)
        .build();
    catalog.bootstrap();

    final DatedEvent updated = new DatedEvent("a", d2);
    assertTrue(catalog.replace("by-id", "a", updated));

    assertEquals(List.of(updated), catalog.search("by-id", "a"));
    assertTrue(catalog.search("by-date", d1.value()).isEmpty());
    assertEquals(2, catalog.search("by-date", d2.value()).size());

    final List<DatedEvent> between = catalog.currentSnapshot()
        .searchBetween("by-date", d1.value(), d2.value());
    assertEquals(2, between.size());
  }

  @Test
  void whenReplace_givenSortedStringIndex_shouldUpdateReversedKeyMap() {
    record Doc(String id, String name) {}

    final Doc a = new Doc("a", "alpha");
    final Doc b = new Doc("b", "beta");
    final Catalog<Doc> catalog = Catalog.of(Doc.class)
        .named("docs")
        .data(new ArrayList<>(List.of(a, b)))
        .index("by-id").by(Doc::id, id -> id)
        .indexSorted("by-name").by(Doc::name, n -> n)
        .build();
    catalog.bootstrap();

    final Doc updated = new Doc("a", "gamma");
    assertTrue(catalog.replace("by-id", "a", updated));

    assertEquals(List.of(updated),
        catalog.currentSnapshot().searchEndsWith("by-name", "amma"));
    assertTrue(catalog.currentSnapshot()
        .searchEndsWith("by-name", "lpha").isEmpty());
  }

  @Test
  void whenReplace_givenMultiKeyIndex_shouldAddRemoveBucketsForDifferingKeys() {
    final TaggedEvent a = new TaggedEvent("a", List.of("red", "blue"));
    final TaggedEvent b = new TaggedEvent("b", List.of("blue", "green"));
    final Catalog<TaggedEvent> catalog = Catalog.of(TaggedEvent.class)
        .named("tagged")
        .data(new ArrayList<>(List.of(a, b)))
        .index("by-id").by(TaggedEvent::id, id -> id)
        .indexMulti("by-tag").by(t -> List.copyOf(t.tags()))
        .build();
    catalog.bootstrap();

    final TaggedEvent updated = new TaggedEvent("a",
        List.of("blue", "yellow"));
    assertTrue(catalog.replace("by-id", "a", updated));

    assertTrue(catalog.search("by-tag", "red").isEmpty());
    assertEquals(2, catalog.search("by-tag", "blue").size());
    assertEquals(List.of(updated), catalog.search("by-tag", "yellow"));
    assertEquals(List.of(b), catalog.search("by-tag", "green"));
  }

  @Test
  void whenReplace_shouldBumpVersionAndChangeHash() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Catalog<Event> catalog = buildEventCatalog(new ArrayList<>(
        List.of(a)));
    catalog.bootstrap();

    final long versionBefore = catalog.currentSnapshot().version();
    final String hashBefore = catalog.currentSnapshot().hash();

    final Event updated = new Event("a", new Sport("G"), new Venue("V2"));
    assertTrue(catalog.replace("by-id", "a", updated));

    assertEquals(versionBefore + 1, catalog.currentSnapshot().version());
    assertNotEquals(hashBefore, catalog.currentSnapshot().hash());
  }

  @Test
  void whenReplace_shouldRecomputeViewsForNewItem() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(new ArrayList<>(List.of(a)))
        .index("by-id").by(Event::id, id -> id)
        .view(EventSummary.class, EventSummary::from)
        .build();
    catalog.bootstrap();

    final Event updated = new Event("a", new Sport("F"), new Venue("V2"));
    assertTrue(catalog.replace("by-id", "a", updated));

    final List<EventSummary> views = catalog.search("by-id", "a",
        EventSummary.class);
    assertEquals(1, views.size());
    assertEquals("V2", views.get(0).venueName());
  }

  @Test
  void whenReplace_shouldRunHooksOnNewItem() {
    final AtomicReference<Event> seen = new AtomicReference<>();
    final SnapshotBuildHook<Event> hook = item -> {
      seen.set(item);
      return item;
    };
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(new ArrayList<>(List.of(a)))
        .index("by-id").by(Event::id, id -> id)
        .hook(hook)
        .build();
    catalog.bootstrap();

    final Event updated = new Event("a", new Sport("F"), new Venue("V2"));
    assertTrue(catalog.replace("by-id", "a", updated));

    assertSame(updated, seen.get());
  }

  @Test
  void whenReplace_shouldShareUntouchedBucketsAcrossSnapshots() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Event b = new Event("b", new Sport("G"), new Venue("V2"));
    final Catalog<Event> catalog = buildEventCatalog(new ArrayList<>(
        List.of(a, b)));
    catalog.bootstrap();

    final Snapshot<Event> before = catalog.currentSnapshot();
    final List<Event> bucketBSportBefore = before.search("by-sport", "G");

    final Event updated = new Event("a", new Sport("F"), new Venue("V3"));
    assertTrue(catalog.replace("by-id", "a", updated));

    final Snapshot<Event> after = catalog.currentSnapshot();
    final List<Event> bucketBSportAfter = after.search("by-sport", "G");

    assertEquals(bucketBSportBefore, bucketBSportAfter);
    assertEquals(List.of(b), bucketBSportAfter);
  }

  @Test
  void whenReplaceAndRefresh_shouldSerializeAccessViaRefreshLock() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final List<Event> data = new ArrayList<>(List.of(a));
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> data)
        .index("by-id").by(Event::id, id -> id)
        .build();
    catalog.bootstrap();

    final AtomicInteger raceFailures = new AtomicInteger();
    final int iterations = 50;

    final Thread replacer = new Thread(() -> {
      for (int i = 0; i < iterations; i++) {
        try {
          catalog.replace("by-id", "a", new Event("a",
              new Sport("S" + i), new Venue("V" + i)));
        } catch (final Exception ignored) {
          raceFailures.incrementAndGet();
        }
      }
    });
    final Thread refresher = new Thread(() -> {
      for (int i = 0; i < iterations; i++) {
        try {
          catalog.refresh();
        } catch (final Exception ignored) {
          raceFailures.incrementAndGet();
        }
      }
    });

    replacer.start();
    refresher.start();
    try {
      replacer.join();
      refresher.join();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertEquals(0, raceFailures.get(),
        "refreshLock must serialize replace and refresh");
    assertNotNull(catalog.currentSnapshot());
  }
}
