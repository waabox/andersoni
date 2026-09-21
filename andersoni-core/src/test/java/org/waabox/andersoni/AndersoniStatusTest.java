package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AndersoniStatus}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class AndersoniStatusTest {

  private static AndersoniStatus.CatalogStatus status(final String name,
      final SyncState syncState) {
    return new AndersoniStatus.CatalogStatus(name, true, 1L, "h", true, 1, 0.1,
        syncState, Optional.of(Instant.EPOCH));
  }

  @Test
  void whenCheckingInSync_givenNoDriftedCatalog_shouldBeTrue() {
    final AndersoniStatus status = new AndersoniStatus("node-1", true,
        List.of(status("a", SyncState.IN_SYNC), status("b", SyncState.UNKNOWN)));

    assertTrue(status.inSync());
  }

  @Test
  void whenCheckingInSync_givenOneDriftedCatalog_shouldBeFalse() {
    final AndersoniStatus status = new AndersoniStatus("node-1", true,
        List.of(status("a", SyncState.IN_SYNC), status("b", SyncState.DRIFTED)));

    assertFalse(status.inSync());
  }

  @Test
  void whenCreatingCatalogStatus_givenNullSyncState_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        new AndersoniStatus.CatalogStatus("a", true, 1L, "h", true, 1, 0.1,
            null, Optional.empty()));
  }

  @Test
  void whenCreatingCatalogStatus_givenNullLastReconciledAt_shouldThrow() {
    assertThrows(NullPointerException.class, () ->
        new AndersoniStatus.CatalogStatus("a", true, 1L, "h", true, 1, 0.1,
            SyncState.UNKNOWN, null));
  }
}
