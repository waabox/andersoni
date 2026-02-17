package org.waabox.andersoni;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import org.waabox.andersoni.snapshot.SnapshotSerializer;

/**
 * Groups a data type with its {@link DataLoader} (or static data) and all
 * its {@link IndexDefinition index definitions} into a single cohesive unit.
 *
 * <p>A Catalog manages the lifecycle of loading data, building indices, and
 * providing lock-free search access through immutable {@link Snapshot}s.
 * One load operation populates all indices simultaneously, and the resulting
 * snapshot is atomically swapped for concurrent readers.
 *
 * <p>Instances are created through the fluent builder starting with
 * {@link #of(Class)}.
 *
 * <p>Usage with a DataLoader:
 * <pre>{@code
 * Catalog<Event> catalog = Catalog.of(Event.class)
 *     .named("events")
 *     .loadWith(() -> eventRepository.findAll())
 *     .serializer(new EventSerializer())
 *     .refreshEvery(Duration.ofMinutes(5))
 *     .index("by-venue").by(Event::getVenue, Venue::getName)
 *     .index("by-sport").by(Event::getSport, Sport::getName)
 *     .build();
 * }</pre>
 *
 * <p>Usage with static data:
 * <pre>{@code
 * Catalog<Event> catalog = Catalog.of(Event.class)
 *     .named("events")
 *     .data(List.of(e1, e2))
 *     .index("by-venue").by(Event::getVenue, Venue::getName)
 *     .build();
 * }</pre>
 *
 * @param <T> the type of data items held in this catalog
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class Catalog<T> {

  /** The hex characters used for hash string conversion. */
  private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

  /** Delimiter byte used to separate items in hash computation, preventing
   *  collisions between concatenated toString() representations. */
  private static final byte[] ITEM_DELIMITER = new byte[]{0x00};

  /** The current snapshot, atomically swapped on refresh. */
  private final AtomicReference<Snapshot<T>> current;

  /** The lock used to serialize write operations. */
  private final ReentrantLock refreshLock;

  /** The monotonically increasing version counter. */
  private final AtomicLong versionCounter;

  /** The index definitions for this catalog. */
  private final List<IndexDefinition<T>> indexDefinitions;

  /** The data loader, null if using static data. */
  private final DataLoader<T> dataLoader;

  /** The initial static data, null if using a DataLoader. */
  private final List<T> initialData;

  /** The optional serializer for snapshot persistence. */
  private final SnapshotSerializer<T> serializer;

  /** The optional refresh interval. */
  private final Duration refreshInterval;

  /** The name of this catalog. */
  private final String name;

  /**
   * Creates a new Catalog instance.
   *
   * @param name             the catalog name, never null
   * @param dataLoader       the data loader, may be null if using static data
   * @param initialData      the initial static data, may be null if using
   *                         a DataLoader
   * @param indexDefinitions the index definitions, never null
   * @param serializer       the optional serializer, may be null
   * @param refreshInterval  the optional refresh interval, may be null
   */
  private Catalog(final String name,
      final DataLoader<T> dataLoader,
      final List<T> initialData,
      final List<IndexDefinition<T>> indexDefinitions,
      final SnapshotSerializer<T> serializer,
      final Duration refreshInterval) {
    this.name = name;
    this.dataLoader = dataLoader;
    this.initialData = initialData;
    this.indexDefinitions = Collections.unmodifiableList(
        new ArrayList<>(indexDefinitions));
    this.serializer = serializer;
    this.refreshInterval = refreshInterval;
    this.current = new AtomicReference<>(Snapshot.empty());
    this.refreshLock = new ReentrantLock();
    this.versionCounter = new AtomicLong(0);
  }

  /**
   * Starts the fluent builder for a new Catalog of the given type.
   *
   * <p>The type parameter is used for type inference in the builder chain.
   *
   * @param type the class of the data items, never null
   * @param <T>  the type of data items
   *
   * @return the first step of the builder chain, never null
   *
   * @throws NullPointerException if type is null
   */
  public static <T> NameStep<T> of(final Class<T> type) {
    Objects.requireNonNull(type, "type must not be null");
    return new NameStep<>();
  }

  /**
   * Loads data (via DataLoader or initial static data), builds all index
   * maps, creates a Snapshot, and atomically sets the current reference.
   *
   * <p>This method is protected by the refresh lock to prevent concurrent
   * write operations from corrupting state. Read operations via
   * {@link #search(String, Object)} remain lock-free during bootstrap.
   */
  public void bootstrap() {
    refreshLock.lock();
    try {
      final List<T> data;
      if (dataLoader != null) {
        data = dataLoader.load();
        Objects.requireNonNull(data,
            "DataLoader.load() must not return null for catalog '"
                + name + "'");
      } else {
        data = initialData;
      }
      buildAndSwapSnapshot(data);
    } finally {
      refreshLock.unlock();
    }
  }

  /**
   * Re-executes the DataLoader, rebuilds all indices, and atomically swaps
   * to the new Snapshot.
   *
   * <p>This method is only available for catalogs created with
   * {@code .loadWith()}. Calling it on a catalog created with
   * {@code .data()} will throw {@link IllegalStateException}.
   *
   * @throws IllegalStateException if this catalog was created with static
   *                               data (no DataLoader configured)
   */
  public void refresh() {
    if (dataLoader == null) {
      throw new IllegalStateException(
          "Cannot refresh a catalog created with static data. "
              + "Use refresh(List<T>) to provide new data explicitly.");
    }
    refreshLock.lock();
    try {
      final List<T> data = dataLoader.load();
      Objects.requireNonNull(data,
          "DataLoader.load() must not return null for catalog '"
              + name + "'");
      buildAndSwapSnapshot(data);
    } finally {
      refreshLock.unlock();
    }
  }

  /**
   * Accepts new data directly, rebuilds all indices, and atomically swaps
   * to the new Snapshot.
   *
   * <p>This method works for both DataLoader-based and static data catalogs.
   *
   * @param newData the new data to load into the catalog, never null
   *
   * @throws NullPointerException if newData is null
   */
  public void refresh(final List<T> newData) {
    Objects.requireNonNull(newData, "newData must not be null");
    refreshLock.lock();
    try {
      buildAndSwapSnapshot(newData);
    } finally {
      refreshLock.unlock();
    }
  }

  /**
   * Searches the specified index for items matching the given key.
   *
   * <p>Delegates to the current {@link Snapshot#search(String, Object)}.
   * This method is lock-free and safe for concurrent use.
   *
   * @param indexName the name of the index to search, never null
   * @param key       the key to look up in the index, never null
   *
   * @return an unmodifiable list of matching items, never null
   */
  public List<T> search(final String indexName, final Object key) {
    Objects.requireNonNull(indexName, "indexName must not be null");
    Objects.requireNonNull(key, "key must not be null");
    return current.get().search(indexName, key);
  }

  /**
   * Returns the name of this catalog.
   *
   * @return the catalog name, never null
   */
  public String name() {
    return name;
  }

  /**
   * Returns the current snapshot for external use (e.g., hash comparison).
   *
   * @return the current snapshot, never null
   */
  public Snapshot<T> currentSnapshot() {
    return current.get();
  }

  /**
   * Returns the optional serializer for this catalog.
   *
   * @return an Optional containing the serializer if configured,
   *         or empty if not
   */
  public Optional<SnapshotSerializer<T>> serializer() {
    return Optional.ofNullable(serializer);
  }

  /**
   * Returns the optional refresh interval for this catalog.
   *
   * @return an Optional containing the refresh interval if configured,
   *         or empty if not
   */
  public Optional<Duration> refreshInterval() {
    return Optional.ofNullable(refreshInterval);
  }

  /**
   * Returns whether this catalog has a DataLoader configured.
   *
   * @return true if this catalog was created with a DataLoader,
   *         false if it uses static data
   */
  public boolean hasDataLoader() {
    return dataLoader != null;
  }

  /**
   * Returns statistics about this catalog, including per-index memory
   * estimation based on the current snapshot.
   *
   * <p>This method is lock-free and delegates to the current snapshot.
   *
   * @return the catalog info, never null
   */
  public CatalogInfo info() {
    final Snapshot<T> snapshot = current.get();
    final List<IndexInfo> indexInfos = snapshot.indexInfo();
    final long totalSize = indexInfos.stream()
        .mapToLong(IndexInfo::estimatedSizeBytes).sum();
    return new CatalogInfo(name, snapshot.data().size(), indexInfos,
        totalSize);
  }

  /**
   * Builds all index maps from the given data, computes the hash, creates
   * a new Snapshot, and atomically swaps the current reference.
   *
   * <p>Must be called while holding the refresh lock.
   *
   * @param data the data to build the snapshot from, never null
   */
  private void buildAndSwapSnapshot(final List<T> data) {
    final Map<String, Map<Object, List<T>>> indices = new HashMap<>();
    for (final IndexDefinition<T> indexDef : indexDefinitions) {
      indices.put(indexDef.name(), indexDef.buildIndex(data));
    }

    final long version = versionCounter.incrementAndGet();
    final String hash = computeHash(data);

    final Snapshot<T> snapshot = Snapshot.of(data, indices, version, hash);
    current.set(snapshot);
  }

  /**
   * Computes a SHA-256 hash of the given data.
   *
   * <p>If a serializer is available, the hash is computed over the
   * serialized bytes. Otherwise, the hash is computed over the
   * {@code toString()} representation of each item concatenated together.
   *
   * <p><strong>Warning:</strong> Without a serializer, hash computation
   * falls back to {@code toString()}, which is unreliable for change
   * detection if domain objects do not override it. In that case, the
   * default {@code Object.toString()} includes the identity hash code,
   * causing every snapshot to produce a different hash even when the data
   * is identical. Always provide a {@link SnapshotSerializer} for
   * production use to ensure deterministic hash computation.
   *
   * @param data the data to hash, never null
   *
   * @return the hex-encoded SHA-256 hash string, never null
   */
  private String computeHash(final List<T> data) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");

      if (serializer != null) {
        final byte[] serialized = serializer.serialize(data);
        digest.update(serialized);
      } else {
        for (final T item : data) {
          final byte[] bytes = item.toString()
              .getBytes(StandardCharsets.UTF_8);
          digest.update(bytes);
          digest.update(ITEM_DELIMITER);
        }
      }

      return toHexString(digest.digest());
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }

  /**
   * Converts a byte array to a lowercase hex string.
   *
   * @param bytes the bytes to convert, never null
   *
   * @return the hex string representation, never null
   */
  private static String toHexString(final byte[] bytes) {
    final char[] hexChars = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      final int v = bytes[i] & 0xFF;
      hexChars[i * 2] = HEX_CHARS[v >>> 4];
      hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
    }
    return new String(hexChars);
  }

  /**
   * First step of the Catalog builder: collects the catalog name.
   *
   * @param <T> the type of data items
   */
  public static final class NameStep<T> {

    /** Creates a new NameStep. */
    private NameStep() {
    }

    /**
     * Sets the name for the catalog being built.
     *
     * @param name the catalog name, never null or empty
     *
     * @return the next step in the builder chain, never null
     *
     * @throws NullPointerException     if name is null
     * @throws IllegalArgumentException if name is empty
     */
    public DataSourceStep<T> named(final String name) {
      Objects.requireNonNull(name, "name must not be null");
      if (name.isEmpty()) {
        throw new IllegalArgumentException("name must not be empty");
      }
      return new DataSourceStep<>(name);
    }
  }

  /**
   * Second step of the Catalog builder: collects the data source
   * (either a DataLoader or static data).
   *
   * @param <T> the type of data items
   */
  public static final class DataSourceStep<T> {

    /** The catalog name. */
    private final String name;

    /**
     * Creates a new DataSourceStep.
     *
     * @param name the catalog name, never null
     */
    private DataSourceStep(final String name) {
      this.name = name;
    }

    /**
     * Configures this catalog to load data via a {@link DataLoader}.
     *
     * @param loader the data loader, never null
     *
     * @return the next step in the builder chain, never null
     *
     * @throws NullPointerException if loader is null
     */
    public BuildStep<T> loadWith(final DataLoader<T> loader) {
      Objects.requireNonNull(loader, "loader must not be null");
      return new BuildStep<>(name, loader, null);
    }

    /**
     * Configures this catalog with static data instead of a DataLoader.
     *
     * @param data the static data items, never null
     *
     * @return the next step in the builder chain, never null
     *
     * @throws NullPointerException if data is null
     */
    public BuildStep<T> data(final List<T> data) {
      Objects.requireNonNull(data, "data must not be null");
      return new BuildStep<>(name, null, List.copyOf(data));
    }
  }

  /**
   * Final step of the Catalog builder: collects optional configuration
   * (serializer, refresh interval) and index definitions, then builds
   * the Catalog.
   *
   * @param <T> the type of data items
   */
  public static final class BuildStep<T> {

    /** The catalog name. */
    private final String name;

    /** The data loader, may be null. */
    private final DataLoader<T> dataLoader;

    /** The static initial data, may be null. */
    private final List<T> initialData;

    /** The optional serializer. */
    private SnapshotSerializer<T> serializer;

    /** The optional refresh interval. */
    private Duration refreshInterval;

    /** The accumulated index definitions. */
    private final List<IndexDefinition<T>> indexDefinitions;

    /** The set of registered index names for duplicate detection. */
    private final Set<String> indexNames;

    /**
     * Creates a new BuildStep.
     *
     * @param name        the catalog name, never null
     * @param dataLoader  the data loader, may be null
     * @param initialData the static initial data, may be null
     */
    private BuildStep(final String name,
        final DataLoader<T> dataLoader,
        final List<T> initialData) {
      this.name = name;
      this.dataLoader = dataLoader;
      this.initialData = initialData;
      this.indexDefinitions = new ArrayList<>();
      this.indexNames = new HashSet<>();
    }

    /**
     * Sets the optional serializer for snapshot persistence and hash
     * computation.
     *
     * @param snapshotSerializer the serializer, never null
     *
     * @return this builder step for chaining, never null
     *
     * @throws NullPointerException if snapshotSerializer is null
     */
    public BuildStep<T> serializer(
        final SnapshotSerializer<T> snapshotSerializer) {
      Objects.requireNonNull(snapshotSerializer,
          "serializer must not be null");
      this.serializer = snapshotSerializer;
      return this;
    }

    /**
     * Sets the optional refresh interval for automatic refresh scheduling.
     *
     * @param interval the refresh interval, never null
     *
     * @return this builder step for chaining, never null
     *
     * @throws NullPointerException if interval is null
     */
    public BuildStep<T> refreshEvery(final Duration interval) {
      Objects.requireNonNull(interval, "interval must not be null");
      this.refreshInterval = interval;
      return this;
    }

    /**
     * Starts defining a new index with the given name.
     *
     * <p>Returns an {@link IndexStep} that requires a call to
     * {@link IndexStep#by(Function, Function)} to complete the index
     * definition and return to this builder for further chaining.
     *
     * @param indexName the name of the index, never null or empty
     *
     * @return an IndexStep for defining the key extraction, never null
     *
     * @throws NullPointerException     if indexName is null
     * @throws IllegalArgumentException if indexName is empty
     */
    public IndexStep<T> index(final String indexName) {
      Objects.requireNonNull(indexName, "indexName must not be null");
      if (indexName.isEmpty()) {
        throw new IllegalArgumentException("indexName must not be empty");
      }
      return new IndexStep<>(this, indexName);
    }

    /**
     * Builds the Catalog with all the accumulated configuration.
     *
     * @return a new Catalog instance, never null
     *
     * @throws IllegalStateException if no index definitions have been added
     */
    public Catalog<T> build() {
      if (indexDefinitions.isEmpty()) {
        throw new IllegalStateException(
            "At least one index definition is required");
      }
      return new Catalog<>(name, dataLoader, initialData,
          indexDefinitions, serializer, refreshInterval);
    }

    /**
     * Adds an index definition to the builder. Package-private, called
     * by {@link IndexStep}.
     *
     * @param indexDefinition the index definition to add, never null
     *
     * @throws IllegalArgumentException if an index with the same name
     *                                  has already been added
     */
    void addIndex(final IndexDefinition<T> indexDefinition) {
      if (!indexNames.add(indexDefinition.name())) {
        throw new IllegalArgumentException(
            "Duplicate index name: '" + indexDefinition.name() + "'");
      }
      indexDefinitions.add(indexDefinition);
    }
  }

  /**
   * Intermediate builder step for defining the key extraction functions
   * of an index.
   *
   * <p>After calling {@link #by(Function, Function)}, control returns to
   * the {@link BuildStep} for further chaining.
   *
   * @param <T> the type of data items
   */
  public static final class IndexStep<T> {

    /** The parent builder step. */
    private final BuildStep<T> buildStep;

    /** The index name. */
    private final String indexName;

    /**
     * Creates a new IndexStep.
     *
     * @param buildStep the parent builder step, never null
     * @param indexName the index name, never null
     */
    private IndexStep(final BuildStep<T> buildStep, final String indexName) {
      this.buildStep = buildStep;
      this.indexName = indexName;
    }

    /**
     * Defines the two-step key extraction by composing two functions.
     *
     * <p>The first function extracts an intermediate value from the data
     * item, and the second function extracts the final index key from
     * that intermediate value. For example:
     * {@code by(Event::venue, Venue::name)} extracts the venue name.
     *
     * @param first  the function to extract the intermediate value,
     *               never null
     * @param second the function to extract the key from the intermediate
     *               value, never null
     * @param <I>    the type of the intermediate value
     *
     * @return the parent BuildStep for further chaining, never null
     *
     * @throws NullPointerException if first or second is null
     */
    public <I> BuildStep<T> by(final Function<T, I> first,
        final Function<I, ?> second) {
      final IndexDefinition<T> indexDef =
          IndexDefinition.<T>named(indexName).by(first, second);
      buildStep.addIndex(indexDef);
      return buildStep;
    }
  }
}
