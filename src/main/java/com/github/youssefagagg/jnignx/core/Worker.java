package com.github.youssefagagg.jnignx.core;

import com.github.youssefagagg.jnignx.handlers.ProxyHandler;
import com.github.youssefagagg.jnignx.handlers.StaticHandler;
import com.github.youssefagagg.jnignx.http.HttpParser;
import com.github.youssefagagg.jnignx.http.Request;
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
 */
public class Worker implements Runnable {

  private final SocketChannel clientChannel;
  private final Router router;
  private final MetricsCollector metrics;

  public Worker(SocketChannel clientChannel, Router router) {
    this.clientChannel = clientChannel;
    this.router = router;
    this.metrics = MetricsCollector.getInstance();
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
    // Allocate buffer for reading request
    MemorySegment bufferSegment = arena.allocate(8192);
    ByteBuffer buffer = bufferSegment.asByteBuffer();

    int totalBytesRead = 0;
    Request request = null;

    while (buffer.hasRemaining()) {
      int bytesRead = clientChannel.read(buffer);
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

    // Check for metrics endpoint
    if ("/metrics".equals(path)) {
      serveMetrics(clientChannel);
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
      router.recordConnectionStart(backend);
      try {
        if (backend.startsWith("file://")) {
          new StaticHandler().handle(clientChannel, backend, request);
        } else {
          new ProxyHandler().handle(clientChannel, backend, buffer, totalBytesRead, request, arena);
        }
        router.recordProxySuccess(backend);
      } catch (Exception e) {
        router.recordProxyFailure(backend, e.getMessage());
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
  }

  private String extractClientIp() {
    try {
      return clientChannel.getRemoteAddress().toString().split(":")[0].replace("/", "");
    } catch (IOException e) {
      return "unknown";
    }
  }

  private void serveMetrics(SocketChannel clientChannel) throws IOException {
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
      clientChannel.write(buffer);
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
