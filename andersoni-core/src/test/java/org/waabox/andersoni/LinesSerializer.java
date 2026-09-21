package org.waabox.andersoni;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.waabox.andersoni.snapshot.SnapshotSerializer;

/**
 * Serializes strings one per line; deterministic round trip.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
class LinesSerializer implements SnapshotSerializer<String> {

  @Override
  public byte[] serialize(final List<String> items) {
    return String.join("\n", items).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public List<String> deserialize(final byte[] data) {
    final String text = new String(data, StandardCharsets.UTF_8);
    return text.isEmpty() ? List.of() : Arrays.asList(text.split("\n"));
  }
}
