package org.waabox.andersoni.sync.kafka.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for the Spring Kafka-based synchronization
 * strategy.
 *
 * <p>These properties are bound from the {@code andersoni.sync.kafka}
 * prefix in the application's configuration (e.g. {@code application.yaml}).
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@ConfigurationProperties(prefix = "andersoni.sync.kafka")
public class SpringKafkaSyncProperties {

  /** The Kafka bootstrap servers connection string, never null after
   * configuration binding. */
  private String bootstrapServers;

  /** The Kafka topic for sync events, defaults to "andersoni-sync". */
  private String topic = "andersoni-sync";

  /** The prefix for generating unique consumer group IDs per node,
   * defaults to "andersoni-". */
  private String consumerGroupPrefix = "andersoni-";

  /** The producer acknowledgment level, defaults to "1". */
  private String acks = "1";

  /** The optional stable node identifier for consumer group naming. */
  private String nodeId;

  /** Returns the Kafka bootstrap servers connection string.
   *
   * @return the bootstrap servers, may be null if not yet configured
   */
  public String getBootstrapServers() {
    return bootstrapServers;
  }

  /** Sets the Kafka bootstrap servers connection string.
   *
   * @param theBootstrapServers the bootstrap servers string, never null
   */
  public void setBootstrapServers(final String theBootstrapServers) {
    bootstrapServers = theBootstrapServers;
  }

  /** Returns the Kafka topic for sync events.
   *
   * @return the topic name, never null
   */
  public String getTopic() {
    return topic;
  }

  /** Sets the Kafka topic for sync events.
   *
   * @param theTopic the topic name, never null
   */
  public void setTopic(final String theTopic) {
    topic = theTopic;
  }

  /** Returns the consumer group prefix for broadcast semantics.
   *
   * @return the consumer group prefix, never null
   */
  public String getConsumerGroupPrefix() {
    return consumerGroupPrefix;
  }

  /** Sets the consumer group prefix for broadcast semantics.
   *
   * @param theConsumerGroupPrefix the consumer group prefix, never null
   */
  public void setConsumerGroupPrefix(final String theConsumerGroupPrefix) {
    consumerGroupPrefix = theConsumerGroupPrefix;
  }

  /** Returns the producer acknowledgment level.
   *
   * @return the acks setting, never null
   */
  public String getAcks() {
    return acks;
  }

  /** Sets the producer acknowledgment level.
   *
   * @param theAcks the acks setting (e.g. "0", "1", "all"), never null
   */
  public void setAcks(final String theAcks) {
    acks = theAcks;
  }

  /** Returns the optional stable node identifier for consumer group naming.
   *
   * @return the node identifier, may be null if not configured
   */
  public String getNodeId() {
    return nodeId;
  }

  /** Sets the stable node identifier for consumer group naming.
   *
   * @param theNodeId the node identifier, may be null
   */
  public void setNodeId(final String theNodeId) {
    nodeId = theNodeId;
  }
}
