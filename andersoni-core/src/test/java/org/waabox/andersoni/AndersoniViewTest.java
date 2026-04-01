package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class AndersoniViewTest {

  record Sport(String name) {}
  record Venue(String name) {}
  record Event(String id, Sport sport, Venue venue) {}
  record EventSummary(String id, String sportName) {}

  @Test
  void whenSearchingWithView_shouldReturnViews() {
    final Event e1 = new Event("1", new Sport("Football"), new Venue("Maracana"));
    final Event e2 = new Event("2", new Sport("Rugby"), new Venue("Wembley"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1, e2))
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class, e -> new EventSummary(e.id(), e.sport().name()))
        .build();

    final Andersoni andersoni = Andersoni.builder().nodeId("node-1").build();
    andersoni.register(catalog);
    andersoni.start();

    final List<EventSummary> result = andersoni.search(
        "events", "by-venue", "Maracana", EventSummary.class);
    assertEquals(1, result.size());
    assertEquals(new EventSummary("1", "Football"), result.get(0));
  }

  @Test
  void whenQueryingWithView_shouldReturnViews() {
    final Event e1 = new Event("1", new Sport("Football"), new Venue("Maracana"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class, e -> new EventSummary(e.id(), e.sport().name()))
        .build();

    final Andersoni andersoni = Andersoni.builder().nodeId("node-1").build();
    andersoni.register(catalog);
    andersoni.start();

    final List<EventSummary> result = andersoni.query("events", "by-venue")
        .equalTo("Maracana", EventSummary.class);
    assertEquals(1, result.size());
    assertEquals(new EventSummary("1", "Football"), result.get(0));
  }

  @Test
  void whenCompoundWithView_shouldReturnViews() {
    final Event e1 = new Event("1", new Sport("Football"), new Venue("Maracana"));
    final Event e2 = new Event("2", new Sport("Rugby"), new Venue("Maracana"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1, e2))
        .index("by-sport").by(Event::sport, Sport::name)
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class, e -> new EventSummary(e.id(), e.sport().name()))
        .build();

    final Andersoni andersoni = Andersoni.builder().nodeId("node-1").build();
    andersoni.register(catalog);
    andersoni.start();

    final List<EventSummary> result = andersoni.compound("events")
        .where("by-sport").equalTo("Football")
        .and("by-venue").equalTo("Maracana")
        .execute(EventSummary.class);
    assertEquals(1, result.size());
    assertEquals(new EventSummary("1", "Football"), result.get(0));
  }

  @Test
  void whenSearchingWithItemType_shouldStillWork() {
    final Event e1 = new Event("1", new Sport("Football"), new Venue("Maracana"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    final Andersoni andersoni = Andersoni.builder().nodeId("node-1").build();
    andersoni.register(catalog);
    andersoni.start();

    final List<Event> result = andersoni.search("events", "by-venue", "Maracana", Event.class);
    assertEquals(1, result.size());
    assertEquals(e1, result.get(0));
  }
}
