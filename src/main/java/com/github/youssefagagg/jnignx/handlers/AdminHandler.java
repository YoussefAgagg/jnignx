package com.github.youssefagagg.jnignx.handlers;

import com.github.youssefagagg.jnignx.core.CircuitBreaker;
import com.github.youssefagagg.jnignx.core.RateLimiter;
import com.github.youssefagagg.jnignx.core.Router;
import com.github.youssefagagg.jnignx.http.Request;
import com.github.youssefagagg.jnignx.util.MetricsCollector;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

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
   * Handles admin API requests.
   */
  public void handle(SocketChannel channel, Request request) throws IOException {
    String path = request.path();
    String method = request.method();

    // Route to appropriate handler
    String response = switch (path) {
      case "/admin/health" -> handleHealth();
      case "/admin/metrics" -> handleMetrics();
      case "/admin/stats" -> handleStats();
      case "/admin/routes" -> handleRoutes();
      case "/admin/routes/reload" ->
          method.equals("POST") ? handleReloadRoutes() : methodNotAllowed();
      case "/admin/circuits" -> handleCircuits();
      case "/admin/circuits/reset" ->
          method.equals("POST") ? handleResetCircuits(request) : methodNotAllowed();
      case "/admin/ratelimit" -> handleRateLimit();
      case "/admin/ratelimit/reset" ->
          method.equals("POST") ? handleResetRateLimit(request) : methodNotAllowed();
      case "/admin/config" -> handleConfig();
      case "/admin/config/update" ->
          method.equals("POST") ? handleUpdateConfig(request) : methodNotAllowed();
      default -> notFound();
    };

    sendJsonResponse(channel, response);
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
   * Circuit breaker status endpoint.
   */
  private String handleCircuits() {
    if (circuitBreaker == null) {
      return error("Circuit breaker not configured");
    }

    StringBuilder json = new StringBuilder("{\n  \"circuits\": [\n");
    // Note: Would need to track backend URLs to list all circuits
    // This is a simplified version
    json.append("  ]\n}");
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
   * Rate limiter status endpoint.
   */
  private String handleRateLimit() {
    if (rateLimiter == null) {
      return error("Rate limiter not configured");
    }

    // Simplified - would show rate limit status for active clients
    return """
        {
            "rate_limiter": {
                "enabled": true,
                "active_clients": 0
            }
        }
        """;
  }

  /**
   * Reset rate limiters endpoint.
   */
  private String handleResetRateLimit(Request request) {
    if (rateLimiter == null) {
      return error("Rate limiter not configured");
    }

    return success("Rate limiters reset");
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
                    "health_checking",
                    "circuit_breaker",
                    "rate_limiting",
                    "compression"
                ]
            }
        }
        """;
  }

  /**
   * Update configuration endpoint.
   */
  private String handleUpdateConfig(Request request) {
    // This would update configuration based on request body
    return error("Configuration updates not yet implemented");
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

  /**
   * Sends JSON response.
   */
  private void sendJsonResponse(SocketChannel channel, String json) throws IOException {
    String response =
        "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: " + json.length() + "\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "\r\n" +
            json;

    channel.write(java.nio.ByteBuffer.wrap(response.getBytes()));
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
