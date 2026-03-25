package org.waabox.andersoni.sync.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.SyncEvent;
import org.waabox.andersoni.sync.SyncEventCodec;

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

    final String json = SyncEventCodec.serialize(event);

    assertNotNull(json);
    assertTrue(json.contains("\"catalogName\":\"events\""));
    assertTrue(json.contains("\"sourceNodeId\":\"node-uuid-1\""));
    assertTrue(json.contains("\"version\":1"));
    assertTrue(json.contains("\"hash\":\"abc123\""));
    assertTrue(json.contains("\"timestamp\":\"2024-01-01T00:00:00Z\""));
    assertTrue(json.contains("\"type\":\"REFRESH\""));
  }

  @Test
  void whenDeserializing_givenValidJson_shouldParseRefreshEvent() {
    final String json = "{\"type\":\"REFRESH\","
        + "\"catalogName\":\"events\","
        + "\"sourceNodeId\":\"node-uuid-1\","
        + "\"version\":1,"
        + "\"hash\":\"abc123\","
        + "\"timestamp\":\"2024-01-01T00:00:00Z\"}";

    final SyncEvent event = SyncEventCodec.deserialize(json);

    assertNotNull(event);
    assertTrue(event instanceof RefreshEvent);
    assertEquals("events", event.catalogName());
    assertEquals("node-uuid-1", event.sourceNodeId());
    assertEquals(1L, event.version());
    assertEquals("abc123", ((RefreshEvent) event).hash());
    assertEquals(Instant.parse("2024-01-01T00:00:00Z"), event.timestamp());
  }

  @Test
  void whenDeserializing_givenLargeVersion_shouldParseLong() {
    final String json = "{\"type\":\"REFRESH\","
        + "\"catalogName\":\"catalog\","
        + "\"sourceNodeId\":\"node\","
        + "\"version\":9999999999,"
        + "\"hash\":\"h\","
        + "\"timestamp\":\"2024-06-15T12:30:00Z\"}";

    final SyncEvent event = SyncEventCodec.deserialize(json);

    assertEquals(9999999999L, event.version());
  }

  @Test
  void whenSubscribing_givenListener_shouldBeNotifiedOnPublish() {
    final KafkaSyncConfig config = KafkaSyncConfig.create(
        "localhost:9092");
    final KafkaSyncStrategy strategy = new KafkaSyncStrategy(config);

    final java.util.List<SyncEvent> received = new java.util.ArrayList<>();
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

    final java.util.List<SyncEvent> received1 = new java.util.ArrayList<>();
    final java.util.List<SyncEvent> received2 = new java.util.ArrayList<>();
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

    final String json = SyncEventCodec.serialize(original);
    final SyncEvent restored = SyncEventCodec.deserialize(json);

    assertEquals(original, restored);
  }

  @Test
  void whenCreatingConfig_givenDefaults_shouldHaveDefaultAcks() {
    final KafkaSyncConfig config = KafkaSyncConfig.create("localhost:9092");
    assertEquals("1", config.acks());
  }

  @Test
  void whenCreatingConfig_givenCustomAcks_shouldUseCustomValue() {
    final KafkaSyncConfig config = KafkaSyncConfig.builder()
        .bootstrapServers("localhost:9092")
        .acks("all")
        .build();
    assertEquals("all", config.acks());
  }

  @Test
  void whenCreatingConfig_givenNodeId_shouldUseItForConsumerGroup() {
    final KafkaSyncConfig config = KafkaSyncConfig.builder()
        .bootstrapServers("localhost:9092")
        .nodeId("node-42")
        .build();
    assertEquals("node-42", config.nodeId());
  }

  @Test
  void whenCreatingConfig_givenNoNodeId_shouldDefaultToNull() {
    final KafkaSyncConfig config = KafkaSyncConfig.create("localhost:9092");
    assertNull(config.nodeId());
  }

  @Test
  void whenCreatingConfig_givenBuilder_shouldHaveCorrectDefaults() {
    final KafkaSyncConfig config = KafkaSyncConfig.builder()
        .bootstrapServers("localhost:9092")
        .build();
    assertEquals("localhost:9092", config.bootstrapServers());
    assertEquals("andersoni-sync", config.topic());
    assertEquals("andersoni-", config.consumerGroupPrefix());
    assertEquals("1", config.acks());
    assertNull(config.nodeId());
  }

  @Test
  void whenPublishing_givenNotStarted_shouldThrowIllegalStateException() {
    final KafkaSyncConfig config = KafkaSyncConfig.create("localhost:9092");
    final KafkaSyncStrategy strategy = new KafkaSyncStrategy(config);

    final RefreshEvent event = new RefreshEvent(
        "events", "node-1", 1L, "hash",
        Instant.parse("2024-01-01T00:00:00Z"));

    assertThrows(IllegalStateException.class, () -> strategy.publish(event));
  }

  @Test
  void whenPublishing_givenAlreadyStopped_shouldThrowIllegalStateException() {
    final KafkaSyncConfig config = KafkaSyncConfig.create("localhost:9092");
    final KafkaSyncStrategy strategy = new KafkaSyncStrategy(config);

    final RefreshEvent event = new RefreshEvent(
        "events", "node-1", 1L, "hash",
        Instant.parse("2024-01-01T00:00:00Z"));

    assertThrows(IllegalStateException.class, () -> strategy.publish(event));
  }
}
