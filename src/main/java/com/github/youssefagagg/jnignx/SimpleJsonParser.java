package com.github.youssefagagg.jnignx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, reflection-free JSON parser specifically designed for parsing routes.json.
 * This parser is GraalVM Native Image compatible as it uses no reflection.
 *
 * <p>Supports only the subset of JSON needed for route configuration:
 * <ul>
 *   <li>Objects with string keys</li>
 *   <li>Arrays of strings</li>
 *   <li>String values</li>
 * </ul>
 */
public final class SimpleJsonParser {

  private final String json;
  private int pos;

  private SimpleJsonParser(String json) {
    this.json = json;
    this.pos = 0;
  }

  /**
   * Parses a JSON configuration string into a RouteConfig.
   * Expected format: {"routes": {"/path": ["backend1", "backend2"], ...}}
   *
   * @param json the JSON string to parse
   * @return the parsed RouteConfig
   * @throws IllegalArgumentException if the JSON is malformed
   */
  public static RouteConfig parseRouteConfig(String json) {
    SimpleJsonParser parser = new SimpleJsonParser(json);
    Map<String, Object> root = parser.parseObject();

    @SuppressWarnings("unchecked")
    Map<String, Object> routesObj = (Map<String, Object>) root.get("routes");
    if (routesObj == null) {
      return RouteConfig.empty();
    }

    Map<String, List<String>> routes = new HashMap<>();
    for (Map.Entry<String, Object> entry : routesObj.entrySet()) {
      @SuppressWarnings("unchecked")
      List<String> backends = (List<String>) entry.getValue();
      routes.put(entry.getKey(), backends);
    }

    return new RouteConfig(routes);
  }

  private Map<String, Object> parseObject() {
    skipWhitespace();
    expect('{');
    skipWhitespace();

    Map<String, Object> obj = new HashMap<>();

    if (peek() != '}') {
      do {
        skipWhitespace();
        if (peek() == ',') {
          pos++;
          skipWhitespace();
        }

        String key = parseString();
        skipWhitespace();
        expect(':');
        skipWhitespace();
        Object value = parseValue();
        obj.put(key, value);
        skipWhitespace();
      } while (peek() == ',');
    }

    expect('}');
    return obj;
  }

  private List<String> parseArray() {
    expect('[');
    skipWhitespace();

    List<String> arr = new ArrayList<>();

    if (peek() != ']') {
      do {
        skipWhitespace();
        if (peek() == ',') {
          pos++;
          skipWhitespace();
        }
        arr.add(parseString());
        skipWhitespace();
      } while (peek() == ',');
    }

    expect(']');
    return arr;
  }

  private Object parseValue() {
    skipWhitespace();
    char c = peek();
    if (c == '{') {
      return parseObject();
    } else if (c == '[') {
      return parseArray();
    } else if (c == '"') {
      return parseString();
    } else {
      throw new IllegalArgumentException("Unexpected character: " + c + " at position " + pos);
    }
  }

  private String parseString() {
    expect('"');
    StringBuilder sb = new StringBuilder();
    while (pos < json.length()) {
      char c = json.charAt(pos);
      if (c == '"') {
        pos++;
        return sb.toString();
      } else if (c == '\\') {
        pos++;
        if (pos >= json.length()) {
          throw new IllegalArgumentException("Unexpected end of string");
        }
        char escaped = json.charAt(pos);
        sb.append(switch (escaped) {
          case '"' -> '"';
          case '\\' -> '\\';
          case 'n' -> '\n';
          case 'r' -> '\r';
          case 't' -> '\t';
          default -> escaped;
        });
        pos++;
      } else {
        sb.append(c);
        pos++;
      }
    }
    throw new IllegalArgumentException("Unterminated string");
  }

  private void skipWhitespace() {
    while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
      pos++;
    }
  }

  private char peek() {
    if (pos >= json.length()) {
      throw new IllegalArgumentException("Unexpected end of JSON");
    }
    return json.charAt(pos);
  }

  private void expect(char expected) {
    if (pos >= json.length()) {
      throw new IllegalArgumentException("Expected '" + expected + "' but reached end of JSON");
    }
    char actual = json.charAt(pos);
    if (actual != expected) {
      throw new IllegalArgumentException(
          "Expected '" + expected + "' but found '" + actual + "' at position " + pos);
    }
    pos++;
  }
}
