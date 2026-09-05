package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests how {@link SnapshotBuildHook#supportsPatch()} decides whether a
 * patch derives the next snapshot surgically or rebuilds it.
 */
class CatalogHookPatchTest {

  record Event(String id, String venue) {}

  /** A hook that records every item it is handed, across all builds. */
  static class RecordingHook implements SnapshotBuildHook<Event> {

    private final List<String> seen = new ArrayList<>();
    private final boolean patchable;

    RecordingHook(final boolean thePatchable) {
      patchable = thePatchable;
    }

    @Override
    public Event process(final Event item) {
      seen.add(item.id());
      return item;
    }

    @Override
    public boolean supportsPatch() {
      return patchable;
    }

    List<String> seen() {
      return seen;
    }

    void clear() {
      seen.clear();
    }
  }

  private static final Event A = new Event("a", "Wembley");
  private static final Event B = new Event("b", "Camp Nou");
  private static final Event C = new Event("c", "Anfield");

  private static Catalog<Event> catalogWith(final SnapshotBuildHook<Event> hook) {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(new ArrayList<>(List.of(A, B, C)))
        .index("by-id").by(Event::id, id -> id)
        .index("by-venue").by(Event::venue, venue -> venue)
        .hook(hook)
        .build();
    catalog.bootstrap();
    return catalog;
  }

  @Test
  void whenPatching_givenHookThatOptedOut_shouldRunItForEveryItem() {
    final RecordingHook hook = new RecordingHook(false);
    final Catalog<Event> catalog = catalogWith(hook);
    assertFalse(catalog.appliesPatchesSurgically());
    hook.clear();

    catalog.replace("by-id", "a", new Event("a", "Anfield"));

    assertEquals(List.of("a", "b", "c"), hook.seen(),
        "A hook that accumulates across a build must see the whole "
            + "catalog, not just the patched item");
  }

  @Test
  void whenPatching_givenHookThatOptedIn_shouldRunItOnlyForThePatchedItem() {
    final RecordingHook hook = new RecordingHook(true);
    final Catalog<Event> catalog = catalogWith(hook);
    assertTrue(catalog.appliesPatchesSurgically());
    hook.clear();

    catalog.replace("by-id", "a", new Event("a", "Anfield"));

    assertEquals(List.of("a"), hook.seen(),
        "A hook that opted in is only handed the item that changed");
  }

  @Test
  void whenPatching_givenHookThatOptedOut_shouldStillProduceTheRightResult() {
    final Catalog<Event> degraded = catalogWith(new RecordingHook(false));
    final Catalog<Event> surgical = catalogWith(new RecordingHook(true));
    final Event moved = new Event("a", "Anfield");

    degraded.replace("by-id", "a", moved);
    surgical.replace("by-id", "a", moved);

    assertEquals(surgical.currentSnapshot().hash(),
        degraded.currentSnapshot().hash(),
        "Rebuilding and patching surgically must agree on the result");
    assertEquals(List.of(moved), degraded.search("by-id", "a"));
    assertEquals(List.of(), degraded.search("by-venue", "Wembley"));
  }

  @Test
  void whenPatching_givenHookThatOptedOut_shouldNotShareUntouchedBuckets() {
    final Catalog<Event> catalog = catalogWith(new RecordingHook(false));
    final Snapshot<Event> before = catalog.currentSnapshot();

    catalog.replace("by-id", "a", new Event("a", "Anfield"));

    assertNotSame(before.indicesMap().get("by-id").get("b"),
        catalog.currentSnapshot().indicesMap().get("by-id").get("b"),
        "A rebuild reconstructs every bucket; that is the cost being paid");
  }

  @Test
  void whenPatching_givenNoHooks_shouldStaySurgical() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(new ArrayList<>(List.of(A, B, C)))
        .index("by-id").by(Event::id, id -> id)
        .build();
    catalog.bootstrap();
    final Snapshot<Event> before = catalog.currentSnapshot();

    assertTrue(catalog.appliesPatchesSurgically());
    catalog.replace("by-id", "a", new Event("a", "Anfield"));

    assertSame(before.indicesMap().get("by-id").get("b"),
        catalog.currentSnapshot().indicesMap().get("by-id").get("b"));
  }

  @Test
  void whenPatching_givenOneOptedOutHookAmongMany_shouldRebuild() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(new ArrayList<>(List.of(A, B, C)))
        .index("by-id").by(Event::id, id -> id)
        .hook(new RecordingHook(true), 1)
        .hook(new RecordingHook(false), 2)
        .build();
    catalog.bootstrap();

    assertFalse(catalog.appliesPatchesSurgically(),
        "One hook that has not opted in decides for the whole catalog");
  }

  @Test
  void whenBatching_givenHookThatOptedOut_shouldRunItOncePerItemNotPerOp() {
    final RecordingHook hook = new RecordingHook(false);
    final Catalog<Event> catalog = catalogWith(hook);
    hook.clear();

    catalog.apply(List.of(
        new PatchOp.Replace<>("by-id", "a", new Event("a", "Anfield")),
        new PatchOp.Remove<>("by-id", "b")));

    assertEquals(List.of("a", "c"), hook.seen(),
        "A degraded batch rebuilds once at the end, not once per operation");
  }
}
