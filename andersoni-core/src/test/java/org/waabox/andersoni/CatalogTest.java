package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.waabox.andersoni.snapshot.SnapshotSerializer;

/**
 * Tests for {@link Catalog}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class CatalogTest {

  /** A sport domain object used for testing. */
  record Sport(String name) {}

  /** A venue domain object used for testing. */
  record Venue(String name) {}

  /** An event domain object used for testing. */
  record Event(String id, Sport sport, Venue venue) {}

  /** A simple serializer for testing purposes. */
  static final class TestSerializer implements SnapshotSerializer<Event> {

    @Override
    public byte[] serialize(final List<Event> items) {
      final StringBuilder sb = new StringBuilder();
      for (final Event e : items) {
        sb.append(e.id()).append(",");
      }
      return sb.toString().getBytes();
    }

    @Override
    public List<Event> deserialize(final byte[] data) {
      return List.of();
    }
  }

  @Test
  void whenBuilding_givenDataLoaderAndIndices_shouldBuildCorrectly() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of())
        .index("by-venue").by(Event::venue, Venue::name)
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    assertEquals("events", catalog.name());
    assertTrue(catalog.hasDataLoader());
  }

  @Test
  void whenBootstrapping_givenDataLoader_shouldPopulateIndices() {
    final Venue maracana = new Venue("Maracana");
    final Venue wembley = new Venue("Wembley");
    final Sport football = new Sport("Football");
    final Sport rugby = new Sport("Rugby");

    final Event e1 = new Event("1", football, maracana);
    final Event e2 = new Event("2", rugby, wembley);
    final Event e3 = new Event("3", football, maracana);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of(e1, e2, e3))
        .index("by-venue").by(Event::venue, Venue::name)
        .index("by-sport").by(Event::sport, Sport::name)
        .build();

    catalog.bootstrap();

    final List<Event> maracanaEvents = catalog.search("by-venue", "Maracana");
    assertEquals(2, maracanaEvents.size());
    assertTrue(maracanaEvents.contains(e1));
    assertTrue(maracanaEvents.contains(e3));

    final List<Event> footballEvents = catalog.search("by-sport", "Football");
    assertEquals(2, footballEvents.size());
    assertTrue(footballEvents.contains(e1));
    assertTrue(footballEvents.contains(e3));

    final List<Event> rugbyEvents = catalog.search("by-sport", "Rugby");
    assertEquals(1, rugbyEvents.size());
    assertTrue(rugbyEvents.contains(e2));

    final Snapshot<Event> snapshot = catalog.currentSnapshot();
    assertEquals(1L, snapshot.version());
    assertNotNull(snapshot.hash());
    assertFalse(snapshot.hash().isEmpty());
  }

  @Test
  void whenBootstrapping_givenStaticData_shouldPopulateIndices() {
    final Venue maracana = new Venue("Maracana");
    final Venue wembley = new Venue("Wembley");
    final Sport football = new Sport("Football");
    final Sport rugby = new Sport("Rugby");

    final Event e1 = new Event("1", football, maracana);
    final Event e2 = new Event("2", rugby, wembley);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1, e2))
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    catalog.bootstrap();

    final List<Event> maracanaEvents = catalog.search("by-venue", "Maracana");
    assertEquals(1, maracanaEvents.size());
    assertTrue(maracanaEvents.contains(e1));

    final List<Event> wembleyEvents = catalog.search("by-venue", "Wembley");
    assertEquals(1, wembleyEvents.size());
    assertTrue(wembleyEvents.contains(e2));

    assertFalse(catalog.hasDataLoader());
  }

  @Test
  void whenRefreshing_givenNewData_shouldAtomicallySwapSnapshot() {
    final Venue maracana = new Venue("Maracana");
    final Venue wembley = new Venue("Wembley");
    final Sport football = new Sport("Football");
    final Sport rugby = new Sport("Rugby");

    final Event e1 = new Event("1", football, maracana);
    final Event e2 = new Event("2", rugby, wembley);
    final Event e3 = new Event("3", rugby, maracana);

    final List<List<Event>> dataSets = new ArrayList<>();
    dataSets.add(List.of(e1, e2));
    dataSets.add(List.of(e1, e2, e3));

    final int[] callCount = {0};

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> {
          final List<Event> result = dataSets.get(callCount[0]);
          callCount[0]++;
          return result;
        })
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    catalog.bootstrap();

    final Snapshot<Event> firstSnapshot = catalog.currentSnapshot();
    assertEquals(1L, firstSnapshot.version());
    assertEquals(2, firstSnapshot.data().size());

    catalog.refresh();

    final Snapshot<Event> secondSnapshot = catalog.currentSnapshot();
    assertEquals(2L, secondSnapshot.version());
    assertEquals(3, secondSnapshot.data().size());
    assertNotEquals(firstSnapshot.hash(), secondSnapshot.hash());

    final List<Event> maracanaEvents = catalog.search("by-venue", "Maracana");
    assertEquals(2, maracanaEvents.size());
    assertTrue(maracanaEvents.contains(e1));
    assertTrue(maracanaEvents.contains(e3));
  }

  @Test
  void whenRefreshing_givenStaticDataAndNoArgs_shouldThrowIllegalState() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    catalog.bootstrap();

    assertThrows(IllegalStateException.class, catalog::refresh);
  }

  @Test
  void whenRefreshing_givenStaticData_shouldAcceptNewData() {
    final Venue maracana = new Venue("Maracana");
    final Venue wembley = new Venue("Wembley");
    final Sport football = new Sport("Football");
    final Sport rugby = new Sport("Rugby");

    final Event e1 = new Event("1", football, maracana);
    final Event e2 = new Event("2", rugby, wembley);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    catalog.bootstrap();

    assertEquals(1, catalog.currentSnapshot().data().size());

    catalog.refresh(List.of(e1, e2));

    assertEquals(2, catalog.currentSnapshot().data().size());

    final List<Event> wembleyEvents = catalog.search("by-venue", "Wembley");
    assertEquals(1, wembleyEvents.size());
    assertTrue(wembleyEvents.contains(e2));

    assertEquals(2L, catalog.currentSnapshot().version());
  }

  @Test
  void whenSearching_givenMissingKey_shouldReturnEmptyList() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    catalog.bootstrap();

    final List<Event> result = catalog.search("by-venue", "NonExistent");
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenSearching_givenNotBootstrapped_shouldReturnEmptyList() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of())
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    final List<Event> result = catalog.search("by-venue", "Maracana");
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenBuilding_givenSerializer_shouldBeAvailable() {
    final TestSerializer serializer = new TestSerializer();

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of())
        .serializer(serializer)
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    final Optional<SnapshotSerializer<Event>> result = catalog.serializer();
    assertTrue(result.isPresent());
    assertEquals(serializer, result.get());
  }

  @Test
  void whenBuilding_givenRefreshInterval_shouldBeAvailable() {
    final Duration interval = Duration.ofMinutes(5);

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of())
        .refreshEvery(interval)
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    final Optional<Duration> result = catalog.refreshInterval();
    assertTrue(result.isPresent());
    assertEquals(interval, result.get());
  }

  @Test
  void whenBuilding_givenNoSerializer_shouldReturnEmpty() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of())
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    assertTrue(catalog.serializer().isEmpty());
  }

  @Test
  void whenBuilding_givenNoRefreshInterval_shouldReturnEmpty() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> List.of())
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    assertTrue(catalog.refreshInterval().isEmpty());
  }

  @Test
  void whenBootstrapping_givenSerializer_shouldUseItForHash() {
    final Venue maracana = new Venue("Maracana");
    final Sport football = new Sport("Football");
    final Event e1 = new Event("1", football, maracana);

    final TestSerializer serializer = new TestSerializer();

    final Catalog<Event> catalogWithSerializer = Catalog.of(Event.class)
        .named("with-serializer")
        .data(List.of(e1))
        .serializer(serializer)
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    final Catalog<Event> catalogWithout = Catalog.of(Event.class)
        .named("without-serializer")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    catalogWithSerializer.bootstrap();
    catalogWithout.bootstrap();

    // Both should produce non-empty hashes, but they may differ because
    // the hash inputs are different (serialized bytes vs toString).
    assertNotNull(catalogWithSerializer.currentSnapshot().hash());
    assertFalse(catalogWithSerializer.currentSnapshot().hash().isEmpty());
    assertNotNull(catalogWithout.currentSnapshot().hash());
    assertFalse(catalogWithout.currentSnapshot().hash().isEmpty());
  }

  // --- Corner case tests ---

  @Test
  void whenBuilding_givenNullType_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        Catalog.of(null)
    );
  }

  @Test
  void whenBuilding_givenNullName_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        Catalog.of(Event.class).named(null)
    );
  }

  @Test
  void whenBuilding_givenEmptyName_shouldThrowIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () ->
        Catalog.of(Event.class).named("")
    );
  }

  @Test
  void whenBuilding_givenNullDataLoader_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        Catalog.of(Event.class)
            .named("events")
            .loadWith(null)
    );
  }

  @Test
  void whenBuilding_givenNullStaticData_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        Catalog.of(Event.class)
            .named("events")
            .data(null)
    );
  }

  @Test
  void whenBuilding_givenNoIndices_shouldThrowIllegalState() {
    assertThrows(IllegalStateException.class, () ->
        Catalog.of(Event.class)
            .named("events")
            .data(List.of())
            .build()
    );
  }

  @Test
  void whenBuilding_givenNullSerializer_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        Catalog.of(Event.class)
            .named("events")
            .data(List.of())
            .serializer(null)
    );
  }

  @Test
  void whenBuilding_givenNullRefreshInterval_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        Catalog.of(Event.class)
            .named("events")
            .data(List.of())
            .refreshEvery(null)
    );
  }

  @Test
  void whenBuilding_givenNullIndexName_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        Catalog.of(Event.class)
            .named("events")
            .data(List.of())
            .index(null)
    );
  }

  @Test
  void whenBuilding_givenEmptyIndexName_shouldThrowIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () ->
        Catalog.of(Event.class)
            .named("events")
            .data(List.of())
            .index("")
    );
  }

  @Test
  void whenBootstrapping_givenDataLoaderReturnsNull_shouldThrowNpeWithMessage() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> null)
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    final NullPointerException exception = assertThrows(
        NullPointerException.class, catalog::bootstrap);
    assertTrue(exception.getMessage().contains("events"),
        "Exception message should contain the catalog name");
  }

  @Test
  void whenBootstrapping_givenDataLoaderThrows_shouldPreservePreviousState() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));

    final boolean[] shouldThrow = {false};

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .loadWith(() -> {
          if (shouldThrow[0]) {
            throw new RuntimeException("DataLoader failure");
          }
          return List.of(e1);
        })
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    catalog.bootstrap();
    assertEquals(1L, catalog.currentSnapshot().version());
    assertEquals(1, catalog.currentSnapshot().data().size());

    shouldThrow[0] = true;

    assertThrows(RuntimeException.class, catalog::refresh);

    assertEquals(1L, catalog.currentSnapshot().version(),
        "Version should remain unchanged after failed refresh");
    assertEquals(1, catalog.currentSnapshot().data().size(),
        "Data should remain unchanged after failed refresh");
  }

  @Test
  void whenRefreshing_givenNullData_shouldThrowNpe() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(new Event("1", new Sport("Football"),
            new Venue("Maracana"))))
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    catalog.bootstrap();

    assertThrows(NullPointerException.class, () ->
        catalog.refresh(null)
    );
  }

  @Test
  void whenRefreshing_givenEmptyData_shouldSwapToEmptySnapshot() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of(e1))
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    catalog.bootstrap();
    assertEquals(1, catalog.currentSnapshot().data().size());

    catalog.refresh(List.of());

    assertEquals(2L, catalog.currentSnapshot().version());
    assertTrue(catalog.currentSnapshot().data().isEmpty());
    assertTrue(catalog.search("by-venue", "Maracana").isEmpty());
  }

  @Test
  void whenBuilding_givenStaticData_shouldDefensivelyCopy() {
    final Event e1 = new Event("1", new Sport("Football"),
        new Venue("Maracana"));

    final List<Event> mutableData = new ArrayList<>(List.of(e1));

    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(mutableData)
        .index("by-venue").by(Event::venue, Venue::name)
        .build();

    mutableData.add(new Event("2", new Sport("Rugby"),
        new Venue("Wembley")));

    catalog.bootstrap();

    assertEquals(1, catalog.currentSnapshot().data().size(),
        "Mutating the original list should not affect the catalog");
  }

  @Test
  void whenBuilding_givenDuplicateIndexName_shouldThrowIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () ->
        Catalog.of(Event.class)
            .named("events")
            .data(List.of())
            .index("by-venue").by(Event::venue, Venue::name)
            .index("by-venue").by(Event::sport, Sport::name)
            .build()
    );
  }

  @Test
  void whenSearching_givenNullIndexName_shouldThrowNpe() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of())
        .index("by-venue").by(Event::venue, Venue::name)
        .build();
    catalog.bootstrap();

    assertThrows(NullPointerException.class,
        () -> catalog.search(null, "key"));
  }

  @Test
  void whenSearching_givenNullKey_shouldThrowNpe() {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(List.of())
        .index("by-venue").by(Event::venue, Venue::name)
        .build();
    catalog.bootstrap();

    assertThrows(NullPointerException.class,
        () -> catalog.search("by-venue", null));
  }
}
