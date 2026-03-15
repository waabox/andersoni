package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class QueryPlannerTest {

  record Country(String code) {}
  record Event(Country country) {}
  record Pub(String categoryPath, List<Event> events) {}

  private GraphIndexDefinition<Pub> twoFieldIndex() {
    return GraphIndexDefinition.<Pub>named("by-country-cat")
        .traverseMany("country", Pub::events,
            event -> event.country().code())
        .traversePath("category", "/", Pub::categoryPath)
        .hotpath("country", "category")
        .build();
  }

  @Test
  void whenPlanning_givenTwoMatchingFields_shouldSelectFullHotpath() {
    final var index = twoFieldIndex();
    final Map<String, GraphQueryCondition> conditions = new LinkedHashMap<>();
    conditions.put("country", new GraphQueryCondition("country",
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{"AR"}));
    conditions.put("category", new GraphQueryCondition("category",
        GraphQueryCondition.Operation.EQUAL_TO,
        new Object[]{"deportes/futbol"}));

    final QueryPlanner.Plan<Pub> plan =
        QueryPlanner.plan(List.of(index), conditions);

    assertEquals("by-country-cat", plan.graphIndexName());
    assertEquals(CompositeKey.of("AR", "deportes/futbol"), plan.key());
    assertTrue(plan.postFilterConditions().isEmpty());
  }

  @Test
  void whenPlanning_givenOneMatchingField_shouldSelectPrefixHotpath() {
    final var index = twoFieldIndex();
    final Map<String, GraphQueryCondition> conditions = new LinkedHashMap<>();
    conditions.put("country", new GraphQueryCondition("country",
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{"AR"}));

    final QueryPlanner.Plan<Pub> plan =
        QueryPlanner.plan(List.of(index), conditions);

    assertEquals(CompositeKey.of("AR"), plan.key());
    assertTrue(plan.postFilterConditions().isEmpty());
  }

  @Test
  void whenPlanning_givenNoMatchingFields_shouldReturnNull() {
    final var index = twoFieldIndex();
    final Map<String, GraphQueryCondition> conditions = new LinkedHashMap<>();
    conditions.put("unknown", new GraphQueryCondition("unknown",
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{"X"}));

    final QueryPlanner.Plan<Pub> plan =
        QueryPlanner.plan(List.of(index), conditions);

    assertNull(plan);
  }

  @Test
  void whenPlanning_givenPartialMatch_shouldPostFilter() {
    final var index = twoFieldIndex();
    final Map<String, GraphQueryCondition> conditions = new LinkedHashMap<>();
    conditions.put("country", new GraphQueryCondition("country",
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{"AR"}));
    conditions.put("organizer", new GraphQueryCondition("organizer",
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{"AFA"}));

    final QueryPlanner.Plan<Pub> plan =
        QueryPlanner.plan(List.of(index), conditions);

    assertEquals(CompositeKey.of("AR"), plan.key());
    assertEquals(1, plan.postFilterConditions().size());
    assertEquals("organizer",
        plan.postFilterConditions().getFirst().fieldName());
  }

  @Test
  void whenPlanning_givenMultipleIndexes_shouldSelectBestCoverage() {
    final var index1 = twoFieldIndex();
    final var index2 = GraphIndexDefinition.<Pub>named("by-country-only")
        .traverse("country",
            pub -> pub.events().getFirst().country().code())
        .hotpath("country")
        .build();

    final Map<String, GraphQueryCondition> conditions = new LinkedHashMap<>();
    conditions.put("country", new GraphQueryCondition("country",
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{"AR"}));
    conditions.put("category", new GraphQueryCondition("category",
        GraphQueryCondition.Operation.EQUAL_TO, new Object[]{"deportes"}));

    final QueryPlanner.Plan<Pub> plan =
        QueryPlanner.plan(List.of(index1, index2), conditions);

    assertEquals("by-country-cat", plan.graphIndexName());
    assertEquals(CompositeKey.of("AR", "deportes"), plan.key());
  }
}
