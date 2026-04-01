package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompoundQueryViewTest {

  record Sport(String name) {}
  record Venue(String name) {}
  record Event(String id, Sport sport, Venue venue) {}
  record EventSummary(String id) {}

  @Test
  void whenExecutingCompoundWithView_shouldReturnViews() {
    final Event e1 = new Event("1", new Sport("Football"), new Venue("Maracana"));
    final Event e2 = new Event("2", new Sport("Rugby"), new Venue("Wembley"));
    final Event e3 = new Event("3", new Sport("Football"), new Venue("Wembley"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1, e2, e3))
        .index("by-sport").by(Event::sport, Sport::name)
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class, e -> new EventSummary(e.id()))
        .build();
    catalog.bootstrap();

    final List<EventSummary> result = catalog.compound()
        .where("by-sport").equalTo("Football")
        .and("by-venue").equalTo("Wembley")
        .execute(EventSummary.class);

    assertEquals(1, result.size());
    assertEquals(new EventSummary("3"), result.get(0));
  }

  @Test
  void whenExecutingCompoundWithoutView_shouldReturnItemsAsUsual() {
    final Event e1 = new Event("1", new Sport("Football"), new Venue("Maracana"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-sport").by(Event::sport, Sport::name)
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class, e -> new EventSummary(e.id()))
        .build();
    catalog.bootstrap();

    final List<Event> result = catalog.compound()
        .where("by-sport").equalTo("Football")
        .execute();

    assertEquals(1, result.size());
    assertEquals(e1, result.get(0));
  }
}
