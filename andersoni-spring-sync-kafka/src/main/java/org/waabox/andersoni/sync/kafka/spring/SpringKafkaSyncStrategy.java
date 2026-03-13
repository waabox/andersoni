package org.waabox.andersoni.sync.kafka.spring;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.waabox.andersoni.sync.SyncEvent;
import org.waabox.andersoni.sync.SyncEventCodec;
import org.waabox.andersoni.sync.SyncEventListener;
import org.waabox.andersoni.sync.SyncStrategy;

/** Spring Kafka-based implementation of {@link SyncStrategy} that broadcasts
 * {@link SyncEvent}s to all nodes in the cluster.
 *
 * <p>This strategy delegates publishing to {@link KafkaTemplate} and uses
 * {@link KafkaListener} for consuming. The consumer group is unique per
 * instance (broadcast pattern) so that every node receives every sync
 * event. Messages are serialized as JSON strings using
 * {@link SyncEventCodec}.
 *
 * <p>Spring manages the full Kafka listener lifecycle, so {@link #start()}
 * and {@link #stop()} are no-ops.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class SpringKafkaSyncStrategy implements SyncStrategy {

  /** The class logger. */
  private static final Logger log = LoggerFactory.getLogger(
      SpringKafkaSyncStrategy.class);

  /** The Spring Kafka template for publishing messages, never null. */
  private final KafkaTemplate<String, String> kafkaTemplate;

  /** The Kafka topic for sync events, never null. */
  private final String topic;

  /** The registered listeners, never null. Thread-safe. */
  private final List<SyncEventListener> listeners =
      new CopyOnWriteArrayList<>();

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
  public void publish(final SyncEvent event) {
    Objects.requireNonNull(event, "event must not be null");

    final String json = SyncEventCodec.serialize(event);

    kafkaTemplate.send(topic, event.catalogName(), json)
        .whenComplete((result, exception) -> {
          if (exception != null) {
            log.error("Failed to publish sync event for catalog '{}': {}",
                event.catalogName(), exception.getMessage(), exception);
          } else {
            log.debug("Published sync event for catalog '{}' to "
                + "partition {} offset {}",
                event.catalogName(),
                result.getRecordMetadata().partition(),
                result.getRecordMetadata().offset());
          }
        });
  }

  /** {@inheritDoc} */
  @Override
  public void subscribe(final SyncEventListener listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    listeners.add(listener);
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
   * {@link SyncEvent}s.
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
      final SyncEvent event = SyncEventCodec.deserialize(record.value());
      for (final SyncEventListener listener : listeners) {
        listener.onEvent(event);
      }
    } catch (final Exception e) {
      log.error("Failed to process sync event from partition {} "
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
}
