package org.waabox.andersoni;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.easymock.Capture;
import org.junit.jupiter.api.Test;
import org.waabox.andersoni.leader.LeaderElectionStrategy;
import org.waabox.andersoni.snapshot.SerializedSnapshot;
import org.waabox.andersoni.snapshot.SnapshotSerializer;
import org.waabox.andersoni.snapshot.SnapshotStore;
import org.waabox.andersoni.sync.PatchEvent;
import org.waabox.andersoni.sync.PatchListener;
import org.waabox.andersoni.sync.RefreshListener;
import org.waabox.andersoni.sync.SyncStrategy;

/**
 * Tests for {@link Andersoni#replaceAndSync(String, String, Object, Object)}.
 */
class AndersoniReplaceAndSyncTest {

  record Sport(String name) {}
  record Venue(String name) {}
  record Event(String id, Sport sport, Venue venue) {}

  /** Serializer that JSON-ish encodes one event per line. */
  static final class EventSerializer implements SnapshotSerializer<Event> {
    @Override
    public byte[] serialize(final List<Event> items) {
      final StringBuilder sb = new StringBuilder();
      for (final Event e : items) {
        sb.append(e.id()).append('|').append(e.sport().name()).append('|')
            .append(e.venue().name()).append('\n');
      }
      return sb.toString().getBytes();
    }

    @Override
    public List<Event> deserialize(final byte[] data) {
      final String raw = new String(data).trim();
      if (raw.isEmpty()) {
        return List.of();
      }
      final List<Event> result = new ArrayList<>();
      for (final String line : raw.split("\n")) {
        final String[] parts = line.split("\\|");
        result.add(new Event(parts[0], new Sport(parts[1]),
            new Venue(parts[2])));
      }
      return result;
    }
  }

  private static Catalog<Event> eventsCatalog(final List<Event> data) {
    return Catalog.of(Event.class)
        .named("events")
        .data(data)
        .serializer(new EventSerializer())
        .index("by-id").by(Event::id, id -> id)
        .index("by-venue").by(Event::venue, Venue::name)
        .build();
  }

  @Test
  void whenReplaceAndSync_givenLeaderAndPatchSupport_shouldPublishPatchEvent() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));

    final SyncStrategy syncStrategy = createMock(SyncStrategy.class);
    expect(syncStrategy.supportsPatches()).andReturn(true).anyTimes();
    syncStrategy.subscribe(anyObject(RefreshListener.class));
    expectLastCall().once();
    syncStrategy.subscribePatch(anyObject(PatchListener.class));
    expectLastCall().once();
    syncStrategy.start();
    expectLastCall().once();
    final Capture<PatchEvent> patchCapture = newCapture();
    syncStrategy.publishPatch(capture(patchCapture));
    expectLastCall().once();
    syncStrategy.stop();
    expectLastCall().once();

    final LeaderElectionStrategy leader = createMock(
        LeaderElectionStrategy.class);
    leader.start();
    expectLastCall().anyTimes();
    expect(leader.isLeader()).andReturn(true).anyTimes();
    leader.stop();
    expectLastCall().anyTimes();

    final SnapshotStore snapshotStore = createMock(SnapshotStore.class);
    expect(snapshotStore.load(eq("events"))).andReturn(
        java.util.Optional.empty()).anyTimes();
    snapshotStore.save(eq("events"), anyObject(SerializedSnapshot.class));
    expectLastCall().atLeastOnce();

    replay(syncStrategy, leader, snapshotStore);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("leader-1")
        .syncStrategy(syncStrategy)
        .leaderElection(leader)
        .snapshotStore(snapshotStore)
        .build();
    andersoni.register(eventsCatalog(List.of(a)));
    andersoni.start();

    final Event updated = new Event("a", new Sport("F"), new Venue("V2"));
    final boolean replaced = andersoni.replaceAndSync("events", "by-id",
        "a", updated);

    assertTrue(replaced);

    final PatchEvent event = patchCapture.getValue();
    assertEquals("events", event.catalogName());
    assertEquals("leader-1", event.sourceNodeId());
    assertEquals("by-id", event.indexName());
    assertNotEquals(event.fromHash(), event.toHash());
    assertTrue(event.toVersion() > event.fromVersion());
    final Event deserializedOld = new EventSerializer()
        .deserialize(event.serializedOldItem()).get(0);
    assertEquals(a, deserializedOld);
    final Event deserializedNew = new EventSerializer()
        .deserialize(event.serializedNewItem()).get(0);
    assertEquals(updated, deserializedNew);

    andersoni.stop();
    verify(syncStrategy, leader, snapshotStore);
  }

  @Test
  void whenReplaceAndSync_givenNotLeader_shouldReturnFalseAndSkipPublish() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));

    final SyncStrategy syncStrategy = createMock(SyncStrategy.class);
    expect(syncStrategy.supportsPatches()).andReturn(true).anyTimes();
    syncStrategy.subscribe(anyObject(RefreshListener.class));
    expectLastCall().once();
    syncStrategy.subscribePatch(anyObject(PatchListener.class));
    expectLastCall().once();
    syncStrategy.start();
    expectLastCall().once();
    syncStrategy.stop();
    expectLastCall().once();
    // No publish/publishPatch expected.

    final LeaderElectionStrategy leader = createMock(
        LeaderElectionStrategy.class);
    leader.start();
    expectLastCall().anyTimes();
    expect(leader.isLeader()).andReturn(true).times(1);  // bootstrap
    expect(leader.isLeader()).andReturn(false).anyTimes();
    leader.stop();
    expectLastCall().anyTimes();

    replay(syncStrategy, leader);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("follower-1")
        .syncStrategy(syncStrategy)
        .leaderElection(leader)
        .build();
    andersoni.register(eventsCatalog(List.of(a)));
    andersoni.start();

    final boolean replaced = andersoni.replaceAndSync("events", "by-id",
        "a", new Event("a", new Sport("F"), new Venue("V2")));

    assertFalse(replaced);

    andersoni.stop();
    verify(syncStrategy, leader);
  }

  @Test
  void whenReplaceAndSync_givenNoMatch_shouldReturnFalseAndSkipPublish() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));

    final SyncStrategy syncStrategy = createMock(SyncStrategy.class);
    expect(syncStrategy.supportsPatches()).andReturn(true).anyTimes();
    syncStrategy.subscribe(anyObject(RefreshListener.class));
    expectLastCall().once();
    syncStrategy.subscribePatch(anyObject(PatchListener.class));
    expectLastCall().once();
    syncStrategy.start();
    expectLastCall().once();
    syncStrategy.stop();
    expectLastCall().once();
    // No publishPatch expected.

    final LeaderElectionStrategy leader = createMock(
        LeaderElectionStrategy.class);
    leader.start();
    expectLastCall().anyTimes();
    expect(leader.isLeader()).andReturn(true).anyTimes();
    leader.stop();
    expectLastCall().anyTimes();

    replay(syncStrategy, leader);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("leader-1")
        .syncStrategy(syncStrategy)
        .leaderElection(leader)
        .build();
    andersoni.register(eventsCatalog(List.of(a)));
    andersoni.start();

    final boolean replaced = andersoni.replaceAndSync("events", "by-id",
        "missing", new Event("missing", new Sport("F"), new Venue("V1")));

    assertFalse(replaced);

    andersoni.stop();
    verify(syncStrategy, leader);
  }

  @Test
  void whenReplaceAndSync_givenNoSerializer_shouldThrowIllegalState() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(a))
        .index("by-id").by(Event::id, id -> id)
        .build();

    final Andersoni andersoni = Andersoni.builder().build();
    andersoni.register(catalog);
    andersoni.start();

    assertThrows(IllegalStateException.class,
        () -> andersoni.replaceAndSync("events", "by-id", "a",
            new Event("a", new Sport("F"), new Venue("V2"))));

    andersoni.stop();
  }

  @Test
  void whenReplaceAndSync_givenSyncWithoutPatchSupport_shouldSkipPublish() {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));

    final SyncStrategy syncStrategy = createMock(SyncStrategy.class);
    expect(syncStrategy.supportsPatches()).andReturn(false).anyTimes();
    syncStrategy.subscribe(anyObject(RefreshListener.class));
    expectLastCall().once();
    syncStrategy.start();
    expectLastCall().once();
    syncStrategy.stop();
    expectLastCall().once();
    // No subscribePatch and no publishPatch expected.

    final LeaderElectionStrategy leader = createMock(
        LeaderElectionStrategy.class);
    leader.start();
    expectLastCall().anyTimes();
    expect(leader.isLeader()).andReturn(true).anyTimes();
    leader.stop();
    expectLastCall().anyTimes();

    replay(syncStrategy, leader);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("leader-1")
        .syncStrategy(syncStrategy)
        .leaderElection(leader)
        .build();
    andersoni.register(eventsCatalog(List.of(a)));
    andersoni.start();

    final boolean replaced = andersoni.replaceAndSync("events", "by-id",
        "a", new Event("a", new Sport("F"), new Venue("V2")));

    assertTrue(replaced);

    andersoni.stop();
    verify(syncStrategy, leader);
  }
}
