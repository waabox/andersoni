package org.waabox.andersoni.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SnapshotMetadata}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SnapshotMetadataTest {

  @Test
  void whenCreatingFromSnapshot_givenSerializedSnapshot_shouldCopyMetadataOnly() {
    final Instant createdAt = Instant.parse("2026-09-11T10:00:00Z");
    final SerializedSnapshot snapshot = new SerializedSnapshot(
        "events", "abc", 7L, createdAt, new byte[] {1, 2, 3});

    final SnapshotMetadata metadata = SnapshotMetadata.of(snapshot);

    assertEquals("events", metadata.catalogName());
    assertEquals("abc", metadata.hash());
    assertEquals(7L, metadata.version());
    assertEquals(createdAt, metadata.createdAt());
  }

  @Test
  void whenCreating_givenNullHash_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        new SnapshotMetadata("events", null, 1L, Instant.EPOCH));
  }

  @Test
  void whenCreatingFromSnapshot_givenNull_shouldThrow() {
    assertThrows(NullPointerException.class, () -> SnapshotMetadata.of(null));
  }
}
