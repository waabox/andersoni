package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class CatalogMultiKeyTest {

  record Category(String id, List<String> ancestorIds) {}
  record Publication(String id, String country, Category category) {}

  @Test
  void whenBootstrapping_givenMultiKeyIndex_shouldIndexCorrectly() {
    final Category sports = new Category("cat-1", List.of("cat-1"));
    final Category football = new Category("cat-2", List.of("cat-1", "cat-2"));
    final Publication p1 = new Publication("pub-1", "AR", football);
    final Publication p2 = new Publication("pub-2", "AR", sports);
    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of(p1, p2))
        .index("by-country").by(Publication::country, Function.identity())
        .indexMulti("by-category").by(pub -> pub.category().ancestorIds())
        .build();
    catalog.bootstrap();
    final List<Publication> byCat1 = catalog.search("by-category", "cat-1");
    assertEquals(2, byCat1.size());
    final List<Publication> byCat2 = catalog.search("by-category", "cat-2");
    assertEquals(1, byCat2.size());
    assertTrue(byCat2.contains(p1));
  }

  @Test
  void whenCompound_givenMultiKeyIndex_shouldIntersect() {
    final Category sports = new Category("cat-1", List.of("cat-1"));
    final Category football = new Category("cat-2", List.of("cat-1", "cat-2"));
    final Publication p1 = new Publication("pub-1", "AR", football);
    final Publication p2 = new Publication("pub-2", "BR", sports);
    final Publication p3 = new Publication("pub-3", "AR", sports);
    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of(p1, p2, p3))
        .index("by-country").by(Publication::country, Function.identity())
        .indexMulti("by-category").by(pub -> pub.category().ancestorIds())
        .build();
    catalog.bootstrap();
    final List<Publication> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .and("by-category").equalTo("cat-2")
        .execute();
    assertEquals(1, results.size());
    assertTrue(results.contains(p1));
  }

  @Test
  void whenCompound_givenAncestorCategory_shouldReturnAllDescendants() {
    final Category sports = new Category("cat-1", List.of("cat-1"));
    final Category football = new Category("cat-2", List.of("cat-1", "cat-2"));
    final Publication p1 = new Publication("pub-1", "AR", football);
    final Publication p2 = new Publication("pub-2", "AR", sports);
    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of(p1, p2))
        .index("by-country").by(Publication::country, Function.identity())
        .indexMulti("by-category").by(pub -> pub.category().ancestorIds())
        .build();
    catalog.bootstrap();
    final List<Publication> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .and("by-category").equalTo("cat-1")
        .execute();
    assertEquals(2, results.size());
    assertTrue(results.contains(p1));
    assertTrue(results.contains(p2));
  }

  @Test
  void whenBuilding_givenDuplicateMultiKeyName_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        Catalog.of(Publication.class)
            .named("publications")
            .data(List.of())
            .index("by-country").by(Publication::country, Function.identity())
            .indexMulti("by-country").by(pub -> pub.category().ancestorIds())
            .build());
  }
}
