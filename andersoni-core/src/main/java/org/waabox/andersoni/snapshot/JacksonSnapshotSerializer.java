package org.waabox.andersoni.snapshot;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/**
 * A Jackson-based {@link SnapshotSerializer} that serializes and deserializes
 * catalog items to and from JSON byte arrays.
 *
 * <p>By default, the serializer is configured with:
 * <ul>
 *   <li>{@link JavaTimeModule} for {@code java.time} types
 *       ({@code LocalDate}, {@code LocalDateTime}, {@code Instant}, etc.)</li>
 *   <li>Field-level visibility ({@link PropertyAccessor#FIELD} set to
 *       {@link Visibility#ANY}), so it works with records, private fields,
 *       and classes without getters.</li>
 * </ul>
 *
 * <p>A custom {@link ObjectMapper} can be provided via the builder to
 * override the default configuration.
 *
 * <p>Usage with default ObjectMapper:
 * <pre>{@code
 * SnapshotSerializer<Event> serializer =
 *     JacksonSnapshotSerializer.forType(Event.class).build();
 * }</pre>
 *
 * <p>Usage with a custom ObjectMapper:
 * <pre>{@code
 * SnapshotSerializer<Event> serializer =
 *     JacksonSnapshotSerializer.forType(Event.class)
 *         .withObjectMapper(myMapper)
 *         .build();
 * }</pre>
 *
 * @param <T> the type of items this serializer handles
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class JacksonSnapshotSerializer<T>
    implements SnapshotSerializer<T> {

  /** The Jackson object mapper used for serialization. */
  private final ObjectMapper mapper;

  /** The resolved JavaType for {@code List<T>}. */
  private final JavaType listType;

  /**
   * Creates a new JacksonSnapshotSerializer.
   *
   * @param mapper   the object mapper, never null
   * @param itemType the class of the items, never null
   */
  private JacksonSnapshotSerializer(final ObjectMapper mapper,
      final Class<T> itemType) {
    this.mapper = mapper;
    this.listType = mapper.getTypeFactory()
        .constructCollectionType(List.class, itemType);
  }

  /**
   * Starts the builder for a new JacksonSnapshotSerializer of the given type.
   *
   * @param itemType the class of the items to serialize, never null
   * @param <T>      the type of items
   *
   * @return a new builder, never null
   *
   * @throws NullPointerException if itemType is null
   */
  public static <T> Builder<T> forType(final Class<T> itemType) {
    Objects.requireNonNull(itemType, "itemType must not be null");
    return new Builder<>(itemType);
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
      return mapper.readValue(data, listType);
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to deserialize items", e);
    }
  }

  /**
   * Builder for {@link JacksonSnapshotSerializer}.
   *
   * @param <T> the type of items
   *
   * @author waabox(waabox[at]gmail[dot]com)
   */
  public static final class Builder<T> {

    /** The class of the items. */
    private final Class<T> itemType;

    /** The optional custom ObjectMapper. */
    private ObjectMapper mapper;

    /**
     * Creates a new Builder.
     *
     * @param itemType the class of the items, never null
     */
    private Builder(final Class<T> itemType) {
      this.itemType = itemType;
    }

    /**
     * Sets a custom {@link ObjectMapper} to use instead of the default.
     *
     * <p>When a custom ObjectMapper is provided, the caller is responsible
     * for its configuration (modules, visibility, etc.).
     *
     * @param objectMapper the custom ObjectMapper, never null
     *
     * @return this builder for chaining, never null
     *
     * @throws NullPointerException if objectMapper is null
     */
    public Builder<T> withObjectMapper(final ObjectMapper objectMapper) {
      Objects.requireNonNull(objectMapper, "objectMapper must not be null");
      this.mapper = objectMapper;
      return this;
    }

    /**
     * Builds the serializer.
     *
     * <p>If no custom ObjectMapper was provided, a default one is created
     * with {@link JavaTimeModule} and field-level visibility.
     *
     * @return a new JacksonSnapshotSerializer, never null
     */
    public JacksonSnapshotSerializer<T> build() {
      final ObjectMapper effectiveMapper;
      if (mapper != null) {
        effectiveMapper = mapper;
      } else {
        effectiveMapper = new ObjectMapper();
        effectiveMapper.registerModule(new JavaTimeModule());
        effectiveMapper.setVisibility(
            PropertyAccessor.FIELD, Visibility.ANY);
      }
      return new JacksonSnapshotSerializer<>(effectiveMapper, itemType);
    }
  }
}
