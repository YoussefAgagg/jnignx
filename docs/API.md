# NanoServer API Documentation

This document provides API-level documentation for using and integrating NanoServer components.

## Table of Contents

1. [Router API](#router-api)
2. [Load Balancer API](#load-balancer-api)
3. [Health Checker API](#health-checker-api)
4. [Metrics API](#metrics-api)
5. [Access Logger API](#access-logger-api)
6. [TLS/SSL API](#tlsssl-api)

---

## Router API

The Router manages request routing with load balancing and health checking.

### Constructor

```java
// Default constructor (Round-robin load balancing)
Router router = new Router(Path.of("routes.json"));

// With custom load balancing strategy
Router router = new Router(
    Path.of("routes.json"),
    LoadBalancer.Strategy.LEAST_CONNECTIONS
);
```

### Methods

#### loadConfig()

Loads initial configuration from the routes file.

```java
void loadConfig() throws IOException
```

**Example**:

```java
Router router = new Router(Path.of("routes.json"));
router.

loadConfig();
```

#### startHotReloadWatcher()

Starts background monitoring for configuration changes.

```java
void startHotReloadWatcher()
```

**Example**:

```java
router.startHotReloadWatcher();
```

#### resolveBackend()

Resolves a request path to a backend URL.

```java
String resolveBackend(String path)

String resolveBackend(String path, String clientIp)
```

**Parameters**:

- `path`: Request path (e.g., "/api/users")
- `clientIp`: Client IP address (optional, used for IP hash strategy)

**Returns**: Backend URL or `null` if no route matches

**Example**:

```java
String backend = router.resolveBackend("/api/users", "192.168.1.100");
if(backend !=null){
    // Forward request to backend
    }
```

#### recordConnectionStart() / recordConnectionEnd()

Track active connections for least-connections load balancing.

```java
void recordConnectionStart(String backend)

void recordConnectionEnd(String backend)
```

**Example**:

```java
String backend = router.resolveBackend(path, clientIp);
router.

recordConnectionStart(backend);
try{
    // Handle request...
    }finally{
    router.

recordConnectionEnd(backend);
}
```

#### recordProxySuccess() / recordProxyFailure()

Passive health checking based on actual request results.

```java
void recordProxySuccess(String backend)

void recordProxyFailure(String backend, String error)
```

**Example**:

```java
try{
proxyRequest(backend);
    router.

recordProxySuccess(backend);
}catch(
IOException e){
    router.

recordProxyFailure(backend, e.getMessage());
    }
```

#### stop()

Gracefully shuts down the router and health checker.

```java
void stop()
```

---

## Load Balancer API

Manages backend selection using various strategies.

### Enum: Strategy

```java
public enum Strategy {
  ROUND_ROBIN,
  LEAST_CONNECTIONS,
  IP_HASH
}
```

### Constructor

```java
LoadBalancer lb = new LoadBalancer(
    LoadBalancer.Strategy.ROUND_ROBIN,
    healthChecker
);
```

### Methods

#### selectBackend()

Selects a backend from the list using the configured strategy.

```java
String selectBackend(String path, List<String> backends, String clientIp)
```

**Parameters**:

- `path`: Request path (for grouping in round-robin)
- `backends`: List of available backend URLs
- `clientIp`: Client IP (for IP hash strategy)

**Returns**: Selected backend URL

**Example**:

```java
List<String> backends = List.of(
    "http://backend1:3000",
    "http://backend2:3000",
    "http://backend3:3000"
);

String selected = loadBalancer.selectBackend(
    "/api/users",
    backends,
    "192.168.1.100"
);
```

#### getConnectionCount()

Returns active connection count for a backend.

```java
long getConnectionCount(String backend)
```

**Example**:

```java
long connections = loadBalancer.getConnectionCount("http://backend1:3000");
System.out.

println("Active connections: "+connections);
```

---

## Health Checker API

Monitors backend health with active and passive checks.

### Constructor

```java
HealthChecker healthChecker = new HealthChecker();
```

### Methods

#### start()

Begins health checking for a list of backends.

```java
void start(List<String> backends)
```

**Example**:

```java
List<String> backends = List.of(
    "http://backend1:3000",
    "http://backend2:3000"
);
healthChecker.

start(backends);
```

#### isHealthy()

Checks if a backend is currently healthy.

```java
boolean isHealthy(String backendUrl)
```

**Example**:

```java
if(healthChecker.isHealthy("http://backend1:3000")){
    // Use this backend
    }
```

#### getHealth()

Returns detailed health status for a backend.

```java
BackendHealth getHealth(String backendUrl)
```

**BackendHealth Properties**:

```java
class BackendHealth {
  boolean isHealthy()

  int getConsecutiveFailures()

  String getLastError()

  Instant getLastCheck()
}
```

**Example**:

```java
BackendHealth health = healthChecker.getHealth("http://backend1:3000");
System.out.

println("Healthy: "+health.isHealthy());
    System.out.

println("Last check: "+health.getLastCheck());
    System.out.

println("Consecutive failures: "+health.getConsecutiveFailures());
```

#### registerBackend()

Registers a new backend for health monitoring.

```java
void registerBackend(String backendUrl)
```

**Example**:

```java
healthChecker.registerBackend("http://backend4:3000");
```

### Configuration Constants

```java
private static final int CHECK_INTERVAL_MS = 10_000;  // 10 seconds
private static final int TIMEOUT_MS = 5_000;          // 5 seconds
private static final int FAILURE_THRESHOLD = 3;        // Mark unhealthy
private static final int SUCCESS_THRESHOLD = 2;        // Mark healthy
```

---

## Metrics API

Collects and exports Prometheus-style metrics.

### Singleton Access

```java
MetricsCollector metrics = MetricsCollector.getInstance();
```

### Methods

#### recordRequest()

Records a completed HTTP request.

```java
void recordRequest(
    int statusCode,
    long durationMs,
    String path,
    long bytesIn,
    long bytesOut
)
```

**Example**:

```java
long startTime = System.currentTimeMillis();
// ... handle request ...
long duration = System.currentTimeMillis() - startTime;

metrics.

recordRequest(200,duration, "/api/users",1024,2048);
```

#### Connection Tracking

```java
void incrementActiveConnections()

void decrementActiveConnections()

long getActiveConnections()
```

**Example**:

```java
metrics.incrementActiveConnections();
try{
    // Handle connection...
    }finally{
    metrics.

decrementActiveConnections();
}
```

#### exportPrometheus()

Exports all metrics in Prometheus text format.

```java
String exportPrometheus()
```

**Example**:

```java
String metricsText = metrics.exportPrometheus();
// Serve via /metrics endpoint
```

**Output**:

```
# HELP nanoserver_requests_total Total number of HTTP requests
# TYPE nanoserver_requests_total counter
nanoserver_requests_total 12345

# HELP nanoserver_active_connections Current number of active connections
# TYPE nanoserver_active_connections gauge
nanoserver_active_connections 42

# HELP nanoserver_request_duration_ms Request duration in milliseconds
# TYPE nanoserver_request_duration_ms histogram
nanoserver_request_duration_ms_bucket{le="10"} 5432
nanoserver_request_duration_ms_bucket{le="50"} 8765
...
```

### Metrics Provided

| Metric                                      | Type      | Description              |
|---------------------------------------------|-----------|--------------------------|
| `nanoserver_uptime_seconds`                 | Counter   | Server uptime            |
| `nanoserver_requests_total`                 | Counter   | Total requests           |
| `nanoserver_requests_by_status{status}`     | Counter   | Requests by status code  |
| `nanoserver_requests_by_path{path}`         | Counter   | Requests by path         |
| `nanoserver_active_connections`             | Gauge     | Active connections       |
| `nanoserver_bytes_received_total`           | Counter   | Total bytes received     |
| `nanoserver_bytes_sent_total`               | Counter   | Total bytes sent         |
| `nanoserver_request_duration_ms_bucket{le}` | Histogram | Request duration buckets |
| `nanoserver_request_duration_ms_sum`        | Histogram | Total request duration   |
| `nanoserver_request_duration_ms_count`      | Histogram | Request count            |

---

## Access Logger API

Structured JSON logging for access logs and events.

### Static Methods

#### logAccess()

Logs an HTTP access event.

```java
static void logAccess(
    String clientIp,
    String method,
    String path,
    int status,
    long durationMs,
    long bytesSent,
    String userAgent,
    String backend
)
```

**Example**:

```java
AccessLogger.logAccess(
    "192.168.1.100",
        "GET",
        "/api/users",
        200,
        45,
        1234,
        "curl/7.64.1",
        "http://localhost:3000"
);
```

**Output**:

```json
{
  "timestamp": "2026-01-16T10:30:45.123Z",
  "level": "INFO",
  "type": "access",
  "client_ip": "192.168.1.100",
  "method": "GET",
  "path": "/api/users",
  "status": 200,
  "duration_ms": 45,
  "bytes_sent": 1234,
  "user_agent": "curl/7.64.1",
  "backend": "http://localhost:3000"
}
```

#### logError()

Logs an error event.

```java
static void logError(String message, String error)
```

**Example**:

```java
try{
    // ... operation ...
    }catch(Exception e){
    AccessLogger.

logError("Failed to connect to backend",e.getMessage());
    }
```

**Output**:

```json
{
  "timestamp": "2026-01-16T10:30:45.123Z",
  "level": "ERROR",
  "type": "error",
  "message": "Failed to connect to backend",
  "error": "Connection refused"
}
```

#### logInfo()

Logs a general info event with metadata.

```java
static void logInfo(String message, Map<String, String> metadata)
```

**Example**:

```java
Map<String, String> meta = Map.of(
    "backend", "http://localhost:3000",
    "retries", "3"
);
AccessLogger.

logInfo("Backend connection established",meta);
```

**Output**:

```json
{
  "timestamp": "2026-01-16T10:30:45.123Z",
  "level": "INFO",
  "type": "info",
  "message": "Backend connection established",
  "backend": "http://localhost:3000",
  "retries": "3"
}
```

---

## TLS/SSL API

Provides TLS termination using Java's SSLEngine.

### Constructor

```java
SslWrapper ssl = new SslWrapper(
    "keystore.p12",  // Keystore path
    "password"        // Keystore password
);
```

**Supported Formats**:

- PKCS12 (.p12, .pfx)
- JKS (.jks)

### Methods

#### wrap()

Wraps a socket channel with SSL/TLS.

```java
SslSession wrap(SocketChannel channel) throws IOException
```

**Example**:

```java
SocketChannel clientChannel = serverChannel.accept();
SslWrapper.SslSession sslSession = ssl.wrap(clientChannel);
sslSession.

doHandshake();
```

### SslSession API

Represents an active SSL session.

#### doHandshake()

Performs the SSL/TLS handshake.

```java
void doHandshake() throws IOException
```

**Example**:

```java
SslWrapper.SslSession session = ssl.wrap(channel);
session.

doHandshake();
// Now ready for encrypted communication
```

#### read()

Reads decrypted data from the connection.

```java
int read(ByteBuffer dst) throws IOException
```

**Example**:

```java
ByteBuffer buffer = ByteBuffer.allocate(8192);
int bytesRead = session.read(buffer);
```

#### write()

Writes encrypted data to the connection.

```java
int write(ByteBuffer src) throws IOException
```

**Example**:

```java
ByteBuffer response = ByteBuffer.wrap("HTTP/1.1 200 OK\r\n\r\n".getBytes());
session.

write(response);
```

#### getNegotiatedProtocol()

Returns the negotiated protocol from ALPN.

```java
String getNegotiatedProtocol()
```

**Returns**: "h2" for HTTP/2 or "http/1.1"

**Example**:

```java
String protocol = session.getNegotiatedProtocol();
if("h2".

equals(protocol)){
    // Handle HTTP/2
    }else{
    // Handle HTTP/1.1
    }
```

### Creating a Keystore

```bash
# Generate self-signed certificate
keytool -genkeypair \
  -alias server \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore keystore.p12 \
  -validity 365 \
  -dname "CN=localhost, OU=Dev, O=NanoServer, L=City, ST=State, C=US"

# Import existing certificate
keytool -importcert \
  -file certificate.crt \
  -keystore keystore.p12 \
  -alias server \
  -storetype PKCS12
```

---

## Integration Examples

### Complete Server Setup

```java
public class ServerSetup {
  public static void main(String[] args) throws Exception {
    // 1. Create router with load balancing
    Router router = new Router(
        Path.of("routes.json"),
        LoadBalancer.Strategy.LEAST_CONNECTIONS
    );
    router.loadConfig();
    router.startHotReloadWatcher();

    // 2. Start server loop
    ServerLoop server = new ServerLoop(8080, router);

    // 3. Add shutdown hook
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      server.stop();
      router.stop();
    }));

    // 4. Start accepting connections
    server.start();
  }
}
```

### Custom Worker with Metrics

```java
public class CustomWorker implements Runnable {
  private final SocketChannel channel;
  private final Router router;
  private final MetricsCollector metrics;

  @Override
  public void run() {
    metrics.incrementActiveConnections();
    long startTime = System.currentTimeMillis();

    try (Arena arena = Arena.ofConfined()) {
      // Parse request
      Request request = parseRequest(channel, arena);
      String clientIp = extractClientIp(channel);

      // Route to backend
      String backend = router.resolveBackend(request.path(), clientIp);
      router.recordConnectionStart(backend);

      try {
        // Proxy request
        proxyToBackend(channel, backend, request, arena);
        router.recordProxySuccess(backend);

        // Log success
        long duration = System.currentTimeMillis() - startTime;
        AccessLogger.logAccess(
            clientIp, request.method(), request.path(),
            200, duration, 0,
            request.headers().get("User-Agent"),
            backend
        );
        metrics.recordRequest(200, duration, request.path(), 0, 0);

      } catch (IOException e) {
        router.recordProxyFailure(backend, e.getMessage());
        AccessLogger.logError("Proxy failed", e.getMessage());
      } finally {
        router.recordConnectionEnd(backend);
      }
    } finally {
      metrics.decrementActiveConnections();
      closeQuietly(channel);
    }
  }
}
```

### Health Status Dashboard

```java
public class HealthDashboard {
  public static void printStatus(Router router) {
    HealthChecker checker = router.getHealthChecker();

    System.out.println("Backend Health Status:");
    System.out.println("=====================");

    for (var entry : checker.getAllHealth().entrySet()) {
      String backend = entry.getKey();
      HealthChecker.BackendHealth health = entry.getValue();

      String status = health.isHealthy() ? "✓ HEALTHY" : "✗ UNHEALTHY";
      System.out.printf("%-40s %s%n", backend, status);
      System.out.printf("  Last Check: %s%n", health.getLastCheck());
      System.out.printf("  Failures: %d%n", health.getConsecutiveFailures());

      if (!health.isHealthy()) {
        System.out.printf("  Error: %s%n", health.getLastError());
      }
      System.out.println();
    }
  }
}
```

### Prometheus Integration

```java
// Serve metrics endpoint
if("/metrics".equals(request.path())){
MetricsCollector metrics = MetricsCollector.getInstance();
String metricsText = metrics.exportPrometheus();

String response = "HTTP/1.1 200 OK\r\n" +
    "Content-Type: text/plain; version=0.0.4\r\n" +
    "Content-Length: " + metricsText.length() + "\r\n" +
    "\r\n" +
    metricsText;
    
    channel.

write(ByteBuffer.wrap(response.getBytes()));
    }
```

---

## Error Handling

### Common Exceptions

```java
try{
    router.loadConfig();
}catch(
IOException e){
    // Configuration file not found or invalid JSON
    System.err.

println("Failed to load config: "+e.getMessage());
    }

    try{
String backend = router.resolveBackend(path);
// ... proxy request ...
}catch(
IOException e){
    // Network error, backend unavailable
    router.

recordProxyFailure(backend, e.getMessage());
    }

    try{
SslWrapper ssl = new SslWrapper("keystore.p12", "wrong-password");
}catch(
Exception e){
    // Invalid keystore or password
    System.err.

println("SSL initialization failed: "+e.getMessage());
    }
```

### Best Practices

1. **Always use try-with-resources for Arena**:

```java
try(Arena arena = Arena.ofConfined()){
    // Use arena...
    } // Automatically freed
```

2. **Track connections properly**:

```java
router.recordConnectionStart(backend);
try{
    // Handle request...
    }finally{
    router.

recordConnectionEnd(backend);
}
```

3. **Record health check results**:

```java
try{
proxyRequest(backend);
    router.

recordProxySuccess(backend);
}catch(
Exception e){
    router.

recordProxyFailure(backend, e.getMessage());
    throw e;
}
```

4. **Update metrics consistently**:

```java
metrics.incrementActiveConnections();
try{
long start = System.currentTimeMillis();
// Handle request...
long duration = System.currentTimeMillis() - start;
    metrics.

recordRequest(status, duration, path, bytesIn, bytesOut);
}finally{
    metrics.

decrementActiveConnections();
}
```

---

## Testing

### Unit Test Example

```java

@Test
void testLoadBalancing() {
  HealthChecker healthChecker = new HealthChecker();
  LoadBalancer lb = new LoadBalancer(
      LoadBalancer.Strategy.ROUND_ROBIN,
      healthChecker
  );

  List<String> backends = List.of(
      "http://backend1:3000",
      "http://backend2:3000",
      "http://backend3:3000"
  );

  // Should cycle through backends
  assertEquals("http://backend1:3000",
               lb.selectBackend("/api", backends, null));
  assertEquals("http://backend2:3000",
               lb.selectBackend("/api", backends, null));
  assertEquals("http://backend3:3000",
               lb.selectBackend("/api", backends, null));
  assertEquals("http://backend1:3000",
               lb.selectBackend("/api", backends, null));
}
```

### Integration Test Example

```java

@Test
void testHealthChecking() throws Exception {
  // Start mock backend
  ServerSocket backend = new ServerSocket(9999);

  // Configure router
  Path configPath = createTempConfig(
      Map.of("/test", List.of("http://localhost:9999"))
  );
  Router router = new Router(configPath);
  router.loadConfig();

  // Wait for health check
  Thread.sleep(11000);

  // Backend should be healthy
  assertTrue(router.getHealthChecker().isHealthy("http://localhost:9999"));

  // Stop backend
  backend.close();
  Thread.sleep(11000);

  // Backend should be unhealthy
  assertFalse(router.getHealthChecker().isHealthy("http://localhost:9999"));
}
```

---

## Performance Tuning

### JVM Options

```bash
# Tune virtual thread carrier pool
-Djdk.virtualThreadScheduler.parallelism=16

# Enable preview features
--enable-preview

# GC tuning (if needed)
-XX:+UseZGC
-XX:MaxHeapSize=2g
```

### Buffer Sizing

```java
// Adjust buffer size based on workload
private static final int BUFFER_SIZE = 8192;  // 8KB default
private static final int BUFFER_SIZE = 65536; // 64KB for large files
```

### Connection Limits

```bash
# Increase file descriptor limit
ulimit -n 100000
```

---

For more information, see:

- [FEATURES.md](FEATURES.md) - Complete feature documentation
- [ARCHITECTURE.md](ARCHITECTURE.md) - Architecture and implementation details
- [README.md](../README.md) - Getting started guide
