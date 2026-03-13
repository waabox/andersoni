package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Static utility class for serializing and deserializing
 * {@link SyncEvent} instances to and from JSON strings.
 *
 * <p>Uses a {@code "type"} discriminator field to distinguish between
 * {@link RefreshEvent} and {@link PatchEvent}. Uses {@link JSONObject}
 * from org.json for lightweight JSON processing. {@link Instant} values
 * are stored as ISO-8601 strings. {@code byte[]} fields are Base64-encoded.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class SyncEventCodec {

  /** Type discriminator for RefreshEvent. */
  private static final String TYPE_REFRESH = "refresh";

  /** Type discriminator for PatchEvent. */
  private static final String TYPE_PATCH = "patch";

  /** Private constructor to prevent instantiation. */
  private SyncEventCodec() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Serializes a {@link SyncEvent} into a JSON string.
   *
   * @param event the event to serialize, never null
   * @return the JSON representation, never null
   */
  public static String serialize(final SyncEvent event) {
    Objects.requireNonNull(event, "event cannot be null");

    final JSONObject node = new JSONObject();
    node.put("catalogName", event.catalogName());
    node.put("sourceNodeId", event.sourceNodeId());
    node.put("version", event.version());
    node.put("hash", event.hash());
    node.put("timestamp", event.timestamp().toString());

    if (event instanceof PatchEvent patch) {
      node.put("type", TYPE_PATCH);
      node.put("patchType", patch.patchType().name());
      node.put("items", Base64.getEncoder().encodeToString(patch.items()));
    } else {
      node.put("type", TYPE_REFRESH);
    }

    return node.toString();
  }

  /**
   * Deserializes a JSON string into a {@link SyncEvent}.
   *
   * <p>If the JSON has no {@code "type"} field, it is treated as a
   * {@link RefreshEvent} for backward compatibility.
   *
   * @param json the JSON string to parse, never null
   * @return the parsed event, never null
   * @throws IllegalArgumentException if the JSON is malformed
   */
  public static SyncEvent deserialize(final String json) {
    Objects.requireNonNull(json, "json cannot be null");

    try {
      final JSONObject node = new JSONObject(json);

      final String catalogName = requireString(node, "catalogName");
      final String sourceNodeId = requireString(node, "sourceNodeId");
      final long version = node.getLong("version");
      final String hash = requireString(node, "hash");
      final Instant timestamp = Instant.parse(
          requireString(node, "timestamp"));

      final String type = node.optString("type", TYPE_REFRESH);

      if (TYPE_PATCH.equals(type)) {
        final PatchType patchType = PatchType.valueOf(
            requireString(node, "patchType"));
        final byte[] items = Base64.getDecoder().decode(
            requireString(node, "items"));
        return new PatchEvent(catalogName, sourceNodeId, version, hash,
            timestamp, patchType, items);
      }

      return new RefreshEvent(
          catalogName, sourceNodeId, version, hash, timestamp);

    } catch (final IllegalArgumentException e) {
      throw e;
    } catch (final JSONException e) {
      throw new IllegalArgumentException(
          "Failed to deserialize SyncEvent from JSON: " + json, e);
    }
  }

  /**
   * Returns the string value for the given key or throws if missing.
   *
   * @param node  the JSON object
   * @param field the field name to look up
   * @return the string value, never null
   * @throws IllegalArgumentException if the field is missing
   */
  private static String requireString(final JSONObject node,
      final String field) {
    if (!node.has(field) || node.isNull(field)) {
      throw new IllegalArgumentException(
          "Missing field: " + field + " in JSON: " + node);
    }
    return node.getString(field);
  }
}
