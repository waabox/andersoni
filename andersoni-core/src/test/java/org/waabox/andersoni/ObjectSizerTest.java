package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ObjectSizerTest {

  private static final ObjectSizer.Layout COMPRESSED =
      new ObjectSizer.Layout(4, 12, 8);

  private static final ObjectSizer.Layout UNCOMPRESSED =
      new ObjectSizer.Layout(8, 16, 8);

  record Point(int x, int y) {
  }

  record Named(String name, Point point) {
  }

  static final class Node {
    Node next;
  }

  @Test
  void whenSizingString_givenLatin1_shouldCountOneBytePerChar() {
    final ObjectSizer sizer = new ObjectSizer(COMPRESSED);
    // String: 12 header + byte[] ref + int hash + byte coder + boolean = 24.
    // byte[]: 16 header + 3 = 19, aligned to 24.
    assertEquals(48L, sizer.stringSize("abc"));
  }

  @Test
  void whenSizingString_givenNonLatin1_shouldCountTwoBytesPerChar() {
    final ObjectSizer sizer = new ObjectSizer(COMPRESSED);
    // byte[] of 2 * 5 = 10 bytes: 16 + 10 = 26, aligned to 32.
    assertEquals(24L + 32L, sizer.stringSize("€€€€€"));
  }

  @Test
  void whenSizingRecord_givenCompressedLayout_shouldUseFourByteReferences() {
    final ObjectSizer sizer = new ObjectSizer(COMPRESSED);
    // 12 header + two 4-byte references = 20, aligned to 24.
    assertEquals(24L, sizer.shallowSize(Named.class));
  }

  @Test
  void whenSizingRecord_givenUncompressedLayout_shouldUseEightByteReferences() {
    final ObjectSizer sizer = new ObjectSizer(UNCOMPRESSED);
    assertEquals(32L, sizer.shallowSize(Named.class));
  }

  @Test
  void whenComputingDeepSize_givenNestedRecord_shouldIncludeEveryObject() {
    final ObjectSizer sizer = new ObjectSizer(COMPRESSED);
    final Named named = new Named("abc", new Point(1, 2));
    // Named 24 + "abc" 48 + Point (12 + 4 + 4 = 20 -> 24).
    assertEquals(24L + 48L + 24L,
        sizer.deepSize(named, Collections.emptySet()));
  }

  @Test
  void whenComputingDeepSize_givenExcludedObject_shouldNotCountIt() {
    final ObjectSizer sizer = new ObjectSizer(COMPRESSED);
    final Point point = new Point(1, 2);
    final Named named = new Named("abc", point);
    assertEquals(24L + 48L,
        sizer.deepSize(named, Collections.singleton(point)));
  }

  @Test
  void whenComputingDeepSize_givenCycle_shouldCountEachObjectOnce() {
    final ObjectSizer sizer = new ObjectSizer(COMPRESSED);
    final Node a = new Node();
    final Node b = new Node();
    a.next = b;
    b.next = a;
    assertEquals(2 * 16L, sizer.deepSize(a, Collections.emptySet()));
  }

  @Test
  void whenComputingDeepSize_givenJdkValueType_shouldUseItsFields() {
    final ObjectSizer sizer = new ObjectSizer(COMPRESSED);
    // LocalDate: 12 header + int + short + short = 20, aligned to 24.
    assertEquals(24L,
        sizer.deepSize(LocalDate.of(2026, 1, 1), Collections.emptySet()));
  }

  @Test
  void whenComputingDeepSize_givenEnum_shouldTreatItAsFree() {
    final ObjectSizer sizer = new ObjectSizer(COMPRESSED);
    assertEquals(0L,
        sizer.deepSize(java.time.DayOfWeek.MONDAY, Collections.emptySet()));
  }

  @Test
  void whenComputingDeepSize_givenArrayList_shouldIncludeBackingArray() {
    final ObjectSizer sizer = new ObjectSizer(COMPRESSED);
    final List<Point> list = new ArrayList<>();
    list.add(new Point(1, 1));
    list.add(new Point(2, 2));
    final long expected = sizer.shallowSize(ArrayList.class)
        + sizer.referenceArraySize(2) + 2 * 24L;
    assertEquals(expected, sizer.deepSize(list, Collections.emptySet()));
  }

  @Test
  void whenSizingHashMap_givenEntries_shouldGrowTableByPowersOfTwo() {
    final ObjectSizer sizer = new ObjectSizer(COMPRESSED);
    final Map<Integer, Integer> map = new HashMap<>();
    for (int i = 0; i < 13; i++) {
      map.put(i, i);
    }
    // 13 entries exceed the 0.75 threshold of 16 buckets, so 32 buckets.
    final long expected = sizer.shallowSize(HashMap.class)
        + sizer.referenceArraySize(32) + 13 * 32L;
    assertEquals(expected, sizer.containerSize(map, map.size()));
  }

  @Test
  void whenWalking_givenObjectSharedByTwoRoots_shouldCountItAsShared() {
    final ObjectSizer sizer = new ObjectSizer(COMPRESSED);
    final Point shared = new Point(1, 2);
    final ObjectSizer.Walk walk = sizer.walk();
    walk.add(new Named("a", shared), 0);
    walk.add(new Named("b", shared), 1);
    assertEquals(24L, walk.sharedBytes());
    assertEquals(2 * (24L + sizer.stringSize("a")), walk.ownedBytes());
  }

  @Test
  void whenWalking_givenSharedObjectWithChildren_shouldShareTheChildrenToo() {
    final ObjectSizer sizer = new ObjectSizer(COMPRESSED);
    final Named shared = new Named("abc", new Point(1, 2));
    final ObjectSizer.Walk walk = sizer.walk();
    walk.add(List.of(shared), 0);
    walk.add(List.of(shared), 1);
    assertEquals(24L + 48L + 24L, walk.sharedBytes());
  }

  @Test
  void whenDetectingLayout_givenRunningJvm_shouldReturnPlausibleValues() {
    final ObjectSizer.Layout layout = ObjectSizer.Layout.detect();
    assertTrue(layout.referenceSize() == 4 || layout.referenceSize() == 8);
    assertTrue(layout.objectHeader() >= 8 && layout.objectHeader() <= 16);
    assertTrue(layout.alignment() >= 8);
  }
}
