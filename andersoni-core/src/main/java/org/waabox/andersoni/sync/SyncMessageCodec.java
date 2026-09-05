package org.waabox.andersoni.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import org.json.JSONArray;
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
      // A payload with no discriminator predates patch support: every
      // message on the channel was a refresh event then, so reading it as
      // one is what keeps a mixed-version cluster working during a rolling
      // upgrade.
      final String type = node.has("type") && !node.isNull("type")
          ? node.getString("type")
          : TYPE_REFRESH;
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
    node.put("kind", event.kind().name());
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
    final JSONArray ops = new JSONArray();
    for (final PatchEvent.Op op : event.operations()) {
      final JSONObject opNode = new JSONObject();
      opNode.put("operation", op.operation().name());
      putIfPresent(opNode, "indexName", op.indexName());
      putIfPresent(opNode, "oldItem", encode(op.oldItem()));
      putIfPresent(opNode, "newItem", encode(op.newItem()));
      ops.put(opNode);
    }
    node.put("ops", ops);
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
        Instant.parse(requireString(node, "timestamp")),
        parseKind(node));
  }

  /**
   * Reads the {@link RefreshKind} discriminator, defaulting to
   * {@link RefreshKind#EVENT} when the field is absent or unrecognized.
   *
   * <p>Mirrors the tolerance {@code RefreshEventCodec} applies, so a
   * message from a node that predates a kind — or that adds one this
   * version does not know — is read as a plain event rather than rejected.
   *
   * @param node the parsed JSON object, never null
   *
   * @return the kind, never null
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
        readOps(node),
        Instant.parse(requireString(node, "timestamp")));
  }

  /**
   * Reads the operations of a patch payload.
   *
   * <p>A payload with no {@code ops} array carries its single operation in
   * flat fields, which is the shape that predates batching; reading it as a
   * one-operation list is what lets a mixed-version cluster keep working
   * through a rolling upgrade.
   *
   * @param node the parsed JSON object, never null
   *
   * @return the operations, never null or empty
   */
  private static List<PatchEvent.Op> readOps(final JSONObject node) {
    if (!node.has("ops") || node.isNull("ops")) {
      return List.of(readOp(node));
    }
    final JSONArray array = node.getJSONArray("ops");
    final List<PatchEvent.Op> ops = new ArrayList<>(array.length());
    for (int i = 0; i < array.length(); i++) {
      ops.add(readOp(array.getJSONObject(i)));
    }
    return ops;
  }

  /**
   * Reads one operation from the object holding its fields.
   *
   * @param node the object carrying the operation's fields, never null
   *
   * @return the operation, never null
   */
  private static PatchEvent.Op readOp(final JSONObject node) {
    return new PatchEvent.Op(
        parseOperation(node),
        optionalString(node, "indexName"),
        decode(optionalString(node, "oldItem")),
        decode(optionalString(node, "newItem")));
  }

  /**
   * Reads the {@link PatchOperation} discriminator, defaulting to
   * {@link PatchOperation#REPLACE} when absent.
   *
   * <p>Replace was the only operation before insert and remove existed, so
   * a payload without the field can only be one — which is what keeps a
   * mixed-version cluster working while a rolling upgrade is in flight.
   * An operation this version does not recognise is a different matter: it
   * cannot be applied, and guessing would corrupt the follower, so it is
   * rejected and the caller falls back to a full reload.
   *
   * @param node the parsed JSON object, never null
   *
   * @return the operation, never null
   *
   * @throws IllegalArgumentException if the operation is unrecognised
   */
  private static PatchOperation parseOperation(final JSONObject node) {
    if (!node.has("operation") || node.isNull("operation")) {
      return PatchOperation.REPLACE;
    }
    final String raw = node.getString("operation");
    try {
      return PatchOperation.valueOf(raw);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown patch operation: " + raw, e);
    }
  }

  /**
   * Writes a value only when it is present, so an operation's unused
   * payloads stay absent from the JSON rather than appearing as nulls.
   *
   * @param node  the object to write into, never null
   * @param name  the field name, never null
   * @param value the value, may be null
   */
  private static void putIfPresent(final JSONObject node, final String name,
      final String value) {
    if (value != null) {
      node.put(name, value);
    }
  }

  /**
   * Returns a string field, or null when absent.
   *
   * @param node the parsed JSON object, never null
   * @param name the field name, never null
   *
   * @return the value, or null when the field is absent or null
   */
  private static String optionalString(final JSONObject node,
      final String name) {
    return node.has(name) && !node.isNull(name)
        ? node.getString(name) : null;
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
    return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
  }

  /**
   * Decodes a base64 string into a byte array.
   *
   * @param value the base64 string, never null
   * @return the decoded bytes, never null
   */
  private static byte[] decode(final String value) {
    return value == null ? null : Base64.getDecoder().decode(value);
  }
}
