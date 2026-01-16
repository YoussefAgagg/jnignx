package com.github.youssefagagg.jnignx.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Compression utilities for HTTP responses.
 *
 * <p>Supports multiple compression algorithms:
 * <ul>
 *   <li>gzip - widely supported, good compression ratio</li>
 *   <li>deflate - standard compression</li>
 *   <li>br (Brotli) - best compression, requires external library or JDK 21+</li>
 * </ul>
 *
 * <p>Automatically selects the best compression based on client Accept-Encoding header.
 */
public final class CompressionUtil {

  private static final int COMPRESSION_LEVEL = 6; // Balanced compression
  private static final int MIN_COMPRESS_SIZE = 1024; // Don't compress < 1KB

  /**
   * Determines the best compression algorithm based on Accept-Encoding header.
   *
   * @param acceptEncoding the Accept-Encoding header value
   * @return the best supported algorithm
   */
  public static Algorithm selectAlgorithm(String acceptEncoding) {
    if (acceptEncoding == null || acceptEncoding.isEmpty()) {
      return Algorithm.NONE;
    }

    String lower = acceptEncoding.toLowerCase();

    // Prefer Brotli if available (best compression)
    if (lower.contains("br")) {
      return Algorithm.BROTLI;
    }

    // Then gzip (widely supported)
    if (lower.contains("gzip")) {
      return Algorithm.GZIP;
    }

    // Finally deflate
    if (lower.contains("deflate")) {
      return Algorithm.DEFLATE;
    }

    return Algorithm.NONE;
  }

  /**
   * Checks if content should be compressed based on size and type.
   *
   * @param contentType the content type
   * @param size        the content size in bytes
   * @return true if content should be compressed
   */
  public static boolean shouldCompress(String contentType, int size) {
    if (size < MIN_COMPRESS_SIZE) {
      return false;
    }

    if (contentType == null) {
      return false;
    }

    String lowerType = contentType.toLowerCase();

    // Compress text-based content
    return lowerType.startsWith("text/") ||
        lowerType.contains("javascript") ||
        lowerType.contains("json") ||
        lowerType.contains("xml") ||
        lowerType.contains("css") ||
        lowerType.contains("html") ||
        lowerType.contains("svg");
  }

  /**
   * Compresses data using the specified algorithm.
   *
   * @param data      the data to compress
   * @param algorithm the compression algorithm
   * @return the compressed data
   * @throws IOException if compression fails
   */
  public static byte[] compress(byte[] data, Algorithm algorithm) throws IOException {
    return switch (algorithm) {
      case GZIP -> compressGzip(data);
      case DEFLATE -> compressDeflate(data);
      case BROTLI -> compressBrotli(data);
      case NONE -> data;
    };
  }

  /**
   * Compresses data using the specified algorithm (ByteBuffer version).
   */
  public static ByteBuffer compress(ByteBuffer data, Algorithm algorithm) throws IOException {
    byte[] bytes = new byte[data.remaining()];
    data.get(bytes);
    byte[] compressed = compress(bytes, algorithm);
    return ByteBuffer.wrap(compressed);
  }

  /**
   * Compresses data using gzip.
   */
  private static byte[] compressGzip(byte[] data) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
    try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos) {
      {
        this.def.setLevel(COMPRESSION_LEVEL);
      }
    }) {
      gzipOut.write(data);
    }
    return baos.toByteArray();
  }

  /**
   * Compresses data using deflate.
   */
  private static byte[] compressDeflate(byte[] data) throws IOException {
    Deflater deflater = new Deflater(COMPRESSION_LEVEL);
    deflater.setInput(data);
    deflater.finish();

    ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
    byte[] buffer = new byte[8192];

    while (!deflater.finished()) {
      int count = deflater.deflate(buffer);
      baos.write(buffer, 0, count);
    }

    deflater.end();
    return baos.toByteArray();
  }

  /**
   * Compresses data using Brotli.
   * <p>
   * Note: This is a placeholder implementation. For production use:
   * 1. Use Java 21+ with built-in Brotli support via java.util.zip
   * 2. Or add the Brotli4j library dependency
   * 3. Or use native Brotli via JNI/FFM
   */
  private static byte[] compressBrotli(byte[] data) throws IOException {
    // Fallback to gzip if Brotli is not available
    // In production, you would use:
    // - com.aayushatharva.brotli4j.encoder.Encoder (Brotli4j library)
    // - Or wait for JDK 21+ built-in support

    try {
      // Try to use Brotli4j if available
      Class<?> encoderClass = Class.forName("com.aayushatharva.brotli4j.encoder.Encoder");
      var compressMethod = encoderClass.getMethod("compress", byte[].class);
      return (byte[]) compressMethod.invoke(null, (Object) data);
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      // Brotli not available, fallback to gzip
      System.err.println("[Compression] Brotli not available, falling back to gzip");
      return compressGzip(data);
    } catch (Exception e) {
      throw new IOException("Brotli compression failed", e);
    }
  }

  /**
   * Compresses response data with metadata.
   */
  public static CompressedResponse compressResponse(byte[] data, String acceptEncoding,
                                                    String contentType)
      throws IOException {
    Algorithm algorithm = selectAlgorithm(acceptEncoding);

    if (algorithm == Algorithm.NONE || !shouldCompress(contentType, data.length)) {
      return new CompressedResponse(data, Algorithm.NONE, data.length);
    }

    byte[] compressed = compress(data, algorithm);

    // Only use compression if it actually reduces size
    if (compressed.length < data.length) {
      return new CompressedResponse(compressed, algorithm, data.length);
    } else {
      return new CompressedResponse(data, Algorithm.NONE, data.length);
    }
  }

  /**
   * Gets the compression ratio as a percentage (0-100).
   */
  public static int getCompressionPercentage(int original, int compressed) {
    if (original == 0) {
      return 0;
    }
    return (int) ((1.0 - (double) compressed / original) * 100);
  }

  /**
   * Compression algorithms.
   */
  public enum Algorithm {
    NONE("identity"),
    GZIP("gzip"),
    DEFLATE("deflate"),
    BROTLI("br");

    private final String encoding;

    Algorithm(String encoding) {
      this.encoding = encoding;
    }

    public String getEncoding() {
      return encoding;
    }
  }

  /**
   * Compressed response wrapper with metadata.
   */
  public static class CompressedResponse {
    private final byte[] data;
    private final Algorithm algorithm;
    private final int originalSize;
    private final int compressedSize;

    public CompressedResponse(byte[] data, Algorithm algorithm, int originalSize) {
      this.data = data;
      this.algorithm = algorithm;
      this.originalSize = originalSize;
      this.compressedSize = data.length;
    }

    public byte[] data() {
      return data;
    }

    public Algorithm algorithm() {
      return algorithm;
    }

    public int originalSize() {
      return originalSize;
    }

    public int compressedSize() {
      return compressedSize;
    }

    public double compressionRatio() {
      return originalSize > 0 ? (double) compressedSize / originalSize : 1.0;
    }

    public int savedBytes() {
      return originalSize - compressedSize;
    }
  }
}
