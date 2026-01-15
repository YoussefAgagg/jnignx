package com.github.youssefagagg.jnignx.handlers;

import com.github.youssefagagg.jnignx.http.Request;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Handles reverse proxying to backend servers.
 */
public class ProxyHandler {

  private static final int BUFFER_SIZE = 8192;

  /**
   * Handles the proxy request.
   *
   * @param clientChannel the client socket channel
   * @param backendUrl    the backend URL
   * @param initialData   the data already read from the client
   * @param initialBytes  the number of bytes read
   * @param request       the parsed request
   * @param arena         the memory arena for allocation
   * @throws IOException if an I/O error occurs
   */
  public void handle(SocketChannel clientChannel, String backendUrl, ByteBuffer initialData,
                     int initialBytes, Request request, Arena arena) throws IOException {
    proxyToBackend(arena, backendUrl, initialData, initialBytes, request, clientChannel);
  }

  private void proxyToBackend(Arena arena, String backendUrl, ByteBuffer initialData,
                              int initialBytes, Request request, SocketChannel clientChannel)
      throws IOException {
    URI uri = URI.create(backendUrl);
    String host = uri.getHost();
    int port = uri.getPort() != -1 ? uri.getPort() : 80;

    try (SocketChannel backendChannel = SocketChannel.open()) {
      backendChannel.connect(new InetSocketAddress(host, port));

      // Forward initial data (Headers + part of Body)
      initialData.rewind();
      initialData.limit(initialBytes);
      while (initialData.hasRemaining()) {
        backendChannel.write(initialData);
      }

      // Calculate remaining body to read from Client
      long bodyBytesRead = initialBytes - request.headerLength();
      long bodyBytesRemaining = request.bodyLength() - bodyBytesRead;
      if (bodyBytesRemaining < 0) {
        bodyBytesRemaining = 0; // Should not happen if parser is correct
      }

      // Start thread for Backend -> Client (Response)
      Thread backendToClient = Thread.ofVirtual().start(() -> {
        try {
          transfer(backendChannel, clientChannel);
        } catch (IOException e) {
          // Ignored: Connection closed or reset
        }
      });

      // Handle Client -> Backend (Remaining Request Body)
      if (bodyBytesRemaining > 0) {
        transferFixed(clientChannel, backendChannel, bodyBytesRemaining, arena);
      }

      // Wait for response to finish
      try {
        backendToClient.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  // Transfer until EOF
  private void transfer(SocketChannel source, SocketChannel destination) throws IOException {
    try (Arena transferArena = Arena.ofConfined()) {
      MemorySegment bufferSegment = transferArena.allocate(BUFFER_SIZE);
      ByteBuffer buffer = bufferSegment.asByteBuffer();

      while (true) {
        buffer.clear();
        int bytesRead = source.read(buffer);
        if (bytesRead == -1) {
          break;
        }

        buffer.flip();
        while (buffer.hasRemaining()) {
          destination.write(buffer);
        }
      }
    }
  }

  // Transfer fixed amount
  private void transferFixed(SocketChannel source, SocketChannel destination, long count,
                             Arena arena) throws IOException {
    MemorySegment bufferSegment = arena.allocate(BUFFER_SIZE);
    ByteBuffer buffer = bufferSegment.asByteBuffer();
    long remaining = count;

    while (remaining > 0) {
      buffer.clear();
      if (remaining < BUFFER_SIZE) {
        buffer.limit((int) remaining);
      }

      int bytesRead = source.read(buffer);
      if (bytesRead == -1) {
        break;
      }

      buffer.flip();
      while (buffer.hasRemaining()) {
        destination.write(buffer);
      }
      remaining -= bytesRead;
    }
  }
}
