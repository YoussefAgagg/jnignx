# NanoServer (jnignx) - Java Nginx

A high-performance Reverse Proxy & Web Server built with Java 25, leveraging modern JVM capabilities for maximum
throughput and minimum latency. Combines the performance of Nginx with the usability features of Caddy.

## 🚀 Features

### Core Architecture
- **Virtual Threads (Project Loom)**: One virtual thread per connection for massive concurrency
- **FFM API (Project Panama)**: Off-heap memory allocation to minimize GC pressure
- **Zero-Copy I/O**: Direct buffer transfers without JVM heap copies
- **Hot-Reload Configuration**: Atomic route configuration updates with zero downtime
- **GraalVM Native Image Compatible**: No reflection, ready for AOT compilation

### Load Balancing

- **Round-Robin**: Distributes requests evenly across backends
- **Least Connections**: Routes to the backend with fewest active connections
- **IP Hash**: Consistent hashing for sticky sessions based on client IP
- **Health Checking**: Active and passive health checks with automatic backend removal/recovery
- **Circuit Breaker**: Fast failure for known-bad backends

### Observability

- **Structured Logging**: JSON access logs with request/response metrics
- **Prometheus Metrics**: Built-in `/metrics` endpoint with:
    - Request counts by status code and path
    - Response time histograms
    - Active connection counts
    - Bytes sent/received
    - Backend health status
    - Uptime tracking

### Security & Reliability

- **TLS/HTTPS Support**: SSL/TLS termination with SSLEngine
- **Header Forwarding**: Automatic `X-Forwarded-For`, `X-Real-IP`, `X-Forwarded-Proto` headers
- **Path Traversal Protection**: Security checks for static file serving
- **Graceful Shutdown**: Clean connection handling on server stop

### Static File Serving

- **Zero-Copy Transfer**: Efficient file serving using `FileChannel.transferTo()`
- **MIME Types**: Automatic content-type detection
- **Directory Listing**: Auto-generated HTML directory indexes
- **Gzip Compression**: On-the-fly compression for text assets
- **Cache Headers**: `ETag` and `Last-Modified` support

## 📋 Requirements

- Java 25 (with preview features enabled)
- GraalVM (optional, for native compilation)

## 🏗️ Building

```bash
# Compile the project
./gradlew build

# Build native image (requires GraalVM)
./gradlew nativeCompile
```

## 🚀 Running

```bash
# Run with default settings (port 8080, routes.json)
./gradlew run

# Run with custom port and config
./gradlew run --args="9090 custom-routes.json"

# Run native binary (after nativeCompile)
./build/native/nativeCompile/jnignx 8080 routes.json
```

## ⚙️ Configuration

Create a `routes.json` file with your routing configuration:

```json
{
  "routes": {
    "/api": [
      "http://localhost:3000",
      "http://localhost:3001"
    ],
    "/static": [
      "file:///var/www/html"
    ],
    "/": [
      "http://localhost:8081"
    ]
  }
}
```

### Configuration Options

- **Path Prefix Matching**: Routes are matched by longest prefix
- **Multiple Backends**: Array of URLs enables load balancing
- **Load Balancing Strategies**: Round-robin (default), least connections, or IP hash
- **File Serving**: Use `file://` prefix for static file serving
- **Hot-Reload**: Modify the file while running; changes are detected automatically

## 🏛️ Architecture

### Components

1. **NanoServer**: Main server class that accepts connections and spawns virtual threads
2. **Router**: Dynamic routing with hot-reload, health checking, and load balancing
3. **LoadBalancer**: Pluggable load balancing strategies (round-robin, least connections, IP hash)
4. **HealthChecker**: Active health monitoring with automatic backend recovery
5. **ProxyHandler**: Zero-copy reverse proxy with X-Forwarded headers
6. **StaticHandler**: Efficient static file serving with compression
7. **MetricsCollector**: Prometheus-compatible metrics collection
8. **AccessLogger**: Structured JSON logging for observability
9. **SslWrapper**: TLS/HTTPS support with ALPN for HTTP/2

### Performance Optimizations

#### Virtual Threads vs Thread Pools

| Aspect          | Thread Pool           | Virtual Threads     |
|-----------------|-----------------------|---------------------|
| Stack Size      | ~1MB per thread       | ~1KB per thread     |
| Max Connections | Limited by pool size  | Millions            |
| Context Switch  | Expensive (OS kernel) | Cheap (JVM managed) |
| Code Style      | Callback/async        | Sequential          |

#### Foreign Function & Memory (FFM) API

Traditional Java I/O with `byte[]` arrays creates performance bottlenecks:

1. **GC Pressure**: Heap allocations trigger garbage collection
2. **Memory Copies**: Data copied kernel → heap → kernel

FFM API advantages:

- Allocates buffers in native memory (off-heap)
- Deterministic deallocation via Arena
- Direct ByteBuffers avoid heap copies

```
Traditional:  Socket → Kernel → JVM Heap byte[] → Kernel → Socket  (4 copies)
FFM/Direct:   Socket → Kernel → Native Buffer ──→ Kernel → Socket  (2 copies)
```

#### Zero-Copy Transfer

Uses `SocketChannel` with direct buffers that can leverage OS-level optimizations like `sendfile()` and `splice()` when
available, moving data between file descriptors without entering user space.

## 📊 Observability

### Access Logs

JSON-formatted access logs are written to stdout:

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

### Metrics Endpoint

Access metrics at `http://localhost:8080/metrics` in Prometheus format:

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

### Health Checking

Backends are automatically health-checked every 10 seconds:

- ✓ Healthy backends receive traffic
- ✗ Unhealthy backends are removed from rotation
- 🔄 Auto-recovery when backends become healthy again

Monitor health in logs:

```
[HealthChecker] ✓ http://localhost:3000 is healthy
[HealthChecker] ✗ http://localhost:3001 failed: Connection refused
```

## 📁 Project Structure

```
jnignx/
├── build.gradle.kts          # Gradle build config with GraalVM plugin
├── routes.json               # Sample routing configuration
├── README.md                 # This file
└── src/main/java/com/github/youssefagagg/jnignx/
    ├── NanoServer.java       # Main server with virtual threads
    ├── config/
    │   ├── ConfigLoader.java # JSON configuration parser
    │   └── RouteConfig.java  # Immutable route configuration
    ├── core/
    │   ├── ServerLoop.java   # Main accept loop
    │   ├── Worker.java       # Request handler (virtual thread)
    │   ├── Router.java       # Hot-reload router with health checks
    │   ├── LoadBalancer.java # Load balancing strategies
    │   └── HealthChecker.java # Backend health monitoring
    ├── handlers/
    │   ├── ProxyHandler.java # Zero-copy reverse proxy
    │   └── StaticHandler.java # Static file serving
    ├── http/
    │   ├── HttpParser.java   # HTTP/1.1 parser
    │   ├── Request.java      # Request model
    │   ├── Response.java     # Response model
    │   └── ResponseWriter.java # Response writer utility
    ├── tls/
    │   └── SslWrapper.java   # TLS/HTTPS support
    └── util/
        ├── AccessLogger.java # Structured JSON logging
        └── MetricsCollector.java # Prometheus metrics
```

## 📈 Performance Tips

1. **Tune Virtual Thread Carrier Threads**: Set `-Djdk.virtualThreadScheduler.parallelism=N`
2. **Increase File Descriptors**: `ulimit -n 100000`
3. **Use Native Image**: 10x faster startup, 5x lower memory
4. **Buffer Size**: Adjust `BUFFER_SIZE` in ProxyHandler for your workload
5. **Choose Load Balancing Strategy**:
    - Round-robin for even distribution
    - Least connections for variable request durations
    - IP hash for session persistence

## 🔧 Advanced Configuration

### Load Balancing Strategy

To use a different load balancing strategy, modify the Router initialization:

```java
// Round-robin (default)
Router router = new Router(configPath);

// Least connections
Router router = new Router(configPath, LoadBalancer.Strategy.LEAST_CONNECTIONS);

// IP hash for sticky sessions
Router router = new Router(configPath, LoadBalancer.Strategy.IP_HASH);
```

### Health Check Configuration

Health checks run automatically with these defaults:

- **Check Interval**: 10 seconds
- **Timeout**: 5 seconds
- **Failure Threshold**: 3 consecutive failures → unhealthy
- **Success Threshold**: 2 consecutive successes → healthy

## 🔒 Security Features

### TLS/HTTPS Support

Enable HTTPS by providing a keystore:

```java
SslWrapper ssl = new SslWrapper("keystore.p12", "password");
// Integrate with ServerLoop for HTTPS support
```

### Header Forwarding

The proxy automatically adds:

- `X-Forwarded-For`: Client IP address
- `X-Real-IP`: Client IP address
- `X-Forwarded-Proto`: Protocol (http/https)
- `Host`: Backend hostname

## 📜 License

MIT License

## 🔧 Troubleshooting

### "Address already in use"

Another process is using the port. Find it with `lsof -i :8080` and kill it, or use a different port.

### "No route configured"

Add a route in `routes.json` that matches your request path. Remember routes use prefix matching.

### Native Image Build Fails

Ensure you're using GraalVM with native-image installed: `gu install native-image`

### Backend Health Check Failures

Check backend logs and ensure they respond to HEAD requests on `/`. Adjust firewall rules if needed.

## 🚀 Roadmap

- [x] HTTP/1.1 support
- [x] Zero-copy I/O
- [x] Virtual threads
- [x] Health checking
- [x] Advanced load balancing
- [x] Metrics endpoint
- [x] Access logging
- [x] TLS support (basic)
- [ ] HTTP/2 support with ALPN
- [ ] Automatic HTTPS with Let's Encrypt (ACME)
- [ ] WebSocket proxying
- [ ] Brotli compression
- [ ] Request/Response buffering options
- [ ] Rate limiting
- [ ] Circuit breaker patterns
- [ ] Admin API for runtime configuration

## 🤝 Contributing

Contributions are welcome! This project aims to be a production-ready reverse proxy combining the best of Nginx (
performance) and Caddy (usability).

