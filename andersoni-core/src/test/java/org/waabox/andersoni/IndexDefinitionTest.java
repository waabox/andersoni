package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
