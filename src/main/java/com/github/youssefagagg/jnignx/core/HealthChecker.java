package com.github.youssefagagg.jnignx.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Health checker for backend servers.
 *
 * <p>Performs active health checks by periodically sending HTTP requests to backends
 * and tracking their health status. Unhealthy backends are automatically removed from
 * the load balancing pool until they recover.
 *
 * <p><b>Features:</b>
 * <ul>
 *   <li><b>Active Checks:</b> Periodic HTTP GET requests to verify backend availability</li>
 *   <li><b>Passive Checks:</b> Track error rates from actual proxy requests</li>
 *   <li><b>Automatic Recovery:</b> Unhealthy backends are periodically re-checked</li>
 *   <li><b>Circuit Breaker:</b> Fast failure for known-bad backends</li>
 * </ul>
 */
public final class HealthChecker {

  private static final int CHECK_INTERVAL_MS = 10_000; // 10 seconds
  private static final int TIMEOUT_MS = 5_000; // 5 seconds
  private static final int FAILURE_THRESHOLD = 3; // Mark unhealthy after 3 failures
  private static final int SUCCESS_THRESHOLD = 2; // Mark healthy after 2 successes

  private final Map<String, BackendHealth> healthMap;
  private volatile boolean running;

  public HealthChecker() {
    this.healthMap = new ConcurrentHashMap<>();
    this.running = true;
  }

  /**
   * Starts the health check loop in a virtual thread.
   *
   * @param backends list of backend URLs to monitor
   */
  public void start(List<String> backends) {
    // Initialize health entries
    for (String backend : backends) {
      healthMap.putIfAbsent(backend, new BackendHealth());
    }

    // Start health check loop
    Thread.startVirtualThread(() -> {
      System.out.println("[HealthChecker] Started monitoring " + backends.size() + " backends");
      while (running) {
        try {
          Thread.sleep(CHECK_INTERVAL_MS);
          checkAllBackends();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      System.out.println("[HealthChecker] Stopped");
    });
  }

  /**
   * Performs health checks on all registered backends.
   */
  private void checkAllBackends() {
    for (Map.Entry<String, BackendHealth> entry : healthMap.entrySet()) {
      String backend = entry.getKey();
      BackendHealth health = entry.getValue();

      // Skip file:// backends
      if (backend.startsWith("file://")) {
        continue;
      }

      Thread.startVirtualThread(() -> checkBackend(backend, health));
    }
  }

  /**
   * Performs a single health check on a backend.
   *
   * @param backendUrl the backend URL to check
   * @param health     the health tracker for this backend
   */
  private void checkBackend(String backendUrl, BackendHealth health) {
    try {
      URI uri = URI.create(backendUrl);
      String host = uri.getHost();
      int port = uri.getPort() != -1 ? uri.getPort() : 80;

      try (SocketChannel channel = SocketChannel.open()) {
        channel.socket().setSoTimeout(TIMEOUT_MS);
        channel.connect(new InetSocketAddress(host, port));

        // Send simple HEAD request
        String request = "HEAD / HTTP/1.1\r\n" +
            "Host: " + host + "\r\n" +
            "Connection: close\r\n" +
            "\r\n";

        ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }

        // Read response
        buffer = ByteBuffer.allocate(512);
        int bytesRead = channel.read(buffer);

        if (bytesRead > 0) {
          buffer.flip();
          String response = StandardCharsets.UTF_8.decode(buffer).toString();

          // Check for 2xx or 3xx status
          if (response.contains("HTTP/1.1 2") || response.contains("HTTP/1.1 3")) {
            health.recordSuccess();
            System.out.println("[HealthChecker] ✓ " + backendUrl + " is healthy");
          } else {
            health.recordFailure("Non-2xx/3xx response");
            System.out.println("[HealthChecker] ✗ " + backendUrl + " returned error status");
          }
        } else {
          health.recordFailure("No response");
          System.out.println("[HealthChecker] ✗ " + backendUrl + " did not respond");
        }
      }
    } catch (IOException e) {
      health.recordFailure(e.getMessage());
      System.out.println("[HealthChecker] ✗ " + backendUrl + " failed: " + e.getMessage());
    }
  }

  /**
   * Records a successful proxy request to a backend (passive health check).
   *
   * @param backendUrl the backend URL
   */
  public void recordProxySuccess(String backendUrl) {
    BackendHealth health = healthMap.get(backendUrl);
    if (health != null) {
      health.recordSuccess();
    }
  }

  /**
   * Records a failed proxy request to a backend (passive health check).
   *
   * @param backendUrl the backend URL
   * @param error      the error message
   */
  public void recordProxyFailure(String backendUrl, String error) {
    BackendHealth health = healthMap.get(backendUrl);
    if (health != null) {
      health.recordFailure(error);
    }
  }

  /**
   * Checks if a backend is currently healthy.
   *
   * @param backendUrl the backend URL to check
   * @return true if healthy, false otherwise
   */
  public boolean isHealthy(String backendUrl) {
    BackendHealth health = healthMap.get(backendUrl);
    return health == null || health.isHealthy();
  }

  /**
   * Gets the health status for a backend.
   *
   * @param backendUrl the backend URL
   * @return the BackendHealth object, or null if not tracked
   */
  public BackendHealth getHealth(String backendUrl) {
    return healthMap.get(backendUrl);
  }

  /**
   * Registers a new backend for health checking.
   *
   * @param backendUrl the backend URL to register
   */
  public void registerBackend(String backendUrl) {
    healthMap.putIfAbsent(backendUrl, new BackendHealth());
  }

  /**
   * Stops the health checker.
   */
  public void stop() {
    running = false;
  }

  /**
   * Gets all backend health statuses.
   *
   * @return map of backend URLs to their health status
   */
  public Map<String, BackendHealth> getAllHealth() {
    return Map.copyOf(healthMap);
  }

  /**
   * Represents the health status of a backend server.
   */
  public static final class BackendHealth {
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);
    private volatile boolean healthy = true;
    private volatile Instant lastCheck = Instant.now();
    private volatile String lastError = null;

    public boolean isHealthy() {
      return healthy;
    }

    public int getConsecutiveFailures() {
      return consecutiveFailures.get();
    }

    public String getLastError() {
      return lastError;
    }

    public Instant getLastCheck() {
      return lastCheck;
    }

    void recordSuccess() {
      consecutiveSuccesses.incrementAndGet();
      consecutiveFailures.set(0);
      lastError = null;
      lastCheck = Instant.now();

      if (consecutiveSuccesses.get() >= SUCCESS_THRESHOLD) {
        healthy = true;
      }
    }

    void recordFailure(String error) {
      consecutiveFailures.incrementAndGet();
      consecutiveSuccesses.set(0);
      lastError = error;
      lastCheck = Instant.now();

      if (consecutiveFailures.get() >= FAILURE_THRESHOLD) {
        healthy = false;
      }
    }
  }
}
