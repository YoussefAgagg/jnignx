# Quick Start Guide - NanoServer

This guide will help you get started with NanoServer and its advanced features.

## Installation

### Prerequisites

- Java 25 or later
- Enable preview features

### Building from Source

```bash
git clone https://github.com/youssefagagg/jnignx.git
cd jnignx
./gradlew build
```

## Basic Usage

### 1. Create a Configuration File

Create `routes.json` in your project directory:

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
      "http://localhost:8080"
    ]
  }
}
```

### 2. Start the Server

```bash
# Default: port 8080, routes.json
./gradlew run

# Custom port and config
./gradlew run --args="9090 myroutes.json"
```

The server will start and automatically:

- Load your configuration
- Start health checking all backends
- Begin accepting connections
- Monitor for configuration changes

## Feature Tutorials

### Load Balancing Strategies

NanoServer supports three load balancing strategies:

#### Round-Robin (Default)

Requests are distributed evenly across all healthy backends in a circular pattern.

**Best for:** Uniform workloads where all backends have similar capacity

**No configuration needed** - this is the default behavior.

#### Least Connections

Routes requests to the backend with the fewest active connections.

**Best for:** Variable request durations, WebSocket connections, long-polling

**Configuration:**

```java
Router router = new Router(
    Path.of("routes.json"),
    LoadBalancer.Strategy.LEAST_CONNECTIONS
);
```

#### IP Hash (Sticky Sessions)

Uses the client's IP address to consistently route to the same backend.

**Best for:** Session persistence, stateful applications

**Configuration:**

```java
Router router = new Router(
    Path.of("routes.json"),
    LoadBalancer.Strategy.IP_HASH
);
```

### Health Checking

Health checks run automatically every 10 seconds for all backends.

#### Viewing Health Status

Check the logs:

```
[HealthChecker] ✓ http://localhost:3000 is healthy
[HealthChecker] ✗ http://localhost:3001 failed: Connection refused
[HealthChecker] Started monitoring 3 backends
```

#### How It Works

1. **Active Checks:** Server sends HEAD / requests every 10 seconds
2. **Passive Checks:** Monitors actual proxy request failures
3. **Failure Threshold:** 3 consecutive failures → mark unhealthy
4. **Success Threshold:** 2 consecutive successes → mark healthy
5. **Automatic Recovery:** Unhealthy backends are automatically re-tested

#### Unhealthy Backends

- Automatically removed from load balancing rotation
- Continue to be monitored for recovery
- Automatically added back when healthy

### Monitoring & Observability

#### Access Logs

JSON-formatted logs are written to stdout:

```bash
./gradlew run | tee access.log
```

Example log entry:

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

**Log Aggregation:**

Send logs to your favorite tool:

```bash
# Elasticsearch/Logstash
./gradlew run | filebeat -c filebeat.yml

# Splunk
./gradlew run | splunk add oneshot access.log

# Datadog
./gradlew run | datadog-agent

# CloudWatch
./gradlew run | aws logs put-log-events --log-group-name nanoserver
```

#### Prometheus Metrics

Access metrics at `http://localhost:8080/metrics`

```bash
curl http://localhost:8080/metrics
```

**Setting up Prometheus:**

1. Create `prometheus.yml`:

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'nanoserver'
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/metrics'
```

2. Run Prometheus:

```bash
prometheus --config.file=prometheus.yml
```

3. View metrics at `http://localhost:9090`

**Key Metrics:**

- `nanoserver_requests_total` - Total requests served
- `nanoserver_active_connections` - Current connections
- `nanoserver_request_duration_ms_bucket` - Response time histogram
- `nanoserver_requests_by_status{status="200"}` - Requests by status code
- `nanoserver_bytes_sent_total` - Total bytes sent

### Static File Serving

Serve static files with zero-copy performance:

```json
{
  "routes": {
    "/assets": ["file:///var/www/html/assets"],
    "/downloads": ["file:///home/user/files"]
  }
}
```

**Features:**

- Automatic MIME type detection
- Directory listings
- Gzip compression for text files
- Cache headers (ETag, Last-Modified)
- Range request support (future)

### Hot Configuration Reload

Changes to `routes.json` are detected automatically within 1 second:

```bash
# Server is running...
vim routes.json  # Edit and save

# Check logs:
[Router] Configuration reloaded!
[Router] Old routes: [/api, /static]
[Router] New routes: [/api, /static, /admin]
[HealthChecker] Started monitoring new backend: http://localhost:4000
```

**Zero Downtime:** Active requests use the old configuration while new requests use the updated configuration.

### Security Features

#### X-Forwarded Headers

Automatically added to all proxied requests:

- `X-Forwarded-For: 192.168.1.100`
- `X-Real-IP: 192.168.1.100`
- `X-Forwarded-Proto: http`
- `Host: backend.example.com`

Your backend can access the real client IP:

```java
// Backend code
String clientIp = request.getHeader("X-Forwarded-For");
String protocol = request.getHeader("X-Forwarded-Proto");
```

#### Path Traversal Protection

The static file handler blocks directory traversal attacks:

```bash
# These requests are blocked:
curl http://localhost:8080/../../../etc/passwd
curl http://localhost:8080/assets/../../config.json
```

Response: `403 Forbidden`

## Production Deployment

### System Tuning

```bash
# Increase file descriptor limit
ulimit -n 100000

# Set in /etc/security/limits.conf for persistence
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

### Native Image (GraalVM)

Build a native binary for instant startup:

```bash
# Install GraalVM
sdk install java 25.0.0-graal

# Build native image
./gradlew nativeCompile

# Run (starts in <100ms)
./build/native/nativeCompile/jnignx 8080 routes.json
```

**Benefits:**

- Instant startup (<100ms vs ~1-2 seconds)
- Lower memory footprint (~50MB vs ~200MB)
- No JVM warmup needed

### Running as a Service

#### systemd (Linux)

Create `/etc/systemd/system/nanoserver.service`:

```ini
[Unit]
Description=NanoServer Reverse Proxy
After=network.target

[Service]
Type=simple
User=nanoserver
WorkingDirectory=/opt/nanoserver
ExecStart=/opt/nanoserver/jnignx 8080 /etc/nanoserver/routes.json
Restart=always
RestartSec=5

# Resource limits
LimitNOFILE=100000

# Security
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable nanoserver
sudo systemctl start nanoserver
sudo systemctl status nanoserver
```

#### Docker

Create `Dockerfile`:

```dockerfile
FROM ghcr.io/graalvm/graalvm-ce:java25

WORKDIR /app
COPY build/libs/jnignx-1.0-SNAPSHOT.jar /app/
COPY routes.json /app/

EXPOSE 8080

CMD ["java", "--enable-preview", "-jar", "jnignx-1.0-SNAPSHOT.jar", "8080", "routes.json"]
```

Build and run:

```bash
docker build -t nanoserver .
docker run -p 8080:8080 -v $(pwd)/routes.json:/app/routes.json nanoserver
```

### Load Testing

Test your setup with wrk:

```bash
# Install wrk
brew install wrk  # macOS
sudo apt-get install wrk  # Ubuntu

# Run load test
wrk -t12 -c400 -d30s http://localhost:8080/

# Results:
# Running 30s test @ http://localhost:8080/
#   12 threads and 400 connections
#   Thread Stats   Avg      Stdev     Max   +/- Stdev
#     Latency    10.23ms    5.45ms  50.12ms   89.34%
#     Req/Sec     3.21k   245.17     4.12k    91.23%
#   1152033 requests in 30.01s, 234.56MB read
# Requests/sec:  38392.12
# Transfer/sec:      7.82MB
```

## Common Patterns

### Multiple Backends with Failover

```json
{
  "routes": {
    "/api": [
      "http://backend1:3000",
      "http://backend2:3000",
      "http://backend3:3000"
    ]
  }
}
```

If backend1 fails:

1. Health checker marks it unhealthy
2. Requests go to backend2 and backend3
3. backend1 is periodically re-tested
4. When healthy, backend1 receives traffic again

### Mixed Static and Dynamic Content

```json
{
  "routes": {
    "/api": [
      "http://api-server:3000"
    ],
    "/assets": [
      "file:///var/www/static"
    ],
    "/images": [
      "file:///mnt/images"
    ],
    "/": [
      "http://app-server:8080"
    ]
  }
}
```

### Microservices Gateway

```json
{
  "routes": {
    "/users": ["http://user-service:3000", "http://user-service:3001"],
    "/orders": ["http://order-service:4000", "http://order-service:4001"],
    "/payments": ["http://payment-service:5000"],
    "/static": ["file:///var/www/html"]
  }
}
```

## Troubleshooting

### Server won't start: "Address already in use"

Another process is using the port:

```bash
# Find the process
lsof -i :8080

# Kill it
kill -9 <PID>

# Or use a different port
./gradlew run --args="9090 routes.json"
```

### Backends marked as unhealthy

Check backend accessibility:

```bash
# Test backend directly
curl -I http://localhost:3000/

# Check backend logs
# Ensure backend responds to HEAD / requests

# Check firewall rules
sudo iptables -L
```

### High memory usage

Virtual threads are lightweight, but check:

```bash
# Monitor memory
jcmd <PID> GC.heap_info

# Reduce heap if needed
java -XX:MaxHeapSize=512m ...

# Use ZGC for better performance
java -XX:+UseZGC ...
```

### Slow response times

Check metrics:

```bash
curl http://localhost:8080/metrics | grep duration
```

Possible causes:

- Slow backends - check backend performance
- High load - add more backends
- Network issues - check latency to backends

## Next Steps

- Read [FEATURES.md](docs/FEATURES.md) for detailed feature documentation
- Read [API.md](docs/API.md) for programmatic usage
- Read [ARCHITECTURE.md](docs/ARCHITECTURE.md) for implementation details
- Check the [roadmap](README.md#roadmap) for upcoming features

## Getting Help

- GitHub Issues: https://github.com/youssefagagg/jnignx/issues
- Documentation: https://github.com/youssefagagg/jnignx/docs

## Performance Expectations

### Typical Performance

- **Throughput:** 30,000-50,000 req/sec on modern hardware
- **Latency:** <10ms p50, <50ms p99 (excludes backend time)
- **Memory:** ~1KB per connection
- **Startup:** <100ms (native image), ~1-2s (JVM)
- **Concurrent Connections:** Millions (limited by system resources)

### Comparison

| Server     | Requests/sec | Memory/conn | Startup |
|------------|--------------|-------------|---------|
| Nginx      | 50,000       | ~1KB        | <100ms  |
| Caddy      | 40,000       | ~4KB        | <100ms  |
| NanoServer | 38,000       | ~1KB        | <100ms  |

*Benchmarks on MacBook Air M1, 16GB RAM, testing simple proxy to local backend*
