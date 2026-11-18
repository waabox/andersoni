package org.waabox.andersoni;

import java.util.List;
import java.util.Objects;

/**
 * Holds aggregated statistics for a catalog, including per-index info,
 * the estimated memory footprint, and registered view metadata.
 *
 * <p>The footprint is split into the items ({@code dataEstimatedSizeBytes})
 * and the index structures built over them
 * ({@code indexEstimatedSizeBytes}); {@link #totalEstimatedSizeBytes()} is
 * their sum.
 *
 * @param catalogName             the catalog name, never null
 * @param itemCount               the total number of data items
 * @param indices                 the per-index statistics, never null,
 *                                unmodifiable
 * @param dataEstimatedSizeBytes  the estimated size of the items, the
 *                                objects they reference and their views
 * @param indexEstimatedSizeBytes the sum of all index estimated sizes
 * @param viewCount               the number of registered view types
 * @param viewTypeNames           the simple class names of registered view
 *                                types, never null, unmodifiable
 */
public record CatalogInfo(
    String catalogName,
    int itemCount,
    List<IndexInfo> indices,
    long dataEstimatedSizeBytes,
    long indexEstimatedSizeBytes,
    int viewCount,
    List<String> viewTypeNames) {

  /**
   * Creates a new CatalogInfo instance.
   *
   * @param catalogName             the catalog name, never null
   * @param itemCount               the total number of data items
   * @param indices                 the per-index statistics, never null
   * @param dataEstimatedSizeBytes  the estimated size of the items
   * @param indexEstimatedSizeBytes the sum of all index estimated sizes
   * @param viewCount               the number of registered view types
   * @param viewTypeNames           the simple class names of registered view
   *                                types, never null
   *
   * @throws NullPointerException if catalogName, indices, or viewTypeNames
   *                              is null
   */
  public CatalogInfo {
    Objects.requireNonNull(catalogName, "catalogName must not be null");
    Objects.requireNonNull(indices, "indices must not be null");
    Objects.requireNonNull(viewTypeNames, "viewTypeNames must not be null");
    indices = List.copyOf(indices);
    viewTypeNames = List.copyOf(viewTypeNames);
  }

  /**
   * Creates a CatalogInfo from a single size figure, kept for source
   * compatibility. The size is treated as index size and the data size as
   * zero, matching what the figure meant before data was estimated.
   *
   * @param catalogName             the catalog name, never null
   * @param itemCount               the total number of data items
   * @param indices                 the per-index statistics, never null
   * @param totalEstimatedSizeBytes the sum of all index estimated sizes
   * @param viewCount               the number of registered view types
   * @param viewTypeNames           the simple class names of registered view
   *                                types, never null
   *
   * @deprecated use the canonical constructor, which takes the data and
   *             index sizes separately
   */
  @Deprecated
  public CatalogInfo(final String catalogName, final int itemCount,
      final List<IndexInfo> indices, final long totalEstimatedSizeBytes,
      final int viewCount, final List<String> viewTypeNames) {
    this(catalogName, itemCount, indices, 0L, totalEstimatedSizeBytes,
        viewCount, viewTypeNames);
  }

  /**
   * Returns the estimated memory footprint of the catalog: its items plus
   * every index.
   *
   * @return the total estimated size in bytes
   */
  public long totalEstimatedSizeBytes() {
    return dataEstimatedSizeBytes + indexEstimatedSizeBytes;
  }

  /**
   * Returns the total estimated memory size in megabytes.
   *
   * @return the total estimated size in MB
   */
  public double totalEstimatedSizeMB() {
    return totalEstimatedSizeBytes() / (1024.0 * 1024.0);
  }
}
