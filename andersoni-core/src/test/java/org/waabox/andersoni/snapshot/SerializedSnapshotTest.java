package org.waabox.andersoni.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SerializedSnapshot}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SerializedSnapshotTest {

  @Test
  void whenCreating_givenValidParams_shouldRetainValues() {
    final Instant now = Instant.now();
    final byte[] data = new byte[]{1, 2, 3};

    final SerializedSnapshot snapshot = new SerializedSnapshot(
        "events", "hash-1", 1L, now, data);

    assertEquals("events", snapshot.catalogName());
    assertEquals("hash-1", snapshot.hash());
    assertEquals(1L, snapshot.version());
    assertEquals(now, snapshot.createdAt());
    assertArrayEquals(new byte[]{1, 2, 3}, snapshot.data());
  }

  @Test
  void whenCreating_givenByteArray_shouldDefensivelyCopy() {
    final byte[] original = new byte[]{1, 2, 3};

    final SerializedSnapshot snapshot = new SerializedSnapshot(
        "events", "hash-1", 1L, Instant.now(), original);

    original[0] = 99;

    assertArrayEquals(new byte[]{1, 2, 3}, snapshot.data(),
        "Mutating the original array should not affect the snapshot");
  }

  @Test
  void whenAccessingData_shouldReturnDefensiveCopy() {
    final SerializedSnapshot snapshot = new SerializedSnapshot(
        "events", "hash-1", 1L, Instant.now(), new byte[]{1, 2, 3});

    final byte[] first = snapshot.data();
    final byte[] second = snapshot.data();

    assertNotSame(first, second,
        "Each call to data() should return a new copy");

    first[0] = 99;

    assertArrayEquals(new byte[]{1, 2, 3}, snapshot.data(),
        "Mutating returned data should not affect the snapshot");
  }

  @Test
  void whenComparing_givenSameContent_shouldBeEqual() {
    final Instant now = Instant.now();

    final SerializedSnapshot s1 = new SerializedSnapshot(
        "events", "hash-1", 1L, now, new byte[]{1, 2, 3});
    final SerializedSnapshot s2 = new SerializedSnapshot(
        "events", "hash-1", 1L, now, new byte[]{1, 2, 3});

    assertEquals(s1, s2, "Snapshots with identical content should be equal");
    assertEquals(s1.hashCode(), s2.hashCode(),
        "Equal snapshots must have the same hash code");
  }

  @Test
  void whenComparing_givenDifferentData_shouldNotBeEqual() {
    final Instant now = Instant.now();

    final SerializedSnapshot s1 = new SerializedSnapshot(
        "events", "hash-1", 1L, now, new byte[]{1, 2, 3});
    final SerializedSnapshot s2 = new SerializedSnapshot(
        "events", "hash-1", 1L, now, new byte[]{4, 5, 6});

    assertNotEquals(s1, s2,
        "Snapshots with different data should not be equal");
  }

  @Test
  void whenCreating_givenNullCatalogName_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        new SerializedSnapshot(null, "hash", 1L, Instant.now(),
            new byte[]{1}));
  }

  @Test
  void whenCreating_givenNullHash_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        new SerializedSnapshot("events", null, 1L, Instant.now(),
            new byte[]{1}));
  }

  @Test
  void whenCreating_givenNullCreatedAt_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        new SerializedSnapshot("events", "hash", 1L, null,
            new byte[]{1}));
  }

  @Test
  void whenCreating_givenNullData_shouldThrowNpe() {
    assertThrows(NullPointerException.class, () ->
        new SerializedSnapshot("events", "hash", 1L, Instant.now(),
            null));
  }
}
