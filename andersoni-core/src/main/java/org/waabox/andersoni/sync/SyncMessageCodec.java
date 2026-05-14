package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Unified JSON codec for {@link SyncMessage} subtypes.
 *
 * <p>Serialized payloads carry a {@code "type"} discriminator
 * ({@code "refresh"} or {@code "patch"}) and the type-specific fields.
 * Byte-array payloads on {@link PatchEvent} are base64-encoded so the
 * envelope stays text-only.
 *
 * <p>Use this codec from every transport ({@code SyncStrategy}
 * implementation) so refresh and patch messages can travel over the same
 * channel without per-type plumbing.
 */
public final class SyncMessageCodec {

  /** Type discriminator value for {@link RefreshEvent}. */
  static final String TYPE_REFRESH = "refresh";

  /** Type discriminator value for {@link PatchEvent}. */
  static final String TYPE_PATCH = "patch";

  /** Private constructor to prevent instantiation. */
  private SyncMessageCodec() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Serializes a {@link SyncMessage} into a JSON string.
   *
   * @param message the message to serialize, never null
   * @return the JSON string, never null
   * @throws NullPointerException if message is null
   */
  public static String serialize(final SyncMessage message) {
    Objects.requireNonNull(message, "message must not be null");
    if (message instanceof RefreshEvent refresh) {
      return serializeRefresh(refresh);
    }
    if (message instanceof PatchEvent patch) {
      return serializePatch(patch);
    }
    throw new IllegalArgumentException(
        "Unknown SyncMessage subtype: " + message.getClass());
  }

  /**
   * Deserializes a JSON string into a {@link SyncMessage}, dispatching by
   * the {@code "type"} field.
   *
   * @param json the JSON string to parse, never null
   * @return the parsed message, never null
   * @throws NullPointerException     if json is null
   * @throws IllegalArgumentException if the JSON is malformed or carries
   *                                  an unknown type
   */
  public static SyncMessage deserialize(final String json) {
    Objects.requireNonNull(json, "json must not be null");
    try {
      final JSONObject node = new JSONObject(json);
      final String type = requireString(node, "type");
      return switch (type) {
        case TYPE_REFRESH -> readRefresh(node);
        case TYPE_PATCH -> readPatch(node);
        default -> throw new IllegalArgumentException(
            "Unknown SyncMessage type: " + type);
      };
    } catch (final IllegalArgumentException e) {
      throw e;
    } catch (final JSONException e) {
      throw new IllegalArgumentException(
          "Failed to deserialize SyncMessage from JSON: " + json, e);
    }
  }

  /**
   * Serializes a {@link RefreshEvent} into a JSON string with the
   * {@code "type": "refresh"} discriminator.
   *
   * @param event the refresh event, never null
   * @return the JSON string, never null
   */
  private static String serializeRefresh(final RefreshEvent event) {
    final JSONObject node = new JSONObject();
    node.put("type", TYPE_REFRESH);
    node.put("catalogName", event.catalogName());
    node.put("sourceNodeId", event.sourceNodeId());
    node.put("version", event.version());
    node.put("hash", event.hash());
    node.put("timestamp", event.timestamp().toString());
    return node.toString();
  }

  /**
   * Serializes a {@link PatchEvent} into a JSON string with the
   * {@code "type": "patch"} discriminator.
   *
   * @param event the patch event, never null
   * @return the JSON string, never null
   */
  private static String serializePatch(final PatchEvent event) {
    final JSONObject node = new JSONObject();
    node.put("type", TYPE_PATCH);
    node.put("catalogName", event.catalogName());
    node.put("sourceNodeId", event.sourceNodeId());
    node.put("fromVersion", event.fromVersion());
    node.put("toVersion", event.toVersion());
    node.put("fromHash", event.fromHash());
    node.put("toHash", event.toHash());
    node.put("indexName", event.indexName());
    node.put("oldItem", encode(event.serializedOldItem()));
    node.put("newItem", encode(event.serializedNewItem()));
    node.put("timestamp", event.timestamp().toString());
    return node.toString();
  }

  /**
   * Reads a {@link RefreshEvent} from the given JSON object.
   *
   * @param node the parsed JSON object, never null
   * @return the refresh event, never null
   */
  private static RefreshEvent readRefresh(final JSONObject node) {
    return new RefreshEvent(
        requireString(node, "catalogName"),
        requireString(node, "sourceNodeId"),
        node.getLong("version"),
        requireString(node, "hash"),
        Instant.parse(requireString(node, "timestamp")));
  }

  /**
   * Reads a {@link PatchEvent} from the given JSON object.
   *
   * @param node the parsed JSON object, never null
   * @return the patch event, never null
   */
  private static PatchEvent readPatch(final JSONObject node) {
    return new PatchEvent(
        requireString(node, "catalogName"),
        requireString(node, "sourceNodeId"),
        node.getLong("fromVersion"),
        node.getLong("toVersion"),
        requireString(node, "fromHash"),
        requireString(node, "toHash"),
        requireString(node, "indexName"),
        decode(requireString(node, "oldItem")),
        decode(requireString(node, "newItem")),
        Instant.parse(requireString(node, "timestamp")));
  }

  /**
   * Returns the value at {@code field} as a non-null string, throwing if
   * the field is missing.
   *
   * @param node  the JSON object, never null
   * @param field the field name, never null
   * @return the string value, never null
   */
  private static String requireString(final JSONObject node,
      final String field) {
    if (!node.has(field) || node.isNull(field)) {
      throw new IllegalArgumentException(
          "Missing field: " + field + " in JSON: " + node);
    }
    return node.getString(field);
  }

  /**
   * Encodes a byte array as a base64 string.
   *
   * @param bytes the bytes to encode, never null
   * @return the base64 string, never null
   */
  private static String encode(final byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  /**
   * Decodes a base64 string into a byte array.
   *
   * @param value the base64 string, never null
   * @return the decoded bytes, never null
   */
  private static byte[] decode(final String value) {
    return Base64.getDecoder().decode(value);
  }
}
