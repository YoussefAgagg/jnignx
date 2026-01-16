package com.github.youssefagagg.jnignx.core;

import com.github.youssefagagg.jnignx.handlers.AdminHandler;
import com.github.youssefagagg.jnignx.handlers.ProxyHandler;
import com.github.youssefagagg.jnignx.handlers.StaticHandler;
import com.github.youssefagagg.jnignx.handlers.WebSocketHandler;
import com.github.youssefagagg.jnignx.http.HttpParser;
import com.github.youssefagagg.jnignx.http.Request;
import com.github.youssefagagg.jnignx.tls.SslWrapper;
import com.github.youssefagagg.jnignx.util.AccessLogger;
import com.github.youssefagagg.jnignx.util.MetricsCollector;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * Worker thread that handles a single connection with metrics and logging.
 *
 * <p>Supports HTTP, HTTPS, WebSocket, and admin API requests.
 */
public class Worker implements Runnable {

  private final SocketChannel clientChannel;
  private final Router router;
  private final SslWrapper sslWrapper;
  private final MetricsCollector metrics;
  private final AdminHandler adminHandler;
  private final CircuitBreaker circuitBreaker;
  private final RateLimiter rateLimiter;

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
    this.circuitBreaker = new CircuitBreaker();
    this.rateLimiter = new RateLimiter(
        RateLimiter.Strategy.TOKEN_BUCKET,
        100,
        java.time.Duration.ofSeconds(1)
    );
    this.adminHandler = new AdminHandler(router, metrics, circuitBreaker, rateLimiter);
  }

  @Override
  public void run() {
    metrics.incrementActiveConnections();
    long startTime = System.currentTimeMillis();

    try (Arena arena = Arena.ofConfined()) {
      handleConnection(arena, startTime);
    } catch (Exception e) {
      AccessLogger.logError("Error handling connection", e.getMessage());
    } finally {
      metrics.decrementActiveConnections();
      closeQuietly(clientChannel);
    }
  }

  private void handleConnection(Arena arena, long startTime) throws IOException {
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

    int totalBytesRead = 0;
    Request request = null;

    // Read request (with SSL if enabled)
    while (buffer.hasRemaining()) {
      int bytesRead;
      if (sslSession != null) {
        bytesRead = sslSession.read(buffer);
      } else {
        bytesRead = clientChannel.read(buffer);
      }

      if (bytesRead == -1) {
        break;
      }
      totalBytesRead = buffer.position();

      // Try to parse
      request = HttpParser.parse(bufferSegment, totalBytesRead);
      if (request != null) {
        break;
      }
    }

    if (request == null) {
      // Bad request or incomplete headers
      return;
    }

    // Extract client IP
    String clientIp = extractClientIp();
    String userAgent = request.headers().getOrDefault("User-Agent", "");
    String path = request.path();
    String method = request.method();

    // Apply rate limiting
    if (!rateLimiter.allowRequest(clientIp, path)) {
      sendRateLimitResponse(clientChannel, sslSession);
      long duration = System.currentTimeMillis() - startTime;
      AccessLogger.logAccess(clientIp, method, path, 429, duration, 0, userAgent, "rate-limited");
      metrics.recordRequest(429, duration, path, totalBytesRead, 0);
      return;
    }

    // Check for admin API
    if (AdminHandler.isAdminRequest(path)) {
      adminHandler.handle(clientChannel, request);
      long duration = System.currentTimeMillis() - startTime;
      AccessLogger.logAccess(clientIp, method, path, 200, duration, 0, userAgent, "admin");
      metrics.recordRequest(200, duration, path, totalBytesRead, 0);
      return;
    }

    // Check for metrics endpoint
    if ("/metrics".equals(path)) {
      serveMetrics(clientChannel, sslSession);
      long duration = System.currentTimeMillis() - startTime;
      AccessLogger.logAccess(clientIp, method, path, 200, duration, 0, userAgent, "internal");
      metrics.recordRequest(200, duration, path, totalBytesRead, 0);
      return;
    }

    // Route request
    String backend = router.resolveBackend(path, clientIp);
    int status = 200;
    long bytesSent = 0;

    if (backend != null) {
      // Check circuit breaker
      if (!circuitBreaker.allowRequest(backend)) {
        sendServiceUnavailable(clientChannel, sslSession);
        long duration = System.currentTimeMillis() - startTime;
        AccessLogger.logAccess(clientIp, method, path, 503, duration, 0, userAgent, "circuit-open");
        metrics.recordRequest(503, duration, path, totalBytesRead, 0);
        return;
      }

      router.recordConnectionStart(backend);
      try {
        // Check for WebSocket upgrade
        if (WebSocketHandler.isWebSocketUpgrade(request)) {
          WebSocketHandler.handleWebSocket(clientChannel, request, backend);
          circuitBreaker.recordSuccess(backend);
        } else if (backend.startsWith("file://")) {
          new StaticHandler().handle(clientChannel, backend, request);
          circuitBreaker.recordSuccess(backend);
        } else {
          new ProxyHandler().handle(clientChannel, backend, buffer, totalBytesRead, request, arena);
          circuitBreaker.recordSuccess(backend);
        }
        router.recordProxySuccess(backend);
      } catch (Exception e) {
        router.recordProxyFailure(backend, e.getMessage());
        circuitBreaker.recordFailure(backend);
        status = 502; // Bad Gateway
        AccessLogger.logError("Proxy error", e.getMessage());
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
    AccessLogger.logAccess(clientIp, method, path, status, duration, bytesSent, userAgent,
                           backend != null ? backend : "none");
    metrics.recordRequest(status, duration, path, totalBytesRead, bytesSent);

    // Close SSL session if present
    if (sslSession != null) {
      try {
        sslSession.close();
      } catch (IOException e) {
        // Ignore
      }
    }
  }

  private String extractClientIp() {
    try {
      return clientChannel.getRemoteAddress().toString().split(":")[0].replace("/", "");
    } catch (IOException e) {
      return "unknown";
    }
  }

  private void serveMetrics(SocketChannel clientChannel, SslWrapper.SslSession sslSession)
      throws IOException {
    String metricsText = metrics.exportPrometheus();
    byte[] body = metricsText.getBytes(StandardCharsets.UTF_8);

    String response = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/plain; version=0.0.4\r\n" +
        "Content-Length: " + body.length + "\r\n" +
        "Connection: close\r\n" +
        "\r\n";

    ByteBuffer buffer = ByteBuffer.allocate(response.length() + body.length);
    buffer.put(response.getBytes(StandardCharsets.UTF_8));
    buffer.put(body);
    buffer.flip();

    while (buffer.hasRemaining()) {
      if (sslSession != null) {
        sslSession.write(buffer);
      } else {
        clientChannel.write(buffer);
      }
    }
  }

  private void sendRateLimitResponse(SocketChannel clientChannel, SslWrapper.SslSession sslSession)
      throws IOException {
    String response = "HTTP/1.1 429 Too Many Requests\r\n" +
        "Content-Type: text/plain\r\n" +
        "Retry-After: 1\r\n" +
        "Content-Length: 18\r\n" +
        "Connection: close\r\n" +
        "\r\n" +
        "Rate limit exceeded";

    ByteBuffer buffer = ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));

    while (buffer.hasRemaining()) {
      if (sslSession != null) {
        sslSession.write(buffer);
      } else {
        clientChannel.write(buffer);
      }
    }
  }

  private void sendServiceUnavailable(SocketChannel clientChannel, SslWrapper.SslSession sslSession)
      throws IOException {
    String response = "HTTP/1.1 503 Service Unavailable\r\n" +
        "Content-Type: text/plain\r\n" +
        "Retry-After: 60\r\n" +
        "Content-Length: 28\r\n" +
        "Connection: close\r\n" +
        "\r\n" +
        "Service temporarily unavailable";

    ByteBuffer buffer = ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));

    while (buffer.hasRemaining()) {
      if (sslSession != null) {
        sslSession.write(buffer);
      } else {
        clientChannel.write(buffer);
      }
    }
  }

  private void closeQuietly(SocketChannel channel) {
    try {
      if (channel != null) {
        channel.close();
      }
    } catch (IOException e) {
      // Ignore
    }
  }
}
