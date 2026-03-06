package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CompoundConditionStep}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class CompoundConditionStepTest {

  private CompoundQuery<String> createQuery() {
    final Map<Object, List<String>> byCountry = new HashMap<>();
    byCountry.put("AR", List.of("item1", "item2"));
    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("by-country", byCountry);
    final Snapshot<String> snapshot = Snapshot.of(
        List.of("item1", "item2"), indices, 1L, "hash");
    return new CompoundQuery<>(snapshot, "test-catalog");
  }

  @Test
  void whenCallingEqualTo_givenValidKey_shouldRegisterEqualToCondition() {
    final CompoundQuery<String> query = createQuery();
    query.where("by-country").equalTo("AR");

    final List<Condition> conditions = query.conditions();
    assertEquals(1, conditions.size());
    assertEquals("by-country", conditions.get(0).indexName());
    assertEquals(Condition.Operation.EQUAL_TO, conditions.get(0).operation());
    assertEquals(1, conditions.get(0).args().length);
    assertEquals("AR", conditions.get(0).args()[0]);
  }

  @Test
  void whenCallingBetween_givenValidRange_shouldRegisterBetweenCondition() {
    final CompoundQuery<String> query = createQuery();
    query.where("by-country").between("A", "Z");

    final List<Condition> conditions = query.conditions();
    assertEquals(1, conditions.size());
    assertEquals("by-country", conditions.get(0).indexName());
    assertEquals(Condition.Operation.BETWEEN, conditions.get(0).operation());
    assertEquals(2, conditions.get(0).args().length);
    assertEquals("A", conditions.get(0).args()[0]);
    assertEquals("Z", conditions.get(0).args()[1]);
  }

  @Test
  void whenCallingGreaterThan_givenValidKey_shouldRegisterGreaterThanCondition() {
    final CompoundQuery<String> query = createQuery();
    query.where("by-country").greaterThan("M");

    final List<Condition> conditions = query.conditions();
    assertEquals(1, conditions.size());
    assertEquals("by-country", conditions.get(0).indexName());
    assertEquals(Condition.Operation.GREATER_THAN, conditions.get(0).operation());
    assertEquals(1, conditions.get(0).args().length);
    assertEquals("M", conditions.get(0).args()[0]);
  }

  @Test
  void whenCallingGreaterOrEqual_givenValidKey_shouldRegisterGreaterOrEqualCondition() {
    final CompoundQuery<String> query = createQuery();
    query.where("by-country").greaterOrEqual("M");

    final List<Condition> conditions = query.conditions();
    assertEquals(1, conditions.size());
    assertEquals("by-country", conditions.get(0).indexName());
    assertEquals(Condition.Operation.GREATER_OR_EQUAL, conditions.get(0).operation());
    assertEquals(1, conditions.get(0).args().length);
    assertEquals("M", conditions.get(0).args()[0]);
  }

  @Test
  void whenCallingLessThan_givenValidKey_shouldRegisterLessThanCondition() {
    final CompoundQuery<String> query = createQuery();
    query.where("by-country").lessThan("M");

    final List<Condition> conditions = query.conditions();
    assertEquals(1, conditions.size());
    assertEquals("by-country", conditions.get(0).indexName());
    assertEquals(Condition.Operation.LESS_THAN, conditions.get(0).operation());
    assertEquals(1, conditions.get(0).args().length);
    assertEquals("M", conditions.get(0).args()[0]);
  }

  @Test
  void whenCallingLessOrEqual_givenValidKey_shouldRegisterLessOrEqualCondition() {
    final CompoundQuery<String> query = createQuery();
    query.where("by-country").lessOrEqual("M");

    final List<Condition> conditions = query.conditions();
    assertEquals(1, conditions.size());
    assertEquals("by-country", conditions.get(0).indexName());
    assertEquals(Condition.Operation.LESS_OR_EQUAL, conditions.get(0).operation());
    assertEquals(1, conditions.get(0).args().length);
    assertEquals("M", conditions.get(0).args()[0]);
  }

  @Test
  void whenCallingStartsWith_givenValidPrefix_shouldRegisterStartsWithCondition() {
    final CompoundQuery<String> query = createQuery();
    query.where("by-country").startsWith("Ar");

    final List<Condition> conditions = query.conditions();
    assertEquals(1, conditions.size());
    assertEquals("by-country", conditions.get(0).indexName());
    assertEquals(Condition.Operation.STARTS_WITH, conditions.get(0).operation());
    assertEquals(1, conditions.get(0).args().length);
    assertEquals("Ar", conditions.get(0).args()[0]);
  }

  @Test
  void whenCallingEndsWith_givenValidSuffix_shouldRegisterEndsWithCondition() {
    final CompoundQuery<String> query = createQuery();
    query.where("by-country").endsWith("na");

    final List<Condition> conditions = query.conditions();
    assertEquals(1, conditions.size());
    assertEquals("by-country", conditions.get(0).indexName());
    assertEquals(Condition.Operation.ENDS_WITH, conditions.get(0).operation());
    assertEquals(1, conditions.get(0).args().length);
    assertEquals("na", conditions.get(0).args()[0]);
  }

  @Test
  void whenCallingContains_givenValidSubstring_shouldRegisterContainsCondition() {
    final CompoundQuery<String> query = createQuery();
    query.where("by-country").contains("gen");

    final List<Condition> conditions = query.conditions();
    assertEquals(1, conditions.size());
    assertEquals("by-country", conditions.get(0).indexName());
    assertEquals(Condition.Operation.CONTAINS, conditions.get(0).operation());
    assertEquals(1, conditions.get(0).args().length);
    assertEquals("gen", conditions.get(0).args()[0]);
  }

  @Test
  void whenChainingMultipleConditions_givenWhereAndAnd_shouldAccumulateAll() {
    final CompoundQuery<String> query = createQuery();
    query.where("by-country").equalTo("AR")
        .and("by-city").equalTo("Buenos Aires");

    final List<Condition> conditions = query.conditions();
    assertEquals(2, conditions.size());
    assertEquals("by-country", conditions.get(0).indexName());
    assertEquals("by-city", conditions.get(1).indexName());
  }

  @Test
  void whenCallingWhereTwice_givenWhereAlreadyCalled_shouldThrowIllegalState() {
    final CompoundQuery<String> query = createQuery();
    query.where("by-country").equalTo("AR");

    assertThrows(IllegalStateException.class,
        () -> query.where("by-city"));
  }

  @Test
  void whenCallingEqualTo_givenNullKey_shouldThrowNullPointerException() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step = query.where("by-country");

    assertThrows(NullPointerException.class,
        () -> step.equalTo(null));
  }

  @Test
  void whenCallingBetween_givenNullFrom_shouldThrowNullPointerException() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step = query.where("by-country");

    assertThrows(NullPointerException.class,
        () -> step.between(null, "Z"));
  }

  @Test
  void whenCallingBetween_givenNullTo_shouldThrowNullPointerException() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step = query.where("by-country");

    assertThrows(NullPointerException.class,
        () -> step.between("A", null));
  }

  @Test
  void whenCallingGreaterThan_givenNullKey_shouldThrowNullPointerException() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step = query.where("by-country");

    assertThrows(NullPointerException.class,
        () -> step.greaterThan(null));
  }

  @Test
  void whenCallingGreaterOrEqual_givenNullKey_shouldThrowNullPointerException() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step = query.where("by-country");

    assertThrows(NullPointerException.class,
        () -> step.greaterOrEqual(null));
  }

  @Test
  void whenCallingLessThan_givenNullKey_shouldThrowNullPointerException() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step = query.where("by-country");

    assertThrows(NullPointerException.class,
        () -> step.lessThan(null));
  }

  @Test
  void whenCallingLessOrEqual_givenNullKey_shouldThrowNullPointerException() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step = query.where("by-country");

    assertThrows(NullPointerException.class,
        () -> step.lessOrEqual(null));
  }

  @Test
  void whenCallingStartsWith_givenNullPrefix_shouldThrowNullPointerException() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step = query.where("by-country");

    assertThrows(NullPointerException.class,
        () -> step.startsWith(null));
  }

  @Test
  void whenCallingEndsWith_givenNullSuffix_shouldThrowNullPointerException() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step = query.where("by-country");

    assertThrows(NullPointerException.class,
        () -> step.endsWith(null));
  }

  @Test
  void whenCallingContains_givenNullSubstring_shouldThrowNullPointerException() {
    final CompoundQuery<String> query = createQuery();
    final CompoundConditionStep<String> step = query.where("by-country");

    assertThrows(NullPointerException.class,
        () -> step.contains(null));
  }
}
