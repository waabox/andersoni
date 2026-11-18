package org.waabox.andersoni;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.management.HotSpotDiagnosticMXBean;

/**
 * Estimates the heap footprint of Java objects using the object layout of
 * the running JVM (reference size, header size and alignment).
 *
 * <p>Objects whose fields can be read reflectively (application classes,
 * records) are walked field by field. JDK classes cannot be read on Java 21
 * without {@code --add-opens}, so they are sized from their declared fields:
 * collections and maps from their size and well-known internal structure,
 * and other JDK values (such as {@code java.time} types) from the shallow
 * size of the object plus that of any final-typed field.
 *
 * <p>Enum constants and {@link Class} objects are treated as free: they are
 * JVM-wide singletons and do not belong to any cached item.
 *
 * <p>Instances are thread-safe.
 */
final class ObjectSizer {

  /** The class logger. */
  private static final Logger log = LoggerFactory.getLogger(ObjectSizer.class);

  /** {@code java.util.Collections$UnmodifiableRandomAccessList}. */
  private static final Class<?> UNMODIFIABLE_RANDOM_ACCESS_LIST =
      java.util.Collections.unmodifiableList(new ArrayList<>()).getClass();

  /** {@code java.util.ImmutableCollections$List12}. */
  private static final Class<?> LIST_12 = List.of(1).getClass();

  /** {@code java.util.ImmutableCollections$ListN}. */
  private static final Class<?> LIST_N = List.of(1, 2, 3).getClass();

  /** The sizer for the running JVM. */
  static final ObjectSizer DEFAULT = new ObjectSizer(Layout.detect());

  /** Owner marker for objects reached from more than one root. */
  private static final int SHARED = -1;

  /** Upper bound on objects visited by a single walk. */
  private static final int MAX_VISITED = 2_000_000;

  /** Depth limit when estimating fields of unreadable objects by type. */
  private static final int MAX_TYPE_DEPTH = 3;

  /** The JVM object layout. */
  private final Layout layout;

  /** Per-class shape cache. */
  private final ClassValue<Shape> shapes = new ClassValue<>() {
    @Override
    protected Shape computeValue(final Class<?> type) {
      return computeShape(type);
    }
  };

  /** Shallow size of {@code java.util.HashMap$Node}. */
  private final long hashNode;

  /** Shallow size of {@code java.util.LinkedHashMap$Entry}. */
  private final long linkedHashEntry;

  /** Shallow size of {@code java.util.TreeMap$Entry}. */
  private final long treeEntry;

  /** Shallow size of {@code java.util.concurrent.ConcurrentHashMap$Node}. */
  private final long concurrentNode;

  /** Shallow size of {@code java.util.HashMap}. */
  private final long hashMap;

  /** Shallow size of {@code java.util.LinkedHashMap}. */
  private final long linkedHashMap;

  /** Shallow size of {@code java.util.TreeMap}. */
  private final long treeMap;

  /** Shallow size of {@code java.util.ArrayList}. */
  private final long arrayList;

  /** Shallow size of {@code java.lang.String}. */
  private final long string;

  /**
   * Creates a sizer for the given layout.
   *
   * @param theLayout the JVM object layout, never null
   */
  ObjectSizer(final Layout theLayout) {
    layout = theLayout;
    hashNode = shallowSize("java.util.HashMap$Node");
    linkedHashEntry = shallowSize("java.util.LinkedHashMap$Entry");
    treeEntry = shallowSize("java.util.TreeMap$Entry");
    concurrentNode = shallowSize("java.util.concurrent.ConcurrentHashMap$Node");
    hashMap = shallowSize(java.util.HashMap.class);
    linkedHashMap = shallowSize(java.util.LinkedHashMap.class);
    treeMap = shallowSize(java.util.TreeMap.class);
    arrayList = shallowSize(ArrayList.class);
    string = shallowSize(String.class);
  }

  /**
   * Returns the JVM layout this sizer uses.
   *
   * @return the layout, never null
   */
  Layout layout() {
    return layout;
  }

  // -----------------------------------------------------------------------
  // Shallow sizes
  // -----------------------------------------------------------------------

  /**
   * Returns the shallow size of an instance of the given class.
   *
   * @param type the class, never null
   *
   * @return the instance size in bytes, aligned
   */
  long shallowSize(final Class<?> type) {
    return shapes.get(type).shallowSize;
  }

  /**
   * Returns the size of an array of the given component type and length.
   *
   * @param componentType the array component type, never null
   * @param length        the array length
   *
   * @return the array size in bytes, aligned
   */
  long arraySize(final Class<?> componentType, final long length) {
    final int element = fieldSize(componentType);
    return align(layout.arrayBase(element) + element * length);
  }

  /**
   * Returns the size of an {@code Object[]} of the given length.
   *
   * @param length the array length
   *
   * @return the array size in bytes, aligned
   */
  long referenceArraySize(final long length) {
    return arraySize(Object.class, length);
  }

  /**
   * Returns the size of a String including its backing byte array,
   * assuming compact strings (Latin-1 when every char fits in one byte).
   *
   * @param value the string, never null
   *
   * @return the size in bytes
   */
  long stringSize(final String value) {
    final int length = value.length();
    boolean latin1 = true;
    for (int i = 0; i < length && latin1; i++) {
      latin1 = value.charAt(i) <= 0xFF;
    }
    return string + arraySize(byte.class, latin1 ? length : 2L * length);
  }

  /**
   * Returns the size of a HashMap with the given number of entries that
   * was filled by successive puts, excluding keys and values.
   *
   * @param entries the number of entries
   *
   * @return the size in bytes
   */
  long hashMapSize(final int entries) {
    return hashMap + hashTable(entries, hashNode);
  }

  /**
   * Returns the size of a TreeMap with the given number of entries,
   * excluding keys and values.
   *
   * @param entries the number of entries
   *
   * @return the size in bytes
   */
  long treeMapSize(final int entries) {
    return treeMap + entries * treeEntry;
  }

  /**
   * Returns the size of an unmodifiable list produced by
   * {@code Collections.unmodifiableList(List.copyOf(list))}.
   *
   * @param size the list size
   *
   * @return the size in bytes
   */
  long frozenListSize(final int size) {
    final long wrapper = shallowSize(UNMODIFIABLE_RANDOM_ACCESS_LIST);
    if (size == 0) {
      return wrapper;
    }
    if (size <= 2) {
      return wrapper + shallowSize(LIST_12);
    }
    return wrapper + shallowSize(LIST_N) + referenceArraySize(size);
  }

  private long hashTable(final int entries, final long nodeSize) {
    if (entries == 0) {
      return 0L;
    }
    long capacity = 16L;
    while (entries > capacity * 3 / 4) {
      capacity <<= 1;
    }
    return referenceArraySize(capacity) + entries * nodeSize;
  }

  private long shallowSize(final String className) {
    try {
      return shallowSize(Class.forName(className, false, null));
    } catch (final ClassNotFoundException e) {
      return align(layout.objectHeader + 4L * layout.referenceSize);
    }
  }

  private long align(final long size) {
    final long a = layout.alignment;
    return (size + a - 1) / a * a;
  }

  private int fieldSize(final Class<?> type) {
    if (!type.isPrimitive()) {
      return layout.referenceSize;
    }
    if (type == long.class || type == double.class) {
      return 8;
    }
    if (type == int.class || type == float.class) {
      return 4;
    }
    if (type == short.class || type == char.class) {
      return 2;
    }
    return 1;
  }

  // -----------------------------------------------------------------------
  // Deep walks
  // -----------------------------------------------------------------------

  /**
   * Starts a walk that separates bytes owned by a single root from bytes
   * reachable from several roots.
   *
   * @return a new walk, never null
   */
  Walk walk() {
    return new Walk();
  }

  /**
   * Returns the deep size of the given object, not counting objects in
   * the exclusion set.
   *
   * @param root    the object to size, may be null
   * @param exclude objects to treat as already accounted for, never null
   *
   * @return the deep size in bytes
   */
  long deepSize(final Object root, final Set<Object> exclude) {
    if (root == null || exclude.contains(root)) {
      return 0L;
    }
    final Set<Object> seen = java.util.Collections.newSetFromMap(
        new IdentityHashMap<>());
    final Deque<Object> stack = new ArrayDeque<>();
    stack.push(root);
    long total = 0L;
    while (!stack.isEmpty()) {
      final Object o = stack.pop();
      if (exclude.contains(o) || !seen.add(o) || seen.size() > MAX_VISITED) {
        continue;
      }
      total += visit(o, stack::push);
    }
    return total;
  }

  /**
   * Returns the objects reachable from the given root, up to a limit.
   *
   * @param root  the root object, may be null
   * @param limit the maximum number of objects to collect
   *
   * @return an identity set of reachable objects, never null
   */
  Set<Object> reachable(final Object root, final int limit) {
    final Set<Object> seen = java.util.Collections.newSetFromMap(
        new IdentityHashMap<>());
    if (root == null) {
      return seen;
    }
    final Deque<Object> stack = new ArrayDeque<>();
    stack.push(root);
    while (!stack.isEmpty() && seen.size() < limit) {
      final Object o = stack.pop();
      if (seen.add(o)) {
        visit(o, stack::push);
      }
    }
    return seen;
  }

  /**
   * Computes the size an object adds on its own and reports the objects it
   * references.
   *
   * @param o        the object, never null
   * @param children receives each referenced object, never null
   *
   * @return the bytes attributed to the object itself
   */
  private long visit(final Object o, final Consumer<Object> children) {
    final Class<?> type = o.getClass();
    if (o instanceof Class<?> || o instanceof Enum<?>) {
      return 0L;
    }
    if (o instanceof String s) {
      return stringSize(s);
    }
    if (type.isArray()) {
      final int length = Array.getLength(o);
      if (!type.getComponentType().isPrimitive()) {
        for (final Object element : (Object[]) o) {
          offer(element, children);
        }
      }
      return arraySize(type.getComponentType(), length);
    }
    final Shape shape = shapes.get(type);
    if (shape.readable) {
      for (final Field field : shape.referenceFields) {
        try {
          offer(field.get(o), children);
        } catch (final IllegalAccessException e) {
          // Unreachable: readable shapes only hold accessible fields.
        }
      }
      return shape.shallowSize;
    }
    if (o instanceof Map<?, ?> map) {
      try {
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
          offer(entry.getKey(), children);
          offer(entry.getValue(), children);
        }
      } catch (final RuntimeException e) {
        log.debug("Could not iterate {}: {}", type.getName(), e.getMessage());
      }
      return containerSize(o, map.size());
    }
    if (o instanceof Collection<?> collection) {
      try {
        for (final Object element : collection) {
          offer(element, children);
        }
      } catch (final RuntimeException e) {
        log.debug("Could not iterate {}: {}", type.getName(), e.getMessage());
      }
      return containerSize(o, collection.size());
    }
    if (o instanceof Optional<?> optional) {
      offer(optional.orElse(null), children);
      return shape.shallowSize;
    }
    return shape.shallowSize + shape.opaqueExtra;
  }

  private static void offer(final Object o, final Consumer<Object> children) {
    if (o != null) {
      children.accept(o);
    }
  }

  /**
   * Returns the size of a JDK collection or map container, excluding the
   * elements it holds.
   *
   * @param container the collection or map, never null
   * @param size      its number of elements or entries
   *
   * @return the size in bytes
   */
  long containerSize(final Object container, final int size) {
    final Class<?> type = container.getClass();
    final long self = shallowSize(type);
    final String name = type.getName();
    switch (name) {
      case "java.util.ArrayList", "java.util.Vector",
          "java.util.Arrays$ArrayList", "java.util.ArrayDeque",
          "java.util.ImmutableCollections$ListN",
          "java.util.concurrent.CopyOnWriteArrayList":
        return self + referenceArraySize(size);
      case "java.util.ImmutableCollections$SetN":
        return self + referenceArraySize(2L * size);
      case "java.util.ImmutableCollections$MapN":
        return self + referenceArraySize(4L * size);
      case "java.util.HashMap":
        return self + hashTable(size, hashNode);
      case "java.util.LinkedHashMap":
        return self + hashTable(size, linkedHashEntry);
      case "java.util.HashSet":
        return self + hashMap + hashTable(size, hashNode);
      case "java.util.LinkedHashSet":
        return self + linkedHashMap + hashTable(size, linkedHashEntry);
      case "java.util.TreeMap":
        return self + size * treeEntry;
      case "java.util.TreeSet":
        return self + treeMap + size * treeEntry;
      case "java.util.concurrent.ConcurrentHashMap":
        return self + hashTable(size, concurrentNode);
      case "java.util.IdentityHashMap":
        return self + referenceArraySize(4L * Math.max(size, 16));
      default:
        break;
    }
    if (name.startsWith("java.util.Collections$Unmodifiable")
        || name.startsWith("java.util.Collections$Synchronized")
        || name.startsWith("java.util.Collections$Checked")) {
      return self + wrappedContainerSize(container, size);
    }
    if (name.startsWith("java.util.ImmutableCollections$")
        || name.contains("Singleton") || name.contains("Empty")) {
      return self;
    }
    return self + referenceArraySize(size);
  }

  /**
   * Estimates the container wrapped by a {@code Collections} wrapper, which
   * cannot be read reflectively, from the interfaces it exposes.
   */
  private long wrappedContainerSize(final Object wrapper, final int size) {
    if (wrapper instanceof NavigableMap<?, ?> || wrapper instanceof SortedMap<?, ?>) {
      return treeMapSize(size);
    }
    if (wrapper instanceof Map<?, ?>) {
      return hashMapSize(size);
    }
    if (wrapper instanceof SortedSet<?>) {
      return shallowSize(java.util.TreeSet.class) + treeMapSize(size);
    }
    if (wrapper instanceof Set<?>) {
      return shallowSize(java.util.HashSet.class) + hashMapSize(size);
    }
    return arrayList + referenceArraySize(size);
  }

  // -----------------------------------------------------------------------
  // Class shapes
  // -----------------------------------------------------------------------

  private Shape computeShape(final Class<?> type) {
    final List<Field> references = referenceFields(type);
    boolean readable = true;
    for (final Field field : references) {
      if (!field.trySetAccessible()) {
        readable = false;
        break;
      }
    }
    final long opaqueExtra = readable ? 0L : opaqueExtra(references, 0);
    return new Shape(instanceSize(type), references.toArray(new Field[0]),
        readable, opaqueExtra);
  }

  /**
   * Estimates objects held by fields that cannot be read, counting only
   * fields whose declared type is a final class, since that type is the
   * only one the value can have.
   */
  private long opaqueExtra(final List<Field> references, final int depth) {
    if (depth >= MAX_TYPE_DEPTH) {
      return 0L;
    }
    long extra = 0L;
    for (final Field field : references) {
      final Class<?> declared = field.getType();
      if (declared.isArray() || declared == String.class
          || !Modifier.isFinal(declared.getModifiers())
          || declared.isEnum()) {
        continue;
      }
      extra += instanceSize(declared)
          + opaqueExtra(referenceFields(declared), depth + 1);
    }
    return extra;
  }

  /** Computes the aligned instance size of a class from its fields. */
  private long instanceSize(final Class<?> type) {
    long size = layout.objectHeader;
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      for (final Field field : c.getDeclaredFields()) {
        if (!Modifier.isStatic(field.getModifiers())) {
          size += fieldSize(field.getType());
        }
      }
    }
    return align(size);
  }

  /** Returns the instance reference fields of a class and its supertypes. */
  private static List<Field> referenceFields(final Class<?> type) {
    final List<Field> references = new ArrayList<>();
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      for (final Field field : c.getDeclaredFields()) {
        if (!Modifier.isStatic(field.getModifiers())
            && !field.getType().isPrimitive()) {
          references.add(field);
        }
      }
    }
    return references;
  }

  /**
   * The size-relevant shape of a class.
   *
   * @param shallowSize     the aligned instance size
   * @param referenceFields the instance reference fields
   * @param readable        whether every reference field can be read
   * @param opaqueExtra     estimated size of objects held by final-typed
   *                        fields when the class is not readable
   */
  private record Shape(long shallowSize, Field[] referenceFields,
      boolean readable, long opaqueExtra) {
  }

  // -----------------------------------------------------------------------
  // Walk
  // -----------------------------------------------------------------------

  /**
   * A walk over several roots that attributes each object either to the
   * single root it was reached from, or to a shared pool when it is reached
   * from more than one root.
   *
   * <p>This lets a sample of roots be scaled up to a full population: the
   * owned bytes scale with the number of roots, the shared bytes do not.
   */
  final class Walk {

    /** Owner root index per visited object, or {@link #SHARED}. */
    private final IdentityHashMap<Object, int[]> owners = new IdentityHashMap<>();

    /** Bytes reachable from exactly one root. */
    private long ownedBytes;

    /** Bytes reachable from more than one root. */
    private long sharedBytes;

    private Walk() {
    }

    /**
     * Visits everything reachable from the given root.
     *
     * @param root  the root object, may be null
     * @param owner the root index, zero or greater
     */
    void add(final Object root, final int owner) {
      if (root == null) {
        return;
      }
      final Deque<Object> objects = new ArrayDeque<>();
      final Deque<Integer> ownerOf = new ArrayDeque<>();
      objects.push(root);
      ownerOf.push(owner);
      while (!objects.isEmpty()) {
        final Object o = objects.pop();
        final int current = ownerOf.pop();
        final int[] known = owners.get(o);
        if (known == null) {
          if (owners.size() >= MAX_VISITED) {
            continue;
          }
          owners.put(o, new int[] {current});
          final long size = visit(o, child -> {
            objects.push(child);
            ownerOf.push(current);
          });
          if (current == SHARED) {
            sharedBytes += size;
          } else {
            ownedBytes += size;
          }
        } else if (known[0] != SHARED && known[0] != current) {
          known[0] = SHARED;
          final long size = visit(o, child -> {
            objects.push(child);
            ownerOf.push(SHARED);
          });
          ownedBytes -= size;
          sharedBytes += size;
        }
      }
    }

    /**
     * Returns the bytes reachable from exactly one root.
     *
     * @return the owned bytes
     */
    long ownedBytes() {
      return ownedBytes;
    }

    /**
     * Returns the bytes reachable from more than one root.
     *
     * @return the shared bytes
     */
    long sharedBytes() {
      return sharedBytes;
    }
  }

  // -----------------------------------------------------------------------
  // Layout
  // -----------------------------------------------------------------------

  /**
   * The JVM object layout.
   *
   * @param referenceSize the size of a reference field (4 with compressed
   *                      oops, 8 otherwise)
   * @param objectHeader  the object header size (12 with compressed class
   *                      pointers, 8 with compact headers, 16 otherwise)
   * @param alignment     the object alignment in bytes
   */
  record Layout(int referenceSize, int objectHeader, int alignment) {

    /** The heap size above which compressed oops cannot be used. */
    private static final long COMPRESSED_OOPS_LIMIT = 32L * 1024 * 1024 * 1024;

    /**
     * Returns the offset of the first element in an array whose elements
     * have the given size.
     *
     * @param elementSize the element size in bytes
     *
     * @return the array base offset in bytes
     */
    long arrayBase(final int elementSize) {
      final long lengthEnd = objectHeader + 4L;
      final long elementAlign = Math.min(elementSize, 8);
      return (lengthEnd + elementAlign - 1) / elementAlign * elementAlign;
    }

    /**
     * Detects the layout of the running JVM, falling back to the HotSpot
     * defaults when the diagnostic bean is unavailable.
     *
     * @return the detected layout, never null
     */
    static Layout detect() {
      final boolean defaultOops =
          Runtime.getRuntime().maxMemory() < COMPRESSED_OOPS_LIMIT;
      HotSpotDiagnosticMXBean bean = null;
      try {
        bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
      } catch (final RuntimeException | LinkageError e) {
        log.debug("HotSpot diagnostic bean unavailable: {}", e.getMessage());
      }
      final boolean compressedOops = flag(bean, "UseCompressedOops", defaultOops);
      final boolean compressedClass = flag(bean, "UseCompressedClassPointers", true);
      final boolean compactHeaders = flag(bean, "UseCompactObjectHeaders", false);
      final int alignment = (int) number(bean, "ObjectAlignmentInBytes", 8);
      final int header = compactHeaders ? 8 : compressedClass ? 12 : 16;
      return new Layout(compressedOops ? 4 : 8, header, alignment);
    }

    private static boolean flag(final HotSpotDiagnosticMXBean bean,
        final String name, final boolean fallback) {
      final String value = option(bean, name);
      return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static long number(final HotSpotDiagnosticMXBean bean,
        final String name, final long fallback) {
      final String value = option(bean, name);
      try {
        return value == null ? fallback : Long.parseLong(value);
      } catch (final NumberFormatException e) {
        return fallback;
      }
    }

    private static String option(final HotSpotDiagnosticMXBean bean,
        final String name) {
      if (bean == null) {
        return null;
      }
      try {
        return bean.getVMOption(name).getValue();
      } catch (final RuntimeException e) {
        return null;
      }
    }
  }
}
