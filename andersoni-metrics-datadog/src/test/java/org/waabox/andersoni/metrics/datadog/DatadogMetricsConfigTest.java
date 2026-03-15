package org.waabox.andersoni.metrics.datadog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link DatadogMetricsConfig}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class DatadogMetricsConfigTest {

  @Test
  void whenCreatingConfig_givenDefaults_shouldHaveCorrectValues() {
    final DatadogMetricsConfig config = DatadogMetricsConfig.builder()
        .build();

    assertNull(config.host());
    assertEquals(DatadogMetricsConfig.DEFAULT_PORT, config.port());
    assertEquals(DatadogMetricsConfig.DEFAULT_PREFIX, config.prefix());
    assertArrayEquals(new String[0], config.constantTags());
    assertEquals(DatadogMetricsConfig.DEFAULT_POLLING_INTERVAL,
        config.pollingInterval());
  }

  @Test
  void whenCreatingConfig_givenCustomValues_shouldHaveCustomValues() {
    final DatadogMetricsConfig config = DatadogMetricsConfig.builder()
        .host("custom-host")
        .port(9125)
        .prefix("myapp.andersoni")
        .constantTags("service:my-app", "env:staging")
        .pollingInterval(Duration.ofSeconds(60))
        .build();

    assertEquals("custom-host", config.host());
    assertEquals(9125, config.port());
    assertEquals("myapp.andersoni", config.prefix());
    assertArrayEquals(
        new String[]{"service:my-app", "env:staging"},
        config.constantTags());
    assertEquals(Duration.ofSeconds(60), config.pollingInterval());
  }

  @Test
  void whenCreatingConfig_givenNullPrefix_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        DatadogMetricsConfig.builder().prefix(null).build());
  }

  @Test
  void whenCreatingConfig_givenNullPollingInterval_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        DatadogMetricsConfig.builder().pollingInterval(null).build());
  }

  @Test
  void whenCreatingConfig_givenZeroPollingInterval_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        DatadogMetricsConfig.builder()
            .pollingInterval(Duration.ZERO).build());
  }

  @Test
  void whenCreatingConfig_givenNegativePort_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        DatadogMetricsConfig.builder().port(-1).build());
  }

  @Test
  void whenCreatingConfig_givenPortAboveMax_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        DatadogMetricsConfig.builder().port(70000).build());
  }
}
