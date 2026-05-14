package org.waabox.andersoni.sync.kafka;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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
import org.waabox.andersoni.sync.PatchEvent;
import org.waabox.andersoni.sync.PatchListener;
import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.RefreshListener;
import org.waabox.andersoni.sync.SyncMessage;
import org.waabox.andersoni.sync.SyncMessageCodec;
import org.waabox.andersoni.sync.SyncStrategy;

/** Kafka-based implementation of {@link SyncStrategy} that broadcasts
 * {@link RefreshEvent}s to all nodes in the cluster.
 *
 * <p>This strategy uses a unique consumer group per instance (broadcast
 * pattern) so that every node receives every refresh event. Messages are
 * serialized as JSON strings using {@link RefreshEventCodec}.
 *
 * <p><strong>Delivery semantics:</strong> the consumer starts at
 * {@code auto.offset.reset=latest}, so events published in the brief window
 * between {@link #start()} and the consumer joining its group / getting its
 * partition assignment may be missed. This is safe because nodes bootstrap
 * fresh state on start and any missed event is corrected by the next refresh
 * (hash comparison). Note that setting a stable {@code nodeId} on the config
 * yields a stable consumer group, which changes semantics: on restart the node
 * resumes from its last committed offset rather than {@code latest}, and may
 * replay or skip depending on topic retention.
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
public final class KafkaSyncStrategy implements SyncStrategy {

  /** The class logger. */
  private static final Logger log = LoggerFactory.getLogger(
      KafkaSyncStrategy.class);

  /** Poll timeout for the Kafka consumer loop. */
  private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

  /** Backoff applied after a non-fatal poll error before retrying. */
  private static final Duration POLL_ERROR_BACKOFF = Duration.ofSeconds(1);

  /** The Kafka configuration, never null. */
  private final KafkaSyncConfig config;

  /** The registered refresh listeners, never null. Thread-safe. */
  private final List<RefreshListener> listeners = new CopyOnWriteArrayList<>();

  /** The registered patch listeners, never null. Thread-safe. */
  private final List<PatchListener> patchListeners =
      new CopyOnWriteArrayList<>();

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

    if (!running.get()) {
      throw new IllegalStateException(
          "Cannot publish: KafkaSyncStrategy is not running. "
          + "Call start() first.");
    }

    sendMessage(event);
  }

  /** {@inheritDoc} */
  @Override
  public void publishPatch(final PatchEvent event) {
    Objects.requireNonNull(event, "event must not be null");
    if (!running.get()) {
      throw new IllegalStateException(
          "Cannot publish patch: KafkaSyncStrategy is not running. "
          + "Call start() first.");
    }
    sendMessage(event);
  }

  /** {@inheritDoc} */
  @Override
  public boolean supportsPatches() {
    return true;
  }

  /** {@inheritDoc} */
  @Override
  public void subscribe(final RefreshListener listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    listeners.add(listener);
  }

  /** {@inheritDoc} */
  @Override
  public void subscribePatch(final PatchListener listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    patchListeners.add(listener);
  }

  /** Serializes a {@link SyncMessage} and sends it on the configured
   * topic, using the catalog name as the partition key.
   *
   * @param message the message to broadcast, never null
   */
  private void sendMessage(final SyncMessage message) {
    final String json = SyncMessageCodec.serialize(message);
    final ProducerRecord<String, String> record = new ProducerRecord<>(
        config.topic(), message.catalogName(), json);

    producer.send(record, (metadata, exception) -> {
      if (exception != null) {
        log.error("Failed to publish sync message for catalog '{}': {}",
            message.catalogName(), exception.getMessage(), exception);
      } else {
        log.debug("Published sync message for catalog '{}' to partition {}"
            + " offset {}", message.catalogName(), metadata.partition(),
            metadata.offset());
      }
    });
  }

  /** {@inheritDoc} */
  @Override
  public void start() {
    if (running.getAndSet(true)) {
      log.warn("KafkaSyncStrategy is already running");
      return;
    }

    try {
      producer = createProducer();
      consumer = createConsumer();

      consumer.subscribe(Collections.singletonList(config.topic()));

      pollThread = new Thread(this::pollLoop, "andersoni-kafka-sync-poll");
      pollThread.setDaemon(true);
      pollThread.start();

      log.info("KafkaSyncStrategy started on topic '{}' with bootstrap servers "
          + "'{}'", config.topic(), config.bootstrapServers());
    } catch (final RuntimeException e) {
      // Roll back a partial start: close any resource already created and
      // clear the running flag so the producer/consumer do not leak and the
      // instance is not wedged in a "running" state that blocks a retry.
      closeQuietly(producer);
      closeQuietly(consumer);
      producer = null;
      consumer = null;
      running.set(false);
      throw e;
    }
  }

  /** Closes a resource, swallowing and logging any error.
   *
   * @param closeable the resource to close, may be null
   */
  private static void closeQuietly(final AutoCloseable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (final Exception e) {
      log.warn("Error closing Kafka resource: {}", e.getMessage(), e);
    }
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

  /** Notifies all registered patch listeners.
   *
   * <p>Package-private for testability.
   *
   * @param event the patch event to dispatch, never null
   */
  void notifyPatchListeners(final PatchEvent event) {
    for (final PatchListener listener : patchListeners) {
      try {
        listener.onPatch(event);
      } catch (final Exception e) {
        log.error("Patch listener threw exception while processing event "
            + "for catalog '{}': {}", event.catalogName(),
            e.getMessage(), e);
      }
    }
  }

  /** The main consumer poll loop. Runs in a daemon thread until
   * {@link #stop()} is called.
   */
  private void pollLoop() {
    try {
      while (running.get()) {
        try {
          final ConsumerRecords<String, String> records =
              consumer.poll(POLL_TIMEOUT);
          for (final ConsumerRecord<String, String> record : records) {
            try {
              final SyncMessage message = SyncMessageCodec.deserialize(
                  record.value());
              if (message instanceof RefreshEvent refresh) {
                notifyListeners(refresh);
              } else if (message instanceof PatchEvent patch) {
                notifyPatchListeners(patch);
              }
            } catch (final Exception e) {
              log.error("Failed to deserialize sync message from partition {} "
                  + "offset {}: {}", record.partition(), record.offset(),
                  e.getMessage(), e);
            }
          }
        } catch (final WakeupException e) {
          throw e;
        } catch (final Exception e) {
          log.error("Kafka poll failed for topic '{}', backing off {}ms: {}",
              config.topic(), POLL_ERROR_BACKOFF.toMillis(), e.getMessage(), e);
          if (!sleepBackoff()) {
            return;
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

  /** Sleeps for the poll-error backoff duration.
   *
   * @return {@code true} if the sleep completed, {@code false} if the thread
   *     was interrupted (caller should stop the loop).
   */
  private boolean sleepBackoff() {
    try {
      Thread.sleep(POLL_ERROR_BACKOFF.toMillis());
      return true;
    } catch (final InterruptedException ie) {
      Thread.currentThread().interrupt();
      return false;
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
    props.put(ProducerConfig.ACKS_CONFIG, config.acks());
    return new KafkaProducer<>(props);
  }

  /** Creates a new Kafka consumer configured with string deserializers
   * and a unique consumer group for broadcast semantics.
   *
   * @return the Kafka consumer, never null
   */
  private KafkaConsumer<String, String> createConsumer() {
    final String groupSuffix = config.nodeId() != null
        ? config.nodeId()
        : UUID.randomUUID().toString();
    final String groupId = config.consumerGroupPrefix() + groupSuffix;

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
    log.info("Kafka consumer group: {}", groupId);
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
