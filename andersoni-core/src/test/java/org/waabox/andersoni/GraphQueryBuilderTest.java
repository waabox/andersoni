package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GraphQueryBuilderTest {

  record Country(String code) {}
  record Event(Country country) {}
  record Pub(String name, String categoryPath, List<Event> events) {}

  private GraphIndexDefinition<Pub> buildIndex() {
    return GraphIndexDefinition.<Pub>named("by-country-cat")
        .traverseMany("country", Pub::events,
            event -> event.country().code())
        .traversePath("category", "/", Pub::categoryPath)
        .hotpath("country", "category")
        .build();
  }

  private Snapshot<Pub> buildSnapshot(final List<Pub> data,
      final GraphIndexDefinition<Pub> index) {
    final Map<Object, List<Pub>> builtIndex = index.buildIndex(data);
    final Map<String, Map<Object, List<Pub>>> indices =
        Map.of(index.name(), builtIndex);
    return Snapshot.of(data, indices, 1L, "test-hash");
  }

  @Test
  void whenQuerying_givenCountryOnly_shouldReturnMatches() {
    final var index = buildIndex();
    final Pub pub1 = new Pub("p1", "deportes",
        List.of(new Event(new Country("AR"))));
    final Pub pub2 = new Pub("p2", "deportes",
        List.of(new Event(new Country("MX"))));
    final Snapshot<Pub> snapshot = buildSnapshot(List.of(pub1, pub2), index);

    final GraphQueryBuilder<Pub> builder = new GraphQueryBuilder<>(
        snapshot, List.of(index));
    final List<Pub> result = builder
        .where("country").eq("AR")
        .execute();

    assertEquals(1, result.size());
    assertTrue(result.contains(pub1));
  }

  @Test
  void whenQuerying_givenCountryAndCategory_shouldReturnMatches() {
    final var index = buildIndex();
    final Pub pub1 = new Pub("p1", "deportes/futbol",
        List.of(new Event(new Country("AR"))));
    final Pub pub2 = new Pub("p2", "deportes/basket",
        List.of(new Event(new Country("AR"))));
    final Snapshot<Pub> snapshot = buildSnapshot(List.of(pub1, pub2), index);

    final GraphQueryBuilder<Pub> builder = new GraphQueryBuilder<>(
        snapshot, List.of(index));
    final List<Pub> result = builder
        .where("country").eq("AR")
        .and("category").eq("deportes/futbol")
        .execute();

    assertEquals(1, result.size());
    assertTrue(result.contains(pub1));
  }

  @Test
  void whenQuerying_givenCategoryPrefix_shouldReturnMatchesAtThatLevel() {
    final var index = buildIndex();
    final Pub pub1 = new Pub("p1", "deportes/futbol",
        List.of(new Event(new Country("AR"))));
    final Pub pub2 = new Pub("p2", "deportes/basket",
        List.of(new Event(new Country("AR"))));
    final Pub pub3 = new Pub("p3", "musica/rock",
        List.of(new Event(new Country("AR"))));
    final Snapshot<Pub> snapshot = buildSnapshot(List.of(pub1, pub2, pub3), index);

    final GraphQueryBuilder<Pub> builder = new GraphQueryBuilder<>(
        snapshot, List.of(index));
    final List<Pub> result = builder
        .where("country").eq("AR")
        .and("category").eq("deportes")
        .execute();

    assertEquals(2, result.size());
    assertTrue(result.contains(pub1));
    assertTrue(result.contains(pub2));
  }

  @Test
  void whenQuerying_givenNoMatchingIndex_shouldReturnEmptyList() {
    final var index = buildIndex();
    final Pub pub1 = new Pub("p1", "deportes",
        List.of(new Event(new Country("AR"))));
    final Snapshot<Pub> snapshot = buildSnapshot(List.of(pub1), index);

    final GraphQueryBuilder<Pub> builder = new GraphQueryBuilder<>(
        snapshot, List.of(index));
    final List<Pub> result = builder
        .where("unknown").eq("X")
        .execute();

    assertTrue(result.isEmpty());
  }

  @Test
  void whenQuerying_givenUncoveredConditions_shouldThrow() {
    final var index = buildIndex();
    final Pub pub1 = new Pub("p1", "deportes",
        List.of(new Event(new Country("AR"))));
    final Snapshot<Pub> snapshot = buildSnapshot(List.of(pub1), index);

    final GraphQueryBuilder<Pub> builder = new GraphQueryBuilder<>(
        snapshot, List.of(index));

    assertThrows(UnsupportedOperationException.class, () -> builder
        .where("country").eq("AR")
        .and("organizer").eq("AFA")
        .execute());
  }

  @Test
  void whenQuerying_givenEmptyConditions_shouldReturnEmptyList() {
    final var index = buildIndex();
    final Pub pub1 = new Pub("p1", "deportes",
        List.of(new Event(new Country("AR"))));
    final Snapshot<Pub> snapshot = buildSnapshot(List.of(pub1), index);

    final GraphQueryBuilder<Pub> builder = new GraphQueryBuilder<>(
        snapshot, List.of(index));
    final List<Pub> result = builder.execute();

    assertTrue(result.isEmpty());
  }
}
