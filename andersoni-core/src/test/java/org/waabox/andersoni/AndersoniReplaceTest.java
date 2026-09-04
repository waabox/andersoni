package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Andersoni#replace(String, String, Object, Object)}.
 */
class AndersoniReplaceTest {

  record Sport(String name) {}
  record Venue(String name) {}
  record Event(String id, Sport sport, Venue venue) {}

  private static Catalog<Event> eventsCatalog(final List<Event> data) {
    return Catalog.of(Event.class)
        .named("events")
        .data(data)
        .index("by-id").by(Event::id, id -> id)
        .index("by-venue").by(Event::venue, Venue::name)
        .build();
  }

  @Test
  void whenReplace_givenRegisteredCatalog_shouldDelegate() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(eventsCatalog(List.of(a)));
    andersoni.start();

    final Event updated = new Event("a", new Sport("F"), new Venue("V2"));
    final boolean replaced = andersoni.replace("events", "by-id", "a",
        updated);

    assertTrue(replaced);
    assertEquals(List.of(updated),
        andersoni.search("events", "by-id", "a", Event.class));
    assertEquals(List.of(updated),
        andersoni.search("events", "by-venue", "V2", Event.class));

    andersoni.stop();
  }

  @Test
  void whenReplace_givenMissingItem_shouldReturnFalse() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(eventsCatalog(List.of(a)));
    andersoni.start();

    final boolean replaced = andersoni.replace("events", "by-id", "zzz",
        new Event("zzz", new Sport("F"), new Venue("V1")));

    assertFalse(replaced);
    andersoni.stop();
  }

  @Test
  void whenReplace_givenUnknownCatalog_shouldThrowIllegalArgument() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.start();

    assertThrows(IllegalArgumentException.class,
        () -> andersoni.replace("nope", "by-id", "x",
            new Event("x", new Sport("F"), new Venue("V1"))));

    andersoni.stop();
  }

  @Test
  void whenReplace_givenAfterStop_shouldThrowIllegalState() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(eventsCatalog(List.of(a)));
    andersoni.start();
    andersoni.stop();

    assertThrows(IllegalStateException.class,
        () -> andersoni.replace("events", "by-id", "a", a));
  }

  @Test
  void whenReplace_givenNullArgs_shouldThrowNpe() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(eventsCatalog(List.of(a)));
    andersoni.start();

    assertThrows(NullPointerException.class,
        () -> andersoni.replace(null, "by-id", "a", a));
    assertThrows(NullPointerException.class,
        () -> andersoni.replace("events", null, "a", a));
    assertThrows(NullPointerException.class,
        () -> andersoni.replace("events", "by-id", null, a));
    assertThrows(NullPointerException.class,
        () -> andersoni.replace("events", "by-id", "a", null));

    andersoni.stop();
  }

  @Test
  void whenReplace_givenAmbiguousBucket_shouldPropagateException() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Event b = new Event("b", new Sport("F"), new Venue("V1"));
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(eventsCatalog(List.of(a, b)));
    andersoni.start();

    assertThrows(AmbiguousReplaceException.class,
        () -> andersoni.replace("events", "by-venue", "V1",
            new Event("c", new Sport("F"), new Venue("V1"))));

    andersoni.stop();
  }

  @Test
  void whenReplace_givenUnknownIndex_shouldPropagateException() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(eventsCatalog(List.of(a)));
    andersoni.start();

    assertThrows(IndexNotFoundException.class,
        () -> andersoni.replace("events", "nope", "a", a));

    andersoni.stop();
  }
}
