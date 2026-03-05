package org.waabox.andersoni.snapshot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

class JacksonSnapshotSerializerTest {

  /** A simple test item with a timestamp field. */
  static class Item {

    String id;
    String name;
    LocalDateTime createdAt;

    Item() {
    }

    Item(final String theId, final String theName,
        final LocalDateTime theCreatedAt) {
      id = theId;
      name = theName;
      createdAt = theCreatedAt;
    }
  }

  @Test
  void whenSerializingAndDeserializing_givenValidItems_shouldRoundTrip() {
    final SnapshotSerializer<Item> serializer =
        JacksonSnapshotSerializer.forType(Item.class).build();

    final List<Item> items = List.of(
        new Item("1", "Alpha", LocalDateTime.of(2026, 3, 5, 10, 0)),
        new Item("2", "Beta", LocalDateTime.of(2026, 6, 15, 14, 30))
    );

    final byte[] data = serializer.serialize(items);
    assertNotNull(data);
    assertTrue(data.length > 0);

    final List<Item> restored = serializer.deserialize(data);
    assertEquals(2, restored.size());
    assertEquals("1", restored.get(0).id);
    assertEquals("Alpha", restored.get(0).name);
    assertEquals(LocalDateTime.of(2026, 3, 5, 10, 0),
        restored.get(0).createdAt);
    assertEquals("2", restored.get(1).id);
    assertEquals("Beta", restored.get(1).name);
    assertEquals(LocalDateTime.of(2026, 6, 15, 14, 30),
        restored.get(1).createdAt);
  }

  @Test
  void whenSerializingAndDeserializing_givenEmptyList_shouldRoundTrip() {
    final SnapshotSerializer<Item> serializer =
        JacksonSnapshotSerializer.forType(Item.class).build();

    final byte[] data = serializer.serialize(List.of());
    final List<Item> restored = serializer.deserialize(data);
    assertTrue(restored.isEmpty());
  }

  @Test
  void whenSerializing_givenCustomObjectMapper_shouldUseIt() {
    final ObjectMapper custom = new ObjectMapper();
    custom.registerModule(new JavaTimeModule());
    custom.setVisibility(
        com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,
        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY);
    custom.enable(SerializationFeature.INDENT_OUTPUT);

    final SnapshotSerializer<Item> serializer =
        JacksonSnapshotSerializer.forType(Item.class)
            .withObjectMapper(custom)
            .build();

    final List<Item> items = List.of(
        new Item("1", "Alpha", LocalDateTime.of(2026, 3, 5, 10, 0))
    );

    final byte[] data = serializer.serialize(items);
    final String json = new String(data);
    assertTrue(json.contains("\n"), "Should be indented");

    final List<Item> restored = serializer.deserialize(data);
    assertEquals(1, restored.size());
    assertEquals("1", restored.get(0).id);
  }

  @Test
  void whenCreating_givenNullType_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> JacksonSnapshotSerializer.forType(null));
  }

  @Test
  void whenSerializing_givenNullItems_shouldThrow() {
    final SnapshotSerializer<Item> serializer =
        JacksonSnapshotSerializer.forType(Item.class).build();

    assertThrows(NullPointerException.class,
        () -> serializer.serialize(null));
  }

  @Test
  void whenDeserializing_givenNullData_shouldThrow() {
    final SnapshotSerializer<Item> serializer =
        JacksonSnapshotSerializer.forType(Item.class).build();

    assertThrows(NullPointerException.class,
        () -> serializer.deserialize(null));
  }

  @Test
  void whenBuilding_givenNullObjectMapper_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> JacksonSnapshotSerializer.forType(Item.class)
            .withObjectMapper(null));
  }
}
