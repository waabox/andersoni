package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DebouncePolicy}.
 */
class DebouncePolicyTest {

  @Test
  void whenCreating_givenWindowOnly_shouldUseWindowAsMaxWait() {
    final DebouncePolicy policy = DebouncePolicy.of(Duration.ofMillis(100));

    assertEquals(Duration.ofMillis(100), policy.window());
    assertEquals(Duration.ofMillis(100), policy.maxWait());
    assertFalse(policy.isPassThrough());
  }

  @Test
  void whenCreating_givenWindowAndMaxWait_shouldRetainValues() {
    final DebouncePolicy policy = DebouncePolicy.of(
        Duration.ofMillis(50), Duration.ofMillis(200));

    assertEquals(Duration.ofMillis(50), policy.window());
    assertEquals(Duration.ofMillis(200), policy.maxWait());
    assertFalse(policy.isPassThrough());
  }

  @Test
  void whenCreating_givenZeroWindow_shouldBePassThrough() {
    final DebouncePolicy policy = DebouncePolicy.of(Duration.ZERO);

    assertTrue(policy.isPassThrough());
    assertEquals(Duration.ZERO, policy.window());
    assertEquals(Duration.ZERO, policy.maxWait());
  }

  @Test
  void whenCreating_givenNegativeWindow_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        DebouncePolicy.of(Duration.ofMillis(-1)));
  }

  @Test
  void whenCreating_givenNegativeMaxWait_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        DebouncePolicy.of(Duration.ofMillis(50), Duration.ofMillis(-1)));
  }

  @Test
  void whenCreating_givenMaxWaitLessThanWindow_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        DebouncePolicy.of(Duration.ofMillis(100), Duration.ofMillis(50)));
  }

  @Test
  void whenCreating_givenZeroWindowAndZeroMaxWait_shouldNotThrow() {
    final DebouncePolicy policy =
        DebouncePolicy.of(Duration.ZERO, Duration.ZERO);

    assertTrue(policy.isPassThrough());
  }

  @Test
  void whenCreating_givenNullWindow_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        DebouncePolicy.of(null));
  }

  @Test
  void whenCreating_givenNullMaxWait_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        DebouncePolicy.of(Duration.ofMillis(50), null));
  }

  @Test
  void whenUsingPassThrough_shouldHaveZeroDurations() {
    final DebouncePolicy policy = DebouncePolicy.passThrough();

    assertNotNull(policy);
    assertTrue(policy.isPassThrough());
    assertEquals(Duration.ZERO, policy.window());
    assertEquals(Duration.ZERO, policy.maxWait());
  }

  @Test
  void whenCallingPassThroughTwice_shouldReturnSameInstance() {
    assertSame(DebouncePolicy.passThrough(), DebouncePolicy.passThrough());
  }
}
