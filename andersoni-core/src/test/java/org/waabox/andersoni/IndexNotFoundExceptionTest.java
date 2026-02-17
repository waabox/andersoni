package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link IndexNotFoundException}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class IndexNotFoundExceptionTest {

  @Test
  void whenCreating_givenIndexAndCatalog_shouldFormatMessage() {
    final IndexNotFoundException exception =
        new IndexNotFoundException("by-venue", "events");

    assertEquals(
        "Index 'by-venue' not found in catalog 'events'",
        exception.getMessage()
    );
  }

  @Test
  void whenCreating_shouldBeRuntimeException() {
    final IndexNotFoundException exception =
        new IndexNotFoundException("by-venue", "events");

    assertInstanceOf(RuntimeException.class, exception);
  }
}
