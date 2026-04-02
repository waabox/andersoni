package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class SnapshotBuildHookTest {

  record Event(String id, String name) {}

  @Test
  void whenProcessing_givenIdentityHook_shouldReturnSameItem() {
    final SnapshotBuildHook<Event> hook = item -> item;
    final Event event = new Event("1", "Final");
    assertEquals(event, hook.process(event));
  }

  @Test
  void whenProcessing_givenTransformHook_shouldReturnTransformed() {
    final SnapshotBuildHook<Event> hook =
        item -> new Event(item.id(), item.name().toUpperCase());
    final Event event = new Event("1", "final");
    final Event result = hook.process(event);
    assertEquals("FINAL", result.name());
  }
}
