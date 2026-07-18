package org.waabox.andersoni.it;

/**
 * A trivial catalog item used by the cluster integration test.
 *
 * <p>Being a {@code record}, its {@code toString()} is content-based and
 * deterministic. Andersoni computes a catalog's content hash over each item's
 * {@code toString()} when no serializer is configured, so two nodes that load
 * the same rows in the same order produce the same snapshot hash. That is what
 * lets the test assert convergence by comparing hashes across nodes.
 *
 * @param id   the item identifier
 * @param name the item name, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record Item(int id, String name) {
}
