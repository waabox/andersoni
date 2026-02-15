package org.waabox.andersoni.snapshot.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.waabox.andersoni.snapshot.SerializedSnapshot;

/**
 * Tests for {@link FileSystemSnapshotStore}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class FileSystemSnapshotStoreTest {

  @Test
  void whenSavingAndLoading_givenValidSnapshot_shouldReturnEqualSnapshot(
      @TempDir final Path tempDir) {

    final FileSystemSnapshotStore store =
        new FileSystemSnapshotStore(tempDir);

    final byte[] data = "hello-world-data".getBytes();
    final Instant createdAt = Instant.parse("2026-01-15T10:30:00Z");

    final SerializedSnapshot original = new SerializedSnapshot(
        "events", "abc123hash", 42L, createdAt, data);

    store.save("events", original);

    final Optional<SerializedSnapshot> loaded = store.load("events");

    assertTrue(loaded.isPresent(), "Loaded snapshot should be present");

    final SerializedSnapshot result = loaded.get();
    assertEquals("events", result.catalogName());
    assertEquals("abc123hash", result.hash());
    assertEquals(42L, result.version());
    assertEquals(createdAt, result.createdAt());
    assertArrayEquals(data, result.data());
  }

  @Test
  void whenLoading_givenNonExistentCatalog_shouldReturnEmpty(
      @TempDir final Path tempDir) {

    final FileSystemSnapshotStore store =
        new FileSystemSnapshotStore(tempDir);

    final Optional<SerializedSnapshot> loaded =
        store.load("non-existent-catalog");

    assertTrue(loaded.isEmpty(),
        "Loading a non-existent catalog should return empty");
  }

  @Test
  void whenSaving_givenExistingSnapshot_shouldOverwrite(
      @TempDir final Path tempDir) {

    final FileSystemSnapshotStore store =
        new FileSystemSnapshotStore(tempDir);

    final Instant firstCreatedAt = Instant.parse("2026-01-15T10:00:00Z");
    final SerializedSnapshot first = new SerializedSnapshot(
        "events", "hash-v1", 1L, firstCreatedAt, "first-data".getBytes());

    store.save("events", first);

    final Instant secondCreatedAt = Instant.parse("2026-01-15T11:00:00Z");
    final byte[] secondData = "second-data-updated".getBytes();
    final SerializedSnapshot second = new SerializedSnapshot(
        "events", "hash-v2", 2L, secondCreatedAt, secondData);

    store.save("events", second);

    final Optional<SerializedSnapshot> loaded = store.load("events");

    assertTrue(loaded.isPresent(), "Loaded snapshot should be present");

    final SerializedSnapshot result = loaded.get();
    assertEquals("events", result.catalogName());
    assertEquals("hash-v2", result.hash());
    assertEquals(2L, result.version());
    assertEquals(secondCreatedAt, result.createdAt());
    assertArrayEquals(secondData, result.data());
  }
}
