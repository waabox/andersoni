package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReconciliationPolicy}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class ReconciliationPolicyTest {

  @Test
  void whenCreating_givenPositiveInterval_shouldBeEnabledWithThatInterval() {
    final ReconciliationPolicy policy = ReconciliationPolicy.of(Duration.ofSeconds(10));

    assertTrue(policy.enabled());
    assertEquals(Duration.ofSeconds(10), policy.interval());
  }

  @Test
  void whenCreating_givenZeroInterval_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        ReconciliationPolicy.of(Duration.ZERO));
  }

  @Test
  void whenCreating_givenNegativeInterval_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        ReconciliationPolicy.of(Duration.ofSeconds(-1)));
  }

  @Test
  void whenCreating_givenNullInterval_shouldThrow() {
    assertThrows(NullPointerException.class, () -> ReconciliationPolicy.of(null));
  }

  @Test
  void whenUsingDisabled_shouldNotBeEnabled() {
    final ReconciliationPolicy policy = ReconciliationPolicy.disabled();

    assertFalse(policy.enabled());
    assertEquals(Duration.ZERO, policy.interval());
  }

  @Test
  void whenUsingDefault_shouldBeEnabledEveryThirtySeconds() {
    final ReconciliationPolicy policy = ReconciliationPolicy.defaultPolicy();

    assertTrue(policy.enabled());
    assertEquals(Duration.ofSeconds(30), policy.interval());
  }
}
