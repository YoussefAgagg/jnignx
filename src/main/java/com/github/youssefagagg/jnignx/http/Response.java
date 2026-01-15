package com.github.youssefagagg.jnignx.http;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents an HTTP response.
 */
public class Response {
  private final Map<String, String> headers = new HashMap<>();
  private int statusCode;
  private String statusMessage;
  private byte[] body;

  public Response(int statusCode, String statusMessage) {
    this.statusCode = statusCode;
    this.statusMessage = statusMessage;
  }

  public static Response ok() {
    return new Response(200, "OK");
  }

  public static Response notFound() {
    return new Response(404, "Not Found");
  }

  public static Response badRequest() {
    return new Response(400, "Bad Request");
  }

  public static Response internalServerError() {
    return new Response(500, "Internal Server Error");
  }

  public Response header(String key, String value) {
    headers.put(key, value);
    return this;
  }

  public Response body(String content) {
    this.body = content.getBytes(StandardCharsets.UTF_8);
    header("Content-Length", String.valueOf(body.length));
    return this;
  }

  public Response body(byte[] content) {
    this.body = content;
    header("Content-Length", String.valueOf(content.length));
    return this;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public String getStatusMessage() {
    return statusMessage;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public byte[] getBody() {
    return body;
  }
}
