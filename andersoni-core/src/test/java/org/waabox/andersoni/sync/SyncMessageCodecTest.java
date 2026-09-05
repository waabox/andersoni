package org.waabox.andersoni.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

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
  void whenRoundTrip_givenRefreshRequest_shouldPreserveKind() {
    final RefreshEvent request = RefreshEvent.request("events", "node-1",
        Instant.parse("2026-05-14T10:00:00Z"));

    final SyncMessage decoded = SyncMessageCodec.deserialize(
        SyncMessageCodec.serialize(request));

    final RefreshEvent refresh = assertInstanceOf(RefreshEvent.class,
        decoded);
    assertEquals(RefreshKind.REQUEST, refresh.kind());
    assertTrue(refresh.isRequest());
    assertEquals(request, refresh);
  }

  @Test
  void whenDeserialize_givenLegacyPayloadWithoutType_shouldReadAsRefresh() {
    // Exactly what a node running the previous release puts on the wire.
    final RefreshEvent original = new RefreshEvent("events", "node-1", 7L,
        "abc123", Instant.parse("2026-05-14T10:00:00Z"));
    final String legacyJson = RefreshEventCodec.serialize(original);

    final SyncMessage decoded = SyncMessageCodec.deserialize(legacyJson);

    assertEquals(original, assertInstanceOf(RefreshEvent.class, decoded));
  }

  @Test
  void whenDeserialize_givenLegacyRequestWithoutType_shouldPreserveKind() {
    final RefreshEvent request = RefreshEvent.request("events", "node-1",
        Instant.parse("2026-05-14T10:00:00Z"));
    final String legacyJson = RefreshEventCodec.serialize(request);

    final SyncMessage decoded = SyncMessageCodec.deserialize(legacyJson);

    assertTrue(assertInstanceOf(RefreshEvent.class, decoded).isRequest());
  }

  @Test
  void whenDeserialize_givenUnknownKind_shouldFallBackToEvent() {
    final String json = SyncMessageCodec.serialize(
        new RefreshEvent("events", "node-1", 7L, "abc123",
            Instant.parse("2026-05-14T10:00:00Z")))
        .replace("\"kind\":\"EVENT\"", "\"kind\":\"FROM_THE_FUTURE\"");

    final SyncMessage decoded = SyncMessageCodec.deserialize(json);

    assertEquals(RefreshKind.EVENT,
        assertInstanceOf(RefreshEvent.class, decoded).kind());
  }

  @Test
  void whenSerialize_givenPatchEvent_shouldRoundTripByteArrays() {
    final byte[] oldBytes = "old".getBytes();
    final byte[] newBytes = "new".getBytes();
    final PatchEvent original = PatchEvent.replace("events", "node-1", 7L,
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
    assertEquals(original.operations().get(0).indexName(),
        patch.operations().get(0).indexName());
    assertArrayEquals(oldBytes, patch.operations().get(0).oldItem());
    assertArrayEquals(newBytes, patch.operations().get(0).newItem());
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

  @Test
  void whenRoundTrip_givenInsertPatch_shouldOmitTheUnusedPayloads() {
    final PatchEvent original = PatchEvent.insert("events", "node-1", 7L, 8L,
        "fromHash", "toHash", "added".getBytes(),
        Instant.parse("2026-05-14T10:00:00Z"));

    final String json = SyncMessageCodec.serialize(original);
    assertTrue(json.contains("\"operation\":\"INSERT\""));
    assertFalse(json.contains("oldItem"));
    assertFalse(json.contains("indexName"));

    final PatchEvent decoded = assertInstanceOf(PatchEvent.class,
        SyncMessageCodec.deserialize(json));
    final PatchEvent.Op op = decoded.operations().get(0);
    assertEquals(PatchOperation.INSERT, op.operation());
    assertNull(op.oldItem());
    assertNull(op.indexName());
    assertArrayEquals("added".getBytes(), op.newItem());
  }

  @Test
  void whenRoundTrip_givenRemovePatch_shouldOmitTheNewItem() {
    final PatchEvent original = PatchEvent.remove("events", "node-1", 7L, 8L,
        "fromHash", "toHash", "by-id", "gone".getBytes(),
        Instant.parse("2026-05-14T10:00:00Z"));

    final String json = SyncMessageCodec.serialize(original);
    assertTrue(json.contains("\"operation\":\"REMOVE\""));
    assertFalse(json.contains("newItem"));

    final PatchEvent decoded = assertInstanceOf(PatchEvent.class,
        SyncMessageCodec.deserialize(json));
    final PatchEvent.Op op = decoded.operations().get(0);
    assertEquals(PatchOperation.REMOVE, op.operation());
    assertNull(op.newItem());
    assertArrayEquals("gone".getBytes(), op.oldItem());
  }

  @Test
  void whenDeserialize_givenPatchWithoutOperation_shouldReadAsReplace() {
    // What a node that predates batching puts on the wire: one operation in
    // flat fields, with no ops array and no operation discriminator.
    final String json = "{\"type\":\"patch\",\"catalogName\":\"events\","
        + "\"sourceNodeId\":\"node-1\",\"fromVersion\":7,"
        + "\"toVersion\":8,\"fromHash\":\"fromHash\","
        + "\"toHash\":\"toHash\",\"indexName\":\"by-id\","
        + "\"oldItem\":\"" + Base64.getEncoder().encodeToString(
            "old".getBytes()) + "\","
        + "\"newItem\":\"" + Base64.getEncoder().encodeToString(
            "new".getBytes()) + "\","
        + "\"timestamp\":\"2026-05-14T10:00:00Z\"}";

    final PatchEvent decoded = assertInstanceOf(PatchEvent.class,
        SyncMessageCodec.deserialize(json));

    assertEquals(PatchOperation.REPLACE,
        decoded.operations().get(0).operation());
    assertArrayEquals("old".getBytes(),
        decoded.operations().get(0).oldItem());
  }

  @Test
  void whenDeserialize_givenUnknownOperation_shouldRejectRatherThanGuess() {
    final String json = SyncMessageCodec.serialize(
            PatchEvent.insert("events", "node-1", 7L, 8L, "fromHash",
                "toHash", "added".getBytes(),
                Instant.parse("2026-05-14T10:00:00Z")))
        .replace("\"INSERT\"", "\"FROM_THE_FUTURE\"");

    assertThrows(IllegalArgumentException.class,
        () -> SyncMessageCodec.deserialize(json));
  }

  @Test
  void whenRoundTrip_givenBatch_shouldPreserveOperationsInOrder() {
    final PatchEvent original = PatchEvent.batch("events", "node-1", 7L, 8L,
        "fromHash", "toHash",
        List.of(
            new PatchEvent.Op(PatchOperation.REPLACE, "by-id",
                "old".getBytes(), "new".getBytes()),
            new PatchEvent.Op(PatchOperation.INSERT, null, null,
                "added".getBytes()),
            new PatchEvent.Op(PatchOperation.REMOVE, "by-venue",
                "gone".getBytes(), null)),
        Instant.parse("2026-05-14T10:00:00Z"));

    final PatchEvent decoded = assertInstanceOf(PatchEvent.class,
        SyncMessageCodec.deserialize(SyncMessageCodec.serialize(original)));

    assertEquals(3, decoded.operations().size());
    assertEquals(PatchOperation.REPLACE,
        decoded.operations().get(0).operation());
    assertEquals(PatchOperation.INSERT,
        decoded.operations().get(1).operation());
    assertEquals(PatchOperation.REMOVE,
        decoded.operations().get(2).operation());
    assertArrayEquals("added".getBytes(),
        decoded.operations().get(1).newItem());
    assertEquals("by-venue", decoded.operations().get(2).indexName());
    assertNull(decoded.operations().get(2).newItem());
  }

  @Test
  void whenBuild_givenNoOperations_shouldReject() {
    assertThrows(IllegalArgumentException.class,
        () -> PatchEvent.batch("events", "node-1", 7L, 8L, "from", "to",
            List.of(), Instant.parse("2026-05-14T10:00:00Z")));
  }
}
