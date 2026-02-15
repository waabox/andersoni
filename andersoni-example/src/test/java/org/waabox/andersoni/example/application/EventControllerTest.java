package org.waabox.andersoni.example.application;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.Catalog;
import org.waabox.andersoni.example.domain.Event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Unit tests for {@link EventController}.
 *
 * <p>Verifies the REST controller logic for searching events, refreshing
 * the catalog, and retrieving catalog information.
 *
 * <p>Since {@link Andersoni}, {@link Catalog}, and
 * {@link org.waabox.andersoni.Snapshot} are final classes that EasyMock
 * cannot mock via ByteBuddy subclassing, these tests use real Andersoni
 * instances constructed through the builder API with static data.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class EventControllerTest {

  /** Creates a pre-configured Andersoni instance with an events catalog
   * containing the provided events.
   *
   * @param events the events to load into the catalog
   * @return a started Andersoni instance with the events catalog
   */
  private static Andersoni createAndersoni(final List<Event> events) {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(events)
        .index("by-sport").by(Event::getSport, Function.identity())
        .index("by-venue").by(Event::getVenue, Function.identity())
        .index("by-status").by(Event::getStatus, Function.identity())
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("test-node")
        .build();

    andersoni.register(catalog);
    andersoni.start();

    return andersoni;
  }

  @Test
  void whenSearching_givenValidIndexAndKey_shouldReturnMatchingEvents() {
    final Event footballEvent = new Event(
        "e1", "Match 1", "FOOTBALL", "Wembley",
        "Team A", "Team B", "SCHEDULED",
        LocalDateTime.of(2026, 3, 15, 20, 0));

    final Event basketballEvent = new Event(
        "e2", "Match 2", "BASKETBALL", "MSG",
        "Team C", "Team D", "LIVE",
        LocalDateTime.of(2026, 3, 16, 18, 30));

    final Andersoni andersoni = createAndersoni(
        List.of(footballEvent, basketballEvent));

    try {
      final EventController controller = new EventController(andersoni);
      final List<?> results = controller.search("by-sport", "FOOTBALL");

      assertEquals(1, results.size());
      assertEquals("e1", ((Event) results.get(0)).getId());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenSearching_givenNoMatches_shouldReturnEmptyList() {
    final Event event = new Event(
        "e1", "Match 1", "FOOTBALL", "Wembley",
        "Team A", "Team B", "SCHEDULED",
        LocalDateTime.of(2026, 3, 15, 20, 0));

    final Andersoni andersoni = createAndersoni(List.of(event));

    try {
      final EventController controller = new EventController(andersoni);
      final List<?> results = controller.search("by-sport", "CRICKET");

      assertTrue(results.isEmpty());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenGettingInfo_givenCatalogExists_shouldReturnSnapshotMetadata() {
    final Event event1 = new Event(
        "e1", "Match 1", "FOOTBALL", "Wembley",
        "Team A", "Team B", "SCHEDULED",
        LocalDateTime.of(2026, 3, 15, 20, 0));

    final Event event2 = new Event(
        "e2", "Match 2", "BASKETBALL", "MSG",
        "Team C", "Team D", "LIVE",
        LocalDateTime.of(2026, 3, 16, 18, 30));

    final Andersoni andersoni = createAndersoni(
        List.of(event1, event2));

    try {
      final EventController controller = new EventController(andersoni);
      final Map<String, Object> info = controller.info();

      assertEquals("test-node", info.get("nodeId"));
      assertEquals(1L, info.get("version"));
      assertNotNull(info.get("hash"));
      assertNotNull(info.get("createdAt"));
      assertEquals(2, info.get("itemCount"));
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenGettingInfo_givenEmptyCatalog_shouldReturnZeroItemCount() {
    final Andersoni andersoni = createAndersoni(List.of());

    try {
      final EventController controller = new EventController(andersoni);
      final Map<String, Object> info = controller.info();

      assertEquals("test-node", info.get("nodeId"));
      assertEquals(1L, info.get("version"));
      assertEquals(0, info.get("itemCount"));
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenCreating_givenNullAndersoni_shouldThrowException() {
    assertThrows(NullPointerException.class,
        () -> new EventController(null));
  }
}
