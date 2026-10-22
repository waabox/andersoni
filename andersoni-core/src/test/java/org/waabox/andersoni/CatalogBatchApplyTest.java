package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Catalog#apply(List)}.
 */
class CatalogBatchApplyTest {

  record Venue(String name) {}

  record Event(String id, Venue venue) {
    static Event of(final String id, final String venue) {
      return new Event(id, new Venue(venue));
    }
  }

  private static Catalog<Event> catalogOf(final List<Event> data) {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(data)
        .index("by-id").by(Event::id, id -> id)
        .index("by-venue").by(Event::venue, Venue::name)
        .build();
    catalog.bootstrap();
    return catalog;
  }

  private static final Event A = Event.of("a", "Wembley");
  private static final Event B = Event.of("b", "Camp Nou");

  @Test
  void whenApply_givenMixedOps_shouldApplyAllOfThemInOneSwap() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A, B)));
    final long versionBefore = catalog.currentSnapshot().version();
    final Event movedA = Event.of("a", "Anfield");
    final Event c = Event.of("c", "Old Trafford");

    catalog.apply(List.of(
        new PatchOp.Replace<>("by-id", "a", movedA),
        new PatchOp.Insert<>(c),
        new PatchOp.Remove<>("by-id", "b")));

    assertEquals(List.of(movedA), catalog.search("by-id", "a"));
    assertEquals(List.of(c), catalog.search("by-id", "c"));
    assertEquals(List.of(), catalog.search("by-id", "b"));
    assertEquals(List.of(), catalog.search("by-venue", "Wembley"));
    assertEquals(versionBefore + 1, catalog.currentSnapshot().version(),
        "A batch must bump the version once, not once per operation");
  }

  @Test
  void whenApply_givenOpsOnTheSameItem_shouldSeeEarlierOpsEffects() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A)));
    final Event inserted = Event.of("z", "Ibrox");
    final Event replaced = Event.of("z", "Hampden");

    catalog.apply(List.of(
        new PatchOp.Insert<>(inserted),
        new PatchOp.Replace<>("by-id", "z", replaced)));

    assertEquals(List.of(replaced), catalog.search("by-id", "z"),
        "A replace must see an insert made earlier in the same batch");
    assertEquals(2, catalog.currentSnapshot().data().size());
  }

  @Test
  void whenApply_shouldMatchAFullRebuildOfTheResultingItems() {
    final Catalog<Event> patched = catalogOf(new ArrayList<>(List.of(A, B)));
    final Event c = Event.of("c", "Old Trafford");

    patched.apply(List.of(
        new PatchOp.Insert<>(c),
        new PatchOp.Remove<>("by-id", "a")));

    final Catalog<Event> rebuilt = catalogOf(new ArrayList<>(List.of(B, c)));
    assertEquals(rebuilt.currentSnapshot().hash(),
        patched.currentSnapshot().hash());
    assertEquals(rebuilt.currentSnapshot().data(),
        patched.currentSnapshot().data());
  }

  @Test
  void whenApply_givenReplaceThatMatchesNothing_shouldAbandonTheWholeBatch() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A, B)));
    final Snapshot<Event> before = catalog.currentSnapshot();
    final Event c = Event.of("c", "Old Trafford");

    final PatchTargetNotFoundException thrown = assertThrows(
        PatchTargetNotFoundException.class,
        () -> catalog.apply(List.of(
            new PatchOp.Insert<>(c),
            new PatchOp.Replace<>("by-id", "ghost", Event.of("ghost", "X")))));

    assertTrue(thrown.getMessage().contains("ghost"),
        "The failure must name the key that could not be satisfied");
    assertSame(before, catalog.currentSnapshot(),
        "An abandoned batch must leave the live snapshot untouched, "
            + "including the insert that had already been computed");
    assertEquals(List.of(), catalog.search("by-id", "c"));
  }

  @Test
  void whenApply_givenAmbiguousLookup_shouldAbandonTheWholeBatch() {
    final Event sameVenue = Event.of("c", "Wembley");
    final Catalog<Event> catalog =
        catalogOf(new ArrayList<>(List.of(A, B, sameVenue)));
    final Snapshot<Event> before = catalog.currentSnapshot();

    assertThrows(AmbiguousPatchTargetException.class,
        () -> catalog.apply(List.of(
            new PatchOp.Insert<>(Event.of("d", "Ibrox")),
            new PatchOp.Remove<>("by-venue", "Wembley"))));

    assertSame(before, catalog.currentSnapshot());
  }

  @Test
  void whenApply_givenRemoveThatMatchesNothing_shouldSkipItAndApplyTheRest() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A, B)));
    final Event c = Event.of("c", "Old Trafford");

    catalog.apply(List.of(
        new PatchOp.Remove<>("by-id", "never-existed"),
        new PatchOp.Insert<>(c)));

    assertEquals(List.of(c), catalog.search("by-id", "c"),
        "A removal that matches nothing must not stop the batch");
    assertEquals(3, catalog.currentSnapshot().data().size());
  }

  @Test
  void whenApply_givenNothingChanges_shouldNotSwapTheSnapshot() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A, B)));
    final Snapshot<Event> before = catalog.currentSnapshot();

    catalog.apply(List.of());
    assertSame(before, catalog.currentSnapshot(),
        "An empty batch must not bump the version");

    catalog.apply(List.of(new PatchOp.Remove<>("by-id", "never-existed")));
    assertSame(before, catalog.currentSnapshot(),
        "A batch whose only operation matched nothing must not swap");
  }

  @Test
  void whenApply_givenUpsert_shouldReplaceOrInsertPerOperation() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A, B)));
    final Event movedA = Event.of("a", "Anfield");
    final Event newC = Event.of("c", "Old Trafford");

    catalog.apply(List.of(
        new PatchOp.Upsert<>("by-id", "a", movedA),
        new PatchOp.Upsert<>("by-id", "c", newC)));

    assertEquals(List.of(movedA), catalog.search("by-id", "a"));
    assertEquals(List.of(newC), catalog.search("by-id", "c"));
    assertEquals(3, catalog.currentSnapshot().data().size());
  }

  @Test
  void whenApply_givenNullOperation_shouldRejectBeforeTouchingAnything() {
    final Catalog<Event> catalog = catalogOf(new ArrayList<>(List.of(A, B)));
    final Snapshot<Event> before = catalog.currentSnapshot();
    final List<PatchOp<Event>> ops = new ArrayList<>();
    ops.add(new PatchOp.Insert<>(Event.of("c", "Old Trafford")));
    ops.add(null);

    assertThrows(NullPointerException.class, () -> catalog.apply(ops));

    assertSame(before, catalog.currentSnapshot());
  }
}
