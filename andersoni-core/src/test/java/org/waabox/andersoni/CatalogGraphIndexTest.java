package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CatalogGraphIndexTest {

  record Country(String code) {}
  record Event(Country country) {}
  record Publication(String name, String categoryPath, List<Event> events) {}

  @Test
  void whenBootstrapping_givenGraphIndex_shouldBuildAndSearch() {
    final Publication pub1 = new Publication("p1", "deportes/futbol",
        List.of(new Event(new Country("AR"))));
    final Publication pub2 = new Publication("p2", "deportes/basket",
        List.of(new Event(new Country("AR"))));
    final Publication pub3 = new Publication("p3", "musica",
        List.of(new Event(new Country("MX"))));

    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of(pub1, pub2, pub3))
        .indexGraph("by-country-cat")
            .traverseMany("country", Publication::events,
                event -> event.country().code())
            .traversePath("category", "/", Publication::categoryPath)
            .hotpath("country", "category")
            .done()
        .build();

    catalog.bootstrap();

    final List<Publication> arPubs = catalog.graphQuery()
        .where("country").eq("AR")
        .execute();
    assertEquals(2, arPubs.size());
    assertTrue(arPubs.contains(pub1));
    assertTrue(arPubs.contains(pub2));

    final List<Publication> arDeportes = catalog.graphQuery()
        .where("country").eq("AR")
        .and("category").eq("deportes")
        .execute();
    assertEquals(2, arDeportes.size());

    final List<Publication> arFutbol = catalog.graphQuery()
        .where("country").eq("AR")
        .and("category").eq("deportes/futbol")
        .execute();
    assertEquals(1, arFutbol.size());
    assertTrue(arFutbol.contains(pub1));

    final List<Publication> mxPubs = catalog.graphQuery()
        .where("country").eq("MX")
        .execute();
    assertEquals(1, mxPubs.size());
    assertTrue(mxPubs.contains(pub3));
  }

  @Test
  void whenBootstrapping_givenGraphIndexWithRegularIndex_shouldBothWork() {
    final Publication pub1 = new Publication("p1", "deportes",
        List.of(new Event(new Country("AR"))));

    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of(pub1))
        .index("by-name").by(Publication::name,
            java.util.function.Function.identity())
        .indexGraph("by-country-cat")
            .traverseMany("country", Publication::events,
                event -> event.country().code())
            .traversePath("category", "/", Publication::categoryPath)
            .hotpath("country", "category")
            .done()
        .build();

    catalog.bootstrap();

    assertEquals(List.of(pub1), catalog.search("by-name", "p1"));

    final List<Publication> result = catalog.graphQuery()
        .where("country").eq("AR")
        .execute();
    assertEquals(List.of(pub1), result);
  }

  @Test
  void whenBuilding_givenOnlyGraphIndex_shouldSucceed() {
    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of())
        .indexGraph("by-country")
            .traverse("country",
                pub -> pub.events().isEmpty() ? null
                    : pub.events().getFirst().country().code())
            .hotpath("country")
            .done()
        .build();

    catalog.bootstrap();
    assertTrue(catalog.graphQuery().where("country").eq("AR")
        .execute().isEmpty());
  }

  @Test
  void whenRefreshing_givenNewData_shouldUpdateGraphIndex() {
    final Publication pub1 = new Publication("p1", "deportes",
        List.of(new Event(new Country("AR"))));

    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of(pub1))
        .indexGraph("by-country-cat")
            .traverseMany("country", Publication::events,
                event -> event.country().code())
            .traversePath("category", "/", Publication::categoryPath)
            .hotpath("country", "category")
            .done()
        .build();

    catalog.bootstrap();
    assertEquals(1, catalog.graphQuery()
        .where("country").eq("AR").execute().size());

    final Publication pub2 = new Publication("p2", "deportes",
        List.of(new Event(new Country("AR"))));
    catalog.refresh(List.of(pub1, pub2));

    assertEquals(2, catalog.graphQuery()
        .where("country").eq("AR").execute().size());
  }

  @Test
  void whenBuilding_givenDuplicateGraphIndexName_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        Catalog.of(Publication.class)
            .named("publications")
            .data(List.of())
            .indexGraph("same-name")
                .traverse("country", pub -> "AR")
                .hotpath("country")
                .done()
            .indexGraph("same-name")
                .traverse("x", pub -> "X")
                .hotpath("x")
                .done()
            .build());
  }

  @Test
  void whenBuilding_givenGraphIndexNameCollidesWithRegularIndex_shouldThrow() {
    assertThrows(IllegalArgumentException.class, () ->
        Catalog.of(Publication.class)
            .named("publications")
            .data(List.of())
            .index("by-country").by(Publication::name,
                java.util.function.Function.identity())
            .indexGraph("by-country")
                .traverse("country", pub -> "AR")
                .hotpath("country")
                .done()
            .build());
  }
}
