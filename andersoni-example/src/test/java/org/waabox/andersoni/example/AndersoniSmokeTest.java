package org.waabox.andersoni.example;

import org.junit.jupiter.api.Test;

import org.waabox.andersoni.Andersoni;
import org.waabox.andersoni.Catalog;
import org.waabox.andersoni.example.domain.Event;
import org.waabox.andersoni.example.domain.EventSnapshotSerializer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Smoke test verifying Andersoni works with the Event domain model.
 *
 * <p>This test exercises the core Andersoni functionality (catalog creation,
 * index building, search, and Query DSL) using the example module's Event
 * entity and EventSnapshotSerializer without requiring any infrastructure
 * (Kafka, K8s, S3, or a database).
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class AndersoniSmokeTest {

  private static final LocalDateTime MAR_14 =
      LocalDateTime.of(2026, 3, 14, 21, 0);

  private static final LocalDateTime MAR_15 =
      LocalDateTime.of(2026, 3, 15, 20, 0);

  private static final LocalDateTime MAR_16 =
      LocalDateTime.of(2026, 3, 16, 18, 30);

  private static final LocalDateTime MAR_17 =
      LocalDateTime.of(2026, 3, 17, 15, 0);

  private static List<Event> sampleEvents() {
    return List.of(
        new Event("e1", "Champions League Final", "FOOTBALL", "Wembley",
            "Arsenal", "Chelsea", "SCHEDULED", MAR_15),
        new Event("e2", "NBA Playoffs Game 7", "BASKETBALL", "MSG",
            "Lakers", "Celtics", "LIVE", MAR_16),
        new Event("e3", "El Clasico", "FOOTBALL", "Camp Nou",
            "Barcelona", "Madrid", "FINISHED", MAR_14),
        new Event("e4", "Champions League Semifinal", "FOOTBALL",
            "Anfield", "Liverpool", "Bayern", "SCHEDULED", MAR_17)
    );
  }

  private static Catalog<Event> buildCatalog(final List<Event> events) {
    return Catalog.of(Event.class)
        .named("events")
        .data(events)
        .index("by-sport").by(Event::getSport, Function.identity())
        .index("by-venue").by(Event::getVenue, Function.identity())
        .index("by-status").by(Event::getStatus, Function.identity())
        .indexSorted("by-start-time")
            .by(Event::getStartTime, Function.identity())
        .indexSorted("by-name")
            .by(Event::getName, Function.identity())
        .serializer(new EventSnapshotSerializer())
        .build();
  }

  @Test
  void whenSearchingEvents_givenLoadedCatalog_shouldReturnMatchingEvents() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(buildCatalog(sampleEvents()));
    andersoni.start();

    try {
      final List<?> football =
          andersoni.search("events", "by-sport", "FOOTBALL");
      assertEquals(3, football.size());

      final List<?> wembley =
          andersoni.search("events", "by-venue", "Wembley");
      assertEquals(1, wembley.size());

      final List<?> live =
          andersoni.search("events", "by-status", "LIVE");
      assertEquals(1, live.size());
      assertEquals("e2", ((Event) live.get(0)).getId());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingBetween_givenDateRange_shouldReturnEventsInRange() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(buildCatalog(sampleEvents()));
    andersoni.start();

    try {
      final List<?> results = andersoni
          .query("events", "by-start-time")
          .between(MAR_15, MAR_16);

      assertEquals(2, results.size());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingGreaterThan_givenDate_shouldReturnEventsAfter() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(buildCatalog(sampleEvents()));
    andersoni.start();

    try {
      final List<?> results = andersoni
          .query("events", "by-start-time")
          .greaterThan(MAR_15);

      assertEquals(2, results.size());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingGreaterOrEqual_givenDate_shouldReturnEventsFromDate() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(buildCatalog(sampleEvents()));
    andersoni.start();

    try {
      final List<?> results = andersoni
          .query("events", "by-start-time")
          .greaterOrEqual(MAR_15);

      assertEquals(3, results.size());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingLessThan_givenDate_shouldReturnEventsBefore() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(buildCatalog(sampleEvents()));
    andersoni.start();

    try {
      final List<?> results = andersoni
          .query("events", "by-start-time")
          .lessThan(MAR_16);

      assertEquals(2, results.size());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingLessOrEqual_givenDate_shouldReturnEventsUntilDate() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(buildCatalog(sampleEvents()));
    andersoni.start();

    try {
      final List<?> results = andersoni
          .query("events", "by-start-time")
          .lessOrEqual(MAR_16);

      assertEquals(3, results.size());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingStartsWith_givenPrefix_shouldReturnMatchingEvents() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(buildCatalog(sampleEvents()));
    andersoni.start();

    try {
      final List<?> results = andersoni
          .query("events", "by-name")
          .startsWith("Champions");

      assertEquals(2, results.size());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingEndsWith_givenSuffix_shouldReturnMatchingEvents() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(buildCatalog(sampleEvents()));
    andersoni.start();

    try {
      final List<?> results = andersoni
          .query("events", "by-name")
          .endsWith("Final");

      assertEquals(1, results.size());
      assertEquals("e1", ((Event) results.get(0)).getId());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingContains_givenSubstring_shouldReturnMatchingEvents() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(buildCatalog(sampleEvents()));
    andersoni.start();

    try {
      final List<?> results = andersoni
          .query("events", "by-name")
          .contains("League");

      assertEquals(2, results.size());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenQueryingEqualTo_givenExactName_shouldReturnSingleEvent() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(buildCatalog(sampleEvents()));
    andersoni.start();

    try {
      final List<?> results = andersoni
          .query("events", "by-name")
          .equalTo("El Clasico");

      assertEquals(1, results.size());
      assertEquals("e3", ((Event) results.get(0)).getId());
    } finally {
      andersoni.stop();
    }
  }
}
