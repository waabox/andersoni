package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ConditionTest {

  @Test
  void whenCreating_givenValidArgs_shouldStoreFields() {
    final Condition condition = new Condition(
        "by-country", Condition.Operation.EQUAL_TO,
        new Object[]{"AR"});

    assertEquals("by-country", condition.indexName());
    assertEquals(Condition.Operation.EQUAL_TO, condition.operation());
    assertNotNull(condition.args());
    assertEquals("AR", condition.args()[0]);
  }

  @Test
  void whenCreating_givenBetween_shouldStoreTwoArgs() {
    final Condition condition = new Condition(
        "by-date", Condition.Operation.BETWEEN,
        new Object[]{"2026-01-01", "2026-12-31"});

    assertEquals(Condition.Operation.BETWEEN, condition.operation());
    assertEquals("2026-01-01", condition.args()[0]);
    assertEquals("2026-12-31", condition.args()[1]);
  }

  @Test
  void whenCreating_givenNullIndexName_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        new Condition(null, Condition.Operation.EQUAL_TO, new Object[]{"AR"}));
  }

  @Test
  void whenCreating_givenNullOperation_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        new Condition("by-country", null, new Object[]{"AR"}));
  }

  @Test
  void whenCreating_givenNullArgs_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        new Condition("by-country", Condition.Operation.EQUAL_TO, null));
  }
}
