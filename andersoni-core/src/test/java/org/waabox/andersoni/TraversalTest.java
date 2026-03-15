package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TraversalTest {

  record Country(String code) {}
  record Event(Country country) {}
  record Publication(String categoryPath, List<Event> events, Event mainEvent) {}

  @Test
  void whenEvaluatingSimple_givenValidExtractor_shouldReturnSingleValue() {
    final Traversal<Publication> t = Traversal.simple("main-country",
        pub -> pub.mainEvent().country().code());
    final Publication pub = new Publication("deportes",
        List.of(), new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertEquals(Set.of("AR"), result);
  }

  @Test
  void whenEvaluatingSimple_givenNullResult_shouldReturnEmpty() {
    final Traversal<Publication> t = Traversal.simple("main-country",
        pub -> null);
    final Publication pub = new Publication("deportes",
        List.of(), new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenEvaluatingFanOut_givenMultipleEvents_shouldReturnDistinctValues() {
    final Traversal<Publication> t = Traversal.fanOut("country",
        Publication::events, event -> event.country().code());
    final Publication pub = new Publication("deportes",
        List.of(new Event(new Country("AR")), new Event(new Country("MX")),
            new Event(new Country("AR"))),
        new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertEquals(Set.of("AR", "MX"), result);
  }

  @Test
  void whenEvaluatingFanOut_givenNullCollection_shouldReturnEmpty() {
    final Traversal<Publication> t = Traversal.fanOut("country",
        pub -> null, event -> "X");
    final Publication pub = new Publication("deportes", null,
        new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenEvaluatingFanOut_givenEmptyCollection_shouldReturnEmpty() {
    final Traversal<Publication> t = Traversal.fanOut("country",
        Publication::events, event -> event.country().code());
    final Publication pub = new Publication("deportes", List.of(),
        new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenEvaluatingFanOut_givenNullElement_shouldSkipIt() {
    final Traversal<Publication> t = Traversal.fanOut("country",
        Publication::events, event -> event.country().code());
    final List<Event> events = new java.util.ArrayList<>();
    events.add(new Event(new Country("AR")));
    events.add(null);
    events.add(new Event(new Country("MX")));
    final Publication pub = new Publication("deportes", events,
        new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertEquals(Set.of("AR", "MX"), result);
  }

  @Test
  void whenEvaluatingFanOut_givenNullExtractedValue_shouldSkipIt() {
    final Traversal<Publication> t = Traversal.fanOut("country",
        Publication::events, event -> event.country() != null
            ? event.country().code() : null);
    final Publication pub = new Publication("deportes",
        List.of(new Event(new Country("AR")), new Event(null)),
        new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertEquals(Set.of("AR"), result);
  }

  @Test
  void whenEvaluatingPath_givenMultiSegmentPath_shouldReturnAllPrefixes() {
    final Traversal<Publication> t = Traversal.path("category", "/",
        Publication::categoryPath);
    final Publication pub = new Publication("deportes/futbol/liga",
        List.of(), new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertEquals(Set.of("deportes", "deportes/futbol",
        "deportes/futbol/liga"), result);
  }

  @Test
  void whenEvaluatingPath_givenSingleSegment_shouldReturnOneValue() {
    final Traversal<Publication> t = Traversal.path("category", "/",
        Publication::categoryPath);
    final Publication pub = new Publication("deportes",
        List.of(), new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertEquals(Set.of("deportes"), result);
  }

  @Test
  void whenEvaluatingPath_givenNullPath_shouldReturnEmpty() {
    final Traversal<Publication> t = Traversal.path("category", "/",
        Publication::categoryPath);
    final Publication pub = new Publication(null,
        List.of(), new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenEvaluatingPath_givenEmptyPath_shouldReturnEmpty() {
    final Traversal<Publication> t = Traversal.path("category", "/",
        Publication::categoryPath);
    final Publication pub = new Publication("",
        List.of(), new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenEvaluatingPath_givenLeadingTrailingSeparators_shouldFilterEmptySegments() {
    final Traversal<Publication> t = Traversal.path("category", "/",
        Publication::categoryPath);
    final Publication pub = new Publication("/deportes//futbol/",
        List.of(), new Event(new Country("AR")));
    final Set<?> result = t.evaluate(pub);
    assertEquals(Set.of("deportes", "deportes/futbol"), result);
  }

  @Test
  void whenGettingName_shouldReturnConfiguredName() {
    final Traversal<Publication> t = Traversal.simple("my-field",
        pub -> "value");
    assertEquals("my-field", t.name());
  }

  @Test
  void whenCreating_givenNullName_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> Traversal.simple(null, pub -> "value"));
  }

  @Test
  void whenCreating_givenEmptyName_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> Traversal.simple("", pub -> "value"));
  }

  @Test
  void whenCreating_givenNullExtractor_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> Traversal.simple("name", null));
  }

  @Test
  void whenCreatingPath_givenNullSeparator_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> Traversal.path("name", null, pub -> "path"));
  }

  @Test
  void whenCreatingPath_givenEmptySeparator_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> Traversal.path("name", "", pub -> "path"));
  }
}
