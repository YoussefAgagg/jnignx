package com.github.youssefagagg.jnignx.handlers;

import com.github.youssefagagg.jnignx.http.Request;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Handles static file serving using zero-copy transfer, directory listing, and Gzip compression.
 */
public class StaticHandler {

  private static final DateTimeFormatter HTTP_DATE_FORMATTER = DateTimeFormatter
      .ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH)
      .withZone(ZoneId.of("GMT"));

  private static final Map<String, String> MIME_TYPES = new HashMap<>();
  private static final Set<String> COMPRESSIBLE_TYPES = Set.of(
      "text/html", "text/plain", "text/css", "text/javascript", "application/javascript",
      "application/json", "application/xml", "image/svg+xml"
  );

  static {
    MIME_TYPES.put("html", "text/html");
    MIME_TYPES.put("css", "text/css");
    MIME_TYPES.put("js", "application/javascript");
    MIME_TYPES.put("json", "application/json");
    MIME_TYPES.put("png", "image/png");
    MIME_TYPES.put("jpg", "image/jpeg");
    MIME_TYPES.put("jpeg", "image/jpeg");
    MIME_TYPES.put("gif", "image/gif");
    MIME_TYPES.put("svg", "image/svg+xml");
    MIME_TYPES.put("txt", "text/plain");
    MIME_TYPES.put("xml", "application/xml");
  }

  /**
   * Serves a static file or directory.
   *
   * @param clientChannel the client socket channel
   * @param rootPath      the root directory path (e.g., "file:///var/www/html")
   * @param request       the parsed request
   * @throws IOException if an I/O error occurs
   */
  public void handle(SocketChannel clientChannel, String rootPath, Request request)
      throws IOException {
    String requestPath = request.path();

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

    // Ensure the file is actually under the root
    if (!file.startsWith(root)) {
      sendError(clientChannel, 403, "Forbidden");
      return;
    }

    if (!Files.exists(file)) {
      handle404(clientChannel);
      return;
    }

    if (Files.isDirectory(file)) {
      Path indexFile = file.resolve("index.html");
      if (Files.exists(indexFile)) {
        serveFile(clientChannel, indexFile, request);
      } else {
        serveDirectoryListing(clientChannel, file, requestPath);
      }
    } else {
      serveFile(clientChannel, file, request);
    }
  }

  private void serveDirectoryListing(SocketChannel clientChannel, Path dir, String requestPath)
      throws IOException {
    StringBuilder html = new StringBuilder();
    html.append("<!DOCTYPE html><html><head><title>Index of ").append(requestPath)
        .append("</title></head><body>");
    html.append("<h1>Index of ").append(requestPath).append("</h1><hr><ul>");

    if (!requestPath.equals("/")) {
      html.append("<li><a href=\"..\">..</a></li>");
    }

    try (Stream<Path> stream = Files.list(dir)) {
      stream.sorted().forEach(path -> {
        String name = path.getFileName().toString();
        if (Files.isDirectory(path)) {
          name += "/";
        }
        html.append("<li><a href=\"").append(name).append("\">").append(name).append("</a></li>");
      });
    }

    html.append("</ul><hr></body></html>");
    byte[] data = html.toString().getBytes(StandardCharsets.UTF_8);

    String header = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/html\r\n" +
        "Content-Length: " + data.length + "\r\n" +
        "\r\n";

    clientChannel.write(ByteBuffer.wrap(header.getBytes(StandardCharsets.UTF_8)));
    clientChannel.write(ByteBuffer.wrap(data));
  }

  private void serveFile(SocketChannel clientChannel, Path file, Request request)
      throws IOException {
    long length = Files.size(file);
    String contentType = determineContentType(file);
    long lastModifiedTime = Files.getLastModifiedTime(file).toMillis();
    String lastModified =
        HTTP_DATE_FORMATTER.format(java.time.Instant.ofEpochMilli(lastModifiedTime));
    String etag = "\"" + Long.toHexString(lastModifiedTime) + "-" + Long.toHexString(length) + "\"";

    // Check Accept-Encoding
    String acceptEncoding = request.headers().getOrDefault("Accept-Encoding", "");
    boolean useGzip = acceptEncoding.contains("gzip") && COMPRESSIBLE_TYPES.contains(contentType);

    if (useGzip) {
      serveFileGzip(clientChannel, file, contentType, lastModified, etag);
    } else {
      serveFileZeroCopy(clientChannel, file, length, contentType, lastModified, etag);
    }
  }

  private void serveFileZeroCopy(SocketChannel clientChannel, Path file, long length,
                                 String contentType, String lastModified, String etag)
      throws IOException {
    String header = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: " + contentType + "\r\n" +
        "Content-Length: " + length + "\r\n" +
        "Last-Modified: " + lastModified + "\r\n" +
        "ETag: " + etag + "\r\n" +
        "Cache-Control: public, max-age=3600\r\n" +
        "Vary: Accept-Encoding\r\n" +
        "\r\n";

    clientChannel.write(ByteBuffer.wrap(header.getBytes(StandardCharsets.UTF_8)));

    try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
      long transferred = 0;
      while (transferred < length) {
        long count = fileChannel.transferTo(transferred, length - transferred, clientChannel);
        if (count <= 0) {
          break;
        }
        transferred += count;
      }
    }
  }

  private void serveFileGzip(SocketChannel clientChannel, Path file, String contentType,
                             String lastModified, String etag) throws IOException {
    // We use Chunked Transfer Encoding for Gzip to avoid buffering the whole compressed content
    String header = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: " + contentType + "\r\n" +
        "Transfer-Encoding: chunked\r\n" +
        "Content-Encoding: gzip\r\n" +
        "Last-Modified: " + lastModified + "\r\n" +
        "ETag: " + etag + "\r\n" +
        "Cache-Control: public, max-age=3600\r\n" +
        "Vary: Accept-Encoding\r\n" +
        "\r\n";

    clientChannel.write(ByteBuffer.wrap(header.getBytes(StandardCharsets.UTF_8)));

    // Stream file -> Gzip -> Chunked -> Socket
    try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ);
         ChunkedOutputStream chunkedOut = new ChunkedOutputStream(clientChannel);
         GZIPOutputStream gzipOut = new GZIPOutputStream(chunkedOut, 8192)) {

      ByteBuffer buffer = ByteBuffer.allocate(8192);
      while (fileChannel.read(buffer) > 0) {
        buffer.flip();
        gzipOut.write(buffer.array(), 0, buffer.limit());
        buffer.clear();
      }
      gzipOut.finish(); // Finish gzip stream
    }
  }

  public void handle404(SocketChannel clientChannel) throws IOException {
    sendError(clientChannel, 404, "Not Found");
  }

  private void sendError(SocketChannel clientChannel, int code, String message) throws IOException {
    String response = "HTTP/1.1 " + code + " " + message + "\r\nContent-Length: 0\r\n\r\n";
    clientChannel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
  }

  private String determineContentType(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    if (dot != -1) {
      String ext = name.substring(dot + 1).toLowerCase();
      return MIME_TYPES.getOrDefault(ext, "application/octet-stream");
    }
    return "application/octet-stream";
  }

  /**
   * A simple OutputStream that writes data in HTTP chunks.
   */
  private static class ChunkedOutputStream extends OutputStream {
    private final SocketChannel channel;

    ChunkedOutputStream(SocketChannel channel) {
      this.channel = channel;
    }

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      if (len == 0) {
        return;
      }
      // Write chunk size in hex
      String sizeLine = Integer.toHexString(len) + "\r\n";
      channel.write(ByteBuffer.wrap(sizeLine.getBytes(StandardCharsets.US_ASCII)));

      // Write data
      channel.write(ByteBuffer.wrap(b, off, len));

      // Write CRLF
      channel.write(ByteBuffer.wrap("\r\n".getBytes(StandardCharsets.US_ASCII)));
    }

    @Override
    public void close() throws IOException {
      // Write end chunk
      channel.write(ByteBuffer.wrap("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
      // Do not close the channel here, as it's managed by the worker
    }
  }
}
