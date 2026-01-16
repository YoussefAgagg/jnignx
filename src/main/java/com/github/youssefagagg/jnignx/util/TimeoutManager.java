package com.github.youssefagagg.jnignx.util;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Timeout manager for connection and request timeouts.
 *
 * <p>Manages various timeout scenarios:
 * <ul>
 *   <li><b>Connection Timeout:</b> Maximum time to establish backend connection</li>
 *   <li><b>Request Timeout:</b> Maximum time for entire request/response cycle</li>
 *   <li><b>Idle Timeout:</b> Maximum time a connection can be idle</li>
 *   <li><b>Keep-Alive Timeout:</b> HTTP keep-alive connection timeout</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * TimeoutManager manager = TimeoutManager.create()
 *     .connectionTimeout(Duration.ofSeconds(5))
 *     .requestTimeout(Duration.ofSeconds(30))
 *     .idleTimeout(Duration.ofMinutes(5))
 *     .build();
 *
 * // Register a timeout
 * String id = manager.registerTimeout(
 *     TimeoutType.REQUEST,
 *     () -&gt; closeConnection()
 * );
 *
 * // Cancel if completed before timeout
 * manager.cancelTimeout(id);
 * </pre>
 */
public final class TimeoutManager {

  private final Duration connectionTimeout;
  private final Duration requestTimeout;
  private final Duration idleTimeout;
  private final Duration keepAliveTimeout;
  private final ScheduledExecutorService scheduler;
  private final Map<String, ScheduledFuture<?>> timeouts;
  private final Map<String, Long> timeoutStartTimes;

  private TimeoutManager(Builder builder) {
    this.connectionTimeout = builder.connectionTimeout;
    this.requestTimeout = builder.requestTimeout;
    this.idleTimeout = builder.idleTimeout;
    this.keepAliveTimeout = builder.keepAliveTimeout;
    this.scheduler = Executors.newScheduledThreadPool(
        builder.schedulerThreads,
        r -> {
          Thread t = new Thread(r, "timeout-manager");
          t.setDaemon(true);
          return t;
        }
    );
    this.timeouts = new ConcurrentHashMap<>();
    this.timeoutStartTimes = new ConcurrentHashMap<>();
  }

  /**
   * Creates a new builder.
   */
  public static Builder create() {
    return new Builder();
  }

  /**
   * Creates a TimeoutManager with default settings.
   */
  public static TimeoutManager defaults() {
    return new Builder().build();
  }

  /**
   * Creates a TimeoutManager with production settings.
   */
  public static TimeoutManager production() {
    return new Builder()
        .connectionTimeout(Duration.ofSeconds(10))
        .requestTimeout(Duration.ofSeconds(60))
        .idleTimeout(Duration.ofMinutes(10))
        .keepAliveTimeout(Duration.ofMinutes(5))
        .schedulerThreads(4)
        .build();
  }

  /**
   * Registers a timeout with a callback.
   *
   * @param type     the timeout type
   * @param callback the callback to execute on timeout
   * @return a unique timeout ID for cancellation
   */
  public String registerTimeout(TimeoutType type, Runnable callback) {
    Duration duration = getDuration(type);
    if (duration == null || duration.isZero() || duration.isNegative()) {
      return null; // Timeout disabled
    }

    String id = generateTimeoutId();
    timeoutStartTimes.put(id, System.currentTimeMillis());

    ScheduledFuture<?> future = scheduler.schedule(
        () -> {
          timeouts.remove(id);
          timeoutStartTimes.remove(id);

          try {
            callback.run();
          } catch (Exception e) {
            System.err.println("[TimeoutManager] Error in timeout callback: " + e.getMessage());
          }
        },
        duration.toMillis(),
        TimeUnit.MILLISECONDS
    );

    timeouts.put(id, future);
    return id;
  }

  /**
   * Cancels a registered timeout.
   *
   * @param id the timeout ID
   * @return true if cancelled, false if not found or already executed
   */
  public boolean cancelTimeout(String id) {
    if (id == null) {
      return false;
    }

    ScheduledFuture<?> future = timeouts.remove(id);
    timeoutStartTimes.remove(id);

    if (future != null) {
      return future.cancel(false);
    }
    return false;
  }

  /**
   * Gets elapsed time for a timeout.
   *
   * @param id the timeout ID
   * @return elapsed milliseconds, or -1 if not found
   */
  public long getElapsedTime(String id) {
    Long startTime = timeoutStartTimes.get(id);
    if (startTime == null) {
      return -1;
    }
    return System.currentTimeMillis() - startTime;
  }

  /**
   * Gets remaining time for a timeout.
   *
   * @param id   the timeout ID
   * @param type the timeout type
   * @return remaining milliseconds, or -1 if not found
   */
  public long getRemainingTime(String id, TimeoutType type) {
    Duration duration = getDuration(type);
    if (duration == null) {
      return -1;
    }

    long elapsed = getElapsedTime(id);
    if (elapsed == -1) {
      return -1;
    }

    return duration.toMillis() - elapsed;
  }

  /**
   * Gets the number of active timeouts.
   */
  public int getActiveTimeoutCount() {
    return timeouts.size();
  }

  /**
   * Gets timeout configuration for a type.
   */
  public Duration getTimeout(TimeoutType type) {
    return getDuration(type);
  }

  /**
   * Shuts down the timeout manager.
   */
  public void shutdown() {
    // Cancel all pending timeouts
    timeouts.values().forEach(future -> future.cancel(false));
    timeouts.clear();
    timeoutStartTimes.clear();

    // Shutdown scheduler
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private Duration getDuration(TimeoutType type) {
    return switch (type) {
      case CONNECTION -> connectionTimeout;
      case REQUEST -> requestTimeout;
      case IDLE -> idleTimeout;
      case KEEP_ALIVE -> keepAliveTimeout;
    };
  }

  private String generateTimeoutId() {
    return "timeout-" + System.nanoTime() + "-" + Thread.currentThread().threadId();
  }

  /**
   * Timeout types.
   */
  public enum TimeoutType {
    /**
     * Timeout for establishing backend connection
     */
    CONNECTION,
    /**
     * Timeout for entire request/response cycle
     */
    REQUEST,
    /**
     * Timeout for idle connections
     */
    IDLE,
    /**
     * HTTP keep-alive timeout
     */
    KEEP_ALIVE
  }

  /**
   * Builder for TimeoutManager.
   */
  public static class Builder {
    private Duration connectionTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(30);
    private Duration idleTimeout = Duration.ofMinutes(5);
    private Duration keepAliveTimeout = Duration.ofMinutes(2);
    private int schedulerThreads = 2;

    /**
     * Sets the connection timeout.
     */
    public Builder connectionTimeout(Duration timeout) {
      if (timeout == null || timeout.isNegative()) {
        throw new IllegalArgumentException("Connection timeout must be positive");
      }
      this.connectionTimeout = timeout;
      return this;
    }

    /**
     * Sets the request timeout.
     */
    public Builder requestTimeout(Duration timeout) {
      if (timeout == null || timeout.isNegative()) {
        throw new IllegalArgumentException("Request timeout must be positive");
      }
      this.requestTimeout = timeout;
      return this;
    }

    /**
     * Sets the idle timeout.
     */
    public Builder idleTimeout(Duration timeout) {
      if (timeout == null || timeout.isNegative()) {
        throw new IllegalArgumentException("Idle timeout must be positive");
      }
      this.idleTimeout = timeout;
      return this;
    }

    /**
     * Sets the keep-alive timeout.
     */
    public Builder keepAliveTimeout(Duration timeout) {
      if (timeout == null || timeout.isNegative()) {
        throw new IllegalArgumentException("Keep-alive timeout must be positive");
      }
      this.keepAliveTimeout = timeout;
      return this;
    }

    /**
     * Sets the number of scheduler threads.
     */
    public Builder schedulerThreads(int threads) {
      if (threads <= 0) {
        throw new IllegalArgumentException("Scheduler threads must be positive");
      }
      this.schedulerThreads = threads;
      return this;
    }

    /**
     * Builds the TimeoutManager.
     */
    public TimeoutManager build() {
      return new TimeoutManager(this);
    }
  }
}
