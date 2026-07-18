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
   * <p>The resulting JSON contains exactly six fields:
   * {@code catalogName}, {@code sourceNodeId}, {@code version},
   * {@code hash}, {@code timestamp}, and {@code kind}.
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
    node.put("kind", event.kind().name());

    return node.toString();
  }

  /**
   * Deserializes a JSON string into a {@link RefreshEvent}.
   *
   * <p>The JSON must contain the fields {@code catalogName},
   * {@code sourceNodeId}, {@code version}, {@code hash}, and
   * {@code timestamp}. The {@code kind} field is optional and defaults to
   * {@link RefreshKind#EVENT} when absent or unrecognized.
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
      final RefreshKind kind = parseKind(node);

      return new RefreshEvent(
          catalogName, sourceNodeId, version, hash, timestamp, kind
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

  /** Returns the message kind, defaulting to {@link RefreshKind#EVENT}.
   *
   * <p>Absent or unrecognized values default to {@code EVENT} so that
   * payloads produced by older nodes (which did not emit a {@code kind}
   * field) are still accepted on the wire.
   *
   * @param node the JSON object.
   * @return the parsed kind, never null.
   */
  private static RefreshKind parseKind(final JSONObject node) {
    if (!node.has("kind") || node.isNull("kind")) {
      return RefreshKind.EVENT;
    }
    try {
      return RefreshKind.valueOf(node.getString("kind"));
    } catch (final IllegalArgumentException e) {
      return RefreshKind.EVENT;
    }
  }
}
