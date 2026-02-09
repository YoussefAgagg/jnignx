package com.github.youssefagagg.jnignx.handlers;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.youssefagagg.jnignx.http.Request;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticHandlerTest {

  @TempDir
  Path tempDir;

  @Test
  void testDirectoryListing() throws Exception {
    // Setup
    Files.createDirectories(tempDir.resolve("subdir"));
    Files.writeString(tempDir.resolve("subdir/test.txt"), "Hello World");

    runTest(channel -> {
      Request req = new Request("GET", "/subdir/", "HTTP/1.1", Map.of(), 0, false, 0);
      new StaticHandler().handle(channel, "file://" + tempDir.toAbsolutePath(), req);
    }, response -> {
      assertTrue(response.contains("HTTP/1.1 200 OK"));
      assertTrue(response.contains("Index of /subdir/"));
      assertTrue(response.contains("test.txt"));
    });
  }

  @Test
  void testServeSpecificFile() throws Exception {
    // Setup: create a specific file that the route points to directly
    Path file = tempDir.resolve("test.png");
    byte[] content = "fake-png-content".getBytes(StandardCharsets.UTF_8);
    Files.write(file, content);

    runTest(channel -> {
      // The request path is the route prefix, but the backend points to a specific file
      Request req = new Request("GET", "/assets", "HTTP/1.1", Map.of(), 0, false, 0);
      new StaticHandler().handle(channel, "file://" + file.toAbsolutePath(), req);
    }, response -> {
      assertTrue(response.contains("HTTP/1.1 200 OK"));
      assertTrue(response.contains("Content-Type: image/png"));
      assertTrue(response.contains("fake-png-content"));
    });
  }

  @Test
  void testServeSpecificFileWithSubpath() throws Exception {
    // Even with a sub-path in the request, if backend is a file, serve that file
    Path file = tempDir.resolve("data.json");
    Files.writeString(file, "{\"key\":\"value\"}");

    runTest(channel -> {
      Request req = new Request("GET", "/api/data", "HTTP/1.1", Map.of(), 0, false, 0);
      new StaticHandler().handle(channel, "file://" + file.toAbsolutePath(), req);
    }, response -> {
      assertTrue(response.contains("HTTP/1.1 200 OK"));
      assertTrue(response.contains("Content-Type: application/json"));
      assertTrue(response.contains("{\"key\":\"value\"}"));
    });
  }

  @Test
  void testNonExistentRootReturns404() throws Exception {
    runTest(channel -> {
      Request req = new Request("GET", "/missing", "HTTP/1.1", Map.of(), 0, false, 0);
      new StaticHandler().handle(channel, "file://" + tempDir.resolve("nonexistent"), req);
    }, response -> {
      assertTrue(response.contains("HTTP/1.1 404 Not Found"));
    });
  }

  @Test
  void testGzipCompression() throws Exception {
    // Setup
    Path file = tempDir.resolve("script.js");
    Files.writeString(file, "console.log('hello');".repeat(100)); // Make it big enough

    runTest(channel -> {
      Request req =
          new Request("GET", "/script.js", "HTTP/1.1", Map.of("Accept-Encoding", "gzip"), 0, false,
                      0);
      new StaticHandler().handle(channel, "file://" + tempDir.toAbsolutePath(), req);
    }, response -> {
      assertTrue(response.contains("HTTP/1.1 200 OK"));
      assertTrue(response.contains("Content-Encoding: gzip"));
      assertTrue(response.contains("Transfer-Encoding: chunked"));
    });
  }

  private void runTest(ThrowingConsumer<SocketChannel> serverAction,
                       ThrowingConsumer<String> clientAssertion) throws Exception {
    try (ServerSocketChannel server = ServerSocketChannel.open()) {
      server.bind(new InetSocketAddress(0));
      int port = ((InetSocketAddress) server.getLocalAddress()).getPort();

      CompletableFuture<Void> serverFuture = CompletableFuture.runAsync(() -> {
        try (SocketChannel client = server.accept()) {
          serverAction.accept(client);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });

      try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", port))) {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        StringBuilder sb = new StringBuilder();
        while (client.read(buffer) != -1) {
          buffer.flip();
          sb.append(StandardCharsets.UTF_8.decode(buffer));
          buffer.clear();
        }
        clientAssertion.accept(sb.toString());
      }

      serverFuture.join();
    }
  }

  interface ThrowingConsumer<T> {
    void accept(T t) throws Exception;
  }
}
