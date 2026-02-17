package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

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

  @Test
  void whenComputingIndexInfo_givenPopulatedIndices_shouldReturnCorrectStats() {
    final List<String> data = List.of("alpha", "beta", "gamma", "delta");

    final Map<Object, List<String>> byLength = new HashMap<>();
    byLength.put(4, List.of("beta"));
    byLength.put(5, List.of("alpha", "gamma", "delta"));

    final Map<Object, List<String>> byFirst = new HashMap<>();
    byFirst.put("a", List.of("alpha"));
    byFirst.put("b", List.of("beta"));
    byFirst.put("g", List.of("gamma"));
    byFirst.put("d", List.of("delta"));

    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("byLength", byLength);
    indices.put("byFirst", byFirst);

    final Snapshot<String> snapshot = Snapshot.of(data, indices, 1L, "hash");

    final List<IndexInfo> infos = snapshot.indexInfo();

    assertEquals(2, infos.size());

    final IndexInfo byLengthInfo = infos.stream()
        .filter(i -> "byLength".equals(i.name())).findFirst().orElseThrow();
    assertEquals(2, byLengthInfo.uniqueKeys());
    assertEquals(4, byLengthInfo.totalEntries());
    assertTrue(byLengthInfo.estimatedSizeBytes() > 0);

    final IndexInfo byFirstInfo = infos.stream()
        .filter(i -> "byFirst".equals(i.name())).findFirst().orElseThrow();
    assertEquals(4, byFirstInfo.uniqueKeys());
    assertEquals(4, byFirstInfo.totalEntries());
    assertTrue(byFirstInfo.estimatedSizeBytes() > 0);
    assertTrue(byFirstInfo.estimatedSizeBytes() > byLengthInfo.estimatedSizeBytes(),
        "Index with more unique keys should have larger estimated size");
  }

  @Test
  void whenComputingIndexInfo_givenEmptySnapshot_shouldReturnEmptyList() {
    final Snapshot<String> snapshot = Snapshot.empty();
    final List<IndexInfo> infos = snapshot.indexInfo();
    assertNotNull(infos);
    assertTrue(infos.isEmpty());
  }

  @Test
  void whenComputingIndexInfo_givenStringKeys_shouldEstimateLargerThanIntegerKeys() {
    final List<String> data = List.of("alpha", "beta");

    final Map<Object, List<String>> byString = new HashMap<>();
    byString.put("longKeyNameHere", List.of("alpha"));
    byString.put("anotherLongKey", List.of("beta"));

    final Map<Object, List<String>> byInt = new HashMap<>();
    byInt.put(1, List.of("alpha"));
    byInt.put(2, List.of("beta"));

    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("byString", byString);
    indices.put("byInt", byInt);

    final Snapshot<String> snapshot = Snapshot.of(data, indices, 1L, "hash");

    final List<IndexInfo> infos = snapshot.indexInfo();

    final IndexInfo stringInfo = infos.stream()
        .filter(i -> "byString".equals(i.name())).findFirst().orElseThrow();
    final IndexInfo intInfo = infos.stream()
        .filter(i -> "byInt".equals(i.name())).findFirst().orElseThrow();

    assertTrue(stringInfo.estimatedSizeBytes() > intInfo.estimatedSizeBytes(),
        "String keys should produce a larger estimated size than Integer keys");
  }

  // -----------------------------------------------------------------------
  // Sorted index tests
  // -----------------------------------------------------------------------

  /**
   * Creates a snapshot with a numeric sorted index for range query tests.
   *
   * <p>Items: "ten"=10, "twenty"=20, "thirty"=30, "forty"=40, "fifty"=50
   */
  private Snapshot<String> numericSortedSnapshot() {
    final List<String> data = List.of("ten", "twenty", "thirty", "forty",
        "fifty");

    final NavigableMap<Comparable<?>, List<String>> sorted = new TreeMap<>();
    sorted.put(10, List.of("ten"));
    sorted.put(20, List.of("twenty"));
    sorted.put(30, List.of("thirty"));
    sorted.put(40, List.of("forty"));
    sorted.put(50, List.of("fifty"));

    final Map<String, NavigableMap<Comparable<?>, List<String>>> sortedIndices
        = new HashMap<>();
    sortedIndices.put("byValue", sorted);

    return Snapshot.of(data, Collections.emptyMap(), sortedIndices,
        Collections.emptyMap(), 1L, "hash");
  }

  /**
   * Creates a snapshot with a String sorted index for text query tests.
   *
   * <p>Items: "apple", "apricot", "banana", "blueberry", "cherry"
   */
  private Snapshot<String> stringSortedSnapshot() {
    final List<String> data = List.of("apple", "apricot", "banana",
        "blueberry", "cherry");

    final NavigableMap<Comparable<?>, List<String>> sorted = new TreeMap<>();
    sorted.put("apple", List.of("apple"));
    sorted.put("apricot", List.of("apricot"));
    sorted.put("banana", List.of("banana"));
    sorted.put("blueberry", List.of("blueberry"));
    sorted.put("cherry", List.of("cherry"));

    final NavigableMap<String, List<String>> reversed = new TreeMap<>();
    reversed.put(new StringBuilder("apple").reverse().toString(),
        List.of("apple"));
    reversed.put(new StringBuilder("apricot").reverse().toString(),
        List.of("apricot"));
    reversed.put(new StringBuilder("banana").reverse().toString(),
        List.of("banana"));
    reversed.put(new StringBuilder("blueberry").reverse().toString(),
        List.of("blueberry"));
    reversed.put(new StringBuilder("cherry").reverse().toString(),
        List.of("cherry"));

    final Map<String, NavigableMap<Comparable<?>, List<String>>> sortedIndices
        = new HashMap<>();
    sortedIndices.put("byName", sorted);

    final Map<String, NavigableMap<String, List<String>>> reversedIndices
        = new HashMap<>();
    reversedIndices.put("byName", reversed);

    return Snapshot.of(data, Collections.emptyMap(), sortedIndices,
        reversedIndices, 1L, "hash");
  }

  @Test
  void whenSearchBetween_givenSortedIndex_shouldReturnRange() {
    final Snapshot<String> snapshot = numericSortedSnapshot();

    final List<String> result = snapshot.searchBetween("byValue", 20, 40);

    assertEquals(3, result.size());
    assertTrue(result.contains("twenty"));
    assertTrue(result.contains("thirty"));
    assertTrue(result.contains("forty"));
  }

  @Test
  void whenSearchGreaterThan_givenSortedIndex_shouldReturnMatches() {
    final Snapshot<String> snapshot = numericSortedSnapshot();

    final List<String> result = snapshot.searchGreaterThan("byValue", 30);

    assertEquals(2, result.size());
    assertTrue(result.contains("forty"));
    assertTrue(result.contains("fifty"));
  }

  @Test
  void whenSearchGreaterOrEqual_givenSortedIndex_shouldReturnMatches() {
    final Snapshot<String> snapshot = numericSortedSnapshot();

    final List<String> result = snapshot.searchGreaterOrEqual("byValue", 30);

    assertEquals(3, result.size());
    assertTrue(result.contains("thirty"));
    assertTrue(result.contains("forty"));
    assertTrue(result.contains("fifty"));
  }

  @Test
  void whenSearchLessThan_givenSortedIndex_shouldReturnMatches() {
    final Snapshot<String> snapshot = numericSortedSnapshot();

    final List<String> result = snapshot.searchLessThan("byValue", 30);

    assertEquals(2, result.size());
    assertTrue(result.contains("ten"));
    assertTrue(result.contains("twenty"));
  }

  @Test
  void whenSearchLessOrEqual_givenSortedIndex_shouldReturnMatches() {
    final Snapshot<String> snapshot = numericSortedSnapshot();

    final List<String> result = snapshot.searchLessOrEqual("byValue", 30);

    assertEquals(3, result.size());
    assertTrue(result.contains("ten"));
    assertTrue(result.contains("twenty"));
    assertTrue(result.contains("thirty"));
  }

  @Test
  void whenSearchStartsWith_givenStringKeySortedIndex_shouldReturnMatches() {
    final Snapshot<String> snapshot = stringSortedSnapshot();

    final List<String> result = snapshot.searchStartsWith("byName", "ap");

    assertEquals(2, result.size());
    assertTrue(result.contains("apple"));
    assertTrue(result.contains("apricot"));
  }

  @Test
  void whenSearchEndsWith_givenStringKeySortedIndex_shouldReturnMatches() {
    final Snapshot<String> snapshot = stringSortedSnapshot();

    final List<String> result = snapshot.searchEndsWith("byName", "rry");

    assertEquals(2, result.size());
    assertTrue(result.contains("blueberry"));
    assertTrue(result.contains("cherry"));
  }

  @Test
  void whenSearchContains_givenStringKeySortedIndex_shouldReturnMatches() {
    final Snapshot<String> snapshot = stringSortedSnapshot();

    final List<String> result = snapshot.searchContains("byName", "an");

    assertEquals(1, result.size());
    assertTrue(result.contains("banana"));
  }

  @Test
  void whenSearchBetween_givenNonSortedIndex_shouldThrowException() {
    final Map<Object, List<String>> byLength = new HashMap<>();
    byLength.put(5, List.of("alpha"));

    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("byLength", byLength);

    final Snapshot<String> snapshot = Snapshot.of(
        List.of("alpha"), indices, 1L, "hash");

    assertThrows(UnsupportedIndexOperationException.class,
        () -> snapshot.searchBetween("byLength", 1, 10));
  }

  @Test
  void whenSearchBetween_givenNonExistentSortedIndex_shouldThrowException() {
    final Snapshot<String> snapshot = numericSortedSnapshot();

    assertThrows(UnsupportedIndexOperationException.class,
        () -> snapshot.searchBetween("nonExistent", 1, 10));
  }

  @Test
  void whenSearchEndsWith_givenNonStringKeySortedIndex_shouldThrowException() {
    final Snapshot<String> snapshot = numericSortedSnapshot();

    assertThrows(UnsupportedIndexOperationException.class,
        () -> snapshot.searchEndsWith("byValue", "test"));
  }

  @Test
  void whenHasIndex_givenExistingIndex_shouldReturnTrue() {
    final Map<Object, List<String>> byLength = new HashMap<>();
    byLength.put(5, List.of("alpha"));

    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("byLength", byLength);

    final Snapshot<String> snapshot = Snapshot.of(
        List.of("alpha"), indices, 1L, "hash");

    assertTrue(snapshot.hasIndex("byLength"));
    assertFalse(snapshot.hasIndex("nonExistent"));
  }

  @Test
  void whenHasSortedIndex_givenExistingSortedIndex_shouldReturnTrue() {
    final Snapshot<String> snapshot = numericSortedSnapshot();

    assertTrue(snapshot.hasSortedIndex("byValue"));
    assertFalse(snapshot.hasSortedIndex("nonExistent"));

    // hasSortedIndex should also work via hasIndex
    assertTrue(snapshot.hasIndex("byValue"));
  }
}
