package org.waabox.andersoni.it;

/**
 * A trivial catalog item used by the cluster integration test.
 *
 * <p>The catalog configures an {@link ItemSerializer}, so the content hash is
 * computed over its bytes: two nodes that hold the same items in the same
 * order produce the same snapshot hash. That is what lets the test assert
 * convergence by comparing hashes across nodes, and what cross-node patches
 * use both as their precondition and to carry the patched item.
 *
 * @param id   the item identifier
 * @param name the item name, never null
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public record Item(int id, String name) {
}
