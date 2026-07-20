package org.waabox.andersoni.snapshot;

import java.util.List;

/**
 * A strategy for serializing and deserializing catalog items to and from
 * a byte array representation.
 *
 * <p>Implementations define the serialization format (e.g. JSON, Protobuf,
 * Java serialization) used to persist catalog snapshots.
 *
 * <p><strong>Determinism is part of this contract.</strong> Beyond snapshot
 * persistence, {@code serialize} is what produces the catalog content hash —
 * the signal nodes use to decide whether they already hold the same data. The
 * same logical items must therefore always produce byte-identical output, on
 * any node and any JVM. In practice that means:
 *
 * <ul>
 *   <li>Order {@code Map} and {@code Set} entries by key (JVM iteration order
 *       is not stable across processes).</li>
 *   <li>Fix the property order of serialized objects (e.g. alphabetically)
 *       rather than relying on reflection order.</li>
 *   <li>Avoid embedding timestamps, identity hash codes, or any other value
 *       that varies between runs holding identical data.</li>
 * </ul>
 *
 * <p>A nondeterministic implementation does not corrupt data, but it defeats
 * the hash comparison: two nodes holding identical items compute different
 * hashes, so each sync event triggers a redundant reload and cross-node
 * convergence checks report divergence that is not real. See
 * {@link org.waabox.andersoni.DataLoader} for the matching requirement on
 * item order.
 *
 * @param <T> the type of items this serializer handles
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SnapshotSerializer<T> {

  /**
   * Serializes a list of catalog items into a byte array.
   *
   * <p>Must be deterministic: identical items must always produce identical
   * bytes. See the interface documentation for why this matters and how to
   * achieve it.
   *
   * @param items the items to serialize, never null
   * @return the serialized byte array, never null
   */
  byte[] serialize(List<T> items);

  /**
   * Deserializes a byte array back into a list of catalog items.
   *
   * @param data the byte array to deserialize, never null
   * @return the deserialized list of items, never null
   */
  List<T> deserialize(byte[] data);
}
