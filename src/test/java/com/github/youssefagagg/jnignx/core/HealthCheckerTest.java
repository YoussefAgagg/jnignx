package com.github.youssefagagg.jnignx.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HealthCheckerTest {

  private HealthChecker healthChecker;

  @BeforeEach
  void setUp() {
    healthChecker = new HealthChecker();
  }

  @AfterEach
  void tearDown() {
    healthChecker.stop();
  }

  @Test
  void testBackendHealthyByDefault() {
    String backend = "http://backend:8080";
    healthChecker.registerBackend(backend);

    // Newly registered backends should be healthy by default
    assertTrue(healthChecker.isHealthy(backend));
  }

  @Test
  void testRecordProxySuccess() {
    String backend = "http://backend:8080";
    healthChecker.registerBackend(backend);

    healthChecker.recordProxySuccess(backend);

    assertTrue(healthChecker.isHealthy(backend));
  }

  @Test
  void testRecordProxyFailure() {
    String backend = "http://backend:8080";
    healthChecker.registerBackend(backend);

    // Record multiple failures to mark as unhealthy
    for (int i = 0; i < 5; i++) {
      healthChecker.recordProxyFailure(backend, "Test error");
    }

    // After sufficient failures, backend should be unhealthy
    assertFalse(healthChecker.isHealthy(backend));
  }

  @Test
  void testHealthRecovery() {
    String backend = "http://backend:8080";
    healthChecker.registerBackend(backend);

    // Mark as unhealthy
    for (int i = 0; i < 5; i++) {
      healthChecker.recordProxyFailure(backend, "Test error");
    }
    assertFalse(healthChecker.isHealthy(backend));

    // Record successes to recover
    for (int i = 0; i < 3; i++) {
      healthChecker.recordProxySuccess(backend);
    }

    // Should be healthy again after recovery
    assertTrue(healthChecker.isHealthy(backend));
  }

  @Test
  void testMultipleBackends() {
    String backend1 = "http://backend1:8080";
    String backend2 = "http://backend2:8080";

    healthChecker.registerBackend(backend1);
    healthChecker.registerBackend(backend2);

    // Mark only backend1 as unhealthy
    for (int i = 0; i < 5; i++) {
      healthChecker.recordProxyFailure(backend1, "Test error");
    }

    assertFalse(healthChecker.isHealthy(backend1));
    assertTrue(healthChecker.isHealthy(backend2));
  }

  @Test
  void testGetAllHealth() {
    String backend1 = "http://backend1:8080";
    String backend2 = "http://backend2:8080";

    healthChecker.registerBackend(backend1);
    healthChecker.registerBackend(backend2);

    var allHealth = healthChecker.getAllHealth();
    assertNotNull(allHealth);
    assertTrue(allHealth.containsKey(backend1));
    assertTrue(allHealth.containsKey(backend2));
  }

  @Test
  void testUnknownBackend() {
    // Unknown backends should default to healthy
    assertTrue(healthChecker.isHealthy("http://unknown:8080"));
  }

  @Test
  void testStartWithBackends() throws InterruptedException {
    List<String> backends = List.of(
        "http://backend1:8080",
        "http://backend2:8080"
    );

    for (String backend : backends) {
      healthChecker.registerBackend(backend);
    }

    healthChecker.start(backends);

    // Give health checker time to start
    Thread.sleep(100);

    // Should all be registered
    assertTrue(healthChecker.isHealthy(backends.get(0)));
    assertTrue(healthChecker.isHealthy(backends.get(1)));
  }

  @Test
  void testStop() {
    healthChecker.start(List.of("http://backend:8080"));

    // Should not throw
    healthChecker.stop();
  }
}
