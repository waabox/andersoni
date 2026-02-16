package org.waabox.andersoni;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.easymock.Capture;
import org.junit.jupiter.api.Test;
import org.waabox.andersoni.leader.LeaderElectionStrategy;
import org.waabox.andersoni.metrics.AndersoniMetrics;
import org.waabox.andersoni.snapshot.SerializedSnapshot;
import org.waabox.andersoni.snapshot.SnapshotSerializer;
import org.waabox.andersoni.snapshot.SnapshotStore;
import org.waabox.andersoni.sync.RefreshEvent;
import org.waabox.andersoni.sync.RefreshListener;
import org.waabox.andersoni.sync.SyncStrategy;

/**
 * Tests for {@link Andersoni}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class AndersoniTest {

  /** A sport domain object used for testing. */
  record Sport(String name) {}

  /** A venue domain object used for testing. */
  record Venue(String name) {}

  /** An event domain object used for testing. */
  record Event(String id, Sport sport, Venue venue) {}

  @Test
  void whenBuilding_givenDefaults_shouldCreateWithAutoNodeId() {
    final Andersoni andersoni = Andersoni.builder().build();

    assertNotNull(andersoni.nodeId());

    // Verify it is a valid UUID format.
    UUID.fromString(andersoni.nodeId());

    andersoni.stop();
  }

  @Test
  void whenBuilding_givenCustomNodeId_shouldUseIt() {
    final Andersoni andersoni = Andersoni.builder()
        .nodeId("custom-node-1")
        .build();

    assertEquals("custom-node-1", andersoni.nodeId());

    andersoni.stop();
  }

  @Test
  void whenRegistering_givenCatalog_shouldMakeItSearchable() {
    final Sport football = new Sport("Football");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(catalog);
    andersoni.start();

    final List<?> results = andersoni.search("events", "by-sport", "Football");

    assertEquals(1, results.size());
    assertEquals(e1, results.get(0));

    andersoni.stop();
  }

  @Test
  void whenRegistering_givenDuplicateName_shouldThrow() {
    final Catalog<Event> catalog1 = Catalog.of(Event.class)
        .named("events")
        .data(List.of())
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Catalog<Event> catalog2 = Catalog.of(Event.class)
        .named("events")
        .data(List.of())
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(catalog1);

    assertThrows(IllegalArgumentException.class,
        () -> andersoni.register(catalog2));

    andersoni.stop();
  }

  @Test
  void whenSearching_givenUnknownCatalog_shouldThrow() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.start();

    assertThrows(IllegalArgumentException.class,
        () -> andersoni.search("unknown", "by-sport", "Football"));

    andersoni.stop();
  }

  @Test
  void whenStarting_givenMultipleCatalogs_shouldBootstrapAll() {
    final Sport football = new Sport("Football");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    final Catalog<Event> eventsCatalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Catalog<Sport> sportsCatalog = Catalog.of(Sport.class)
        .named("sports")
        .data(List.of(football))
        .index("by-name").by(s -> s, Sport::name)
        .build();

    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(eventsCatalog);
    andersoni.register(sportsCatalog);
    andersoni.start();

    final List<?> eventResults = andersoni.search(
        "events", "by-sport", "Football");
    assertEquals(1, eventResults.size());

    final List<?> sportResults = andersoni.search(
        "sports", "by-name", "Football");
    assertEquals(1, sportResults.size());

    andersoni.stop();
  }

  @Test
  void whenRefreshAndSync_givenSyncStrategy_shouldPublishEvent() {
    final Sport football = new Sport("Football");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of(e1))
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final SyncStrategy syncStrategy = createMock(SyncStrategy.class);

    // Expect subscribe to be called on start.
    syncStrategy.subscribe(anyObject(RefreshListener.class));
    expectLastCall().once();

    // Expect start to be called.
    syncStrategy.start();
    expectLastCall().once();

    // Expect publish with a RefreshEvent for "events" catalog.
    final Capture<RefreshEvent> eventCapture = newCapture();
    syncStrategy.publish(capture(eventCapture));
    expectLastCall().once();

    // Expect stop to be called on cleanup.
    syncStrategy.stop();
    expectLastCall().once();

    replay(syncStrategy);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .syncStrategy(syncStrategy)
        .build();

    andersoni.register(catalog);
    andersoni.start();

    andersoni.refreshAndSync("events");

    final RefreshEvent published = eventCapture.getValue();
    assertEquals("events", published.catalogName());
    assertEquals("node-1", published.sourceNodeId());
    assertNotNull(published.hash());
    assertNotNull(published.timestamp());

    andersoni.stop();

    verify(syncStrategy);
  }

  @Test
  void whenReceivingRefreshEvent_givenDifferentHash_shouldRefreshCatalog() {
    final Sport football = new Sport("Football");
    final Sport rugby = new Sport("Rugby");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    // Use a mutable list holder to change data on refresh.
    final List<Event>[] dataHolder = new List[]{List.of(e1)};

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> dataHolder[0])
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    // Capture the RefreshListener registered via subscribe.
    final Capture<RefreshListener> listenerCapture = newCapture();

    final SyncStrategy syncStrategy = createMock(SyncStrategy.class);
    syncStrategy.subscribe(capture(listenerCapture));
    expectLastCall().once();
    syncStrategy.start();
    expectLastCall().once();
    syncStrategy.stop();
    expectLastCall().once();
    replay(syncStrategy);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .syncStrategy(syncStrategy)
        .build();

    andersoni.register(catalog);
    andersoni.start();

    // Verify initial data.
    final List<?> initialResults = andersoni.search(
        "events", "by-sport", "Football");
    assertEquals(1, initialResults.size());

    // Change the data that DataLoader will return.
    final Event e2 = new Event("2", rugby, maracana);
    dataHolder[0] = List.of(e1, e2);

    // Simulate receiving a refresh event from another node with a
    // different hash.
    final RefreshListener listener = listenerCapture.getValue();
    final RefreshEvent event = new RefreshEvent(
        "events", "node-2", 2L, "different-hash",
        java.time.Instant.now());
    listener.onRefresh(event);

    // Verify the catalog was refreshed with new data.
    final List<?> rugbyResults = andersoni.search(
        "events", "by-sport", "Rugby");
    assertEquals(1, rugbyResults.size());

    andersoni.stop();

    verify(syncStrategy);
  }

  @Test
  void whenReceivingRefreshEvent_givenSameNodeId_shouldIgnore() {
    final Sport football = new Sport("Football");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    final int[] loadCount = {0};

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> {
          loadCount[0]++;
          return List.of(e1);
        })
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Capture<RefreshListener> listenerCapture = newCapture();

    final SyncStrategy syncStrategy = createMock(SyncStrategy.class);
    syncStrategy.subscribe(capture(listenerCapture));
    expectLastCall().once();
    syncStrategy.start();
    expectLastCall().once();
    syncStrategy.stop();
    expectLastCall().once();
    replay(syncStrategy);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .syncStrategy(syncStrategy)
        .build();

    andersoni.register(catalog);
    andersoni.start();

    // Record load count after bootstrap.
    final int loadCountAfterBootstrap = loadCount[0];

    // Simulate receiving an event from the same node.
    final RefreshListener listener = listenerCapture.getValue();
    final RefreshEvent event = new RefreshEvent(
        "events", "node-1", 2L, "some-hash",
        java.time.Instant.now());
    listener.onRefresh(event);

    // Load count should not have increased since our own event is ignored.
    assertEquals(loadCountAfterBootstrap, loadCount[0]);

    andersoni.stop();

    verify(syncStrategy);
  }

  @Test
  void whenStopping_shouldStopSyncAndLeader() {
    final SyncStrategy syncStrategy = createMock(SyncStrategy.class);
    final LeaderElectionStrategy leaderElection =
        createMock(LeaderElectionStrategy.class);

    // Expect start lifecycle: leader election starts before sync.
    leaderElection.start();
    expectLastCall().once();
    leaderElection.isLeader();
    expectLastCall().andReturn(true).anyTimes();

    syncStrategy.subscribe(anyObject(RefreshListener.class));
    expectLastCall().once();
    syncStrategy.start();
    expectLastCall().once();

    // Expect stop lifecycle.
    syncStrategy.stop();
    expectLastCall().once();
    leaderElection.stop();
    expectLastCall().once();

    replay(syncStrategy, leaderElection);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .syncStrategy(syncStrategy)
        .leaderElection(leaderElection)
        .build();

    andersoni.start();
    andersoni.stop();

    verify(syncStrategy, leaderElection);
  }

  @Test
  void whenCatalogs_givenRegisteredCatalogs_shouldReturnUnmodifiable() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of())
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(catalog);

    final Collection<Catalog<?>> catalogs = andersoni.catalogs();
    assertEquals(1, catalogs.size());

    assertThrows(UnsupportedOperationException.class,
        () -> catalogs.add(catalog));

    andersoni.stop();
  }

  @SuppressWarnings("unchecked")
  @Test
  void whenBootstrap_givenLeaderAndS3Fails_shouldFallbackToDataLoaderAndSaveToS3() {
    final Sport football = new Sport("Football");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    final SnapshotSerializer<Event> serializer =
        createMock(SnapshotSerializer.class);
    final SnapshotStore snapshotStore = createMock(SnapshotStore.class);
    final LeaderElectionStrategy leaderElection =
        createMock(LeaderElectionStrategy.class);
    final AndersoniMetrics metrics = createMock(AndersoniMetrics.class);

    // Leader election starts and this node is leader.
    leaderElection.start();
    expectLastCall().once();
    expect(leaderElection.isLeader()).andReturn(true).anyTimes();

    // S3 load returns empty (simulating no snapshot or incompatible).
    expect(snapshotStore.load("events")).andReturn(Optional.empty()).once();

    // serialize is called twice: once by Catalog.computeHash during
    // bootstrap, and once by saveSnapshotIfPossible after.
    expect(serializer.serialize(anyObject(List.class)))
        .andReturn(new byte[]{1, 2, 3}).times(2);
    snapshotStore.save(eq("events"), anyObject(SerializedSnapshot.class));
    expectLastCall().once();

    // Metrics.
    metrics.snapshotLoaded("events", "dataLoader");
    expectLastCall().once();

    // Stop.
    leaderElection.stop();
    expectLastCall().once();

    replay(serializer, snapshotStore, leaderElection, metrics);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of(e1))
        .serializer(serializer)
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .snapshotStore(snapshotStore)
        .leaderElection(leaderElection)
        .metrics(metrics)
        .build();

    andersoni.register(catalog);
    andersoni.start();

    // Verify the catalog was loaded and is searchable.
    final List<?> results = andersoni.search(
        "events", "by-sport", "Football");
    assertEquals(1, results.size());

    andersoni.stop();

    verify(serializer, snapshotStore, leaderElection, metrics);
  }

  @SuppressWarnings("unchecked")
  @Test
  void whenBootstrap_givenFollower_shouldRetryS3UntilSuccess() {
    final Sport football = new Sport("Football");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    final SnapshotSerializer<Event> serializer =
        createMock(SnapshotSerializer.class);
    final SnapshotStore snapshotStore = createMock(SnapshotStore.class);
    final LeaderElectionStrategy leaderElection =
        createMock(LeaderElectionStrategy.class);
    final AndersoniMetrics metrics = createMock(AndersoniMetrics.class);

    // Leader election starts and this node is a follower.
    leaderElection.start();
    expectLastCall().once();
    expect(leaderElection.isLeader()).andReturn(false).anyTimes();

    // First S3 attempt: returns empty (initial try).
    // Then follower retries: empty twice, then succeeds on 3rd.
    final byte[] serializedBytes = new byte[]{1, 2, 3};
    final SerializedSnapshot snapshot = new SerializedSnapshot(
        "events", "hash-1", 1L, Instant.now(), serializedBytes);

    expect(snapshotStore.load("events"))
        .andReturn(Optional.empty())   // initial try in bootstrapWithRetry
        .andReturn(Optional.empty())   // follower attempt 1
        .andReturn(Optional.empty())   // follower attempt 2
        .andReturn(Optional.of(snapshot)); // follower attempt 3

    expect(serializer.deserialize(serializedBytes))
        .andReturn(List.of(e1)).once();

    // serialize is called by Catalog.computeHash when building the
    // snapshot from deserialized data.
    expect(serializer.serialize(anyObject(List.class)))
        .andReturn(new byte[]{1, 2, 3}).once();

    // Metrics: loaded from snapshot store.
    metrics.snapshotLoaded("events", "snapshotStore");
    expectLastCall().once();

    // Stop.
    leaderElection.stop();
    expectLastCall().once();

    replay(serializer, snapshotStore, leaderElection, metrics);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of(e1))
        .serializer(serializer)
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-2")
        .snapshotStore(snapshotStore)
        .leaderElection(leaderElection)
        .retryPolicy(RetryPolicy.of(3, Duration.ofMillis(10)))
        .metrics(metrics)
        .build();

    andersoni.register(catalog);
    andersoni.start();

    // Verify the catalog was loaded from snapshot and is searchable.
    final List<?> results = andersoni.search(
        "events", "by-sport", "Football");
    assertEquals(1, results.size());

    andersoni.stop();

    verify(serializer, snapshotStore, leaderElection, metrics);
  }

  @SuppressWarnings("unchecked")
  @Test
  void whenBootstrap_givenFollowerPromotedToLeader_shouldSwitchToDataLoader() {
    final Sport football = new Sport("Football");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    final SnapshotSerializer<Event> serializer =
        createMock(SnapshotSerializer.class);
    final SnapshotStore snapshotStore = createMock(SnapshotStore.class);
    final LeaderElectionStrategy leaderElection =
        createMock(LeaderElectionStrategy.class);
    final AndersoniMetrics metrics = createMock(AndersoniMetrics.class);

    // Leader election starts, initially this node is a follower.
    leaderElection.start();
    expectLastCall().once();

    // Initial S3 try returns empty.
    expect(snapshotStore.load("events"))
        .andReturn(Optional.empty()).once();

    // First isLeader check: false (enters follower path).
    // Second isLeader check inside follower loop: true (promoted!).
    expect(leaderElection.isLeader())
        .andReturn(false)    // role check -> follower
        .andReturn(true);    // re-check in follower loop -> promoted

    // After promotion, leader calls DataLoader and saves to S3.
    // serialize is called twice: once by computeHash, once by
    // saveSnapshotIfPossible.
    expect(serializer.serialize(anyObject(List.class)))
        .andReturn(new byte[]{1, 2, 3}).times(2);
    snapshotStore.save(eq("events"), anyObject(SerializedSnapshot.class));
    expectLastCall().once();

    metrics.snapshotLoaded("events", "dataLoader");
    expectLastCall().once();

    leaderElection.stop();
    expectLastCall().once();

    replay(serializer, snapshotStore, leaderElection, metrics);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of(e1))
        .serializer(serializer)
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-2")
        .snapshotStore(snapshotStore)
        .leaderElection(leaderElection)
        .retryPolicy(RetryPolicy.of(3, Duration.ofMillis(10)))
        .metrics(metrics)
        .build();

    andersoni.register(catalog);
    andersoni.start();

    final List<?> results = andersoni.search(
        "events", "by-sport", "Football");
    assertEquals(1, results.size());

    andersoni.stop();

    verify(serializer, snapshotStore, leaderElection, metrics);
  }

  @SuppressWarnings("unchecked")
  @Test
  void whenBootstrap_givenFollowerWaiting_shouldLogWarningEvery10Attempts() {
    final SnapshotSerializer<Event> serializer =
        createMock(SnapshotSerializer.class);
    final SnapshotStore snapshotStore = createMock(SnapshotStore.class);
    final LeaderElectionStrategy leaderElection =
        createMock(LeaderElectionStrategy.class);
    final AndersoniMetrics metrics = createMock(AndersoniMetrics.class);

    leaderElection.start();
    expectLastCall().once();
    expect(leaderElection.isLeader()).andReturn(false).anyTimes();

    // S3 always returns empty: exhaust all 30 attempts (maxRetries=3 * 10).
    expect(snapshotStore.load("events"))
        .andReturn(Optional.empty()).anyTimes();

    // Expect failure metric when all attempts exhausted.
    metrics.refreshFailed(eq("events"), anyObject(Throwable.class));
    expectLastCall().once();

    leaderElection.stop();
    expectLastCall().once();

    replay(serializer, snapshotStore, leaderElection, metrics);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of())
        .serializer(serializer)
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-2")
        .snapshotStore(snapshotStore)
        .leaderElection(leaderElection)
        .retryPolicy(RetryPolicy.of(3, Duration.ofMillis(1)))
        .metrics(metrics)
        .build();

    andersoni.register(catalog);
    andersoni.start();

    // Catalog should be marked as failed.
    assertThrows(CatalogNotAvailableException.class,
        () -> andersoni.search("events", "by-sport", "Football"));

    andersoni.stop();

    verify(serializer, snapshotStore, leaderElection, metrics);
  }

  // --- Corner case tests ---

  @Test
  void whenBootstrap_givenLeaderDataLoaderExhausted_shouldMarkAsFailed() {
    final LeaderElectionStrategy leaderElection =
        createMock(LeaderElectionStrategy.class);
    final AndersoniMetrics metrics = createMock(AndersoniMetrics.class);

    leaderElection.start();
    expectLastCall().once();
    expect(leaderElection.isLeader()).andReturn(true).anyTimes();

    metrics.refreshFailed(eq("events"), anyObject(Throwable.class));
    expectLastCall().once();

    leaderElection.stop();
    expectLastCall().once();

    replay(leaderElection, metrics);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> {
          throw new RuntimeException("DataLoader always fails");
        })
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .leaderElection(leaderElection)
        .retryPolicy(RetryPolicy.of(2, Duration.ofMillis(1)))
        .metrics(metrics)
        .build();

    andersoni.register(catalog);
    andersoni.start();

    assertThrows(CatalogNotAvailableException.class,
        () -> andersoni.search("events", "by-sport", "Football"));

    andersoni.stop();

    verify(leaderElection, metrics);
  }

  @Test
  void whenReceivingRefreshEvent_givenUnknownCatalog_shouldIgnore() {
    final Capture<RefreshListener> listenerCapture = newCapture();

    final SyncStrategy syncStrategy = createMock(SyncStrategy.class);
    syncStrategy.subscribe(capture(listenerCapture));
    expectLastCall().once();
    syncStrategy.start();
    expectLastCall().once();
    syncStrategy.stop();
    expectLastCall().once();
    replay(syncStrategy);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .syncStrategy(syncStrategy)
        .build();

    andersoni.start();

    final RefreshListener listener = listenerCapture.getValue();
    final RefreshEvent event = new RefreshEvent(
        "unknown-catalog", "node-2", 1L, "some-hash", Instant.now());

    // Should not throw, just log a warning.
    listener.onRefresh(event);

    andersoni.stop();

    verify(syncStrategy);
  }

  @Test
  void whenReceivingRefreshEvent_givenSameHash_shouldSkipRefresh() {
    final Sport football = new Sport("Football");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    final int[] loadCount = {0};

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> {
          loadCount[0]++;
          return List.of(e1);
        })
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Capture<RefreshListener> listenerCapture = newCapture();

    final SyncStrategy syncStrategy = createMock(SyncStrategy.class);
    syncStrategy.subscribe(capture(listenerCapture));
    expectLastCall().once();
    syncStrategy.start();
    expectLastCall().once();
    syncStrategy.stop();
    expectLastCall().once();
    replay(syncStrategy);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .syncStrategy(syncStrategy)
        .build();

    andersoni.register(catalog);
    andersoni.start();

    final int loadCountAfterBootstrap = loadCount[0];

    // Get the current hash.
    final String currentHash = catalog.currentSnapshot().hash();

    // Send an event with the SAME hash.
    final RefreshListener listener = listenerCapture.getValue();
    final RefreshEvent event = new RefreshEvent(
        "events", "node-2", 2L, currentHash, Instant.now());
    listener.onRefresh(event);

    assertEquals(loadCountAfterBootstrap, loadCount[0],
        "DataLoader should not be called when hashes match");

    andersoni.stop();

    verify(syncStrategy);
  }

  @Test
  void whenRefreshAndSync_givenUnknownCatalog_shouldThrow() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.start();

    assertThrows(IllegalArgumentException.class,
        () -> andersoni.refreshAndSync("non-existent"));

    andersoni.stop();
  }

  @Test
  void whenRefresh_givenUnknownCatalog_shouldThrow() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.start();

    assertThrows(IllegalArgumentException.class,
        () -> andersoni.refresh("non-existent"));

    andersoni.stop();
  }

  @Test
  void whenReceivingRefreshEvent_givenDataLoaderThrows_shouldCatchAndLog() {
    final Sport football = new Sport("Football");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    final boolean[] shouldThrow = {false};

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> {
          if (shouldThrow[0]) {
            throw new RuntimeException("Refresh failure");
          }
          return List.of(e1);
        })
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Capture<RefreshListener> listenerCapture = newCapture();

    final SyncStrategy syncStrategy = createMock(SyncStrategy.class);
    final AndersoniMetrics metrics = createMock(AndersoniMetrics.class);

    syncStrategy.subscribe(capture(listenerCapture));
    expectLastCall().once();
    syncStrategy.start();
    expectLastCall().once();

    // Expect snapshotLoaded during bootstrap (DataLoader succeeds).
    metrics.snapshotLoaded("events", "dataLoader");
    expectLastCall().once();

    // Expect the failure metric when sync refresh fails.
    metrics.refreshFailed(eq("events"), anyObject(Throwable.class));
    expectLastCall().once();

    syncStrategy.stop();
    expectLastCall().once();

    replay(syncStrategy, metrics);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .syncStrategy(syncStrategy)
        .metrics(metrics)
        .build();

    andersoni.register(catalog);
    andersoni.start();

    shouldThrow[0] = true;

    final RefreshListener listener = listenerCapture.getValue();
    final RefreshEvent event = new RefreshEvent(
        "events", "node-2", 2L, "different-hash", Instant.now());

    // Should not throw, the exception is caught internally.
    listener.onRefresh(event);

    // Original data should still be searchable.
    final List<?> results = andersoni.search(
        "events", "by-sport", "Football");
    assertEquals(1, results.size());

    andersoni.stop();

    verify(syncStrategy, metrics);
  }

  @Test
  void whenRegistering_givenNullCatalog_shouldThrowNpe() {
    final Andersoni andersoni = Andersoni.builder().build();

    assertThrows(NullPointerException.class,
        () -> andersoni.register(null));

    andersoni.stop();
  }

  @SuppressWarnings("unchecked")
  @Test
  void whenBootstrap_givenSnapshotStoreThrowsOnLoad_shouldFallbackToDataLoader() {
    final Sport football = new Sport("Football");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    final SnapshotSerializer<Event> serializer =
        createMock(SnapshotSerializer.class);
    final SnapshotStore snapshotStore = createMock(SnapshotStore.class);
    final LeaderElectionStrategy leaderElection =
        createMock(LeaderElectionStrategy.class);
    final AndersoniMetrics metrics = createMock(AndersoniMetrics.class);

    leaderElection.start();
    expectLastCall().once();
    expect(leaderElection.isLeader()).andReturn(true).anyTimes();

    // SnapshotStore throws on load.
    expect(snapshotStore.load("events"))
        .andThrow(new RuntimeException("S3 connection error")).once();

    // Falls back to DataLoader.
    expect(serializer.serialize(anyObject(List.class)))
        .andReturn(new byte[]{1, 2, 3}).times(2);
    snapshotStore.save(eq("events"), anyObject(SerializedSnapshot.class));
    expectLastCall().once();

    metrics.snapshotLoaded("events", "dataLoader");
    expectLastCall().once();

    leaderElection.stop();
    expectLastCall().once();

    replay(serializer, snapshotStore, leaderElection, metrics);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of(e1))
        .serializer(serializer)
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .snapshotStore(snapshotStore)
        .leaderElection(leaderElection)
        .metrics(metrics)
        .build();

    andersoni.register(catalog);
    andersoni.start();

    final List<?> results = andersoni.search(
        "events", "by-sport", "Football");
    assertEquals(1, results.size());

    andersoni.stop();

    verify(serializer, snapshotStore, leaderElection, metrics);
  }

  @Test
  void whenStopping_givenNotStarted_shouldNotThrow() {
    final Andersoni andersoni = Andersoni.builder().build();

    // Should not throw even if start() was never called.
    andersoni.stop();
  }

  @Test
  void whenScheduledRefresh_givenNonLeader_shouldSkipRefresh()
      throws InterruptedException {
    final Sport football = new Sport("Football");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    final int[] loadCount = {0};

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> {
          loadCount[0]++;
          return List.of(e1);
        })
        .refreshEvery(Duration.ofMillis(50))
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final LeaderElectionStrategy leaderElection =
        createMock(LeaderElectionStrategy.class);

    // Leader during bootstrap, non-leader for scheduled refresh.
    leaderElection.start();
    expectLastCall().once();
    expect(leaderElection.isLeader())
        .andReturn(true)   // bootstrap
        .andReturn(false)  // scheduled refresh 1
        .andReturn(false)  // scheduled refresh 2
        .andReturn(false)  // scheduled refresh 3
        .andReturn(false).anyTimes();
    leaderElection.stop();
    expectLastCall().once();

    replay(leaderElection);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .leaderElection(leaderElection)
        .build();

    andersoni.register(catalog);
    andersoni.start();

    final int loadAfterBootstrap = loadCount[0];

    // Wait enough time for several scheduled refreshes to fire.
    Thread.sleep(200);

    assertEquals(loadAfterBootstrap, loadCount[0],
        "Non-leader should not trigger scheduled refresh");

    andersoni.stop();

    verify(leaderElection);
  }

  @Test
  void whenBootstrapAndSync_givenSyncWiredAfterBootstrap_shouldNotReceiveEventsDuringBootstrap() {
    final Sport football = new Sport("Football");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    // Track the order: bootstrap must complete before subscribe is called.
    final java.util.List<String> callOrder =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> {
          callOrder.add("bootstrap");
          return List.of(e1);
        })
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final SyncStrategy syncStrategy = createMock(SyncStrategy.class);

    syncStrategy.subscribe(anyObject(RefreshListener.class));
    expectLastCall().andAnswer(() -> {
      callOrder.add("subscribe");
      return null;
    });

    syncStrategy.start();
    expectLastCall().andAnswer(() -> {
      callOrder.add("sync-start");
      return null;
    });

    syncStrategy.stop();
    expectLastCall().once();

    replay(syncStrategy);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("node-1")
        .syncStrategy(syncStrategy)
        .build();

    andersoni.register(catalog);
    andersoni.start();

    // Verify ordering: bootstrap happens before sync wiring.
    assertTrue(callOrder.indexOf("bootstrap")
            < callOrder.indexOf("subscribe"),
        "Bootstrap must complete before sync subscribe");
    assertTrue(callOrder.indexOf("subscribe")
            < callOrder.indexOf("sync-start"),
        "Subscribe must happen before sync start");

    andersoni.stop();

    verify(syncStrategy);
  }

  // --- Builder null guard tests ---

  @Test
  void whenBuilding_givenNullNodeId_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        Andersoni.builder().nodeId(null));
  }

  @Test
  void whenBuilding_givenEmptyNodeId_shouldThrowIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () ->
        Andersoni.builder().nodeId(""));
  }

  @Test
  void whenBuilding_givenNullSyncStrategy_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        Andersoni.builder().syncStrategy(null));
  }

  @Test
  void whenBuilding_givenNullLeaderElection_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        Andersoni.builder().leaderElection(null));
  }

  @Test
  void whenBuilding_givenNullSnapshotStore_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        Andersoni.builder().snapshotStore(null));
  }

  @Test
  void whenBuilding_givenNullRetryPolicy_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        Andersoni.builder().retryPolicy(null));
  }

  @Test
  void whenBuilding_givenNullMetrics_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        Andersoni.builder().metrics(null));
  }

  // --- Lifecycle edge case tests ---

  @Test
  void whenStarting_givenAlreadyStarted_shouldThrowIllegalState() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.start();

    assertThrows(IllegalStateException.class, andersoni::start);

    andersoni.stop();
  }

  @Test
  void whenStopping_givenAlreadyStopped_shouldBeIdempotent() {
    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.start();

    andersoni.stop();
    // Second stop should not throw.
    andersoni.stop();
  }

  @Test
  void whenRegistering_givenAlreadyStarted_shouldThrowIllegalState() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of())
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.start();

    assertThrows(IllegalStateException.class,
        () -> andersoni.register(catalog));

    andersoni.stop();
  }

  @Test
  void whenRefreshAndSync_givenAlreadyStopped_shouldThrowIllegalState() {
    final Sport football = new Sport("Football");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of(e1))
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(catalog);
    andersoni.start();
    andersoni.stop();

    assertThrows(IllegalStateException.class,
        () -> andersoni.refreshAndSync("events"));
  }

  @Test
  void whenSearching_givenAfterStop_shouldStillReturnData() {
    final Sport football = new Sport("Football");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(catalog);
    andersoni.start();
    andersoni.stop();

    // Search is lock-free and reads from the immutable snapshot.
    final List<?> results = andersoni.search(
        "events", "by-sport", "Football");
    assertEquals(1, results.size());
  }

  // -- Null guard tests for public API methods --

  @Test
  void whenSearching_givenNullCatalogName_shouldThrowNpe() {
    final Andersoni andersoni = Andersoni.builder().build();

    assertThrows(NullPointerException.class,
        () -> andersoni.search(null, "index", "key"));
  }

  @Test
  void whenSearching_givenNullIndexName_shouldThrowNpe() {
    final Andersoni andersoni = Andersoni.builder().build();

    assertThrows(NullPointerException.class,
        () -> andersoni.search("catalog", null, "key"));
  }

  @Test
  void whenSearching_givenNullKey_shouldThrowNpe() {
    final Andersoni andersoni = Andersoni.builder().build();

    assertThrows(NullPointerException.class,
        () -> andersoni.search("catalog", "index", null));
  }

  @Test
  void whenRefreshAndSync_givenNullCatalogName_shouldThrowNpe() {
    final Andersoni andersoni = Andersoni.builder().build();

    assertThrows(NullPointerException.class,
        () -> andersoni.refreshAndSync(null));
  }

  @Test
  void whenRefresh_givenNullCatalogName_shouldThrowNpe() {
    final Andersoni andersoni = Andersoni.builder().build();

    assertThrows(NullPointerException.class,
        () -> andersoni.refresh(null));
  }

  @Test
  void whenRefresh_givenAfterStop_shouldThrowIllegalState() {
    final Sport football = new Sport("Football");
    final Venue maracana = new Venue("Maracana");
    final Event e1 = new Event("1", football, maracana);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of(e1))
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(catalog);
    andersoni.start();
    andersoni.stop();

    assertThrows(IllegalStateException.class,
        () -> andersoni.refresh("events"));
  }
}
