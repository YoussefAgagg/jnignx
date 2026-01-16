package com.github.youssefagagg.jnignx.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

  @Test
  void testInitialStateClosed() {
    CircuitBreaker cb = new CircuitBreaker();
    String backend = "http://backend:8080";

    // Initially, circuit should be closed (allow requests)
    assertTrue(cb.allowRequest(backend), "Initial state should be CLOSED");
  }

  @Test
  void testCircuitOpensAfterFailures() {
    CircuitBreaker cb = new CircuitBreaker(
        3,  // 3 failures threshold
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        2
    );
    String backend = "http://backend:8080";

    // Should allow requests initially
    assertTrue(cb.allowRequest(backend));

    // Record failures
    cb.recordFailure(backend);
    cb.recordFailure(backend);
    cb.recordFailure(backend);

    // Circuit should now be OPEN
    assertFalse(cb.allowRequest(backend), "Circuit should be OPEN after 3 failures");
  }

  @Test
  void testCircuitStaysClosedOnSuccess() {
    CircuitBreaker cb = new CircuitBreaker(3, Duration.ofSeconds(1), Duration.ofSeconds(5), 2);
    String backend = "http://backend:8080";

    // Record some failures but not enough to open
    cb.recordFailure(backend);
    cb.recordFailure(backend);

    // Record success
    cb.recordSuccess(backend);

    // Should still allow requests
    assertTrue(cb.allowRequest(backend), "Circuit should remain CLOSED");
  }

  @Test
  void testCircuitTransitionsToHalfOpen() throws InterruptedException {
    CircuitBreaker cb = new CircuitBreaker(
        2,  // 2 failures threshold
        Duration.ofMillis(100),  // Short timeout for testing
        Duration.ofSeconds(5),
        2
    );
    String backend = "http://backend:8080";

    // Open the circuit
    cb.recordFailure(backend);
    cb.recordFailure(backend);
    assertFalse(cb.allowRequest(backend), "Circuit should be OPEN");

    // Wait for timeout
    Thread.sleep(150);

    // Should transition to HALF_OPEN and allow limited requests
    assertTrue(cb.allowRequest(backend), "Circuit should be HALF_OPEN");
  }

  @Test
  void testHalfOpenTransitionsToClosedOnSuccess() throws InterruptedException {
    CircuitBreaker cb = new CircuitBreaker(
        2,
        Duration.ofMillis(100),
        Duration.ofSeconds(5),
        2
    );
    String backend = "http://backend:8080";

    // Open the circuit
    cb.recordFailure(backend);
    cb.recordFailure(backend);

    // Wait for HALF_OPEN
    Thread.sleep(150);

    // Allow request and record success
    assertTrue(cb.allowRequest(backend));
    cb.recordSuccess(backend);

    assertTrue(cb.allowRequest(backend));
    cb.recordSuccess(backend);

    // Circuit should now be CLOSED
    assertTrue(cb.allowRequest(backend), "Circuit should be CLOSED after successes");
  }

  @Test
  void testHalfOpenTransitionsBackToOpenOnFailure() throws InterruptedException {
    CircuitBreaker cb = new CircuitBreaker(
        2,
        Duration.ofMillis(100),
        Duration.ofSeconds(5),
        2
    );
    String backend = "http://backend:8080";

    // Open the circuit
    cb.recordFailure(backend);
    cb.recordFailure(backend);

    // Wait for HALF_OPEN
    Thread.sleep(150);

    // Allow request but record failure
    assertTrue(cb.allowRequest(backend));
    cb.recordFailure(backend);

    // Circuit should be OPEN again
    assertFalse(cb.allowRequest(backend), "Circuit should reopen on HALF_OPEN failure");
  }

  @Test
  void testMultipleBackendsIndependent() {
    CircuitBreaker cb = new CircuitBreaker(2, Duration.ofSeconds(1), Duration.ofSeconds(5), 2);
    String backend1 = "http://backend1:8080";
    String backend2 = "http://backend2:8080";

    // Open circuit for backend1
    cb.recordFailure(backend1);
    cb.recordFailure(backend1);
    assertFalse(cb.allowRequest(backend1));

    // Backend2 should still work
    assertTrue(cb.allowRequest(backend2), "Backend2 should be independent");
  }

  @Test
  void testGetState() {
    CircuitBreaker cb = new CircuitBreaker(2, Duration.ofSeconds(1), Duration.ofSeconds(5), 2);
    String backend = "http://backend:8080";

    // Initial state
    assertEquals(CircuitBreaker.State.CLOSED, cb.getState(backend));

    // Open circuit
    cb.recordFailure(backend);
    cb.recordFailure(backend);
    assertEquals(CircuitBreaker.State.OPEN, cb.getState(backend));
  }

  @Test
  void testSuccessResetsFailureCount() {
    CircuitBreaker cb = new CircuitBreaker(3, Duration.ofSeconds(1), Duration.ofSeconds(5), 2);
    String backend = "http://backend:8080";

    // Record 2 failures
    cb.recordFailure(backend);
    cb.recordFailure(backend);

    // Record success - should reset counter
    cb.recordSuccess(backend);

    // Should be able to handle more failures before opening
    cb.recordFailure(backend);
    cb.recordFailure(backend);

    // Circuit should still be closed (only 2 failures after reset)
    assertTrue(cb.allowRequest(backend), "Success should reset failure count");
  }

  @Test
  void testGetStats() {
    CircuitBreaker cb = new CircuitBreaker();
    String backend = "http://backend:8080";

    cb.recordSuccess(backend);
    cb.recordSuccess(backend);
    cb.recordFailure(backend);

    CircuitBreaker.CircuitStats stats = cb.getStats(backend);
    assertNotNull(stats);
    assertEquals(CircuitBreaker.State.CLOSED, stats.state());
    assertTrue(stats.successCount() > 0);
  }

  @Test
  void testConcurrentAccess() throws InterruptedException {
    CircuitBreaker cb = new CircuitBreaker(100, Duration.ofSeconds(10), Duration.ofSeconds(10), 10);
    String backend = "http://backend:8080";

    int numThreads = 10;
    Thread[] threads = new Thread[numThreads];

    for (int i = 0; i < numThreads; i++) {
      threads[i] = new Thread(() -> {
        for (int j = 0; j < 100; j++) {
          cb.allowRequest(backend);
          if (j % 2 == 0) {
            cb.recordSuccess(backend);
          } else {
            cb.recordFailure(backend);
          }
        }
      });
      threads[i].start();
    }

    for (Thread thread : threads) {
      thread.join();
    }

    // Should not throw any exceptions
    assertDoesNotThrow(() -> cb.getState(backend));
  }
}
