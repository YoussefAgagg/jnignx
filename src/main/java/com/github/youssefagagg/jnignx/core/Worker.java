package com.github.youssefagagg.jnignx.core;

import com.github.youssefagagg.jnignx.config.ServerConfig;
import com.github.youssefagagg.jnignx.handlers.AdminHandler;
import com.github.youssefagagg.jnignx.handlers.ProxyHandler;
import com.github.youssefagagg.jnignx.handlers.StaticHandler;
import com.github.youssefagagg.jnignx.handlers.WebSocketHandler;
import com.github.youssefagagg.jnignx.http.CorsConfig;
import com.github.youssefagagg.jnignx.http.HttpParser;
import com.github.youssefagagg.jnignx.http.Request;
import com.github.youssefagagg.jnignx.security.AdminAuth;
import com.github.youssefagagg.jnignx.tls.SslWrapper;
import com.github.youssefagagg.jnignx.util.AccessLogger;
import com.github.youssefagagg.jnignx.util.MetricsCollector;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Worker thread that handles a single connection with metrics and logging.
 *
 * <p>Supports HTTP, HTTPS, WebSocket, and admin API requests with full
 * production feature integration (CORS, auth, rate limiting, etc).
 *
 * <p>Uses shared CircuitBreaker and RateLimiter instances across all workers
 * for consistent state management.
 */
public class Worker implements Runnable {

  private static final String CONNECTION_CLOSE_HEADER = "Connection: close\r\n";

  private final SocketChannel clientChannel;
  private final Router router;
  private final SslWrapper sslWrapper;
  private final MetricsCollector metrics;
  private final AdminHandler adminHandler;
  private final CircuitBreaker circuitBreaker;
  private final RateLimiter rateLimiter;
  private final ServerConfig serverConfig;

  private static final Object LOCK = new Object();
  // Shared instances - created once, used by all workers
  private static volatile CircuitBreaker sharedCircuitBreaker;
  private static volatile RateLimiter sharedRateLimiter;

  /**
   * Creates a Worker without TLS support.
   */
  public Worker(SocketChannel clientChannel, Router router) {
    this(clientChannel, router, null);
  }

  /**
   * Creates a Worker with optional TLS support.
   */
  public Worker(SocketChannel clientChannel, Router router, SslWrapper sslWrapper) {
    this.clientChannel = clientChannel;
    this.router = router;
    this.sslWrapper = sslWrapper;
    this.metrics = MetricsCollector.getInstance();

    // Get config from router
    this.serverConfig = router.getServerConfig();

    // Use shared circuit breaker across all workers
    this.circuitBreaker = getOrCreateCircuitBreaker(serverConfig);

    // Use shared rate limiter across all workers
    this.rateLimiter = getOrCreateRateLimiter(serverConfig);

    this.adminHandler = new AdminHandler(router, metrics, circuitBreaker, rateLimiter);
  }

  /**
   * Gets or creates a shared CircuitBreaker instance.
   */
  private static CircuitBreaker getOrCreateCircuitBreaker(ServerConfig config) {
    if (sharedCircuitBreaker == null) {
      synchronized (LOCK) {
        if (sharedCircuitBreaker == null) {
          if (config.circuitBreakerEnabled()) {
            Duration cbTimeout = Duration.ofSeconds(config.circuitBreakerTimeoutSeconds());
            sharedCircuitBreaker = new CircuitBreaker(
                config.circuitBreakerFailureThreshold(),
                cbTimeout,
                cbTimeout.multipliedBy(2),
                3
            );
          } else {
            sharedCircuitBreaker = new CircuitBreaker();
          }
        }
      }
    }
    return sharedCircuitBreaker;
  }

  /**
   * Gets or creates a shared RateLimiter instance.
   */
  private static RateLimiter getOrCreateRateLimiter(ServerConfig config) {
    if (sharedRateLimiter == null) {
      synchronized (LOCK) {
        if (sharedRateLimiter == null) {
          if (config.rateLimiterEnabled()) {
            RateLimiter.Strategy strategy = parseStrategy(config.rateLimitStrategy());
            sharedRateLimiter = new RateLimiter(
                strategy,
                config.rateLimitRequestsPerSecond(),
                Duration.ofSeconds(1)
            );
          } else {
            sharedRateLimiter = new RateLimiter(
                RateLimiter.Strategy.TOKEN_BUCKET,
                Integer.MAX_VALUE,
                Duration.ofSeconds(1)
            );
          }
        }
      }
    }
    return sharedRateLimiter;
  }

  /**
   * Resets the shared instances (useful for testing or config reload).
   */
  public static void resetSharedInstances() {
    synchronized (LOCK) {
      sharedCircuitBreaker = null;
      if (sharedRateLimiter != null) {
        sharedRateLimiter.shutdown();
        sharedRateLimiter = null;
      }
    }
  }

  private static RateLimiter.Strategy parseStrategy(String strategy) {
    return switch (strategy.toLowerCase()) {
      case "sliding-window" -> RateLimiter.Strategy.SLIDING_WINDOW;
      case "fixed-window" -> RateLimiter.Strategy.FIXED_WINDOW;
      default -> RateLimiter.Strategy.TOKEN_BUCKET;
    };
  }

  @Override
  public void run() {
    long connectionStartTime = System.currentTimeMillis();
    metrics.incrementActiveConnections();

    try (Arena arena = Arena.ofConfined()) {
      handleConnection(arena);
    } catch (Exception e) {
      AccessLogger.logError("Error handling connection", e.getMessage());
    } finally {
      long connectionDuration = System.currentTimeMillis() - connectionStartTime;
      metrics.decrementActiveConnections();
      metrics.recordConnectionDuration(connectionDuration);
      closeQuietly(clientChannel);
    }
  }

  private void handleConnection(Arena arena) throws IOException {
    // Handle TLS handshake if HTTPS
    SslWrapper.SslSession sslSession = null;
    if (sslWrapper != null) {
      try {
        sslSession = sslWrapper.wrap(clientChannel);
        sslSession.doHandshake();
      } catch (Exception e) {
        AccessLogger.logError("TLS handshake failed", e.getMessage());
        return;
      }
    }

    // Allocate buffer for reading request
    MemorySegment bufferSegment = arena.allocate(8192);
    ByteBuffer buffer = bufferSegment.asByteBuffer();

    // Set timeout for keep-alive
    clientChannel.socket().setSoTimeout(30000); // 30 seconds

    while (clientChannel.isOpen()) {
      long startTime = System.currentTimeMillis();
      buffer.clear();
      int totalBytesRead = 0;
      Request request = null;

      // Generate request ID for tracing
      String requestId = AccessLogger.generateRequestId();

      // Read request (with SSL if enabled)
      try {
        while (buffer.hasRemaining()) {
          int bytesRead;
          if (sslSession != null) {
            bytesRead = sslSession.read(buffer);
          } else {
            bytesRead = clientChannel.read(buffer);
          }

          if (bytesRead == -1) {
            return; // EOF
          }
          if (bytesRead == 0) {
            // Should not happen with blocking channel unless timeout?
            continue;
          }
          totalBytesRead = buffer.position();

          // Try to parse
          request = HttpParser.parse(bufferSegment, totalBytesRead);
          if (request != null) {
            break;
          }
        }
      } catch (IOException e) {
        // Timeout or other error
        break;
      }

      if (request == null) {
        // Bad request or incomplete headers
        break;
      }

      // Extract client IP
      String clientIp = extractClientIp();
      String userAgent = request.headers().getOrDefault("User-Agent", "");
      String path = request.path();
      String method = request.method();

      // Get CORS and Auth config
      CorsConfig corsConfig = serverConfig.corsConfig();
      AdminAuth adminAuth = serverConfig.adminAuth();
      String origin = request.headers().get("Origin");

      boolean keepAlive = !"close".equalsIgnoreCase(request.headers().get("Connection"));

      // Handle CORS preflight requests
      if (corsConfig.isEnabled() && "OPTIONS".equalsIgnoreCase(method)) {
        String requestMethod = request.headers().get("Access-Control-Request-Method");
        if (CorsConfig.isPreflight(method, origin, requestMethod)) {
          sendCorsPreflightResponse(clientChannel, sslSession, corsConfig, origin, requestMethod,
                                    request.headers().get("Access-Control-Request-Headers"));
          long duration = System.currentTimeMillis() - startTime;
          AccessLogger.logAccess(requestId, clientIp, method, path, 204, duration, 0, userAgent,
                                 "cors-preflight");
          metrics.recordRequest(204, duration, path, totalBytesRead, 0);
          if (!keepAlive) {
            break;
          }
          continue;
        }
      }

      // Apply rate limiting (only if enabled in config)
      if (serverConfig.rateLimiterEnabled() && !rateLimiter.allowRequest(clientIp, path)) {
        sendRateLimitResponse(clientChannel, sslSession, corsConfig, origin, method, clientIp,
                              path);
        long duration = System.currentTimeMillis() - startTime;
        AccessLogger.logAccess(requestId, clientIp, method, path, 429, duration, 0, userAgent,
                               "rate-limited");
        metrics.recordRequest(429, duration, path, totalBytesRead, 0);
        metrics.recordRateLimitRejection();
        // Usually rate limit response should close connection or be handled
        break;
      }

      // Check for admin API
      if (AdminHandler.isAdminRequest(path)) {
        // Check admin authentication
        if (adminAuth.isEnabled()) {
          String authHeader = request.headers().get("Authorization");
          if (!adminAuth.authenticate(authHeader, clientIp)) {
            sendUnauthorizedResponse(clientChannel, sslSession, adminAuth, corsConfig, origin,
                                     method);
            long duration = System.currentTimeMillis() - startTime;
            AccessLogger.logAccess(requestId, clientIp, method, path, 401, duration, 0, userAgent,
                                   "auth-failed");
            metrics.recordRequest(401, duration, path, totalBytesRead, 0);
            break;
          }
        }

        adminHandler.handle(clientChannel, request);
        long duration = System.currentTimeMillis() - startTime;
        AccessLogger.logAccess(requestId, clientIp, method, path, 200, duration, 0, userAgent,
                               "admin");
        metrics.recordRequest(200, duration, path, totalBytesRead, 0);
        if (!keepAlive) {
          break;
        }
        continue;
      }

      // Check for metrics endpoint
      if ("/metrics".equals(path)) {
        serveMetrics(clientChannel, sslSession);
        long duration = System.currentTimeMillis() - startTime;
        AccessLogger.logAccess(requestId, clientIp, method, path, 200, duration, 0, userAgent,
                               "internal");
        metrics.recordRequest(200, duration, path, totalBytesRead, 0);
        if (!keepAlive) {
          break;
        }
        continue;
      }

      // Check for health endpoint
      if ("/health".equals(path)) {
        serveHealth(clientChannel, sslSession);
        long duration = System.currentTimeMillis() - startTime;
        AccessLogger.logAccess(requestId, clientIp, method, path, 200, duration, 0, userAgent,
                               "internal");
        metrics.recordRequest(200, duration, path, totalBytesRead, 0);
        if (!keepAlive) {
          break;
        }
        continue;
      }

      // Route request
      String backend = router.resolveBackend(path, clientIp);
      int status = 200;
      long bytesSent = 0;

      if (backend != null) {
        // Check circuit breaker
        if (!circuitBreaker.allowRequest(backend)) {
          sendServiceUnavailable(clientChannel, sslSession, corsConfig, origin, method);
          long duration = System.currentTimeMillis() - startTime;
          AccessLogger.logAccess(requestId, clientIp, method, path, 503, duration, 0, userAgent,
                                 "circuit-open");
          metrics.recordRequest(503, duration, path, totalBytesRead, 0);
          metrics.recordCircuitBreakerStateChange(backend, false);
          break;
        }

        router.recordConnectionStart(backend);
        long backendStartTime = System.currentTimeMillis();
        try {
          // Check for WebSocket upgrade
          if (WebSocketHandler.isWebSocketUpgrade(request)) {
            WebSocketHandler.handleWebSocket(clientChannel, request, backend);
            circuitBreaker.recordSuccess(backend);
            metrics.recordBackendRequest(backend,
                                         System.currentTimeMillis() - backendStartTime, true);
            // WebSocket takes over connection, so we break loop
            break;
          } else if (backend.startsWith("file://")) {
            new StaticHandler().handle(clientChannel, backend, request);
            circuitBreaker.recordSuccess(backend);
            metrics.recordBackendRequest(backend,
                                         System.currentTimeMillis() - backendStartTime, true);
          } else {
            new ProxyHandler().handle(clientChannel, backend, buffer, totalBytesRead, request,
                                      arena);
            circuitBreaker.recordSuccess(backend);
            metrics.recordBackendRequest(backend,
                                         System.currentTimeMillis() - backendStartTime, true);
          }
          router.recordProxySuccess(backend);
        } catch (Exception e) {
          router.recordProxyFailure(backend, e.getMessage());
          circuitBreaker.recordFailure(backend);
          metrics.recordBackendRequest(backend,
                                       System.currentTimeMillis() - backendStartTime, false);
          status = 502; // Bad Gateway
          AccessLogger.logError("Proxy error", e.getMessage());
          // Send 502 with CORS headers on error responses
          send502WithCors(clientChannel, sslSession, corsConfig, origin, method);
          break;
        } finally {
          router.recordConnectionEnd(backend);
        }
      } else {
        // 404 Not Found
        new StaticHandler().handle404(clientChannel);
        status = 404;
      }

      // Log access and record metrics
      long duration = System.currentTimeMillis() - startTime;
      AccessLogger.logAccess(requestId, clientIp, method, path, status, duration, bytesSent,
                             userAgent,
                             backend != null ? backend : "none");
      metrics.recordRequest(status, duration, path, totalBytesRead, bytesSent);

      if (!keepAlive) {
        break;
      }
    }

    // Close SSL session
    if (sslWrapper != null) {
      // SSL cleanup
    }
  }

  private String extractClientIp() {
    try {
      return clientChannel.getRemoteAddress().toString().split(":")[0].replace("/", "");
    } catch (IOException e) {
      return "unknown";
    }
  }

  private void serveHealth(SocketChannel clientChannel, SslWrapper.SslSession sslSession)
      throws IOException {
    String body = "{\"status\":\"healthy\",\"timestamp\":\"" + java.time.Instant.now() + "\"}";
    String response = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: application/json\r\n" +
        "Content-Length: " + body.length() + "\r\n" +
        "\r\n" +
        body;
    byte[] data = response.getBytes(StandardCharsets.UTF_8);

    if (sslSession != null) {
      sslSession.write(ByteBuffer.wrap(data));
    } else {
      clientChannel.write(ByteBuffer.wrap(data));
    }
  }

  private void serveMetrics(SocketChannel clientChannel, SslWrapper.SslSession sslSession)
      throws IOException {
    String metricsBody = MetricsCollector.getInstance().exportPrometheus();
    String response = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/plain; version=0.0.4\r\n" +
        "Content-Length: " + metricsBody.length() + "\r\n" +
        "\r\n" +
        metricsBody;
    byte[] data = response.getBytes(StandardCharsets.UTF_8);

    if (sslSession != null) {
      sslSession.write(ByteBuffer.wrap(data));
    } else {
      clientChannel.write(ByteBuffer.wrap(data));
    }
  }

  private void sendRateLimitResponse(SocketChannel clientChannel, SslWrapper.SslSession sslSession,
                                     CorsConfig corsConfig, String origin, String method,
                                     String clientIp, String path) throws IOException {
    // Get rate limit info for response headers
    RateLimiter.RateLimitInfo info = rateLimiter.getRateLimitInfo(clientIp, path);

    StringBuilder headers = new StringBuilder();
    headers.append("HTTP/1.1 429 Too Many Requests\r\n");
    headers.append("Content-Type: application/json\r\n");
    headers.append("Retry-After: ").append(Math.max(1, info.resetSeconds())).append("\r\n");
    headers.append("X-RateLimit-Limit: ").append(info.limit()).append("\r\n");
    headers.append("X-RateLimit-Remaining: ").append(info.remaining()).append("\r\n");
    headers.append("X-RateLimit-Reset: ").append(info.resetSeconds()).append("\r\n");

    // Add CORS headers to error responses
    if (corsConfig.isEnabled() && origin != null) {
      Map<String, String> corsHeaders = corsConfig.getCorsHeaders(origin, method);
      for (Map.Entry<String, String> entry : corsHeaders.entrySet()) {
        headers.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
      }
    }

    String body = "{\"error\":\"Too Many Requests\",\"retry_after\":" + info.resetSeconds() + "}";
    headers.append("Content-Length: ").append(body.length()).append("\r\n");
    headers.append(CONNECTION_CLOSE_HEADER);
    headers.append("\r\n");
    headers.append(body);

    byte[] data = headers.toString().getBytes(StandardCharsets.UTF_8);

    if (sslSession != null) {
      sslSession.write(ByteBuffer.wrap(data));
    } else {
      clientChannel.write(ByteBuffer.wrap(data));
    }
  }

  private void sendCorsPreflightResponse(SocketChannel clientChannel,
                                         SslWrapper.SslSession sslSession, CorsConfig corsConfig,
                                         String origin, String requestMethod,
                                         String requestHeaders) throws IOException {
    Map<String, String> corsHeaders = corsConfig.getPreflightHeaders(origin, requestMethod,
                                                                     requestHeaders);

    StringBuilder response = new StringBuilder();
    response.append("HTTP/1.1 204 No Content\r\n");
    for (Map.Entry<String, String> entry : corsHeaders.entrySet()) {
      response.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
    }
    response.append("Content-Length: 0\r\n");
    response.append("\r\n");

    byte[] data = response.toString().getBytes(StandardCharsets.UTF_8);

    if (sslSession != null) {
      sslSession.write(ByteBuffer.wrap(data));
    } else {
      clientChannel.write(ByteBuffer.wrap(data));
    }
  }

  private void sendUnauthorizedResponse(SocketChannel clientChannel,
                                        SslWrapper.SslSession sslSession, AdminAuth adminAuth,
                                        CorsConfig corsConfig, String origin, String method)
      throws IOException {
    StringBuilder response = new StringBuilder();
    response.append("HTTP/1.1 401 Unauthorized\r\n");
    response.append("Content-Type: application/json\r\n");
    response.append("WWW-Authenticate: Basic realm=\"Admin API\"\r\n");

    // Add CORS headers to error responses
    if (corsConfig.isEnabled() && origin != null) {
      Map<String, String> corsHeaders = corsConfig.getCorsHeaders(origin, method);
      for (Map.Entry<String, String> entry : corsHeaders.entrySet()) {
        response.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
      }
    }

    String body = "{\"error\":\"Unauthorized\"}";
    response.append("Content-Length: ").append(body.length()).append("\r\n");
    response.append(CONNECTION_CLOSE_HEADER);
    response.append("\r\n");
    response.append(body);

    byte[] data = response.toString().getBytes(StandardCharsets.UTF_8);

    if (sslSession != null) {
      sslSession.write(ByteBuffer.wrap(data));
    } else {
      clientChannel.write(ByteBuffer.wrap(data));
    }
  }

  private void sendServiceUnavailable(SocketChannel clientChannel,
                                      SslWrapper.SslSession sslSession, CorsConfig corsConfig,
                                      String origin, String method) throws IOException {
    StringBuilder response = new StringBuilder();
    response.append("HTTP/1.1 503 Service Unavailable\r\n");
    response.append("Content-Type: application/json\r\n");
    response.append("Retry-After: 30\r\n");

    // Add CORS headers to error responses
    if (corsConfig.isEnabled() && origin != null) {
      Map<String, String> corsHeaders = corsConfig.getCorsHeaders(origin, method);
      for (Map.Entry<String, String> entry : corsHeaders.entrySet()) {
        response.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
      }
    }

    String body = "{\"error\":\"Service Unavailable\",\"message\":\"Circuit breaker is open\"}";
    response.append("Content-Length: ").append(body.length()).append("\r\n");
    response.append(CONNECTION_CLOSE_HEADER);
    response.append("\r\n");
    response.append(body);

    byte[] data = response.toString().getBytes(StandardCharsets.UTF_8);

    if (sslSession != null) {
      sslSession.write(ByteBuffer.wrap(data));
    } else {
      clientChannel.write(ByteBuffer.wrap(data));
    }
  }

  /**
   * Sends 502 Bad Gateway with CORS headers on error responses.
   */
  private void send502WithCors(SocketChannel clientChannel, SslWrapper.SslSession sslSession,
                               CorsConfig corsConfig, String origin, String method)
      throws IOException {
    StringBuilder response = new StringBuilder();
    response.append("HTTP/1.1 502 Bad Gateway\r\n");
    response.append("Content-Type: application/json\r\n");

    // Add CORS headers to error responses
    if (corsConfig.isEnabled() && origin != null) {
      Map<String, String> corsHeaders = corsConfig.getCorsHeaders(origin, method);
      for (Map.Entry<String, String> entry : corsHeaders.entrySet()) {
        response.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
      }
    }

    String body = "{\"error\":\"Bad Gateway\"}";
    response.append("Content-Length: ").append(body.length()).append("\r\n");
    response.append(CONNECTION_CLOSE_HEADER);
    response.append("\r\n");
    response.append(body);

    byte[] data = response.toString().getBytes(StandardCharsets.UTF_8);

    if (sslSession != null) {
      sslSession.write(ByteBuffer.wrap(data));
    } else {
      clientChannel.write(ByteBuffer.wrap(data));
    }
  }

  private void closeQuietly(SocketChannel channel) {
    try {
      if (channel != null && channel.isOpen()) {
        channel.close();
      }
    } catch (IOException ignored) {
    }
  }
}
