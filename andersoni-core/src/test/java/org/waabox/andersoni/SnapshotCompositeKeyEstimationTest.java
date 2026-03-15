package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SnapshotCompositeKeyEstimationTest {

  @Test
  void whenEstimatingIndexInfo_givenCompositeKeys_shouldEstimateAccurately() {
    final Map<Object, List<String>> indexMap = Map.of(
        CompositeKey.of("AR", "deportes"), List.of("item1"),
        CompositeKey.of("AR", "futbol"), List.of("item2"));

    final Snapshot<String> snapshot = Snapshot.of(
        List.of("item1", "item2"),
        Map.of("test-index", indexMap),
        1L, "hash");

    final List<IndexInfo> infos = snapshot.indexInfo();
    final IndexInfo info = infos.getFirst();

    // CompositeKey with 2 string components ("AR" len=2, "deportes" len=8):
    // Each key: 40 (CK overhead) + (40+2) + (40+8) = 130 bytes
    // With 2 keys: total key size > 260
    // DEFAULT_KEY_SIZE (50) * 2 = 100 would be the old underestimate
    assertTrue(info.estimatedSizeBytes() > 200,
        "CompositeKey estimation should be > 200 bytes for 2 keys"
            + " with string components, got: "
            + info.estimatedSizeBytes());
  }
}
