package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link IndexDefinition}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class IndexDefinitionTest {

  /** A sport domain object used for testing. */
  record Sport(String name) {}

  /** A venue domain object used for testing. */
  record Venue(String name) {}

  /** An event domain object used for testing. */
  record Event(String id, Sport sport, Venue venue) {}

  @Test
  void whenBuildingIndex_givenData_shouldGroupByKey() {
    final Venue maracana = new Venue("Maracana");
    final Venue wembley = new Venue("Wembley");

    final Event e1 = new Event("1", new Sport("Football"), maracana);
    final Event e2 = new Event("2", new Sport("Rugby"), wembley);
    final Event e3 = new Event("3", new Sport("Tennis"), maracana);

    final IndexDefinition<Event> index = IndexDefinition.<Event>named("byVenue")
        .by(Event::venue, Venue::name);

    final Map<Object, List<Event>> result = index.buildIndex(
        List.of(e1, e2, e3));

    assertNotNull(result);
    assertEquals(2, result.size());

    final List<Event> maracanaEvents = result.get("Maracana");
    assertNotNull(maracanaEvents);
    assertEquals(2, maracanaEvents.size());
    assertTrue(maracanaEvents.contains(e1));
    assertTrue(maracanaEvents.contains(e3));

    final List<Event> wembleyEvents = result.get("Wembley");
    assertNotNull(wembleyEvents);
    assertEquals(1, wembleyEvents.size());
    assertTrue(wembleyEvents.contains(e2));
  }

  @Test
  void whenBuildingIndex_givenEmptyData_shouldReturnEmptyMap() {
    final IndexDefinition<Event> index = IndexDefinition.<Event>named("byVenue")
        .by(Event::venue, Venue::name);

    final Map<Object, List<Event>> result = index.buildIndex(List.of());

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenGettingName_shouldReturnConfiguredName() {
    final IndexDefinition<Event> index = IndexDefinition.<Event>named("bySport")
        .by(Event::sport, Sport::name);

    assertEquals("bySport", index.name());
  }

  @Test
  void whenCreating_givenNullName_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        IndexDefinition.<Event>named(null)
    );
  }

  @Test
  void whenCreating_givenEmptyName_shouldThrowIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () ->
        IndexDefinition.<Event>named("")
    );
  }

  @Test
  void whenCreating_givenNullFirstFunction_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        IndexDefinition.<Event>named("byVenue")
            .by(null, Venue::name)
    );
  }

  @Test
  void whenCreating_givenNullSecondFunction_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        IndexDefinition.<Event>named("byVenue")
            .by(Event::venue, null)
    );
  }

  @Test
  void whenBuildingIndex_givenNullData_shouldThrowNpe() {
    final IndexDefinition<Event> index = IndexDefinition.<Event>named("byVenue")
        .by(Event::venue, Venue::name);

    assertThrows(NullPointerException.class, () ->
        index.buildIndex(null)
    );
  }

  @Test
  void whenBuildingIndex_givenNullKeyFromExtractor_shouldIndexUnderNullKey() {
    final Event e1 = new Event("1", new Sport("Football"), new Venue("Maracana"));
    final Event e2 = new Event("2", new Sport("Rugby"), null);

    final IndexDefinition<Event> index = IndexDefinition.<Event>named("byVenue")
        .by(Event::venue, Venue::name);

    final Map<Object, List<Event>> result = index.buildIndex(List.of(e1, e2));

    assertEquals(2, result.size());
    assertNotNull(result.get("Maracana"));
    assertEquals(1, result.get("Maracana").size());
    assertNotNull(result.get(null));
    assertEquals(1, result.get(null).size());
    assertTrue(result.get(null).contains(e2));
  }

  @Test
  void whenBuildingIndex_givenAllUniqueKeys_shouldHaveOneItemPerKey() {
    final Venue maracana = new Venue("Maracana");
    final Venue wembley = new Venue("Wembley");
    final Venue bernabeu = new Venue("Bernabeu");

    final Event e1 = new Event("1", new Sport("Football"), maracana);
    final Event e2 = new Event("2", new Sport("Rugby"), wembley);
    final Event e3 = new Event("3", new Sport("Tennis"), bernabeu);

    final IndexDefinition<Event> index = IndexDefinition.<Event>named("byVenue")
        .by(Event::venue, Venue::name);

    final Map<Object, List<Event>> result = index.buildIndex(
        List.of(e1, e2, e3));

    assertEquals(3, result.size());

    for (final List<Event> events : result.values()) {
      assertEquals(1, events.size());
    }
  }

  @Test
  void whenBuildingIndex_givenResultMap_shouldBeUnmodifiable() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));

    final IndexDefinition<Event> index = IndexDefinition.<Event>named("byVenue")
        .by(Event::venue, Venue::name);

    final Map<Object, List<Event>> result = index.buildIndex(List.of(e1));

    assertThrows(UnsupportedOperationException.class, () ->
        result.put("injected", List.of())
    );

    final List<Event> events = result.get("Maracana");
    assertThrows(UnsupportedOperationException.class, () ->
        events.add(e1)
    );
  }
}
