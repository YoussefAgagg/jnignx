package com.github.youssefagagg.jnignx.http;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Helper to write responses efficiently using FFM.
 */
public class ResponseWriter {

  /**
   * Writes a Response to the channel.
   *
   * @param channel  the socket channel
   * @param response the response object
   * @param arena    the arena to use for allocation
   * @throws IOException if an I/O error occurs
   */
  public static void write(SocketChannel channel, Response response, Arena arena)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("HTTP/1.1 ").append(response.getStatusCode()).append(" ")
      .append(response.getStatusMessage()).append("\r\n");

    for (Map.Entry<String, String> entry : response.getHeaders().entrySet()) {
      sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
    }
    sb.append("\r\n");

    byte[] headerBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
    byte[] body = response.getBody();

    long totalLength = headerBytes.length + (body != null ? body.length : 0);
    MemorySegment segment = arena.allocate(totalLength);

    MemorySegment.copy(MemorySegment.ofArray(headerBytes), 0, segment, 0, headerBytes.length);
    if (body != null) {
      MemorySegment.copy(MemorySegment.ofArray(body), 0, segment, headerBytes.length, body.length);
    }

    channel.write(segment.asByteBuffer());
  }
}
