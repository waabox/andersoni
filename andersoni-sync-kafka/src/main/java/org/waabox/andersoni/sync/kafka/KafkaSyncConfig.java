package org.waabox.andersoni.sync.kafka;

import java.util.Objects;

/** Configuration holder for Kafka-based cache synchronization.
 *
 * <p>Encapsulates the connection details and topic configuration needed by
 * {@link KafkaSyncStrategy} to publish and consume refresh events. Uses a
 * broadcast pattern where each node gets its own consumer group (formed by
 * {@code consumerGroupPrefix + UUID}), so every instance receives every
 * message.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class KafkaSyncConfig {

  /** Default Kafka topic for sync events. */
  private static final String DEFAULT_TOPIC = "andersoni-sync";

  /** Default consumer group prefix. */
  private static final String DEFAULT_CONSUMER_GROUP_PREFIX = "andersoni-";

  /** The Kafka bootstrap servers connection string, never null. */
  private final String bootstrapServers;

  /** The Kafka topic where sync events are published, never null. */
  private final String topic;

  /** The prefix for generating unique consumer groups, never null. */
  private final String consumerGroupPrefix;

  /** Creates a new KafkaSyncConfig.
   *
   * @param theBootstrapServers the Kafka bootstrap servers, never null
   * @param theTopic the topic name for sync events, never null
   * @param theConsumerGroupPrefix the consumer group prefix, never null
   */
  private KafkaSyncConfig(final String theBootstrapServers,
      final String theTopic, final String theConsumerGroupPrefix) {
    bootstrapServers = Objects.requireNonNull(theBootstrapServers,
        "bootstrapServers must not be null");
    topic = Objects.requireNonNull(theTopic, "topic must not be null");
    consumerGroupPrefix = Objects.requireNonNull(theConsumerGroupPrefix,
        "consumerGroupPrefix must not be null");
  }

  /** Creates a configuration with the given bootstrap servers and default
   * topic and consumer group prefix.
   *
   * @param bootstrapServers the Kafka bootstrap servers (e.g.
   *        "localhost:9092"), never null
   *
   * @return a new KafkaSyncConfig with default values, never null
   */
  public static KafkaSyncConfig create(final String bootstrapServers) {
    return new KafkaSyncConfig(bootstrapServers, DEFAULT_TOPIC,
        DEFAULT_CONSUMER_GROUP_PREFIX);
  }

  /** Creates a configuration with custom topic and consumer group prefix.
   *
   * @param bootstrapServers the Kafka bootstrap servers (e.g.
   *        "broker1:9092,broker2:9092"), never null
   * @param topic the topic name for sync events, never null
   * @param consumerGroupPrefix the prefix for generating unique consumer
   *        groups, never null
   *
   * @return a new KafkaSyncConfig with the specified values, never null
   */
  public static KafkaSyncConfig create(final String bootstrapServers,
      final String topic, final String consumerGroupPrefix) {
    return new KafkaSyncConfig(bootstrapServers, topic, consumerGroupPrefix);
  }

  /** Returns the Kafka bootstrap servers connection string.
   *
   * @return the bootstrap servers, never null
   */
  public String bootstrapServers() {
    return bootstrapServers;
  }

  /** Returns the Kafka topic where sync events are published and consumed.
   *
   * @return the topic name, never null
   */
  public String topic() {
    return topic;
  }

  /** Returns the prefix used to generate unique consumer group IDs.
   *
   * <p>Each node generates its consumer group as
   * {@code consumerGroupPrefix + UUID} to ensure broadcast semantics (every
   * node receives every message).
   *
   * @return the consumer group prefix, never null
   */
  public String consumerGroupPrefix() {
    return consumerGroupPrefix;
  }
}
