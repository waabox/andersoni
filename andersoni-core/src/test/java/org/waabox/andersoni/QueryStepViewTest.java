package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryStepViewTest {

  record Event(String id, String name, int rank) {}
  record EventSummary(String id) {}

  @Test
  void whenQueryingEqualToWithView_shouldReturnViews() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(new Event("1", "Alpha", 10), new Event("2", "Beta", 20)))
        .index("by-name").by(Event::name, n -> n)
        .view(EventSummary.class, e -> new EventSummary(e.id()))
        .build();
    catalog.bootstrap();

    final List<EventSummary> result = catalog.query("by-name")
        .equalTo("Alpha", EventSummary.class);
    assertEquals(1, result.size());
    assertEquals(new EventSummary("1"), result.get(0));
  }

  @Test
  void whenQueryingBetweenWithView_shouldReturnViews() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(new Event("1", "A", 10), new Event("2", "B", 20), new Event("3", "C", 30)))
        .indexSorted("by-rank").by(Event::rank, r -> r)
        .view(EventSummary.class, e -> new EventSummary(e.id()))
        .build();
    catalog.bootstrap();

    final List<EventSummary> result = catalog.query("by-rank")
        .between(10, 25, EventSummary.class);
    assertEquals(2, result.size());
  }

  @Test
  void whenQueryingStartsWithWithView_shouldReturnViews() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(new Event("1", "Alpha", 10), new Event("2", "Alpine", 20), new Event("3", "Beta", 30)))
        .indexSorted("by-name").by(Event::name, n -> n)
        .view(EventSummary.class, e -> new EventSummary(e.id()))
        .build();
    catalog.bootstrap();

    final List<EventSummary> result = catalog.query("by-name")
        .startsWith("Alp", EventSummary.class);
    assertEquals(2, result.size());
  }
}
