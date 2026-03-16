package org.waabox.andersoni.sync.kafka;

import java.util.Objects;
import java.util.Set;

/** Configuration holder for Kafka-based cache synchronization.
 *
 * <p>Encapsulates the connection details and topic configuration needed by
 * {@link KafkaSyncStrategy} to publish and consume refresh events. Uses a
 * broadcast pattern where each node gets its own consumer group (formed by
 * {@code consumerGroupPrefix + UUID}), so every instance receives every
 * message.
 *
 * <p>Instances are created via the static factory methods
 * {@link #create(String)} and {@link #create(String, String, String)} for
 * backward compatibility, or via the {@link Builder} for full control over
 * all configuration options.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class KafkaSyncConfig {

  /** Default Kafka topic for sync events. */
  private static final String DEFAULT_TOPIC = "andersoni-sync";

  /** Default consumer group prefix. */
  private static final String DEFAULT_CONSUMER_GROUP_PREFIX = "andersoni-";

  /** Default producer acknowledgment level. */
  private static final String DEFAULT_ACKS = "1";

  /** Valid values for the {@code acks} configuration. */
  private static final Set<String> VALID_ACKS = Set.of("0", "1", "all");

  /** The Kafka bootstrap servers connection string, never null. */
  private final String bootstrapServers;

  /** The Kafka topic where sync events are published, never null. */
  private final String topic;

  /** The prefix for generating unique consumer groups, never null. */
  private final String consumerGroupPrefix;

  /** The Kafka producer acknowledgment level, never null. */
  private final String acks;

  /** The stable node identifier for consumer group naming, may be null. */
  private final String nodeId;

  /** Creates a new KafkaSyncConfig.
   *
   * @param theBootstrapServers the Kafka bootstrap servers, never null
   * @param theTopic the topic name for sync events, never null
   * @param theConsumerGroupPrefix the consumer group prefix, never null
   * @param theAcks the producer acknowledgment level, never null
   * @param theNodeId the stable node identifier, may be null
   */
  private KafkaSyncConfig(
      final String theBootstrapServers,
      final String theTopic,
      final String theConsumerGroupPrefix,
      final String theAcks,
      final String theNodeId) {
    bootstrapServers = Objects.requireNonNull(theBootstrapServers, "bootstrapServers must not be null");
    topic = Objects.requireNonNull(theTopic, "topic must not be null");
    consumerGroupPrefix = Objects.requireNonNull(theConsumerGroupPrefix, "consumerGroupPrefix must not be null");
    acks = Objects.requireNonNull(theAcks, "acks must not be null");

    if (!VALID_ACKS.contains(acks)) {
      throw new IllegalArgumentException("acks must be one of " + VALID_ACKS + ", got: " + acks);
    }

    nodeId = theNodeId;
  }

  /** Creates a configuration with the given bootstrap servers and default
   * topic, consumer group prefix, and acks.
   *
   * @param bootstrapServers the Kafka bootstrap servers (e.g.
   *        "localhost:9092"), never null
   *
   * @return a new KafkaSyncConfig with default values, never null
   */
  public static KafkaSyncConfig create(final String bootstrapServers) {
    return new KafkaSyncConfig(bootstrapServers, DEFAULT_TOPIC, DEFAULT_CONSUMER_GROUP_PREFIX, DEFAULT_ACKS, null);
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
  public static KafkaSyncConfig create(
      final String bootstrapServers,
      final String topic,
      final String consumerGroupPrefix) {
    return new KafkaSyncConfig(bootstrapServers, topic, consumerGroupPrefix, DEFAULT_ACKS, null);
  }

  /** Creates a new {@link Builder} for constructing a KafkaSyncConfig
   * with full control over all configuration options.
   *
   * @return a new Builder instance, never null
   */
  public static Builder builder() {
    return new Builder();
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

  /** Returns the Kafka producer acknowledgment level.
   *
   * <p>Valid values are {@code "0"} (fire and forget), {@code "1"} (leader
   * ack), and {@code "all"} (full ISR ack). Defaults to {@code "1"}.
   *
   * @return the acks configuration, never null
   */
  public String acks() {
    return acks;
  }

  /** Returns the stable node identifier used for consumer group naming.
   *
   * <p>When set, the consumer group can be derived deterministically from
   * this identifier instead of using a random UUID, enabling stable consumer
   * group assignments across restarts.
   *
   * @return the node identifier, or null if not set
   */
  public String nodeId() {
    return nodeId;
  }

  /** Builder for constructing {@link KafkaSyncConfig} instances with full
   * control over all configuration options.
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static final class Builder {

    /** The bootstrap servers, initially null until set. */
    private String bootstrapServers;

    /** The topic, defaults to {@link KafkaSyncConfig#DEFAULT_TOPIC}. */
    private String topic = DEFAULT_TOPIC;

    /** The consumer group prefix, defaults to
     * {@link KafkaSyncConfig#DEFAULT_CONSUMER_GROUP_PREFIX}. */
    private String consumerGroupPrefix = DEFAULT_CONSUMER_GROUP_PREFIX;

    /** The acks configuration, defaults to
     * {@link KafkaSyncConfig#DEFAULT_ACKS}. */
    private String acks = DEFAULT_ACKS;

    /** The node identifier, defaults to null. */
    private String nodeId;

    /** Creates a new Builder. Package-private, use
     * {@link KafkaSyncConfig#builder()}.
     */
    private Builder() {
    }

    /** Sets the Kafka bootstrap servers connection string.
     *
     * @param theBootstrapServers the bootstrap servers, never null
     *
     * @return this builder, never null
     */
    public Builder bootstrapServers(final String theBootstrapServers) {
      Objects.requireNonNull(theBootstrapServers, "bootstrapServers must not be null");
      bootstrapServers = theBootstrapServers;
      return this;
    }

    /** Sets the Kafka topic for sync events.
     *
     * @param theTopic the topic name, never null
     *
     * @return this builder, never null
     */
    public Builder topic(final String theTopic) {
      Objects.requireNonNull(theTopic, "topic must not be null");
      topic = theTopic;
      return this;
    }

    /** Sets the consumer group prefix.
     *
     * @param theConsumerGroupPrefix the consumer group prefix, never null
     *
     * @return this builder, never null
     */
    public Builder consumerGroupPrefix(final String theConsumerGroupPrefix) {
      Objects.requireNonNull(theConsumerGroupPrefix, "consumerGroupPrefix must not be null");
      consumerGroupPrefix = theConsumerGroupPrefix;
      return this;
    }

    /** Sets the Kafka producer acknowledgment level.
     *
     * <p>Must be one of {@code "0"}, {@code "1"}, or {@code "all"}.
     *
     * @param theAcks the acks configuration, never null
     *
     * @return this builder, never null
     */
    public Builder acks(final String theAcks) {
      Objects.requireNonNull(theAcks, "acks must not be null");
      acks = theAcks;
      return this;
    }

    /** Sets the stable node identifier for consumer group naming.
     *
     * @param theNodeId the node identifier, never null
     *
     * @return this builder, never null
     */
    public Builder nodeId(final String theNodeId) {
      Objects.requireNonNull(theNodeId, "nodeId must not be null");
      nodeId = theNodeId;
      return this;
    }

    /** Builds a new {@link KafkaSyncConfig} from this builder's state.
     *
     * @return a new KafkaSyncConfig, never null
     *
     * @throws NullPointerException if bootstrapServers was not set
     * @throws IllegalArgumentException if acks is not a valid value
     */
    public KafkaSyncConfig build() {
      return new KafkaSyncConfig(bootstrapServers, topic, consumerGroupPrefix, acks, nodeId);
    }
  }
}
