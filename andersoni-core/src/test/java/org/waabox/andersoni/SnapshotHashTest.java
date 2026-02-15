package org.waabox.andersoni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for SHA-256 hash computation in {@link Catalog} and {@link Snapshot}.
 *
 * <p>Verifies deterministic hash behavior: identical data produces the same
 * hash, different data produces different hashes, and refreshing with the
 * same data retains the hash.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class SnapshotHashTest {

  /** A category domain object used for testing. */
  record Category(String name) {
    @Override
    public String toString() {
      return name;
    }
  }

  /** A product domain object used for testing. */
  record Product(String sku, Category category) {
    @Override
    public String toString() {
      return sku + ":" + category.name();
    }
  }

  @Test
  void whenBootstrapping_givenSameData_shouldProduceSameHash() {
    final Category tools = new Category("Tools");
    final Product hammer = new Product("HAMMER-01", tools);
    final Product wrench = new Product("WRENCH-02", tools);

    final List<Product> data = List.of(hammer, wrench);

    final Catalog<Product> catalog1 = Catalog.of(Product.class)
        .named("catalog-1")
        .data(data)
        .index("by-category").by(Product::category, Category::name)
        .build();

    final Catalog<Product> catalog2 = Catalog.of(Product.class)
        .named("catalog-2")
        .data(data)
        .index("by-category").by(Product::category, Category::name)
        .build();

    catalog1.bootstrap();
    catalog2.bootstrap();

    final String hash1 = catalog1.currentSnapshot().hash();
    final String hash2 = catalog2.currentSnapshot().hash();

    assertNotNull(hash1);
    assertNotNull(hash2);
    assertFalse(hash1.isEmpty());
    assertFalse(hash2.isEmpty());
    assertEquals(hash1, hash2,
        "Two catalogs bootstrapped with identical data must produce "
            + "the same hash");
  }

  @Test
  void whenBootstrapping_givenDifferentData_shouldProduceDifferentHash() {
    final Category tools = new Category("Tools");
    final Category paint = new Category("Paint");
    final Product hammer = new Product("HAMMER-01", tools);
    final Product brush = new Product("BRUSH-01", paint);

    final Catalog<Product> catalog1 = Catalog.of(Product.class)
        .named("catalog-1")
        .data(List.of(hammer))
        .index("by-category").by(Product::category, Category::name)
        .build();

    final Catalog<Product> catalog2 = Catalog.of(Product.class)
        .named("catalog-2")
        .data(List.of(brush))
        .index("by-category").by(Product::category, Category::name)
        .build();

    catalog1.bootstrap();
    catalog2.bootstrap();

    final String hash1 = catalog1.currentSnapshot().hash();
    final String hash2 = catalog2.currentSnapshot().hash();

    assertNotNull(hash1);
    assertNotNull(hash2);
    assertFalse(hash1.isEmpty());
    assertFalse(hash2.isEmpty());
    assertNotEquals(hash1, hash2,
        "Two catalogs bootstrapped with different data must produce "
            + "different hashes");
  }

  @Test
  void whenRefreshing_givenSameData_shouldRetainHash() {
    final Category tools = new Category("Tools");
    final Product hammer = new Product("HAMMER-01", tools);
    final Product wrench = new Product("WRENCH-02", tools);

    final List<Product> data = List.of(hammer, wrench);

    final Catalog<Product> catalog = Catalog.of(Product.class)
        .named("hash-retain")
        .loadWith(() -> data)
        .index("by-category").by(Product::category, Category::name)
        .build();

    catalog.bootstrap();

    final String hashAfterBootstrap = catalog.currentSnapshot().hash();
    assertNotNull(hashAfterBootstrap);
    assertFalse(hashAfterBootstrap.isEmpty());

    catalog.refresh();

    final String hashAfterRefresh = catalog.currentSnapshot().hash();
    assertNotNull(hashAfterRefresh);
    assertFalse(hashAfterRefresh.isEmpty());

    assertEquals(hashAfterBootstrap, hashAfterRefresh,
        "Refreshing with the same data must produce the same hash");
  }
}
