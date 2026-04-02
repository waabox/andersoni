package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MultiKeyIndexDefinitionTest {

  record Category(String id, String name, List<String> ancestorIds) {}
  record Publication(String id, Category category) {}

  @Test
  void whenBuilding_givenMultipleKeys_shouldIndexUnderEachKey() {
    final Category sports = new Category("cat-1", "Sports", List.of("cat-1"));
    final Category football = new Category("cat-2", "Football", List.of("cat-1", "cat-2"));
    final Publication p1 = new Publication("pub-1", football);
    final Publication p2 = new Publication("pub-2", sports);
    final MultiKeyIndexDefinition<Publication> index =
        MultiKeyIndexDefinition.<Publication>named("by-category")
            .by(pub -> pub.category().ancestorIds());
    final Map<Object, List<Publication>> result = index.buildIndex(List.of(p1, p2));
    assertEquals(2, result.get("cat-1").size());
    assertTrue(result.get("cat-1").contains(p1));
    assertTrue(result.get("cat-1").contains(p2));
    assertEquals(1, result.get("cat-2").size());
    assertTrue(result.get("cat-2").contains(p1));
  }

  @Test
  void whenBuilding_givenEmptyKeyList_shouldNotIndex() {
    final Category empty = new Category("cat-1", "Empty", List.of());
    final Publication p1 = new Publication("pub-1", empty);
    final MultiKeyIndexDefinition<Publication> index =
        MultiKeyIndexDefinition.<Publication>named("by-category")
            .by(pub -> pub.category().ancestorIds());
    final Map<Object, List<Publication>> result = index.buildIndex(List.of(p1));
    assertTrue(result.isEmpty());
  }

  @Test
  void whenBuilding_givenEmptyData_shouldReturnEmptyMap() {
    final MultiKeyIndexDefinition<Publication> index =
        MultiKeyIndexDefinition.<Publication>named("by-category")
            .by(pub -> pub.category().ancestorIds());
    final Map<Object, List<Publication>> result = index.buildIndex(List.of());
    assertTrue(result.isEmpty());
  }

  @Test
  void whenBuilding_shouldReturnUnmodifiableMap() {
    final Category cat = new Category("cat-1", "Sports", List.of("cat-1"));
    final Publication p1 = new Publication("pub-1", cat);
    final MultiKeyIndexDefinition<Publication> index =
        MultiKeyIndexDefinition.<Publication>named("by-category")
            .by(pub -> pub.category().ancestorIds());
    final Map<Object, List<Publication>> result = index.buildIndex(List.of(p1));
    assertThrows(UnsupportedOperationException.class, () -> result.put("injected", List.of()));
    assertThrows(UnsupportedOperationException.class, () -> result.get("cat-1").add(p1));
  }

  @Test
  void whenGettingName_shouldReturnConfiguredName() {
    final MultiKeyIndexDefinition<Publication> index =
        MultiKeyIndexDefinition.<Publication>named("by-category")
            .by(pub -> pub.category().ancestorIds());
    assertEquals("by-category", index.name());
  }

  @Test
  void whenCreating_givenNullName_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () -> MultiKeyIndexDefinition.<Publication>named(null));
  }

  @Test
  void whenCreating_givenEmptyName_shouldThrowIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () -> MultiKeyIndexDefinition.<Publication>named(""));
  }

  @Test
  void whenCreating_givenNullExtractor_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        MultiKeyIndexDefinition.<Publication>named("test").by(null));
  }

  @Test
  void whenBuilding_givenNullData_shouldThrowNpe() {
    final MultiKeyIndexDefinition<Publication> index =
        MultiKeyIndexDefinition.<Publication>named("test")
            .by(pub -> pub.category().ancestorIds());
    assertThrows(NullPointerException.class, () -> index.buildIndex(null));
  }

  @Test
  void whenAccumulating_givenMultipleKeys_shouldAddToAllBuckets() {
    final MultiKeyIndexDefinition<Publication> indexDef =
        MultiKeyIndexDefinition.<Publication>named("by-category")
            .by(pub -> pub.category().ancestorIds());

    final Category football = new Category("cat-2", "Football",
        List.of("cat-1", "cat-2"));
    final Publication pub = new Publication("pub-1", football);
    final AndersoniCatalogItem<Publication> item =
        AndersoniCatalogItem.of(pub, Map.of());

    final Map<Object, List<AndersoniCatalogItem<Publication>>> index =
        new HashMap<>();
    indexDef.accumulate(item, index);

    assertEquals(2, index.size());
    assertEquals(1, index.get("cat-1").size());
    assertSame(item, index.get("cat-1").get(0));
    assertEquals(1, index.get("cat-2").size());
    assertSame(item, index.get("cat-2").get(0));
  }

  @Test
  void whenAccumulating_givenEmptyKeyList_shouldSkipItem() {
    final MultiKeyIndexDefinition<Publication> indexDef =
        MultiKeyIndexDefinition.<Publication>named("by-category")
            .by(pub -> pub.category().ancestorIds());

    final Category empty = new Category("cat-1", "Empty", List.of());
    final Publication pub = new Publication("pub-1", empty);
    final AndersoniCatalogItem<Publication> item =
        AndersoniCatalogItem.of(pub, Map.of());

    final Map<Object, List<AndersoniCatalogItem<Publication>>> index =
        new HashMap<>();
    indexDef.accumulate(item, index);

    assertTrue(index.isEmpty());
  }

  @Test
  void whenAccumulating_givenNullKeyInList_shouldSkipNullKey() {
    final MultiKeyIndexDefinition<Publication> indexDef =
        MultiKeyIndexDefinition.<Publication>named("by-category")
            .by(pub -> pub.category().ancestorIds());

    final Category withNull = new Category("cat-1", "WithNull",
        java.util.Arrays.asList("cat-1", null, "cat-2"));
    final Publication pub = new Publication("pub-1", withNull);
    final AndersoniCatalogItem<Publication> item =
        AndersoniCatalogItem.of(pub, Map.of());

    final Map<Object, List<AndersoniCatalogItem<Publication>>> index =
        new HashMap<>();
    indexDef.accumulate(item, index);

    assertEquals(2, index.size());
    assertTrue(index.containsKey("cat-1"));
    assertTrue(index.containsKey("cat-2"));
  }
}
