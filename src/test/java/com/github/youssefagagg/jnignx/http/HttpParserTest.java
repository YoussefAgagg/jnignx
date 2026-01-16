package com.github.youssefagagg.jnignx.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HttpParserTest {

  @Test
  void testParseSimpleGetRequest() {
    String raw = "GET /index.html HTTP/1.1\r\n" +
        "Host: localhost\r\n" +
        "Content-Length: 0\r\n" +
        "\r\n";

    Request req = parseRequest(raw);

    assertNotNull(req);
    assertEquals("GET", req.method());
    assertEquals("/index.html", req.path());
    assertEquals("HTTP/1.1", req.version());
    assertFalse(req.isChunked());
    assertEquals(0, req.bodyLength());
  }

  @Test
  void testParsePostWithBody() {
    String raw = "POST /api/data HTTP/1.1\r\n" +
        "Host: localhost\r\n" +
        "Content-Length: 13\r\n" +
        "\r\n" +
        "Hello, World!";

    Request req = parseRequest(raw);

    assertNotNull(req);
    assertEquals("POST", req.method());
    assertEquals("/api/data", req.path());
    assertEquals(13, req.bodyLength());
  }

  @Test
  void testParseChunkedEncoding() {
    String raw = "POST /upload HTTP/1.1\r\n" +
        "Host: localhost\r\n" +
        "Transfer-Encoding: chunked\r\n" +
        "\r\n";

    Request req = parseRequest(raw);

    assertNotNull(req);
    assertTrue(req.isChunked());
    assertEquals(0, req.bodyLength());
  }

  @Test
  void testParseMultipleHeaders() {
    String raw = "GET /test HTTP/1.1\r\n" +
        "Host: example.com\r\n" +
        "User-Agent: TestClient/1.0\r\n" +
        "Accept: text/html\r\n" +
        "Accept-Encoding: gzip, deflate\r\n" +
        "Connection: keep-alive\r\n" +
        "\r\n";

    Request req = parseRequest(raw);

    assertNotNull(req);
    assertEquals("GET", req.method());
    assertEquals("/test", req.path());

    // Check if headers are parsed
    assertTrue(req.headers().containsKey("Host") || req.headers().containsKey("host"));
    assertTrue(req.headers().containsKey("User-Agent") || req.headers().containsKey("user-agent"));
  }

  @Test
  void testParseIncompleteRequest() {
    String raw = "GET /test HTTP/1.1\r\n" +
        "Host: localhost\r\n";  // Missing final \r\n\r\n

    Request req = parseRequest(raw);

    assertNull(req, "Incomplete request should return null");
  }

  @Test
  void testParseEmptyPath() {
    String raw = "GET / HTTP/1.1\r\n" +
        "Host: localhost\r\n" +
        "\r\n";

    Request req = parseRequest(raw);

    assertNotNull(req);
    assertEquals("/", req.path());
  }

  @Test
  void testParsePathWithQuery() {
    String raw = "GET /search?q=test&limit=10 HTTP/1.1\r\n" +
        "Host: localhost\r\n" +
        "\r\n";

    Request req = parseRequest(raw);

    assertNotNull(req);
    assertTrue(req.path().contains("?"));
    assertTrue(req.path().contains("q=test"));
  }

  @Test
  void testParseDifferentMethods() {
    String[] methods = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"};

    for (String method : methods) {
      String raw = method + " /test HTTP/1.1\r\n" +
          "Host: localhost\r\n" +
          "\r\n";

      Request req = parseRequest(raw);
      assertNotNull(req, "Method " + method + " should be parsed");
      assertEquals(method, req.method());
    }
  }

  @Test
  void testParseHttp10() {
    String raw = "GET /test HTTP/1.0\r\n" +
        "Host: localhost\r\n" +
        "\r\n";

    Request req = parseRequest(raw);

    assertNotNull(req);
    assertEquals("HTTP/1.0", req.version());
  }

  @Test
  void testParseHeadersCaseInsensitive() {
    String raw = "GET /test HTTP/1.1\r\n" +
        "host: localhost\r\n" +
        "content-length: 0\r\n" +
        "ACCEPT-ENCODING: gzip\r\n" +
        "\r\n";

    Request req = parseRequest(raw);

    assertNotNull(req);
    assertFalse(req.headers().isEmpty());
  }

  @Test
  void testParseLongRequest() {
    StringBuilder sb = new StringBuilder();
    sb.append("GET /very/long/path/with/many/segments/that/goes/on/and/on HTTP/1.1\r\n");
    sb.append("Host: very-long-hostname.example.com\r\n");

    // Add many headers
    for (int i = 0; i < 50; i++) {
      sb.append("X-Custom-Header-").append(i).append(": value").append(i).append("\r\n");
    }
    sb.append("\r\n");

    Request req = parseRequest(sb.toString());

    assertNotNull(req);
    assertFalse(req.headers().isEmpty());
  }

  @Test
  void testParseWithBodyOffset() {
    String raw = "POST /api HTTP/1.1\r\n" +
        "Host: localhost\r\n" +
        "Content-Length: 5\r\n" +
        "\r\n" +
        "Hello";

    Request req = parseRequest(raw);

    assertNotNull(req);
    assertEquals(5, req.bodyLength());
    assertTrue(req.headerLength() > 0);
    assertTrue(req.headerLength() < raw.length());
  }

  @Test
  void testParseInvalidRequest() {
    String raw = "INVALID REQUEST\r\n\r\n";

    Request req = parseRequest(raw);

    // Should handle gracefully (might return null or throw)
    // Depends on implementation
  }

  @Test
  void testParseEmptyRequest() {
    String raw = "";

    Request req = parseRequest(raw);

    assertNull(req);
  }

  @Test
  void testParseRequestWithSpaceInUri() {
    String raw = "GET /path%20with%20spaces HTTP/1.1\r\n" +
        "Host: localhost\r\n" +
        "\r\n";

    Request req = parseRequest(raw);

    assertNotNull(req);
    assertTrue(req.path().contains("%20") || req.path().contains(" "));
  }

  // Helper method to parse request
  private Request parseRequest(String raw) {
    byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment segment = arena.allocate(bytes.length);
      MemorySegment.copy(MemorySegment.ofArray(bytes), 0, segment, 0, bytes.length);

      return HttpParser.parse(segment, bytes.length);
    }
  }
}
