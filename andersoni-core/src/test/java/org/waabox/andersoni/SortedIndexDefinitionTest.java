package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import org.junit.jupiter.api.Test;

import org.waabox.andersoni.SortedIndexDefinition.SortedIndexResult;

/**
 * Tests for {@link SortedIndexDefinition}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SortedIndexDefinitionTest {

  /** An event date value object used for testing. */
  record EventDate(LocalDate value) {}

  /** A venue domain object used for testing. */
  record Venue(String name) {}

  /** An event domain object used for testing. */
  record Event(String id, EventDate eventDate, Venue venue) {}

  @Test
  void whenBuildingIndex_givenData_shouldBuildHashAndTreeMaps() {
    final LocalDate jan1 = LocalDate.of(2025, 1, 1);
    final LocalDate feb1 = LocalDate.of(2025, 2, 1);

    final Event e1 = new Event("1", new EventDate(jan1), new Venue("A"));
    final Event e2 = new Event("2", new EventDate(feb1), new Venue("B"));
    final Event e3 = new Event("3", new EventDate(jan1), new Venue("C"));

    final SortedIndexDefinition<Event> index =
        SortedIndexDefinition.<Event>named("by-date")
            .by(Event::eventDate, EventDate::value);

    final SortedIndexResult<Event> result = index.buildIndex(
        List.of(e1, e2, e3));

    assertNotNull(result.hashIndex());
    assertNotNull(result.sortedIndex());

    assertEquals(2, result.hashIndex().size());
    assertEquals(2, result.sortedIndex().size());

    final List<Event> jan1Hash = result.hashIndex().get(jan1);
    final List<Event> jan1Sorted = result.sortedIndex().get(jan1);

    assertNotNull(jan1Hash);
    assertNotNull(jan1Sorted);
    assertEquals(2, jan1Hash.size());
    assertTrue(jan1Hash.contains(e1));
    assertTrue(jan1Hash.contains(e3));

    assertSame(jan1Hash, jan1Sorted,
        "HashMap and TreeMap must share the same List instance");

    final List<Event> feb1Hash = result.hashIndex().get(feb1);
    final List<Event> feb1Sorted = result.sortedIndex().get(feb1);

    assertSame(feb1Hash, feb1Sorted,
        "HashMap and TreeMap must share the same List instance");
  }

  @Test
  void whenBuildingIndex_givenStringKeys_shouldBuildReversedTreeMap() {
    final Event e1 = new Event("1", new EventDate(null),
        new Venue("Madison Square"));
    final Event e2 = new Event("2", new EventDate(null),
        new Venue("Wembley"));

    final SortedIndexDefinition<Event> index =
        SortedIndexDefinition.<Event>named("by-venue")
            .by(Event::venue, Venue::name);

    final SortedIndexResult<Event> result = index.buildIndex(
        List.of(e1, e2));

    assertTrue(result.hasStringKeys());
    assertNotNull(result.reversedKeyIndex());

    final NavigableMap<String, List<Event>> reversed =
        result.reversedKeyIndex();
    assertEquals(2, reversed.size());

    final String reversedMadison = new StringBuilder("Madison Square")
        .reverse().toString();
    final String reversedWembley = new StringBuilder("Wembley")
        .reverse().toString();

    assertTrue(reversed.containsKey(reversedMadison));
    assertTrue(reversed.containsKey(reversedWembley));

    final List<Event> madisonHash = result.hashIndex().get("Madison Square");
    final List<Event> madisonReversed = reversed.get(reversedMadison);

    assertSame(madisonHash, madisonReversed,
        "HashMap and reversed TreeMap must share the same List instance");
  }

  @Test
  void whenBuildingIndex_givenNonStringKeys_shouldNotBuildReversedTreeMap() {
    final Event e1 = new Event("1",
        new EventDate(LocalDate.of(2025, 3, 15)), new Venue("A"));

    final SortedIndexDefinition<Event> index =
        SortedIndexDefinition.<Event>named("by-date")
            .by(Event::eventDate, EventDate::value);

    final SortedIndexResult<Event> result = index.buildIndex(List.of(e1));

    assertFalse(result.hasStringKeys());
    assertNull(result.reversedKeyIndex());
  }

  @Test
  void whenBuildingIndex_givenEmptyData_shouldReturnEmptyMaps() {
    final SortedIndexDefinition<Event> index =
        SortedIndexDefinition.<Event>named("by-date")
            .by(Event::eventDate, EventDate::value);

    final SortedIndexResult<Event> result = index.buildIndex(List.of());

    assertNotNull(result.hashIndex());
    assertNotNull(result.sortedIndex());
    assertTrue(result.hashIndex().isEmpty());
    assertTrue(result.sortedIndex().isEmpty());
    assertFalse(result.hasStringKeys());
    assertNull(result.reversedKeyIndex());
  }

  @Test
  void whenBuildingIndex_givenNullIntermediateValue_shouldIndexUnderNull() {
    final Event e1 = new Event("1",
        new EventDate(LocalDate.of(2025, 5, 10)), new Venue("A"));
    final Event e2 = new Event("2", null, new Venue("B"));

    final SortedIndexDefinition<Event> index =
        SortedIndexDefinition.<Event>named("by-date")
            .by(Event::eventDate, EventDate::value);

    final SortedIndexResult<Event> result = index.buildIndex(
        List.of(e1, e2));

    assertEquals(2, result.hashIndex().size());
    assertNotNull(result.hashIndex().get(null));
    assertEquals(1, result.hashIndex().get(null).size());
    assertTrue(result.hashIndex().get(null).contains(e2));

    assertEquals(1, result.sortedIndex().size(),
        "Sorted index must not contain null keys");
    assertTrue(result.sortedIndex()
        .containsKey(LocalDate.of(2025, 5, 10)));
  }

  @Test
  void whenBuildingIndex_givenResults_shouldBeUnmodifiable() {
    final Event e1 = new Event("1",
        new EventDate(LocalDate.of(2025, 1, 1)), new Venue("Wembley"));

    final SortedIndexDefinition<Event> indexByDate =
        SortedIndexDefinition.<Event>named("by-date")
            .by(Event::eventDate, EventDate::value);

    final SortedIndexResult<Event> dateResult = indexByDate.buildIndex(
        List.of(e1));

    assertThrows(UnsupportedOperationException.class, () ->
        dateResult.hashIndex().put(LocalDate.now(), List.of()));

    assertThrows(UnsupportedOperationException.class, () ->
        dateResult.sortedIndex().put(LocalDate.now(), List.of()));

    final List<Event> hashList = dateResult.hashIndex()
        .get(LocalDate.of(2025, 1, 1));
    assertThrows(UnsupportedOperationException.class, () ->
        hashList.add(e1));

    final SortedIndexDefinition<Event> indexByVenue =
        SortedIndexDefinition.<Event>named("by-venue")
            .by(Event::venue, Venue::name);

    final SortedIndexResult<Event> venueResult = indexByVenue.buildIndex(
        List.of(e1));

    assertNotNull(venueResult.reversedKeyIndex());
    assertThrows(UnsupportedOperationException.class, () ->
        venueResult.reversedKeyIndex().put("injected", List.of()));
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
        SortedIndexDefinition.<Event>named("by-date")
            .by(null, EventDate::value));
  }

  @Test
  void whenCreating_givenNullSecondFunction_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        SortedIndexDefinition.<Event>named("by-date")
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
