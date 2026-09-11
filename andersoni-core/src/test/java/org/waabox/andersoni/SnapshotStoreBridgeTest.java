package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.waabox.andersoni.snapshot.SerializedSnapshot;

/**
 * Tests for {@link SnapshotStoreBridge}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SnapshotStoreBridgeTest {

  private static Catalog<String> catalogWithSerializer(final List<String> data) {
    return Catalog.of(String.class)
        .named("cities")
        .data(data)
        .serializer(new LinesSerializer())
        .index("by-self").by(s -> s, Function.identity())
        .build();
  }

  private static SerializedSnapshot snapshotOf(final List<String> items,
      final long version) {
    final byte[] bytes = new LinesSerializer().serialize(items);
    return new SerializedSnapshot("cities", SnapshotStoreBridge.sha256Hex(bytes),
        version, Instant.parse("2026-09-11T10:00:00Z"), bytes);
  }

  @Test
  void whenLoading_givenStoredSnapshot_shouldRefreshCatalogAndRecordAppliedHash() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SerializedSnapshot stored = snapshotOf(List.of("Madrid", "Tokyo"), 5L);
    store.put("cities", stored);
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> catalog = catalogWithSerializer(List.of());

    final boolean loaded = bridge.load(catalog);

    assertTrue(loaded);
    assertEquals(2, catalog.currentSnapshot().data().size());
    assertEquals(stored.hash(), bridge.appliedStoreHash("cities").orElseThrow());
  }

  @Test
  void whenLoading_givenEmptyStore_shouldReturnFalseAndLeaveAppliedHashEmpty() {
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(new InMemorySnapshotStore());
    final Catalog<String> catalog = catalogWithSerializer(List.of());

    assertFalse(bridge.load(catalog));
    assertTrue(bridge.appliedStoreHash("cities").isEmpty());
  }

  @Test
  void whenSaving_givenBootstrappedCatalog_shouldStoreBytesAndRecordHashOfStoredBytes() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    final Catalog<String> catalog = catalogWithSerializer(List.of("Madrid"));
    catalog.bootstrap();

    bridge.save(catalog);

    final SerializedSnapshot saved = store.get("cities").orElseThrow();
    assertEquals(SnapshotStoreBridge.sha256Hex(saved.data()), saved.hash());
    assertEquals(saved.hash(), bridge.appliedStoreHash("cities").orElseThrow());
  }

  @Test
  void whenMarkingUnknown_givenAppliedHash_shouldClearIt() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    store.put("cities", snapshotOf(List.of("Madrid"), 1L));
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);
    bridge.load(catalogWithSerializer(List.of()));

    bridge.markUnknown("cities");

    assertTrue(bridge.appliedStoreHash("cities").isEmpty());
  }

  @Test
  void whenCheckingSupport_givenCatalogWithoutSerializer_shouldBeFalse() {
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(new InMemorySnapshotStore());
    final Catalog<String> catalog = Catalog.of(String.class)
        .named("plain")
        .data(List.of("a"))
        .index("by-self").by(s -> s, Function.identity())
        .build();

    assertFalse(bridge.supports(catalog));
  }

  @Test
  void whenUsingBridge_givenNoStore_shouldBeInertEverywhere() {
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(null);
    final Catalog<String> catalog = catalogWithSerializer(List.of("Madrid"));
    catalog.bootstrap();

    assertFalse(bridge.isConfigured());
    assertFalse(bridge.supports(catalog));
    assertFalse(bridge.load(catalog));
    assertTrue(bridge.describe("cities").isEmpty());
    bridge.save(catalog);
    assertTrue(bridge.appliedStoreHash("cities").isEmpty());
  }

  @Test
  void whenDescribing_givenStoredSnapshot_shouldReturnItsMetadata() {
    final InMemorySnapshotStore store = new InMemorySnapshotStore();
    final SerializedSnapshot stored = snapshotOf(List.of("Madrid"), 3L);
    store.put("cities", stored);
    final SnapshotStoreBridge bridge = new SnapshotStoreBridge(store);

    assertEquals(stored.hash(), bridge.describe("cities").orElseThrow().hash());
  }
}
