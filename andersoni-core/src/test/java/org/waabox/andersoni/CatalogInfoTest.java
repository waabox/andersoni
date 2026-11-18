package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class CatalogInfoTest {

  @Test
  void whenCreating_givenValidFields_shouldRetainValues() {
    final IndexInfo idx1 = new IndexInfo("by-venue", 5, 50, 2000L);
    final IndexInfo idx2 = new IndexInfo("by-sport", 3, 50, 1500L);
    final CatalogInfo info = new CatalogInfo(
        "events", 50, List.of(idx1, idx2), 0L, 3500L, 0, List.of());
    assertEquals("events", info.catalogName());
    assertEquals(50, info.itemCount());
    assertEquals(2, info.indices().size());
    assertEquals(3500L, info.totalEstimatedSizeBytes());
    assertEquals(0, info.viewCount());
    assertEquals(List.of(), info.viewTypeNames());
  }

  @Test
  void whenComputingTotalSize_givenDataAndIndexSizes_shouldAddThem() {
    final CatalogInfo info = new CatalogInfo(
        "events", 10, List.of(), 3000L, 500L, 0, List.of());
    assertEquals(3000L, info.dataEstimatedSizeBytes());
    assertEquals(500L, info.indexEstimatedSizeBytes());
    assertEquals(3500L, info.totalEstimatedSizeBytes());
  }

  @Test
  @SuppressWarnings("deprecation")
  void whenCreating_givenLegacySingleSize_shouldTreatItAsIndexSize() {
    final CatalogInfo info = new CatalogInfo(
        "events", 10, List.of(), 3500L, 0, List.of());
    assertEquals(0L, info.dataEstimatedSizeBytes());
    assertEquals(3500L, info.indexEstimatedSizeBytes());
    assertEquals(3500L, info.totalEstimatedSizeBytes());
  }

  @Test
  void whenComputingTotalSizeMB_givenKnownBytes_shouldConvertCorrectly() {
    final long twoMB = 2L * 1024L * 1024L;
    final CatalogInfo info = new CatalogInfo("events", 10, List.of(), 0L, twoMB, 0, List.of());
    assertEquals(2.0, info.totalEstimatedSizeMB(), 0.001);
  }

  @Test
  void whenAccessingIndices_shouldBeUnmodifiable() {
    final IndexInfo idx = new IndexInfo("by-venue", 5, 50, 2000L);
    final CatalogInfo info = new CatalogInfo("events", 50, List.of(idx), 0L, 2000L, 0, List.of());
    assertThrows(UnsupportedOperationException.class,
        () -> info.indices().add(new IndexInfo("hack", 0, 0, 0L)));
  }

  @Test
  void whenAccessingViewTypeNames_shouldBeUnmodifiable() {
    final CatalogInfo info = new CatalogInfo(
        "events", 0, List.of(), 0L, 0L, 1, List.of("EventSummary"));
    assertThrows(UnsupportedOperationException.class,
        () -> info.viewTypeNames().add("hack"));
  }

  @Test
  void whenCreating_givenViewMetadata_shouldRetainValues() {
    final CatalogInfo info = new CatalogInfo(
        "events", 10, List.of(), 0L, 0L, 2, List.of("EventSummary", "EventCard"));
    assertEquals(2, info.viewCount());
    assertEquals(List.of("EventSummary", "EventCard"), info.viewTypeNames());
  }

  @Test
  void whenCreating_givenNullCatalogName_shouldThrowNpe() {
    assertThrows(NullPointerException.class,
        () -> new CatalogInfo(null, 0, List.of(), 0L, 0L, 0, List.of()));
  }

  @Test
  void whenCreating_givenNullIndices_shouldThrowNpe() {
    assertThrows(NullPointerException.class,
        () -> new CatalogInfo("events", 0, null, 0L, 0L, 0, List.of()));
  }

  @Test
  void whenCreating_givenNullViewTypeNames_shouldThrowNpe() {
    assertThrows(NullPointerException.class,
        () -> new CatalogInfo("events", 0, List.of(), 0L, 0L, 0, null));
  }
}
