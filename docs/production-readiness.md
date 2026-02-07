# Production Readiness

This document provides an honest assessment of what JNignx can and cannot do today, what must be addressed before
running in production, and a deployment guide for when the server is ready.

---

## Current State Summary

JNignx implements the core mechanics of a reverse proxy: request routing, load balancing, health checking, TLS
termination, WebSocket proxying, rate limiting, circuit breaking, and observability. However, several gaps remain before
it should handle real production traffic.

---

## Critical Gaps (Must Fix Before Production)

These issues can cause data loss, outages, or security vulnerabilities under real-world conditions.

### 1. No Chunked Transfer Encoding

**Impact:** Any client or backend using `Transfer-Encoding: chunked` (very common — most HTTP clients and frameworks
default to it for dynamic content) will have requests/responses silently truncated or dropped.

**What to do:** Implement chunked encoding parsing in `HttpParser` and chunked body forwarding in `ProxyHandler`.

### 2. Circuit Breaker Not Shared Across Connections

**Impact:** Each `Worker` creates its own `CircuitBreaker` instance. A backend could fail on 100 connections without any
circuit opening because each connection tracks failures independently.

**What to do:** Move `CircuitBreaker` to a shared, per-backend singleton managed by `Router`, similar to how
`HealthChecker` works.

### 3. No Proper Error Responses on Proxy Failure

**Impact:** When a backend connection fails, the proxy may close the client connection without sending any response.
Well-behaved proxies must return `502 Bad Gateway` or `504 Gateway Timeout`.

**What to do:** Catch all exceptions in `ProxyHandler` and ensure a valid HTTP error response is written to the client
before closing.

### 4. Fixed 8 KB Request Buffer

**Impact:** Requests with headers larger than 8 KB (common with many cookies or large Authorization tokens) will fail to
parse. Request bodies larger than the initial buffer are partially handled but edge cases exist.

**What to do:** Implement dynamic buffer growth or configurable buffer sizes in the read loop.

### 5. Health Check Constants Not Read From Config

**Impact:** The `healthCheck` configuration section is parsed but `HealthChecker` uses hardcoded values (10s interval,
5s timeout, 3 failure threshold, 2 success threshold). Changing config has no effect.

**What to do:** Pass `ServerConfig` health check parameters into `HealthChecker` constructor.

### 6. Admin API Returns Incorrect Status Codes

**Impact:** All admin API responses return `200 OK`, even errors and "not found". Monitoring tools that check status
codes will get false positives.

**What to do:** Use appropriate HTTP status codes (400, 404, 405, 500) in `AdminHandler.sendJsonResponse()`.

---

## Important Gaps (Should Fix for Reliability)

### 7. No Backend Connection Pooling

Each proxy request opens a new TCP connection to the backend and closes it after use. Under high load this wastes time
on TCP handshakes and port exhaustion is possible.

### 8. No Retry on Backend Failure

If the selected backend fails, the request is not retried on an alternate backend. The client receives an error even
though healthy backends are available.

### 9. HTTP/2 Not Functional

`SslWrapper` negotiates ALPN and may advertise `h2`, but `Worker` always processes connections as HTTP/1.1. A client
that upgrades to HTTP/2 will get a broken connection.

**Quick fix:** Remove `h2` from ALPN advertisement until HTTP/2 is fully implemented, or detect the negotiated protocol
and fall back gracefully.

### 10. `bytes_sent` Metric Always Zero for Proxied Requests

The proxy relay happens in a separate virtual thread and response size is never captured back. Monitoring dashboards
will show zero egress.

### 11. ACME Client is Non-Functional

`AcmeClient` exists but every method (account registration, order creation, challenge completion, certificate download)
returns a placeholder string. It should either be completed or removed to avoid confusion.

---

## Improvement Priorities (Ordered)

| Priority | Item                                        | Effort | Impact        |
|----------|---------------------------------------------|--------|---------------|
| P0       | Chunked transfer encoding                   | Medium | Correctness   |
| P0       | Shared circuit breaker                      | Low    | Reliability   |
| P0       | Proper error responses                      | Low    | Correctness   |
| P0       | Dynamic buffer sizing                       | Low    | Correctness   |
| P0       | Health check config integration             | Low    | Correctness   |
| P0       | Fix admin API status codes                  | Low    | Correctness   |
| P1       | Remove `h2` from ALPN (or implement HTTP/2) | Low    | Correctness   |
| P1       | Connection pooling                          | Medium | Performance   |
| P1       | Retry logic                                 | Medium | Reliability   |
| P1       | Fix `bytes_sent` metric                     | Low    | Observability |
| P2       | Range requests                              | Medium | Feature       |
| P2       | Cache headers (ETag, Last-Modified)         | Medium | Performance   |
| P2       | Per-route rate limits                       | Medium | Feature       |
| P2       | Log file output and rotation                | Low    | Operations    |
| P2       | PEM certificate loading                     | Low    | Usability     |
| P3       | Complete HTTP/2 (HPACK, stream mux)         | High   | Feature       |
| P3       | Real ACME client                            | High   | Feature       |
| P3       | Response caching                            | High   | Performance   |
| P3       | Middleware pipeline                         | High   | Extensibility |

---

## Deployment Guide

The following guide applies once the critical gaps above are resolved.

### System Tuning

```bash
# Increase file descriptor limit
ulimit -n 100000

# Persist in /etc/security/limits.conf
* soft nofile 100000
* hard nofile 100000
```

### JVM Options

```bash
java \
  --enable-preview \
  -Djdk.virtualThreadScheduler.parallelism=16 \
  -XX:+UseZGC \
  -XX:MaxHeapSize=2g \
  -jar build/libs/jnignx-1.0-SNAPSHOT.jar 8080 routes.json
```

| Flag                                          | Purpose                                  |
|-----------------------------------------------|------------------------------------------|
| `--enable-preview`                            | Required for Virtual Threads and FFM API |
| `-Djdk.virtualThreadScheduler.parallelism=16` | Carrier thread count (match CPU cores)   |
| `-XX:+UseZGC`                                 | Low-latency garbage collector            |
| `-XX:MaxHeapSize=2g`                          | Heap size (most I/O is off-heap)         |

### GraalVM Native Image

```bash
# Build
./gradlew nativeCompile

# Run (starts in <100ms, lower memory)
./build/native/nativeCompile/jnignx 8080 routes.json
```

### systemd Service (Linux)

```ini
[Unit]
Description=JNignx Reverse Proxy
After=network.target

[Service]
Type=simple
User=jnignx
WorkingDirectory=/opt/jnignx
ExecStart=/opt/jnignx/jnignx 8080 /etc/jnignx/routes.json
Restart=always
RestartSec=5
LimitNOFILE=100000
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable jnignx
sudo systemctl start jnignx
```

### Docker

```dockerfile
FROM ghcr.io/graalvm/graalvm-ce:java25

WORKDIR /app
COPY build/libs/jnignx-1.0-SNAPSHOT.jar /app/
COPY routes.json /app/

EXPOSE 8080

CMD ["java", "--enable-preview", "-jar", "jnignx-1.0-SNAPSHOT.jar", "8080", "routes.json"]
```

```bash
docker build -t jnignx .
docker run -p 8080:8080 -v $(pwd)/routes.json:/app/routes.json jnignx
```

### Monitoring Setup

**Prometheus** (`prometheus.yml`):

```yaml
scrape_configs:
  - job_name: 'jnignx'
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/metrics'
    scrape_interval: 15s
```

**Log aggregation:**

```bash
# Pipe JSON logs to your preferred tool
./jnignx 8080 routes.json | tee /var/log/jnignx/access.log
```

### TLS Configuration

```bash
# Generate self-signed certificate for testing
keytool -genkeypair \
  -alias server \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore keystore.p12 \
  -validity 365 \
  -dname "CN=localhost"
```

TLS is enabled programmatically:

```java
SslWrapper ssl = new SslWrapper("keystore.p12", "password");
ServerLoop server = new ServerLoop(443, router, ssl);
```

> **Note:** There is no configuration file option to enable TLS yet. It requires code changes or a wrapper script.

---

## Load Testing

Verify your deployment with wrk:

```bash
wrk -t12 -c400 -d30s http://localhost:8080/
```

Monitor during the test:

```bash
# Check metrics
curl -s http://localhost:8080/metrics

# Check admin health
curl -s http://localhost:8080/admin/health
```
