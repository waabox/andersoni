package org.waabox.andersoni.example.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.waabox.andersoni.snapshot.SnapshotSerializer;

import java.time.LocalDateTime;
import java.util.List;

class EventSnapshotSerializerTest {

  private final SnapshotSerializer<Event> serializer =
      new EventSnapshotSerializer();

  @Test
  void whenSerializingAndDeserializing_givenValidEvents_shouldRoundTrip() {
    List<Event> events = List.of(
        new Event("e1", "Match 1", "FOOTBALL", "Wembley",
            "Team A", "Team B", "SCHEDULED",
            LocalDateTime.of(2026, 3, 15, 20, 0)),
        new Event("e2", "Match 2", "BASKETBALL", "MSG",
            "Team C", "Team D", "LIVE",
            LocalDateTime.of(2026, 3, 16, 18, 30))
    );

    byte[] data = serializer.serialize(events);
    assertNotNull(data);
    assertTrue(data.length > 0);

    List<Event> restored = serializer.deserialize(data);
    assertEquals(2, restored.size());
    assertEquals("e1", restored.get(0).getId());
    assertEquals("FOOTBALL", restored.get(0).getSport());
    assertEquals("Wembley", restored.get(0).getVenue());
    assertEquals("e2", restored.get(1).getId());
    assertEquals("BASKETBALL", restored.get(1).getSport());
    assertEquals(LocalDateTime.of(2026, 3, 16, 18, 30),
        restored.get(1).getStartTime());
  }

  @Test
  void whenSerializingAndDeserializing_givenEmptyList_shouldRoundTrip() {
    byte[] data = serializer.serialize(List.of());
    List<Event> restored = serializer.deserialize(data);
    assertTrue(restored.isEmpty());
  }
}
