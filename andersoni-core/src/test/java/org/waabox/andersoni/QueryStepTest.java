package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Tests for {@link QueryStep}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class QueryStepTest {

  /**
   * Creates a snapshot with a hash (non-sorted) index named "byLength"
   * mapping integer keys to string items.
   *
   * <p>Items: "alpha"=5, "beta"=4, "gamma"=5
   */
  private Snapshot<String> hashIndexSnapshot() {
    final List<String> data = List.of("alpha", "beta", "gamma");

    final Map<Object, List<String>> byLength = new HashMap<>();
    byLength.put(4, List.of("beta"));
    byLength.put(5, List.of("alpha", "gamma"));

    final Map<String, Map<Object, List<String>>> indices = new HashMap<>();
    indices.put("byLength", byLength);

    return Snapshot.of(data, indices, 1L, "hash");
  }

  /**
   * Creates a snapshot with a numeric sorted index named "byValue".
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
   * Creates a snapshot with a String sorted index named "byName" and a
   * corresponding reversed-key index for endsWith queries.
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
  void whenEqualTo_givenHashIndex_shouldReturnMatches() {
    final Snapshot<String> snapshot = hashIndexSnapshot();
    final QueryStep<String> query = new QueryStep<>(snapshot, "byLength",
        "products");

    final List<String> result = query.equalTo(5);

    assertEquals(2, result.size());
    assertTrue(result.contains("alpha"));
    assertTrue(result.contains("gamma"));
  }

  @Test
  void whenEqualTo_givenMissingKey_shouldReturnEmpty() {
    final Snapshot<String> snapshot = hashIndexSnapshot();
    final QueryStep<String> query = new QueryStep<>(snapshot, "byLength",
        "products");

    final List<String> result = query.equalTo(999);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void whenEqualTo_givenNonExistentIndex_shouldThrowIndexNotFound() {
    final Snapshot<String> snapshot = hashIndexSnapshot();
    final QueryStep<String> query = new QueryStep<>(snapshot, "nonExistent",
        "products");

    final IndexNotFoundException exception = assertThrows(
        IndexNotFoundException.class, () -> query.equalTo("key"));

    assertTrue(exception.getMessage().contains("nonExistent"));
    assertTrue(exception.getMessage().contains("products"));
  }

  @Test
  void whenBetween_givenSortedIndex_shouldReturnRange() {
    final Snapshot<String> snapshot = numericSortedSnapshot();
    final QueryStep<String> query = new QueryStep<>(snapshot, "byValue",
        "scores");

    final List<String> result = query.between(20, 40);

    assertEquals(3, result.size());
    assertTrue(result.contains("twenty"));
    assertTrue(result.contains("thirty"));
    assertTrue(result.contains("forty"));
  }

  @Test
  void whenGreaterThan_givenSortedIndex_shouldReturnMatches() {
    final Snapshot<String> snapshot = numericSortedSnapshot();
    final QueryStep<String> query = new QueryStep<>(snapshot, "byValue",
        "scores");

    final List<String> result = query.greaterThan(30);

    assertEquals(2, result.size());
    assertTrue(result.contains("forty"));
    assertTrue(result.contains("fifty"));
  }

  @Test
  void whenLessThan_givenSortedIndex_shouldReturnMatches() {
    final Snapshot<String> snapshot = numericSortedSnapshot();
    final QueryStep<String> query = new QueryStep<>(snapshot, "byValue",
        "scores");

    final List<String> result = query.lessThan(30);

    assertEquals(2, result.size());
    assertTrue(result.contains("ten"));
    assertTrue(result.contains("twenty"));
  }

  @Test
  void whenGreaterOrEqual_givenSortedIndex_shouldReturnMatches() {
    final Snapshot<String> snapshot = numericSortedSnapshot();
    final QueryStep<String> query = new QueryStep<>(snapshot, "byValue",
        "scores");

    final List<String> result = query.greaterOrEqual(30);

    assertEquals(3, result.size());
    assertTrue(result.contains("thirty"));
    assertTrue(result.contains("forty"));
    assertTrue(result.contains("fifty"));
  }

  @Test
  void whenLessOrEqual_givenSortedIndex_shouldReturnMatches() {
    final Snapshot<String> snapshot = numericSortedSnapshot();
    final QueryStep<String> query = new QueryStep<>(snapshot, "byValue",
        "scores");

    final List<String> result = query.lessOrEqual(30);

    assertEquals(3, result.size());
    assertTrue(result.contains("ten"));
    assertTrue(result.contains("twenty"));
    assertTrue(result.contains("thirty"));
  }

  @Test
  void whenStartsWith_givenStringKeySortedIndex_shouldReturnMatches() {
    final Snapshot<String> snapshot = stringSortedSnapshot();
    final QueryStep<String> query = new QueryStep<>(snapshot, "byName",
        "fruits");

    final List<String> result = query.startsWith("ap");

    assertEquals(2, result.size());
    assertTrue(result.contains("apple"));
    assertTrue(result.contains("apricot"));
  }

  @Test
  void whenEndsWith_givenStringKeySortedIndex_shouldReturnMatches() {
    final Snapshot<String> snapshot = stringSortedSnapshot();
    final QueryStep<String> query = new QueryStep<>(snapshot, "byName",
        "fruits");

    final List<String> result = query.endsWith("rry");

    assertEquals(2, result.size());
    assertTrue(result.contains("blueberry"));
    assertTrue(result.contains("cherry"));
  }

  @Test
  void whenContains_givenStringKeySortedIndex_shouldReturnMatches() {
    final Snapshot<String> snapshot = stringSortedSnapshot();
    final QueryStep<String> query = new QueryStep<>(snapshot, "byName",
        "fruits");

    final List<String> result = query.contains("an");

    assertEquals(1, result.size());
    assertTrue(result.contains("banana"));
  }

  @Test
  void whenBetween_givenNonSortedIndex_shouldThrowException() {
    final Snapshot<String> snapshot = hashIndexSnapshot();
    final QueryStep<String> query = new QueryStep<>(snapshot, "byLength",
        "products");

    assertThrows(UnsupportedIndexOperationException.class,
        () -> query.between(1, 10));
  }

  @Test
  void whenStartsWith_givenNonStringKeySortedIndex_shouldThrowException() {
    final Snapshot<String> snapshot = numericSortedSnapshot();
    final QueryStep<String> query = new QueryStep<>(snapshot, "byValue",
        "scores");

    assertThrows(UnsupportedIndexOperationException.class,
        () -> query.endsWith("test"));
  }
}
