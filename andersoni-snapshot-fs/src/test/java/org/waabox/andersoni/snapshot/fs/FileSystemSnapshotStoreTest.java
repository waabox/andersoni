package org.waabox.andersoni.snapshot.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
  void whenSaving_givenTraversalCatalogName_shouldThrow(
      @TempDir final Path tempDir) {
    final FileSystemSnapshotStore store =
        new FileSystemSnapshotStore(tempDir);
    final SerializedSnapshot snapshot = new SerializedSnapshot(
        "x", "h", 1L, Instant.parse("2026-01-15T10:30:00Z"),
        "data".getBytes());

    assertThrows(IllegalArgumentException.class, () ->
        store.save("../escape", snapshot));
    assertThrows(IllegalArgumentException.class, () ->
        store.load("../escape"));
    assertThrows(IllegalArgumentException.class, () ->
        store.load(".."));
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

  @Test
  void whenLoading_givenLegacyTwoFileLayout_shouldStillReadSnapshot(
      @TempDir final Path tempDir) throws Exception {

    final Path catalogDir = tempDir.resolve("events");
    Files.createDirectories(catalogDir);
    Files.write(catalogDir.resolve("snapshot.dat"), "legacy-data".getBytes());
    Files.writeString(catalogDir.resolve("snapshot.meta"),
        "hash=legacy-hash\nversion=7\ncreatedAt=2026-01-15T10:30:00Z\n");

    final FileSystemSnapshotStore store =
        new FileSystemSnapshotStore(tempDir);

    final Optional<SerializedSnapshot> loaded = store.load("events");

    assertTrue(loaded.isPresent(),
        "A snapshot written by an earlier version must remain readable");
    assertEquals("legacy-hash", loaded.get().hash());
    assertEquals(7L, loaded.get().version());
    assertArrayEquals("legacy-data".getBytes(), loaded.get().data());
  }

  @Test
  void whenSaving_givenLegacyTwoFileLayout_shouldMigrateToSingleFile(
      @TempDir final Path tempDir) throws Exception {

    final Path catalogDir = tempDir.resolve("events");
    Files.createDirectories(catalogDir);
    Files.write(catalogDir.resolve("snapshot.dat"), "legacy-data".getBytes());
    Files.writeString(catalogDir.resolve("snapshot.meta"),
        "hash=legacy-hash\nversion=7\ncreatedAt=2026-01-15T10:30:00Z\n");

    final FileSystemSnapshotStore store =
        new FileSystemSnapshotStore(tempDir);

    store.save("events", new SerializedSnapshot("events", "new-hash", 8L,
        Instant.parse("2026-01-16T10:30:00Z"), "new-data".getBytes()));

    assertTrue(Files.exists(catalogDir.resolve("snapshot.bin")));
    assertTrue(Files.notExists(catalogDir.resolve("snapshot.dat")),
        "The superseded legacy data file must be removed");
    assertTrue(Files.notExists(catalogDir.resolve("snapshot.meta")),
        "The superseded legacy metadata file must be removed");

    final Optional<SerializedSnapshot> loaded = store.load("events");
    assertEquals("new-hash", loaded.orElseThrow().hash());
    assertArrayEquals("new-data".getBytes(), loaded.get().data());
  }

  @Test
  void whenLoadingDuringConcurrentSaves_shouldNeverMixDataAndMetadata(
      @TempDir final Path tempDir) throws Exception {

    final FileSystemSnapshotStore store =
        new FileSystemSnapshotStore(tempDir);

    final byte[] dataV1 = "v1-payload".repeat(500).getBytes();
    final byte[] dataV2 = "v2-different-payload".repeat(500).getBytes();

    final SerializedSnapshot v1 = new SerializedSnapshot("events",
        "hash-v1", 1L, Instant.parse("2026-01-15T10:00:00Z"), dataV1);
    final SerializedSnapshot v2 = new SerializedSnapshot("events",
        "hash-v2", 2L, Instant.parse("2026-01-15T11:00:00Z"), dataV2);

    store.save("events", v1);

    final AtomicBoolean running = new AtomicBoolean(true);
    final AtomicReference<String> mismatch = new AtomicReference<>();

    final Thread writer = new Thread(() -> {
      int i = 0;
      while (running.get()) {
        store.save("events", i++ % 2 == 0 ? v2 : v1);
      }
    });

    final Thread reader = new Thread(() -> {
      while (running.get()) {
        final Optional<SerializedSnapshot> loaded = store.load("events");
        if (loaded.isEmpty()) {
          continue;
        }
        final SerializedSnapshot read = loaded.get();
        final byte[] expected =
            "hash-v1".equals(read.hash()) ? dataV1 : dataV2;
        if (!Arrays.equals(expected, read.data())) {
          mismatch.compareAndSet(null, "metadata says " + read.hash()
              + " but the data bytes belong to the other snapshot");
          running.set(false);
        }
      }
    });

    writer.start();
    reader.start();
    Thread.sleep(2000);
    running.set(false);
    writer.join(5000);
    reader.join(5000);

    assertNull(mismatch.get(),
        "A concurrent reader must never observe one snapshot's data paired "
            + "with another snapshot's metadata: " + mismatch.get());
  }
}
