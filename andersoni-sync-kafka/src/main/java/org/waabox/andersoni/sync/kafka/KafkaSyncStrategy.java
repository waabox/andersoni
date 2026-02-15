package org.waabox.andersoni.sync.kafka;

import java.time.Duration;
import java.time.Instant;
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
import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.RefreshListener;
import org.waabox.andersoni.sync.SyncStrategy;

/** Kafka-based implementation of {@link SyncStrategy} that broadcasts
 * {@link RefreshEvent}s to all nodes in the cluster.
 *
 * <p>This strategy uses a unique consumer group per instance (broadcast
 * pattern) so that every node receives every refresh event. Messages are
 * serialized as simple JSON strings without external JSON libraries.
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

  /** Serializes a {@link RefreshEvent} to a JSON string.
   *
   * <p>Uses simple string formatting without external JSON libraries. The
   * format is fixed and known, matching the structure of RefreshEvent.
   *
   * @param event the event to serialize, never null
   *
   * @return the JSON string representation, never null
   */
  static String serialize(final RefreshEvent event) {
    Objects.requireNonNull(event, "event must not be null");

    final StringBuilder sb = new StringBuilder(256);
    sb.append("{\"catalogName\":\"").append(escapeJson(event.catalogName()));
    sb.append("\",\"sourceNodeId\":\"").append(
        escapeJson(event.sourceNodeId()));
    sb.append("\",\"version\":").append(event.version());
    sb.append(",\"hash\":\"").append(escapeJson(event.hash()));
    sb.append("\",\"timestamp\":\"").append(event.timestamp().toString());
    sb.append("\"}");
    return sb.toString();
  }

  /** Deserializes a JSON string into a {@link RefreshEvent}.
   *
   * <p>Parses the fixed-format JSON produced by {@link #serialize}. This
   * parser handles the known structure without external JSON libraries.
   *
   * @param json the JSON string to parse, never null
   *
   * @return the deserialized RefreshEvent, never null
   *
   * @throws IllegalArgumentException if the JSON cannot be parsed
   */
  static RefreshEvent deserialize(final String json) {
    Objects.requireNonNull(json, "json must not be null");

    final String catalogName = extractStringValue(json, "catalogName");
    final String sourceNodeId = extractStringValue(json, "sourceNodeId");
    final long version = extractLongValue(json, "version");
    final String hash = extractStringValue(json, "hash");
    final Instant timestamp = Instant.parse(
        extractStringValue(json, "timestamp"));

    return new RefreshEvent(catalogName, sourceNodeId, version, hash,
        timestamp);
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

  /** Extracts a string value for a given key from a simple JSON object.
   *
   * @param json the JSON string, never null
   * @param key the key whose value to extract, never null
   *
   * @return the extracted string value, never null
   *
   * @throws IllegalArgumentException if the key is not found
   */
  private static String extractStringValue(final String json,
      final String key) {
    final String pattern = "\"" + key + "\":\"";
    final int start = json.indexOf(pattern);
    if (start < 0) {
      throw new IllegalArgumentException(
          "Key '" + key + "' not found in JSON: " + json);
    }
    final int valueStart = start + pattern.length();
    final int valueEnd = findUnescapedQuote(json, valueStart);
    return unescapeJson(json.substring(valueStart, valueEnd));
  }

  /** Extracts a long value for a given key from a simple JSON object.
   *
   * @param json the JSON string, never null
   * @param key the key whose value to extract, never null
   *
   * @return the extracted long value
   *
   * @throws IllegalArgumentException if the key is not found or the value
   *         is not a valid long
   */
  private static long extractLongValue(final String json, final String key) {
    final String pattern = "\"" + key + "\":";
    final int start = json.indexOf(pattern);
    if (start < 0) {
      throw new IllegalArgumentException(
          "Key '" + key + "' not found in JSON: " + json);
    }
    final int valueStart = start + pattern.length();
    int valueEnd = valueStart;
    while (valueEnd < json.length()
        && (Character.isDigit(json.charAt(valueEnd))
            || json.charAt(valueEnd) == '-')) {
      valueEnd++;
    }
    return Long.parseLong(json.substring(valueStart, valueEnd));
  }

  /** Finds the position of the next unescaped double-quote character.
   *
   * @param json the JSON string to search, never null
   * @param from the index to start searching from
   *
   * @return the index of the closing quote
   *
   * @throws IllegalArgumentException if no closing quote is found
   */
  private static int findUnescapedQuote(final String json, final int from) {
    int i = from;
    while (i < json.length()) {
      if (json.charAt(i) == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
        return i;
      }
      i++;
    }
    throw new IllegalArgumentException(
        "No closing quote found starting at position " + from);
  }

  /** Escapes special characters for JSON string values.
   *
   * @param value the string to escape, never null
   *
   * @return the escaped string, never null
   */
  private static String escapeJson(final String value) {
    final StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }

  /** Unescapes JSON string escape sequences.
   *
   * @param value the escaped string, never null
   *
   * @return the unescaped string, never null
   */
  private static String unescapeJson(final String value) {
    final StringBuilder sb = new StringBuilder(value.length());
    int i = 0;
    while (i < value.length()) {
      if (value.charAt(i) == '\\' && i + 1 < value.length()) {
        final char next = value.charAt(i + 1);
        switch (next) {
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          default -> {
            sb.append('\\');
            sb.append(next);
          }
        }
        i += 2;
      } else {
        sb.append(value.charAt(i));
        i++;
      }
    }
    return sb.toString();
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
