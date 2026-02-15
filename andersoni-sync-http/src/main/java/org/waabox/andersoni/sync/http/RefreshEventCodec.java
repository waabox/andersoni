package org.waabox.andersoni.sync.http;

import java.time.Instant;
import java.util.Objects;

import org.waabox.andersoni.sync.RefreshEvent;

/**
 * Static utility class for serializing and deserializing
 * {@link RefreshEvent} instances to and from JSON strings.
 *
 * <p>Uses manual JSON formatting with no external dependencies. The JSON
 * format is fixed and known, matching the fields of {@link RefreshEvent}.
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
   * @param event the event to serialize, never null
   * @return the JSON representation of the event, never null
   */
  public static String serialize(final RefreshEvent event) {
    Objects.requireNonNull(event, "event cannot be null");

    final StringBuilder sb = new StringBuilder(256);
    sb.append('{');
    appendString(sb, "catalogName", event.catalogName());
    sb.append(',');
    appendString(sb, "sourceNodeId", event.sourceNodeId());
    sb.append(',');
    sb.append("\"version\":").append(event.version());
    sb.append(',');
    appendString(sb, "hash", event.hash());
    sb.append(',');
    appendString(sb, "timestamp", event.timestamp().toString());
    sb.append('}');
    return sb.toString();
  }

  /**
   * Deserializes a JSON string into a {@link RefreshEvent}.
   *
   * <p>The JSON must contain the fields {@code catalogName},
   * {@code sourceNodeId}, {@code version}, {@code hash}, and
   * {@code timestamp}.
   *
   * @param json the JSON string to parse, never null
   * @return the parsed {@link RefreshEvent}, never null
   * @throws IllegalArgumentException if the JSON is malformed or missing
   *     required fields
   */
  public static RefreshEvent deserialize(final String json) {
    Objects.requireNonNull(json, "json cannot be null");

    final String catalogName = extractString(json, "catalogName");
    final String sourceNodeId = extractString(json, "sourceNodeId");
    final long version = extractLong(json, "version");
    final String hash = extractString(json, "hash");
    final Instant timestamp = Instant.parse(
        extractString(json, "timestamp")
    );

    return new RefreshEvent(
        catalogName, sourceNodeId, version, hash, timestamp
    );
  }

  /**
   * Appends a JSON key-value pair with a string value to the builder.
   *
   * @param sb    the string builder to append to
   * @param key   the JSON key
   * @param value the string value
   */
  private static void appendString(final StringBuilder sb,
      final String key, final String value) {
    sb.append('"').append(key).append("\":\"")
        .append(escapeJson(value)).append('"');
  }

  /**
   * Extracts a string value for the given key from a JSON string.
   *
   * @param json the JSON string
   * @param key  the key to search for
   * @return the extracted string value
   * @throws IllegalArgumentException if the key is not found
   */
  private static String extractString(final String json, final String key) {
    final String search = "\"" + key + "\":\"";
    final int start = json.indexOf(search);
    if (start < 0) {
      throw new IllegalArgumentException(
          "Missing field: " + key + " in JSON: " + json
      );
    }
    final int valueStart = start + search.length();
    final int valueEnd = findUnescapedQuote(json, valueStart);
    return unescapeJson(json.substring(valueStart, valueEnd));
  }

  /**
   * Extracts a long value for the given key from a JSON string.
   *
   * @param json the JSON string
   * @param key  the key to search for
   * @return the extracted long value
   * @throws IllegalArgumentException if the key is not found
   */
  private static long extractLong(final String json, final String key) {
    final String search = "\"" + key + "\":";
    final int start = json.indexOf(search);
    if (start < 0) {
      throw new IllegalArgumentException(
          "Missing field: " + key + " in JSON: " + json
      );
    }
    final int valueStart = start + search.length();
    int valueEnd = valueStart;
    while (valueEnd < json.length()
        && (Character.isDigit(json.charAt(valueEnd))
            || json.charAt(valueEnd) == '-')) {
      valueEnd++;
    }
    return Long.parseLong(json.substring(valueStart, valueEnd));
  }

  /**
   * Finds the index of the next unescaped double-quote character.
   *
   * @param json  the JSON string
   * @param start the position to start searching from
   * @return the index of the closing quote
   */
  private static int findUnescapedQuote(final String json, final int start) {
    int i = start;
    while (i < json.length()) {
      if (json.charAt(i) == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
        return i;
      }
      i++;
    }
    throw new IllegalArgumentException(
        "Unterminated string starting at position " + start
    );
  }

  /**
   * Escapes special characters for JSON string values.
   *
   * @param value the raw string value
   * @return the escaped string
   */
  private static String escapeJson(final String value) {
    if (value == null) {
      return "null";
    }
    final StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Unescapes JSON string escape sequences.
   *
   * @param value the escaped JSON string
   * @return the unescaped string
   */
  private static String unescapeJson(final String value) {
    if (value == null || !value.contains("\\")) {
      return value;
    }
    final StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      if (c == '\\' && i + 1 < value.length()) {
        final char next = value.charAt(i + 1);
        switch (next) {
          case '"' -> { sb.append('"'); i++; }
          case '\\' -> { sb.append('\\'); i++; }
          case 'n' -> { sb.append('\n'); i++; }
          case 'r' -> { sb.append('\r'); i++; }
          case 't' -> { sb.append('\t'); i++; }
          default -> sb.append(c);
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
