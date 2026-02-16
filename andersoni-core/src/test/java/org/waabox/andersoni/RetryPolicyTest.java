package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RetryPolicy}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class RetryPolicyTest {

  @Test
  void whenCreating_givenValidParams_shouldRetainValues() {
    final RetryPolicy policy = RetryPolicy.of(5, Duration.ofSeconds(10));

    assertEquals(5, policy.maxRetries());
    assertEquals(Duration.ofSeconds(10), policy.backoff());
  }

  @Test
  void whenCreating_givenZeroRetries_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        RetryPolicy.of(0, Duration.ofSeconds(1))
    );
  }

  @Test
  void whenCreating_givenNullBackoff_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        RetryPolicy.of(3, null)
    );
  }

  @Test
  void whenUsingDefault_shouldHaveReasonableValues() {
    final RetryPolicy policy = RetryPolicy.defaultPolicy();

    assertNotNull(policy);
    assertEquals(3, policy.maxRetries());
    assertEquals(Duration.ofSeconds(2), policy.backoff());
  }

  @Test
  void whenCreating_givenNegativeRetries_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        RetryPolicy.of(-1, Duration.ofSeconds(1))
    );
  }

  @Test
  void whenCreating_givenNegativeBackoff_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        RetryPolicy.of(3, Duration.ofSeconds(-5))
    );
  }

  @Test
  void whenCreating_givenZeroBackoff_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        RetryPolicy.of(3, Duration.ZERO)
    );
  }
}
