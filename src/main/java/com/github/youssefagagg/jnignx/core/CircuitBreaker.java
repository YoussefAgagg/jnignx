package com.github.youssefagagg.jnignx.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker pattern implementation for fault tolerance.
 *
 * <p>Implements the circuit breaker pattern to prevent cascading failures
 * by quickly failing requests to unhealthy backends. Automatically recovers
 * when backends become healthy again.
 *
 * <p><b>States:</b>
 * <ul>
 *   <li><b>CLOSED:</b> Normal operation, all requests pass through</li>
 *   <li><b>OPEN:</b> Fast-fail mode, requests rejected immediately</li>
 *   <li><b>HALF_OPEN:</b> Testing mode, limited requests allowed to test recovery</li>
 * </ul>
 *
 * <p><b>Features:</b>
 * <ul>
 *   <li>Configurable failure threshold</li>
 *   <li>Automatic state transitions</li>
 *   <li>Time-based recovery</li>
 *   <li>Success rate monitoring</li>
 *   <li>Per-backend circuit breakers</li>
 *   <li>Virtual thread safe</li>
 * </ul>
 */
public final class CircuitBreaker {

  private final Map<String, BackendCircuit> circuits = new ConcurrentHashMap<>();
  private final int failureThreshold;
  private final Duration timeout;
  private final Duration resetTimeout;
  private final int halfOpenRequests;

  /**
   * Creates a circuit breaker with default settings.
   * - Failure threshold: 5 failures
   * - Timeout: 30 seconds
   * - Reset timeout: 60 seconds
   * - Half-open requests: 3
   */
  public CircuitBreaker() {
    this(5, Duration.ofSeconds(30), Duration.ofSeconds(60), 3);
  }

  /**
   * Creates a circuit breaker with custom settings.
   *
   * @param failureThreshold number of failures before opening circuit
   * @param timeout          time to wait before transitioning from OPEN to HALF_OPEN
   * @param resetTimeout     time to wait before resetting failure count in CLOSED state
   * @param halfOpenRequests number of requests to allow in HALF_OPEN state
   */
  public CircuitBreaker(int failureThreshold, Duration timeout, Duration resetTimeout,
                        int halfOpenRequests) {
    this.failureThreshold = failureThreshold;
    this.timeout = timeout;
    this.resetTimeout = resetTimeout;
    this.halfOpenRequests = halfOpenRequests;
  }

  /**
   * Checks if a request to the backend should be allowed.
   *
   * @param backend the backend URL
   * @return true if request is allowed, false if circuit is open
   */
  public boolean allowRequest(String backend) {
    BackendCircuit circuit = circuits.computeIfAbsent(backend, k -> new BackendCircuit());
    return circuit.allowRequest();
  }

  /**
   * Records a successful request to the backend.
   *
   * @param backend the backend URL
   */
  public void recordSuccess(String backend) {
    BackendCircuit circuit = circuits.get(backend);
    if (circuit != null) {
      circuit.recordSuccess();
    }
  }

  /**
   * Records a failed request to the backend.
   *
   * @param backend the backend URL
   */
  public void recordFailure(String backend) {
    BackendCircuit circuit = circuits.get(backend);
    if (circuit != null) {
      circuit.recordFailure();
    }
  }

  /**
   * Gets the current state of the circuit for a backend.
   *
   * @param backend the backend URL
   * @return the current circuit state
   */
  public State getState(String backend) {
    BackendCircuit circuit = circuits.get(backend);
    return circuit != null ? circuit.getState() : State.CLOSED;
  }

  /**
   * Gets statistics for a backend circuit.
   *
   * @param backend the backend URL
   * @return circuit statistics
   */
  public CircuitStats getStats(String backend) {
    BackendCircuit circuit = circuits.get(backend);
    return circuit != null ? circuit.getStats() : new CircuitStats(State.CLOSED, 0, 0, 0);
  }

  /**
   * Manually resets a circuit to CLOSED state.
   *
   * @param backend the backend URL
   */
  public void reset(String backend) {
    BackendCircuit circuit = circuits.get(backend);
    if (circuit != null) {
      circuit.reset();
    }
  }

  /**
   * Clears all circuit breakers.
   */
  public void clear() {
    circuits.clear();
  }

  /**
   * Executes an operation with circuit breaker protection.
   *
   * @param backend   the backend identifier
   * @param operation the operation to execute
   * @return the result of the operation
   * @throws CircuitOpenException if the circuit is open
   */
  public <T> T execute(String backend, ThrowingSupplier<T> operation) throws Exception {
    if (!allowRequest(backend)) {
      throw new CircuitOpenException("Circuit breaker is OPEN for backend: " + backend);
    }

    try {
      T result = operation.get();
      recordSuccess(backend);
      return result;
    } catch (Exception e) {
      recordFailure(backend);
      throw e;
    }
  }

  /**
   * Circuit breaker state.
   */
  public enum State {
    /**
     * Normal operation - requests pass through
     */
    CLOSED,
    /**
     * Fast-fail mode - requests rejected
     */
    OPEN,
    /**
     * Testing recovery - limited requests allowed
     */
    HALF_OPEN
  }

  /**
   * Functional interface for operations that may throw exceptions.
   */
  @FunctionalInterface
  public interface ThrowingSupplier<T> {
    T get() throws Exception;
  }

  /**
   * Circuit breaker statistics.
   */
  public record CircuitStats(
      State state,
      int failureCount,
      int successCount,
      int halfOpenRequestCount
  ) {
    /**
     * Gets the success rate (0.0 to 1.0).
     */
    public double successRate() {
      int total = successCount + failureCount;
      return total > 0 ? (double) successCount / total : 1.0;
    }

    /**
     * Gets the failure rate (0.0 to 1.0).
     */
    public double failureRate() {
      return 1.0 - successRate();
    }
  }

  /**
   * Exception thrown when circuit breaker is open.
   */
  public static class CircuitOpenException extends RuntimeException {
    public CircuitOpenException(String message) {
      super(message);
    }
  }

  /**
   * Circuit breaker for a single backend.
   */
  private final class BackendCircuit {
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenRequestCount = new AtomicInteger(0);
    private volatile Instant stateChangedAt = Instant.now();
    private volatile Instant lastFailureAt = Instant.now();

    boolean allowRequest() {
      State currentState = state.get();
      Instant now = Instant.now();

      switch (currentState) {
        case CLOSED:
          // Check if we should reset the failure count
          if (Duration.between(lastFailureAt, now).compareTo(resetTimeout) > 0) {
            failureCount.set(0);
          }
          return true;

        case OPEN:
          // Check if timeout has elapsed
          if (Duration.between(stateChangedAt, now).compareTo(timeout) > 0) {
            transitionTo(State.HALF_OPEN);
            return true;
          }
          return false;

        case HALF_OPEN:
          // Allow limited number of requests
          int count = halfOpenRequestCount.get();
          if (count < halfOpenRequests) {
            halfOpenRequestCount.incrementAndGet();
            return true;
          }
          return false;

        default:
          return true;
      }
    }

    void recordSuccess() {
      State currentState = state.get();
      successCount.incrementAndGet();

      if (currentState == State.HALF_OPEN) {
        // Check if we have enough successful requests to close the circuit
        if (halfOpenRequestCount.get() >= halfOpenRequests) {
          transitionTo(State.CLOSED);
          failureCount.set(0);
          halfOpenRequestCount.set(0);
        }
      } else if (currentState == State.CLOSED) {
        // Reset failure count on successful request
        failureCount.set(0);
      }
    }

    void recordFailure() {
      State currentState = state.get();
      lastFailureAt = Instant.now();
      int failures = failureCount.incrementAndGet();

      if (currentState == State.HALF_OPEN) {
        // Any failure in HALF_OPEN reopens the circuit
        transitionTo(State.OPEN);
        halfOpenRequestCount.set(0);
      } else if (currentState == State.CLOSED) {
        // Check if we've reached the threshold
        if (failures >= failureThreshold) {
          transitionTo(State.OPEN);
        }
      }
    }

    State getState() {
      return state.get();
    }

    CircuitStats getStats() {
      return new CircuitStats(
          state.get(),
          failureCount.get(),
          successCount.get(),
          halfOpenRequestCount.get()
      );
    }

    void reset() {
      transitionTo(State.CLOSED);
      failureCount.set(0);
      successCount.set(0);
      halfOpenRequestCount.set(0);
    }

    private void transitionTo(State newState) {
      State oldState = state.getAndSet(newState);
      if (oldState != newState) {
        stateChangedAt = Instant.now();
        System.out.println("[CircuitBreaker] State transition: " + oldState + " -> " + newState);
      }
    }
  }
}
