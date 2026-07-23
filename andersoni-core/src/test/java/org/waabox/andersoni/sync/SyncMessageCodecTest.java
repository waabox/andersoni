package org.waabox.andersoni.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SyncMessageCodec} covering type discrimination for
 * both {@link RefreshEvent} and {@link PatchEvent}.
 */
class SyncMessageCodecTest {

  @Test
  void whenSerialize_givenRefreshEvent_shouldIncludeTypeDiscriminator() {
    final RefreshEvent event = new RefreshEvent("events", "node-1", 7L,
        "abc123", Instant.parse("2026-05-14T10:00:00Z"));

    final String json = SyncMessageCodec.serialize(event);

    assertTrue(json.contains("\"type\":\"refresh\""));
    assertTrue(json.contains("\"catalogName\":\"events\""));
    assertTrue(json.contains("\"hash\":\"abc123\""));
  }

  @Test
  void whenDeserialize_givenRefreshJson_shouldReturnRefreshEvent() {
    final RefreshEvent original = new RefreshEvent("events", "node-1", 7L,
        "abc123", Instant.parse("2026-05-14T10:00:00Z"));
    final String json = SyncMessageCodec.serialize(original);

    final SyncMessage decoded = SyncMessageCodec.deserialize(json);

    final RefreshEvent refresh = assertInstanceOf(RefreshEvent.class,
        decoded);
    assertEquals(original, refresh);
  }

  @Test
  void whenSerialize_givenPatchEvent_shouldRoundTripByteArrays() {
    final byte[] oldBytes = "old".getBytes();
    final byte[] newBytes = "new".getBytes();
    final PatchEvent original = new PatchEvent("events", "node-1", 7L,
        8L, "fromHash", "toHash", "by-id", oldBytes, newBytes,
        Instant.parse("2026-05-14T10:00:00Z"));

    final String json = SyncMessageCodec.serialize(original);
    final SyncMessage decoded = SyncMessageCodec.deserialize(json);

    final PatchEvent patch = assertInstanceOf(PatchEvent.class, decoded);
    assertEquals(original.catalogName(), patch.catalogName());
    assertEquals(original.fromVersion(), patch.fromVersion());
    assertEquals(original.toVersion(), patch.toVersion());
    assertEquals(original.fromHash(), patch.fromHash());
    assertEquals(original.toHash(), patch.toHash());
    assertEquals(original.indexName(), patch.indexName());
    assertArrayEquals(oldBytes, patch.serializedOldItem());
    assertArrayEquals(newBytes, patch.serializedNewItem());
    assertEquals(original.timestamp(), patch.timestamp());
  }

  @Test
  void whenDeserialize_givenUnknownType_shouldThrow() {
    final String json = "{\"type\":\"mystery\",\"catalogName\":\"x\"}";

    assertThrows(IllegalArgumentException.class,
        () -> SyncMessageCodec.deserialize(json));
  }

  @Test
  void whenDeserialize_givenMalformedJson_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> SyncMessageCodec.deserialize("{not json"));
  }
}
