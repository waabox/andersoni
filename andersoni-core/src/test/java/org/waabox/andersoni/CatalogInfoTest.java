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
        "events", 50, List.of(idx1, idx2), 3500L);
    assertEquals("events", info.catalogName());
    assertEquals(50, info.itemCount());
    assertEquals(2, info.indices().size());
    assertEquals(3500L, info.totalEstimatedSizeBytes());
  }

  @Test
  void whenComputingTotalSizeMB_givenKnownBytes_shouldConvertCorrectly() {
    final long twoMB = 2L * 1024L * 1024L;
    final CatalogInfo info = new CatalogInfo("events", 10, List.of(), twoMB);
    assertEquals(2.0, info.totalEstimatedSizeMB(), 0.001);
  }

  @Test
  void whenAccessingIndices_shouldBeUnmodifiable() {
    final IndexInfo idx = new IndexInfo("by-venue", 5, 50, 2000L);
    final CatalogInfo info = new CatalogInfo("events", 50, List.of(idx), 2000L);
    assertThrows(UnsupportedOperationException.class,
        () -> info.indices().add(new IndexInfo("hack", 0, 0, 0L)));
  }

  @Test
  void whenCreating_givenNullCatalogName_shouldThrowNpe() {
    assertThrows(NullPointerException.class,
        () -> new CatalogInfo(null, 0, List.of(), 0L));
  }

  @Test
  void whenCreating_givenNullIndices_shouldThrowNpe() {
    assertThrows(NullPointerException.class,
        () -> new CatalogInfo("events", 0, null, 0L));
  }
}
