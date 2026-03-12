package org.waabox.andersoni.snapshot.json;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

class JacksonSnapshotSerializerTest {

  record SampleItem(String id, String name, LocalDateTime createdAt) { }

  private static final TypeReference<List<SampleItem>> TYPE_REF =
      new TypeReference<>() { };

  @Test
  void whenSerializingAndDeserializing_givenValidItems_shouldRoundTrip() {
    final JacksonSnapshotSerializer<SampleItem> serializer =
        new JacksonSnapshotSerializer<>(TYPE_REF);

    final List<SampleItem> items = List.of(
        new SampleItem("1", "Alpha",
            LocalDateTime.of(2026, 3, 1, 10, 0)),
        new SampleItem("2", "Beta",
            LocalDateTime.of(2026, 3, 2, 14, 30))
    );

    final byte[] data = serializer.serialize(items);
    assertNotNull(data);
    assertTrue(data.length > 0);

    final List<SampleItem> restored = serializer.deserialize(data);
    assertEquals(2, restored.size());
    assertEquals("1", restored.get(0).id());
    assertEquals("Alpha", restored.get(0).name());
    assertEquals(LocalDateTime.of(2026, 3, 1, 10, 0),
        restored.get(0).createdAt());
    assertEquals("2", restored.get(1).id());
  }

  @Test
  void whenSerializingAndDeserializing_givenEmptyList_shouldRoundTrip() {
    final JacksonSnapshotSerializer<SampleItem> serializer =
        new JacksonSnapshotSerializer<>(TYPE_REF);

    final byte[] data = serializer.serialize(List.of());
    final List<SampleItem> restored = serializer.deserialize(data);
    assertTrue(restored.isEmpty());
  }

  @Test
  void whenSerializing_givenNullItems_shouldThrowNPE() {
    final JacksonSnapshotSerializer<SampleItem> serializer =
        new JacksonSnapshotSerializer<>(TYPE_REF);

    assertThrows(NullPointerException.class,
        () -> serializer.serialize(null));
  }

  @Test
  void whenDeserializing_givenNullData_shouldThrowNPE() {
    final JacksonSnapshotSerializer<SampleItem> serializer =
        new JacksonSnapshotSerializer<>(TYPE_REF);

    assertThrows(NullPointerException.class,
        () -> serializer.deserialize(null));
  }

  @Test
  void whenCreating_givenNullTypeReference_shouldThrowNPE() {
    assertThrows(NullPointerException.class,
        () -> new JacksonSnapshotSerializer<>(null));
  }

  @Test
  void whenCreating_givenNullMapperOrTypeRef_shouldThrowNPE() {
    assertThrows(NullPointerException.class,
        () -> new JacksonSnapshotSerializer<>(null, TYPE_REF));
    assertThrows(NullPointerException.class,
        () -> new JacksonSnapshotSerializer<>(new ObjectMapper(), null));
  }

  @Test
  void whenSerializingAndDeserializing_givenCustomObjectMapper_shouldWork() {
    final ObjectMapper custom = new ObjectMapper();
    custom.registerModule(
        new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    final JacksonSnapshotSerializer<SampleItem> serializer =
        new JacksonSnapshotSerializer<>(custom, TYPE_REF);

    final List<SampleItem> items = List.of(
        new SampleItem("x", "Custom",
            LocalDateTime.of(2026, 1, 1, 0, 0))
    );

    final byte[] data = serializer.serialize(items);
    final List<SampleItem> restored = serializer.deserialize(data);
    assertEquals(1, restored.size());
    assertEquals("Custom", restored.get(0).name());
  }
}
