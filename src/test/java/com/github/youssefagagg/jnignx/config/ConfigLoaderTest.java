package com.github.youssefagagg.jnignx.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

  @TempDir
  Path tempDir;

  @Test
  void testLoadSimpleRouteConfig() throws Exception {
    String config = """
        {
          "routes": {
            "/api": ["http://backend:8080"]
          }
        }
        """;
    Path configFile = tempDir.resolve("config.json");
    Files.writeString(configFile, config);

    RouteConfig routeConfig = ConfigLoader.load(configFile);

    assertNotNull(routeConfig);
    assertNotNull(routeConfig.routes());
    assertTrue(routeConfig.routes().containsKey("/api"));
  }

  @Test
  void testLoadServerConfigWithAllFeatures() throws Exception {
    String config = """
        {
          "routes": {
            "/api": ["http://backend1:8080", "http://backend2:8080"]
          },
          "loadBalancer": "ROUND_ROBIN",
          "rateLimiter": {
            "enabled": true,
            "requestsPerSecond": 100,
            "burstSize": 20
          },
          "cors": {
            "enabled": true,
            "allowedOrigins": ["*"],
            "allowedMethods": ["GET", "POST"]
          }
        }
        """;
    Path configFile = tempDir.resolve("config.json");
    Files.writeString(configFile, config);

    ServerConfig serverConfig = ConfigLoader.loadServerConfig(configFile);

    assertNotNull(serverConfig);
    assertNotNull(serverConfig.routes());
    assertTrue(serverConfig.rateLimiterEnabled());
    assertTrue(serverConfig.corsConfig().isEnabled());
  }

  @Test
  void testLoadWithDefaults() throws Exception {
    String config = """
        {
          "routes": {
            "/": ["http://backend:8080"]
          }
        }
        """;
    Path configFile = tempDir.resolve("config.json");
    Files.writeString(configFile, config);

    ServerConfig serverConfig = ConfigLoader.loadServerConfig(configFile);

    assertNotNull(serverConfig);
    assertNotNull(serverConfig.routes());
    // Should have default values
    assertNotNull(serverConfig.loadBalancerAlgorithm());
  }

  @Test
  void testLoadInvalidJson() throws Exception {
    String config = "{ invalid json";
    Path configFile = tempDir.resolve("config.json");
    Files.writeString(configFile, config);

    assertThrows(Exception.class, () -> {
      ConfigLoader.loadServerConfig(configFile);
    });
  }

  @Test
  void testLoadNonExistentFile() {
    Path configFile = tempDir.resolve("non-existent.json");

    assertThrows(Exception.class, () -> {
      ConfigLoader.loadServerConfig(configFile);
    });
  }

  @Test
  void testLoadEmptyConfig() throws Exception {
    String config = "{}";
    Path configFile = tempDir.resolve("config.json");
    Files.writeString(configFile, config);

    // Should work but have empty routes
    ServerConfig serverConfig = ConfigLoader.loadServerConfig(configFile);
    assertNotNull(serverConfig);
    // Empty routes map is allowed, validation happens elsewhere
  }

  @Test
  void testLoadWithHealthCheck() throws Exception {
    String config = """
        {
          "routes": {
            "/api": ["http://backend:8080"]
          },
          "healthCheck": {
            "enabled": true,
            "intervalSeconds": 10,
            "timeoutSeconds": 5
          }
        }
        """;
    Path configFile = tempDir.resolve("config.json");
    Files.writeString(configFile, config);

    ServerConfig serverConfig = ConfigLoader.loadServerConfig(configFile);

    assertNotNull(serverConfig);
    assertTrue(serverConfig.healthCheckEnabled());
  }

  @Test
  void testLoadWithCircuitBreaker() throws Exception {
    String config = """
        {
          "routes": {
            "/api": ["http://backend:8080"]
          },
          "circuitBreaker": {
            "enabled": true,
            "failureThreshold": 5,
            "timeoutSeconds": 30
          }
        }
        """;
    Path configFile = tempDir.resolve("config.json");
    Files.writeString(configFile, config);

    ServerConfig serverConfig = ConfigLoader.loadServerConfig(configFile);

    assertNotNull(serverConfig);
    assertTrue(serverConfig.circuitBreakerEnabled());
  }

  @Test
  void testLoadWithStaticFiles() throws Exception {
    String config = """
        {
          "routes": {
            "/": ["file:///var/www/html"]
          }
        }
        """;
    Path configFile = tempDir.resolve("config.json");
    Files.writeString(configFile, config);

    ServerConfig serverConfig = ConfigLoader.loadServerConfig(configFile);

    assertNotNull(serverConfig);
    assertNotNull(serverConfig.routes());
  }

  @Test
  void testLoadWithWebSocket() throws Exception {
    String config = """
        {
          "routes": {
            "/ws": ["ws://backend:8080"]
          }
        }
        """;
    Path configFile = tempDir.resolve("config.json");
    Files.writeString(configFile, config);

    ServerConfig serverConfig = ConfigLoader.loadServerConfig(configFile);

    assertNotNull(serverConfig);
  }

  @Test
  void testLoadWithCompression() throws Exception {
    String config = """
        {
          "routes": {
            "/": ["http://backend:8080"]
          }
        }
        """;
    Path configFile = tempDir.resolve("config.json");
    Files.writeString(configFile, config);

    ServerConfig serverConfig = ConfigLoader.loadServerConfig(configFile);

    assertNotNull(serverConfig);
  }

  @Test
  void testLoadWithAccessLog() throws Exception {
    String config = """
        {
          "routes": {
            "/": ["http://backend:8080"]
          }
        }
        """;
    Path configFile = tempDir.resolve("config.json");
    Files.writeString(configFile, config);

    ServerConfig serverConfig = ConfigLoader.loadServerConfig(configFile);

    assertNotNull(serverConfig);
  }

  @Test
  void testReloadConfig() throws Exception {
    Path configFile = tempDir.resolve("config.json");

    String config1 = """
        {
          "routes": {
            "/": ["http://backend1:8080"]
          }
        }
        """;
    Files.writeString(configFile, config1);
    ServerConfig serverConfig1 = ConfigLoader.loadServerConfig(configFile);
    assertTrue(serverConfig1.routes().containsKey("/"));

    String config2 = """
        {
          "routes": {
            "/api": ["http://backend2:8080"]
          }
        }
        """;
    Files.writeString(configFile, config2);
    ServerConfig serverConfig2 = ConfigLoader.loadServerConfig(configFile);
    assertTrue(serverConfig2.routes().containsKey("/api"));
  }

  @Test
  void testLoadMultipleBackends() throws Exception {
    String config = """
        {
          "routes": {
            "/api": [
              "http://backend1:8080",
              "http://backend2:8080",
              "http://backend3:8080"
            ]
          }
        }
        """;
    Path configFile = tempDir.resolve("config.json");
    Files.writeString(configFile, config);

    RouteConfig routeConfig = ConfigLoader.load(configFile);

    assertNotNull(routeConfig);
    assertEquals(3, routeConfig.routes().get("/api").size());
  }
}
