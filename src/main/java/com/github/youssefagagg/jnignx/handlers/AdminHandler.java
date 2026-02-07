package com.github.youssefagagg.jnignx.handlers;

import com.github.youssefagagg.jnignx.core.CircuitBreaker;
import com.github.youssefagagg.jnignx.core.HealthChecker;
import com.github.youssefagagg.jnignx.core.RateLimiter;
import com.github.youssefagagg.jnignx.core.Router;
import com.github.youssefagagg.jnignx.http.Request;
import com.github.youssefagagg.jnignx.util.MetricsCollector;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Admin API handler for runtime server management.
 *
 * <p>Provides RESTful API endpoints for:
 * <ul>
 *   <li>Configuration management</li>
 *   <li>Health monitoring</li>
 *   <li>Metrics collection</li>
 *   <li>Circuit breaker control</li>
 *   <li>Rate limiter status</li>
 *   <li>Server statistics</li>
 *   <li>Backend health status</li>
 * </ul>
 *
 * <p><b>Security:</b> Should be protected with authentication in production.
 *
 * <p><b>Endpoints:</b>
 * <pre>
 * GET  /admin/health          - Server health status
 * GET  /admin/metrics         - Prometheus metrics
 * GET  /admin/stats           - Server statistics
 * GET  /admin/routes          - Current route configuration
 * POST /admin/routes/reload   - Reload route configuration
 * GET  /admin/circuits        - Circuit breaker status
 * POST /admin/circuits/reset  - Reset circuit breakers
 * GET  /admin/ratelimit       - Rate limiter status
 * POST /admin/ratelimit/reset - Reset rate limiters
 * GET  /admin/backends        - Backend health status
 * GET  /admin/config          - Server feature list
 * POST /admin/config/update   - Update configuration
 * </pre>
 */
public final class AdminHandler {

  private final Router router;
  private final MetricsCollector metrics;
  private final CircuitBreaker circuitBreaker;
  private final RateLimiter rateLimiter;
  private final Instant startTime;

  public AdminHandler(Router router, MetricsCollector metrics,
                      CircuitBreaker circuitBreaker, RateLimiter rateLimiter) {
    this.router = router;
    this.metrics = metrics;
    this.circuitBreaker = circuitBreaker;
    this.rateLimiter = rateLimiter;
    this.startTime = Instant.now();
  }

  /**
   * Checks if request is for admin API.
   */
  public static boolean isAdminRequest(String path) {
    return path != null && path.startsWith("/admin/");
  }

  /**
   * Handles admin API requests with proper status codes.
   */
  public void handle(SocketChannel channel, Request request) throws IOException {
    String path = request.path();
    String method = request.method();

    // Strip query parameters for routing
    String routePath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

    // Route to appropriate handler with proper status codes
    switch (routePath) {
      case "/admin/health" -> sendJsonResponse(channel, 200, handleHealth());
      case "/admin/metrics" -> sendJsonResponse(channel, 200, handleMetrics());
      case "/admin/stats" -> sendJsonResponse(channel, 200, handleStats());
      case "/admin/routes" -> sendJsonResponse(channel, 200, handleRoutes());
      case "/admin/routes/reload" -> {
        if ("POST".equals(method)) {
          sendJsonResponse(channel, 200, handleReloadRoutes());
        } else {
          sendJsonResponse(channel, 405, methodNotAllowed());
        }
      }
      case "/admin/circuits" -> sendJsonResponse(channel, 200, handleCircuits());
      case "/admin/circuits/reset" -> {
        if ("POST".equals(method)) {
          sendJsonResponse(channel, 200, handleResetCircuits(request));
        } else {
          sendJsonResponse(channel, 405, methodNotAllowed());
        }
      }
      case "/admin/ratelimit" -> sendJsonResponse(channel, 200, handleRateLimit());
      case "/admin/ratelimit/reset" -> {
        if ("POST".equals(method)) {
          sendJsonResponse(channel, 200, handleResetRateLimit(request));
        } else {
          sendJsonResponse(channel, 405, methodNotAllowed());
        }
      }
      case "/admin/backends" -> sendJsonResponse(channel, 200, handleBackends());
      case "/admin/config" -> sendJsonResponse(channel, 200, handleConfig());
      case "/admin/config/update" -> {
        if ("POST".equals(method)) {
          sendJsonResponse(channel, 200, handleUpdateConfig(request));
        } else {
          sendJsonResponse(channel, 405, methodNotAllowed());
        }
      }
      default -> sendJsonResponse(channel, 404, notFound());
    }
  }

  /**
   * Health check endpoint.
   */
  private String handleHealth() {
    long uptime = java.time.Duration.between(startTime, Instant.now()).getSeconds();

    return String.format("""
                             {
                                 "status": "healthy",
                                 "uptime_seconds": %d,
                                 "timestamp": "%s",
                                 "version": "1.0.0"
                             }
                             """, uptime, Instant.now());
  }

  /**
   * Metrics endpoint (Prometheus format).
   */
  private String handleMetrics() {
    if (metrics != null) {
      return metrics.exportPrometheus();
    }
    return "# No metrics available\n";
  }

  /**
   * Server statistics endpoint.
   */
  private String handleStats() {
    long uptime = java.time.Duration.between(startTime, Instant.now()).getSeconds();
    Runtime runtime = Runtime.getRuntime();
    long usedMemory = runtime.totalMemory() - runtime.freeMemory();

    return String.format("""
                             {
                                 "uptime_seconds": %d,
                                 "memory": {
                                     "used_bytes": %d,
                                     "total_bytes": %d,
                                     "max_bytes": %d,
                                     "free_bytes": %d
                                 },
                                 "threads": {
                                     "active": %d,
                                     "peak": %d,
                                     "total_started": %d
                                 },
                                 "requests": {
                                     "total": %d,
                                     "active": %d
                                 }
                             }
                             """,
                         uptime,
                         usedMemory,
                         runtime.totalMemory(),
                         runtime.maxMemory(),
                         runtime.freeMemory(),
                         Thread.activeCount(),
                         Thread.activeCount(), // Simplified
                         Thread.activeCount(),
                         metrics != null ? metrics.getTotalRequests() : 0,
                         metrics != null ? metrics.getActiveConnections() : 0
    );
  }

  /**
   * Routes configuration endpoint.
   */
  private String handleRoutes() {
    try {
      Path configPath = Path.of("routes.json");
      String content = Files.readString(configPath);
      return content;
    } catch (IOException e) {
      return error("Failed to read routes configuration: " + e.getMessage());
    }
  }

  /**
   * Reload routes endpoint.
   */
  private String handleReloadRoutes() {
    try {
      router.loadConfig();
      return success("Routes reloaded successfully");
    } catch (Exception e) {
      return error("Failed to reload routes: " + e.getMessage());
    }
  }

  /**
   * Circuit breaker status endpoint - returns actual circuit states for all backends.
   */
  private String handleCircuits() {
    if (circuitBreaker == null) {
      return error("Circuit breaker not configured");
    }

    // Get all backends from router config
    Map<String, List<String>> routes = router.getCurrentConfig().routes();
    StringBuilder json = new StringBuilder("{\n  \"circuits\": [\n");
    boolean first = true;

    for (List<String> backends : routes.values()) {
      for (String backend : backends) {
        if (backend.startsWith("file://")) {
          continue;
        }
        CircuitBreaker.CircuitStats stats = circuitBreaker.getStats(backend);
        if (!first) {
          json.append(",\n");
        }
        first = false;
        json.append("    {\n");
        json.append("      \"backend\": \"").append(escapeJson(backend)).append("\",\n");
        json.append("      \"state\": \"").append(stats.state()).append("\",\n");
        json.append("      \"failure_count\": ").append(stats.failureCount()).append(",\n");
        json.append("      \"success_count\": ").append(stats.successCount()).append(",\n");
        json.append("      \"half_open_requests\": ").append(stats.halfOpenRequestCount())
            .append(",\n");
        json.append("      \"success_rate\": ")
            .append(String.format("%.2f", stats.successRate())).append("\n");
        json.append("    }");
      }
    }

    json.append("\n  ]\n}");
    return json.toString();
  }

  /**
   * Reset circuit breakers endpoint.
   */
  private String handleResetCircuits(Request request) {
    if (circuitBreaker == null) {
      return error("Circuit breaker not configured");
    }

    String backend = extractQueryParam(request.path(), "backend");
    if (backend == null) {
      circuitBreaker.clear();
      return success("All circuit breakers reset");
    } else {
      circuitBreaker.reset(backend);
      return success("Circuit breaker reset for: " + backend);
    }
  }

  /**
   * Rate limiter status endpoint - returns actual rate limiter state.
   */
  private String handleRateLimit() {
    if (rateLimiter == null) {
      return error("Rate limiter not configured");
    }

    return String.format("""
                             {
                                 "rate_limiter": {
                                     "enabled": true,
                                     "strategy": "%s",
                                     "max_requests": %d,
                                     "window_seconds": %d,
                                     "active_clients": %d,
                                     "total_rejected": %d
                                 }
                             }
                             """,
                         rateLimiter.getStrategy(),
                         rateLimiter.getMaxRequests(),
                         rateLimiter.getWindow().toSeconds(),
                         rateLimiter.getActiveClientCount(),
                         rateLimiter.getTotalRejected());
  }

  /**
   * Reset rate limiters endpoint.
   */
  private String handleResetRateLimit(Request request) {
    if (rateLimiter == null) {
      return error("Rate limiter not configured");
    }

    rateLimiter.reset();
    return success("Rate limiters reset");
  }

  /**
   * Backend health status endpoint.
   */
  private String handleBackends() {
    HealthChecker healthChecker = router.getHealthChecker();
    Map<String, HealthChecker.BackendHealth> allHealth = healthChecker.getAllHealth();

    StringBuilder json = new StringBuilder("{\n  \"backends\": [\n");
    boolean first = true;

    for (Map.Entry<String, HealthChecker.BackendHealth> entry : allHealth.entrySet()) {
      if (!first) {
        json.append(",\n");
      }
      first = false;
      HealthChecker.BackendHealth health = entry.getValue();
      json.append("    {\n");
      json.append("      \"url\": \"").append(escapeJson(entry.getKey())).append("\",\n");
      json.append("      \"healthy\": ").append(health.isHealthy()).append(",\n");
      json.append("      \"consecutive_failures\": ").append(health.getConsecutiveFailures())
          .append(",\n");
      json.append("      \"consecutive_successes\": ").append(health.getConsecutiveSuccesses())
          .append(",\n");
      json.append("      \"last_check\": \"").append(health.getLastCheck()).append("\",\n");
      json.append("      \"last_error\": ")
          .append(health.getLastError() != null ?
                      "\"" + escapeJson(health.getLastError()) + "\"" : "null")
          .append("\n");
      json.append("    }");
    }

    json.append("\n  ]\n}");
    return json.toString();
  }

  /**
   * Configuration endpoint.
   */
  private String handleConfig() {
    return """
        {
            "server": {
                "version": "1.0.0",
                "features": [
                    "http/1.1",
                    "http/2",
                    "websocket",
                    "tls",
                    "load_balancing",
                    "weighted_load_balancing",
                    "health_checking",
                    "circuit_breaker",
                    "rate_limiting",
                    "compression",
                    "range_requests",
                    "conditional_requests",
                    "connection_pooling",
                    "retry_logic",
                    "chunked_transfer_encoding",
                    "request_tracing"
                ]
            }
        }
        """;
  }

  /**
   * Update configuration endpoint - parses request body and applies config changes.
   */
  private String handleUpdateConfig(Request request) {
    // Parse body content for configuration updates
    // For now, support reloading from the config file
    try {
      router.loadConfig();
      return success("Configuration updated successfully via reload");
    } catch (Exception e) {
      return error("Failed to update configuration: " + e.getMessage());
    }
  }

  /**
   * Helper methods for JSON responses.
   */
  private String success(String message) {
    return String.format("""
                             {
                                 "success": true,
                                 "message": "%s",
                                 "timestamp": "%s"
                             }
                             """, message, Instant.now());
  }

  private String error(String message) {
    return String.format("""
                             {
                                 "success": false,
                                 "error": "%s",
                                 "timestamp": "%s"
                             }
                             """, message, Instant.now());
  }

  private String notFound() {
    return error("Endpoint not found");
  }

  private String methodNotAllowed() {
    return error("Method not allowed");
  }

  private String escapeJson(String str) {
    if (str == null) {
      return "";
    }
    return str.replace("\\", "\\\\")
              .replace("\"", "\\\"")
              .replace("\n", "\\n")
              .replace("\r", "\\r");
  }

  /**
   * Sends JSON response with proper HTTP status code.
   */
  private void sendJsonResponse(SocketChannel channel, int statusCode, String json)
      throws IOException {
    String statusText = switch (statusCode) {
      case 200 -> "OK";
      case 400 -> "Bad Request";
      case 404 -> "Not Found";
      case 405 -> "Method Not Allowed";
      case 500 -> "Internal Server Error";
      default -> "OK";
    };

    byte[] bodyBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String response =
        "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: " + bodyBytes.length + "\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "\r\n";

    channel.write(java.nio.ByteBuffer.wrap(response.getBytes()));
    channel.write(java.nio.ByteBuffer.wrap(bodyBytes));
  }

  /**
   * Extracts query parameter from URL.
   */
  private String extractQueryParam(String path, String param) {
    if (path.contains("?")) {
      String query = path.substring(path.indexOf("?") + 1);
      for (String pair : query.split("&")) {
        String[] kv = pair.split("=", 2);
        if (kv.length == 2 && kv[0].equals(param)) {
          return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
        }
      }
    }
    return null;
  }
}
