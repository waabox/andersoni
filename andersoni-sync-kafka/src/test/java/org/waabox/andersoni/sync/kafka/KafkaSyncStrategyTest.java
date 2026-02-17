package org.waabox.andersoni.sync.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.RefreshEventCodec;

/** Unit tests for {@link KafkaSyncStrategy} and {@link KafkaSyncConfig}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class KafkaSyncStrategyTest {

  @Test
  void whenCreatingConfig_givenDefaults_shouldHaveCorrectValues() {
    final KafkaSyncConfig config = KafkaSyncConfig.create(
        "localhost:9092");

    assertEquals("localhost:9092", config.bootstrapServers());
    assertEquals("andersoni-sync", config.topic());
    assertEquals("andersoni-", config.consumerGroupPrefix());
  }

  @Test
  void whenCreatingConfig_givenCustomValues_shouldHaveCustomValues() {
    final KafkaSyncConfig config = KafkaSyncConfig.create(
        "broker1:9092,broker2:9092", "custom-topic", "custom-prefix-");

    assertEquals("broker1:9092,broker2:9092", config.bootstrapServers());
    assertEquals("custom-topic", config.topic());
    assertEquals("custom-prefix-", config.consumerGroupPrefix());
  }

  @Test
  void whenPublishing_givenRefreshEvent_shouldSerializeCorrectly() {
    final Instant timestamp = Instant.parse("2024-01-01T00:00:00Z");

    final RefreshEvent event = new RefreshEvent(
        "events", "node-uuid-1", 1L, "abc123", timestamp);

    final String json = RefreshEventCodec.serialize(event);

    assertNotNull(json);
    assertTrue(json.contains("\"catalogName\":\"events\""));
    assertTrue(json.contains("\"sourceNodeId\":\"node-uuid-1\""));
    assertTrue(json.contains("\"version\":1"));
    assertTrue(json.contains("\"hash\":\"abc123\""));
    assertTrue(json.contains("\"timestamp\":\"2024-01-01T00:00:00Z\""));
  }

  @Test
  void whenDeserializing_givenValidJson_shouldParseRefreshEvent() {
    final String json = "{\"catalogName\":\"events\","
        + "\"sourceNodeId\":\"node-uuid-1\","
        + "\"version\":1,"
        + "\"hash\":\"abc123\","
        + "\"timestamp\":\"2024-01-01T00:00:00Z\"}";

    final RefreshEvent event = RefreshEventCodec.deserialize(json);

    assertNotNull(event);
    assertEquals("events", event.catalogName());
    assertEquals("node-uuid-1", event.sourceNodeId());
    assertEquals(1L, event.version());
    assertEquals("abc123", event.hash());
    assertEquals(Instant.parse("2024-01-01T00:00:00Z"), event.timestamp());
  }

  @Test
  void whenDeserializing_givenLargeVersion_shouldParseLong() {
    final String json = "{\"catalogName\":\"catalog\","
        + "\"sourceNodeId\":\"node\","
        + "\"version\":9999999999,"
        + "\"hash\":\"h\","
        + "\"timestamp\":\"2024-06-15T12:30:00Z\"}";

    final RefreshEvent event = RefreshEventCodec.deserialize(json);

    assertEquals(9999999999L, event.version());
  }

  @Test
  void whenSubscribing_givenListener_shouldBeNotifiedOnPublish() {
    final KafkaSyncConfig config = KafkaSyncConfig.create(
        "localhost:9092");
    final KafkaSyncStrategy strategy = new KafkaSyncStrategy(config);

    final java.util.List<RefreshEvent> received = new java.util.ArrayList<>();
    strategy.subscribe(received::add);

    final Instant timestamp = Instant.parse("2024-01-01T00:00:00Z");
    final RefreshEvent event = new RefreshEvent(
        "events", "node-uuid-1", 1L, "abc123", timestamp);

    // Directly test the listener notification mechanism.
    strategy.notifyListeners(event);

    assertEquals(1, received.size());
    assertEquals(event, received.get(0));
  }

  @Test
  void whenSubscribing_givenMultipleListeners_shouldNotifyAll() {
    final KafkaSyncConfig config = KafkaSyncConfig.create(
        "localhost:9092");
    final KafkaSyncStrategy strategy = new KafkaSyncStrategy(config);

    final java.util.List<RefreshEvent> received1 = new java.util.ArrayList<>();
    final java.util.List<RefreshEvent> received2 = new java.util.ArrayList<>();
    strategy.subscribe(received1::add);
    strategy.subscribe(received2::add);

    final Instant timestamp = Instant.parse("2024-01-01T00:00:00Z");
    final RefreshEvent event = new RefreshEvent(
        "events", "node-uuid-1", 1L, "abc123", timestamp);

    strategy.notifyListeners(event);

    assertEquals(1, received1.size());
    assertEquals(1, received2.size());
    assertEquals(event, received1.get(0));
    assertEquals(event, received2.get(0));
  }

  @Test
  void whenSerializingAndDeserializing_givenRoundTrip_shouldProduceEqualEvent() {
    final Instant timestamp = Instant.parse("2024-07-20T15:45:30Z");

    final RefreshEvent original = new RefreshEvent(
        "products", "node-abc-def", 42L, "sha256hash", timestamp);

    final String json = RefreshEventCodec.serialize(original);
    final RefreshEvent restored = RefreshEventCodec.deserialize(json);

    assertEquals(original, restored);
  }
}
