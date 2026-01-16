package com.github.youssefagagg.jnignx.http;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * CORS (Cross-Origin Resource Sharing) configuration and handler.
 *
 * <p>Manages CORS policies for cross-origin requests, supporting:
 * <ul>
 *   <li>Allowed origins (wildcard or specific domains)</li>
 *   <li>Allowed methods (GET, POST, PUT, DELETE, etc.)</li>
 *   <li>Allowed headers</li>
 *   <li>Exposed headers</li>
 *   <li>Credentials support</li>
 *   <li>Preflight request handling</li>
 *   <li>Max age caching</li>
 * </ul>
 *
 * <p><b>Example Configuration:</b>
 * <pre>
 * CorsConfig cors = new CorsConfig.Builder()
 *     .allowOrigin("https://example.com")
 *     .allowOrigin("https://app.example.com")
 *     .allowMethod("GET", "POST", "PUT", "DELETE")
 *     .allowHeader("Content-Type", "Authorization")
 *     .allowCredentials(true)
 *     .maxAge(3600)
 *     .build();
 * </pre>
 *
 * <p><b>Security Best Practices:</b>
 * <ul>
 *   <li>Avoid using "*" for allowOrigin when allowCredentials is true</li>
 *   <li>Explicitly list allowed origins instead of wildcards</li>
 *   <li>Only allow necessary HTTP methods</li>
 *   <li>Validate and sanitize Origin header</li>
 * </ul>
 */
public final class CorsConfig {

  private final Set<String> allowedOrigins;
  private final boolean allowAnyOrigin;
  private final Set<String> allowedMethods;
  private final Set<String> allowedHeaders;
  private final Set<String> exposedHeaders;
  private final boolean allowCredentials;
  private final int maxAge;
  private final boolean enabled;

  private CorsConfig(Builder builder) {
    this.allowedOrigins = Set.copyOf(builder.allowedOrigins);
    this.allowAnyOrigin = builder.allowAnyOrigin;
    this.allowedMethods = Set.copyOf(builder.allowedMethods);
    this.allowedHeaders = Set.copyOf(builder.allowedHeaders);
    this.exposedHeaders = Set.copyOf(builder.exposedHeaders);
    this.allowCredentials = builder.allowCredentials;
    this.maxAge = builder.maxAge;
    this.enabled = builder.enabled;
  }

  /**
   * Checks if a request is a CORS preflight request.
   *
   * @param method        the HTTP method
   * @param origin        the Origin header
   * @param requestMethod the Access-Control-Request-Method header
   * @return true if preflight request
   */
  public static boolean isPreflight(String method, String origin, String requestMethod) {
    return "OPTIONS".equalsIgnoreCase(method) &&
        origin != null &&
        requestMethod != null;
  }

  /**
   * Creates a permissive CORS config for development.
   */
  public static CorsConfig permissive() {
    return new Builder().permissive().build();
  }

  /**
   * Creates a disabled CORS config.
   */
  public static CorsConfig disabled() {
    return new Builder().enabled(false).build();
  }

  /**
   * Checks if CORS is enabled.
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Checks if the origin is allowed.
   *
   * @param origin the Origin header value
   * @return true if allowed
   */
  public boolean isOriginAllowed(String origin) {
    if (!enabled || origin == null || origin.isBlank()) {
      return false;
    }

    if (allowAnyOrigin) {
      return true;
    }

    return allowedOrigins.contains(origin);
  }

  /**
   * Checks if the method is allowed.
   *
   * @param method the HTTP method
   * @return true if allowed
   */
  public boolean isMethodAllowed(String method) {
    if (!enabled || method == null) {
      return false;
    }

    return allowedMethods.isEmpty() || allowedMethods.contains(method.toUpperCase());
  }

  /**
   * Gets CORS headers for a request.
   *
   * @param origin the Origin header value
   * @param method the request method (for preflight)
   * @return map of CORS headers to add to response
   */
  public Map<String, String> getCorsHeaders(String origin, String method) {
    Map<String, String> headers = new HashMap<>();

    if (!enabled || !isOriginAllowed(origin)) {
      return headers;
    }

    // Access-Control-Allow-Origin
    if (allowAnyOrigin && !allowCredentials) {
      headers.put("Access-Control-Allow-Origin", "*");
    } else {
      headers.put("Access-Control-Allow-Origin", origin);
    }

    // Access-Control-Allow-Credentials
    if (allowCredentials) {
      headers.put("Access-Control-Allow-Credentials", "true");
    }

    // Access-Control-Expose-Headers
    if (!exposedHeaders.isEmpty()) {
      headers.put("Access-Control-Expose-Headers", String.join(", ", exposedHeaders));
    }

    // Vary header for caching
    if (!allowAnyOrigin) {
      headers.put("Vary", "Origin");
    }

    return headers;
  }

  /**
   * Gets CORS preflight headers.
   *
   * @param origin         the Origin header value
   * @param requestMethod  the Access-Control-Request-Method header
   * @param requestHeaders the Access-Control-Request-Headers header
   * @return map of CORS preflight headers
   */
  public Map<String, String> getPreflightHeaders(String origin, String requestMethod,
                                                 String requestHeaders) {
    Map<String, String> headers = new HashMap<>();

    if (!enabled || !isOriginAllowed(origin)) {
      return headers;
    }

    // Basic CORS headers
    if (allowAnyOrigin && !allowCredentials) {
      headers.put("Access-Control-Allow-Origin", "*");
    } else {
      headers.put("Access-Control-Allow-Origin", origin);
    }

    if (allowCredentials) {
      headers.put("Access-Control-Allow-Credentials", "true");
    }

    // Access-Control-Allow-Methods
    if (!allowedMethods.isEmpty()) {
      headers.put("Access-Control-Allow-Methods", String.join(", ", allowedMethods));
    } else {
      headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
    }

    // Access-Control-Allow-Headers
    if (!allowedHeaders.isEmpty()) {
      headers.put("Access-Control-Allow-Headers", String.join(", ", allowedHeaders));
    } else if (requestHeaders != null && !requestHeaders.isBlank()) {
      headers.put("Access-Control-Allow-Headers", requestHeaders);
    }

    // Access-Control-Max-Age
    if (maxAge > 0) {
      headers.put("Access-Control-Max-Age", String.valueOf(maxAge));
    }

    return headers;
  }

  // Builder
  public static class Builder {
    private final Set<String> allowedOrigins = new HashSet<>();
    private final Set<String> allowedMethods = new HashSet<>();
    private final Set<String> allowedHeaders = new HashSet<>();
    private final Set<String> exposedHeaders = new HashSet<>();
    private boolean allowAnyOrigin = false;
    private boolean allowCredentials = false;
    private int maxAge = 3600; // 1 hour default
    private boolean enabled = true;

    /**
     * Allows a specific origin.
     */
    public Builder allowOrigin(String origin) {
      if ("*".equals(origin)) {
        allowAnyOrigin = true;
      } else {
        allowedOrigins.add(origin);
      }
      return this;
    }

    /**
     * Allows multiple origins.
     */
    public Builder allowOrigins(String... origins) {
      for (String origin : origins) {
        allowOrigin(origin);
      }
      return this;
    }

    /**
     * Allows any origin (*).
     */
    public Builder allowAnyOrigin() {
      this.allowAnyOrigin = true;
      return this;
    }

    /**
     * Allows specific HTTP methods.
     */
    public Builder allowMethod(String... methods) {
      for (String method : methods) {
        allowedMethods.add(method.toUpperCase());
      }
      return this;
    }

    /**
     * Allows specific headers.
     */
    public Builder allowHeader(String... headers) {
      for (String header : headers) {
        allowedHeaders.add(header);
      }
      return this;
    }

    /**
     * Exposes specific headers to JavaScript.
     */
    public Builder exposeHeader(String... headers) {
      for (String header : headers) {
        exposedHeaders.add(header);
      }
      return this;
    }

    /**
     * Allows credentials (cookies, authorization headers).
     */
    public Builder allowCredentials(boolean allow) {
      this.allowCredentials = allow;
      return this;
    }

    /**
     * Sets the max age for preflight cache (in seconds).
     */
    public Builder maxAge(int seconds) {
      if (seconds < 0) {
        throw new IllegalArgumentException("Max age must be non-negative");
      }
      this.maxAge = seconds;
      return this;
    }

    /**
     * Enables or disables CORS.
     */
    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    /**
     * Creates a permissive CORS configuration (useful for development).
     */
    public Builder permissive() {
      this.allowAnyOrigin = true;
      this.allowedMethods.addAll(Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
      this.allowedHeaders.addAll(Set.of("*"));
      this.allowCredentials = false;
      this.maxAge = 86400; // 24 hours
      return this;
    }

    /**
     * Creates a strict CORS configuration (useful for production).
     */
    public Builder strict(String... origins) {
      this.allowAnyOrigin = false;
      this.allowedOrigins.addAll(Set.of(origins));
      this.allowedMethods.addAll(Set.of("GET", "POST"));
      this.allowCredentials = true;
      this.maxAge = 3600;
      return this;
    }

    public CorsConfig build() {
      // Validation
      if (allowAnyOrigin && allowCredentials) {
        throw new IllegalStateException(
            "Cannot allow credentials with wildcard origin (*). " +
                "Specify explicit origins when using credentials.");
      }

      return new CorsConfig(this);
    }
  }
}
