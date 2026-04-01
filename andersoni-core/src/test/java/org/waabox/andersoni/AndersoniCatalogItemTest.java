package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class AndersoniCatalogItemTest {

  record Event(String id, String name) {}

  record EventSummary(String id) {}

  @Test
  void whenCreating_givenItemAndViews_shouldReturnItemAndViews() {
    final Event event = new Event("1", "Final");
    final EventSummary summary = new EventSummary("1");

    final AndersoniCatalogItem<Event> item = AndersoniCatalogItem.of(
        event, Map.of(EventSummary.class, summary)
    );

    assertSame(event, item.item());
    assertEquals(summary, item.view(EventSummary.class));
  }

  @Test
  void whenCreatingWithoutViews_givenOnlyItem_shouldReturnItem() {
    final Event event = new Event("1", "Final");

    final AndersoniCatalogItem<Event> item = AndersoniCatalogItem.of(
        event, Map.of()
    );

    assertSame(event, item.item());
    assertTrue(item.views().isEmpty());
  }

  @Test
  void whenRequestingUnregisteredView_shouldThrowIllegalArgument() {
    final Event event = new Event("1", "Final");

    final AndersoniCatalogItem<Event> item = AndersoniCatalogItem.of(
        event, Map.of()
    );

    assertThrows(IllegalArgumentException.class, () ->
        item.view(EventSummary.class)
    );
  }

  @Test
  void whenCreating_givenNullItem_shouldThrowNPE() {
    assertThrows(NullPointerException.class, () ->
        AndersoniCatalogItem.of(null, Map.of())
    );
  }

  @Test
  void whenCreating_givenNullViews_shouldThrowNPE() {
    assertThrows(NullPointerException.class, () ->
        AndersoniCatalogItem.of(new Event("1", "x"), null)
    );
  }

  @Test
  void whenAccessingViews_shouldReturnUnmodifiableMap() {
    final Event event = new Event("1", "Final");
    final EventSummary summary = new EventSummary("1");

    final AndersoniCatalogItem<Event> item = AndersoniCatalogItem.of(
        event, Map.of(EventSummary.class, summary)
    );

    assertThrows(UnsupportedOperationException.class, () ->
        item.views().put(String.class, "nope")
    );
  }
}
