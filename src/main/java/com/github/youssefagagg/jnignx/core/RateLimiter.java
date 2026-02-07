package com.github.youssefagagg.jnignx.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter with multiple strategies for request throttling.
 *
 * <p>Implements token bucket and sliding window algorithms for flexible
 * rate limiting. Supports per-IP, per-path, and global rate limits.
 *
 * <p><b>Features:</b>
 * <ul>
 *   <li>Token bucket algorithm</li>
 *   <li>Sliding window counter</li>
 *   <li>Fixed window counter</li>
 *   <li>Per-client IP limiting</li>
 *   <li>Per-path limiting</li>
 *   <li>Rate limit response headers (X-RateLimit-*)</li>
 *   <li>Automatic cleanup of expired entries</li>
 *   <li>Virtual thread compatible</li>
 * </ul>
 */
public final class RateLimiter {

  private final Strategy strategy;
  private final int maxRequests;
  private final Duration window;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final ScheduledExecutorService cleaner;
  private final AtomicLong totalRejected = new AtomicLong(0);

  /**
   * Creates a rate limiter with the specified strategy.
   *
   * @param strategy    the rate limiting strategy
   * @param maxRequests maximum requests allowed per window
   * @param window      the time window duration
   */
  public RateLimiter(Strategy strategy, int maxRequests, Duration window) {
    this.strategy = strategy;
    this.maxRequests = maxRequests;
    this.window = window;

    // Schedule periodic cleanup of expired entries
    this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "RateLimiter-Cleaner");
      t.setDaemon(true);
      return t;
    });

    cleaner.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.MINUTES);
  }

  /**
   * Checks if a request should be allowed.
   *
   * @param clientIp the client IP address
   * @return true if the request is allowed, false if rate limited
   */
  public boolean allowRequest(String clientIp) {
    return allowRequest(clientIp, null);
  }

  /**
   * Checks if a request should be allowed for a specific path.
   *
   * @param clientIp the client IP address
   * @param path     the request path
   * @return true if the request is allowed, false if rate limited
   */
  public boolean allowRequest(String clientIp, String path) {
    String key = path != null ? clientIp + ":" + path : clientIp;
    Bucket bucket = buckets.computeIfAbsent(key, k -> createBucket());

    boolean allowed = bucket.tryConsume();
    if (!allowed) {
      totalRejected.incrementAndGet();
    }
    return allowed;
  }

  /**
   * Gets the current number of requests for a client.
   */
  public int getCurrentRequests(String clientIp) {
    Bucket bucket = buckets.get(clientIp);
    return bucket != null ? bucket.getCount() : 0;
  }

  /**
   * Gets the time until the next request is allowed.
   */
  public Duration getRetryAfter(String clientIp) {
    Bucket bucket = buckets.get(clientIp);
    return bucket != null ? bucket.getRetryAfter() : Duration.ZERO;
  }

  /**
   * Gets rate limit info for response headers.
   *
   * @param clientIp the client IP address
   * @param path     the request path (may be null)
   * @return RateLimitInfo with limit, remaining, and reset information
   */
  public RateLimitInfo getRateLimitInfo(String clientIp, String path) {
    String key = path != null ? clientIp + ":" + path : clientIp;
    Bucket bucket = buckets.get(key);
    if (bucket == null) {
      return new RateLimitInfo(maxRequests, maxRequests, 0);
    }
    int count = bucket.getCount();
    int remaining = Math.max(0, maxRequests - count);
    long resetSeconds = bucket.getRetryAfter().toSeconds();
    return new RateLimitInfo(maxRequests, remaining, resetSeconds);
  }

  /**
   * Gets the maximum requests per window.
   */
  public int getMaxRequests() {
    return maxRequests;
  }

  /**
   * Gets the current strategy.
   */
  public Strategy getStrategy() {
    return strategy;
  }

  /**
   * Gets the window duration.
   */
  public Duration getWindow() {
    return window;
  }

  /**
   * Gets the number of active client entries being tracked.
   */
  public int getActiveClientCount() {
    return buckets.size();
  }

  /**
   * Gets the total number of rejected requests.
   */
  public long getTotalRejected() {
    return totalRejected.get();
  }

  /**
   * Cleans up expired entries.
   */
  private void cleanup() {
    Instant now = Instant.now();
    buckets.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
  }

  /**
   * Resets all rate limit state.
   */
  public void reset() {
    buckets.clear();
    totalRejected.set(0);
  }

  /**
   * Creates a bucket based on the strategy.
   */
  private Bucket createBucket() {
    return switch (strategy) {
      case TOKEN_BUCKET -> new TokenBucket(maxRequests, window);
      case SLIDING_WINDOW -> new SlidingWindowBucket(maxRequests, window);
      case FIXED_WINDOW -> new FixedWindowBucket(maxRequests, window);
    };
  }

  /**
   * Shuts down the rate limiter.
   */
  public void shutdown() {
    cleaner.shutdown();
  }

  /**
   * Rate limit information for response headers.
   */
  public record RateLimitInfo(int limit, int remaining, long resetSeconds) {
  }

  /**
   * Rate limiting strategies.
   */
  public enum Strategy {
    /**
     * Token bucket algorithm - smooth rate limiting
     */
    TOKEN_BUCKET,
    /**
     * Sliding window counter - precise time-based limiting
     */
    SLIDING_WINDOW,
    /**
     * Fixed window counter - simple time-based limiting
     */
    FIXED_WINDOW
  }

  /**
   * Base interface for rate limiting buckets.
   */
  private interface Bucket {
    boolean tryConsume();

    int getCount();

    Duration getRetryAfter();

    boolean isExpired(Instant now);
  }

  /**
   * Token bucket implementation.
   * Tokens are added at a fixed rate and consumed per request.
   */
  private static final class TokenBucket implements Bucket {
    private final int capacity;
    private final long refillIntervalNanos;
    private final AtomicInteger tokens;
    private volatile long lastRefillTime;

    TokenBucket(int capacity, Duration window) {
      this.capacity = capacity;
      if (capacity > 0) {
        this.refillIntervalNanos = window.toNanos() / capacity;
        this.tokens = new AtomicInteger(capacity);
      } else {
        this.refillIntervalNanos = Long.MAX_VALUE;
        this.tokens = new AtomicInteger(0);
      }
      this.lastRefillTime = System.nanoTime();
    }

    @Override
    public boolean tryConsume() {
      refill();
      return tokens.getAndUpdate(current -> current > 0 ? current - 1 : 0) > 0;
    }

    private void refill() {
      long now = System.nanoTime();
      long elapsed = now - lastRefillTime;
      int tokensToAdd = (int) (elapsed / refillIntervalNanos);

      if (tokensToAdd > 0) {
        tokens.updateAndGet(current -> Math.min(capacity, current + tokensToAdd));
        lastRefillTime = now;
      }
    }

    @Override
    public int getCount() {
      return capacity - tokens.get();
    }

    @Override
    public Duration getRetryAfter() {
      long nanosUntilNext = refillIntervalNanos - (System.nanoTime() - lastRefillTime);
      return Duration.ofNanos(Math.max(0, nanosUntilNext));
    }

    @Override
    public boolean isExpired(Instant now) {
      return Duration.ofNanos(System.nanoTime() - lastRefillTime).toMinutes() > 5;
    }
  }

  /**
   * Sliding window implementation.
   * Tracks request timestamps in a sliding time window.
   */
  private static final class SlidingWindowBucket implements Bucket {
    private final int maxRequests;
    private final Duration window;
    private final ConcurrentHashMap<Long, AtomicInteger> slots = new ConcurrentHashMap<>();

    SlidingWindowBucket(int maxRequests, Duration window) {
      this.maxRequests = maxRequests;
      this.window = window;
    }

    @Override
    public boolean tryConsume() {
      long now = System.currentTimeMillis();
      long windowStart = now - window.toMillis();

      // Remove old slots
      slots.keySet().removeIf(time -> time < windowStart);

      // Count requests in current window
      int count = slots.values().stream()
                       .mapToInt(AtomicInteger::get)
                       .sum();

      if (count >= maxRequests) {
        return false;
      }

      // Add current request
      slots.computeIfAbsent(now, k -> new AtomicInteger(0)).incrementAndGet();
      return true;
    }

    @Override
    public int getCount() {
      long windowStart = System.currentTimeMillis() - window.toMillis();
      return slots.entrySet().stream()
                  .filter(e -> e.getKey() >= windowStart)
                  .mapToInt(e -> e.getValue().get())
                  .sum();
    }

    @Override
    public Duration getRetryAfter() {
      if (slots.isEmpty()) {
        return Duration.ZERO;
      }

      long oldest = slots.keySet().stream().min(Long::compare).orElse(0L);
      long windowStart = oldest + window.toMillis();
      long now = System.currentTimeMillis();

      return Duration.ofMillis(Math.max(0, windowStart - now));
    }

    @Override
    public boolean isExpired(Instant now) {
      long cutoff = now.toEpochMilli() - window.toMillis() - Duration.ofMinutes(5).toMillis();
      return slots.keySet().stream().allMatch(time -> time < cutoff);
    }
  }

  /**
   * Fixed window implementation.
   * Simple counter that resets after each time window.
   */
  private static final class FixedWindowBucket implements Bucket {
    private final int maxRequests;
    private final Duration window;
    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile long windowStart;

    FixedWindowBucket(int maxRequests, Duration window) {
      this.maxRequests = maxRequests;
      this.window = window;
      this.windowStart = System.currentTimeMillis();
    }

    @Override
    public boolean tryConsume() {
      long now = System.currentTimeMillis();

      // Reset window if expired
      if (now - windowStart >= window.toMillis()) {
        synchronized (this) {
          if (now - windowStart >= window.toMillis()) {
            counter.set(0);
            windowStart = now;
          }
        }
      }

      return counter.getAndIncrement() < maxRequests;
    }

    @Override
    public int getCount() {
      return counter.get();
    }

    @Override
    public Duration getRetryAfter() {
      long now = System.currentTimeMillis();
      long windowEnd = windowStart + window.toMillis();
      return Duration.ofMillis(Math.max(0, windowEnd - now));
    }

    @Override
    public boolean isExpired(Instant now) {
      return now.toEpochMilli() - windowStart >
          window.toMillis() + Duration.ofMinutes(5).toMillis();
    }
  }
}
