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
 * index building, and search) using the example module's Event entity and
 * EventSnapshotSerializer without requiring any infrastructure (Kafka, K8s,
 * S3, or a database).
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class AndersoniSmokeTest {

  @Test
  void whenSearchingEvents_givenLoadedCatalog_shouldReturnMatchingEvents() {
    List<Event> events = List.of(
        new Event("e1", "Match 1", "FOOTBALL", "Wembley",
            "Arsenal", "Chelsea", "SCHEDULED",
            LocalDateTime.of(2026, 3, 15, 20, 0)),
        new Event("e2", "Match 2", "BASKETBALL", "MSG",
            "Lakers", "Celtics", "LIVE",
            LocalDateTime.of(2026, 3, 16, 18, 30)),
        new Event("e3", "Match 3", "FOOTBALL", "Camp Nou",
            "Barcelona", "Madrid", "FINISHED",
            LocalDateTime.of(2026, 3, 14, 21, 0))
    );

    Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(events)
        .index("by-sport").by(Event::getSport, Function.identity())
        .index("by-venue").by(Event::getVenue, Function.identity())
        .index("by-status").by(Event::getStatus, Function.identity())
        .serializer(new EventSnapshotSerializer())
        .build();

    Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(catalog);
    andersoni.start();

    try {
      List<?> football = andersoni.search("events", "by-sport", "FOOTBALL");
      assertEquals(2, football.size());

      List<?> wembley = andersoni.search("events", "by-venue", "Wembley");
      assertEquals(1, wembley.size());

      List<?> live = andersoni.search("events", "by-status", "LIVE");
      assertEquals(1, live.size());
      assertEquals("e2", ((Event) live.get(0)).getId());
    } finally {
      andersoni.stop();
    }
  }
}
