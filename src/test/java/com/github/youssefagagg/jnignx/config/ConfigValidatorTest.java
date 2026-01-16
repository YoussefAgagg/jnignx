package com.github.youssefagagg.jnignx.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for ConfigValidator.
 */
class ConfigValidatorTest {

  private ConfigValidator validator;

  @BeforeEach
  void setUp() {
    validator = new ConfigValidator();
  }

  @Test
  void testValidConfiguration() {
    RouteConfig config = new RouteConfig(
        Map.of("/api", List.of("http://localhost:3000"))
    );

    List<String> errors = validator.validate(config);
    assertTrue(errors.isEmpty(), "Valid config should have no errors: " + errors);
  }

  @Test
  void testNullConfiguration() {
    List<String> errors = validator.validate(null);
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("null"));
  }

  @Test
  void testEmptyRoutes() {
    RouteConfig config = new RouteConfig(Map.of());

    List<String> errors = validator.validate(config);
    assertFalse(errors.isEmpty());
    assertTrue(errors.stream().anyMatch(e -> e.contains("No routes")));
  }

  @Test
  void testInvalidPath() {
    RouteConfig config = new RouteConfig(
        Map.of("invalid-path", List.of("http://localhost:3000"))
    );

    List<String> errors = validator.validate(config);
    assertTrue(errors.stream().anyMatch(e -> e.contains("must start with '/'")));
  }

  @Test
  void testDangerousPath() {
    RouteConfig config = new RouteConfig(
        Map.of("/../etc", List.of("http://localhost:3000"))
    );

    List<String> errors = validator.validate(config);
    assertTrue(errors.stream().anyMatch(e -> e.contains("dangerous")));
  }

  @Test
  void testInvalidBackendUrl() {
    RouteConfig config = new RouteConfig(
        Map.of("/api", List.of("not-a-url", "ftp://invalid.com"))
    );

    List<String> errors = validator.validate(config);
    assertTrue(errors.stream().anyMatch(
        e -> e.contains("Invalid backend URL") || e.contains("missing scheme")));
    assertTrue(errors.stream().anyMatch(e -> e.contains("ftp") || e.contains("must use http")));
  }

  @Test
  void testEmptyBackends() {
    RouteConfig config = new RouteConfig(
        Map.of("/api", List.of())
    );

    List<String> errors = validator.validate(config);
    assertTrue(errors.stream().anyMatch(e -> e.contains("no backends")));
  }

  @Test
  void testDuplicateBackends() {
    RouteConfig config = new RouteConfig(
        Map.of("/api", List.of("http://localhost:3000", "http://localhost:3000"))
    );

    List<String> errors = validator.validate(config);
    assertTrue(errors.stream().anyMatch(e -> e.contains("duplicate backend")));
  }

  @Test
  void testFileBackend(@TempDir Path tempDir) throws Exception {
    Path validDir = tempDir.resolve("static");
    Files.createDirectories(validDir);

    RouteConfig config = new RouteConfig(
        Map.of("/static", List.of("file://" + validDir.toString()))
    );

    List<String> errors = validator.validate(config);
    assertTrue(errors.isEmpty(), "Valid file backend should have no errors: " + errors);
  }

  @Test
  void testFileBackendNonExistent() {
    RouteConfig config = new RouteConfig(
        Map.of("/static", List.of("file:///non/existent/directory"))
    );

    List<String> errors = validator.validate(config);
    assertTrue(errors.stream().anyMatch(e -> e.contains("does not exist")));
  }

  @Test
  void testMultipleBackends() {
    RouteConfig config = new RouteConfig(
        Map.of("/api", List.of(
            "http://localhost:3000",
            "http://localhost:3001",
            "http://localhost:3002"
        ))
    );

    List<String> errors = validator.validate(config);
    assertTrue(errors.isEmpty(), "Valid multiple backends should have no errors: " + errors);
  }

  @Test
  void testHttpsBackend() {
    RouteConfig config = new RouteConfig(
        Map.of("/secure", List.of("https://secure-backend:8443"))
    );

    List<String> errors = validator.validate(config);
    assertTrue(errors.isEmpty(), "Valid HTTPS backend should have no errors: " + errors);
  }

  @Test
  void testPathWithSlashes() {
    RouteConfig config = new RouteConfig(
        Map.of("/api/v1/users", List.of("http://localhost:3000"))
    );

    List<String> errors = validator.validate(config);
    assertTrue(errors.isEmpty(), "Valid nested path should have no errors: " + errors);
  }

  @Test
  void testValidateOrThrowSuccess() {
    RouteConfig config = new RouteConfig(
        Map.of("/api", List.of("http://localhost:3000"))
    );

    assertDoesNotThrow(() -> validator.validateOrThrow(config));
  }

  @Test
  void testValidateOrThrowFailure() {
    RouteConfig config = new RouteConfig(Map.of());

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> validator.validateOrThrow(config)
    );
    assertTrue(ex.getMessage().contains("validation failed"));
  }

  @Test
  void testBackendWithPort() {
    RouteConfig config = new RouteConfig(
        Map.of("/api", List.of("http://localhost:8080"))
    );

    List<String> errors = validator.validate(config);
    assertTrue(errors.isEmpty(), "Valid backend with port should have no errors: " + errors);
  }

  @Test
  void testBackendWithoutPort() {
    RouteConfig config = new RouteConfig(
        Map.of("/api", List.of("http://localhost"))
    );

    List<String> errors = validator.validate(config);
    assertTrue(errors.isEmpty(),
               "Valid backend without explicit port should have no errors: " + errors);
  }
}
