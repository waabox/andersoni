package org.waabox.andersoni.example.domain;

import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.waabox.andersoni.snapshot.SnapshotSerializer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/** Jackson-based {@link SnapshotSerializer} for {@link Event} instances.
 *
 * <p>Serializes and deserializes lists of events to and from JSON byte
 * arrays. Uses the {@link JavaTimeModule} to support {@code LocalDateTime}
 * fields.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public class EventSnapshotSerializer implements SnapshotSerializer<Event> {

  /** The type reference for deserializing a list of events. */
  private static final TypeReference<List<Event>> EVENT_LIST_TYPE =
      new TypeReference<>() { };

  /** The Jackson object mapper configured for event serialization. */
  private final ObjectMapper mapper;

  /** Creates a new EventSnapshotSerializer with a pre-configured
   * {@link ObjectMapper}.
   */
  public EventSnapshotSerializer() {
    mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
  }

  /** Serializes a list of events into a JSON byte array.
   *
   * @param items the events to serialize, never null
   * @return the serialized JSON bytes, never null
   * @throws UncheckedIOException if serialization fails
   */
  @Override
  public byte[] serialize(final List<Event> items) {
    Objects.requireNonNull(items, "items cannot be null");
    try {
      return mapper.writeValueAsBytes(items);
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to serialize events", e);
    }
  }

  /** Deserializes a JSON byte array into a list of events.
   *
   * @param data the byte array to deserialize, never null
   * @return the deserialized list of events, never null
   * @throws UncheckedIOException if deserialization fails
   */
  @Override
  public List<Event> deserialize(final byte[] data) {
    Objects.requireNonNull(data, "data cannot be null");
    try {
      return mapper.readValue(data, EVENT_LIST_TYPE);
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to deserialize events", e);
    }
  }
}
