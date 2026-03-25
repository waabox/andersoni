package org.waabox.andersoni.sync.kafka.spring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.waabox.andersoni.sync.SyncStrategy;

/** Unit tests for {@link SpringKafkaSyncAutoConfiguration}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SpringKafkaSyncAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(
              SpringKafkaSyncAutoConfiguration.class));

  @Test
  void whenBootstrapServersSet_shouldCreateAllBeans() {
    contextRunner
        .withPropertyValues(
            "andersoni.sync.kafka.bootstrap-servers=localhost:9092")
        .run(context -> {
          assertTrue(context.containsBean("andersoniProducerFactory"));
          assertTrue(context.containsBean("andersoniConsumerFactory"));
          assertTrue(context.containsBean("andersoniKafkaTemplate"));
          assertTrue(context.containsBean("andersoniKafkaListenerContainerFactory"));
          assertTrue(context.containsBean("springKafkaSyncStrategy"));

          assertNotNull(context.getBean(ProducerFactory.class));
          assertNotNull(context.getBean(ConsumerFactory.class));
          assertNotNull(context.getBean(KafkaTemplate.class));
          assertNotNull(context.getBean(SpringKafkaSyncStrategy.class));
          assertNotNull(context.getBean(SyncStrategy.class));
        });
  }

  @Test
  void whenBootstrapServersNotSet_shouldNotCreateBeans() {
    contextRunner
        .run(context -> {
          assertFalse(context.containsBean("springKafkaSyncStrategy"));
          assertFalse(context.containsBean("andersoniKafkaTemplate"));
        });
  }

  @Test
  void whenExistingSyncStrategyBean_shouldBackOff() {
    contextRunner
        .withPropertyValues(
            "andersoni.sync.kafka.bootstrap-servers=localhost:9092")
        .withBean(SyncStrategy.class, () -> new SyncStrategy() {
          @Override
          public void publish(
              final org.waabox.andersoni.sync.SyncEvent event) { }
          @Override
          public void subscribe(
              final org.waabox.andersoni.sync.SyncListener listener) { }
          @Override
          public void start() { }
          @Override
          public void stop() { }
        })
        .run(context -> {
          assertFalse(context.containsBean("springKafkaSyncStrategy"));
          assertNotNull(context.getBean(SyncStrategy.class));
        });
  }

  @Test
  void whenCustomProperties_shouldUseCustomValues() {
    contextRunner
        .withPropertyValues(
            "andersoni.sync.kafka.bootstrap-servers=broker1:9092",
            "andersoni.sync.kafka.topic=custom-topic",
            "andersoni.sync.kafka.consumer-group-prefix=custom-")
        .run(context -> {
          final SpringKafkaSyncStrategy strategy =
              context.getBean(SpringKafkaSyncStrategy.class);

          assertNotNull(strategy);
          assertTrue(strategy.getTopic().equals("custom-topic"));
        });
  }

  @Test
  void whenExistingKafkaListenerContainerFactory_shouldNotOverwrite() {
    contextRunner
        .withPropertyValues(
            "andersoni.sync.kafka.bootstrap-servers=localhost:9092")
        .withBean("kafkaListenerContainerFactory",
            ConcurrentKafkaListenerContainerFactory.class,
            () -> new ConcurrentKafkaListenerContainerFactory<>())
        .run(context -> {
          assertTrue(context.containsBean("kafkaListenerContainerFactory"));
          assertTrue(context.containsBean("andersoniKafkaListenerContainerFactory"));
          assertTrue(context.containsBean("springKafkaSyncStrategy"));
        });
  }

  @Test
  void whenNodeIdSet_shouldCreateConsumerFactoryWithStableGroup() {
    contextRunner
        .withPropertyValues(
            "andersoni.sync.kafka.bootstrap-servers=localhost:9092",
            "andersoni.sync.kafka.node-id=node-42")
        .run(context -> {
          assertNotNull(context.getBean(ConsumerFactory.class));
          assertNotNull(context.getBean(SpringKafkaSyncStrategy.class));
        });
  }

  @Test
  void whenAcksSet_shouldCreateProducerFactoryWithCustomAcks() {
    contextRunner
        .withPropertyValues(
            "andersoni.sync.kafka.bootstrap-servers=localhost:9092",
            "andersoni.sync.kafka.acks=all")
        .run(context -> {
          assertNotNull(context.getBean(ProducerFactory.class));
        });
  }
}
