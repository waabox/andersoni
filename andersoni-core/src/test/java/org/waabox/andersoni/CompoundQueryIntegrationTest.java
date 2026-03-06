package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test for compound queries with multi-key
 * indices, simulating the publication-service use case.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class CompoundQueryIntegrationTest {

  record Category(String id, String name, List<String> ancestorIds) {}

  record Publication(String id, String country, Category category,
      String organizer, LocalDate publishDate) {}

  private Andersoni andersoni;

  @BeforeEach
  void setUp() {
    final Category entertainment = new Category("cat-1", "Entertainment",
        List.of("cat-1"));
    final Category sports = new Category("cat-2", "Sports",
        List.of("cat-1", "cat-2"));
    final Category football = new Category("cat-3", "Football",
        List.of("cat-1", "cat-2", "cat-3"));
    final Category baseball = new Category("cat-4", "Baseball",
        List.of("cat-1", "cat-2", "cat-4"));
    final Category music = new Category("cat-5", "Music",
        List.of("cat-1", "cat-5"));

    final Publication p1 = new Publication("pub-1", "AR", football,
        "ORG-1", LocalDate.of(2026, 1, 15));
    final Publication p2 = new Publication("pub-2", "AR", football,
        "ORG-2", LocalDate.of(2026, 2, 20));
    final Publication p3 = new Publication("pub-3", "AR", baseball,
        "ORG-1", LocalDate.of(2026, 3, 10));
    final Publication p4 = new Publication("pub-4", "BR", football,
        "ORG-1", LocalDate.of(2026, 1, 25));
    final Publication p5 = new Publication("pub-5", "AR", music,
        "ORG-3", LocalDate.of(2026, 4, 1));

    final Catalog<Publication> catalog = Catalog.of(Publication.class)
        .named("publications")
        .data(List.of(p1, p2, p3, p4, p5))
        .index("by-country").by(
            Publication::country, Function.identity())
        .indexMulti("by-category").by(
            pub -> pub.category().ancestorIds())
        .index("by-organizer").by(
            Publication::organizer, Function.identity())
        .indexSorted("by-publish-date").by(
            Publication::publishDate, Function.identity())
        .build();

    andersoni = Andersoni.builder().nodeId("test").build();
    andersoni.register(catalog);
    andersoni.start();
  }

  @AfterEach
  void tearDown() {
    andersoni.stop();
  }

  @Test
  void whenSearching_givenCountryAndSportsAncestor_shouldReturnAll() {
    // AR + Sports (cat-2) = p1 (football), p2 (football), p3 (baseball)
    final List<Publication> results =
        andersoni.compound("publications", Publication.class)
            .where("by-country").equalTo("AR")
            .and("by-category").equalTo("cat-2")
            .execute();

    assertEquals(3, results.size());
  }

  @Test
  void whenSearching_givenCountryAndFootball_shouldReturnLeaf() {
    // AR + Football (cat-3) = p1, p2
    final List<Publication> results =
        andersoni.compound("publications", Publication.class)
            .where("by-country").equalTo("AR")
            .and("by-category").equalTo("cat-3")
            .execute();

    assertEquals(2, results.size());
  }

  @Test
  void whenSearching_givenCountryAndCategoryAndOrganizer_shouldIntersect() {
    // AR + Sports (cat-2) + ORG-1 = p1 (football), p3 (baseball)
    final List<Publication> results =
        andersoni.compound("publications", Publication.class)
            .where("by-country").equalTo("AR")
            .and("by-category").equalTo("cat-2")
            .and("by-organizer").equalTo("ORG-1")
            .execute();

    assertEquals(2, results.size());
  }

  @Test
  void whenSearching_givenCountryAndDateRange_shouldFilter() {
    // AR + Jan-Feb 2026
    final List<Publication> results =
        andersoni.compound("publications", Publication.class)
            .where("by-country").equalTo("AR")
            .and("by-publish-date").between(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 28))
            .execute();

    assertEquals(2, results.size());
  }

  @Test
  void whenSearching_givenAllFourCriteria_shouldNarrowDown() {
    // AR + Football (cat-3) + ORG-1 + Jan 2026
    final List<Publication> results =
        andersoni.compound("publications", Publication.class)
            .where("by-country").equalTo("AR")
            .and("by-category").equalTo("cat-3")
            .and("by-organizer").equalTo("ORG-1")
            .and("by-publish-date").between(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31))
            .execute();

    assertEquals(1, results.size());
    assertEquals("pub-1", results.get(0).id());
  }

  @Test
  void whenSearching_givenEntertainmentRoot_shouldReturnAll() {
    // AR + Entertainment (cat-1) = all AR publications
    final List<Publication> results =
        andersoni.compound("publications", Publication.class)
            .where("by-country").equalTo("AR")
            .and("by-category").equalTo("cat-1")
            .execute();

    assertEquals(4, results.size());
  }

  @Test
  void whenSearching_givenNoMatch_shouldReturnEmpty() {
    // CL + anything = empty
    final List<Publication> results =
        andersoni.compound("publications", Publication.class)
            .where("by-country").equalTo("CL")
            .and("by-category").equalTo("cat-1")
            .execute();

    assertTrue(results.isEmpty());
  }
}
