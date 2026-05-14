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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.easymock.Capture;
import org.junit.jupiter.api.Test;
import org.waabox.andersoni.leader.LeaderElectionStrategy;
import org.waabox.andersoni.snapshot.SnapshotSerializer;
import org.waabox.andersoni.sync.PatchEvent;
import org.waabox.andersoni.sync.PatchListener;
import org.waabox.andersoni.sync.RefreshListener;
import org.waabox.andersoni.sync.SyncStrategy;

/**
 * Tests for the follower-side patch dispatch in {@link Andersoni}.
 */
class AndersoniPatchDispatchTest {

  record Sport(String name) {}
  record Venue(String name) {}
  record Event(String id, Sport sport, Venue venue) {}

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

  /** Bundles the wired Andersoni instance with the captured patch listener. */
  private record Setup(Andersoni andersoni, PatchListener listener,
      Catalog<Event> catalog, AtomicReference<List<Event>> loaderData,
      SyncStrategy syncStrategy, LeaderElectionStrategy leader) {}

  private Setup followerSetup(final List<Event> initial) {
    final AtomicReference<List<Event>> data = new AtomicReference<>(
        new ArrayList<>(initial));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.copyOf(data.get()))
        .serializer(new EventSerializer())
        .index("by-id").by(Event::id, id -> id)
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    final SyncStrategy syncStrategy = createMock(SyncStrategy.class);
    expect(syncStrategy.supportsPatches()).andReturn(true).anyTimes();
    syncStrategy.subscribe(anyObject(RefreshListener.class));
    expectLastCall().once();
    final Capture<PatchListener> listenerCapture = newCapture();
    syncStrategy.subscribePatch(capture(listenerCapture));
    expectLastCall().once();
    syncStrategy.start();
    expectLastCall().once();
    syncStrategy.stop();
    expectLastCall().once();

    final LeaderElectionStrategy leader = createMock(
        LeaderElectionStrategy.class);
    leader.start();
    expectLastCall().anyTimes();
    // Bootstrap as leader (fast path via DataLoader) then switch to
    // follower for the rest of the test so handlePatch sees a non-leader.
    expect(leader.isLeader()).andReturn(true).times(1);
    expect(leader.isLeader()).andReturn(false).anyTimes();
    leader.stop();
    expectLastCall().anyTimes();

    replay(syncStrategy, leader);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("follower-1")
        .syncStrategy(syncStrategy)
        .leaderElection(leader)
        .build();
    andersoni.register(catalog);
    andersoni.start();

    return new Setup(andersoni, listenerCapture.getValue(), catalog,
        data, syncStrategy, leader);
  }

  private static PatchEvent buildEvent(final long fromVersion,
      final long toVersion, final String fromHash, final String toHash,
      final Event oldItem, final Event newItem, final String sourceNode) {
    final EventSerializer s = new EventSerializer();
    return new PatchEvent("events", sourceNode, fromVersion, toVersion,
        fromHash, toHash, "by-id",
        s.serialize(List.of(oldItem)),
        s.serialize(List.of(newItem)),
        Instant.now());
  }

  @Test
  void whenPatchArrives_givenMatchingPrecondition_shouldApplySurgically()
      throws InterruptedException {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Setup setup = followerSetup(List.of(a));

    final Snapshot<Event> before = setup.catalog.currentSnapshot();
    final Event updated = new Event("a", new Sport("F"), new Venue("V2"));
    final PatchEvent event = buildEvent(before.version(),
        before.version() + 1, before.hash(), "newHash", a, updated,
        "leader-1");

    setup.listener.onPatch(event);
    Thread.sleep(200);

    assertEquals(List.of(updated),
        setup.catalog.search("by-id", "a"));
    assertEquals(List.of(updated),
        setup.catalog.search("by-venue", "V2"));
    assertNotEquals(before.version(),
        setup.catalog.currentSnapshot().version());

    setup.andersoni.stop();
    verify(setup.syncStrategy, setup.leader);
  }

  @Test
  void whenPatchArrives_givenStaleToVersion_shouldDiscard()
      throws InterruptedException {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Setup setup = followerSetup(List.of(a));

    final Snapshot<Event> before = setup.catalog.currentSnapshot();
    final Event updated = new Event("a", new Sport("F"), new Venue("V2"));
    final PatchEvent event = buildEvent(0L, before.version(),
        "old", before.hash(), a, updated, "leader-1");

    setup.listener.onPatch(event);
    Thread.sleep(200);

    assertEquals(List.of(a), setup.catalog.search("by-id", "a"));
    assertEquals(before.version(),
        setup.catalog.currentSnapshot().version());

    setup.andersoni.stop();
    verify(setup.syncStrategy, setup.leader);
  }

  @Test
  void whenPatchArrives_givenPrecondMismatch_shouldFallBackToReload()
      throws InterruptedException {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Setup setup = followerSetup(List.of(a));

    final Snapshot<Event> before = setup.catalog.currentSnapshot();

    final Event divergent = new Event("a", new Sport("Z"),
        new Venue("VZ"));
    setup.loaderData.set(new ArrayList<>(List.of(divergent)));

    final Event updated = new Event("a", new Sport("F"), new Venue("V2"));
    final PatchEvent event = buildEvent(before.version(),
        before.version() + 1, "totallyDifferentFromHash", "newHash",
        a, updated, "leader-1");

    setup.listener.onPatch(event);
    Thread.sleep(300);

    assertEquals(List.of(divergent), setup.catalog.search("by-id", "a"));

    setup.andersoni.stop();
    verify(setup.syncStrategy, setup.leader);
  }

  @Test
  void whenPatchArrives_givenSelfEvent_shouldIgnore()
      throws InterruptedException {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Setup setup = followerSetup(List.of(a));

    final Snapshot<Event> before = setup.catalog.currentSnapshot();
    final Event updated = new Event("a", new Sport("F"), new Venue("V2"));
    final PatchEvent event = buildEvent(before.version(),
        before.version() + 1, before.hash(), "newHash", a, updated,
        "follower-1");  // same nodeId as this andersoni instance

    setup.listener.onPatch(event);
    Thread.sleep(200);

    assertEquals(List.of(a), setup.catalog.search("by-id", "a"));
    assertEquals(before.version(),
        setup.catalog.currentSnapshot().version());

    setup.andersoni.stop();
    verify(setup.syncStrategy, setup.leader);
  }

  @Test
  void whenPatchArrives_givenUnknownCatalog_shouldIgnore()
      throws InterruptedException {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Setup setup = followerSetup(List.of(a));

    final long versionBefore = setup.catalog.currentSnapshot().version();

    final EventSerializer s = new EventSerializer();
    final PatchEvent event = new PatchEvent("unknown-catalog", "leader-1",
        0L, 1L, "from", "to", "by-id",
        s.serialize(List.of(a)),
        s.serialize(List.of(new Event("a", new Sport("F"),
            new Venue("V2")))),
        Instant.now());

    setup.listener.onPatch(event);
    Thread.sleep(100);

    // The known catalog is untouched.
    assertEquals(versionBefore,
        setup.catalog.currentSnapshot().version());

    setup.andersoni.stop();
    verify(setup.syncStrategy, setup.leader);
  }
}
