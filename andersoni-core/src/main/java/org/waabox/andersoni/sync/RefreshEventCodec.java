package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Objects;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Static utility class for serializing and deserializing
 * {@link RefreshEvent} instances to and from JSON strings.
 *
 * <p>Uses {@link JSONObject} from org.json for lightweight
 * JSON processing without requiring full object binding or additional
 * modules. {@link Instant} values are stored as ISO-8601 strings.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class RefreshEventCodec {

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

    final JSONObject node = new JSONObject();
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
      final JSONObject node = new JSONObject(json);

      final String catalogName = requireString(node, "catalogName");
      final String sourceNodeId = requireString(node, "sourceNodeId");
      final long version = node.getLong("version");
      final String hash = requireString(node, "hash");
      final Instant timestamp = Instant.parse(requireString(node, "timestamp"));

      return new RefreshEvent(
          catalogName, sourceNodeId, version, hash, timestamp
      );
    } catch (final IllegalArgumentException e) {
      throw e;
    } catch (final JSONException e) {
      throw new IllegalArgumentException(
          "Failed to deserialize RefreshEvent from JSON: " + json, e
      );
    }
  }

  /** Returns the string value for the given key or throws if missing.
   *
   * @param node the JSON object.
   * @param field the field name to look up.
   * @return the string value, never null.
   * @throws IllegalArgumentException if the field is missing.
   */
  private static String requireString(final JSONObject node,
      final String field) {
    if (!node.has(field) || node.isNull(field)) {
      throw new IllegalArgumentException(
          "Missing field: " + field + " in JSON: " + node
      );
    }
    return node.getString(field);
  }
}
