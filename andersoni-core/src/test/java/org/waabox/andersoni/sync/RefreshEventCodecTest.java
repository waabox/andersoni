package org.waabox.andersoni.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RefreshEventCodec}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class RefreshEventCodecTest {

  @Test
  void whenSerializing_givenRefreshEvent_shouldProduceValidJson() {
    final Instant now = Instant.parse("2026-01-15T10:30:00Z");
    final RefreshEvent event = new RefreshEvent(
        "products", "node-1", 42L, "abc123hash", now
    );

    final String json = RefreshEventCodec.serialize(event);

    assertNotNull(json);
    assertTrue(json.contains("\"catalogName\":\"products\""));
    assertTrue(json.contains("\"sourceNodeId\":\"node-1\""));
    assertTrue(json.contains("\"version\":42"));
    assertTrue(json.contains("\"hash\":\"abc123hash\""));
    assertTrue(json.contains("\"timestamp\":\"2026-01-15T10:30:00Z\""));
  }

  @Test
  void whenDeserializing_givenValidJson_shouldParseCorrectly() {
    final String json = "{\"catalogName\":\"orders\","
        + "\"sourceNodeId\":\"node-2\","
        + "\"version\":99,"
        + "\"hash\":\"sha256abc\","
        + "\"timestamp\":\"2026-02-10T08:00:00Z\"}";

    final RefreshEvent event = RefreshEventCodec.deserialize(json);

    assertNotNull(event);
    assertEquals("orders", event.catalogName());
    assertEquals("node-2", event.sourceNodeId());
    assertEquals(99L, event.version());
    assertEquals("sha256abc", event.hash());
    assertEquals(Instant.parse("2026-02-10T08:00:00Z"), event.timestamp());
  }

  @Test
  void whenRoundTripping_givenEvent_shouldBeEqual() {
    final RefreshEvent original = new RefreshEvent(
        "inventory", "node-3", 7L, "hash789",
        Instant.parse("2026-03-01T12:00:00Z")
    );

    final String json = RefreshEventCodec.serialize(original);
    final RefreshEvent restored = RefreshEventCodec.deserialize(json);

    assertEquals(original, restored);
  }
}
