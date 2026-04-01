package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ViewDefinitionTest {

  record Event(String id, String name, String venue) {}

  record EventSummary(String id, String name) {}

  @Test
  void whenCreating_givenValidTypeAndMapper_shouldStoreTypeAndMapper() {
    final ViewDefinition<Event, EventSummary> view = ViewDefinition.of(
        EventSummary.class,
        e -> new EventSummary(e.id(), e.name())
    );

    assertEquals(EventSummary.class, view.viewType());
    assertNotNull(view.mapper());
  }

  @Test
  void whenApplyingMapper_givenAnEvent_shouldProduceCorrectView() {
    final ViewDefinition<Event, EventSummary> view = ViewDefinition.of(
        EventSummary.class,
        e -> new EventSummary(e.id(), e.name())
    );

    final Event event = new Event("1", "Final", "Maracana");
    final EventSummary summary = view.mapper().apply(event);

    assertEquals("1", summary.id());
    assertEquals("Final", summary.name());
  }

  @Test
  void whenCreating_givenNullViewType_shouldThrowNPE() {
    assertThrows(NullPointerException.class, () ->
        ViewDefinition.of(null, e -> e)
    );
  }

  @Test
  void whenCreating_givenNullMapper_shouldThrowNPE() {
    assertThrows(NullPointerException.class, () ->
        ViewDefinition.of(EventSummary.class, null)
    );
  }
}
