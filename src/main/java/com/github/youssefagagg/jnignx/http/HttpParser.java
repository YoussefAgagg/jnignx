package com.github.youssefagagg.jnignx.http;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP/1.1 Parser using FFM API.
 */
public class HttpParser {

  private static final byte CR = 13;
  private static final byte LF = 10;

  /**
   * Parses an HTTP request from a MemorySegment.
   *
   * @param segment the memory segment containing the request data
   * @param length  the length of valid data in the segment
   * @return the parsed Request object, or null if incomplete
   */
  public static Request parse(MemorySegment segment, int length) {
    // Find end of headers (\r\n\r\n)
    long headerEnd = -1;
    for (long i = 0; i < length - 3; i++) {
      if (segment.get(ValueLayout.JAVA_BYTE, i) == CR &&
          segment.get(ValueLayout.JAVA_BYTE, i + 1) == LF &&
          segment.get(ValueLayout.JAVA_BYTE, i + 2) == CR &&
          segment.get(ValueLayout.JAVA_BYTE, i + 3) == LF) {
        headerEnd = i;
        break;
      }
    }

    if (headerEnd == -1) {
      return null; // Incomplete headers
    }

    // Convert headers part to string
    byte[] headerBytes = new byte[(int) headerEnd];
    MemorySegment.copy(segment, 0, MemorySegment.ofArray(headerBytes), 0, headerEnd);
    String headerString = new String(headerBytes, StandardCharsets.UTF_8);

    String[] lines = headerString.split("\r\n");
    if (lines.length == 0) {
      return null;
    }

    // Parse Request Line
    String requestLine = lines[0];
    String[] parts = requestLine.split(" ");
    if (parts.length < 3) {
      return null; // Invalid request line
    }
    String method = parts[0];
    String path = parts[1];
    String version = parts[2];

    // Parse Headers
    Map<String, String> headers = new HashMap<>();
    for (int i = 1; i < lines.length; i++) {
      String line = lines[i];
      int colonIndex = line.indexOf(':');
      if (colonIndex > 0) {
        String key = line.substring(0, colonIndex).trim();
        String value = line.substring(colonIndex + 1).trim();
        headers.put(key, value);
      }
    }

    long bodyLength = 0;
    if (headers.containsKey("Content-Length")) {
      try {
        bodyLength = Long.parseLong(headers.get("Content-Length"));
      } catch (NumberFormatException ignored) {
      }
    }

    boolean isChunked = false;
    if (headers.containsKey("Transfer-Encoding")) {
      String encoding = headers.get("Transfer-Encoding");
      if (encoding.equalsIgnoreCase("chunked")) {
        isChunked = true;
      }
    }

    int headerLength = (int) headerEnd + 4;

    return new Request(method, path, version, headers, bodyLength, isChunked, headerLength);
  }
}
