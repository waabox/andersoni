package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

/**
 * Checks the snapshot memory estimates against the real object graph size
 * measured by JOL.
 */
class SnapshotMemoryEstimationTest {

  private static final double TOLERANCE = 0.10;

  record Event(String id, String venue, String sport, LocalDate date,
      List<String> tags, String description) {
  }

  record EventCard(String id, String venue) {
  }

  @Test
  void whenEstimating_givenMixedIndices_shouldMatchRealFootprint() throws Exception {
    final Catalog<Event> catalog = Catalog.of(Event.class)
        .named("events")
        .data(events(20_000))
        .index("by-id").by(Event::id, s -> s)
        .index("by-venue").by(Event::venue, s -> s)
        .index("by-sport").by(Event::sport, String::toUpperCase)
        .indexSorted("by-date").by(Event::date, d -> d)
        .indexSorted("by-name").by(Event::id, s -> s)
        .indexMulti("by-tags").by(Event::tags)
        .view(EventCard.class, e -> new EventCard(e.id(), e.venue()))
        .build();
    catalog.bootstrap();

    final Snapshot<Event> snapshot = catalog.currentSnapshot();
    final CatalogInfo info = catalog.info();

    final GraphLayout items = GraphLayout.parseInstance(field(snapshot, "items"));
    final long realData = items.totalSize();
    final long realTotal = GraphLayout.parseInstance(snapshot).totalSize();

    assertClose("data", realData, info.dataEstimatedSizeBytes());
    assertClose("indices", realTotal - realData, info.indexEstimatedSizeBytes());
    assertClose("total", realTotal, info.totalEstimatedSizeBytes());

    for (final IndexInfo index : info.indices()) {
      assertClose(index.name(), realIndexSize(snapshot, index.name(), items),
          index.estimatedSizeBytes());
    }
  }

  @Test
  void whenEstimating_givenSortedStringIndex_shouldIncludeBothTrees() throws Exception {
    final Catalog<Event> withSorted = Catalog.of(Event.class)
        .named("events")
        .data(events(2_000))
        .indexSorted("by-name").by(Event::id, s -> s)
        .build();
    withSorted.bootstrap();
    final Catalog<Event> hashOnly = Catalog.of(Event.class)
        .named("events")
        .data(events(2_000))
        .index("by-name").by(Event::id, s -> s)
        .build();
    hashOnly.bootstrap();

    final long sorted = withSorted.info().indexEstimatedSizeBytes();
    final long hash = hashOnly.info().indexEstimatedSizeBytes();

    assertTrue(sorted > 2 * hash,
        "sorted String index should cost more than twice a hash index, got "
            + sorted + " vs " + hash);
  }

  @Test
  void whenEstimating_givenEmptySnapshot_shouldReportZero() {
    final Snapshot<Event> snapshot = Snapshot.empty();
    assertEquals(0L, snapshot.estimatedDataSizeBytes());
    assertTrue(snapshot.indexInfo().isEmpty());
  }

  private static long realIndexSize(final Snapshot<Event> snapshot,
      final String name, final GraphLayout items) throws Exception {
    GraphLayout layout = GraphLayout.parseInstance(
        ((Map<?, ?>) field(snapshot, "indices")).get(name));
    for (final String tree : List.of("sortedIndices", "reversedKeyIndices")) {
      final Object structure = ((Map<?, ?>) field(snapshot, tree)).get(name);
      if (structure != null) {
        layout = layout.add(GraphLayout.parseInstance(structure));
      }
    }
    return layout.subtract(items).totalSize();
  }

  private static void assertClose(final String what, final long real,
      final long estimated) {
    final double ratio = (double) estimated / real;
    assertTrue(Math.abs(ratio - 1.0) <= TOLERANCE,
        what + ": estimated " + estimated + " bytes, real " + real
            + " bytes (ratio " + String.format("%.2f", ratio) + ")");
  }

  private static Object field(final Object target, final String name)
      throws Exception {
    final Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static List<Event> events(final int count) {
    final Random random = new Random(42);
    final String[] venues = new String[200];
    for (int i = 0; i < venues.length; i++) {
      venues[i] = "Venue number " + i;
    }
    final String[] sports = {"football", "basketball", "tennis", "rugby"};
    final List<Event> events = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      events.add(new Event(
          new UUID(random.nextLong(), random.nextLong()).toString(),
          venues[random.nextInt(venues.length)],
          sports[random.nextInt(sports.length)],
          LocalDate.of(2026, 1, 1).plusDays(random.nextInt(365)),
          List.of("tag" + random.nextInt(50), "tag" + random.nextInt(50)),
          "Description of event " + i));
    }
    return events;
  }
}
