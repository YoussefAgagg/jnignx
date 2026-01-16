package com.github.youssefagagg.jnignx.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for CORS configuration and handling.
 */
class CorsConfigTest {

  private CorsConfig.Builder builder;

  @BeforeEach
  void setUp() {
    builder = new CorsConfig.Builder();
  }

  @Test
  void testDefaultConfiguration() {
    CorsConfig cors = builder.build();

    assertTrue(cors.isEnabled());
    assertFalse(cors.isOriginAllowed("https://example.com"));
  }

  @Test
  void testAllowSpecificOrigin() {
    CorsConfig cors = builder
        .allowOrigin("https://example.com")
        .build();

    assertTrue(cors.isOriginAllowed("https://example.com"));
    assertFalse(cors.isOriginAllowed("https://other.com"));
  }

  @Test
  void testAllowMultipleOrigins() {
    CorsConfig cors = builder
        .allowOrigins("https://example.com", "https://app.example.com")
        .build();

    assertTrue(cors.isOriginAllowed("https://example.com"));
    assertTrue(cors.isOriginAllowed("https://app.example.com"));
    assertFalse(cors.isOriginAllowed("https://other.com"));
  }

  @Test
  void testAllowAnyOrigin() {
    CorsConfig cors = builder
        .allowAnyOrigin()
        .build();

    assertTrue(cors.isOriginAllowed("https://example.com"));
    assertTrue(cors.isOriginAllowed("https://any-domain.com"));
  }

  @Test
  void testAllowSpecificMethods() {
    CorsConfig cors = builder
        .allowOrigin("https://example.com")
        .allowMethod("GET", "POST")
        .build();

    assertTrue(cors.isMethodAllowed("GET"));
    assertTrue(cors.isMethodAllowed("POST"));
    assertFalse(cors.isMethodAllowed("DELETE"));
  }

  @Test
  void testCorsHeaders() {
    CorsConfig cors = builder
        .allowOrigin("https://example.com")
        .allowCredentials(true)
        .exposeHeader("X-Custom-Header")
        .build();

    Map<String, String> headers = cors.getCorsHeaders("https://example.com", "GET");

    assertEquals("https://example.com", headers.get("Access-Control-Allow-Origin"));
    assertEquals("true", headers.get("Access-Control-Allow-Credentials"));
    assertTrue(headers.get("Access-Control-Expose-Headers").contains("X-Custom-Header"));
  }

  @Test
  void testPreflightHeaders() {
    CorsConfig cors = builder
        .allowOrigin("https://example.com")
        .allowMethod("GET", "POST", "PUT")
        .allowHeader("Content-Type", "Authorization")
        .maxAge(7200)
        .build();

    Map<String, String> headers = cors.getPreflightHeaders(
        "https://example.com",
        "POST",
        "Content-Type"
    );

    assertEquals("https://example.com", headers.get("Access-Control-Allow-Origin"));
    assertTrue(headers.get("Access-Control-Allow-Methods").contains("POST"));
    assertTrue(headers.get("Access-Control-Allow-Headers").contains("Content-Type"));
    assertEquals("7200", headers.get("Access-Control-Max-Age"));
  }

  @Test
  void testIsPreflightRequest() {
    assertTrue(CorsConfig.isPreflight("OPTIONS", "https://example.com", "POST"));
    assertFalse(CorsConfig.isPreflight("POST", "https://example.com", "POST"));
    assertFalse(CorsConfig.isPreflight("OPTIONS", null, "POST"));
    assertFalse(CorsConfig.isPreflight("OPTIONS", "https://example.com", null));
  }

  @Test
  void testPermissiveConfiguration() {
    CorsConfig cors = CorsConfig.permissive();

    assertTrue(cors.isEnabled());
    assertTrue(cors.isOriginAllowed("https://any-domain.com"));
    assertTrue(cors.isMethodAllowed("DELETE"));
  }

  @Test
  void testDisabledConfiguration() {
    CorsConfig cors = CorsConfig.disabled();

    assertFalse(cors.isEnabled());
    assertFalse(cors.isOriginAllowed("https://example.com"));
  }

  @Test
  void testStrictConfiguration() {
    CorsConfig cors = builder
        .strict("https://example.com", "https://app.example.com")
        .build();

    assertTrue(cors.isOriginAllowed("https://example.com"));
    assertFalse(cors.isOriginAllowed("https://other.com"));
    assertTrue(cors.isMethodAllowed("GET"));
    assertFalse(cors.isMethodAllowed("DELETE"));
  }

  @Test
  void testWildcardWithCredentials() {
    // Should throw exception - security violation
    assertThrows(IllegalStateException.class, () -> {
      builder
          .allowAnyOrigin()
          .allowCredentials(true)
          .build();
    });
  }

  @Test
  void testDisabledCors() {
    CorsConfig cors = builder
        .enabled(false)
        .allowOrigin("https://example.com")
        .build();

    assertFalse(cors.isEnabled());
    assertFalse(cors.isOriginAllowed("https://example.com"));

    Map<String, String> headers = cors.getCorsHeaders("https://example.com", "GET");
    assertTrue(headers.isEmpty());
  }

  @Test
  void testVaryHeader() {
    CorsConfig cors = builder
        .allowOrigin("https://example.com")
        .build();

    Map<String, String> headers = cors.getCorsHeaders("https://example.com", "GET");
    assertEquals("Origin", headers.get("Vary"));
  }

  @Test
  void testNoVaryHeaderForWildcard() {
    CorsConfig cors = builder
        .allowAnyOrigin()
        .build();

    Map<String, String> headers = cors.getCorsHeaders("https://example.com", "GET");
    assertNull(headers.get("Vary"));
  }

  @Test
  void testInvalidMaxAge() {
    assertThrows(IllegalArgumentException.class, () -> {
      builder.maxAge(-1);
    });
  }

  @Test
  void testHeaderCaseInsensitivity() {
    CorsConfig cors = builder
        .allowOrigin("https://example.com")
        .allowMethod("get", "post")
        .build();

    assertTrue(cors.isMethodAllowed("GET"));
    assertTrue(cors.isMethodAllowed("get"));
    assertTrue(cors.isMethodAllowed("Post"));
  }
}
