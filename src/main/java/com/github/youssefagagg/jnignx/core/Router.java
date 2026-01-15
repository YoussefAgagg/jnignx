package com.github.youssefagagg.jnignx.core;

import com.github.youssefagagg.jnignx.config.ConfigLoader;
import com.github.youssefagagg.jnignx.config.RouteConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dynamic Router with hot-reload capability and round-robin load balancing.
 *
 * <p>The router monitors a configuration file (routes.json) for changes and
 * atomically swaps the routing table when updates are detected. This ensures
 * zero downtime during configuration changes.
 *
 * <p><b>Thread Safety:</b> Uses AtomicReference for lock-free configuration
 * swapping and AtomicInteger counters for high-throughput round-robin load
 * balancing across multiple backends.
 *
 * <p><b>Hot-Reload Mechanism:</b> A dedicated Virtual Thread monitors the
 * configuration file's last modified timestamp. When a change is detected,
 * the new configuration is parsed and atomically swapped in, ensuring active
 * requests continue with the old config while new requests use the updated one.
 */
public final class Router {

  private final AtomicReference<RouteConfig> configRef;
  private final Path configPath;
  private final Map<String, AtomicInteger> roundRobinCounters;
  private volatile boolean running;
  private volatile FileTime lastModified;

  /**
   * Creates a new Router with the specified configuration file path.
   *
   * @param configPath path to the routes.json configuration file
   */
  public Router(Path configPath) {
    this.configPath = configPath;
    this.configRef = new AtomicReference<>(RouteConfig.empty());
    this.roundRobinCounters = new ConcurrentHashMap<>();
    this.running = true;
    this.lastModified = null;
  }

  /**
   * Loads the initial configuration from the config file.
   * Must be called before starting the hot-reload watcher.
   *
   * @throws IOException if the configuration file cannot be read
   */
  public void loadConfig() throws IOException {
    if (Files.exists(configPath)) {
      RouteConfig newConfig = ConfigLoader.load(configPath);
      configRef.set(newConfig);
      lastModified = Files.getLastModifiedTime(configPath);
      System.out.println("[Router] Loaded configuration from " + configPath);
      System.out.println("[Router] Routes: " + newConfig.routes().keySet());
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
   * Resolves a request path to a backend URL using round-robin load balancing.
   *
   * <p>This method is thread-safe and lock-free, using AtomicInteger for
   * high-throughput counter increments across concurrent requests.
   *
   * @param path the request path to route
   * @return the selected backend URL, or null if no route matches
   */
  public String resolveBackend(String path) {
    RouteConfig config = configRef.get();
    List<String> backends = config.getBackends(path);

    if (backends == null || backends.isEmpty()) {
      return null;
    }

    if (backends.size() == 1) {
      return backends.getFirst();
    }

    // Round-robin load balancing using AtomicInteger for high throughput
    AtomicInteger counter = roundRobinCounters.computeIfAbsent(path, _ -> new AtomicInteger(0));
    int index = Math.abs(counter.getAndIncrement() % backends.size());
    return backends.get(index);
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
   * Stops the hot-reload watcher gracefully.
   */
  public void stop() {
    running = false;
  }
}
