package com.github.youssefagagg.jnignx.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates route configuration before loading into the router.
 *
 * <p>Performs comprehensive validation including:
 * <ul>
 *   <li>Path format validation</li>
 *   <li>Backend URL validation</li>
 *   <li>Duplicate route detection</li>
 *   <li>Configuration consistency checks</li>
 * </ul>
 *
 * <p><b>Example Usage:</b>
 * <pre>
 * ConfigValidator validator = new ConfigValidator();
 * RouteConfig config = ConfigLoader.load(path);
 *
 * List&lt;String&gt; errors = validator.validate(config);
 * if (!errors.isEmpty()) {
 *   errors.forEach(System.err::println);
 *   throw new IllegalArgumentException("Invalid configuration");
 * }
 * </pre>
 */
public final class ConfigValidator {

  private static final int MAX_PATH_LENGTH = 2048;
  private static final int MAX_BACKENDS_PER_ROUTE = 100;
  private static final String ROUTE_LITERAL = "Route '";

  /**
   * Validates a route configuration.
   *
   * @param config the configuration to validate
   * @return a list of error messages (empty if valid)
   */
  public List<String> validate(RouteConfig config) {
    List<String> errors = new ArrayList<>();

    if (config == null) {
      errors.add("Configuration is null");
      return errors;
    }

    // Validate routes
    Map<String, List<String>> routes = config.routes();
    if (routes == null || routes.isEmpty()) {
      errors.add("No routes configured");
    } else {
      Set<String> seenPaths = new HashSet<>();

      for (Map.Entry<String, List<String>> entry : routes.entrySet()) {
        String path = entry.getKey();
        List<String> backends = entry.getValue();

        // Validate path
        errors.addAll(validatePath(path));

        // Check for duplicates
        if (seenPaths.contains(path)) {
          errors.add("Duplicate route path: " + path);
        }
        seenPaths.add(path);

        // Validate backends
        errors.addAll(validateBackends(path, backends));
      }
    }

    return errors;
  }

  /**
   * Validates a route path.
   */
  private List<String> validatePath(String path) {
    List<String> errors = new ArrayList<>();

    if (path == null || path.isBlank()) {
      errors.add("Path cannot be empty");
      return errors;
    }

    if (!path.startsWith("/")) {
      errors.add("Path must start with '/': " + path);
    }

    if (path.length() > MAX_PATH_LENGTH) {
      errors.add("Path exceeds maximum length of " + MAX_PATH_LENGTH + ": " + path);
    }

    // Check for invalid characters
    if (path.contains("..")) {
      errors.add("Path contains dangerous '..' sequence: " + path);
    }

    if (path.contains("//")) {
      errors.add("Path contains double slashes: " + path);
    }

    // Check for null bytes (security)
    if (path.contains("\0")) {
      errors.add("Path contains null byte: " + path);
    }

    return errors;
  }

  /**
   * Validates backend URLs for a route.
   */
  private List<String> validateBackends(String path, List<String> backends) {
    List<String> errors = new ArrayList<>();

    if (backends == null || backends.isEmpty()) {
      errors.add(ROUTE_LITERAL + path + "' has no backends configured");
      return errors;
    }

    if (backends.size() > MAX_BACKENDS_PER_ROUTE) {
      errors.add(ROUTE_LITERAL + path + "' has too many backends (max " +
                     MAX_BACKENDS_PER_ROUTE + "): " + backends.size());
    }

    Set<String> seenBackends = new HashSet<>();
    for (String backend : backends) {
      if (backend == null || backend.isBlank()) {
        errors.add(ROUTE_LITERAL + path + "' has empty backend URL");
        continue;
      }

      // Check for duplicates
      if (seenBackends.contains(backend)) {
        errors.add(ROUTE_LITERAL + path + "' has duplicate backend: " + backend);
      }
      seenBackends.add(backend);

      // Validate URL format
      if (backend.startsWith("file://")) {
        // Validate file path
        errors.addAll(validateFilePath(path, backend));
      } else {
        errors.addAll(validateHttpUrl(backend));
      }
    }

    return errors;
  }

  /**
   * Validates an HTTP/HTTPS URL.
   */
  private List<String> validateHttpUrl(String backend) {
    List<String> errors = new ArrayList<>();

    try {
      URI uri = new URI(backend);

      String scheme = uri.getScheme();
      if (scheme == null) {
        errors.add("Backend URL missing scheme: " + backend);
      } else if (!scheme.equals("http") && !scheme.equals("https")) {
        errors.add("Backend URL must use http or https: " + backend);
      }

      String host = uri.getHost();
      if (host == null || host.isBlank()) {
        errors.add("Backend URL missing host: " + backend);
      }

      int port = uri.getPort();
      if (port < -1 || port > 65535) {
        errors.add("Backend URL has invalid port: " + backend);
      }

    } catch (URISyntaxException e) {
      errors.add("Invalid backend URL '" + backend + "': " + e.getMessage());
    }

    return errors;
  }

  /**
   * Validates a file:// URL.
   */
  private List<String> validateFilePath(String path, String fileUrl) {
    List<String> errors = new ArrayList<>();

    String filePath = fileUrl.substring(7); // Remove "file://"
    Path dirPath = Path.of(filePath);

    if (!Files.exists(dirPath)) {
      errors.add("Static directory does not exist for route '" + path + "': " + filePath);
    } else if (!Files.isDirectory(dirPath)) {
      errors.add("Static path is not a directory for route '" + path + "': " + filePath);
    } else if (!Files.isReadable(dirPath)) {
      errors.add("Static directory is not readable for route '" + path + "': " + filePath);
    }

    return errors;
  }

  /**
   * Validates configuration and throws exception if invalid.
   *
   * @param config the configuration to validate
   * @throws IllegalArgumentException if configuration is invalid
   */
  public void validateOrThrow(RouteConfig config) {
    List<String> errors = validate(config);
    if (!errors.isEmpty()) {
      StringBuilder sb = new StringBuilder("Configuration validation failed:\n");
      for (String error : errors) {
        sb.append("  - ").append(error).append("\n");
      }
      throw new IllegalArgumentException(sb.toString());
    }
  }

  /**
   * Validates a configuration file before loading.
   *
   * @param configPath path to the configuration file
   * @return list of validation errors
   */
  public List<String> validateFile(Path configPath) {
    List<String> errors = new ArrayList<>();

    if (configPath == null) {
      errors.add("Configuration path is null");
      return errors;
    }

    if (!Files.exists(configPath)) {
      errors.add("Configuration file does not exist: " + configPath);
      return errors;
    }

    if (!Files.isReadable(configPath)) {
      errors.add("Configuration file is not readable: " + configPath);
      return errors;
    }

    if (!configPath.toString().endsWith(".json")) {
      errors.add("Configuration file must be JSON (.json extension): " + configPath);
    }

    // Try to load and validate the config
    try {
      RouteConfig config = ConfigLoader.load(configPath);
      errors.addAll(validate(config));
    } catch (Exception e) {
      errors.add("Failed to load configuration: " + e.getMessage());
    }

    return errors;
  }
}
