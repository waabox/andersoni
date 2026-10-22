package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.waabox.andersoni.leader.LeaderChangeListener;
import org.waabox.andersoni.leader.LeaderElectionStrategy;
import org.waabox.andersoni.snapshot.SerializedSnapshot;
import org.waabox.andersoni.snapshot.SnapshotSerializer;
import org.waabox.andersoni.sync.PatchEvent;
import org.waabox.andersoni.sync.PatchListener;
import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.RefreshListener;
import org.waabox.andersoni.sync.SyncStrategy;

/**
 * How snapshot patches and snapshot reconciliation share the applied store
 * hash: a delivered patch must not read as drift, a missed one must be
 * repaired, and a change the store never saw must be uploaded.
 */
class AndersoniPatchReconciliationTest {

  record Event(String id, String venue) {}

  /** Deterministic and byte-stable: one {@code id|venue} line per item. */
  static final class EventSerializer implements SnapshotSerializer<Event> {
    @Override
    public byte[] serialize(final List<Event> items) {
      final StringBuilder sb = new StringBuilder();
      for (final Event e : items) {
        sb.append(e.id()).append('|').append(e.venue()).append('\n');
      }
      return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public List<Event> deserialize(final byte[] data) {
      final List<Event> result = new ArrayList<>();
      for (final String line
          : new String(data, StandardCharsets.UTF_8).split("\n")) {
        if (line.isBlank()) {
          continue;
        }
        final int sep = line.indexOf('|');
        result.add(new Event(line.substring(0, sep), line.substring(sep + 1)));
      }
      return result;
    }
  }

  /** Records what is published and hands the patch listener to the test. */
  static final class PatchAwareSyncStrategy implements SyncStrategy {
    final List<RefreshEvent> refreshes = new CopyOnWriteArrayList<>();
    final List<PatchEvent> patches = new CopyOnWriteArrayList<>();
    volatile PatchListener patchListener;

    @Override
    public void publish(final RefreshEvent event) {
      refreshes.add(event);
    }

    @Override
    public void subscribe(final RefreshListener listener) {
    }

    @Override
    public void publishPatch(final PatchEvent event) {
      patches.add(event);
    }

    @Override
    public void subscribePatch(final PatchListener listener) {
      patchListener = listener;
    }

    @Override
    public boolean supportsPatches() {
      return true;
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }
  }

  /** Leader election pinned to one role. */
  static final class FixedRoleElection implements LeaderElectionStrategy {
    private final boolean leader;

    FixedRoleElection(final boolean leader) {
      this.leader = leader;
    }

    @Override
    public void start() {
    }

    @Override
    public boolean isLeader() {
      return leader;
    }

    @Override
    public void onLeaderChange(final LeaderChangeListener listener) {
    }

    @Override
    public void stop() {
    }
  }

  private static final Event A = new Event("a", "V1");
  private static final Event A_PATCHED = new Event("a", "V2");
  private static final Event B = new Event("b", "V1");

  private static Catalog<Event> eventsCatalog(final List<Event> loaderData) {
    return Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> loaderData)
        .serializer(new EventSerializer())
        .index("by-id").by(Event::id, id -> id)
        .build();
  }

  /** What the leader's save writes for these items. */
  private static SerializedSnapshot storeSnapshot(final List<Event> events) {
    final byte[] bytes = new EventSerializer().serialize(events);
    return new SerializedSnapshot("events", SnapshotStoreBridge.sha256Hex(bytes),
        1L, Instant.parse("2026-09-21T10:00:00Z"), bytes);
  }

  private static Andersoni startNode(final boolean leader,
      final InMemorySnapshotStore store, final PatchAwareSyncStrategy sync,
      final Catalog<Event> catalog) {
    final Andersoni andersoni = Andersoni.builder()
        .nodeId(leader ? "leader-1" : "follower-1")
        .leaderElection(new FixedRoleElection(leader))
        .snapshotStore(store)
        .syncStrategy(sync)
        .reconciliation(ReconciliationPolicy.of(Duration.ofHours(1)))
        .build();
    andersoni.register(catalog);
    andersoni.start();
    return andersoni;
  }

  private static PatchEvent replacePatch(final String fromHash,
      final String toHash) {
    final EventSerializer serializer = new EventSerializer();
    return PatchEvent.replace("events", "leader-1", 1L, 2L, fromHash, toHash,
        "by-id", serializer.serialize(List.of(A)),
        serializer.serialize(List.of(A_PATCHED)), Instant.now());
  }

  private static String storeHash(final InMemorySnapshotStore store) {
    return store.get("events").orElseThrow().hash();
  }

  private static SyncState syncStateOf(final Andersoni andersoni) {
    return andersoni.status().catalogs().stream()
        .filter(c -> c.catalogName().equals("events"))
        .findFirst()
        .orElseThrow()
        .syncState();
  }

  private static void awaitUntil(final BooleanSupplier condition,
      final Duration timeout) throws InterruptedException {
    final long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Condition not met within " + timeout);
      }
      Thread.sleep(20);
    }
  }

  @Test
  void whenReplaceAndSync_givenSnapshotStore_shouldLeaveLeaderInSyncWithoutRepair()
      throws InterruptedException {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final PatchAwareSyncStrategy sync = new PatchAwareSyncStrategy();
    final Catalog<Event> catalog = eventsCatalog(List.of(A));
    final Andersoni andersoni = startNode(true, store, sync, catalog);
    try {
      assertEquals(PatchOutcome.APPLIED,
          andersoni.replaceAndSync("events", "by-id", "a", A_PATCHED));

      // The store now holds the snapshot the event's toHash names: both are
      // the same digest over the same serializer bytes.
      assertEquals(catalog.currentSnapshot().hash(), storeHash(store));
      assertEquals(sync.patches.get(0).toHash(), storeHash(store));

      final int savesBefore = store.saveCalls();
      andersoni.reconcileNow();

      assertEquals(SyncState.IN_SYNC, syncStateOf(andersoni));
      assertEquals(savesBefore, store.saveCalls(),
          "An in-sync leader must not re-save");
      assertTrue(sync.refreshes.isEmpty(),
          "An in-sync leader must not re-publish");
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenReplaceAndSync_givenSaveFails_shouldThrowAndLetReconciliationRepair()
      throws InterruptedException {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final PatchAwareSyncStrategy sync = new PatchAwareSyncStrategy();
    final Catalog<Event> catalog = eventsCatalog(List.of(A));
    final Andersoni andersoni = startNode(true, store, sync, catalog);
    try {
      store.failNextSave = true;

      assertThrows(IllegalStateException.class,
          () -> andersoni.replaceAndSync("events", "by-id", "a", A_PATCHED));

      assertEquals(List.of(A_PATCHED), catalog.search("by-id", "a"),
          "The local change stands");
      assertTrue(sync.patches.isEmpty(),
          "A change the store has not seen is not announced");
      assertNotEquals(catalog.currentSnapshot().hash(), storeHash(store));

      andersoni.reconcileNow();

      awaitUntil(() -> sync.refreshes.size() == 1, Duration.ofSeconds(5));
      assertEquals(catalog.currentSnapshot().hash(), storeHash(store));
      assertEquals(catalog.currentSnapshot().hash(),
          sync.refreshes.get(0).hash());
      awaitUntil(() -> syncStateOf(andersoni) == SyncState.IN_SYNC,
          Duration.ofSeconds(5));
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenPatchApplied_givenFollowerWithStore_shouldStayInSyncWithoutReloading()
      throws InterruptedException {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    store.put("events", storeSnapshot(List.of(A)));
    final PatchAwareSyncStrategy sync = new PatchAwareSyncStrategy();
    final Catalog<Event> catalog = eventsCatalog(List.of());
    final Andersoni andersoni = startNode(false, store, sync, catalog);
    try {
      assertEquals(1, store.loadCalls(), "A follower bootstraps from the store");
      final SerializedSnapshot patched = storeSnapshot(List.of(A_PATCHED));

      sync.patchListener.onPatch(
          replacePatch(catalog.currentSnapshot().hash(), patched.hash()));
      awaitUntil(() -> catalog.search("by-id", "a").equals(List.of(A_PATCHED)),
          Duration.ofSeconds(5));
      assertEquals(patched.hash(), catalog.currentSnapshot().hash(),
          "A surgical apply lands on the hash the leader saved");

      // What the leader saved just before publishing the patch.
      store.put("events", patched);
      andersoni.reconcileNow();

      assertEquals(SyncState.IN_SYNC, syncStateOf(andersoni));
      assertEquals(1, store.loadCalls(),
          "A delivered patch must not trigger a reload");
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenPatchMissed_givenFollowerWithStore_shouldReloadFromStoreOnReconciliation()
      throws InterruptedException {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    store.put("events", storeSnapshot(List.of(A)));
    final PatchAwareSyncStrategy sync = new PatchAwareSyncStrategy();
    final Catalog<Event> catalog = eventsCatalog(List.of());
    final Andersoni andersoni = startNode(false, store, sync, catalog);
    try {
      // The leader patched and saved, but this node never saw the event.
      store.put("events", storeSnapshot(List.of(A_PATCHED)));
      assertEquals(List.of(A), catalog.search("by-id", "a"));

      andersoni.reconcileNow();

      awaitUntil(() -> catalog.search("by-id", "a").equals(List.of(A_PATCHED)),
          Duration.ofSeconds(5));
      awaitUntil(() -> syncStateOf(andersoni) == SyncState.IN_SYNC,
          Duration.ofSeconds(5));
      assertEquals(2, store.loadCalls());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenReplacingLocally_givenLeaderWithStore_shouldUploadAndBroadcastOnReconciliation()
      throws InterruptedException {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final PatchAwareSyncStrategy sync = new PatchAwareSyncStrategy();
    final Catalog<Event> catalog = eventsCatalog(List.of(A));
    final Andersoni andersoni = startNode(true, store, sync, catalog);
    try {
      assertTrue(andersoni.replace("events", "by-id", "a", A_PATCHED));
      assertNotEquals(catalog.currentSnapshot().hash(), storeHash(store),
          "A local patch does not touch the store by itself");

      andersoni.reconcileNow();

      awaitUntil(() -> sync.refreshes.size() == 1, Duration.ofSeconds(5));
      assertEquals(catalog.currentSnapshot().hash(), storeHash(store));
      awaitUntil(() -> syncStateOf(andersoni) == SyncState.IN_SYNC,
          Duration.ofSeconds(5));
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenInsertingLocally_givenLeaderWithStore_shouldUploadAndBroadcastOnReconciliation()
      throws InterruptedException {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final PatchAwareSyncStrategy sync = new PatchAwareSyncStrategy();
    final Catalog<Event> catalog = eventsCatalog(List.of(A));
    final Andersoni andersoni = startNode(true, store, sync, catalog);
    try {
      andersoni.insert("events", B);
      assertNotEquals(catalog.currentSnapshot().hash(), storeHash(store));

      andersoni.reconcileNow();

      awaitUntil(() -> sync.refreshes.size() == 1, Duration.ofSeconds(5));
      assertEquals(catalog.currentSnapshot().hash(), storeHash(store));
      assertEquals(2, new EventSerializer()
          .deserialize(store.get("events").orElseThrow().data()).size());
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenRemovingLocally_givenLeaderWithStore_shouldUploadAndBroadcastOnReconciliation()
      throws InterruptedException {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final PatchAwareSyncStrategy sync = new PatchAwareSyncStrategy();
    final Catalog<Event> catalog = eventsCatalog(List.of(A, B));
    final Andersoni andersoni = startNode(true, store, sync, catalog);
    try {
      assertTrue(andersoni.remove("events", "by-id", "b"));
      assertNotEquals(catalog.currentSnapshot().hash(), storeHash(store));

      andersoni.reconcileNow();

      awaitUntil(() -> sync.refreshes.size() == 1, Duration.ofSeconds(5));
      assertEquals(catalog.currentSnapshot().hash(), storeHash(store));
      assertEquals(List.of(A), new EventSerializer()
          .deserialize(store.get("events").orElseThrow().data()));
    } finally {
      andersoni.stop();
    }
  }

  @Test
  void whenReplacingLocally_givenFollowerWithStore_shouldBeOverwrittenByStoreOnReconciliation()
      throws InterruptedException {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    store.put("events", storeSnapshot(List.of(A)));
    final PatchAwareSyncStrategy sync = new PatchAwareSyncStrategy();
    final Catalog<Event> catalog = eventsCatalog(List.of());
    final Andersoni andersoni = startNode(false, store, sync, catalog);
    try {
      assertTrue(andersoni.replace("events", "by-id", "a", A_PATCHED));
      assertEquals(List.of(A_PATCHED), catalog.search("by-id", "a"));

      andersoni.reconcileNow();

      awaitUntil(() -> catalog.search("by-id", "a").equals(List.of(A)),
          Duration.ofSeconds(5));
      awaitUntil(() -> syncStateOf(andersoni) == SyncState.IN_SYNC,
          Duration.ofSeconds(5));
      assertEquals(2, store.loadCalls());
    } finally {
      andersoni.stop();
    }
  }
}
