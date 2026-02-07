package com.github.youssefagagg.jnignx.config;

import com.github.youssefagagg.jnignx.http.CorsConfig;
import com.github.youssefagagg.jnignx.security.AdminAuth;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Complete server configuration with all production features.
 *
 * <p>This replaces the simple RouteConfig and includes:
 * <ul>
 *   <li>Route mappings</li>
 *   <li>Load balancer configuration</li>
 *   <li>Rate limiter configuration</li>
 *   <li>Circuit breaker configuration</li>
 *   <li>Health check configuration</li>
 *   <li>CORS configuration</li>
 *   <li>Admin authentication configuration</li>
 *   <li>Timeout configuration</li>
 *   <li>Buffer/request limits</li>
 * </ul>
 */
public final class ServerConfig {

  private final Map<String, List<String>> routes;

  // Domain-based routing: domain -> backends
  private final Map<String, List<String>> domainRoutes;

  // Load Balancer
  private final String loadBalancerAlgorithm;
  private final Map<String, Integer> backendWeights;

  // Rate Limiter
  private final boolean rateLimiterEnabled;
  private final int rateLimitRequestsPerSecond;
  private final int rateLimitBurstSize;
  private final String rateLimitStrategy;

  // Circuit Breaker
  private final boolean circuitBreakerEnabled;
  private final int circuitBreakerFailureThreshold;
  private final int circuitBreakerTimeoutSeconds;

  // Health Check
  private final boolean healthCheckEnabled;
  private final int healthCheckIntervalSeconds;
  private final int healthCheckTimeoutSeconds;
  private final int healthCheckFailureThreshold;
  private final int healthCheckSuccessThreshold;
  private final String healthCheckPath;
  private final int healthCheckExpectedStatusMin;
  private final int healthCheckExpectedStatusMax;

  // CORS
  private final CorsConfig corsConfig;

  // Admin API
  private final boolean adminEnabled;
  private final AdminAuth adminAuth;

  // Timeouts
  private final Duration connectionTimeout;
  private final Duration requestTimeout;
  private final Duration idleTimeout;
  private final Duration keepAliveTimeout;

  // Request/Response Limits
  private final long maxRequestSize;
  private final long maxResponseSize;
  private final int bufferSize;

  private ServerConfig(Builder builder) {
    this.routes = Map.copyOf(builder.routes);
    this.domainRoutes = Map.copyOf(builder.domainRoutes);
    this.loadBalancerAlgorithm = builder.loadBalancerAlgorithm;
    this.backendWeights = Map.copyOf(builder.backendWeights);

    this.rateLimiterEnabled = builder.rateLimiterEnabled;
    this.rateLimitRequestsPerSecond = builder.rateLimitRequestsPerSecond;
    this.rateLimitBurstSize = builder.rateLimitBurstSize;
    this.rateLimitStrategy = builder.rateLimitStrategy;

    this.circuitBreakerEnabled = builder.circuitBreakerEnabled;
    this.circuitBreakerFailureThreshold = builder.circuitBreakerFailureThreshold;
    this.circuitBreakerTimeoutSeconds = builder.circuitBreakerTimeoutSeconds;

    this.healthCheckEnabled = builder.healthCheckEnabled;
    this.healthCheckIntervalSeconds = builder.healthCheckIntervalSeconds;
    this.healthCheckTimeoutSeconds = builder.healthCheckTimeoutSeconds;
    this.healthCheckFailureThreshold = builder.healthCheckFailureThreshold;
    this.healthCheckSuccessThreshold = builder.healthCheckSuccessThreshold;
    this.healthCheckPath = builder.healthCheckPath;
    this.healthCheckExpectedStatusMin = builder.healthCheckExpectedStatusMin;
    this.healthCheckExpectedStatusMax = builder.healthCheckExpectedStatusMax;

    this.corsConfig = builder.corsConfig;
    this.adminEnabled = builder.adminEnabled;
    this.adminAuth = builder.adminAuth;

    this.connectionTimeout = builder.connectionTimeout;
    this.requestTimeout = builder.requestTimeout;
    this.idleTimeout = builder.idleTimeout;
    this.keepAliveTimeout = builder.keepAliveTimeout;

    this.maxRequestSize = builder.maxRequestSize;
    this.maxResponseSize = builder.maxResponseSize;
    this.bufferSize = builder.bufferSize;
  }

  public static Builder builder() {
    return new Builder();
  }

  // Getters
  public Map<String, List<String>> routes() {
    return routes;
  }

  public Map<String, List<String>> domainRoutes() {
    return domainRoutes;
  }

  public String loadBalancerAlgorithm() {
    return loadBalancerAlgorithm;
  }

  public Map<String, Integer> backendWeights() {
    return backendWeights;
  }

  public boolean rateLimiterEnabled() {
    return rateLimiterEnabled;
  }

  public int rateLimitRequestsPerSecond() {
    return rateLimitRequestsPerSecond;
  }

  public int rateLimitBurstSize() {
    return rateLimitBurstSize;
  }

  public String rateLimitStrategy() {
    return rateLimitStrategy;
  }

  public boolean circuitBreakerEnabled() {
    return circuitBreakerEnabled;
  }

  public int circuitBreakerFailureThreshold() {
    return circuitBreakerFailureThreshold;
  }

  public int circuitBreakerTimeoutSeconds() {
    return circuitBreakerTimeoutSeconds;
  }

  public boolean healthCheckEnabled() {
    return healthCheckEnabled;
  }

  public int healthCheckIntervalSeconds() {
    return healthCheckIntervalSeconds;
  }

  public int healthCheckTimeoutSeconds() {
    return healthCheckTimeoutSeconds;
  }

  public int healthCheckFailureThreshold() {
    return healthCheckFailureThreshold;
  }

  public int healthCheckSuccessThreshold() {
    return healthCheckSuccessThreshold;
  }

  public String healthCheckPath() {
    return healthCheckPath;
  }

  public int healthCheckExpectedStatusMin() {
    return healthCheckExpectedStatusMin;
  }

  public int healthCheckExpectedStatusMax() {
    return healthCheckExpectedStatusMax;
  }

  public CorsConfig corsConfig() {
    return corsConfig;
  }

  public boolean adminEnabled() {
    return adminEnabled;
  }

  public AdminAuth adminAuth() {
    return adminAuth;
  }

  public Duration connectionTimeout() {
    return connectionTimeout;
  }

  public Duration requestTimeout() {
    return requestTimeout;
  }

  public Duration idleTimeout() {
    return idleTimeout;
  }

  public Duration keepAliveTimeout() {
    return keepAliveTimeout;
  }

  public long maxRequestSize() {
    return maxRequestSize;
  }

  public long maxResponseSize() {
    return maxResponseSize;
  }

  public int bufferSize() {
    return bufferSize;
  }

  /**
   * Converts to legacy RouteConfig for backward compatibility.
   */
  public RouteConfig toRouteConfig() {
    return new RouteConfig(routes);
  }

  public static class Builder {
    private Map<String, List<String>> routes = Map.of();
    private Map<String, List<String>> domainRoutes = Map.of();

    // Defaults
    private String loadBalancerAlgorithm = "round-robin";
    private Map<String, Integer> backendWeights = Map.of();

    private boolean rateLimiterEnabled = false;
    private int rateLimitRequestsPerSecond = 1000;
    private int rateLimitBurstSize = 2000;
    private String rateLimitStrategy = "token-bucket";

    private boolean circuitBreakerEnabled = false;
    private int circuitBreakerFailureThreshold = 5;
    private int circuitBreakerTimeoutSeconds = 30;

    private boolean healthCheckEnabled = true;
    private int healthCheckIntervalSeconds = 10;
    private int healthCheckTimeoutSeconds = 5;
    private int healthCheckFailureThreshold = 3;
    private int healthCheckSuccessThreshold = 2;
    private String healthCheckPath = "/";
    private int healthCheckExpectedStatusMin = 200;
    private int healthCheckExpectedStatusMax = 399;

    private CorsConfig corsConfig = CorsConfig.disabled();
    private boolean adminEnabled = false;
    private AdminAuth adminAuth = new AdminAuth();

    private Duration connectionTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(30);
    private Duration idleTimeout = Duration.ofMinutes(5);
    private Duration keepAliveTimeout = Duration.ofMinutes(2);

    private long maxRequestSize = 10 * 1024 * 1024L; // 10MB
    private long maxResponseSize = 50 * 1024 * 1024L; // 50MB
    private int bufferSize = 8192;

    public Builder routes(Map<String, List<String>> routes) {
      this.routes = routes;
      return this;
    }

    public Builder domainRoutes(Map<String, List<String>> domainRoutes) {
      this.domainRoutes = domainRoutes;
      return this;
    }

    public Builder loadBalancerAlgorithm(String algorithm) {
      this.loadBalancerAlgorithm = algorithm;
      return this;
    }

    public Builder backendWeights(Map<String, Integer> weights) {
      this.backendWeights = weights;
      return this;
    }

    public Builder rateLimiter(boolean enabled, int requestsPerSecond, int burstSize,
                               String strategy) {
      this.rateLimiterEnabled = enabled;
      this.rateLimitRequestsPerSecond = requestsPerSecond;
      this.rateLimitBurstSize = burstSize;
      this.rateLimitStrategy = strategy;
      return this;
    }

    public Builder circuitBreaker(boolean enabled, int failureThreshold, int timeoutSeconds) {
      this.circuitBreakerEnabled = enabled;
      this.circuitBreakerFailureThreshold = failureThreshold;
      this.circuitBreakerTimeoutSeconds = timeoutSeconds;
      return this;
    }

    public Builder healthCheck(boolean enabled, int intervalSeconds, int timeoutSeconds,
                               int failureThreshold, int successThreshold) {
      this.healthCheckEnabled = enabled;
      this.healthCheckIntervalSeconds = intervalSeconds;
      this.healthCheckTimeoutSeconds = timeoutSeconds;
      this.healthCheckFailureThreshold = failureThreshold;
      this.healthCheckSuccessThreshold = successThreshold;
      return this;
    }

    public Builder healthCheckPath(String path) {
      this.healthCheckPath = path;
      return this;
    }

    public Builder healthCheckExpectedStatus(int min, int max) {
      this.healthCheckExpectedStatusMin = min;
      this.healthCheckExpectedStatusMax = max;
      return this;
    }

    public Builder cors(CorsConfig config) {
      this.corsConfig = config;
      return this;
    }

    public Builder adminEnabled(boolean enabled) {
      this.adminEnabled = enabled;
      return this;
    }

    public Builder adminAuth(AdminAuth auth) {
      this.adminAuth = auth;
      return this;
    }

    public Builder timeouts(Duration connection, Duration request, Duration idle,
                            Duration keepAlive) {
      this.connectionTimeout = connection;
      this.requestTimeout = request;
      this.idleTimeout = idle;
      this.keepAliveTimeout = keepAlive;
      return this;
    }

    public Builder limits(long maxRequestSize, long maxResponseSize, int bufferSize) {
      this.maxRequestSize = maxRequestSize;
      this.maxResponseSize = maxResponseSize;
      this.bufferSize = bufferSize;
      return this;
    }

    public ServerConfig build() {
      return new ServerConfig(this);
    }
  }
}
