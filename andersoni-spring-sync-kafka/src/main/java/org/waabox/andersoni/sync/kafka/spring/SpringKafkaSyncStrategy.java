package org.waabox.andersoni.sync.kafka.spring;

import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.RefreshListener;
import org.waabox.andersoni.sync.SyncStrategy;

/** Spring Kafka-based implementation of {@link SyncStrategy} that broadcasts
 * {@link RefreshEvent}s to all nodes in the cluster.
 *
 * <p>This strategy delegates publishing to {@link KafkaTemplate} and uses
 * {@link KafkaListener} for consuming. The consumer group is unique per
 * instance (broadcast pattern) so that every node receives every refresh
 * event. Messages are serialized as JSON strings using Jackson.
 *
 * <p>Spring manages the full Kafka listener lifecycle, so {@link #start()}
 * and {@link #stop()} are no-ops.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public class SpringKafkaSyncStrategy implements SyncStrategy {

  /** The class logger. */
  private static final Logger log = LoggerFactory.getLogger(
      SpringKafkaSyncStrategy.class);

  /** Shared ObjectMapper configured for RefreshEvent serialization. */
  private static final ObjectMapper MAPPER = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  /** The Spring Kafka template for publishing messages, never null. */
  private final KafkaTemplate<String, String> kafkaTemplate;

  /** The Kafka topic for sync events, never null. */
  private final String topic;

  /** The single registered listener, null until subscribed. */
  private volatile RefreshListener listener;

  /** Creates a new SpringKafkaSyncStrategy.
   *
   * @param theKafkaTemplate the Spring Kafka template for publishing,
   *        never null
   * @param theTopic the Kafka topic for sync events, never null
   */
  public SpringKafkaSyncStrategy(
      final KafkaTemplate<String, String> theKafkaTemplate,
      final String theTopic) {
    kafkaTemplate = Objects.requireNonNull(theKafkaTemplate,
        "kafkaTemplate must not be null");
    topic = Objects.requireNonNull(theTopic,
        "topic must not be null");
  }

  /** {@inheritDoc} */
  @Override
  public void publish(final RefreshEvent event) {
    Objects.requireNonNull(event, "event must not be null");

    final String json = serialize(event);

    kafkaTemplate.send(topic, event.catalogName(), json)
        .whenComplete((result, exception) -> {
          if (exception != null) {
            log.error("Failed to publish refresh event for catalog '{}': {}",
                event.catalogName(), exception.getMessage(), exception);
          } else {
            log.debug("Published refresh event for catalog '{}' to "
                + "partition {} offset {}",
                event.catalogName(),
                result.getRecordMetadata().partition(),
                result.getRecordMetadata().offset());
          }
        });
  }

  /** {@inheritDoc} */
  @Override
  public void subscribe(final RefreshListener theListener) {
    Objects.requireNonNull(theListener, "listener must not be null");
    listener = theListener;
  }

  /** No-op: Spring manages the Kafka listener container lifecycle.
   *
   * {@inheritDoc}
   */
  @Override
  public void start() {
    log.debug("start() is a no-op; Spring manages the Kafka listener "
        + "container lifecycle");
  }

  /** No-op: Spring manages the Kafka listener container lifecycle.
   *
   * {@inheritDoc}
   */
  @Override
  public void stop() {
    log.debug("stop() is a no-op; Spring manages the Kafka listener "
        + "container lifecycle");
  }

  /** Handles incoming Kafka messages containing serialized
   * {@link RefreshEvent}s.
   *
   * <p>The consumer group is configured in the
   * {@code andersoniConsumerFactory} bean (prefix + random UUID),
   * ensuring broadcast semantics. The listener inherits it from the
   * container factory.
   *
   * @param record the Kafka consumer record, never null
   */
  @KafkaListener(
      topics = "#{@springKafkaSyncStrategy.getTopic()}")
  public void onMessage(final ConsumerRecord<String, String> record) {
    try {
      final RefreshEvent event = deserialize(record.value());
      if (listener != null) {
        listener.onRefresh(event);
      }
    } catch (final Exception e) {
      log.error("Failed to process refresh event from partition {} "
          + "offset {}: {}", record.partition(), record.offset(),
          e.getMessage(), e);
    }
  }

  /** Returns the Kafka topic used by this strategy.
   *
   * <p>Exposed for SpEL topic resolution in {@code @KafkaListener}.
   *
   * @return the topic name, never null
   */
  public String getTopic() {
    return topic;
  }

  /** Serializes a {@link RefreshEvent} to a JSON string using Jackson.
   *
   * @param event the event to serialize, never null
   *
   * @return the JSON string representation, never null
   *
   * @throws IllegalArgumentException if the event cannot be serialized
   */
  static String serialize(final RefreshEvent event) {
    Objects.requireNonNull(event, "event must not be null");
    try {
      return MAPPER.writeValueAsString(event);
    } catch (final JsonProcessingException e) {
      throw new IllegalArgumentException(
          "Failed to serialize RefreshEvent: " + e.getMessage(), e);
    }
  }

  /** Deserializes a JSON string into a {@link RefreshEvent} using Jackson.
   *
   * @param json the JSON string to parse, never null
   *
   * @return the deserialized RefreshEvent, never null
   *
   * @throws IllegalArgumentException if the JSON cannot be parsed
   */
  static RefreshEvent deserialize(final String json) {
    Objects.requireNonNull(json, "json must not be null");
    try {
      return MAPPER.readValue(json, RefreshEvent.class);
    } catch (final JsonProcessingException e) {
      throw new IllegalArgumentException(
          "Failed to deserialize RefreshEvent: " + e.getMessage(), e);
    }
  }
}
