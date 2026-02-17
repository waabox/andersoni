package org.waabox.andersoni.sync.kafka.spring;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.RefreshEventCodec;
import org.waabox.andersoni.sync.RefreshListener;
import org.waabox.andersoni.sync.SyncStrategy;

/** Spring Kafka-based implementation of {@link SyncStrategy} that broadcasts
 * {@link RefreshEvent}s to all nodes in the cluster.
 *
 * <p>This strategy delegates publishing to {@link KafkaTemplate} and uses
 * {@link KafkaListener} for consuming. The consumer group is unique per
 * instance (broadcast pattern) so that every node receives every refresh
 * event. Messages are serialized as JSON strings using
 * {@link RefreshEventCodec}.
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
  private final List<RefreshListener> listeners = new CopyOnWriteArrayList<>();

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

    final String json = RefreshEventCodec.serialize(event);

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
  public void subscribe(final RefreshListener listener) {
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
      final RefreshEvent event = RefreshEventCodec.deserialize(record.value());
      for (final RefreshListener listener : listeners) {
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
}
