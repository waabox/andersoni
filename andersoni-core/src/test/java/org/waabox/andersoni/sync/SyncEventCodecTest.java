package org.waabox.andersoni.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SyncEventCodec}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SyncEventCodecTest {

  @Test
  void whenSerializing_givenRefreshEvent_shouldProduceValidJson() {
    final Instant now = Instant.parse("2026-01-15T10:30:00Z");
    final RefreshEvent event = new RefreshEvent(
        "products", "node-1", 42L, "abc123hash", now
    );

    final String json = SyncEventCodec.serialize(event);

    assertNotNull(json);
    assertTrue(json.contains("\"catalogName\":\"products\""));
    assertTrue(json.contains("\"sourceNodeId\":\"node-1\""));
    assertTrue(json.contains("\"version\":42"));
    assertTrue(json.contains("\"hash\":\"abc123hash\""));
    assertTrue(json.contains("\"timestamp\":\"2026-01-15T10:30:00Z\""));
    assertTrue(json.contains("\"type\":\"refresh\""));
  }

  @Test
  void whenDeserializing_givenRefreshJson_shouldParseCorrectly() {
    final String json = "{\"catalogName\":\"orders\","
        + "\"sourceNodeId\":\"node-2\","
        + "\"version\":99,"
        + "\"hash\":\"sha256abc\","
        + "\"timestamp\":\"2026-02-10T08:00:00Z\","
        + "\"type\":\"refresh\"}";

    final SyncEvent event = SyncEventCodec.deserialize(json);

    assertInstanceOf(RefreshEvent.class, event);
    assertEquals("orders", event.catalogName());
    assertEquals("node-2", event.sourceNodeId());
    assertEquals(99L, event.version());
    assertEquals("sha256abc", event.hash());
    assertEquals(Instant.parse("2026-02-10T08:00:00Z"), event.timestamp());
  }

  @Test
  void whenRoundTripping_givenRefreshEvent_shouldBeEqual() {
    final RefreshEvent original = new RefreshEvent(
        "inventory", "node-3", 7L, "hash789",
        Instant.parse("2026-03-01T12:00:00Z")
    );

    final String json = SyncEventCodec.serialize(original);
    final SyncEvent restored = SyncEventCodec.deserialize(json);

    assertInstanceOf(RefreshEvent.class, restored);
    assertEquals(original, restored);
  }

  @Test
  void whenDeserializing_givenJsonWithoutType_shouldDefaultToRefreshEvent() {
    final String json = "{\"catalogName\":\"orders\","
        + "\"sourceNodeId\":\"node-2\","
        + "\"version\":99,"
        + "\"hash\":\"sha256abc\","
        + "\"timestamp\":\"2026-02-10T08:00:00Z\"}";

    final SyncEvent event = SyncEventCodec.deserialize(json);

    assertInstanceOf(RefreshEvent.class, event);
    assertEquals("orders", event.catalogName());
  }

  @Test
  void whenSerializing_givenPatchEvent_shouldIncludeBase64Items() {
    final Instant now = Instant.parse("2026-01-15T10:30:00Z");
    final byte[] items = new byte[] {1, 2, 3, 4, 5};
    final PatchEvent event = new PatchEvent(
        "products", "node-1", 42L, "abc123hash", now,
        PatchType.ADD, items
    );

    final String json = SyncEventCodec.serialize(event);

    assertNotNull(json);
    assertTrue(json.contains("\"type\":\"patch\""));
    assertTrue(json.contains("\"patchType\":\"ADD\""));
    assertTrue(json.contains("\"items\""));
  }

  @Test
  void whenRoundTripping_givenPatchEvent_shouldPreserveAllFields() {
    final Instant now = Instant.parse("2026-01-15T10:30:00Z");
    final byte[] items = "hello world".getBytes();
    final PatchEvent original = new PatchEvent(
        "products", "node-1", 42L, "abc123hash", now,
        PatchType.REMOVE, items
    );

    final String json = SyncEventCodec.serialize(original);
    final SyncEvent restored = SyncEventCodec.deserialize(json);

    assertInstanceOf(PatchEvent.class, restored);
    final PatchEvent patch = (PatchEvent) restored;
    assertEquals(original.catalogName(), patch.catalogName());
    assertEquals(original.sourceNodeId(), patch.sourceNodeId());
    assertEquals(original.version(), patch.version());
    assertEquals(original.hash(), patch.hash());
    assertEquals(original.timestamp(), patch.timestamp());
    assertEquals(original.patchType(), patch.patchType());
    assertArrayEquals(original.items(), patch.items());
  }
}
