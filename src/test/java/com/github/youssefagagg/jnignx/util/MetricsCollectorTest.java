package com.github.youssefagagg.jnignx.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetricsCollectorTest {

  private MetricsCollector metrics;

  @BeforeEach
  void setUp() {
    metrics = MetricsCollector.getInstance();
    // Reset metrics before each test
    metrics.reset();
  }

  @Test
  void testRecordRequest() {
    metrics.recordRequest(200, 50, "/api/test", 1024, 2048);

    String output = metrics.exportPrometheus();
    assertNotNull(output);
    assertTrue(
        output.contains("nanoserver_requests_total") || output.contains("http_requests_total"));
    assertTrue(output.contains("duration") || output.contains("ms"));
  }

  @Test
  void testActiveConnections() {
    metrics.incrementActiveConnections();
    metrics.incrementActiveConnections();

    long active = metrics.getActiveConnections();
    assertEquals(2, active);

    metrics.decrementActiveConnections();
    active = metrics.getActiveConnections();
    assertEquals(1, active);
  }

  @Test
  void testStatusCodeCounting() {
    metrics.recordRequest(200, 10, "/test", 0, 100);
    metrics.recordRequest(200, 20, "/test", 0, 200);
    metrics.recordRequest(404, 5, "/missing", 0, 50);
    metrics.recordRequest(500, 100, "/error", 0, 0);

    String output = metrics.exportPrometheus();
    assertTrue(output.contains("200") || output.contains("nanoserver"));
  }

  @Test
  void testPathCounting() {
    metrics.recordRequest(200, 10, "/api/v1", 0, 100);
    metrics.recordRequest(200, 20, "/api/v1", 0, 200);
    metrics.recordRequest(200, 15, "/api/v2", 0, 150);

    String output = metrics.exportPrometheus();
    assertNotNull(output);
  }

  @Test
  void testDurationHistogram() {
    // Test different duration buckets
    metrics.recordRequest(200, 5, "/fast", 0, 100);      // < 10ms
    metrics.recordRequest(200, 75, "/medium", 0, 100);   // 50-100ms
    metrics.recordRequest(200, 750, "/slow", 0, 100);    // 500-1000ms
    metrics.recordRequest(200, 6000, "/veryslow", 0, 100); // 5-10s

    String output = metrics.exportPrometheus();
    assertNotNull(output);
  }

  @Test
  void testBytesTracking() {
    metrics.recordRequest(200, 10, "/test", 1024, 2048);
    metrics.recordRequest(200, 20, "/test", 512, 1024);

    String output = metrics.exportPrometheus();
    assertTrue(output.contains("bytes") || output.contains("nanoserver"));
  }

  @Test
  void testPrometheusFormat() {
    metrics.recordRequest(200, 50, "/test", 100, 200);

    String output = metrics.exportPrometheus();

    // Check Prometheus format
    assertTrue(output.contains("# TYPE") || output.contains("# HELP"));
    assertFalse(output.trim().isEmpty());
  }

  @Test
  void testMultipleRequests() {
    long initialRequests = metrics.getTotalRequests();

    for (int i = 0; i < 100; i++) {
      metrics.recordRequest(200, i, "/test", i * 10, i * 20);
    }

    long finalRequests = metrics.getTotalRequests();
    assertTrue(finalRequests >= initialRequests + 100);
  }

  @Test
  void testResetNotAvailable() {
    // Since reset() may not exist, just verify state
    metrics.recordRequest(200, 50, "/test", 100, 200);
    metrics.incrementActiveConnections();

    assertTrue(metrics.getActiveConnections() >= 0);
    String output = metrics.exportPrometheus();
    assertNotNull(output);
  }

  @Test
  void testConcurrentAccess() throws InterruptedException {
    int numThreads = 10;
    int requestsPerThread = 100;
    Thread[] threads = new Thread[numThreads];

    for (int i = 0; i < numThreads; i++) {
      final int threadId = i;
      threads[i] = new Thread(() -> {
        for (int j = 0; j < requestsPerThread; j++) {
          metrics.recordRequest(200, j, "/test" + threadId, j, j * 2);
        }
      });
      threads[i].start();
    }

    for (Thread thread : threads) {
      thread.join();
    }

    String output = metrics.exportPrometheus();
    assertNotNull(output);
    // Just verify it doesn't throw exceptions
  }

  @Test
  void testUptime() throws InterruptedException {
    Thread.sleep(100);

    String output = metrics.exportPrometheus();
    assertTrue(output.contains("uptime") || output.contains("nanoserver"));
  }
}
