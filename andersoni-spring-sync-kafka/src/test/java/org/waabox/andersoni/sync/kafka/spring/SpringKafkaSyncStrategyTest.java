package org.waabox.andersoni.sync.kafka.spring;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.easymock.Capture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.RefreshEventCodec;

/** Unit tests for {@link SpringKafkaSyncStrategy}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SpringKafkaSyncStrategyTest {

  @Test
  void whenSerializing_givenRefreshEvent_shouldProduceValidJson() {
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
  void whenDeserializing_givenInvalidJson_shouldThrowException() {
    assertThrows(IllegalArgumentException.class, () ->
        RefreshEventCodec.deserialize("not valid json"));
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

  @Test
  @SuppressWarnings("unchecked")
  void whenPublishing_givenRefreshEvent_shouldSendViaKafkaTemplate() {
    final KafkaTemplate<String, String> template = createMock(
        KafkaTemplate.class);

    final SpringKafkaSyncStrategy strategy =
        new SpringKafkaSyncStrategy(template, "test-topic");

    final Instant timestamp = Instant.parse("2024-01-01T00:00:00Z");
    final RefreshEvent event = new RefreshEvent(
        "events", "node-1", 1L, "hash", timestamp);

    final String expectedJson = RefreshEventCodec.serialize(event);

    final RecordMetadata metadata = new RecordMetadata(
        new TopicPartition("test-topic", 0), 0L, 0, 0L, 0, 0);
    final ProducerRecord<String, String> producerRecord =
        new ProducerRecord<>("test-topic", "events", expectedJson);
    final SendResult<String, String> sendResult =
        new SendResult<>(producerRecord, metadata);

    final Capture<String> topicCapture = newCapture();
    final Capture<String> keyCapture = newCapture();
    final Capture<String> valueCapture = newCapture();

    expect(template.send(
        capture(topicCapture),
        capture(keyCapture),
        capture(valueCapture)))
        .andReturn(CompletableFuture.completedFuture(sendResult));

    replay(template);

    strategy.publish(event);

    verify(template);

    assertEquals("test-topic", topicCapture.getValue());
    assertEquals("events", keyCapture.getValue());
    assertEquals(expectedJson, valueCapture.getValue());
  }

  @Test
  @SuppressWarnings("unchecked")
  void whenReceivingMessage_givenSubscribedListener_shouldBeNotified() {
    final KafkaTemplate<String, String> template = createMock(
        KafkaTemplate.class);

    final SpringKafkaSyncStrategy strategy =
        new SpringKafkaSyncStrategy(template, "test-topic");

    final List<RefreshEvent> received = new ArrayList<>();
    strategy.subscribe(received::add);

    final Instant timestamp = Instant.parse("2024-01-01T00:00:00Z");
    final RefreshEvent event = new RefreshEvent(
        "events", "node-uuid-1", 1L, "abc123", timestamp);
    final String json = RefreshEventCodec.serialize(event);

    final ConsumerRecord<String, String> record =
        new ConsumerRecord<>("test-topic", 0, 0L, "events", json);

    strategy.onMessage(record);

    assertEquals(1, received.size());
    assertEquals(event, received.get(0));
  }

  @Test
  @SuppressWarnings("unchecked")
  void whenReceivingMessage_givenInvalidJson_shouldNotThrow() {
    final KafkaTemplate<String, String> template = createMock(
        KafkaTemplate.class);

    final SpringKafkaSyncStrategy strategy =
        new SpringKafkaSyncStrategy(template, "test-topic");

    final List<RefreshEvent> received = new ArrayList<>();
    strategy.subscribe(received::add);

    final ConsumerRecord<String, String> record =
        new ConsumerRecord<>("test-topic", 0, 0L, "key", "bad json");

    strategy.onMessage(record);

    assertTrue(received.isEmpty());
  }

  @Test
  @SuppressWarnings("unchecked")
  void whenGettingTopic_shouldReturnConfiguredTopic() {
    final KafkaTemplate<String, String> template = createMock(
        KafkaTemplate.class);

    final SpringKafkaSyncStrategy strategy =
        new SpringKafkaSyncStrategy(template, "my-topic");

    assertEquals("my-topic", strategy.getTopic());
  }
}
