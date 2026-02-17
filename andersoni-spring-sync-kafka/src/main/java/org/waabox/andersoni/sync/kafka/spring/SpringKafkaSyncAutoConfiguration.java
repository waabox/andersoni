package org.waabox.andersoni.sync.kafka.spring;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.waabox.andersoni.sync.SyncStrategy;

/** Spring Boot auto-configuration for the Spring Kafka-based synchronization
 * strategy.
 *
 * <p>This configuration creates and manages the Kafka infrastructure beans
 * (producer factory, consumer factory, template, listener container factory)
 * and the {@link SpringKafkaSyncStrategy} bean.
 *
 * <p>Activation conditions:
 * <ul>
 *   <li>{@link KafkaTemplate} must be on the classpath</li>
 *   <li>The property {@code andersoni.sync.kafka.bootstrap-servers} must
 *       be set</li>
 *   <li>No other {@link SyncStrategy} bean must be present</li>
 * </ul>
 *
 * <p>Each node gets a unique consumer group (prefix + UUID) for broadcast
 * semantics, so every instance receives every refresh event.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "andersoni.sync.kafka",
    name = "bootstrap-servers")
@ConditionalOnMissingBean(SyncStrategy.class)
@EnableConfigurationProperties(SpringKafkaSyncProperties.class)
public class SpringKafkaSyncAutoConfiguration {

  /** The class logger. */
  private static final Logger log = LoggerFactory.getLogger(
      SpringKafkaSyncAutoConfiguration.class);

  /** Creates a Kafka producer factory configured with string serializers.
   *
   * @param properties the sync properties, never null
   *
   * @return the producer factory, never null
   */
  @Bean
  public ProducerFactory<String, String> andersoniProducerFactory(
      final SpringKafkaSyncProperties properties) {

    final Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
        properties.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
        StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
        StringSerializer.class);
    props.put(ProducerConfig.ACKS_CONFIG, "1");

    return new DefaultKafkaProducerFactory<>(props);
  }

  /** Creates a Kafka consumer factory configured with string deserializers
   * and a unique consumer group for broadcast semantics.
   *
   * <p>The consumer group ID is set here (prefix + UUID) so the
   * {@code @KafkaListener} in {@link SpringKafkaSyncStrategy} inherits
   * it from the container factory without needing SpEL.
   *
   * @param properties the sync properties, never null
   *
   * @return the consumer factory, never null
   */
  @Bean
  public ConsumerFactory<String, String> andersoniConsumerFactory(
      final SpringKafkaSyncProperties properties) {

    final String groupId = properties.getConsumerGroupPrefix()
        + UUID.randomUUID();

    final Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        properties.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
        StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
        StringDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);

    log.info("Andersoni Kafka consumer group: {}", groupId);

    return new DefaultKafkaConsumerFactory<>(props);
  }

  /** Creates the Kafka template for publishing refresh events.
   *
   * @param producerFactory the producer factory, never null
   *
   * @return the Kafka template, never null
   */
  @Bean
  public KafkaTemplate<String, String> andersoniKafkaTemplate(
      final ProducerFactory<String, String> producerFactory) {

    return new KafkaTemplate<>(producerFactory);
  }

  /** Creates the concurrent Kafka listener container factory used by
   * the {@code @KafkaListener} in {@link SpringKafkaSyncStrategy}.
   *
   * @param consumerFactory the consumer factory, never null
   *
   * @return the listener container factory, never null
   */
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String>
      kafkaListenerContainerFactory(
          final ConsumerFactory<String, String> consumerFactory) {

    final ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    return factory;
  }

  /** Creates the Spring Kafka-based sync strategy bean.
   *
   * @param kafkaTemplate the Kafka template for publishing, never null
   * @param properties the sync properties, never null
   *
   * @return the sync strategy, never null
   */
  @Bean
  public SpringKafkaSyncStrategy springKafkaSyncStrategy(
      final KafkaTemplate<String, String> kafkaTemplate,
      final SpringKafkaSyncProperties properties) {

    log.info("Creating SpringKafkaSyncStrategy with topic '{}' and "
        + "bootstrap servers '{}'",
        properties.getTopic(), properties.getBootstrapServers());

    return new SpringKafkaSyncStrategy(kafkaTemplate, properties.getTopic());
  }
}
