package com.github.youssefagagg.jnignx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RouterTest {

  @TempDir
  Path tempDir;

  private Path configFile;
  private Router router;

  @BeforeEach
  void setUp() throws Exception {
    configFile = tempDir.resolve("routes.json");
  }

  @AfterEach
  void tearDown() {
    if (router != null) {
      router.stop();
    }
  }

  @Test
  void testLoadSimpleConfig() throws Exception {
    String config = """
        {
          "routes": {
            "/api": ["http://backend:8080"]
          }
        }
        """;
    Files.writeString(configFile, config);

    router = new Router(configFile);
    router.loadConfig();

    List<String> backends = router.getCurrentConfig().getBackends("/api");
    assertNotNull(backends);
    assertEquals(1, backends.size());
    assertEquals("http://backend:8080", backends.get(0));
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
    Files.writeString(configFile, config);

    router = new Router(configFile);
    router.loadConfig();

    List<String> backends = router.getCurrentConfig().getBackends("/api");
    assertNotNull(backends);
    assertEquals(3, backends.size());
  }

  @Test
  void testMultipleRoutes() throws Exception {
    String config = """
        {
          "routes": {
            "/api": ["http://api-backend:8080"],
            "/static": ["http://static-backend:8081"],
            "/admin": ["http://admin-backend:8082"]
          }
        }
        """;
    Files.writeString(configFile, config);

    router = new Router(configFile);
    router.loadConfig();

    assertNotNull(router.resolveBackend("/api", "192.168.1.1"));
    assertNotNull(router.resolveBackend("/static", "192.168.1.1"));
    assertNotNull(router.resolveBackend("/admin", "192.168.1.1"));
  }

  @Test
  void testMatchExactPath() throws Exception {
    String config = """
        {
          "routes": {
            "/api/v1": ["http://backend1:8080"],
            "/api/v2": ["http://backend2:8080"]
          }
        }
        """;
    Files.writeString(configFile, config);

    router = new Router(configFile);
    router.loadConfig();

    List<String> backends = router.getCurrentConfig().getBackends("/api/v1");
    assertEquals("http://backend1:8080", backends.get(0));

    backends = router.getCurrentConfig().getBackends("/api/v2");
    assertEquals("http://backend2:8080", backends.get(0));
  }

  @Test
  void testMatchPrefixPath() throws Exception {
    String config = """
        {
          "routes": {
            "/api": ["http://backend:8080"]
          }
        }
        """;
    Files.writeString(configFile, config);

    router = new Router(configFile);
    router.loadConfig();

    // Should match paths starting with /api
    assertNotNull(router.getCurrentConfig().getBackends("/api/users"));
    assertNotNull(router.getCurrentConfig().getBackends("/api/v1/data"));
  }

  @Test
  void testRootRoute() throws Exception {
    String config = """
        {
          "routes": {
            "/": ["http://backend:8080"]
          }
        }
        """;
    Files.writeString(configFile, config);

    router = new Router(configFile);
    router.loadConfig();

    assertNotNull(router.getCurrentConfig().getBackends("/"));
    assertNotNull(router.getCurrentConfig().getBackends("/anything"));
  }

  @Test
  void testNoMatchingRoute() throws Exception {
    String config = """
        {
          "routes": {
            "/api": ["http://backend:8080"]
          }
        }
        """;
    Files.writeString(configFile, config);

    router = new Router(configFile);
    router.loadConfig();

    List<String> backends = router.getCurrentConfig().getBackends("/unknown");
    // Might return null or empty list depending on implementation
    assertTrue(backends == null || backends.isEmpty());
  }

  @Test
  void testResolveBackend() throws Exception {
    String config = """
        {
          "routes": {
            "/api": ["http://backend:8080"]
          }
        }
        """;
    Files.writeString(configFile, config);

    router = new Router(configFile);
    router.loadConfig();

    String backend = router.resolveBackend("/api");
    assertNotNull(backend);
    assertEquals("http://backend:8080", backend);
  }

  @Test
  void testLoadBalancing() throws Exception {
    String config = """
        {
          "routes": {
            "/api": [
              "http://backend1:8080",
              "http://backend2:8080"
            ]
          }
        }
        """;
    Files.writeString(configFile, config);

    router = new Router(configFile, LoadBalancer.Strategy.ROUND_ROBIN);
    router.loadConfig();

    String backend1 = router.resolveBackend("/api");
    String backend2 = router.resolveBackend("/api");

    assertNotNull(backend1);
    assertNotNull(backend2);
    // Round robin should alternate
    assertNotEquals(backend1, backend2);
  }

  @Test
  void testHealthChecker() throws Exception {
    String config = """
        {
          "routes": {
            "/api": ["http://backend:8080"]
          }
        }
        """;
    Files.writeString(configFile, config);

    router = new Router(configFile);
    router.loadConfig();

    assertNotNull(router.getHealthChecker());
    assertNotNull(router.getLoadBalancer());
  }

  @Test
  void testRecordConnectionLifecycle() throws Exception {
    String config = """
        {
          "routes": {
            "/api": ["http://backend:8080"]
          }
        }
        """;
    Files.writeString(configFile, config);

    router = new Router(configFile);
    router.loadConfig();

    String backend = "http://backend:8080";
    router.recordConnectionStart(backend);
    router.recordConnectionEnd(backend);
    router.recordProxySuccess(backend);
  }
}
