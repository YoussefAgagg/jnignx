package com.github.youssefagagg.jnignx.http;

import java.util.Collections;
import java.util.Map;

/**
 * Represents an HTTP request.
 *
 * @param method       HTTP method (GET, POST, etc.)
 * @param path         Request path
 * @param version      HTTP version (HTTP/1.1)
 * @param headers      Map of HTTP headers
 * @param bodyLength   Content-Length of the body (0 if missing or chunked)
 * @param isChunked    true if Transfer-Encoding is chunked
 * @param headerLength Length of the header section in bytes (including \r\n\r\n)
 */
public record Request(String method, String path, String version, Map<String, String> headers,
                      long bodyLength, boolean isChunked, int headerLength) {
  public Request {
    headers = Collections.unmodifiableMap(headers);
  }
}
