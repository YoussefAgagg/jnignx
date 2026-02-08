package com.github.youssefagagg.jnignx.handlers;

import com.github.youssefagagg.jnignx.core.ClientConnection;
import com.github.youssefagagg.jnignx.http.Request;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Handles reverse proxying to backend servers with proper header forwarding.
 *
 * <p>Features:
 * <ul>
 *   <li>Chunked Transfer Encoding support for both requests and responses</li>
 *   <li>Connection pooling for reduced latency</li>
 *   <li>Retry logic with alternate backends</li>
 *   <li>Proper 502 Bad Gateway error responses</li>
 *   <li>X-Forwarded-For, X-Real-IP, X-Forwarded-Proto header injection</li>
 * </ul>
 */
public class ProxyHandler {

  private static final int BUFFER_SIZE = 8192;

  // Connection pool: backend host:port -> queue of reusable channels
  private static final Map<String, Queue<SocketChannel>> CONNECTION_POOL =
      new ConcurrentHashMap<>();
  private static final int MAX_POOL_SIZE_PER_BACKEND = 10;

  // Retry configuration
  private static final int MAX_RETRIES = 2;

  /**
   * Clears all pooled connections. Useful for shutdown.
   */
  public static void clearConnectionPool() {
    for (Queue<SocketChannel> pool : CONNECTION_POOL.values()) {
      SocketChannel channel;
      while ((channel = pool.poll()) != null) {
        try {
          channel.close();
        } catch (IOException ignored) {
        }
      }
    }
    CONNECTION_POOL.clear();
  }

  /**
   * Handles the proxy request with retry logic and proper error responses.
   *
   * @param conn         the client connection (may be TLS)
   * @param backendUrl   the backend URL
   * @param initialData  the data already read from the client
   * @param initialBytes the number of bytes read
   * @param request      the parsed request
   * @param arena        the memory arena for allocation
   * @throws IOException if an I/O error occurs after all retries
   */
  public void handle(ClientConnection conn, String backendUrl, ByteBuffer initialData,
                     int initialBytes, Request request, Arena arena) throws IOException {
    handle(conn, backendUrl, initialData, initialBytes, request, arena, null);
  }

  /**
   * Backward-compatible overload that wraps a raw SocketChannel.
   */
  public void handle(SocketChannel clientChannel, String backendUrl, ByteBuffer initialData,
                     int initialBytes, Request request, Arena arena) throws IOException {
    handle(new ClientConnection(clientChannel), backendUrl, initialData, initialBytes, request,
           arena, null);
  }

  /**
   * Handles the proxy request with retry logic across alternate backends.
   *
   * @param conn              the client connection (may be TLS)
   * @param backendUrl        the primary backend URL
   * @param initialData       the data already read from the client
   * @param initialBytes      the number of bytes read
   * @param request           the parsed request
   * @param arena             the memory arena for allocation
   * @param alternateBackends list of alternate backends to try on failure (may be null)
   * @throws IOException if an I/O error occurs after all retries
   */
  public void handle(ClientConnection conn, String backendUrl, ByteBuffer initialData,
                     int initialBytes, Request request, Arena arena,
                     List<String> alternateBackends) throws IOException {
    IOException lastException = null;

    // Try primary backend with retries
    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      try {
        proxyToBackend(arena, backendUrl, initialData, initialBytes, request, conn);
        return; // Success
      } catch (IOException e) {
        lastException = e;
        // Reset buffer position for retry
        initialData.rewind();
      }
    }

    // Try alternate backends if available
    if (alternateBackends != null) {
      for (String alternate : alternateBackends) {
        if (alternate.equals(backendUrl)) {
          continue; // Skip the primary that already failed
        }
        try {
          initialData.rewind();
          proxyToBackend(arena, alternate, initialData, initialBytes, request, conn);
          return; // Success on alternate
        } catch (IOException e) {
          lastException = e;
        }
      }
    }

    // All backends failed - send 502 Bad Gateway
    send502Response(conn, lastException);
    throw lastException != null ? lastException : new IOException("All backends failed");
  }

  /**
   * Sends a proper 502 Bad Gateway response to the client.
   */
  private void send502Response(ClientConnection conn, IOException cause) {
    try {
      String errorMessage = cause != null ? cause.getMessage() : "Backend connection failed";
      String body = "{\"error\":\"Bad Gateway\",\"message\":\"" +
          escapeJson(errorMessage) + "\"}";
      byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

      String response = "HTTP/1.1 502 Bad Gateway\r\n" +
          "Content-Type: application/json\r\n" +
          "Content-Length: " + bodyBytes.length + "\r\n" +
          "Connection: close\r\n" +
          "\r\n";

      conn.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
      conn.write(ByteBuffer.wrap(bodyBytes));
    } catch (IOException ignored) {
      // Best-effort error response
    }
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

  private void proxyToBackend(Arena arena, String backendUrl, ByteBuffer initialData,
                              int initialBytes, Request request, ClientConnection conn)
      throws IOException {
    URI uri = URI.create(backendUrl);
    String host = uri.getHost();
    int port = uri.getPort() != -1 ? uri.getPort() : 80;
    String poolKey = host + ":" + port;

    SocketChannel backendChannel = acquireConnection(poolKey, host, port);

    try {
      // 1. Send modified headers with X-Forwarded-* headers
      String clientIp = extractClientIp(conn.rawChannel());
      boolean isTls = conn.isTls();
      byte[] newHeaders = reconstructHeaders(request, clientIp, host, isTls);
      ByteBuffer headersBuf = ByteBuffer.wrap(newHeaders);
      while (headersBuf.hasRemaining()) {
        backendChannel.write(headersBuf);
      }

      // 2. Forward remaining body from initialData (skip original headers)
      initialData.rewind();
      if (initialBytes > request.headerLength()) {
        initialData.position(request.headerLength());
        initialData.limit(initialBytes);
        while (initialData.hasRemaining()) {
          backendChannel.write(initialData);
        }
      }

      // Calculate remaining body to read from Client
      long bodyBytesRead = initialBytes - request.headerLength();
      if (bodyBytesRead < 0) {
        bodyBytesRead = 0;
      }

      // Handle chunked transfer encoding for request body
      // Note: client reads go through conn (which handles TLS decryption)
      if (request.isChunked()) {
        transferChunkedFromClient(conn, backendChannel, arena);
      } else {
        long bodyBytesRemaining = request.bodyLength() - bodyBytesRead;
        if (bodyBytesRemaining < 0) {
          bodyBytesRemaining = 0;
        }

        // Handle Client -> Backend (Remaining Request Body)
        if (bodyBytesRemaining > 0) {
          transferFixedFromClient(conn, backendChannel, bodyBytesRemaining, arena);
        }
      }

      // Start thread for Backend -> Client (Response)
      // Backend sends plain HTTP; we write to client through conn (which encrypts if TLS)
      Thread backendToClient = Thread.ofVirtual().start(() -> {
        try {
          transferToClient(backendChannel, conn);
        } catch (IOException e) {
          // Ignored: Connection closed or reset
        }
      });

      // Wait for response to finish
      try {
        backendToClient.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    } finally {
      // Always close the backend connection since we send Connection: close
      closeQuietly(backendChannel);
    }
  }

  /**
   * Acquires a connection from the pool or creates a new one.
   */
  private SocketChannel acquireConnection(String poolKey, String host, int port)
      throws IOException {
    Queue<SocketChannel> pool = CONNECTION_POOL.get(poolKey);
    if (pool != null) {
      SocketChannel channel;
      while ((channel = pool.poll()) != null) {
        if (channel.isOpen() && channel.isConnected()) {
          return channel;
        }
        closeQuietly(channel);
      }
    }

    // Create new connection
    SocketChannel channel = SocketChannel.open();
    channel.connect(new InetSocketAddress(host, port));
    return channel;
  }

  /**
   * Returns a connection to the pool for reuse.
   */
  private void releaseConnection(String poolKey, SocketChannel channel) {
    Queue<SocketChannel> pool = CONNECTION_POOL.computeIfAbsent(
        poolKey, _ -> new ConcurrentLinkedQueue<>());

    if (pool.size() < MAX_POOL_SIZE_PER_BACKEND && channel.isOpen()) {
      pool.offer(channel);
    } else {
      closeQuietly(channel);
    }
  }

  private String extractClientIp(SocketChannel channel) {
    try {
      return channel.getRemoteAddress().toString().split(":")[0].replace("/", "");
    } catch (IOException e) {
      return "unknown";
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

  private byte[] reconstructHeaders(Request request, String clientIp, String backendHost,
                                    boolean isTls) {
    StringBuilder sb = new StringBuilder();
    sb.append(request.method()).append(" ")
      .append(request.path()).append(" ")
      .append(request.version()).append("\r\n");

    // Forward existing headers (except Connection, Host, and existing X-Forwarded-*)
    for (Map.Entry<String, String> entry : request.headers().entrySet()) {
      String key = entry.getKey();
      if (!key.equalsIgnoreCase("Connection") &&
          !key.equalsIgnoreCase("Host") &&
          !key.startsWith("X-Forwarded-")) {
        sb.append(key).append(": ").append(entry.getValue()).append("\r\n");
      }
    }

    // Add proxy headers
    sb.append("Host: ").append(backendHost).append("\r\n");
    sb.append("Connection: close\r\n");
    sb.append("X-Forwarded-For: ").append(clientIp).append("\r\n");
    sb.append("X-Forwarded-Proto: ").append(isTls ? "https" : "http").append("\r\n");
    sb.append("X-Real-IP: ").append(clientIp).append("\r\n");
    sb.append("\r\n");

    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Transfers chunked encoded data from client (through ClientConnection) to backend.
   */
  private void transferChunkedFromClient(ClientConnection source, SocketChannel destination,
                                         Arena arena) throws IOException {
    MemorySegment bufferSegment = arena.allocate(BUFFER_SIZE);
    ByteBuffer buffer = bufferSegment.asByteBuffer();

    while (true) {
      // Read chunk size line from client
      String chunkSizeLine = readLineFromClient(source, buffer);
      if (chunkSizeLine == null) {
        break;
      }

      // Forward the chunk size line to backend
      writeString(destination, chunkSizeLine + "\r\n");

      // Parse chunk size
      int chunkSize;
      try {
        chunkSize = Integer.parseInt(chunkSizeLine.trim().split(";")[0], 16);
      } catch (NumberFormatException e) {
        break;
      }

      if (chunkSize == 0) {
        // Last chunk - forward the trailing CRLF
        writeString(destination, "\r\n");
        break;
      }

      // Forward chunk data from client to backend
      transferFixedFromClient(source, destination, chunkSize, arena);

      // Read and forward trailing CRLF after chunk data
      String trailing = readLineFromClient(source, buffer);
      if (trailing != null) {
        writeString(destination, trailing + "\r\n");
      }
    }
  }

  /**
   * Reads a line (terminated by CRLF) from the client connection.
   */
  private String readLineFromClient(ClientConnection source, ByteBuffer buffer) throws IOException {
    StringBuilder line = new StringBuilder();
    buffer.clear();
    buffer.limit(1);

    while (true) {
      int bytesRead = source.read(buffer);
      if (bytesRead == -1) {
        return line.isEmpty() ? null : line.toString();
      }
      if (bytesRead == 0) {
        continue;
      }

      buffer.flip();
      char c = (char) buffer.get();
      buffer.clear();
      buffer.limit(1);

      if (c == '\r') {
        // Read the \n
        bytesRead = source.read(buffer);
        if (bytesRead > 0) {
          buffer.flip();
          buffer.get(); // consume \n
          buffer.clear();
          buffer.limit(1);
        }
        return line.toString();
      }
      line.append(c);
    }
  }

  /**
   * Transfers data from backend to client through ClientConnection (encrypts if TLS).
   */
  private void transferToClient(SocketChannel source, ClientConnection destination)
      throws IOException {
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
        destination.write(buffer);
      }
    }
  }

  /**
   * Transfers a fixed number of bytes from client to backend.
   */
  private void transferFixedFromClient(ClientConnection source, SocketChannel destination,
                                       long count, Arena arena) throws IOException {
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

  private void writeString(SocketChannel destination, String str) throws IOException {
    ByteBuffer buf = ByteBuffer.wrap(str.getBytes(StandardCharsets.UTF_8));
    while (buf.hasRemaining()) {
      destination.write(buf);
    }
  }
}
