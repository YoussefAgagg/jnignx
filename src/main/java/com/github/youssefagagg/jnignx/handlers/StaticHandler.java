package com.github.youssefagagg.jnignx.handlers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Handles static file serving using zero-copy transfer.
 */
public class StaticHandler {

  private static final DateTimeFormatter HTTP_DATE_FORMATTER = DateTimeFormatter
      .ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH)
      .withZone(ZoneId.of("GMT"));

  /**
   * Serves a static file.
   *
   * @param clientChannel the client socket channel
   * @param rootPath      the root directory path (e.g., "file:///var/www/html")
   * @param requestPath   the requested path (e.g., "/index.html")
   * @throws IOException if an I/O error occurs
   */
  public void handle(SocketChannel clientChannel, String rootPath, String requestPath)
      throws IOException {
    // Basic security check
    if (requestPath.contains("..")) {
      sendError(clientChannel, 403, "Forbidden");
      return;
    }

    // Remove scheme if present
    String rawRoot = rootPath.startsWith("file://") ? rootPath.substring(7) : rootPath;
    Path root = Path.of(rawRoot);

    // Remove leading slash to resolve correctly against root
    String relativePath = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
    Path file = root.resolve(relativePath).normalize();

    // Ensure the file is actually under the root (prevent traversal attacks that bypass simple check)
    if (!file.startsWith(root)) {
      sendError(clientChannel, 403, "Forbidden");
      return;
    }

    if (!Files.exists(file)) {
      handle404(clientChannel);
      return;
    }

    if (Files.isDirectory(file)) {
      file = file.resolve("index.html");
      if (!Files.exists(file)) {
        handle404(clientChannel);
        return;
      }
    }

    long length = Files.size(file);
    String contentType = probeContentType(file);
    if (contentType == null) {
      contentType = "application/octet-stream";
    }

    long lastModifiedTime = Files.getLastModifiedTime(file).toMillis();
    String lastModified =
        HTTP_DATE_FORMATTER.format(java.time.Instant.ofEpochMilli(lastModifiedTime));
    String etag = "\"" + Long.toHexString(lastModifiedTime) + "-" + Long.toHexString(length) + "\"";

    String header = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: " + contentType + "\r\n" +
        "Content-Length: " + length + "\r\n" +
        "Last-Modified: " + lastModified + "\r\n" +
        "ETag: " + etag + "\r\n" +
        "Cache-Control: public, max-age=3600\r\n" +
        "\r\n";

    clientChannel.write(ByteBuffer.wrap(header.getBytes()));

    // Zero-copy transfer
    try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
      long transferred = 0;
      while (transferred < length) {
        long count = fileChannel.transferTo(transferred, length - transferred, clientChannel);
          if (count <= 0) {
              break; // Should not happen unless channel closed or file shrunk
          }
        transferred += count;
      }
    }
  }

  public void handle404(SocketChannel clientChannel) throws IOException {
    sendError(clientChannel, 404, "Not Found");
  }

  private void sendError(SocketChannel clientChannel, int code, String message) throws IOException {
    String response = "HTTP/1.1 " + code + " " + message + "\r\nContent-Length: 0\r\n\r\n";
    clientChannel.write(ByteBuffer.wrap(response.getBytes()));
  }

  private String probeContentType(Path path) {
    try {
      return Files.probeContentType(path);
    } catch (IOException e) {
      return "application/octet-stream";
    }
  }
}
