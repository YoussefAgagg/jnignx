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
 *   <li><b>Active Checks:</b> Periodic HTTP requests to verify backend availability</li>
 *   <li><b>Passive Checks:</b> Track error rates from actual proxy requests</li>
 *   <li><b>Automatic Recovery:</b> Unhealthy backends are periodically re-checked</li>
 *   <li><b>Configurable Health Check Path:</b> Custom health endpoint (e.g., /healthz)</li>
 *   <li><b>Expected Status Codes:</b> Configurable acceptable response codes</li>
 *   <li><b>Config Integration:</b> Reads parameters from ServerConfig</li>
 * </ul>
 */
public final class HealthChecker {

  // Defaults (used when no config provided)
  private static final int DEFAULT_CHECK_INTERVAL_MS = 10_000;
  private static final int DEFAULT_TIMEOUT_MS = 5_000;
  private static final int DEFAULT_FAILURE_THRESHOLD = 3;
  private static final int DEFAULT_SUCCESS_THRESHOLD = 2;
  private static final String DEFAULT_HEALTH_CHECK_PATH = "/";
  private static final int DEFAULT_EXPECTED_STATUS_MIN = 200;
  private static final int DEFAULT_EXPECTED_STATUS_MAX = 399;

  private final Map<String, BackendHealth> healthMap;
  private volatile boolean running;

  // Configurable parameters
  private int checkIntervalMs;
  private int timeoutMs;
  private int failureThreshold;
  private int successThreshold;
  private String healthCheckPath;
  private int expectedStatusMin;
  private int expectedStatusMax;

  public HealthChecker() {
    this.healthMap = new ConcurrentHashMap<>();
    this.running = true;
    this.checkIntervalMs = DEFAULT_CHECK_INTERVAL_MS;
    this.timeoutMs = DEFAULT_TIMEOUT_MS;
    this.failureThreshold = DEFAULT_FAILURE_THRESHOLD;
    this.successThreshold = DEFAULT_SUCCESS_THRESHOLD;
    this.healthCheckPath = DEFAULT_HEALTH_CHECK_PATH;
    this.expectedStatusMin = DEFAULT_EXPECTED_STATUS_MIN;
    this.expectedStatusMax = DEFAULT_EXPECTED_STATUS_MAX;
  }

  /**
   * Configures health check parameters.
   *
   * @param intervalSeconds  check interval in seconds
   * @param timeoutSeconds   connection timeout in seconds
   * @param failureThreshold consecutive failures to mark unhealthy
   * @param successThreshold consecutive successes to mark healthy
   */
  public void configure(int intervalSeconds, int timeoutSeconds,
                        int failureThreshold, int successThreshold) {
    this.checkIntervalMs = intervalSeconds * 1000;
    this.timeoutMs = timeoutSeconds * 1000;
    this.failureThreshold = failureThreshold;
    this.successThreshold = successThreshold;
  }

  /**
   * Gets the configured health check path.
   *
   * @return the health check path
   */
  public String getHealthCheckPath() {
    return healthCheckPath;
  }

  /**
   * Sets the health check path.
   *
   * @param path the path to check (e.g., "/healthz", "/ready")
   */
  public void setHealthCheckPath(String path) {
    if (path != null && !path.isBlank()) {
      this.healthCheckPath = path.startsWith("/") ? path : "/" + path;
    }
  }

  /**
   * Sets the expected status code range for healthy responses.
   *
   * @param min minimum acceptable status code (inclusive)
   * @param max maximum acceptable status code (inclusive)
   */
  public void setExpectedStatusRange(int min, int max) {
    this.expectedStatusMin = min;
    this.expectedStatusMax = max;
  }

  /**
   * Gets the expected status code range.
   *
   * @return array with [min, max] status codes
   */
  public int[] getExpectedStatusRange() {
    return new int[] {expectedStatusMin, expectedStatusMax};
  }

  /**
   * Starts the health check loop in a virtual thread.
   *
   * @param backends list of backend URLs to monitor
   */
  public void start(List<String> backends) {
    // Initialize health entries
    for (String backend : backends) {
      healthMap.putIfAbsent(backend, new BackendHealth(failureThreshold, successThreshold));
    }

    // Start health check loop
    Thread.startVirtualThread(() -> {
      System.out.println("[HealthChecker] Started monitoring " + backends.size() + " backends");
      System.out.println("[HealthChecker] Health check path: " + healthCheckPath);
      System.out.println("[HealthChecker] Expected status: " + expectedStatusMin + "-" +
                             expectedStatusMax);
      while (running) {
        try {
          Thread.sleep(checkIntervalMs);
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
        channel.socket().setSoTimeout(timeoutMs);
        channel.connect(new InetSocketAddress(host, port));

        // Send HEAD request to configured path
        String request = "HEAD " + healthCheckPath + " HTTP/1.1\r\n" +
            "Host: " + host + "\r\n" +
            "Connection: close\r\n" +
            "User-Agent: JNignx-HealthChecker/1.0\r\n" +
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

          // Parse status code from response
          int statusCode = parseStatusCode(response);
          if (statusCode >= expectedStatusMin && statusCode <= expectedStatusMax) {
            health.recordSuccess();
            System.out.println(
                "[HealthChecker] ✓ " + backendUrl + " is healthy (status: " + statusCode + ")");
          } else {
            health.recordFailure("Unexpected status code: " + statusCode);
            System.out.println(
                "[HealthChecker] ✗ " + backendUrl + " returned status " + statusCode);
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
   * Parses HTTP status code from response string.
   */
  private int parseStatusCode(String response) {
    // Response format: "HTTP/1.1 200 OK\r\n..."
    try {
      int spaceIdx = response.indexOf(' ');
      if (spaceIdx >= 0) {
        int nextSpace = response.indexOf(' ', spaceIdx + 1);
        if (nextSpace < 0) {
          nextSpace = response.indexOf('\r', spaceIdx + 1);
        }
        if (nextSpace > spaceIdx) {
          return Integer.parseInt(response.substring(spaceIdx + 1, nextSpace).trim());
        }
      }
    } catch (NumberFormatException ignored) {
    }
    return -1; // Unknown
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
    healthMap.putIfAbsent(backendUrl, new BackendHealth(failureThreshold, successThreshold));
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
    private final int failureThreshold;
    private final int successThreshold;

    public BackendHealth() {
      this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_SUCCESS_THRESHOLD);
    }

    public BackendHealth(int failureThreshold, int successThreshold) {
      this.failureThreshold = failureThreshold;
      this.successThreshold = successThreshold;
    }

    public boolean isHealthy() {
      return healthy;
    }

    public int getConsecutiveFailures() {
      return consecutiveFailures.get();
    }

    public int getConsecutiveSuccesses() {
      return consecutiveSuccesses.get();
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

      if (consecutiveSuccesses.get() >= successThreshold) {
        healthy = true;
      }
    }

    void recordFailure(String error) {
      consecutiveFailures.incrementAndGet();
      consecutiveSuccesses.set(0);
      lastError = error;
      lastCheck = Instant.now();

      if (consecutiveFailures.get() >= failureThreshold) {
        healthy = false;
      }
    }
  }
}
