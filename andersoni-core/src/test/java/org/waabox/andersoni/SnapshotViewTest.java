package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SnapshotViewTest {

  record Event(String id, String name, String venue) {}
  record EventSummary(String id, String name) {}

  @Test
  void whenSearchingWithView_givenRegisteredView_shouldReturnViewList() {
    final Event e1 = new Event("1", "Final", "Maracana");
    final Event e2 = new Event("2", "Semi", "Wembley");
    final Event e3 = new Event("3", "Quarter", "Maracana");

    final EventSummary s1 = new EventSummary("1", "Final");
    final EventSummary s2 = new EventSummary("2", "Semi");
    final EventSummary s3 = new EventSummary("3", "Quarter");

    final AndersoniCatalogItem<Event> i1 = AndersoniCatalogItem.of(e1, Map.of(EventSummary.class, s1));
    final AndersoniCatalogItem<Event> i2 = AndersoniCatalogItem.of(e2, Map.of(EventSummary.class, s2));
    final AndersoniCatalogItem<Event> i3 = AndersoniCatalogItem.of(e3, Map.of(EventSummary.class, s3));

    final Map<String, Map<Object, List<AndersoniCatalogItem<Event>>>> indices = new HashMap<>();
    indices.put("by-venue", Map.of(
        "Maracana", List.of(i1, i3),
        "Wembley", List.of(i2)
    ));

    final Snapshot<Event> snapshot = Snapshot.ofWithItems(
        List.of(i1, i2, i3), indices, Collections.emptyMap(), Collections.emptyMap(), 1L, "hash123");

    final List<EventSummary> result = snapshot.search("by-venue", "Maracana", EventSummary.class);
    assertEquals(2, result.size());
    assertEquals(s1, result.get(0));
    assertEquals(s3, result.get(1));
  }

  @Test
  void whenSearchingWithView_givenNoMatch_shouldReturnEmptyList() {
    final AndersoniCatalogItem<Event> i1 = AndersoniCatalogItem.of(
        new Event("1", "Final", "Maracana"), Map.of(EventSummary.class, new EventSummary("1", "Final")));

    final Map<String, Map<Object, List<AndersoniCatalogItem<Event>>>> indices = new HashMap<>();
    indices.put("by-venue", Map.of("Maracana", List.of(i1)));

    final Snapshot<Event> snapshot = Snapshot.ofWithItems(
        List.of(i1), indices, Collections.emptyMap(), Collections.emptyMap(), 1L, "hash");

    assertEquals(0, snapshot.search("by-venue", "Unknown", EventSummary.class).size());
  }

  @Test
  void whenSearchingWithView_givenUnregisteredView_shouldThrowIllegalArg() {
    final AndersoniCatalogItem<Event> i1 = AndersoniCatalogItem.of(
        new Event("1", "Final", "Maracana"), Map.of());

    final Map<String, Map<Object, List<AndersoniCatalogItem<Event>>>> indices = new HashMap<>();
    indices.put("by-venue", Map.of("Maracana", List.of(i1)));

    final Snapshot<Event> snapshot = Snapshot.ofWithItems(
        List.of(i1), indices, Collections.emptyMap(), Collections.emptyMap(), 1L, "hash");

    assertThrows(IllegalArgumentException.class, () ->
        snapshot.search("by-venue", "Maracana", EventSummary.class));
  }

  @Test
  void whenSearchingWithoutView_shouldReturnItemsAsUsual() {
    final Event e1 = new Event("1", "Final", "Maracana");
    final AndersoniCatalogItem<Event> i1 = AndersoniCatalogItem.of(
        e1, Map.of(EventSummary.class, new EventSummary("1", "Final")));

    final Map<String, Map<Object, List<AndersoniCatalogItem<Event>>>> indices = new HashMap<>();
    indices.put("by-venue", Map.of("Maracana", List.of(i1)));

    final Snapshot<Event> snapshot = Snapshot.ofWithItems(
        List.of(i1), indices, Collections.emptyMap(), Collections.emptyMap(), 1L, "hash");

    final List<Event> result = snapshot.search("by-venue", "Maracana");
    assertEquals(1, result.size());
    assertEquals(e1, result.get(0));
  }

  @Test
  void whenUsingExistingOfFactory_shouldStillWork() {
    final List<String> data = List.of("a", "b");
    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("by-length", Map.of(1, List.of("a", "b")));

    final Snapshot<String> snapshot = Snapshot.of(data, indices, 1L, "hash");
    assertEquals(List.of("a", "b"), snapshot.search("by-length", 1));
    assertEquals(data, snapshot.data());
  }
}
