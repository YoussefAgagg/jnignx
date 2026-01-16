package com.github.youssefagagg.jnignx.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class CompressionUtilTest {

  @Test
  void testGzipCompressionAndDecompression() throws IOException {
    String original = "Hello, World! This is a test string for compression.".repeat(20);
    byte[] originalBytes = original.getBytes(StandardCharsets.UTF_8);

    // Compress
    byte[] compressed = CompressionUtil.compress(originalBytes, CompressionUtil.Algorithm.GZIP);
    assertNotNull(compressed);
    assertTrue(compressed.length < originalBytes.length,
               "Compressed size should be smaller for compressible data");

    // Decompress
    byte[] decompressed = decompress(compressed);
    String result = new String(decompressed, StandardCharsets.UTF_8);

    assertEquals(original, result, "Decompressed content should match original");
  }

  @Test
  void testGzipLargeData() throws IOException {
    // Create large repetitive data (very compressible)
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      sb.append("This is line ").append(i).append(" of repeated data.\n");
    }
    String original = sb.toString();
    byte[] originalBytes = original.getBytes(StandardCharsets.UTF_8);

    byte[] compressed = CompressionUtil.compress(originalBytes, CompressionUtil.Algorithm.GZIP);

    // Large repetitive data should compress well
    assertTrue(compressed.length < originalBytes.length / 10,
               "Large repetitive data should compress to less than 10% of original");

    byte[] decompressed = decompress(compressed);
    assertEquals(original, new String(decompressed, StandardCharsets.UTF_8));
  }

  @Test
  void testGzipEmptyData() throws IOException {
    byte[] empty = new byte[0];
    byte[] compressed = CompressionUtil.compress(empty, CompressionUtil.Algorithm.GZIP);

    assertNotNull(compressed);
    assertTrue(compressed.length > 0, "GZIP has overhead even for empty data");

    byte[] decompressed = decompress(compressed);
    assertEquals(0, decompressed.length);
  }

  @Test
  void testGzipBinaryData() throws IOException {
    // Random-ish binary data (less compressible)
    byte[] binary = new byte[1000];
    for (int i = 0; i < binary.length; i++) {
      binary[i] = (byte) (i % 256);
    }

    byte[] compressed = CompressionUtil.compress(binary, CompressionUtil.Algorithm.GZIP);
    byte[] decompressed = decompress(compressed);

    assertArrayEquals(binary, decompressed);
  }

  @Test
  void testShouldCompressContentType() {
    int largeSize = 2000; // Above MIN_COMPRESS_SIZE

    // Text types should be compressed
    assertTrue(CompressionUtil.shouldCompress("text/html", largeSize));
    assertTrue(CompressionUtil.shouldCompress("text/plain", largeSize));
    assertTrue(CompressionUtil.shouldCompress("text/css", largeSize));
    assertTrue(CompressionUtil.shouldCompress("application/json", largeSize));
    assertTrue(CompressionUtil.shouldCompress("application/javascript", largeSize));
    assertTrue(CompressionUtil.shouldCompress("application/xml", largeSize));

    // Binary types should not be compressed
    assertFalse(CompressionUtil.shouldCompress("image/jpeg", largeSize));
    assertFalse(CompressionUtil.shouldCompress("image/png", largeSize));
    assertFalse(CompressionUtil.shouldCompress("video/mp4", largeSize));
    assertFalse(CompressionUtil.shouldCompress("application/zip", largeSize));
    assertFalse(CompressionUtil.shouldCompress("application/gzip", largeSize));
  }

  @Test
  void testShouldCompressCaseInsensitive() {
    int largeSize = 2000;
    assertTrue(CompressionUtil.shouldCompress("TEXT/HTML", largeSize));
    assertTrue(CompressionUtil.shouldCompress("Text/Html", largeSize));
    assertFalse(CompressionUtil.shouldCompress("IMAGE/JPEG", largeSize));
  }

  @Test
  void testShouldCompressWithCharset() {
    int largeSize = 2000;
    assertTrue(CompressionUtil.shouldCompress("text/html; charset=utf-8", largeSize));
    assertTrue(CompressionUtil.shouldCompress("application/json; charset=UTF-8", largeSize));
    assertFalse(CompressionUtil.shouldCompress("image/png; charset=binary", largeSize));
  }

  @Test
  void testSelectGzipAlgorithm() {
    assertEquals(CompressionUtil.Algorithm.GZIP, CompressionUtil.selectAlgorithm("gzip"));
    assertEquals(CompressionUtil.Algorithm.GZIP, CompressionUtil.selectAlgorithm("gzip, deflate"));
    assertEquals(CompressionUtil.Algorithm.BROTLI,
                 CompressionUtil.selectAlgorithm("gzip, deflate, br"));
    assertEquals(CompressionUtil.Algorithm.GZIP, CompressionUtil.selectAlgorithm("deflate, gzip"));
    assertEquals(CompressionUtil.Algorithm.GZIP, CompressionUtil.selectAlgorithm("gzip;q=1.0"));
    assertEquals(CompressionUtil.Algorithm.GZIP,
                 CompressionUtil.selectAlgorithm("gzip;q=0.8, deflate;q=0.5"));

    assertEquals(CompressionUtil.Algorithm.DEFLATE, CompressionUtil.selectAlgorithm("deflate"));
    assertEquals(CompressionUtil.Algorithm.BROTLI, CompressionUtil.selectAlgorithm("br"));
    assertEquals(CompressionUtil.Algorithm.NONE, CompressionUtil.selectAlgorithm(""));
    assertEquals(CompressionUtil.Algorithm.NONE, CompressionUtil.selectAlgorithm(null));
  }

  @Test
  void testSelectAlgorithmCaseInsensitive() {
    assertEquals(CompressionUtil.Algorithm.GZIP, CompressionUtil.selectAlgorithm("GZIP"));
    assertEquals(CompressionUtil.Algorithm.GZIP, CompressionUtil.selectAlgorithm("GZip"));
    assertEquals(CompressionUtil.Algorithm.GZIP, CompressionUtil.selectAlgorithm("Gzip, Deflate"));
  }

  @Test
  void testMinCompressionSize() {
    // Small data should not be worth compressing
    assertFalse(CompressionUtil.shouldCompress("text/html", 100));
    assertFalse(CompressionUtil.shouldCompress("text/html", 500));

    // Larger data should be compressed
    assertTrue(CompressionUtil.shouldCompress("text/html", 2000));
    assertTrue(CompressionUtil.shouldCompress("text/html", 10000));
  }

  @Test
  void testNoneAlgorithm() throws IOException {
    byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
    byte[] result = CompressionUtil.compress(data, CompressionUtil.Algorithm.NONE);
    assertArrayEquals(data, result, "NONE algorithm should return original data");
  }

  @Test
  void testDeflateCompression() throws IOException {
    String original = "Test data for deflate compression algorithm";
    byte[] originalBytes = original.getBytes(StandardCharsets.UTF_8);

    byte[] compressed = CompressionUtil.compress(originalBytes, CompressionUtil.Algorithm.DEFLATE);
    assertNotNull(compressed);
    assertTrue(compressed.length > 0);
  }

  @Test
  void testCompressResponse() throws IOException {
    String data = "This is test data for HTTP response compression. ".repeat(50);
    byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);

    CompressionUtil.CompressedResponse response = CompressionUtil.compressResponse(
        dataBytes, "gzip, deflate", "text/html");

    assertNotNull(response);
    assertEquals(CompressionUtil.Algorithm.GZIP, response.algorithm());
    assertTrue(response.compressedSize() < response.originalSize());
  }

  // Helper method to decompress gzip data
  private byte[] decompress(byte[] compressed) throws IOException {
    ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
    try (GZIPInputStream gzipIn = new GZIPInputStream(bais);
         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[1024];
      int len;
      while ((len = gzipIn.read(buffer)) > 0) {
        baos.write(buffer, 0, len);
      }
      return baos.toByteArray();
    }
  }
}
