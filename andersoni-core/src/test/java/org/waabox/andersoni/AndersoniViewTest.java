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

  @Test
  void whenUsingViewsWithSortedIndex_shouldSupportAllRangeQueries() {
    final Event e1 = new Event("1", new Sport("A"), new Venue("V1"));
    final Event e2 = new Event("2", new Sport("B"), new Venue("V2"));
    final Event e3 = new Event("3", new Sport("C"), new Venue("V3"));
    final Event e4 = new Event("4", new Sport("D"), new Venue("V4"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1, e2, e3, e4))
        .indexSorted("by-sport").by(Event::sport, Sport::name)
        .view(EventSummary.class, e -> new EventSummary(e.id(), e.sport().name()))
        .build();

    final Andersoni andersoni = Andersoni.builder().nodeId("node-1").build();
    andersoni.register(catalog);
    andersoni.start();

    // between
    List<EventSummary> result = andersoni.query("events", "by-sport")
        .between("A", "C", EventSummary.class);
    assertEquals(3, result.size());

    // greaterThan
    result = andersoni.query("events", "by-sport")
        .greaterThan("B", EventSummary.class);
    assertEquals(2, result.size());

    // lessThan
    result = andersoni.query("events", "by-sport")
        .lessThan("C", EventSummary.class);
    assertEquals(2, result.size());

    // startsWith
    result = andersoni.query("events", "by-sport")
        .startsWith("A", EventSummary.class);
    assertEquals(1, result.size());
    assertEquals(new EventSummary("1", "A"), result.get(0));
  }

  @Test
  void whenUsingViewsWithNoViewsDefined_shouldWorkNormally() {
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
