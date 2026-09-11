package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.waabox.andersoni.leader.LeaderChangeListener;
import org.waabox.andersoni.leader.LeaderElectionStrategy;
import org.waabox.andersoni.metrics.AndersoniMetrics;
import org.waabox.andersoni.snapshot.SerializedSnapshot;
import org.waabox.andersoni.snapshot.SnapshotSerializer;

/**
 * Tests for {@link SnapshotReconciler}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SnapshotReconcilerTest {

  /** Runs repairs on the calling thread so tests are deterministic. */
  private static final BiConsumer<String, Runnable> INLINE = (name, task) -> task.run();

  /** Deserializes in reverse order: the in-memory hash never equals the
   *  stored one, which must not cause a reload loop. */
  static final class ReversingSerializer extends LinesSerializer {

    @Override
    public List<String> deserialize(final byte[] data) {
      final List<String> items = new ArrayList<>(super.deserialize(data));
      Collections.reverse(items);
      return items;
    }
  }

  /** Leader election whose role tests flip at will. */
  static final class ToggleLeaderElection implements LeaderElectionStrategy {

    private volatile boolean leader;
    private final List<LeaderChangeListener> listeners = new CopyOnWriteArrayList<>();

    ToggleLeaderElection(final boolean initiallyLeader) {
      leader = initiallyLeader;
    }

    void become(final boolean isLeader) {
      leader = isLeader;
      for (final LeaderChangeListener listener : listeners) {
        listener.onLeaderChange(isLeader);
      }
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
      listeners.add(listener);
    }

    @Override
    public void stop() {
    }
  }

  /** Collects drift metric calls. */
  static final class RecordingMetrics implements AndersoniMetrics {

    final List<String> detected = new CopyOnWriteArrayList<>();
    final List<String> repaired = new CopyOnWriteArrayList<>();
    final List<String> failed = new CopyOnWriteArrayList<>();

    @Override
    public void snapshotLoaded(final String catalogName, final String source) {
    }

    @Override
    public void refreshFailed(final String catalogName, final Throwable cause) {
    }

    @Override
    public void indexSizeReported(final String catalogName, final String indexName,
        final long estimatedSizeBytes) {
    }

    @Override
    public void driftDetected(final String catalogName) {
      detected.add(catalogName);
    }

    @Override
    public void driftRepaired(final String catalogName) {
      repaired.add(catalogName);
    }

    @Override
    public void reconcileFailed(final String catalogName, final Throwable cause) {
      failed.add(catalogName);
    }
  }

  private static Catalog<String> catalog(final String name,
      final SnapshotSerializer<String> serializer, final List<String> data) {
    return Catalog.of(String.class)
        .named(name)
        .data(data)
        .serializer(serializer)
        .index("by-self").by(s -> s, Function.identity())
        .build();
  }

  private static SerializedSnapshot snapshotOf(final String name,
      final List<String> items, final long version) {
    final byte[] bytes = new LinesSerializer().serialize(items);
    return new SerializedSnapshot(name, SnapshotStoreBridge.sha256Hex(bytes),
        version, Instant.parse("2026-09-11T10:00:00Z"), bytes);
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

  /** Builds a reconciler with inline dispatch and repairs wired to the bridge. */
  private static SnapshotReconciler reconciler(final Map<String, Catalog<?>> catalogs,
      final SnapshotStoreBridge bridge, final LeaderElectionStrategy election,
      final RecordingMetrics metrics, final Set<String> failed,
      final List<String> followerRepairs, final List<String> leaderRepairs) {
    final Consumer<Catalog<?>> followerRepair = c -> {
      followerRepairs.add(c.name());
      if (!bridge.load(c)) {
        throw new IllegalStateException("nothing to load");
      }
    };
    final Consumer<Catalog<?>> leaderRepair = c -> {
      leaderRepairs.add(c.name());
      bridge.save(c);
    };
    return new SnapshotReconciler(catalogs, bridge, election, INLINE, metrics,
        ReconciliationPolicy.of(Duration.ofMillis(50)), failed, followerRepair,
        leaderRepair);
  }

  @Test
  void whenRunningPass_givenFollowerBehindStore_shouldRepairAndReportInSync() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of());
    store.put("cities", snapshotOf("cities", List.of("Madrid"), 1L));
    bridge.load(cities);
    store.put("cities", snapshotOf("cities", List.of("Madrid", "Tokyo"), 2L));
    final RecordingMetrics metrics = new RecordingMetrics();
    final List<String> followerRepairs = new ArrayList<>();
    final List<String> leaderRepairs = new ArrayList<>();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(false), metrics, ConcurrentHashMap.newKeySet(),
        followerRepairs, leaderRepairs);

    reconciler.runPass();

    assertEquals(List.of("cities"), followerRepairs);
    assertTrue(leaderRepairs.isEmpty());
    assertEquals(2, cities.currentSnapshot().data().size());
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("cities"));
    assertTrue(reconciler.lastReconciledAt("cities").isPresent());
    assertEquals(List.of("cities"), metrics.detected);
    assertEquals(List.of("cities"), metrics.repaired);
  }

  @Test
  void whenRunningPass_givenFollowerAtStoreHash_shouldNotRepair() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of());
    store.put("cities", snapshotOf("cities", List.of("Madrid"), 1L));
    bridge.load(cities);
    final RecordingMetrics metrics = new RecordingMetrics();
    final List<String> followerRepairs = new ArrayList<>();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(false), metrics, ConcurrentHashMap.newKeySet(),
        followerRepairs, new ArrayList<>());

    reconciler.runPass();

    assertTrue(followerRepairs.isEmpty());
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("cities"));
    assertTrue(metrics.detected.isEmpty());
  }

  @Test
  void whenRunningPass_givenFollowerAndEmptyStore_shouldDoNothing() {
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(new InMemorySnapshotStore());
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of("x"));
    cities.bootstrap();
    final List<String> followerRepairs = new ArrayList<>();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(false), new RecordingMetrics(),
        ConcurrentHashMap.newKeySet(), followerRepairs, new ArrayList<>());

    reconciler.runPass();

    assertTrue(followerRepairs.isEmpty());
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("cities"));
  }

  @Test
  void whenRunningPass_givenLeaderWithoutAppliedHash_shouldSaveAndReportInSync() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of("Madrid"));
    cities.bootstrap();
    final RecordingMetrics metrics = new RecordingMetrics();
    final List<String> leaderRepairs = new ArrayList<>();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(true), metrics, ConcurrentHashMap.newKeySet(),
        new ArrayList<>(), leaderRepairs);

    reconciler.runPass();

    assertEquals(List.of("cities"), leaderRepairs);
    assertTrue(store.get("cities").isPresent());
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("cities"));
    assertEquals(List.of("cities"), metrics.repaired);
  }

  @Test
  void whenRunningPass_givenLeaderAtStoreHash_shouldNotSave() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of());
    store.put("cities", snapshotOf("cities", List.of("Madrid"), 1L));
    bridge.load(cities);
    final List<String> leaderRepairs = new ArrayList<>();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(true), new RecordingMetrics(),
        ConcurrentHashMap.newKeySet(), new ArrayList<>(), leaderRepairs);

    reconciler.runPass();

    assertTrue(leaderRepairs.isEmpty());
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("cities"));
  }

  @Test
  void whenRunningPass_givenLeaderWithFailedCatalog_shouldStayDriftedWithoutRepair() {
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(new InMemorySnapshotStore());
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of());
    final Set<String> failed = ConcurrentHashMap.newKeySet();
    failed.add("cities");
    final List<String> leaderRepairs = new ArrayList<>();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(true), new RecordingMetrics(), failed,
        new ArrayList<>(), leaderRepairs);

    reconciler.runPass();

    assertTrue(leaderRepairs.isEmpty());
    assertEquals(SyncState.DRIFTED, reconciler.syncState("cities"));
  }

  @Test
  void whenRunningPass_givenDescribeThrows_shouldReportFailureAndContinueWithOtherCatalogs() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> a = catalog("a", new LinesSerializer(), List.of("1"));
    final Catalog<String> b = catalog("b", new LinesSerializer(), List.of("2"));
    a.bootstrap();
    b.bootstrap();
    final RecordingMetrics metrics = new RecordingMetrics();
    final List<String> leaderRepairs = new ArrayList<>();
    final Map<String, Catalog<?>> catalogs = new LinkedHashMap<>();
    catalogs.put("a", a);
    catalogs.put("b", b);
    final SnapshotReconciler reconciler = reconciler(catalogs, bridge,
        new ToggleLeaderElection(true), metrics, ConcurrentHashMap.newKeySet(),
        new ArrayList<>(), leaderRepairs);
    reconciler.runPass();
    assertEquals(List.of("a", "b"), leaderRepairs);
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("a"));
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("b"));
    store.failDescribe = true;

    reconciler.runPass();

    assertEquals(List.of("a", "b"), metrics.failed);
    assertEquals(List.of("a", "b"), leaderRepairs);
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("a"));
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("b"));
  }

  @Test
  void whenRunningPass_givenRepairThrows_shouldReportFailureAndStayDrifted() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of());
    store.put("cities", snapshotOf("cities", List.of("Madrid"), 1L));
    final RecordingMetrics metrics = new RecordingMetrics();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(false), metrics, ConcurrentHashMap.newKeySet(),
        new ArrayList<>(), new ArrayList<>());
    store.failLoad = true;

    reconciler.runPass();

    assertEquals(SyncState.DRIFTED, reconciler.syncState("cities"));
    assertEquals(List.of("cities"), metrics.failed);
    assertTrue(metrics.repaired.isEmpty());
  }

  @Test
  void whenRunningPass_givenCatalogWithoutSerializer_shouldReportUnknown() {
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(new InMemorySnapshotStore());
    final Catalog<String> plain = Catalog.of(String.class)
        .named("plain")
        .data(List.of("a"))
        .index("by-self").by(s -> s, Function.identity())
        .build();
    plain.bootstrap();
    final SnapshotReconciler reconciler = reconciler(Map.of("plain", plain), bridge,
        new ToggleLeaderElection(false), new RecordingMetrics(),
        ConcurrentHashMap.newKeySet(), new ArrayList<>(), new ArrayList<>());

    reconciler.runPass();

    assertEquals(SyncState.UNKNOWN, reconciler.syncState("plain"));
    assertTrue(reconciler.lastReconciledAt("plain").isEmpty());
  }

  @Test
  void whenRunningPasses_givenUnstableSerializerRoundTrip_shouldNotRepairRepeatedly() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new ReversingSerializer(), List.of());
    store.put("cities", snapshotOf("cities", List.of("Madrid", "Tokyo"), 1L));
    final List<String> followerRepairs = new ArrayList<>();
    final SnapshotReconciler reconciler = reconciler(Map.of("cities", cities), bridge,
        new ToggleLeaderElection(false), new RecordingMetrics(),
        ConcurrentHashMap.newKeySet(), followerRepairs, new ArrayList<>());

    reconciler.runPass();
    reconciler.runPass();
    reconciler.runPass();

    assertEquals(1, followerRepairs.size());
    assertEquals(SyncState.IN_SYNC, reconciler.syncState("cities"));
  }

  @Test
  void whenPromotedToLeader_givenStartedReconciler_shouldRunPassImmediately()
      throws InterruptedException {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of("Madrid"));
    cities.bootstrap();
    final ToggleLeaderElection election = new ToggleLeaderElection(false);
    final List<String> leaderRepairs = new CopyOnWriteArrayList<>();
    final SnapshotReconciler reconciler = new SnapshotReconciler(Map.of("cities", cities),
        bridge, election, INLINE, new RecordingMetrics(),
        ReconciliationPolicy.of(Duration.ofHours(1)), ConcurrentHashMap.newKeySet(),
        c -> bridge.load(c), c -> {
          leaderRepairs.add(c.name());
          bridge.save(c);
        });
    reconciler.start();
    try {
      election.become(true);

      awaitUntil(() -> !leaderRepairs.isEmpty(), Duration.ofSeconds(5));
      assertEquals(List.of("cities"), leaderRepairs);
    } finally {
      reconciler.stop();
    }
  }

  @Test
  void whenStarted_givenShortInterval_shouldRunPassesPeriodically()
      throws InterruptedException {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> cities = catalog("cities", new LinesSerializer(), List.of("Madrid"));
    cities.bootstrap();
    final SnapshotReconciler reconciler = new SnapshotReconciler(Map.of("cities", cities),
        bridge, new ToggleLeaderElection(false), INLINE, new RecordingMetrics(),
        ReconciliationPolicy.of(Duration.ofMillis(30)), ConcurrentHashMap.newKeySet(),
        c -> bridge.load(c), c -> bridge.save(c));
    reconciler.start();
    try {
      awaitUntil(() -> store.describeCalls() >= 3, Duration.ofSeconds(5));
    } finally {
      reconciler.stop();
    }
    final int callsAtStop = store.describeCalls();
    Thread.sleep(150);
    assertTrue(store.describeCalls() <= callsAtStop + 1,
        "No passes must run after stop()");
  }

  @Test
  void whenStopping_givenNeverStarted_shouldNotThrow() {
    final SnapshotReconciler reconciler = new SnapshotReconciler(Map.of(),
        new SnapshotStoreBridge(null), new ToggleLeaderElection(false), INLINE,
        new RecordingMetrics(), ReconciliationPolicy.defaultPolicy(),
        ConcurrentHashMap.newKeySet(), c -> { }, c -> { });

    reconciler.stop();
    reconciler.requestPass();
  }
}
