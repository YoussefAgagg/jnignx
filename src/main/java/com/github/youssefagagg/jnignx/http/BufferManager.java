package com.github.youssefagagg.jnignx.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Request/Response buffer manager for handling large requests and responses.
 *
 * <p>Provides buffering capabilities for:
 * <ul>
 *   <li><b>Request Buffering:</b> Buffer entire request before proxying (useful for authentication/validation)</li>
 *   <li><b>Response Buffering:</b> Buffer response for compression, transformation, or caching</li>
 *   <li><b>Streaming:</b> Stream data through with size limits</li>
 * </ul>
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Request body inspection (WAF, content validation)</li>
 *   <li>Response transformation (compression, minification)</li>
 *   <li>Caching responses based on content</li>
 *   <li>Request/response logging</li>
 *   <li>Rate limiting based on request size</li>
 * </ul>
 *
 * <p><b>Configuration:</b>
 * <pre>
 * BufferManager bufferManager = new BufferManager.Builder()
 *     .maxRequestSize(10 * 1024 * 1024)  // 10MB
 *     .maxResponseSize(50 * 1024 * 1024)  // 50MB
 *     .bufferSize(8192)
 *     .build();
 * </pre>
 */
public final class BufferManager {

  private final long maxRequestSize;
  private final long maxResponseSize;
  private final int bufferSize;
  private final boolean streamingEnabled;

  private BufferManager(Builder builder) {
    this.maxRequestSize = builder.maxRequestSize;
    this.maxResponseSize = builder.maxResponseSize;
    this.bufferSize = builder.bufferSize;
    this.streamingEnabled = builder.streamingEnabled;
  }

  /**
   * Buffers a complete request body.
   *
   * @param channel       the socket channel to read from
   * @param contentLength the Content-Length header value
   * @param arena         the memory arena for off-heap allocation
   * @return the buffered request body
   * @throws IOException              if I/O error occurs
   * @throws RequestTooLargeException if request exceeds max size
   */
  public BufferedRequest bufferRequest(SocketChannel channel, long contentLength, Arena arena)
      throws IOException {
    if (contentLength > maxRequestSize) {
      throw new RequestTooLargeException(
          "Request size " + contentLength + " exceeds maximum " + maxRequestSize);
    }

    if (contentLength == 0) {
      return new BufferedRequest(new byte[0], 0);
    }

    // Allocate off-heap buffer
    MemorySegment buffer = arena.allocate(contentLength);
    ByteBuffer byteBuffer = buffer.asByteBuffer();

    // Read entire request body
    long totalRead = 0;
    while (totalRead < contentLength) {
      int read = channel.read(byteBuffer);
      if (read == -1) {
        throw new IOException("Unexpected end of stream");
      }
      totalRead += read;
    }

    // Copy to byte array for processing
    byte[] body = new byte[(int) contentLength];
    byteBuffer.flip();
    byteBuffer.get(body);

    return new BufferedRequest(body, contentLength);
  }

  /**
   * Buffers a chunked request body.
   *
   * @param channel the socket channel to read from
   * @param arena   the memory arena
   * @return the buffered request body
   * @throws IOException              if I/O error occurs
   * @throws RequestTooLargeException if request exceeds max size
   */
  public BufferedRequest bufferChunkedRequest(SocketChannel channel, Arena arena)
      throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    MemorySegment chunkBuffer = arena.allocate(bufferSize);
    ByteBuffer byteBuffer = chunkBuffer.asByteBuffer();

    long totalSize = 0;

    while (true) {
      // Read chunk size line
      String sizeLine = readLine(channel, arena);
      if (sizeLine == null || sizeLine.isBlank()) {
        break;
      }

      // Parse chunk size (hex)
      int chunkSize;
      try {
        chunkSize = Integer.parseInt(sizeLine.trim(), 16);
      } catch (NumberFormatException e) {
        throw new IOException("Invalid chunk size: " + sizeLine);
      }

      if (chunkSize == 0) {
        // Last chunk
        readLine(channel, arena); // Read trailing \r\n
        break;
      }

      // Check size limit
      totalSize += chunkSize;
      if (totalSize > maxRequestSize) {
        throw new RequestTooLargeException(
            "Chunked request size exceeds maximum " + maxRequestSize);
      }

      // Read chunk data
      byteBuffer.clear();
      byteBuffer.limit(Math.min(chunkSize, bufferSize));

      int remaining = chunkSize;
      while (remaining > 0) {
        int toRead = Math.min(remaining, byteBuffer.remaining());
        byteBuffer.limit(byteBuffer.position() + toRead);

        int read = channel.read(byteBuffer);
        if (read == -1) {
          throw new IOException("Unexpected end of stream");
        }
        remaining -= read;

        if (byteBuffer.position() > 0) {
          byteBuffer.flip();
          byte[] chunk = new byte[byteBuffer.remaining()];
          byteBuffer.get(chunk);
          baos.write(chunk);
          byteBuffer.clear();
        }
      }

      // Read trailing \r\n after chunk
      readLine(channel, arena);
    }

    byte[] body = baos.toByteArray();
    return new BufferedRequest(body, body.length);
  }

  /**
   * Reads a line from a socket channel.
   */
  private String readLine(SocketChannel channel, Arena arena) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    MemorySegment buffer = arena.allocate(1);
    ByteBuffer byteBuffer = buffer.asByteBuffer();

    boolean foundCR = false;

    while (true) {
      byteBuffer.clear();
      int read = channel.read(byteBuffer);
      if (read == -1) {
        break;
      }

      byteBuffer.flip();
      byte b = byteBuffer.get();

      if (foundCR && b == '\n') {
        break;
      }

      if (b == '\r') {
        foundCR = true;
      } else {
        if (foundCR) {
          line.write('\r');
          foundCR = false;
        }
        line.write(b);
      }
    }

    return line.toString(StandardCharsets.UTF_8);
  }

  /**
   * Buffers a response for processing.
   *
   * @param data   the response data
   * @param length the data length
   * @return the buffered response
   * @throws ResponseTooLargeException if response exceeds max size
   */
  public BufferedResponse bufferResponse(byte[] data, int length) {
    if (length > maxResponseSize) {
      throw new ResponseTooLargeException(
          "Response size " + length + " exceeds maximum " + maxResponseSize);
    }

    return new BufferedResponse(data, length);
  }

  /**
   * Creates a streaming buffer for large data transfer.
   *
   * @return a new streaming buffer
   */
  public StreamingBuffer createStreamingBuffer() {
    return new StreamingBuffer(bufferSize, maxResponseSize);
  }

  // Builder pattern
  public static class Builder {
    private long maxRequestSize = 10 * 1024 * 1024; // 10MB default
    private long maxResponseSize = 50 * 1024 * 1024; // 50MB default
    private int bufferSize = 8192; // 8KB default
    private boolean streamingEnabled = true;

    public Builder maxRequestSize(long bytes) {
      if (bytes <= 0) {
        throw new IllegalArgumentException("Max request size must be positive");
      }
      this.maxRequestSize = bytes;
      return this;
    }

    public Builder maxResponseSize(long bytes) {
      if (bytes <= 0) {
        throw new IllegalArgumentException("Max response size must be positive");
      }
      this.maxResponseSize = bytes;
      return this;
    }

    public Builder bufferSize(int bytes) {
      if (bytes <= 0 || bytes > 1024 * 1024) {
        throw new IllegalArgumentException("Buffer size must be between 1 and 1MB");
      }
      this.bufferSize = bytes;
      return this;
    }

    public Builder streamingEnabled(boolean enabled) {
      this.streamingEnabled = enabled;
      return this;
    }

    public BufferManager build() {
      return new BufferManager(this);
    }
  }

  // Buffered request
  public record BufferedRequest(byte[] body, long size) {
    public String asString() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  // Buffered response
  public record BufferedResponse(byte[] body, int size) {
    public String asString() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  // Streaming buffer for large transfers
  public static class StreamingBuffer {
    private final List<byte[]> chunks = new ArrayList<>();
    private final int chunkSize;
    private final long maxSize;
    private long totalSize = 0;

    StreamingBuffer(int chunkSize, long maxSize) {
      this.chunkSize = chunkSize;
      this.maxSize = maxSize;
    }

    public void write(byte[] data, int length) {
      if (totalSize + length > maxSize) {
        throw new ResponseTooLargeException(
            "Streaming buffer size exceeds maximum " + maxSize);
      }

      byte[] chunk = new byte[length];
      System.arraycopy(data, 0, chunk, 0, length);
      chunks.add(chunk);
      totalSize += length;
    }

    public byte[] toByteArray() {
      byte[] result = new byte[(int) totalSize];
      int offset = 0;
      for (byte[] chunk : chunks) {
        System.arraycopy(chunk, 0, result, offset, chunk.length);
        offset += chunk.length;
      }
      return result;
    }

    public long size() {
      return totalSize;
    }

    public void clear() {
      chunks.clear();
      totalSize = 0;
    }
  }

  // Exceptions
  public static class RequestTooLargeException extends IOException {
    public RequestTooLargeException(String message) {
      super(message);
    }
  }

  public static class ResponseTooLargeException extends RuntimeException {
    public ResponseTooLargeException(String message) {
      super(message);
    }
  }
}
