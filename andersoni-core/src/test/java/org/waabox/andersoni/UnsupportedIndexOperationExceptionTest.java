package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link UnsupportedIndexOperationException}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class UnsupportedIndexOperationExceptionTest {

  @Test
  void whenCreating_givenMessage_shouldRetainIt() {
    final String message =
        "Index 'by-venue' does not support range queries."
        + " Use indexSorted() to enable them";

    final UnsupportedIndexOperationException exception =
        new UnsupportedIndexOperationException(message);

    assertEquals(message, exception.getMessage());
  }

  @Test
  void whenCreating_shouldBeRuntimeException() {
    final UnsupportedIndexOperationException exception =
        new UnsupportedIndexOperationException("some error");

    assertInstanceOf(RuntimeException.class, exception);
  }
}
