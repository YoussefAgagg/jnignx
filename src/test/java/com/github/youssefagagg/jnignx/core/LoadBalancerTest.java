package com.github.youssefagagg.jnignx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoadBalancerTest {

  private HealthChecker healthChecker;
  private List<String> backends;

  @BeforeEach
  void setUp() {
    healthChecker = new HealthChecker();
    backends = Arrays.asList(
        "http://backend1:8080",
        "http://backend2:8080",
        "http://backend3:8080"
    );
  }

  @Test
  void testRoundRobinDistribution() {
    LoadBalancer lb = new LoadBalancer(LoadBalancer.Strategy.ROUND_ROBIN, healthChecker);

    // Should cycle through backends in order
    String first = lb.selectBackend("/test", backends, "192.168.1.1");
    String second = lb.selectBackend("/test", backends, "192.168.1.1");
    String third = lb.selectBackend("/test", backends, "192.168.1.1");
    String fourth = lb.selectBackend("/test", backends, "192.168.1.1");

    assertNotNull(first);
    assertNotNull(second);
    assertNotNull(third);
    assertNotNull(fourth);

    // Fourth should be same as first (cycling)
    assertEquals(first, fourth);

    // All three should be different in the first cycle
    assertNotEquals(first, second);
    assertNotEquals(second, third);
    assertNotEquals(first, third);
  }

  @Test
  void testLeastConnectionsStrategy() {
    LoadBalancer lb = new LoadBalancer(LoadBalancer.Strategy.LEAST_CONNECTIONS, healthChecker);

    String backend1 = lb.selectBackend("/test", backends, "192.168.1.1");
    assertNotNull(backend1);

    // Simulate connection
    lb.recordConnectionStart(backend1);

    // Next selection should prefer a different backend
    String backend2 = lb.selectBackend("/test", backends, "192.168.1.2");
    assertNotNull(backend2);

    // Clean up
    lb.recordConnectionEnd(backend1);
  }

  @Test
  void testIpHashStrategy() {
    LoadBalancer lb = new LoadBalancer(LoadBalancer.Strategy.IP_HASH, healthChecker);

    String ip1 = "192.168.1.1";
    String ip2 = "192.168.1.2";

    // Same IP should get same backend
    String backend1 = lb.selectBackend("/test", backends, ip1);
    String backend2 = lb.selectBackend("/test", backends, ip1);
    String backend3 = lb.selectBackend("/test", backends, ip1);

    assertEquals(backend1, backend2);
    assertEquals(backend2, backend3);

    // Different IP might get different backend
    String backend4 = lb.selectBackend("/test", backends, ip2);
    assertNotNull(backend4);
  }

  @Test
  void testEmptyBackendList() {
    LoadBalancer lb = new LoadBalancer(LoadBalancer.Strategy.ROUND_ROBIN, healthChecker);

    String result = lb.selectBackend("/test", List.of(), "192.168.1.1");
    assertNull(result);
  }

  @Test
  void testNullBackendList() {
    LoadBalancer lb = new LoadBalancer(LoadBalancer.Strategy.ROUND_ROBIN, healthChecker);

    String result = lb.selectBackend("/test", null, "192.168.1.1");
    assertNull(result);
  }

  @Test
  void testSingleBackend() {
    LoadBalancer lb = new LoadBalancer(LoadBalancer.Strategy.ROUND_ROBIN, healthChecker);
    List<String> singleBackend = List.of("http://backend:8080");

    String result1 = lb.selectBackend("/test", singleBackend, "192.168.1.1");
    String result2 = lb.selectBackend("/test", singleBackend, "192.168.1.2");

    assertEquals(singleBackend.get(0), result1);
    assertEquals(singleBackend.get(0), result2);
  }

  @Test
  void testDifferentPathsRoundRobin() {
    LoadBalancer lb = new LoadBalancer(LoadBalancer.Strategy.ROUND_ROBIN, healthChecker);

    // Different paths should have independent counters
    String path1Backend1 = lb.selectBackend("/path1", backends, "192.168.1.1");
    String path2Backend1 = lb.selectBackend("/path2", backends, "192.168.1.1");

    assertNotNull(path1Backend1);
    assertNotNull(path2Backend1);
  }

  @Test
  void testAllBackendsUnhealthy() {
    LoadBalancer lb = new LoadBalancer(LoadBalancer.Strategy.ROUND_ROBIN, healthChecker);

    // Mark all backends as unhealthy by recording failures
    for (String backend : backends) {
      for (int i = 0; i < 5; i++) {
        healthChecker.recordProxyFailure(backend, "Test failure");
      }
    }

    // Should still return a backend as fallback
    String result = lb.selectBackend("/test", backends, "192.168.1.1");
    assertNotNull(result);
    assertTrue(backends.contains(result));
  }

  @Test
  void testConnectionCounting() {
    LoadBalancer lb = new LoadBalancer(LoadBalancer.Strategy.LEAST_CONNECTIONS, healthChecker);
    String backend = backends.get(0);

    lb.recordConnectionStart(backend);
    lb.recordConnectionStart(backend);

    assertEquals(2, lb.getConnectionCount(backend));

    lb.recordConnectionEnd(backend);
    assertEquals(1, lb.getConnectionCount(backend));
  }

  @Test
  void testGetAllConnectionCounts() {
    LoadBalancer lb = new LoadBalancer(LoadBalancer.Strategy.LEAST_CONNECTIONS, healthChecker);

    lb.recordConnectionStart(backends.get(0));
    lb.recordConnectionStart(backends.get(1));
    lb.recordConnectionStart(backends.get(1));

    var counts = lb.getAllConnectionCounts();
    assertNotNull(counts);
    assertEquals(1L, counts.getOrDefault(backends.get(0), 0L));
    assertEquals(2L, counts.getOrDefault(backends.get(1), 0L));
  }
}
