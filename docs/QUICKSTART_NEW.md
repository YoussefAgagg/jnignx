# Quick Start Guide - NanoServer with New Features

This guide will help you get started with NanoServer's new features including HTTPS, WebSocket, rate limiting, and more.

## Prerequisites

- Java 25 with preview features enabled
- GraalVM (optional, for native compilation)

## Basic Setup

### 1. Clone and Build

```bash
git clone <repository-url>
cd jnignx
./gradlew build
```

### 2. Configure Routes

Create `routes.json`:

```json
{
  "routes": {
    "/api": [
      "http://localhost:3000",
      "http://localhost:3001"
    ],
    "/ws": [
      "ws://localhost:3000"
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

### 3. Run the Server

#### HTTP Only

```bash
./gradlew run --args="8080 routes.json"
```

#### With HTTPS

First, generate a certificate:

```bash
keytool -genkeypair -alias server -keyalg RSA -keysize 2048 \
  -validity 365 -keystore keystore.p12 -storetype PKCS12 \
  -dname "CN=localhost, OU=Dev, O=MyOrg, L=City, ST=State, C=US" \
  -storepass changeit -keypass changeit
```

Then modify `NanoServer.java` to enable HTTPS:

```java
public static void main(String[] args) {
  int port = 443;
  Path configPath = Path.of("routes.json");

  try {
    // Create SSL wrapper
    SslWrapper ssl = new SslWrapper("keystore.p12", "changeit");

    // Create server with HTTPS
    Router router = new Router(configPath);
    ServerLoop server = new ServerLoop(port, router, ssl);

    // Graceful shutdown
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("\n[Server] Shutting down...");
      server.stop();
    }));

    // Start
    router.loadConfig();
    router.startHotReloadWatcher();
    server.start();

  } catch (Exception e) {
    System.err.println("[Server] Failed to start: " + e.getMessage());
    System.exit(1);
  }
}
```

## Feature Examples

### 1. WebSocket Support

Your WebSocket clients can connect directly:

```javascript
// JavaScript
const ws = new WebSocket('wss://localhost:443/ws');

ws.onopen = () => {
    console.log('Connected!');
    ws.send('Hello Server');
};

ws.onmessage = (event) => {
    console.log('Received:', event.data);
};
```

The server automatically detects WebSocket upgrade requests and proxies them to your backend.

### 2. Admin API

Monitor and control your server:

```bash
# Check health
curl https://localhost:443/admin/health

# View metrics
curl https://localhost:443/admin/metrics

# Get statistics
curl https://localhost:443/admin/stats

# Reload configuration
curl -X POST https://localhost:443/admin/routes/reload
```

### 3. Rate Limiting

Rate limiting is enabled by default. Configure it in `Worker.java`:

```java
this.rateLimiter =new

RateLimiter(
    RateLimiter.Strategy.TOKEN_BUCKET,
    100,  // 100 requests per second per client
    Duration.ofSeconds(1)
);
```

Test it:

```bash
# This will eventually get rate limited
for i in {1..150}; do curl https://localhost:443/api; done
```

### 4. Circuit Breaker

Circuit breaker is automatically enabled. If a backend fails repeatedly, requests will fast-fail.

Monitor circuit status:

```bash
curl https://localhost:443/admin/circuits
```

Reset a circuit manually:

```bash
curl -X POST "https://localhost:443/admin/circuits/reset?backend=http://localhost:3000"
```

### 5. Compression

Compression is automatic. Test it:

```bash
# Request with compression
curl -H "Accept-Encoding: gzip, br" https://localhost:443/api/data

# Response will include:
# Content-Encoding: br
# or
# Content-Encoding: gzip
```

### 6. Let's Encrypt (Production)

For automatic HTTPS in production:

```java
public static void main(String[] args) {
  try {
    // Initialize ACME client
    AcmeClient acme = new AcmeClient(
        "admin@yourdomain.com",
        "yourdomain.com",
        "www.yourdomain.com"
    );

    // Obtain certificate
    Path certPath = acme.obtainCertificate();

    // Start auto-renewal
    acme.startAutoRenewal();

    // Create SSL wrapper with Let's Encrypt certificate
    SslWrapper ssl = new SslWrapper(certPath.toString(), "changeit");

    // Start server
    Router router = new Router(Path.of("routes.json"));
    ServerLoop server = new ServerLoop(443, router, ssl);

    router.loadConfig();
    router.startHotReloadWatcher();
    server.start();

  } catch (Exception e) {
    e.printStackTrace();
    System.exit(1);
  }
}
```

**Important**: For Let's Encrypt to work:

- Your domain must resolve to your server's IP
- Port 80 must be accessible for HTTP-01 challenge
- Use staging environment for testing

## Testing the Features

### 1. Test HTTP/2

```bash
# Check HTTP/2 support
curl --http2 -v https://localhost:443/

# Should show: "ALPN, server accepted to use h2"
```

### 2. Load Test with Rate Limiting

```bash
# Install wrk
brew install wrk  # macOS
apt-get install wrk  # Ubuntu

# Run load test
wrk -t12 -c400 -d30s https://localhost:443/api

# You should see some 429 (Too Many Requests) responses
```

### 3. Test Circuit Breaker

```bash
# Stop a backend server to trigger failures
# After 5 failures, circuit opens and requests fast-fail

# Monitor circuit status
watch -n 1 "curl -s https://localhost:443/admin/circuits | jq"
```

### 4. Test WebSocket

```bash
# Install websocat
brew install websocat

# Connect to WebSocket
websocat wss://localhost:443/ws

# Type messages and see responses
```

## Monitoring in Production

### Prometheus Setup

1. Install Prometheus
2. Configure scraping:

```yaml
# prometheus.yml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'nanoserver'
    static_configs:
      - targets: [ 'localhost:8080' ]
    metrics_path: '/metrics'
    scheme: https
    tls_config:
      insecure_skip_verify: true  # For self-signed certs
```

3. Start Prometheus:

```bash
prometheus --config.file=prometheus.yml
```

4. View metrics at `http://localhost:9090`

### Grafana Dashboard

1. Add Prometheus as data source
2. Import dashboard or create custom panels:
    - Request rate: `rate(nanoserver_requests_total[5m])`
    - Error rate: `rate(nanoserver_requests_by_status{status=~"5.."}[5m])`
    - Response time: `nanoserver_request_duration_ms`
    - Active connections: `nanoserver_active_connections`

## Performance Tuning

### JVM Options

```bash
java --enable-preview \
  -Xms2g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+UseStringDeduplication \
  -Djdk.virtualThreadScheduler.parallelism=16 \
  -jar build/libs/jnignx-1.0-SNAPSHOT.jar
```

### System Limits

```bash
# Increase file descriptors
ulimit -n 100000

# TCP settings
sysctl -w net.core.somaxconn=4096
sysctl -w net.ipv4.tcp_max_syn_backlog=4096
sysctl -w net.ipv4.ip_local_port_range="1024 65535"
```

### Native Compilation (Optional)

For maximum performance:

```bash
# Build native image
./gradlew nativeCompile

# Run
./build/native/nativeCompile/jnignx 443 routes.json
```

Benefits:

- 10x faster startup
- 5x lower memory usage
- No JVM warmup needed

## Troubleshooting

### Port 443 Permission Denied

On Linux/macOS, ports < 1024 require root:

```bash
# Option 1: Use sudo
sudo java -jar ...

# Option 2: Use authbind (Linux)
sudo apt-get install authbind
sudo touch /etc/authbind/byport/443
sudo chmod 500 /etc/authbind/byport/443
sudo chown $USER /etc/authbind/byport/443
authbind --deep java -jar ...

# Option 3: Use different port
java -jar ... 8443 routes.json
```

### Certificate Errors

If clients can't connect to HTTPS:

```bash
# Test certificate
openssl s_client -connect localhost:443 -showcerts

# For development, clients may need to accept self-signed certs:
curl -k https://localhost:443
```

### Rate Limiting Too Aggressive

Adjust the rate limiter settings in `Worker.java`:

```java
this.rateLimiter =new

RateLimiter(
    RateLimiter.Strategy.TOKEN_BUCKET,
    1000,  // Increase limit
    Duration.ofSeconds(1)
);
```

### High Memory Usage

Monitor with:

```bash
# Check memory
curl https://localhost:443/admin/stats | jq '.memory'

# JVM heap dump
jmap -dump:live,format=b,file=heap.bin <pid>
```

Reduce with:

- Lower rate limiter window size
- Shorter circuit breaker timeouts
- Smaller buffer sizes

## Next Steps

1. **Production Deployment**: Set up systemd service or Docker container
2. **Load Balancing**: Add multiple backend servers
3. **Monitoring**: Set up Prometheus + Grafana
4. **Security**: Add authentication to admin endpoints
5. **Scaling**: Deploy multiple instances behind a load balancer
6. **Caching**: Add Redis for distributed rate limiting

## Resources

- [Full Documentation](docs/NEW_FEATURES.md)
- [API Reference](docs/API.md)
- [Architecture Guide](docs/ARCHITECTURE.md)
- [GitHub Repository](#)

## Support

For issues and questions:

- GitHub Issues
- Email: support@example.com
- Documentation: docs/

---

**Happy Proxying! 🚀**
