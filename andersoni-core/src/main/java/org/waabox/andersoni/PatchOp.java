package org.waabox.andersoni;

import java.util.Objects;

/**
 * A single change in a {@link Catalog#apply(java.util.List) batch}.
 *
 * <p>The variants mirror the individual operations and keep their
 * contracts: {@link Replace} and {@link Remove} locate their target through
 * an index lookup, {@link Insert} performs none, and {@link Upsert}
 * replaces when the lookup finds something and inserts when it does not.
 *
 * <p>Within a batch, operations apply in order against the accumulating
 * state, so a later one sees what an earlier one did — inserting an item
 * and then replacing it in the same batch works.
 *
 * @param <T> the catalog item type
 *
 * @author borafael
 */
public sealed interface PatchOp<T> {

  /**
   * Replaces the item found at {@code (indexName, key)}.
   *
   * <p>Unlike the standalone {@link Catalog#replace}, which reports a miss
   * by returning {@code false}, a replace that matches nothing fails the
   * whole batch: a batch is atomic, so there is no meaningful way to report
   * "operation 37 did nothing" while the rest applied.
   *
   * @param indexName the index to look the item up in, never null
   * @param key       the lookup key, never null
   * @param item      the replacement, never null
   * @param <T>       the catalog item type
   */
  record Replace<T>(String indexName, Object key, T item)
      implements PatchOp<T> {

    /** Canonical constructor validating mandatory fields. */
    public Replace {
      Objects.requireNonNull(indexName, "indexName must not be null");
      Objects.requireNonNull(key, "key must not be null");
      Objects.requireNonNull(item, "item must not be null");
    }
  }

  /**
   * Adds an item, displacing nothing.
   *
   * @param item the item to add, never null
   * @param <T>  the catalog item type
   */
  record Insert<T>(T item) implements PatchOp<T> {

    /** Canonical constructor validating mandatory fields. */
    public Insert {
      Objects.requireNonNull(item, "item must not be null");
    }
  }

  /**
   * Removes the item found at {@code (indexName, key)}.
   *
   * <p>A removal that matches nothing is skipped rather than failing the
   * batch, keeping the idempotence that makes removal safe to redeliver.
   *
   * @param indexName the index to look the item up in, never null
   * @param key       the lookup key, never null
   * @param <T>       the catalog item type
   */
  record Remove<T>(String indexName, Object key) implements PatchOp<T> {

    /** Canonical constructor validating mandatory fields. */
    public Remove {
      Objects.requireNonNull(indexName, "indexName must not be null");
      Objects.requireNonNull(key, "key must not be null");
    }
  }

  /**
   * Replaces the item found at {@code (indexName, key)}, or inserts
   * {@code item} when the lookup finds nothing.
   *
   * @param indexName the index to look the item up in, never null
   * @param key       the lookup key, never null
   * @param item      the item to store, never null
   * @param <T>       the catalog item type
   */
  record Upsert<T>(String indexName, Object key, T item)
      implements PatchOp<T> {

    /** Canonical constructor validating mandatory fields. */
    public Upsert {
      Objects.requireNonNull(indexName, "indexName must not be null");
      Objects.requireNonNull(key, "key must not be null");
      Objects.requireNonNull(item, "item must not be null");
    }
  }
}
