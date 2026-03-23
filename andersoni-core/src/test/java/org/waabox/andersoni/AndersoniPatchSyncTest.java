package org.waabox.andersoni;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.easymock.Capture;
import org.junit.jupiter.api.Test;
import org.waabox.andersoni.snapshot.SnapshotSerializer;
import org.waabox.andersoni.sync.PatchEvent;
import org.waabox.andersoni.sync.SyncEvent;
import org.waabox.andersoni.sync.SyncListener;
import org.waabox.andersoni.sync.SyncStrategy;

class AndersoniPatchSyncTest {

  record Item(String id, String value) {}

  static final class ItemSerializer implements SnapshotSerializer<Item> {
    @Override
    public byte[] serialize(final List<Item> items) {
      final StringBuilder sb = new StringBuilder();
      for (final Item i : items) {
        sb.append(i.id()).append(":").append(i.value()).append(";");
      }
      return sb.toString().getBytes();
    }

    @Override
    public List<Item> deserialize(final byte[] data) {
      final String str = new String(data);
      return java.util.Arrays.stream(str.split(";"))
          .filter(s -> !s.isEmpty())
          .map(s -> {
            final String[] parts = s.split(":");
            return new Item(parts[0], parts[1]);
          })
          .toList();
    }
  }

  private Catalog<Item> buildCatalog(final List<Item> data) {
    return Catalog.of(Item.class)
        .named("items")
        .data(data)
        .identifiedBy(Item::id)
        .serializer(new ItemSerializer())
        .index("by-value").by(Item::value)
        .build();
  }

  @Test
  void whenAddAndSync_givenSyncStrategy_shouldPublishPatchEvent() {
    final SyncStrategy sync = createMock(SyncStrategy.class);
    final Capture<SyncEvent> captured = newCapture();

    sync.subscribe(anyObject(SyncListener.class));
    expectLastCall();
    sync.start();
    expectLastCall();
    sync.publish(capture(captured));
    expectLastCall();
    sync.stop();
    expectLastCall();
    replay(sync);

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("test-node")
        .syncStrategy(sync)
        .build();
    andersoni.register(buildCatalog(List.of(new Item("1", "a"))));
    andersoni.start();

    andersoni.addAndSync("items", new Item("2", "b"));

    assertTrue(captured.hasCaptured());
    final PatchEvent event = (PatchEvent) captured.getValue();
    assertEquals("items", event.catalogName());
    assertEquals(PatchOperation.ADD, event.operationType());
    assertEquals("test-node", event.sourceNodeId());

    andersoni.stop();
    verify(sync);
  }

  @Test
  void whenAddAndSync_givenNoSyncStrategy_shouldWorkLocally() {
    final Andersoni andersoni = Andersoni.builder()
        .nodeId("test-node")
        .build();
    andersoni.register(buildCatalog(List.of(new Item("1", "a"))));
    andersoni.start();

    andersoni.addAndSync("items", new Item("2", "b"));

    final Catalog<?> catalog = andersoni.catalog("items");
    assertEquals(2, catalog.currentSnapshot().data().size());

    andersoni.stop();
  }

  @Test
  void whenBootstrapping_givenIdentityAndSyncButNoSerializer_shouldFail() {
    final SyncStrategy sync = createMock(SyncStrategy.class);
    sync.subscribe(anyObject(SyncListener.class));
    expectLastCall();
    sync.start();
    expectLastCall();
    replay(sync);

    final Catalog<Item> catalog = Catalog.of(Item.class)
        .named("items")
        .data(List.of(new Item("1", "a")))
        .identifiedBy(Item::id)
        .index("by-value").by(Item::value)
        .build();

    final Andersoni andersoni = Andersoni.builder()
        .nodeId("test-node")
        .syncStrategy(sync)
        .build();
    andersoni.register(catalog);

    assertThrows(IllegalStateException.class, andersoni::start);
  }
}
