package com.github.youssefagagg.jnignx.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Load balancing strategies for distributing requests across multiple backends.
 *
 * <p>Supports multiple algorithms:
 * <ul>
 *   <li><b>Round Robin:</b> Distributes requests evenly in a circular fashion</li>
 *   <li><b>Least Connections:</b> Sends requests to the backend with fewest active connections</li>
 *   <li><b>IP Hash:</b> Consistent hashing for sticky sessions based on client IP</li>
 * </ul>
 */
public final class LoadBalancer {

  private final Strategy strategy;
  private final Map<String, AtomicInteger> roundRobinCounters;
  private final Map<String, AtomicLong> connectionCounts;
  private final HealthChecker healthChecker;
  public LoadBalancer(Strategy strategy, HealthChecker healthChecker) {
    this.strategy = strategy;
    this.roundRobinCounters = new ConcurrentHashMap<>();
    this.connectionCounts = new ConcurrentHashMap<>();
    this.healthChecker = healthChecker;
  }

  /**
   * Selects a backend URL from the list based on the configured strategy.
   *
   * @param path     the request path (used for grouping counters)
   * @param backends list of backend URLs
   * @param clientIp client IP address (used for IP hash)
   * @return the selected backend URL, or null if no healthy backend available
   */
  public String selectBackend(String path, List<String> backends, String clientIp) {
    if (backends == null || backends.isEmpty()) {
      return null;
    }

    // Filter healthy backends
    List<String> healthyBackends = backends.stream()
                                           .filter(healthChecker::isHealthy)
                                           .toList();

    if (healthyBackends.isEmpty()) {
      // No healthy backends, try any backend as fallback
      System.err.println("[LoadBalancer] No healthy backends available for " + path);
      healthyBackends = backends;
    }

    if (healthyBackends.size() == 1) {
      return healthyBackends.getFirst();
    }

    return switch (strategy) {
      case ROUND_ROBIN -> selectRoundRobin(path, healthyBackends);
      case LEAST_CONNECTIONS -> selectLeastConnections(healthyBackends);
      case IP_HASH -> selectIpHash(clientIp, healthyBackends);
    };
  }

  /**
   * Round-robin selection: cycles through backends in order.
   */
  private String selectRoundRobin(String path, List<String> backends) {
    AtomicInteger counter = roundRobinCounters.computeIfAbsent(path, _ -> new AtomicInteger(0));
    int index = Math.abs(counter.getAndIncrement() % backends.size());
    return backends.get(index);
  }

  /**
   * Least connections selection: sends to backend with fewest active connections.
   */
  private String selectLeastConnections(List<String> backends) {
    String selected = null;
    long minConnections = Long.MAX_VALUE;

    for (String backend : backends) {
      long connections = connectionCounts.getOrDefault(backend, new AtomicLong(0)).get();
      if (connections < minConnections) {
        minConnections = connections;
        selected = backend;
      }
    }

    return selected != null ? selected : backends.getFirst();
  }

  /**
   * IP hash selection: consistent hashing based on client IP for sticky sessions.
   */
  private String selectIpHash(String clientIp, List<String> backends) {
    if (clientIp == null || clientIp.isEmpty()) {
      return backends.getFirst();
    }

    // Simple hash-based selection
    int hash = Math.abs(clientIp.hashCode());
    int index = hash % backends.size();
    return backends.get(index);
  }

  /**
   * Records that a connection has been opened to a backend.
   * Used for least connections tracking.
   *
   * @param backend the backend URL
   */
  public void recordConnectionStart(String backend) {
    connectionCounts.computeIfAbsent(backend, _ -> new AtomicLong(0)).incrementAndGet();
  }

  /**
   * Records that a connection to a backend has been closed.
   * Used for least connections tracking.
   *
   * @param backend the backend URL
   */
  public void recordConnectionEnd(String backend) {
    AtomicLong count = connectionCounts.get(backend);
    if (count != null) {
      count.decrementAndGet();
    }
  }

  /**
   * Gets the current connection count for a backend.
   *
   * @param backend the backend URL
   * @return the number of active connections
   */
  public long getConnectionCount(String backend) {
    AtomicLong count = connectionCounts.get(backend);
    return count != null ? count.get() : 0;
  }

  /**
   * Gets all connection counts.
   *
   * @return map of backend URLs to connection counts
   */
  public Map<String, Long> getAllConnectionCounts() {
    Map<String, Long> result = new ConcurrentHashMap<>();
    for (Map.Entry<String, AtomicLong> entry : connectionCounts.entrySet()) {
      result.put(entry.getKey(), entry.getValue().get());
    }
    return result;
  }

  /**
   * Load balancing strategy enum.
   */
  public enum Strategy {
    ROUND_ROBIN,
    LEAST_CONNECTIONS,
    IP_HASH
  }
}
