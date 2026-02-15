package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * End-to-end integration tests for the Andersoni cache library.
 *
 * <p>These tests exercise the full lifecycle of Andersoni with no mocks,
 * using real domain objects, real catalogs, and real indices. They verify
 * that the library works correctly in a single-node deployment with
 * default configuration.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class AndersoniIntegrationTest {

  /** A sport domain object used for testing. */
  record Sport(String name) {}

  /** A venue domain object used for testing. */
  record Venue(String name) {}

  /** An event domain object used for testing. */
  record Event(String id, Sport sport, Venue venue) {}

  @Test
  void whenRunningFullLifecycle_givenCoreOnly_shouldWorkEndToEnd() {

    // Domain data for the initial load.
    final Sport football = new Sport("Football");
    final Sport rugby = new Sport("Rugby");
    final Venue maracana = new Venue("Maracana");
    final Venue wembley = new Venue("Wembley");

    final Event e1 = new Event("ev-1", football, maracana);
    final Event e2 = new Event("ev-2", football, wembley);
    final Event e3 = new Event("ev-3", rugby, maracana);

    // Mutable data source to simulate refreshed data.
    final List<Event> dataSource = new ArrayList<>(List.of(e1, e2, e3));

    // Build catalog with a DataLoader so we can refresh.
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.copyOf(dataSource))
        .index("by-sport").by(Event::sport, Sport::name)
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    // Create Andersoni with defaults (no sync, no snapshot store,
    // SingleNodeLeaderElection).
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(catalog);

    // Start the lifecycle: bootstraps the catalog.
    andersoni.start();

    // Search by sport.
    final List<?> footballEvents =
        andersoni.search("events", "by-sport", "Football");
    assertEquals(2, footballEvents.size());
    assertTrue(footballEvents.contains(e1));
    assertTrue(footballEvents.contains(e2));

    // Search by venue.
    final List<?> maracanaEvents =
        andersoni.search("events", "by-venue", "Maracana");
    assertEquals(2, maracanaEvents.size());
    assertTrue(maracanaEvents.contains(e1));
    assertTrue(maracanaEvents.contains(e3));

    // Add new data and refresh (without sync strategy, should just
    // refresh locally).
    final Event e4 = new Event("ev-4", rugby, wembley);
    dataSource.add(e4);

    andersoni.refreshAndSync("events");

    // Verify new results after refresh.
    final List<?> rugbyEventsAfterRefresh =
        andersoni.search("events", "by-sport", "Rugby");
    assertEquals(2, rugbyEventsAfterRefresh.size());
    assertTrue(rugbyEventsAfterRefresh.contains(e3));
    assertTrue(rugbyEventsAfterRefresh.contains(e4));

    final List<?> wembleyEventsAfterRefresh =
        andersoni.search("events", "by-venue", "Wembley");
    assertEquals(2, wembleyEventsAfterRefresh.size());
    assertTrue(wembleyEventsAfterRefresh.contains(e2));
    assertTrue(wembleyEventsAfterRefresh.contains(e4));

    // Stop the lifecycle.
    andersoni.stop();
  }

  @Test
  void whenRunningFullLifecycle_givenMultipleIndices_shouldAllBeSearchable() {

    final Sport football = new Sport("Football");
    final Sport rugby = new Sport("Rugby");
    final Sport tennis = new Sport("Tennis");
    final Venue maracana = new Venue("Maracana");
    final Venue wembley = new Venue("Wembley");
    final Venue rolandGarros = new Venue("Roland Garros");

    final Event e1 = new Event("ev-1", football, maracana);
    final Event e2 = new Event("ev-2", rugby, wembley);
    final Event e3 = new Event("ev-3", tennis, rolandGarros);
    final Event e4 = new Event("ev-4", football, wembley);

    // Build catalog with 3 indices: by-venue, by-sport, by-id.
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1, e2, e3, e4))
        .index("by-venue").by(Event::venue, Venue::name)
        .index("by-sport").by(Event::sport, Sport::name)
        .index("by-id").by(Event::id, Function.identity())
        .build();

    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(catalog);
    andersoni.start();

    // Search by venue.
    final List<?> wembleyEvents =
        andersoni.search("events", "by-venue", "Wembley");
    assertEquals(2, wembleyEvents.size());
    assertTrue(wembleyEvents.contains(e2));
    assertTrue(wembleyEvents.contains(e4));

    final List<?> rolandGarrosEvents =
        andersoni.search("events", "by-venue", "Roland Garros");
    assertEquals(1, rolandGarrosEvents.size());
    assertEquals(e3, rolandGarrosEvents.get(0));

    // Search by sport.
    final List<?> footballEvents =
        andersoni.search("events", "by-sport", "Football");
    assertEquals(2, footballEvents.size());
    assertTrue(footballEvents.contains(e1));
    assertTrue(footballEvents.contains(e4));

    final List<?> tennisEvents =
        andersoni.search("events", "by-sport", "Tennis");
    assertEquals(1, tennisEvents.size());
    assertEquals(e3, tennisEvents.get(0));

    // Search by id (unique key).
    final List<?> ev1Results =
        andersoni.search("events", "by-id", "ev-1");
    assertEquals(1, ev1Results.size());
    assertEquals(e1, ev1Results.get(0));

    final List<?> ev3Results =
        andersoni.search("events", "by-id", "ev-3");
    assertEquals(1, ev3Results.size());
    assertEquals(e3, ev3Results.get(0));

    // Search for a non-existent key.
    final List<?> noResults =
        andersoni.search("events", "by-id", "non-existent");
    assertNotNull(noResults);
    assertTrue(noResults.isEmpty());

    andersoni.stop();
  }
}
