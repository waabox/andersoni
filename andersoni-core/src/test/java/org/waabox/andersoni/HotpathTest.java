package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class HotpathTest {

  @Test
  void whenCreating_givenFieldNames_shouldReturnThem() {
    final Hotpath hotpath = Hotpath.of("country", "category");
    assertEquals(List.of("country", "category"), hotpath.fieldNames());
    assertEquals(2, hotpath.size());
  }

  @Test
  void whenCreating_givenSingleField_shouldReturnIt() {
    final Hotpath hotpath = Hotpath.of("country");
    assertEquals(List.of("country"), hotpath.fieldNames());
    assertEquals(1, hotpath.size());
  }

  @Test
  void whenCreating_givenNullArray_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> Hotpath.of((String[]) null));
  }

  @Test
  void whenCreating_givenEmptyArray_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> Hotpath.of());
  }

  @Test
  void whenCreating_givenNullFieldName_shouldThrow() {
    assertThrows(NullPointerException.class,
        () -> Hotpath.of("country", null));
  }

  @Test
  void whenCreating_givenEmptyFieldName_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> Hotpath.of("country", ""));
  }

  @Test
  void whenCreating_givenDuplicateFieldName_shouldThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> Hotpath.of("country", "country"));
  }

  @Test
  void whenGettingFieldNames_shouldReturnUnmodifiableList() {
    final Hotpath hotpath = Hotpath.of("country", "category");
    assertThrows(UnsupportedOperationException.class,
        () -> hotpath.fieldNames().add("extra"));
  }

  @Test
  void whenCreating_givenSpecificOrder_shouldPreserveOrder() {
    final Hotpath hotpath = Hotpath.of("beta", "alpha");
    assertEquals(List.of("beta", "alpha"), hotpath.fieldNames());
  }
}
