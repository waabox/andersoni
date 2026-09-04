package org.waabox.andersoni.it;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.waabox.andersoni.snapshot.SnapshotSerializer;

/**
 * A deterministic text serializer for {@link Item}.
 *
 * <p>Cross-node patches require a serializer: the patched item travels in
 * the sync message as bytes produced by this class. It is also what the
 * catalog hashes, so it must emit the same bytes for the same items in the
 * same order on every node — hence a fixed field order and a plain
 * tab-separated encoding rather than anything reflective.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class ItemSerializer implements SnapshotSerializer<Item> {

  /** {@inheritDoc} */
  @Override
  public byte[] serialize(final List<Item> items) {
    final StringBuilder builder = new StringBuilder();
    for (final Item item : items) {
      builder.append(item.id()).append('\t').append(item.name()).append('\n');
    }
    return builder.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** {@inheritDoc} */
  @Override
  public List<Item> deserialize(final byte[] data) {
    final String raw = new String(data, StandardCharsets.UTF_8);
    final List<Item> items = new ArrayList<>();
    for (final String line : raw.split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      final int tab = line.indexOf('\t');
      items.add(new Item(Integer.parseInt(line.substring(0, tab)),
          line.substring(tab + 1)));
    }
    return items;
  }
}
