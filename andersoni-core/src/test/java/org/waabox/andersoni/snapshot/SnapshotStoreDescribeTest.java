package org.waabox.andersoni.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Tests for the default {@link SnapshotStore#describe(String)}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SnapshotStoreDescribeTest {

  /** A store that only implements the two abstract methods. */
  private static final class LoadOnlyStore implements SnapshotStore {

    private final SerializedSnapshot stored;

    private LoadOnlyStore(final SerializedSnapshot theStored) {
      stored = theStored;
    }

    @Override
    public void save(final String catalogName, final SerializedSnapshot snapshot) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<SerializedSnapshot> load(final String catalogName) {
      return Optional.ofNullable(stored);
    }
  }

  @Test
  void whenDescribing_givenDefaultImplementationAndSnapshot_shouldDelegateToLoad() {
    final Instant createdAt = Instant.parse("2026-09-11T10:00:00Z");
    final SnapshotStore store = new LoadOnlyStore(new SerializedSnapshot(
        "events", "hash-1", 3L, createdAt, new byte[] {9}));

    final Optional<SnapshotMetadata> metadata = store.describe("events");

    assertTrue(metadata.isPresent());
    assertEquals("hash-1", metadata.get().hash());
    assertEquals(3L, metadata.get().version());
    assertEquals(createdAt, metadata.get().createdAt());
  }

  @Test
  void whenDescribing_givenDefaultImplementationAndNoSnapshot_shouldReturnEmpty() {
    final SnapshotStore store = new LoadOnlyStore(null);

    assertTrue(store.describe("events").isEmpty());
  }
}
