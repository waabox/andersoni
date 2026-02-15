package org.waabox.andersoni.snapshot;

import java.util.List;

/**
 * A strategy for serializing and deserializing catalog items to and from
 * a byte array representation.
 *
 * <p>Implementations define the serialization format (e.g. JSON, Protobuf,
 * Java serialization) used to persist catalog snapshots.
 *
 * @param <T> the type of items this serializer handles
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public interface SnapshotSerializer<T> {

  /**
   * Serializes a list of catalog items into a byte array.
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
