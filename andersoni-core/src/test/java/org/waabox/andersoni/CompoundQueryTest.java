package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class CompoundQueryTest {

  record Event(String country, String category, String organizer, LocalDate date) {}

  private Catalog<Event> buildCatalog(final List<Event> data) {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(data)
        .index("by-country").by(Event::country, Function.identity())
        .index("by-category").by(Event::category, Function.identity())
        .index("by-organizer").by(Event::organizer, Function.identity())
        .indexSorted("by-date").by(Event::date, Function.identity())
        .build();
    catalog.bootstrap();
    return catalog;
  }

  @Test
  void whenExecuting_givenSingleEqualTo_shouldReturnMatches() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Event e2 = new Event("BR", "SPORTS", "ORG-1", LocalDate.of(2026, 2, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1, e2));
    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .execute();
    assertEquals(1, results.size());
    assertTrue(results.contains(e1));
  }

  @Test
  void whenExecuting_givenTwoConditions_shouldIntersect() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Event e2 = new Event("AR", "MUSIC", "ORG-2", LocalDate.of(2026, 2, 1));
    final Event e3 = new Event("BR", "SPORTS", "ORG-1", LocalDate.of(2026, 3, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1, e2, e3));
    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .and("by-category").equalTo("SPORTS")
        .execute();
    assertEquals(1, results.size());
    assertTrue(results.contains(e1));
  }

  @Test
  void whenExecuting_givenThreeConditions_shouldIntersectAll() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Event e2 = new Event("AR", "SPORTS", "ORG-2", LocalDate.of(2026, 2, 1));
    final Event e3 = new Event("AR", "MUSIC", "ORG-1", LocalDate.of(2026, 3, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1, e2, e3));
    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .and("by-category").equalTo("SPORTS")
        .and("by-organizer").equalTo("ORG-1")
        .execute();
    assertEquals(1, results.size());
    assertTrue(results.contains(e1));
  }

  @Test
  void whenExecuting_givenNoMatch_shouldReturnEmpty() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1));
    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("CL")
        .execute();
    assertTrue(results.isEmpty());
  }

  @Test
  void whenExecuting_givenFirstConditionEmpty_shouldShortCircuit() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1));
    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("CL")
        .and("by-category").equalTo("SPORTS")
        .execute();
    assertTrue(results.isEmpty());
  }

  @Test
  void whenExecuting_givenIntersectionEmpty_shouldShortCircuit() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Event e2 = new Event("BR", "MUSIC", "ORG-2", LocalDate.of(2026, 2, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1, e2));
    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .and("by-category").equalTo("MUSIC")
        .execute();
    assertTrue(results.isEmpty());
  }

  @Test
  void whenExecuting_givenEqualToAndBetween_shouldIntersect() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 15));
    final Event e2 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 6, 15));
    final Event e3 = new Event("BR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 20));
    final Catalog<Event> catalog = buildCatalog(List.of(e1, e2, e3));
    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .and("by-date").between(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
        .execute();
    assertEquals(1, results.size());
    assertTrue(results.contains(e1));
  }

  @Test
  void whenExecuting_givenNoConditions_shouldThrowIllegalState() {
    final Catalog<Event> catalog = buildCatalog(List.of());
    final CompoundQuery<Event> query = catalog.compound();
    assertThrows(IllegalStateException.class, query::execute);
  }

  @Test
  void whenCallingWhereTwice_shouldThrowIllegalState() {
    final Catalog<Event> catalog = buildCatalog(List.of());
    final CompoundQuery<Event> query = catalog.compound();
    query.where("by-country").equalTo("AR");
    assertThrows(IllegalStateException.class, () -> query.where("by-category"));
  }

  @Test
  void whenExecuting_givenIndexNotFound_shouldThrowIndexNotFound() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1));
    assertThrows(IndexNotFoundException.class, () ->
        catalog.compound().where("non-existent").equalTo("AR").execute());
  }

  @Test
  void whenExecuting_shouldReturnUnmodifiableList() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1));
    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .execute();
    assertThrows(UnsupportedOperationException.class, () -> results.add(e1));
  }

  @Test
  void whenExecuting_givenMultipleMatches_shouldReturnAll() {
    final Event e1 = new Event("AR", "SPORTS", "ORG-1", LocalDate.of(2026, 1, 1));
    final Event e2 = new Event("AR", "SPORTS", "ORG-2", LocalDate.of(2026, 2, 1));
    final Event e3 = new Event("AR", "MUSIC", "ORG-1", LocalDate.of(2026, 3, 1));
    final Catalog<Event> catalog = buildCatalog(List.of(e1, e2, e3));
    final List<Event> results = catalog.compound()
        .where("by-country").equalTo("AR")
        .and("by-category").equalTo("SPORTS")
        .execute();
    assertEquals(2, results.size());
    assertTrue(results.contains(e1));
    assertTrue(results.contains(e2));
  }
}
