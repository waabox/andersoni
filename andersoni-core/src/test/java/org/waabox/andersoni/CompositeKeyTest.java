package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class CompositeKeyTest {

  @Test
  void whenCreating_givenTwoComponents_shouldReturnCorrectComponents() {
    final CompositeKey key = CompositeKey.of("AR", "deportes");
    assertEquals(List.of("AR", "deportes"), key.components());
    assertEquals(2, key.size());
  }

  @Test
  void whenCreating_givenSingleComponent_shouldReturnCorrectComponents() {
    final CompositeKey key = CompositeKey.of("AR");
    assertEquals(List.of("AR"), key.components());
    assertEquals(1, key.size());
  }

  @Test
  void whenComparing_givenEqualKeys_shouldBeEqual() {
    final CompositeKey k1 = CompositeKey.of("AR", "deportes");
    final CompositeKey k2 = CompositeKey.of("AR", "deportes");
    assertEquals(k1, k2);
    assertEquals(k1.hashCode(), k2.hashCode());
  }

  @Test
  void whenComparing_givenDifferentKeys_shouldNotBeEqual() {
    final CompositeKey k1 = CompositeKey.of("AR", "deportes");
    final CompositeKey k2 = CompositeKey.of("AR", "futbol");
    assertNotEquals(k1, k2);
  }

  @Test
  void whenComparing_givenDifferentSizes_shouldNotBeEqual() {
    final CompositeKey k1 = CompositeKey.of("AR");
    final CompositeKey k2 = CompositeKey.of("AR", "deportes");
    assertNotEquals(k1, k2);
  }

  @Test
  void whenCreating_givenNullArray_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> CompositeKey.of((Object[]) null));
  }

  @Test
  void whenCreating_givenEmptyArray_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> CompositeKey.of());
  }

  @Test
  void whenCreating_givenNullComponent_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> CompositeKey.of("AR", null));
  }

  @Test
  void whenCreatingFromList_givenList_shouldBeEqualToVarargs() {
    final CompositeKey k1 = CompositeKey.of("AR", "deportes");
    final CompositeKey k2 = CompositeKey.ofList(List.of("AR", "deportes"));
    assertEquals(k1, k2);
  }

  @Test
  void whenUsingAsHashMapKey_givenEqualKeys_shouldFindValue() {
    final java.util.Map<CompositeKey, String> map = new java.util.HashMap<>();
    map.put(CompositeKey.of("AR", "deportes"), "found");
    assertEquals("found", map.get(CompositeKey.of("AR", "deportes")));
  }

  @Test
  void whenCallingToString_shouldShowComponents() {
    final CompositeKey key = CompositeKey.of("AR", "deportes");
    assertEquals("CompositeKey[AR, deportes]", key.toString());
  }
}
