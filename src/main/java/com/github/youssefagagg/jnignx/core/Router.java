package com.github.youssefagagg.jnignx.core;

import com.github.youssefagagg.jnignx.config.ConfigLoader;
import com.github.youssefagagg.jnignx.config.RouteConfig;
import com.github.youssefagagg.jnignx.config.ServerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dynamic Router with hot-reload capability, advanced load balancing, and health checking.
 *
 * <p>The router monitors a configuration file (routes.json) for changes and
 * atomically swaps the routing table when updates are detected. This ensures
 * zero downtime during configuration changes.
 *
 * <p><b>Thread Safety:</b> Uses AtomicReference for lock-free configuration
 * swapping and supports multiple load balancing strategies with health checking.
 *
 * <p><b>Hot-Reload Mechanism:</b> A dedicated Virtual Thread monitors the
 * configuration file's last modified timestamp. When a change is detected,
 * the new configuration is parsed and atomically swapped in, ensuring active
 * requests continue with the old config while new requests use the updated one.
 */
public final class Router {

  private final AtomicReference<RouteConfig> configRef;
  private final AtomicReference<ServerConfig> serverConfigRef;
  private final Path configPath;
  private final Map<String, AtomicInteger> roundRobinCounters;
  private final HealthChecker healthChecker;
  private final LoadBalancer loadBalancer;
  private volatile boolean running;
  private volatile FileTime lastModified;

  /**
   * Creates a new Router with the specified configuration file path.
   *
   * @param configPath path to the routes.json configuration file
   */
  public Router(Path configPath) {
    this(configPath, LoadBalancer.Strategy.ROUND_ROBIN);
  }

  /**
   * Creates a new Router with the specified configuration file path and load balancing strategy.
   *
   * @param configPath path to the routes.json configuration file
   * @param lbStrategy the load balancing strategy to use
   */
  public Router(Path configPath, LoadBalancer.Strategy lbStrategy) {
    this.configPath = configPath;
    this.configRef = new AtomicReference<>(RouteConfig.empty());
    this.serverConfigRef = new AtomicReference<>(ServerConfig.builder().build());
    this.roundRobinCounters = new ConcurrentHashMap<>();
    this.healthChecker = new HealthChecker();
    this.loadBalancer = new LoadBalancer(lbStrategy, healthChecker);
    this.running = true;
    this.lastModified = null;
  }

  /**
   * Gets the current server configuration.
   *
   * @return the current ServerConfig
   */
  public ServerConfig getServerConfig() {
    return serverConfigRef.get();
  }

  /**
   * Loads the initial configuration from the config file.
   * Must be called before starting the hot-reload watcher.
   *
   * @throws IOException if the configuration file cannot be read
   */
  public void loadConfig() throws IOException {
    if (Files.exists(configPath)) {
      // Try to load enhanced ServerConfig first
      try {
        ServerConfig serverConfig = ConfigLoader.loadServerConfig(configPath);
        serverConfigRef.set(serverConfig);
        configRef.set(serverConfig.toRouteConfig());
        lastModified = Files.getLastModifiedTime(configPath);
        System.out.println("[Router] Loaded enhanced configuration from " + configPath);
        System.out.println("[Router] Routes: " + serverConfig.routes().keySet());
        System.out.println("[Router] Rate Limiter: " +
                               (serverConfig.rateLimiterEnabled() ? "enabled" : "disabled"));
        System.out.println("[Router] Circuit Breaker: " +
                               (serverConfig.circuitBreakerEnabled() ? "enabled" : "disabled"));
        System.out.println(
            "[Router] CORS: " + (serverConfig.corsConfig().isEnabled() ? "enabled" : "disabled"));
        System.out.println("[Router] Admin Auth: " +
                               (serverConfig.adminAuth().isEnabled() ? "enabled" : "disabled"));
      } catch (Exception e) {
        // Fallback to simple RouteConfig for backward compatibility
        System.out.println(
            "[Router] Enhanced config parse failed, using simple config: " + e.getMessage());
        RouteConfig newConfig = ConfigLoader.load(configPath);
        configRef.set(newConfig);
        lastModified = Files.getLastModifiedTime(configPath);
        System.out.println("[Router] Loaded simple configuration from " + configPath);
        System.out.println("[Router] Routes: " + newConfig.routes().keySet());
      }

      // Start health checking for all backends
      List<String> allBackends = new ArrayList<>();
      for (List<String> backends : configRef.get().routes().values()) {
        for (String backend : backends) {
          if (!allBackends.contains(backend)) {
            allBackends.add(backend);
            healthChecker.registerBackend(backend);
          }
        }
      }
      healthChecker.start(allBackends);
    } else {
      System.out.println(
          "[Router] Config file not found, using empty configuration: " + configPath);
    }
  }

  /**
   * Starts the hot-reload watcher in a dedicated Virtual Thread.
   * The watcher monitors the configuration file for changes every second
   * and atomically updates the routing configuration when changes are detected.
   */
  public void startHotReloadWatcher() {
    Thread.startVirtualThread(() -> {
      System.out.println("[Router] Hot-reload watcher started for: " + configPath);
      while (running) {
        try {
          Thread.sleep(1000); // Check every second
          checkAndReload();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        } catch (Exception e) {
          System.err.println("[Router] Error checking configuration: " + e.getMessage());
        }
      }
      System.out.println("[Router] Hot-reload watcher stopped");
    });
  }

  /**
   * Checks if the configuration file has been modified and reloads it.
   * Uses atomic swap to ensure thread-safe configuration updates.
   */
  private void checkAndReload() throws IOException {
    if (!Files.exists(configPath)) {
      return;
    }

    FileTime currentModified = Files.getLastModifiedTime(configPath);
    if (lastModified == null || currentModified.compareTo(lastModified) > 0) {
      RouteConfig newConfig = ConfigLoader.load(configPath);

      // Atomic swap - ensures thread safety for active requests
      RouteConfig oldConfig = configRef.getAndSet(newConfig);
      lastModified = currentModified;

      // Reset round-robin counters for changed routes
      roundRobinCounters.clear();

      System.out.println("[Router] Configuration reloaded!");
      System.out.println(
          "[Router] Old routes: " + (oldConfig != null ? oldConfig.routes().keySet() : "none"));
      System.out.println("[Router] New routes: " + newConfig.routes().keySet());
    }
  }

  /**
   * Resolves a request path to a backend URL using the configured load balancing strategy.
   *
   * <p>This method is thread-safe and uses the configured LoadBalancer for
   * backend selection with automatic health checking.
   *
   * @param path the request path to route
   * @param clientIp the client IP address (used for IP hash load balancing)
   * @return the selected backend URL, or null if no route matches
   */
  public String resolveBackend(String path, String clientIp) {
    RouteConfig config = configRef.get();
    List<String> backends = config.getBackends(path);

    if (backends == null || backends.isEmpty()) {
      return null;
    }

    return loadBalancer.selectBackend(path, backends, clientIp);
  }

  /**
   * Resolves a request path to a backend URL (without client IP for backward compatibility).
   *
   * @param path the request path to route
   * @return the selected backend URL, or null if no route matches
   */
  public String resolveBackend(String path) {
    return resolveBackend(path, null);
  }

  /**
   * Records that a connection has been established to a backend.
   *
   * @param backend the backend URL
   */
  public void recordConnectionStart(String backend) {
    loadBalancer.recordConnectionStart(backend);
  }

  /**
   * Records that a connection to a backend has ended.
   *
   * @param backend the backend URL
   */
  public void recordConnectionEnd(String backend) {
    loadBalancer.recordConnectionEnd(backend);
  }

  /**
   * Records a successful proxy request (passive health check).
   *
   * @param backend the backend URL
   */
  public void recordProxySuccess(String backend) {
    healthChecker.recordProxySuccess(backend);
  }

  /**
   * Records a failed proxy request (passive health check).
   *
   * @param backend the backend URL
   * @param error   the error message
   */
  public void recordProxyFailure(String backend, String error) {
    healthChecker.recordProxyFailure(backend, error);
  }

  /**
   * Gets the health checker instance.
   *
   * @return the HealthChecker
   */
  public HealthChecker getHealthChecker() {
    return healthChecker;
  }

  /**
   * Gets the load balancer instance.
   *
   * @return the LoadBalancer
   */
  public LoadBalancer getLoadBalancer() {
    return loadBalancer;
  }

  /**
   * Returns the current route configuration (for debugging/monitoring).
   *
   * @return the current RouteConfig
   */
  public RouteConfig getCurrentConfig() {
    return configRef.get();
  }

  /**
   * Stops the hot-reload watcher and health checker gracefully.
   */
  public void stop() {
    running = false;
    healthChecker.stop();
  }
}
