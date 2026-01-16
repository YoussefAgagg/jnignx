package com.github.youssefagagg.jnignx.core;

import com.github.youssefagagg.jnignx.handlers.ProxyHandler;
import com.github.youssefagagg.jnignx.handlers.StaticHandler;
import com.github.youssefagagg.jnignx.http.HttpParser;
import com.github.youssefagagg.jnignx.http.Request;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Worker thread that handles a single connection.
 */
public class Worker implements Runnable {

  private final SocketChannel clientChannel;
  private final Router router;

  public Worker(SocketChannel clientChannel, Router router) {
    this.clientChannel = clientChannel;
    this.router = router;
  }

  @Override
  public void run() {
    try (Arena arena = Arena.ofConfined()) {
      handleConnection(arena);
    } catch (Exception e) {
      System.err.println("[Worker] Error handling connection: " + e.getMessage());
      e.printStackTrace();
    } finally {
      closeQuietly(clientChannel);
    }
  }

  private void handleConnection(Arena arena) throws IOException {
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

    // Route request
    String backend = router.resolveBackend(request.path());
    if (backend != null) {
      if (backend.startsWith("file://")) {
        new StaticHandler().handle(clientChannel, backend, request);
      } else {
        new ProxyHandler().handle(clientChannel, backend, buffer, totalBytesRead, request, arena);
      }
    } else {
      // 404 Not Found
      new StaticHandler().handle404(clientChannel);
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
