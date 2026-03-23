package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

import org.json.JSONException;
import org.json.JSONObject;
import org.waabox.andersoni.PatchOperation;

/** Static utility class for serializing and deserializing {@link SyncEvent}
 * instances to and from JSON strings.
 *
 * <p>Uses a {@code "type"} discriminator field to distinguish between
 * {@link RefreshEvent} ({@code "REFRESH"}) and {@link PatchEvent}
 * ({@code "PATCH"}).
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
public final class SyncEventCodec {

  private static final String TYPE_REFRESH = "REFRESH";
  private static final String TYPE_PATCH = "PATCH";

  private SyncEventCodec() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Serializes a {@link SyncEvent} into a JSON string.
   * @param event the event to serialize, never null
   * @return the JSON representation, never null
   */
  public static String serialize(final SyncEvent event) {
    Objects.requireNonNull(event, "event must not be null");

    final JSONObject json = new JSONObject();
    json.put("catalogName", event.catalogName());
    json.put("sourceNodeId", event.sourceNodeId());
    json.put("version", event.version());
    json.put("timestamp", event.timestamp().toString());

    if (event instanceof RefreshEvent r) {
      json.put("type", TYPE_REFRESH);
      json.put("hash", r.hash());
    } else if (event instanceof PatchEvent p) {
      json.put("type", TYPE_PATCH);
      json.put("operationType", p.operationType().name());
      json.put("payload", Base64.getEncoder().encodeToString(p.payload()));
    } else {
      throw new IllegalArgumentException(
          "Unknown SyncEvent type: " + event.getClass().getName());
    }

    return json.toString();
  }

  /** Deserializes a JSON string into a {@link SyncEvent}.
   * @param json the JSON string, never null
   * @return the parsed event, never null
   * @throws IllegalArgumentException if the JSON is malformed or has unknown
   *     type
   */
  public static SyncEvent deserialize(final String json) {
    Objects.requireNonNull(json, "json must not be null");

    try {
      final JSONObject node = new JSONObject(json);

      final String type = requireString(node, "type");
      final String catalogName = requireString(node, "catalogName");
      final String sourceNodeId = requireString(node, "sourceNodeId");
      final long version = node.getLong("version");
      final Instant timestamp = Instant.parse(
          requireString(node, "timestamp"));

      return switch (type) {
        case TYPE_REFRESH -> new RefreshEvent(
            catalogName, sourceNodeId, version,
            requireString(node, "hash"), timestamp);
        case TYPE_PATCH -> new PatchEvent(
            catalogName, sourceNodeId, version,
            PatchOperation.valueOf(requireString(node, "operationType")),
            Base64.getDecoder().decode(requireString(node, "payload")),
            timestamp);
        default -> throw new IllegalArgumentException(
            "Unknown event type: " + type);
      };
    } catch (final IllegalArgumentException e) {
      throw e;
    } catch (final JSONException e) {
      throw new IllegalArgumentException(
          "Failed to deserialize SyncEvent from JSON: " + json, e);
    }
  }

  private static String requireString(final JSONObject node,
      final String field) {
    if (!node.has(field) || node.isNull(field)) {
      throw new IllegalArgumentException(
          "Missing field: " + field + " in JSON: " + node);
    }
    return node.getString(field);
  }
}
