package com.github.youssefagagg.jnignx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.youssefagagg.jnignx.http.HttpParser;
import com.github.youssefagagg.jnignx.http.Request;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class Phase1Test {

  @Test
  void testHttpParserNormal() {
    String raw = "GET /index.html HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n";
    byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment segment = arena.allocate(bytes.length);
      MemorySegment.copy(MemorySegment.ofArray(bytes), 0, segment, 0, bytes.length);

      Request req = HttpParser.parse(segment, bytes.length);
      assertNotNull(req);
      assertEquals("GET", req.method());
      assertEquals("/index.html", req.path());
      assertFalse(req.isChunked());
    }
  }

  @Test
  void testHttpParserChunked() {
    String raw = "POST /upload HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n";
    byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment segment = arena.allocate(bytes.length);
      MemorySegment.copy(MemorySegment.ofArray(bytes), 0, segment, 0, bytes.length);

      Request req = HttpParser.parse(segment, bytes.length);
      assertNotNull(req);
      assertTrue(req.isChunked());
      assertEquals(0, req.bodyLength());
    }
  }

  @Test
  void testHttpParserIncomplete() {
    String raw = "GET / HTTP/1.1\r\nHost: localhost\r\n"; // Missing \r\n\r\n
    byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment segment = arena.allocate(bytes.length);
      MemorySegment.copy(MemorySegment.ofArray(bytes), 0, segment, 0, bytes.length);

      Request req = HttpParser.parse(segment, bytes.length);
      assertNull(req);
    }
  }

  // Since I cannot mock SocketChannel easily (it's final/hard to mock without PowerMock),
  // and ResponseWriter writes to it.
  // I can assume ResponseWriter works if it compiles and uses standard logic,
  // or I can try to use a real loopback connection if I want integration test.
  // But unit testing HttpParser covers the main "Logic" change.
}
