package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GraphIndexDefinitionTest {

  record Country(String code) {}
  record Event(Country country) {}
  record Publication(String categoryPath, List<Event> events) {}

  private GraphIndexDefinition<Publication> buildStandardIndex() {
    return GraphIndexDefinition.<Publication>named("by-country-category")
        .traverseMany("country", Publication::events,
            event -> event.country().code())
        .traversePath("category", "/", Publication::categoryPath)
        .hotpath("country", "category")
        .build();
  }

  @Test
  void whenBuilding_givenTraversalsAndHotpath_shouldGenerateCorrectKeys() {
    final GraphIndexDefinition<Publication> def = buildStandardIndex();
    final Publication pub = new Publication("deportes/futbol",
        List.of(new Event(new Country("AR"))));
    final Map<Object, List<Publication>> index = def.buildIndex(List.of(pub));
    assertEquals(List.of(pub), index.get(CompositeKey.of("AR")));
    assertEquals(List.of(pub), index.get(CompositeKey.of("AR", "deportes")));
    assertEquals(List.of(pub), index.get(CompositeKey.of("AR", "deportes/futbol")));
  }

  @Test
  void whenBuilding_givenFanOut_shouldGenerateKeysForEachValue() {
    final GraphIndexDefinition<Publication> def = buildStandardIndex();
    final Publication pub = new Publication("deportes",
        List.of(new Event(new Country("AR")), new Event(new Country("MX"))));
    final Map<Object, List<Publication>> index = def.buildIndex(List.of(pub));
    assertEquals(List.of(pub), index.get(CompositeKey.of("AR")));
    assertEquals(List.of(pub), index.get(CompositeKey.of("MX")));
    assertEquals(List.of(pub), index.get(CompositeKey.of("AR", "deportes")));
    assertEquals(List.of(pub), index.get(CompositeKey.of("MX", "deportes")));
  }

  @Test
  void whenBuilding_givenDuplicateFanOutValues_shouldDeduplicateKeys() {
    final GraphIndexDefinition<Publication> def = buildStandardIndex();
    final Publication pub = new Publication("deportes",
        List.of(new Event(new Country("AR")), new Event(new Country("AR"))));
    final Map<Object, List<Publication>> index = def.buildIndex(List.of(pub));
    assertEquals(1, index.get(CompositeKey.of("AR")).size());
  }

  @Test
  void whenBuilding_givenNullTraversalResult_shouldExcludeItem() {
    final GraphIndexDefinition<Publication> def = buildStandardIndex();
    final Publication pub = new Publication(null,
        List.of(new Event(new Country("AR"))));
    final Map<Object, List<Publication>> index = def.buildIndex(List.of(pub));
    assertEquals(List.of(pub), index.get(CompositeKey.of("AR")));
    assertEquals(1, index.size());
  }

  @Test
  void whenBuilding_givenMultipleItems_shouldGroupByKey() {
    final GraphIndexDefinition<Publication> def = buildStandardIndex();
    final Publication pub1 = new Publication("deportes",
        List.of(new Event(new Country("AR"))));
    final Publication pub2 = new Publication("deportes",
        List.of(new Event(new Country("AR"))));
    final Map<Object, List<Publication>> index = def.buildIndex(List.of(pub1, pub2));
    assertEquals(2, index.get(CompositeKey.of("AR", "deportes")).size());
    assertTrue(index.get(CompositeKey.of("AR", "deportes")).contains(pub1));
    assertTrue(index.get(CompositeKey.of("AR", "deportes")).contains(pub2));
  }

  @Test
  void whenBuilding_givenEmptyData_shouldReturnEmptyMap() {
    final GraphIndexDefinition<Publication> def = buildStandardIndex();
    final Map<Object, List<Publication>> index = def.buildIndex(List.of());
    assertTrue(index.isEmpty());
  }

  @Test
  void whenBuilding_givenMaxKeysExceeded_shouldThrow() {
    final GraphIndexDefinition<Publication> def =
        GraphIndexDefinition.<Publication>named("test")
            .traverseMany("country", Publication::events,
                event -> event.country().code())
            .traversePath("category", "/", Publication::categoryPath)
            .hotpath("country", "category")
            .maxKeysPerItem(2)
            .build();
    final Publication pub = new Publication("deportes/futbol",
        List.of(new Event(new Country("AR"))));
    assertThrows(IndexKeyLimitExceededException.class,
        () -> def.buildIndex(List.of(pub)));
  }

  @Test
  void whenBuilding_givenMultipleHotpaths_shouldMergeIntoSingleMap() {
    final GraphIndexDefinition<Publication> def =
        GraphIndexDefinition.<Publication>named("test")
            .traverse("first-country",
                pub -> pub.events().isEmpty() ? null
                    : pub.events().getFirst().country().code())
            .traverseMany("all-countries", Publication::events,
                event -> event.country().code())
            .traversePath("category", "/", Publication::categoryPath)
            .hotpath("first-country", "category")
            .hotpath("all-countries")
            .build();
    final Publication pub = new Publication("deportes",
        List.of(new Event(new Country("AR")), new Event(new Country("MX"))));
    final Map<Object, List<Publication>> index = def.buildIndex(List.of(pub));
    assertEquals(List.of(pub), index.get(CompositeKey.of("AR", "deportes")));
    assertEquals(List.of(pub), index.get(CompositeKey.of("MX")));
  }

  @Test
  void whenBuilding_givenItemDuplicateAcrossHotpaths_shouldDeduplicateByIdentity() {
    final GraphIndexDefinition<Publication> def =
        GraphIndexDefinition.<Publication>named("test")
            .traverse("country",
                pub -> pub.events().getFirst().country().code())
            .traverseMany("countries", Publication::events,
                event -> event.country().code())
            .hotpath("country")
            .hotpath("countries")
            .build();
    final Publication pub = new Publication("deportes",
        List.of(new Event(new Country("AR"))));
    final Map<Object, List<Publication>> index = def.buildIndex(List.of(pub));
    assertEquals(1, index.get(CompositeKey.of("AR")).size());
  }

  @Test
  void whenBuilding_givenNoTraversals_shouldThrow() {
    assertThrows(IllegalStateException.class,
        () -> GraphIndexDefinition.<Publication>named("test")
            .hotpath("country").build());
  }

  @Test
  void whenBuilding_givenNoHotpaths_shouldThrow() {
    assertThrows(IllegalStateException.class,
        () -> GraphIndexDefinition.<Publication>named("test")
            .traverse("country", pub -> "AR").build());
  }

  @Test
  void whenBuilding_givenHotpathReferencesUnknownTraversal_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> GraphIndexDefinition.<Publication>named("test")
            .traverse("country", pub -> "AR")
            .hotpath("country", "unknown").build());
  }

  @Test
  void whenBuilding_givenDuplicateTraversalName_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> GraphIndexDefinition.<Publication>named("test")
            .traverse("country", pub -> "AR")
            .traverse("country", pub -> "MX"));
  }

  @Test
  void whenGettingName_shouldReturnConfiguredName() {
    final GraphIndexDefinition<Publication> def = buildStandardIndex();
    assertEquals("by-country-category", def.name());
  }
}
