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
import org.waabox.andersoni.leader.LeaderChangeListener;
import org.waabox.andersoni.leader.LeaderElectionStrategy;
import org.waabox.andersoni.snapshot.SnapshotSerializer;
import org.waabox.andersoni.sync.PatchEvent;
import org.waabox.andersoni.sync.PatchOperation;
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
    leader.onLeaderChange(anyObject(LeaderChangeListener.class));
    expectLastCall().once();
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

  private static String hashOf(final List<Event> data) {
    final Catalog<Event> probe = Catalog.of(Event.class)
        .named("probe")
        .data(data)
        .serializer(new EventSerializer())
        .index("by-id").by(Event::id, id -> id)
        .build();
    probe.bootstrap();
    return probe.currentSnapshot().hash();
  }

  private static PatchEvent buildEvent(final long fromVersion,
      final long toVersion, final String fromHash, final String toHash,
      final Event oldItem, final Event newItem, final String sourceNode) {
    final EventSerializer s = new EventSerializer();
    return PatchEvent.replace("events", sourceNode, fromVersion, toVersion,
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
  void whenPatchArrives_givenToHashAlreadyLocal_shouldDiscard()
      throws InterruptedException {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Setup setup = followerSetup(List.of(a));

    final Snapshot<Event> before = setup.catalog.currentSnapshot();
    final Event updated = new Event("a", new Sport("F"), new Venue("V2"));
    final PatchEvent event = buildEvent(0L, 99L, "someOtherHash",
        before.hash(), a, updated, "leader-1");

    setup.listener.onPatch(event);
    Thread.sleep(200);

    assertEquals(List.of(a), setup.catalog.search("by-id", "a"));
    assertEquals(before.version(),
        setup.catalog.currentSnapshot().version());

    setup.andersoni.stop();
    verify(setup.syncStrategy, setup.leader);
  }

  @Test
  void whenPatchArrives_givenLocalVersionAheadOfEvent_shouldStillApply()
      throws InterruptedException {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Setup setup = followerSetup(List.of(a));

    final Snapshot<Event> before = setup.catalog.currentSnapshot();
    final Event updated = new Event("a", new Sport("F"), new Venue("V2"));

    // A restarted leader publishes from a counter that reset to zero while
    // this follower sits at a higher local version. Versions are per-node,
    // so this must not be read as a stale event.
    final PatchEvent event = buildEvent(0L, 1L, before.hash(),
        hashOf(List.of(updated)), a, updated, "leader-1");
    assertEquals(1L, before.version());

    setup.listener.onPatch(event);
    Thread.sleep(200);

    assertEquals(List.of(updated), setup.catalog.search("by-id", "a"));

    setup.andersoni.stop();
    verify(setup.syncStrategy, setup.leader);
  }

  @Test
  void whenTwoPatchesArriveBackToBack_givenSecondNeedsReload_shouldConverge()
      throws InterruptedException {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Event b = new Event("b", new Sport("F"), new Venue("V1"));
    final Setup setup = followerSetup(List.of(a, b));

    final Event patchedA = new Event("a", new Sport("F"), new Venue("V2"));
    final Event patchedB = new Event("b", new Sport("F"), new Venue("V3"));

    final String h0 = setup.catalog.currentSnapshot().hash();
    final String h1 = hashOf(List.of(patchedA, b));
    final String h2 = hashOf(List.of(patchedA, patchedB));

    // The leader saved the fully-patched state before publishing, so a
    // follower that falls back to a reload lands on it.
    setup.loaderData.set(new ArrayList<>(List.of(patchedA, patchedB)));

    // Delivered without a pause: the first applies surgically, the second
    // cannot (its base is not local yet) and must fall back to a reload.
    // The two paths must not share a coalescing slot, or one swallows the
    // other and this node silently stops at the first patch.
    setup.listener.onPatch(buildEvent(1L, 2L, h0, h1, a, patchedA,
        "leader-1"));
    setup.listener.onPatch(buildEvent(2L, 3L, h1, h2, b, patchedB,
        "leader-1"));
    Thread.sleep(600);

    assertEquals(List.of(patchedA), setup.catalog.search("by-id", "a"));
    assertEquals(List.of(patchedB), setup.catalog.search("by-id", "b"));

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
    final PatchEvent event = PatchEvent.replace("unknown-catalog",
        "leader-1", 0L, 1L, "from", "to", "by-id",
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

  @Test
  void whenInsertPatchArrives_givenMatchingPrecondition_shouldAddTheItem()
      throws InterruptedException {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Setup setup = followerSetup(List.of(a));

    final Snapshot<Event> before = setup.catalog.currentSnapshot();
    final Event added = new Event("b", new Sport("F"), new Venue("V2"));
    final EventSerializer serializer = new EventSerializer();

    setup.listener.onPatch(PatchEvent.insert("events", "leader-1",
        before.version(), before.version() + 1, before.hash(),
        hashOf(List.of(a, added)), serializer.serialize(List.of(added)),
        Instant.now()));
    Thread.sleep(300);

    assertEquals(List.of(added), setup.catalog.search("by-id", "b"));
    assertEquals(List.of(a), setup.catalog.search("by-id", "a"));
    assertEquals(2, setup.catalog.currentSnapshot().data().size());

    setup.andersoni.stop();
    verify(setup.syncStrategy, setup.leader);
  }

  @Test
  void whenRemovePatchArrives_givenMatchingPrecondition_shouldDropTheItem()
      throws InterruptedException {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Event b = new Event("b", new Sport("F"), new Venue("V2"));
    final Setup setup = followerSetup(List.of(a, b));

    final Snapshot<Event> before = setup.catalog.currentSnapshot();
    final EventSerializer serializer = new EventSerializer();

    setup.listener.onPatch(PatchEvent.remove("events", "leader-1",
        before.version(), before.version() + 1, before.hash(),
        hashOf(List.of(b)), "by-id", serializer.serialize(List.of(a)),
        Instant.now()));
    Thread.sleep(300);

    assertEquals(List.of(), setup.catalog.search("by-id", "a"));
    assertEquals(List.of(b), setup.catalog.currentSnapshot().data());

    setup.andersoni.stop();
    verify(setup.syncStrategy, setup.leader);
  }

  @Test
  void whenInsertPatchArrives_givenDriftedSnapshot_shouldFallBackToReload()
      throws InterruptedException {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Setup setup = followerSetup(List.of(a));

    final Event divergent = new Event("a", new Sport("Z"), new Venue("VZ"));
    setup.loaderData.set(new ArrayList<>(List.of(divergent)));

    final Event added = new Event("b", new Sport("F"), new Venue("V2"));
    setup.listener.onPatch(PatchEvent.insert("events", "leader-1", 0L, 1L,
        "aHashThisNodeDoesNotHave", "toHash",
        new EventSerializer().serialize(List.of(added)), Instant.now()));
    Thread.sleep(400);

    assertEquals(List.of(divergent), setup.catalog.currentSnapshot().data(),
        "A drifted follower must reload rather than append blindly");

    setup.andersoni.stop();
    verify(setup.syncStrategy, setup.leader);
  }

  @Test
  void whenBatchArrives_givenMatchingPrecondition_shouldApplyEveryOperation()
      throws InterruptedException {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Event b = new Event("b", new Sport("F"), new Venue("V2"));
    final Setup setup = followerSetup(List.of(a, b));

    final Snapshot<Event> before = setup.catalog.currentSnapshot();
    final Event movedA = new Event("a", new Sport("F"), new Venue("V9"));
    final Event c = new Event("c", new Sport("G"), new Venue("V3"));
    final EventSerializer serializer = new EventSerializer();

    setup.listener.onPatch(PatchEvent.batch("events", "leader-1",
        before.version(), before.version() + 1, before.hash(),
        hashOf(List.of(movedA, c)),
        List.of(
            new PatchEvent.Op(PatchOperation.REPLACE, "by-id",
                serializer.serialize(List.of(a)),
                serializer.serialize(List.of(movedA))),
            new PatchEvent.Op(PatchOperation.REMOVE, "by-id",
                serializer.serialize(List.of(b)), null),
            new PatchEvent.Op(PatchOperation.INSERT, null, null,
                serializer.serialize(List.of(c)))),
        Instant.now()));
    Thread.sleep(400);

    assertEquals(List.of(movedA), setup.catalog.search("by-id", "a"));
    assertEquals(List.of(), setup.catalog.search("by-id", "b"));
    assertEquals(List.of(c), setup.catalog.search("by-id", "c"));
    assertEquals(before.version() + 1,
        setup.catalog.currentSnapshot().version(),
        "A batch must land as one snapshot swap on the follower too");

    setup.andersoni.stop();
    verify(setup.syncStrategy, setup.leader);
  }

  @Test
  void whenBatchArrives_givenAnUnsatisfiableOp_shouldNotApplyAnyOfIt()
      throws InterruptedException {
    final Event a = new Event("a", new Sport("F"), new Venue("V1"));
    final Setup setup = followerSetup(List.of(a));
    setup.loaderData.set(new ArrayList<>(List.of(a)));

    final Snapshot<Event> before = setup.catalog.currentSnapshot();
    final Event c = new Event("c", new Sport("G"), new Venue("V3"));
    final Event ghost = new Event("ghost", new Sport("G"), new Venue("V4"));
    final EventSerializer serializer = new EventSerializer();

    // The insert is satisfiable, the replace is not: this follower has no
    // item under "ghost". Neither may be visible afterwards.
    setup.listener.onPatch(PatchEvent.batch("events", "leader-1",
        before.version(), before.version() + 1, before.hash(), "toHash",
        List.of(
            new PatchEvent.Op(PatchOperation.INSERT, null, null,
                serializer.serialize(List.of(c))),
            new PatchEvent.Op(PatchOperation.REPLACE, "by-id",
                serializer.serialize(List.of(ghost)),
                serializer.serialize(List.of(ghost)))),
        Instant.now()));
    Thread.sleep(400);

    assertEquals(List.of(a), setup.catalog.currentSnapshot().data(),
        "A batch that cannot be satisfied must leave no trace of the "
            + "operations that could have applied");

    setup.andersoni.stop();
    verify(setup.syncStrategy, setup.leader);
  }
}
