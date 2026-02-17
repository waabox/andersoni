package org.waabox.andersoni.sync.kafka.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link SpringKafkaSyncProperties}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SpringKafkaSyncPropertiesTest {

  @Test
  void whenCreatingDefaults_shouldHaveExpectedValues() {
    final SpringKafkaSyncProperties properties =
        new SpringKafkaSyncProperties();

    assertNull(properties.getBootstrapServers());
    assertEquals("andersoni-sync", properties.getTopic());
    assertEquals("andersoni-", properties.getConsumerGroupPrefix());
  }

  @Test
  void whenSettingBootstrapServers_shouldReturnConfiguredValue() {
    final SpringKafkaSyncProperties properties =
        new SpringKafkaSyncProperties();

    properties.setBootstrapServers("broker1:9092,broker2:9092");

    assertEquals("broker1:9092,broker2:9092",
        properties.getBootstrapServers());
  }

  @Test
  void whenSettingTopic_shouldReturnConfiguredValue() {
    final SpringKafkaSyncProperties properties =
        new SpringKafkaSyncProperties();

    properties.setTopic("custom-topic");

    assertEquals("custom-topic", properties.getTopic());
  }

  @Test
  void whenSettingConsumerGroupPrefix_shouldReturnConfiguredValue() {
    final SpringKafkaSyncProperties properties =
        new SpringKafkaSyncProperties();

    properties.setConsumerGroupPrefix("my-app-");

    assertEquals("my-app-", properties.getConsumerGroupPrefix());
  }
}
