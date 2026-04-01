package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CatalogViewTest {

  record Sport(String name) {}
  record Venue(String name) {}
  record Event(String id, Sport sport, Venue venue) {}
  record EventSummary(String id, String sportName) {}
  record EventCard(String id, String venueName) {}

  @Test
  void whenSearchingWithView_givenRegisteredView_shouldReturnViews() {
    final Venue maracana = new Venue("Maracana");
    final Venue wembley = new Venue("Wembley");
    final Sport football = new Sport("Football");
    final Sport rugby = new Sport("Rugby");
    final Event e1 = new Event("1", football, maracana);
    final Event e2 = new Event("2", rugby, wembley);
    final Event e3 = new Event("3", football, maracana);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1, e2, e3))
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class,
            e -> new EventSummary(e.id(), e.sport().name()))
        .build();
    catalog.bootstrap();

    final List<EventSummary> result = catalog.search(
        "by-venue", "Maracana", EventSummary.class);
    assertEquals(2, result.size());
    assertEquals(new EventSummary("1", "Football"), result.get(0));
    assertEquals(new EventSummary("3", "Football"), result.get(1));
  }

  @Test
  void whenSearchingWithoutView_shouldReturnItemsAsUsual() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class,
            e -> new EventSummary(e.id(), e.sport().name()))
        .build();
    catalog.bootstrap();

    final List<Event> result = catalog.search("by-venue", "Maracana");
    assertEquals(1, result.size());
    assertEquals(e1, result.get(0));
  }

  @Test
  void whenSearchingWithUnregisteredView_shouldThrowIllegalArg() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .build();
    catalog.bootstrap();

    assertThrows(IllegalArgumentException.class, () ->
        catalog.search("by-venue", "Maracana", EventSummary.class));
  }

  @Test
  void whenDefiningMultipleViews_shouldSupportAll() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class,
            e -> new EventSummary(e.id(), e.sport().name()))
        .view(EventCard.class,
            e -> new EventCard(e.id(), e.venue().name()))
        .build();
    catalog.bootstrap();

    final List<EventSummary> summaries = catalog.search(
        "by-venue", "Maracana", EventSummary.class);
    final List<EventCard> cards = catalog.search(
        "by-venue", "Maracana", EventCard.class);
    assertEquals(1, summaries.size());
    assertEquals(new EventSummary("1", "Football"), summaries.get(0));
    assertEquals(1, cards.size());
    assertEquals(new EventCard("1", "Maracana"), cards.get(0));
  }

  @Test
  void whenDefiningDuplicateViewType_shouldThrowIllegalArg() {
    assertThrows(IllegalArgumentException.class, () ->
        Catalog.of(Event.class)
            .named("events")
            .data(List.of())
            .index("by-venue").by(Event::venue, Venue::name)
            .view(EventSummary.class,
                e -> new EventSummary(e.id(), e.sport().name()))
            .view(EventSummary.class,
                e -> new EventSummary(e.id(), "other"))
            .build());
  }

  @Test
  void whenGettingInfo_givenViewsDefined_shouldIncludeViewMetadata() {
    final Event e1 = new Event("1", new Sport("Football"), new Venue("Maracana"));
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class, e -> new EventSummary(e.id(), e.sport().name()))
        .view(EventCard.class, e -> new EventCard(e.id(), e.venue().name()))
        .build();
    catalog.bootstrap();

    final CatalogInfo info = catalog.info();
    assertEquals(2, info.viewCount());
    assertTrue(info.viewTypeNames().contains("EventSummary"));
    assertTrue(info.viewTypeNames().contains("EventCard"));
  }

  @Test
  void whenRefreshing_givenViewsDefined_shouldRecomputeViews() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));
    final Event e2 = new Event("2", new Sport("Rugby"),
        new Venue("Wembley"));
    final List<List<Event>> dataSets =
        List.of(List.of(e1), List.of(e1, e2));
    final int[] callCount = {0};

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> dataSets.get(callCount[0]++))
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class,
            e -> new EventSummary(e.id(), e.sport().name()))
        .build();

    catalog.bootstrap();
    assertEquals(1, catalog.search(
        "by-venue", "Maracana", EventSummary.class).size());

    catalog.refresh();
    assertEquals(1, catalog.search(
        "by-venue", "Wembley", EventSummary.class).size());
  }
}
