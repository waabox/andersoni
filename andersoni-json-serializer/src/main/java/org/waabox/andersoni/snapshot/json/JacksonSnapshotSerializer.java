package org.waabox.andersoni.snapshot.json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.waabox.andersoni.snapshot.SnapshotSerializer;

/** Jackson-based {@link SnapshotSerializer} implementation.
 *
 * <p>Serializes and deserializes lists of items to and from JSON byte
 * arrays using Jackson's {@link ObjectMapper}. Registers
 * {@link JavaTimeModule} by default to support {@code java.time} types.
 *
 * <p>Usage:
 * <pre>{@code
 * SnapshotSerializer<Event> serializer = new JacksonSnapshotSerializer<>(
 *     new TypeReference<List<Event>>() {}
 * );
 * }</pre>
 *
 * @param <T> the type of items this serializer handles
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public class JacksonSnapshotSerializer<T>
    implements SnapshotSerializer<T> {

  /** The Jackson object mapper, never null. */
  private final ObjectMapper mapper;

  /** The type reference for deserializing, never null. */
  private final TypeReference<List<T>> typeReference;

  /** Creates a new serializer with the given {@link ObjectMapper} and
   * type reference.
   *
   * @param theMapper the ObjectMapper to use, never null.
   * @param theTypeReference the type reference for deserialization,
   *     never null.
   */
  public JacksonSnapshotSerializer(final ObjectMapper theMapper,
      final TypeReference<List<T>> theTypeReference) {
    Objects.requireNonNull(theMapper, "mapper must not be null");
    Objects.requireNonNull(theTypeReference, "typeReference must not be null");
    mapper = theMapper;
    typeReference = theTypeReference;
  }

  /** Creates a new serializer with a default {@link ObjectMapper}
   * configured with {@link JavaTimeModule}.
   *
   * @param theTypeReference the type reference for deserialization,
   *     never null.
   */
  public JacksonSnapshotSerializer(
      final TypeReference<List<T>> theTypeReference) {
    this(defaultMapper(), theTypeReference);
  }

  /** {@inheritDoc} */
  @Override
  public byte[] serialize(final List<T> items) {
    Objects.requireNonNull(items, "items must not be null");
    try {
      return mapper.writeValueAsBytes(items);
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to serialize items", e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public List<T> deserialize(final byte[] data) {
    Objects.requireNonNull(data, "data must not be null");
    try {
      return Collections.unmodifiableList(mapper.readValue(data, typeReference));
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to deserialize items", e);
    }
  }

  /** Creates a default ObjectMapper with JavaTimeModule registered.
   *
   * @return a new ObjectMapper, never null.
   */
  private static ObjectMapper defaultMapper() {
    final ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    return mapper;
  }
}
