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
 * <p>Verifies the REST controller logic for searching events, querying with
 * the Query DSL (date ranges and text search), refreshing the catalog, and
 * retrieving catalog information.
 *
 * <p>Since {@link Andersoni}, {@link Catalog}, and
 * {@link org.waabox.andersoni.Snapshot} are final classes that EasyMock
 * cannot mock via ByteBuddy subclassing, these tests use real Andersoni
 * instances constructed through the builder API with static data.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class EventControllerTest {

  private static final LocalDateTime MAR_14 =
      LocalDateTime.of(2026, 3, 14, 21, 0);

  private static final LocalDateTime MAR_15 =
      LocalDateTime.of(2026, 3, 15, 20, 0);

  private static final LocalDateTime MAR_16 =
      LocalDateTime.of(2026, 3, 16, 18, 30);

  private static final LocalDateTime MAR_17 =
      LocalDateTime.of(2026, 3, 17, 15, 0);

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
        .indexSorted("by-start-time")
            .by(Event::getStartTime, Function.identity())
        .indexSorted("by-name")
            .by(Event::getName, Function.identity())
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("test-node")
        .build();

    andersoni.register(catalog);
    andersoni.start();

    return andersoni;
  }

  private static List<Event> sampleEvents() {
    return List.of(
        new Event("e1", "Champions League Final", "FOOTBALL", "Wembley",
            "Team A", "Team B", "SCHEDULED", MAR_15),
        new Event("e2", "NBA Playoffs Game 7", "BASKETBALL", "MSG",
            "Team C", "Team D", "LIVE", MAR_16),
        new Event("e3", "El Clasico", "FOOTBALL", "Camp Nou",
            "Team E", "Team F", "FINISHED", MAR_14),
        new Event("e4", "Champions League Semifinal", "FOOTBALL",
            "Anfield", "Team G", "Team H", "SCHEDULED", MAR_17)
    );
  }

  // -- Equality search tests ------------------------------------------

  @Test
  void whenSearching_givenValidIndexAndKey_shouldReturnMatchingEvents() {
    final Andersoni andersoni = createAndersoni(sampleEvents());

    try {
      final EventController controller = new EventController(andersoni);
      final List<?> results = controller.search("by-sport", "FOOTBALL");

      assertEquals(3, results.size());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenSearching_givenNoMatches_shouldReturnEmptyList() {
    final Andersoni andersoni = createAndersoni(sampleEvents());

    try {
      final EventController controller = new EventController(andersoni);
      final List<?> results = controller.search("by-sport", "CRICKET");

      assertTrue(results.isEmpty());
    } finally {
      andersoni.stop();
    }
  }

  // -- Date range query tests -----------------------------------------

  @Test
  void whenQueryingBetween_givenDateRange_shouldReturnEventsInRange() {
    final Andersoni andersoni = createAndersoni(sampleEvents());

    try {
      final EventController controller = new EventController(andersoni);
      final List<?> results = controller.between(MAR_15, MAR_16);

      assertEquals(2, results.size());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingAfter_givenDate_shouldReturnEventsAfterDate() {
    final Andersoni andersoni = createAndersoni(sampleEvents());

    try {
      final EventController controller = new EventController(andersoni);
      final List<?> results = controller.after(MAR_15);

      assertEquals(2, results.size());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingFrom_givenDate_shouldReturnEventsFromDateInclusive() {
    final Andersoni andersoni = createAndersoni(sampleEvents());

    try {
      final EventController controller = new EventController(andersoni);
      final List<?> results = controller.from(MAR_15);

      assertEquals(3, results.size());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingBefore_givenDate_shouldReturnEventsBeforeDate() {
    final Andersoni andersoni = createAndersoni(sampleEvents());

    try {
      final EventController controller = new EventController(andersoni);
      final List<?> results = controller.before(MAR_16);

      assertEquals(2, results.size());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingUntil_givenDate_shouldReturnEventsUntilDateInclusive() {
    final Andersoni andersoni = createAndersoni(sampleEvents());

    try {
      final EventController controller = new EventController(andersoni);
      final List<?> results = controller.until(MAR_16);

      assertEquals(3, results.size());
    } finally {
      andersoni.stop();
    }
  }

  // -- Text search query tests ----------------------------------------

  @Test
  void whenQueryingStartsWith_givenPrefix_shouldReturnMatchingEvents() {
    final Andersoni andersoni = createAndersoni(sampleEvents());

    try {
      final EventController controller = new EventController(andersoni);
      final List<?> results = controller.startsWith("Champions");

      assertEquals(2, results.size());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingEndsWith_givenSuffix_shouldReturnMatchingEvents() {
    final Andersoni andersoni = createAndersoni(sampleEvents());

    try {
      final EventController controller = new EventController(andersoni);
      final List<?> results = controller.endsWith("Final");

      assertEquals(1, results.size());
      assertEquals("e1", ((Event) results.get(0)).getId());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingContains_givenSubstring_shouldReturnMatchingEvents() {
    final Andersoni andersoni = createAndersoni(sampleEvents());

    try {
      final EventController controller = new EventController(andersoni);
      final List<?> results = controller.contains("League");

      assertEquals(2, results.size());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingContains_givenNoMatches_shouldReturnEmptyList() {
    final Andersoni andersoni = createAndersoni(sampleEvents());

    try {
      final EventController controller = new EventController(andersoni);
      final List<?> results = controller.contains("ZZZZZ");

      assertTrue(results.isEmpty());
    } finally {
      andersoni.stop();
    }
  }

  // -- Info and lifecycle tests ---------------------------------------

  @Test
  void whenGettingInfo_givenCatalogExists_shouldReturnSnapshotMetadata() {
    final Andersoni andersoni = createAndersoni(sampleEvents());

    try {
      final EventController controller = new EventController(andersoni);
      final Map<String, Object> info = controller.info();

      assertEquals("test-node", info.get("nodeId"));
      assertEquals(1L, info.get("version"));
      assertNotNull(info.get("hash"));
      assertNotNull(info.get("createdAt"));
      assertEquals(4, info.get("itemCount"));

      assertNotNull(info.get("totalEstimatedSizeMB"));
      assertTrue((double) info.get("totalEstimatedSizeMB") > 0);

      @SuppressWarnings("unchecked")
      final List<Map<String, Object>> indices =
          (List<Map<String, Object>>) info.get("indices");
      assertEquals(5, indices.size());

      final Map<String, Object> firstIndex = indices.get(0);
      assertNotNull(firstIndex.get("name"));
      assertNotNull(firstIndex.get("uniqueKeys"));
      assertNotNull(firstIndex.get("totalEntries"));
      assertNotNull(firstIndex.get("estimatedSizeMB"));
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
