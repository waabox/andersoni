package org.waabox.andersoni.example.domain;

import org.waabox.andersoni.snapshot.JacksonSnapshotSerializer;
import org.waabox.andersoni.snapshot.SnapshotSerializer;

import java.util.List;

/** Jackson-based {@link SnapshotSerializer} for {@link Event} instances.
 *
 * <p>Delegates to {@link JacksonSnapshotSerializer} with the default
 * configuration (field visibility, {@code JavaTimeModule}).
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public class EventSnapshotSerializer implements SnapshotSerializer<Event> {

  /** The delegate serializer. */
  private final SnapshotSerializer<Event> delegate =
      JacksonSnapshotSerializer.forType(Event.class).build();

  /** {@inheritDoc} */
  @Override
  public byte[] serialize(final List<Event> items) {
    return delegate.serialize(items);
  }

  /** {@inheritDoc} */
  @Override
  public List<Event> deserialize(final byte[] data) {
    return delegate.deserialize(data);
  }
}
