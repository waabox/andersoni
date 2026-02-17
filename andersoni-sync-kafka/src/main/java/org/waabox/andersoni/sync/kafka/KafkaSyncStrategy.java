package org.waabox.andersoni.sync.kafka;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.RefreshListener;
import org.waabox.andersoni.sync.SyncStrategy;

/** Kafka-based implementation of {@link SyncStrategy} that broadcasts
 * {@link RefreshEvent}s to all nodes in the cluster.
 *
 * <p>This strategy uses a unique consumer group per instance (broadcast
 * pattern) so that every node receives every refresh event. Messages are
 * serialized as JSON strings using Jackson.
 *
 * <p>Typical usage:
 * <pre>
 *   KafkaSyncConfig config = KafkaSyncConfig.create("localhost:9092");
 *   KafkaSyncStrategy strategy = new KafkaSyncStrategy(config);
 *   strategy.subscribe(event -&gt; reloadCatalog(event));
 *   strategy.start();
 *   // ... later ...
 *   strategy.publish(new RefreshEvent(...));
 *   // ... on shutdown ...
 *   strategy.stop();
 * </pre>
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public class KafkaSyncStrategy implements SyncStrategy {

  /** The class logger. */
  private static final Logger log = LoggerFactory.getLogger(
      KafkaSyncStrategy.class);

  /** Shared ObjectMapper configured for RefreshEvent serialization. */
  private static final ObjectMapper MAPPER = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  /** Poll timeout for the Kafka consumer loop. */
  private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

  /** The Kafka configuration, never null. */
  private final KafkaSyncConfig config;

  /** The registered listeners, never null. Thread-safe. */
  private final List<RefreshListener> listeners = new CopyOnWriteArrayList<>();

  /** Flag indicating whether the poll loop is running. */
  private final AtomicBoolean running = new AtomicBoolean(false);

  /** The Kafka producer, created on {@link #start()}. */
  private volatile KafkaProducer<String, String> producer;

  /** The Kafka consumer, created on {@link #start()}. */
  private volatile KafkaConsumer<String, String> consumer;

  /** The daemon thread running the consumer poll loop. */
  private volatile Thread pollThread;

  /** Creates a new KafkaSyncStrategy with the given configuration.
   *
   * @param theConfig the Kafka synchronization configuration, never null
   */
  public KafkaSyncStrategy(final KafkaSyncConfig theConfig) {
    config = Objects.requireNonNull(theConfig, "config must not be null");
  }

  /** {@inheritDoc} */
  @Override
  public void publish(final RefreshEvent event) {
    Objects.requireNonNull(event, "event must not be null");

    final String json = serialize(event);
    final ProducerRecord<String, String> record = new ProducerRecord<>(
        config.topic(), event.catalogName(), json);

    producer.send(record, (metadata, exception) -> {
      if (exception != null) {
        log.error("Failed to publish refresh event for catalog '{}': {}",
            event.catalogName(), exception.getMessage(), exception);
      } else {
        log.debug("Published refresh event for catalog '{}' to partition {} "
            + "offset {}", event.catalogName(), metadata.partition(),
            metadata.offset());
      }
    });
  }

  /** {@inheritDoc} */
  @Override
  public void subscribe(final RefreshListener listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    listeners.add(listener);
  }

  /** {@inheritDoc} */
  @Override
  public void start() {
    if (running.getAndSet(true)) {
      log.warn("KafkaSyncStrategy is already running");
      return;
    }

    producer = createProducer();
    consumer = createConsumer();

    consumer.subscribe(Collections.singletonList(config.topic()));

    pollThread = new Thread(this::pollLoop, "andersoni-kafka-sync-poll");
    pollThread.setDaemon(true);
    pollThread.start();

    log.info("KafkaSyncStrategy started on topic '{}' with bootstrap servers "
        + "'{}'", config.topic(), config.bootstrapServers());
  }

  /** {@inheritDoc} */
  @Override
  public void stop() {
    if (!running.getAndSet(false)) {
      log.warn("KafkaSyncStrategy is not running");
      return;
    }

    log.info("Stopping KafkaSyncStrategy...");

    if (consumer != null) {
      consumer.wakeup();
    }

    if (pollThread != null) {
      try {
        pollThread.join(5_000);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Interrupted while waiting for poll thread to stop");
      }
    }

    closeQuietly(producer, "producer");
    closeQuietly(consumer, "consumer");

    producer = null;
    consumer = null;
    pollThread = null;

    log.info("KafkaSyncStrategy stopped");
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

  /** Notifies all registered listeners of a refresh event.
   *
   * <p>Package-private for testability.
   *
   * @param event the refresh event to dispatch, never null
   */
  void notifyListeners(final RefreshEvent event) {
    for (final RefreshListener listener : listeners) {
      try {
        listener.onRefresh(event);
      } catch (final Exception e) {
        log.error("Listener threw exception while processing event for "
            + "catalog '{}': {}", event.catalogName(), e.getMessage(), e);
      }
    }
  }

  /** The main consumer poll loop. Runs in a daemon thread until
   * {@link #stop()} is called.
   */
  private void pollLoop() {
    try {
      while (running.get()) {
        final ConsumerRecords<String, String> records =
            consumer.poll(POLL_TIMEOUT);
        for (final ConsumerRecord<String, String> record : records) {
          try {
            final RefreshEvent event = deserialize(record.value());
            notifyListeners(event);
          } catch (final Exception e) {
            log.error("Failed to deserialize refresh event from partition {} "
                + "offset {}: {}", record.partition(), record.offset(),
                e.getMessage(), e);
          }
        }
      }
    } catch (final WakeupException e) {
      if (running.get()) {
        throw e;
      }
      // Expected on shutdown, ignore.
    }
  }

  /** Creates a new Kafka producer configured with string serializers.
   *
   * @return the Kafka producer, never null
   */
  private KafkaProducer<String, String> createProducer() {
    final Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
        config.bootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
        StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
        StringSerializer.class.getName());
    props.put(ProducerConfig.ACKS_CONFIG, "1");
    return new KafkaProducer<>(props);
  }

  /** Creates a new Kafka consumer configured with string deserializers
   * and a unique consumer group for broadcast semantics.
   *
   * @return the Kafka consumer, never null
   */
  private KafkaConsumer<String, String> createConsumer() {
    final String groupId = config.consumerGroupPrefix()
        + UUID.randomUUID();

    final Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        config.bootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
        StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
        StringDeserializer.class.getName());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
    return new KafkaConsumer<>(props);
  }

  /** Closes an AutoCloseable resource quietly, logging any errors.
   *
   * @param closeable the resource to close, may be null
   * @param name the name for logging purposes, never null
   */
  private static void closeQuietly(final AutoCloseable closeable,
      final String name) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (final Exception e) {
        log.warn("Error closing {}: {}", name, e.getMessage(), e);
      }
    }
  }
}
