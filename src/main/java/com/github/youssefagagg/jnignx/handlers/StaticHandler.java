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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Handles static file serving with zero-copy transfer, directory listing, Gzip compression,
 * range requests, conditional requests, and custom error pages.
 *
 * <p>Features:
 * <ul>
 *   <li>Zero-copy file transfer via FileChannel.transferTo</li>
 *   <li>Automatic MIME type detection</li>
 *   <li>Directory listing when no index.html is present</li>
 *   <li>Gzip compression for text-based content</li>
 *   <li>Range requests (byte serving) for video streaming and download resumption</li>
 *   <li>Conditional requests (If-None-Match, If-Modified-Since) for cache validation</li>
 *   <li>Custom error pages</li>
 *   <li>Path traversal protection</li>
 * </ul>
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

  // Custom error pages: status code -> file path
  private static final Map<Integer, Path> CUSTOM_ERROR_PAGES = new ConcurrentHashMap<>();

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
    MIME_TYPES.put("mp4", "video/mp4");
    MIME_TYPES.put("webm", "video/webm");
    MIME_TYPES.put("mp3", "audio/mpeg");
    MIME_TYPES.put("wav", "audio/wav");
    MIME_TYPES.put("pdf", "application/pdf");
    MIME_TYPES.put("woff", "font/woff");
    MIME_TYPES.put("woff2", "font/woff2");
    MIME_TYPES.put("ico", "image/x-icon");
    MIME_TYPES.put("webp", "image/webp");
  }

  /**
   * Registers a custom error page for a status code.
   *
   * @param statusCode the HTTP status code (e.g., 404, 403)
   * @param filePath   the path to the custom error page HTML file
   */
  public static void registerErrorPage(int statusCode, Path filePath) {
    if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
      CUSTOM_ERROR_PAGES.put(statusCode, filePath);
    }
  }

  /**
   * Clears all registered custom error pages.
   */
  public static void clearErrorPages() {
    CUSTOM_ERROR_PAGES.clear();
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
        HTTP_DATE_FORMATTER.format(Instant.ofEpochMilli(lastModifiedTime));
    String etag =
        "\"" + Long.toHexString(lastModifiedTime) + "-" + Long.toHexString(length) + "\"";

    // Check conditional requests (If-None-Match / If-Modified-Since)
    if (checkConditional(clientChannel, request, etag, lastModifiedTime)) {
      return; // 304 Not Modified was sent
    }

    // Check for Range requests
    String rangeHeader = request.headers().get("Range");
    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
      serveRangeRequest(clientChannel, file, length, contentType, lastModified, etag, rangeHeader);
      return;
    }

    // Check Accept-Encoding
    String acceptEncoding = request.headers().getOrDefault("Accept-Encoding", "");
    boolean useGzip = acceptEncoding.contains("gzip") && COMPRESSIBLE_TYPES.contains(contentType);

    if (useGzip) {
      serveFileGzip(clientChannel, file, contentType, lastModified, etag);
    } else {
      serveFileZeroCopy(clientChannel, file, length, contentType, lastModified, etag);
    }
  }

  /**
   * Checks conditional request headers and sends 304 Not Modified if appropriate.
   *
   * @return true if 304 was sent, false if the request should proceed normally
   */
  private boolean checkConditional(SocketChannel clientChannel, Request request,
                                   String etag, long lastModifiedTime) throws IOException {
    // Check If-None-Match (takes precedence over If-Modified-Since)
    String ifNoneMatch = request.headers().get("If-None-Match");
    if (ifNoneMatch != null) {
      if (ifNoneMatch.equals(etag) || ifNoneMatch.equals("*")) {
        send304(clientChannel, etag, lastModifiedTime);
        return true;
      }
      // Check comma-separated list of ETags
      for (String tag : ifNoneMatch.split(",")) {
        if (tag.trim().equals(etag)) {
          send304(clientChannel, etag, lastModifiedTime);
          return true;
        }
      }
    }

    // Check If-Modified-Since
    String ifModifiedSince = request.headers().get("If-Modified-Since");
    if (ifModifiedSince != null && ifNoneMatch == null) {
      try {
        Instant clientDate = HTTP_DATE_FORMATTER.parse(ifModifiedSince.trim(), Instant::from);
        Instant serverDate = Instant.ofEpochMilli(lastModifiedTime);
        // Compare at second precision (HTTP dates don't have sub-second)
        if (!serverDate.isAfter(clientDate.plusSeconds(1))) {
          send304(clientChannel, etag, lastModifiedTime);
          return true;
        }
      } catch (Exception ignored) {
        // Invalid date format, proceed with normal response
      }
    }

    return false;
  }

  /**
   * Sends a 304 Not Modified response.
   */
  private void send304(SocketChannel clientChannel, String etag, long lastModifiedTime)
      throws IOException {
    String lastModified = HTTP_DATE_FORMATTER.format(Instant.ofEpochMilli(lastModifiedTime));
    String response = "HTTP/1.1 304 Not Modified\r\n" +
        "ETag: " + etag + "\r\n" +
        "Last-Modified: " + lastModified + "\r\n" +
        "Cache-Control: public, max-age=3600\r\n" +
        "\r\n";
    clientChannel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Serves a range request (partial content).
   * Supports single byte ranges like "bytes=0-499" or "bytes=500-" or "bytes=-500".
   */
  private void serveRangeRequest(SocketChannel clientChannel, Path file, long fileSize,
                                 String contentType, String lastModified, String etag,
                                 String rangeHeader) throws IOException {
    // Parse range: "bytes=start-end"
    String rangeSpec = rangeHeader.substring(6); // Remove "bytes="

    long start;
    long end;

    try {
      if (rangeSpec.startsWith("-")) {
        // Suffix range: last N bytes
        long suffixLength = Long.parseLong(rangeSpec.substring(1));
        start = fileSize - suffixLength;
        end = fileSize - 1;
      } else if (rangeSpec.endsWith("-")) {
        // Open-ended range
        start = Long.parseLong(rangeSpec.substring(0, rangeSpec.length() - 1));
        end = fileSize - 1;
      } else if (rangeSpec.contains("-")) {
        String[] parts = rangeSpec.split("-", 2);
        start = Long.parseLong(parts[0]);
        end = Long.parseLong(parts[1]);
      } else {
        // Invalid range
        sendError(clientChannel, 416, "Range Not Satisfiable");
        return;
      }
    } catch (NumberFormatException e) {
      sendError(clientChannel, 416, "Range Not Satisfiable");
      return;
    }

    // Validate range
    if (start < 0 || start >= fileSize || end < start || end >= fileSize) {
      String errorResponse = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
          "Content-Range: bytes */" + fileSize + "\r\n" +
          "Content-Length: 0\r\n" +
          "\r\n";
      clientChannel.write(ByteBuffer.wrap(errorResponse.getBytes(StandardCharsets.UTF_8)));
      return;
    }

    long contentLength = end - start + 1;

    String header = "HTTP/1.1 206 Partial Content\r\n" +
        "Content-Type: " + contentType + "\r\n" +
        "Content-Length: " + contentLength + "\r\n" +
        "Content-Range: bytes " + start + "-" + end + "/" + fileSize + "\r\n" +
        "Last-Modified: " + lastModified + "\r\n" +
        "ETag: " + etag + "\r\n" +
        "Accept-Ranges: bytes\r\n" +
        "Cache-Control: public, max-age=3600\r\n" +
        "\r\n";

    clientChannel.write(ByteBuffer.wrap(header.getBytes(StandardCharsets.UTF_8)));

    try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
      long transferred = 0;
      while (transferred < contentLength) {
        long count = fileChannel.transferTo(start + transferred,
                                            contentLength - transferred, clientChannel);
        if (count <= 0) {
          break;
        }
        transferred += count;
      }
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
        "Accept-Ranges: bytes\r\n" +
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
    // Check for custom error page
    Path customPage = CUSTOM_ERROR_PAGES.get(code);
    if (customPage != null && Files.exists(customPage)) {
      serveCustomErrorPage(clientChannel, code, message, customPage);
      return;
    }

    // Default error page with styled HTML
    String body = "<!DOCTYPE html><html><head><title>" + code + " " + message + "</title>" +
        "<style>body{font-family:sans-serif;text-align:center;padding:50px}" +
        "h1{color:#333}p{color:#666}</style></head>" +
        "<body><h1>" + code + " " + message + "</h1>" +
        "<p>JNignx Server</p></body></html>";
    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

    String response = "HTTP/1.1 " + code + " " + message + "\r\n" +
        "Content-Type: text/html\r\n" +
        "Content-Length: " + bodyBytes.length + "\r\n" +
        "\r\n";
    clientChannel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
    clientChannel.write(ByteBuffer.wrap(bodyBytes));
  }

  private void serveCustomErrorPage(SocketChannel clientChannel, int code, String message,
                                    Path customPage) throws IOException {
    byte[] bodyBytes = Files.readAllBytes(customPage);
    String response = "HTTP/1.1 " + code + " " + message + "\r\n" +
        "Content-Type: text/html\r\n" +
        "Content-Length: " + bodyBytes.length + "\r\n" +
        "\r\n";
    clientChannel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
    clientChannel.write(ByteBuffer.wrap(bodyBytes));
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
