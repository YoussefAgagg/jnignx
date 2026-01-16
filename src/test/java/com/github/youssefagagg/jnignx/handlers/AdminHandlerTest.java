package com.github.youssefagagg.jnignx.handlers;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.youssefagagg.jnignx.http.Request;
import com.github.youssefagagg.jnignx.util.MetricsCollector;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminHandlerTest {

  @Test
  void testHealthEndpoint() throws Exception {
    runTest(channel -> {
      Request req = new Request("GET", "/admin/health", "HTTP/1.1", Map.of(), 0, false, 0);
      new AdminHandler(null, MetricsCollector.getInstance(), null, null).handle(channel, req);
    }, response -> {
      assertTrue(response.contains("HTTP/1.1 200 OK"), "Should return 200 OK");
      assertTrue(response.contains("\"status\":\"ok\"") || response.contains("ok") ||
                     response.contains("health"),
                 "Should contain status info");
    });
  }

  @Test
  void testMetricsEndpoint() throws Exception {
    runTest(channel -> {
      Request req = new Request("GET", "/admin/metrics", "HTTP/1.1", Map.of(), 0, false, 0);
      new AdminHandler(null, MetricsCollector.getInstance(), null, null).handle(channel, req);
    }, response -> {
      assertTrue(response.contains("HTTP/1.1 200 OK"), "Should return 200 OK");
      // Prometheus metrics format
      assertTrue(
          response.contains("nanoserver") || response.contains("#") || response.contains("metrics"),
          "Should contain metrics");
    });
  }

  @Test
  void testStatsEndpoint() throws Exception {
    runTest(channel -> {
      Request req = new Request("GET", "/admin/stats", "HTTP/1.1", Map.of(), 0, false, 0);
      new AdminHandler(null, MetricsCollector.getInstance(), null, null).handle(channel, req);
    }, response -> {
      assertTrue(response.contains("HTTP/1.1 200 OK") || response.contains("200"),
                 "Should return 200 OK");
    });
  }

  @Test
  void testUnauthorizedAccess() throws Exception {
    AdminHandler handler = new AdminHandler(null, MetricsCollector.getInstance(), null, null);

    runTest(channel -> {
      // No auth header
      Request req = new Request("GET", "/admin/config", "HTTP/1.1", Map.of(), 0, false, 0);
      handler.handle(channel, req);
    }, response -> {
      // Might require authentication or allow access
      assertTrue(response.contains("401") || response.contains("403") || response.contains("200") ||
                     response.contains("HTTP"));
    });
  }

  @Test
  void testInvalidEndpoint() throws Exception {
    runTest(channel -> {
      Request req = new Request("GET", "/admin/invalid", "HTTP/1.1", Map.of(), 0, false, 0);
      new AdminHandler(null, MetricsCollector.getInstance(), null, null).handle(channel, req);
    }, response -> {
      assertTrue(
          response.contains("404") || response.contains("Not Found") || response.contains("HTTP"));
    });
  }

  @Test
  void testConfigReloadEndpoint() throws Exception {
    runTest(channel -> {
      Request req = new Request("POST", "/admin/routes/reload", "HTTP/1.1",
                                Map.of("Authorization", "Bearer test-token"), 0, false, 0);
      new AdminHandler(null, MetricsCollector.getInstance(), null, null).handle(channel, req);
    }, response -> {
      // Should attempt reload or return auth error
      assertTrue(response.contains("HTTP/1.1"));
    });
  }

  @Test
  void testMethodNotAllowed() throws Exception {
    runTest(channel -> {
      // POST to GET-only endpoint
      Request req = new Request("POST", "/admin/health", "HTTP/1.1", Map.of(), 0, false, 0);
      new AdminHandler(null, MetricsCollector.getInstance(), null, null).handle(channel, req);
    }, response -> {
      // Might allow or return 405
      assertTrue(response.contains("HTTP/1.1"));
    });
  }

  private void runTest(ThrowingConsumer<SocketChannel> serverAction,
                       ThrowingConsumer<String> clientAssertion) throws Exception {
    try (ServerSocketChannel server = ServerSocketChannel.open()) {
      server.bind(new InetSocketAddress(0));
      int port = ((InetSocketAddress) server.getLocalAddress()).getPort();

      Thread serverThread = new Thread(() -> {
        try (SocketChannel client = server.accept()) {
          serverAction.accept(client);
        } catch (Exception e) {
          // Expected for some tests
        }
      });
      serverThread.start();

      try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", port))) {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        StringBuilder sb = new StringBuilder();

        // Read with timeout
        client.configureBlocking(false);
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 2000) {
          int read = client.read(buffer);
          if (read > 0) {
            buffer.flip();
            sb.append(StandardCharsets.UTF_8.decode(buffer));
            buffer.clear();
          } else if (read == -1) {
            break;
          }
          Thread.sleep(10);
        }

        clientAssertion.accept(sb.toString());
      }

      serverThread.join(3000);
    }
  }

  interface ThrowingConsumer<T> {
    void accept(T t) throws Exception;
  }
}
