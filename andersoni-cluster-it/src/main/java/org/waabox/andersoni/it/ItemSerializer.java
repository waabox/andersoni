package org.waabox.andersoni.it;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.waabox.andersoni.snapshot.SnapshotSerializer;

/**
 * Line-based, deterministic serializer for {@link Item}: {@code id|name}.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class ItemSerializer implements SnapshotSerializer<Item> {

  @Override
  public byte[] serialize(final List<Item> items) {
    final StringBuilder builder = new StringBuilder();
    for (final Item item : items) {
      builder.append(item.id()).append('|').append(item.name()).append('\n');
    }
    return builder.toString().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public List<Item> deserialize(final byte[] data) {
    final List<Item> items = new ArrayList<>();
    for (final String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      final int separator = line.indexOf('|');
      items.add(new Item(Integer.parseInt(line.substring(0, separator)),
          line.substring(separator + 1)));
    }
    return items;
  }
}
