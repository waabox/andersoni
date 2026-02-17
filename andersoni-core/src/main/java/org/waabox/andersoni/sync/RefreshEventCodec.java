package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Static utility class for serializing and deserializing
 * {@link RefreshEvent} instances to and from JSON strings.
 *
 * <p>Uses Jackson's tree model ({@link JsonNode}) for lightweight
 * JSON processing without requiring full object binding or additional
 * modules. {@link Instant} values are stored as ISO-8601 strings.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class RefreshEventCodec {

  /** Shared ObjectMapper for tree model operations. */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Private constructor to prevent instantiation. */
  private RefreshEventCodec() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Serializes a {@link RefreshEvent} into a JSON string.
   *
   * <p>The resulting JSON contains exactly five fields:
   * {@code catalogName}, {@code sourceNodeId}, {@code version},
   * {@code hash}, and {@code timestamp}.
   *
   * @param event the event to serialize, never null.
   * @return the JSON representation of the event, never null.
   */
  public static String serialize(final RefreshEvent event) {
    Objects.requireNonNull(event, "event cannot be null");

    final ObjectNode node = MAPPER.createObjectNode();
    node.put("catalogName", event.catalogName());
    node.put("sourceNodeId", event.sourceNodeId());
    node.put("version", event.version());
    node.put("hash", event.hash());
    node.put("timestamp", event.timestamp().toString());

    return node.toString();
  }

  /**
   * Deserializes a JSON string into a {@link RefreshEvent}.
   *
   * <p>The JSON must contain the fields {@code catalogName},
   * {@code sourceNodeId}, {@code version}, {@code hash}, and
   * {@code timestamp}.
   *
   * @param json the JSON string to parse, never null.
   * @return the parsed {@link RefreshEvent}, never null.
   * @throws IllegalArgumentException if the JSON is malformed or missing
   *     required fields.
   */
  public static RefreshEvent deserialize(final String json) {
    Objects.requireNonNull(json, "json cannot be null");

    try {
      final JsonNode node = MAPPER.readTree(json);

      final String catalogName = requireField(node, "catalogName").asText();
      final String sourceNodeId = requireField(node, "sourceNodeId").asText();
      final long version = requireField(node, "version").asLong();
      final String hash = requireField(node, "hash").asText();
      final Instant timestamp = Instant.parse(
          requireField(node, "timestamp").asText()
      );

      return new RefreshEvent(
          catalogName, sourceNodeId, version, hash, timestamp
      );
    } catch (final IllegalArgumentException e) {
      throw e;
    } catch (final Exception e) {
      throw new IllegalArgumentException(
          "Failed to deserialize RefreshEvent from JSON: " + json, e
      );
    }
  }

  /** Returns the field node for the given key or throws if missing.
   *
   * @param node the parent JSON node.
   * @param field the field name to look up.
   * @return the field node, never null.
   * @throws IllegalArgumentException if the field is missing.
   */
  private static JsonNode requireField(final JsonNode node,
      final String field) {
    final JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException(
          "Missing field: " + field + " in JSON: " + node
      );
    }
    return value;
  }
}
