package com.github.youssefagagg.jnignx.config;

import com.github.youssefagagg.jnignx.http.CorsConfig;
import com.github.youssefagagg.jnignx.security.AdminAuth;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and parses configuration for NanoServer.
 * Handles parsing of routes.json with full production feature support.
 *
 * <p>This class replaces the old SimpleJsonParser and adds file loading capabilities.
 */
public final class ConfigLoader {

  private final String json;
  private int pos;

  ConfigLoader(String json) {
    this.json = json;
    this.pos = 0;
  }

  /**
   * Loads configuration from a file.
   *
   * @param path path to the configuration file
   * @return the parsed RouteConfig
   * @throws IOException if the file cannot be read
   */
  public static RouteConfig load(Path path) throws IOException {
    String json = Files.readString(path);
    return parseRouteConfig(json);
  }

  /**
   * Loads enhanced ServerConfig from a file with all production features.
   *
   * @param path path to the configuration file
   * @return the parsed ServerConfig
   * @throws IOException if the file cannot be read
   */
  public static ServerConfig loadServerConfig(Path path) throws IOException {
    String json = Files.readString(path);
    return parseServerConfig(json);
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
    ConfigLoader parser = new ConfigLoader(json);
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

  /**
   * Parses a JSON configuration string into a ServerConfig with all features.
   *
   * @param json the JSON string to parse
   * @return the parsed ServerConfig
   */
  public static ServerConfig parseServerConfig(String json) {
    ConfigLoader parser = new ConfigLoader(json);
    Map<String, Object> root = parser.parseObject();

    ServerConfig.Builder builder = ServerConfig.builder();

    // Parse routes
    @SuppressWarnings("unchecked")
    Map<String, Object> routesObj = (Map<String, Object>) root.get("routes");
    if (routesObj != null) {
      Map<String, List<String>> routes = new HashMap<>();
      for (Map.Entry<String, Object> entry : routesObj.entrySet()) {
        @SuppressWarnings("unchecked")
        List<String> backends = (List<String>) entry.getValue();
        routes.put(entry.getKey(), backends);
      }
      builder.routes(routes);
    }

    // Parse load balancer
    String lbAlgorithm = parser.getString(root, "loadBalancer", "loadBalancerAlgorithm");
    if (lbAlgorithm != null) {
      builder.loadBalancerAlgorithm(lbAlgorithm);
    }

    // Parse rate limiter
    @SuppressWarnings("unchecked")
    Map<String, Object> rateLimiter = (Map<String, Object>) root.get("rateLimiter");
    if (rateLimiter != null) {
      boolean enabled = parser.getBoolean(rateLimiter, "enabled", false);
      int rps = parser.getInt(rateLimiter, "requestsPerSecond", 1000);
      int burst = parser.getInt(rateLimiter, "burstSize", 2000);
      String strategy = parser.getString(rateLimiter, "strategy", "algorithm");
      if (strategy == null) {
        strategy = "token-bucket";
      }
      builder.rateLimiter(enabled, rps, burst, strategy);
    }

    // Parse circuit breaker
    @SuppressWarnings("unchecked")
    Map<String, Object> circuitBreaker = (Map<String, Object>) root.get("circuitBreaker");
    if (circuitBreaker != null) {
      boolean enabled = parser.getBoolean(circuitBreaker, "enabled", false);
      int failureThreshold = parser.getInt(circuitBreaker, "failureThreshold", 5);
      int timeout = parser.getInt(circuitBreaker, "timeout", 30);
      builder.circuitBreaker(enabled, failureThreshold, timeout);
    }

    // Parse health check
    @SuppressWarnings("unchecked")
    Map<String, Object> healthCheck = (Map<String, Object>) root.get("healthCheck");
    if (healthCheck != null) {
      boolean enabled = parser.getBoolean(healthCheck, "enabled", true);
      int interval = parser.getInt(healthCheck, "intervalSeconds", 10);
      int timeout = parser.getInt(healthCheck, "timeoutSeconds", 5);
      int failureThreshold = parser.getInt(healthCheck, "failureThreshold", 3);
      int successThreshold = parser.getInt(healthCheck, "successThreshold", 2);
      builder.healthCheck(enabled, interval, timeout, failureThreshold, successThreshold);

      String hcPath = parser.getString(healthCheck, "path", "healthCheckPath");
      if (hcPath != null) {
        builder.healthCheckPath(hcPath);
      }

      int expectedStatusMin = parser.getInt(healthCheck, "expectedStatusMin", 200);
      int expectedStatusMax = parser.getInt(healthCheck, "expectedStatusMax", 399);
      builder.healthCheckExpectedStatus(expectedStatusMin, expectedStatusMax);
    }

    // Parse domain routes
    @SuppressWarnings("unchecked")
    Map<String, Object> domainRoutesObj = (Map<String, Object>) root.get("domainRoutes");
    if (domainRoutesObj != null) {
      Map<String, List<String>> domainRoutes = new HashMap<>();
      for (Map.Entry<String, Object> entry : domainRoutesObj.entrySet()) {
        Object value = entry.getValue();
        if (value instanceof List) {
          @SuppressWarnings("unchecked")
          List<String> backends = (List<String>) value;
          domainRoutes.put(entry.getKey().toLowerCase(), backends);
        } else if (value instanceof String) {
          domainRoutes.put(entry.getKey().toLowerCase(), List.of((String) value));
        }
      }
      builder.domainRoutes(domainRoutes);
    }

    // Parse backend weights
    @SuppressWarnings("unchecked")
    Map<String, Object> weightsObj = (Map<String, Object>) root.get("backendWeights");
    if (weightsObj != null) {
      Map<String, Integer> weights = new HashMap<>();
      for (Map.Entry<String, Object> entry : weightsObj.entrySet()) {
        if (entry.getValue() instanceof Number) {
          weights.put(entry.getKey(), ((Number) entry.getValue()).intValue());
        }
      }
      builder.backendWeights(weights);
    }

    // Parse CORS
    @SuppressWarnings("unchecked")
    Map<String, Object> corsObj = (Map<String, Object>) root.get("cors");
    if (corsObj != null) {
      builder.cors(parseCorsConfig(corsObj, parser));
    }

    // Parse admin configuration
    @SuppressWarnings("unchecked")
    Map<String, Object> adminObj = (Map<String, Object>) root.get("admin");
    if (adminObj != null) {
      boolean adminEnabled = parser.getBoolean(adminObj, "enabled", false);
      builder.adminEnabled(adminEnabled);

      @SuppressWarnings("unchecked")
      Map<String, Object> authObj = (Map<String, Object>) adminObj.get("authentication");
      if (authObj != null) {
        builder.adminAuth(parseAdminAuth(authObj, parser));
      }
    }

    return builder.build();
  }

  private static CorsConfig parseCorsConfig(Map<String, Object> corsObj, ConfigLoader parser) {
    CorsConfig.Builder corsBuilder = new CorsConfig.Builder();

    boolean enabled = parser.getBoolean(corsObj, "enabled", false);
    corsBuilder.enabled(enabled);

    if (enabled) {
      @SuppressWarnings("unchecked")
      List<String> origins = (List<String>) corsObj.get("allowedOrigins");
      if (origins != null) {
        origins.forEach(corsBuilder::allowOrigin);
      }

      @SuppressWarnings("unchecked")
      List<String> methods = (List<String>) corsObj.get("allowedMethods");
      if (methods != null) {
        corsBuilder.allowMethod(methods.toArray(new String[0]));
      }

      @SuppressWarnings("unchecked")
      List<String> headers = (List<String>) corsObj.get("allowedHeaders");
      if (headers != null) {
        corsBuilder.allowHeader(headers.toArray(new String[0]));
      }

      boolean allowCredentials = parser.getBoolean(corsObj, "allowCredentials", false);
      corsBuilder.allowCredentials(allowCredentials);

      int maxAge = parser.getInt(corsObj, "maxAge", 3600);
      corsBuilder.maxAge(maxAge);
    }

    return corsBuilder.build();
  }

  private static AdminAuth parseAdminAuth(Map<String, Object> authObj, ConfigLoader parser) {
    AdminAuth adminAuth = new AdminAuth();

    String apiKey = expandEnvVar(parser.getString(authObj, "apiKey"));
    if (apiKey != null && !apiKey.isBlank()) {
      adminAuth.setApiKey(apiKey);
    }

    @SuppressWarnings("unchecked")
    List<String> ipWhitelist = (List<String>) authObj.get("ipWhitelist");
    if (ipWhitelist != null) {
      ipWhitelist.forEach(adminAuth::whitelistIP);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, String>> users = (List<Map<String, String>>) authObj.get("users");
    if (users != null) {
      for (Map<String, String> user : users) {
        String username = user.get("username");
        String password = expandEnvVar(user.get("password"));
        if (username != null && password != null) {
          adminAuth.addUser(username, password);
        }
      }
    }

    return adminAuth;
  }

  /**
   * Expands environment variables in format ${VAR_NAME}.
   */
  private static String expandEnvVar(String value) {
    if (value == null) {
      return null;
    }

    if (value.startsWith("${") && value.endsWith("}")) {
      String varName = value.substring(2, value.length() - 1);
      String envValue = System.getenv(varName);
      return envValue != null ? envValue : value;
    }

    return value;
  }

  // Helper methods for parsing
  private String getString(Map<String, Object> map, String... keys) {
    for (String key : keys) {
      Object value = map.get(key);
      if (value instanceof String) {
        return (String) value;
      }
    }
    return null;
  }

  private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
    Object value = map.get(key);
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value instanceof String) {
      return Boolean.parseBoolean((String) value);
    }
    return defaultValue;
  }

  private int getInt(Map<String, Object> map, String key, int defaultValue) {
    Object value = map.get(key);
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    if (value instanceof String) {
      try {
        return Integer.parseInt((String) value);
      } catch (NumberFormatException ignored) {
      }
    }
    return defaultValue;
  }

  Map<String, Object> parseObject() {
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

  private List<Object> parseArray() {
    expect('[');
    skipWhitespace();

    List<Object> arr = new ArrayList<>();

    if (peek() != ']') {
      do {
        skipWhitespace();
        if (peek() == ',') {
          pos++;
          skipWhitespace();
        }
        arr.add(parseValue());
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
    } else if (c == 't' || c == 'f') {
      return parseBoolean();
    } else if (c == '-' || Character.isDigit(c)) {
      return parseNumber();
    } else {
      throw new IllegalArgumentException("Unexpected character: " + c + " at position " + pos);
    }
  }

  private boolean parseBoolean() {
    char c = peek();
    if (c == 't') {
      consume("true");
      return true;
    } else {
      consume("false");
      return false;
    }
  }

  private Number parseNumber() {
    int start = pos;
    if (peek() == '-') {
      pos++;
    }
    while (pos < json.length() && Character.isDigit(json.charAt(pos))) {
      pos++;
    }
    if (pos < json.length() && json.charAt(pos) == '.') {
      pos++;
      while (pos < json.length() && Character.isDigit(json.charAt(pos))) {
        pos++;
      }
      return Double.parseDouble(json.substring(start, pos));
    }
    String numStr = json.substring(start, pos);
    try {
      return Integer.parseInt(numStr);
    } catch (NumberFormatException e) {
      return Long.parseLong(numStr);
    }
  }

  private void consume(String text) {
    if (json.startsWith(text, pos)) {
      pos += text.length();
    } else {
      throw new IllegalArgumentException("Expected '" + text + "' at position " + pos);
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
