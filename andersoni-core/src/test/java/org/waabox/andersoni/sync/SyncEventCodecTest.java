package org.waabox.andersoni.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.waabox.andersoni.PatchOperation;

class SyncEventCodecTest {

  @Test
  void whenSerializing_givenRefreshEvent_shouldRoundTrip() {
    final RefreshEvent original = new RefreshEvent(
        "events", "node-1", 42, "abc123", Instant.parse("2026-01-01T00:00:00Z"));
    final String json = SyncEventCodec.serialize(original);
    final SyncEvent deserialized = SyncEventCodec.deserialize(json);

    assertEquals(RefreshEvent.class, deserialized.getClass());
    final RefreshEvent result = (RefreshEvent) deserialized;
    assertEquals("events", result.catalogName());
    assertEquals("node-1", result.sourceNodeId());
    assertEquals(42, result.version());
    assertEquals("abc123", result.hash());
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), result.timestamp());
  }

  @Test
  void whenSerializing_givenPatchEvent_shouldRoundTrip() {
    final byte[] payload = "test-payload".getBytes();
    final PatchEvent original = new PatchEvent(
        "events", "node-2", 7, PatchOperation.ADD, payload,
        Instant.parse("2026-02-15T12:00:00Z"));
    final String json = SyncEventCodec.serialize(original);
    final SyncEvent deserialized = SyncEventCodec.deserialize(json);

    assertEquals(PatchEvent.class, deserialized.getClass());
    final PatchEvent result = (PatchEvent) deserialized;
    assertEquals("events", result.catalogName());
    assertEquals("node-2", result.sourceNodeId());
    assertEquals(7, result.version());
    assertEquals(PatchOperation.ADD, result.operationType());
    assertArrayEquals(payload, result.payload());
    assertEquals(Instant.parse("2026-02-15T12:00:00Z"), result.timestamp());
  }

  @Test
  void whenDeserializing_givenNullJson_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> SyncEventCodec.deserialize(null));
  }

  @Test
  void whenDeserializing_givenInvalidJson_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> SyncEventCodec.deserialize("not-json"));
  }

  @Test
  void whenDeserializing_givenUnknownType_shouldThrow() {
    final String json = "{\"type\":\"UNKNOWN\",\"catalogName\":\"x\","
        + "\"sourceNodeId\":\"n\",\"version\":1,\"timestamp\":\"2026-01-01T00:00:00Z\"}";
    assertThrows(IllegalArgumentException.class,
        () -> SyncEventCodec.deserialize(json));
  }

  @Test
  void whenSerializing_givenNullEvent_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> SyncEventCodec.serialize(null));
  }
}
