package com.github.youssefagagg.jnignx.util;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Metrics collector for exposing Prometheus-style metrics.
 *
 * <p>Tracks various server metrics:
 * <ul>
 *   <li>Total requests served</li>
 *   <li>Active connections</li>
 *   <li>Request duration histograms</li>
 *   <li>HTTP status code counts</li>
 *   <li>Bytes sent/received</li>
 * </ul>
 *
 * <p>Metrics can be exported in Prometheus text format via the /metrics endpoint.
 */
public final class MetricsCollector {

  // Duration buckets in milliseconds: 10ms, 50ms, 100ms, 500ms, 1s, 5s, 10s, +Inf
  private static final long[] DURATION_BUCKETS =
      {10, 50, 100, 500, 1000, 5000, 10000, Long.MAX_VALUE};

  private static final MetricsCollector INSTANCE = new MetricsCollector();

  private final LongAdder totalRequests = new LongAdder();
  private final AtomicLong activeConnections = new AtomicLong(0);
  private final LongAdder bytesReceived = new LongAdder();
  private final LongAdder bytesSent = new LongAdder();
  private final Map<Integer, LongAdder> statusCodeCounts = new ConcurrentHashMap<>();
  private final Map<String, LongAdder> pathCounts = new ConcurrentHashMap<>();
  private final LongAdder totalDurationMs = new LongAdder();
  private final Instant startTime = Instant.now();

  private final LongAdder[] durationBuckets = new LongAdder[DURATION_BUCKETS.length];

  private MetricsCollector() {
    for (int i = 0; i < durationBuckets.length; i++) {
      durationBuckets[i] = new LongAdder();
    }
  }

  public static MetricsCollector getInstance() {
    return INSTANCE;
  }

  /**
   * Records a completed HTTP request.
   *
   * @param statusCode the HTTP status code
   * @param durationMs the request duration in milliseconds
   * @param path       the request path
   * @param bytesIn    bytes received
   * @param bytesOut   bytes sent
   */
  public void recordRequest(int statusCode, long durationMs, String path, long bytesIn,
                            long bytesOut) {
    totalRequests.increment();
    statusCodeCounts.computeIfAbsent(statusCode, _ -> new LongAdder()).increment();
    pathCounts.computeIfAbsent(path, _ -> new LongAdder()).increment();
    totalDurationMs.add(durationMs);
    bytesReceived.add(bytesIn);
    bytesSent.add(bytesOut);

    // Record in histogram buckets
    for (int i = 0; i < DURATION_BUCKETS.length; i++) {
      if (durationMs <= DURATION_BUCKETS[i]) {
        durationBuckets[i].increment();
        break;
      }
    }
  }

  /**
   * Increments the active connection counter.
   */
  public void incrementActiveConnections() {
    activeConnections.incrementAndGet();
  }

  /**
   * Decrements the active connection counter.
   */
  public void decrementActiveConnections() {
    activeConnections.decrementAndGet();
  }

  /**
   * Gets the current number of active connections.
   */
  public long getActiveConnections() {
    return activeConnections.get();
  }

  /**
   * Gets the total number of requests served.
   */
  public long getTotalRequests() {
    return totalRequests.sum();
  }

  /**
   * Exports metrics in Prometheus text format.
   *
   * @return Prometheus-formatted metrics string
   */
  public String exportPrometheus() {
    StringBuilder sb = new StringBuilder();

    // Server uptime
    long uptimeSeconds = Duration.between(startTime, Instant.now()).getSeconds();
    sb.append("# HELP nanoserver_uptime_seconds Server uptime in seconds\n");
    sb.append("# TYPE nanoserver_uptime_seconds counter\n");
    sb.append("nanoserver_uptime_seconds ").append(uptimeSeconds).append("\n\n");

    // Total requests
    sb.append("# HELP nanoserver_requests_total Total number of HTTP requests\n");
    sb.append("# TYPE nanoserver_requests_total counter\n");
    sb.append("nanoserver_requests_total ").append(totalRequests.sum()).append("\n\n");

    // Active connections
    sb.append("# HELP nanoserver_active_connections Current number of active connections\n");
    sb.append("# TYPE nanoserver_active_connections gauge\n");
    sb.append("nanoserver_active_connections ").append(activeConnections.get()).append("\n\n");

    // Bytes transferred
    sb.append("# HELP nanoserver_bytes_received_total Total bytes received\n");
    sb.append("# TYPE nanoserver_bytes_received_total counter\n");
    sb.append("nanoserver_bytes_received_total ").append(bytesReceived.sum()).append("\n\n");

    sb.append("# HELP nanoserver_bytes_sent_total Total bytes sent\n");
    sb.append("# TYPE nanoserver_bytes_sent_total counter\n");
    sb.append("nanoserver_bytes_sent_total ").append(bytesSent.sum()).append("\n\n");

    // Status codes
    sb.append("# HELP nanoserver_requests_by_status HTTP requests by status code\n");
    sb.append("# TYPE nanoserver_requests_by_status counter\n");
    for (Map.Entry<Integer, LongAdder> entry : statusCodeCounts.entrySet()) {
      sb.append("nanoserver_requests_by_status{status=\"")
        .append(entry.getKey())
        .append("\"} ")
        .append(entry.getValue().sum())
        .append("\n");
    }
    sb.append("\n");

    // Request duration histogram
    sb.append("# HELP nanoserver_request_duration_ms Request duration in milliseconds\n");
    sb.append("# TYPE nanoserver_request_duration_ms histogram\n");
    for (int i = 0; i < DURATION_BUCKETS.length; i++) {
      String le =
          DURATION_BUCKETS[i] == Long.MAX_VALUE ? "+Inf" : String.valueOf(DURATION_BUCKETS[i]);
      sb.append("nanoserver_request_duration_ms_bucket{le=\"")
        .append(le)
        .append("\"} ")
        .append(durationBuckets[i].sum())
        .append("\n");
    }
    sb.append("nanoserver_request_duration_ms_sum ").append(totalDurationMs.sum()).append("\n");
    sb.append("nanoserver_request_duration_ms_count ").append(totalRequests.sum()).append("\n\n");

    // Top paths
    sb.append("# HELP nanoserver_requests_by_path HTTP requests by path\n");
    sb.append("# TYPE nanoserver_requests_by_path counter\n");
    pathCounts.entrySet().stream()
              .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
              .limit(20) // Top 20 paths
              .forEach(entry -> {
                String path = entry.getKey().replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append("nanoserver_requests_by_path{path=\"")
                  .append(path)
                  .append("\"} ")
                  .append(entry.getValue().sum())
                  .append("\n");
              });

    return sb.toString();
  }

  /**
   * Resets all metrics (useful for testing).
   */
  public void reset() {
    totalRequests.reset();
    activeConnections.set(0);
    bytesReceived.reset();
    bytesSent.reset();
    statusCodeCounts.clear();
    pathCounts.clear();
    totalDurationMs.reset();
    for (LongAdder bucket : durationBuckets) {
      bucket.reset();
    }
  }
}
