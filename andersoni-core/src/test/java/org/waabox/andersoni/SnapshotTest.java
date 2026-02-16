package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Snapshot}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SnapshotTest {

  @Test
  void whenCreating_givenDataAndIndices_shouldRetainImmutableState() {
    final List<String> data = List.of("alpha", "beta", "gamma");

    final Map<Object, List<String>> byLength = new HashMap<>();
    byLength.put(4, List.of("beta"));
    byLength.put(5, List.of("alpha", "gamma"));

    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("byLength", byLength);

    final Snapshot<String> snapshot = Snapshot.of(data, indices, 1L, "abc123");

    assertEquals(3, snapshot.data().size());
    assertTrue(snapshot.data().contains("alpha"));
    assertTrue(snapshot.data().contains("beta"));
    assertTrue(snapshot.data().contains("gamma"));
    assertEquals(1L, snapshot.version());
    assertEquals("abc123", snapshot.hash());
    assertNotNull(snapshot.createdAt());
  }

  @Test
  void whenSearchingIndex_givenExistingKey_shouldReturnMatches() {
    final List<String> data = List.of("alpha", "beta", "gamma");

    final Map<Object, List<String>> byLength = new HashMap<>();
    byLength.put(4, List.of("beta"));
    byLength.put(5, List.of("alpha", "gamma"));

    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("byLength", byLength);

    final Snapshot<String> snapshot = Snapshot.of(data, indices, 1L, "abc123");

    final List<String> result = snapshot.search("byLength", 5);
    assertEquals(2, result.size());
    assertTrue(result.contains("alpha"));
    assertTrue(result.contains("gamma"));

    final List<String> singleResult = snapshot.search("byLength", 4);
    assertEquals(1, singleResult.size());
    assertTrue(singleResult.contains("beta"));
  }

  @Test
  void whenSearchingIndex_givenMissingKey_shouldReturnEmptyList() {
    final List<String> data = List.of("alpha", "beta");

    final Map<Object, List<String>> byLength = new HashMap<>();
    byLength.put(5, List.of("alpha"));

    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("byLength", byLength);

    final Snapshot<String> snapshot = Snapshot.of(data, indices, 1L, "hash");

    final List<String> result = snapshot.search("byLength", 999);
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenSearchingIndex_givenMissingIndex_shouldReturnEmptyList() {
    final List<String> data = List.of("alpha");

    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();

    final Snapshot<String> snapshot = Snapshot.of(data, indices, 1L, "hash");

    final List<String> result = snapshot.search("nonExistent", "key");
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenAccessingData_shouldBeUnmodifiable() {
    final List<String> data = List.of("alpha", "beta");

    final Map<Object, List<String>> byLength = new HashMap<>();
    byLength.put(5, List.of("alpha"));

    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("byLength", byLength);

    final Snapshot<String> snapshot = Snapshot.of(data, indices, 1L, "hash");

    assertThrows(UnsupportedOperationException.class, () ->
        snapshot.data().add("new")
    );

    final List<String> searchResult = snapshot.search("byLength", 5);
    assertThrows(UnsupportedOperationException.class, () ->
        searchResult.add("new")
    );
  }

  @Test
  void whenCreatingEmpty_shouldWorkCorrectly() {
    final Snapshot<String> snapshot = Snapshot.empty();

    assertNotNull(snapshot);
    assertTrue(snapshot.data().isEmpty());
    assertEquals(0L, snapshot.version());
    assertEquals("", snapshot.hash());
    assertNotNull(snapshot.createdAt());

    final List<String> result = snapshot.search("anyIndex", "anyKey");
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenCreating_givenNullData_shouldThrowNpe() {
    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();

    assertThrows(NullPointerException.class, () ->
        Snapshot.of(null, indices, 1L, "hash")
    );
  }

  @Test
  void whenCreating_givenNullIndices_shouldThrowNpe() {
    final List<String> data = List.of("alpha");

    assertThrows(NullPointerException.class, () ->
        Snapshot.of(data, null, 1L, "hash")
    );
  }

  @Test
  void whenCreating_givenNullHash_shouldThrowNpe() {
    final List<String> data = List.of("alpha");
    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();

    assertThrows(NullPointerException.class, () ->
        Snapshot.of(data, indices, 1L, null)
    );
  }

  @Test
  void whenCreating_givenMutableData_shouldDefensivelyCopy() {
    final List<String> mutableData = new java.util.ArrayList<>(
        List.of("alpha", "beta"));

    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();

    final Snapshot<String> snapshot = Snapshot.of(
        mutableData, indices, 1L, "hash");

    mutableData.add("gamma");

    assertEquals(2, snapshot.data().size(),
        "Mutating the original data should not affect the snapshot");
  }

  @Test
  void whenCreating_givenMutableIndices_shouldDefensivelyCopy() {
    final List<String> data = List.of("alpha", "beta");

    final Map<Object, List<String>> innerMap = new HashMap<>();
    innerMap.put("key", List.of("alpha"));

    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("byKey", innerMap);

    final Snapshot<String> snapshot = Snapshot.of(data, indices, 1L, "hash");

    indices.put("injected", new HashMap<>());
    innerMap.put("injected-key", List.of("beta"));

    assertEquals(1, snapshot.search("byKey", "key").size());
    assertTrue(snapshot.search("injected", "anything").isEmpty(),
        "Mutating the original indices should not affect the snapshot");
    assertTrue(snapshot.search("byKey", "injected-key").isEmpty(),
        "Mutating the original inner map should not affect the snapshot");
  }

  @Test
  void whenSearching_givenNullIndexName_shouldThrowNpe() {
    final Snapshot<String> snapshot = Snapshot.of(
        List.of("alpha"), new HashMap<>(), 1L, "hash");

    assertThrows(NullPointerException.class,
        () -> snapshot.search(null, "key"));
  }

  @Test
  void whenSearching_givenNullKey_shouldThrowNpe() {
    final Snapshot<String> snapshot = Snapshot.of(
        List.of("alpha"), new HashMap<>(), 1L, "hash");

    assertThrows(NullPointerException.class,
        () -> snapshot.search("index", null));
  }
}
