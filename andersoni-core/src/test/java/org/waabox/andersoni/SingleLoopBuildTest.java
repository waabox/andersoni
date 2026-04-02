package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SingleLoopBuildTest {

  record Sport(String name) {}
  record Venue(String name) {}
  record Event(String id, Sport sport, Venue venue) {}
  record EventSummary(String id) {}

  @Test
  void whenBuilding_givenHooksRegistered_shouldExecuteInPriorityOrder() {
    final List<String> executionOrder = new ArrayList<>();

    final SnapshotBuildHook<Event> hookA = item -> {
      executionOrder.add("A");
      return item;
    };
    final SnapshotBuildHook<Event> hookB = item -> {
      executionOrder.add("B");
      return item;
    };

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(new Event("1", new Sport("Football"), new Venue("Maracana"))))
        .index("by-venue").by(Event::venue, Venue::name)
        .hook(hookB, 20)
        .hook(hookA, 10)
        .build();

    catalog.bootstrap();

    assertEquals(List.of("A", "B"), executionOrder);
  }

  @Test
  void whenBuilding_givenHooksAndViews_shouldIndexAndComputeViews() {
    final AtomicInteger hookCallCount = new AtomicInteger(0);

    final SnapshotBuildHook<Event> countingHook = item -> {
      hookCallCount.incrementAndGet();
      return item;
    };

    final Event e1 = new Event("1", new Sport("Football"), new Venue("Maracana"));
    final Event e2 = new Event("2", new Sport("Rugby"), new Venue("Wembley"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1, e2))
        .index("by-venue").by(Event::venue, Venue::name)
        .view(EventSummary.class, e -> new EventSummary(e.id()))
        .hook(countingHook, 10)
        .build();

    catalog.bootstrap();

    assertEquals(2, hookCallCount.get());
    assertEquals(1, catalog.search("by-venue", "Maracana").size());
    final List<EventSummary> summaries = catalog.search("by-venue", "Maracana", EventSummary.class);
    assertEquals(1, summaries.size());
    assertEquals(new EventSummary("1"), summaries.get(0));
  }

  @Test
  void whenBuilding_givenNoHooks_shouldWorkAsUsual() {
    final Event e1 = new Event("1", new Sport("Football"), new Venue("Maracana"));
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    catalog.bootstrap();

    assertEquals(1, catalog.search("by-venue", "Maracana").size());
    assertEquals(e1, catalog.search("by-venue", "Maracana").get(0));
  }

  @Test
  void whenBuilding_givenDefaultPriority_shouldUse100() {
    final List<String> order = new ArrayList<>();

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(new Event("1", new Sport("F"), new Venue("V"))))
        .index("by-venue").by(Event::venue, Venue::name)
        .hook(item -> { order.add("default-100"); return item; })
        .hook(item -> { order.add("explicit-50"); return item; }, 50)
        .build();

    catalog.bootstrap();

    assertEquals(List.of("explicit-50", "default-100"), order);
  }
}
