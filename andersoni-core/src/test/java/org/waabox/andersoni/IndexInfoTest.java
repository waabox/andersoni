package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IndexInfoTest {

  @Test
  void whenCreating_givenValidFields_shouldRetainValues() {
    final IndexInfo info = new IndexInfo("by-venue", 10, 100, 5000L);
    assertEquals("by-venue", info.name());
    assertEquals(10, info.uniqueKeys());
    assertEquals(100, info.totalEntries());
    assertEquals(5000L, info.estimatedSizeBytes());
  }

  @Test
  void whenComputingSizeMB_givenKnownBytes_shouldConvertCorrectly() {
    final long oneMB = 1024L * 1024L;
    final IndexInfo info = new IndexInfo("idx", 1, 1, oneMB);
    assertEquals(1.0, info.estimatedSizeMB(), 0.001);
  }

  @Test
  void whenComputingSizeMB_givenZeroBytes_shouldReturnZero() {
    final IndexInfo info = new IndexInfo("idx", 0, 0, 0L);
    assertEquals(0.0, info.estimatedSizeMB(), 0.001);
  }

  @Test
  void whenCreating_givenNullName_shouldThrowNpe() {
    assertThrows(NullPointerException.class,
        () -> new IndexInfo(null, 0, 0, 0L));
  }
}
