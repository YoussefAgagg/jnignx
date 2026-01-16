package com.github.youssefagagg.jnignx.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Structured JSON logger for HTTP access logs and server events.
 *
 * <p>Logs are written to stdout in JSON format for easy parsing by log aggregation
 * systems like ELK, Splunk, or Datadog.
 *
 * <p>Example access log:
 * <pre>
 * {
 *   "timestamp": "2026-01-16T10:30:45.123Z",
 *   "level": "INFO",
 *   "type": "access",
 *   "client_ip": "192.168.1.100",
 *   "method": "GET",
 *   "path": "/api/users",
 *   "status": 200,
 *   "duration_ms": 45,
 *   "bytes_sent": 1234,
 *   "user_agent": "curl/7.64.1",
 *   "backend": "http://localhost:3000"
 * }
 * </pre>
 */
public final class AccessLogger {

  private static final DateTimeFormatter ISO_FORMATTER =
      DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

  /**
   * Logs an HTTP access event.
   *
   * @param clientIp   the client IP address
   * @param method     the HTTP method
   * @param path       the request path
   * @param status     the HTTP status code
   * @param durationMs the request duration in milliseconds
   * @param bytesSent  the number of bytes sent in the response
   * @param userAgent  the User-Agent header value
   * @param backend    the backend server that handled the request
   */
  public static void logAccess(String clientIp, String method, String path, int status,
                               long durationMs, long bytesSent, String userAgent, String backend) {
    String timestamp = ISO_FORMATTER.format(Instant.now());

    StringBuilder json = new StringBuilder();
    json.append("{");
    json.append("\"timestamp\":\"").append(timestamp).append("\",");
    json.append("\"level\":\"INFO\",");
    json.append("\"type\":\"access\",");
    json.append("\"client_ip\":\"").append(escape(clientIp)).append("\",");
    json.append("\"method\":\"").append(escape(method)).append("\",");
    json.append("\"path\":\"").append(escape(path)).append("\",");
    json.append("\"status\":").append(status).append(",");
    json.append("\"duration_ms\":").append(durationMs).append(",");
    json.append("\"bytes_sent\":").append(bytesSent).append(",");
    json.append("\"user_agent\":\"").append(escape(userAgent)).append("\",");
    json.append("\"backend\":\"").append(escape(backend)).append("\"");
    json.append("}");

    System.out.println(json);
  }

  /**
   * Logs an error event.
   *
   * @param message the error message
   * @param error   the exception or error details
   */
  public static void logError(String message, String error) {
    String timestamp = ISO_FORMATTER.format(Instant.now());

    StringBuilder json = new StringBuilder();
    json.append("{");
    json.append("\"timestamp\":\"").append(timestamp).append("\",");
    json.append("\"level\":\"ERROR\",");
    json.append("\"type\":\"error\",");
    json.append("\"message\":\"").append(escape(message)).append("\",");
    json.append("\"error\":\"").append(escape(error)).append("\"");
    json.append("}");

    System.err.println(json);
  }

  /**
   * Logs a general info event.
   *
   * @param message  the log message
   * @param metadata additional metadata key-value pairs
   */
  public static void logInfo(String message, Map<String, String> metadata) {
    String timestamp = ISO_FORMATTER.format(Instant.now());

    StringBuilder json = new StringBuilder();
    json.append("{");
    json.append("\"timestamp\":\"").append(timestamp).append("\",");
    json.append("\"level\":\"INFO\",");
    json.append("\"type\":\"info\",");
    json.append("\"message\":\"").append(escape(message)).append("\"");

    if (metadata != null && !metadata.isEmpty()) {
      for (Map.Entry<String, String> entry : metadata.entrySet()) {
        json.append(",\"").append(escape(entry.getKey())).append("\":\"")
            .append(escape(entry.getValue())).append("\"");
      }
    }

    json.append("}");

    System.out.println(json);
  }

  /**
   * Escapes special characters in JSON strings.
   */
  private static String escape(String str) {
    if (str == null) {
      return "";
    }
    return str.replace("\\", "\\\\")
              .replace("\"", "\\\"")
              .replace("\n", "\\n")
              .replace("\r", "\\r")
              .replace("\t", "\\t");
  }
}
