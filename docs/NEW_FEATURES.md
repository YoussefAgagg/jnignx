# NanoServer New Features Guide

This document describes the newly implemented features in NanoServer that make it a complete Nginx/Caddy alternative.

## Table of Contents

1. [HTTP/2 Support](#http2-support)
2. [WebSocket Proxying](#websocket-proxying)
3. [Rate Limiting](#rate-limiting)
4. [Circuit Breaker](#circuit-breaker)
5. [Compression (Gzip/Brotli)](#compression)
6. [Let's Encrypt (ACME)](#lets-encrypt-acme)
7. [Admin API](#admin-api)
8. [Enhanced HTTPS Support](#enhanced-https-support)

---

## HTTP/2 Support

NanoServer now supports HTTP/2 with ALPN (Application-Layer Protocol Negotiation) for multiplexed connections.

### Features

- **Stream Multiplexing**: Multiple requests over single connection
- **Server Push**: Proactive resource delivery
- **HPACK Compression**: Efficient header compression
- **Flow Control**: Per-stream and connection-level control
- **Priority Management**: Request prioritization

### Configuration

HTTP/2 is automatically enabled when using HTTPS with ALPN:

```java
SslWrapper ssl = new SslWrapper("keystore.p12", "password");
ServerLoop server = new ServerLoop(443, router, ssl);
server.

start();
```

The SSL wrapper negotiates HTTP/2 via ALPN during TLS handshake. If the client doesn't support HTTP/2, it falls back to
HTTP/1.1.

### Implementation Details

The `Http2Handler` class implements:

- Binary framing layer
- Frame parsing and generation
- Stream state management
- SETTINGS frame handling
- PING/PONG for keepalive
- GOAWAY for graceful shutdown

---

## WebSocket Proxying

Full WebSocket protocol support with transparent proxying to backend servers.

### Features

- **RFC 6455 Compliant**: Full WebSocket protocol implementation
- **Transparent Proxying**: Automatic detection and proxying
- **Bidirectional**: Full-duplex communication
- **Frame Handling**: Text, binary, ping/pong, close frames
- **Virtual Thread Compatible**: Efficient resource usage

### Usage

WebSocket connections are automatically detected from the `Upgrade: websocket` header:

```json
{
  "routes": {
    "/ws": [
      "ws://backend:8080"
    ],
    "/chat": [
      "ws://chat-server:9000"
    ]
  }
}
```

### How It Works

1. Client sends HTTP upgrade request
2. Server validates WebSocket handshake
3. Forwards upgrade to backend
4. Establishes bidirectional proxy
5. Frames are transparently forwarded in both directions

### Example Client Code

```javascript
// JavaScript WebSocket client
const ws = new WebSocket('wss://your-server.com/ws');

ws.onopen = () => {
    console.log('Connected');
    ws.send('Hello Server!');
};

ws.onmessage = (event) => {
    console.log('Received:', event.data);
};
```

---

## Rate Limiting

Protect your server from traffic spikes and DoS attacks with flexible rate limiting.

### Algorithms

#### 1. Token Bucket

Best for smooth rate limiting with burst capacity.

```java
RateLimiter limiter = new RateLimiter(
    RateLimiter.Strategy.TOKEN_BUCKET,
    100,  // 100 requests
    Duration.ofSeconds(1)  // per second
);
```

**Characteristics:**

- Allows bursts up to bucket capacity
- Tokens refill at constant rate
- Smooth rate limiting
- Memory efficient

#### 2. Sliding Window

Most accurate time-based limiting.

```java
RateLimiter limiter = new RateLimiter(
    RateLimiter.Strategy.SLIDING_WINDOW,
    1000,  // 1000 requests
    Duration.ofMinutes(1)  // per minute
);
```

**Characteristics:**

- Precise time-window tracking
- No burst allowance
- Slightly higher memory usage
- Best for strict limits

#### 3. Fixed Window

Simple and efficient.

```java
RateLimiter limiter = new RateLimiter(
    RateLimiter.Strategy.FIXED_WINDOW,
    10000,  // 10000 requests
    Duration.ofHours(1)  // per hour
);
```

**Characteristics:**

- Resets at window boundaries
- Very memory efficient
- Allows edge-case bursts
- Simplest implementation

### Per-Path Rate Limiting

```java
// Limit by IP and path
boolean allowed = limiter.allowRequest(clientIp, "/api/expensive");
```

### Response

When rate limited, clients receive:

```
HTTP/1.1 429 Too Many Requests
Retry-After: 1
```

---

## Circuit Breaker

Prevent cascading failures with automatic circuit breaker pattern.

### States

```
CLOSED → (failures ≥ threshold) → OPEN
OPEN → (timeout elapsed) → HALF_OPEN
HALF_OPEN → (success) → CLOSED
HALF_OPEN → (failure) → OPEN
```

### Configuration

```java
CircuitBreaker breaker = new CircuitBreaker(
    5,                          // failure threshold
    Duration.ofSeconds(30),     // timeout before half-open
    Duration.ofMinutes(5),      // reset timeout
    3                           // half-open test requests
);
```

### Usage

#### Automatic Mode

Circuit breaker is automatically applied to all backend requests in the Worker class.

#### Manual Mode

```java
try{
String result = breaker.execute("http://backend:8080", () -> {
  // Your backend call
  return httpClient.send(request);
});
}catch(
CircuitOpenException e){
    // Handle fast failure
    return

fallbackResponse();
}
```

### Monitoring

Check circuit status via Admin API:

```bash
curl http://localhost:8080/admin/circuits
```

Response:

```json
{
  "circuits": [
    {
      "backend": "http://backend:8080",
      "state": "CLOSED",
      "failure_count": 0,
      "success_count": 1250,
      "success_rate": 1.0
    }
  ]
}
```

---

## Compression

Automatic response compression to reduce bandwidth.

### Supported Algorithms

1. **Brotli** (`br`) - Best compression, slower
2. **Gzip** (`gzip`) - Good compression, widely supported
3. **Deflate** (`deflate`) - Standard compression

### Automatic Selection

The server automatically selects the best algorithm based on:

- Client's `Accept-Encoding` header
- Content type (only text-based content)
- Response size (minimum 1KB)

### Compressed Content Types

- `text/*` (html, css, plain, etc.)
- `application/javascript`
- `application/json`
- `application/xml`
- `image/svg+xml`

### Example

Request:

```http
GET /api/data HTTP/1.1
Accept-Encoding: br, gzip, deflate
```

Response:

```http
HTTP/1.1 200 OK
Content-Encoding: br
Content-Length: 1234
```

### Performance

Typical compression ratios:

- JSON: 70-80% reduction
- HTML: 60-75% reduction
- JavaScript: 65-75% reduction
- CSS: 70-80% reduction

---

## Let's Encrypt (ACME)

Automatic HTTPS certificate provisioning and renewal (experimental).

### Features

- Automatic certificate issuance
- Auto-renewal before expiration
- HTTP-01 challenge support
- Multi-domain (SAN) certificates
- Zero-downtime updates

### Usage

```java
// Initialize ACME client
AcmeClient acme = new AcmeClient(
        "admin@example.com",      // Contact email
        false,                     // Use production (true = staging)
        "example.com",            // Domains
        "www.example.com"
    );

// Obtain certificate
Path certPath = acme.obtainCertificate();

// Start auto-renewal (checks daily)
acme.

startAutoRenewal();

// Use the certificate
SslWrapper ssl = new SslWrapper(certPath.toString(), "changeit");
ServerLoop server = new ServerLoop(443, router, ssl);
```

### How It Works

1. Generate account key pair
2. Register with Let's Encrypt
3. Create certificate order
4. Complete HTTP-01 challenge
    - Server responds at `/.well-known/acme-challenge/<token>`
5. Finalize order with CSR
6. Download and install certificate
7. Auto-renew 30 days before expiration

### HTTP-01 Challenge

The server automatically handles ACME challenges at:

```
GET /.well-known/acme-challenge/<token>
```

No configuration needed - the challenge handler is integrated.

### Testing

Use Let's Encrypt staging for testing:

```java
AcmeClient acme = new AcmeClient(email, true, domains); // staging = true
```

### Limitations

- HTTP-01 challenge requires port 80 accessible
- Domain must resolve to your server
- Rate limits: 50 certificates per domain per week

---

## Admin API

RESTful API for runtime management and monitoring.

### Endpoints

#### Health Check

```bash
curl http://localhost:8080/admin/health
```

Response:

```json
{
  "status": "healthy",
  "uptime_seconds": 3600,
  "timestamp": "2026-01-16T10:30:00Z",
  "version": "1.0.0"
}
```

#### Server Statistics

```bash
curl http://localhost:8080/admin/stats
```

Response:

```json
{
  "uptime_seconds": 3600,
  "memory": {
    "used_bytes": 104857600,
    "total_bytes": 268435456,
    "max_bytes": 4294967296
  },
  "threads": {
    "active": 42,
    "peak": 50
  },
  "requests": {
    "total": 150000,
    "active": 12
  }
}
```

#### Metrics (Prometheus)

```bash
curl http://localhost:8080/admin/metrics
```

#### Route Configuration

```bash
# View current routes
curl http://localhost:8080/admin/routes

# Reload routes from file
curl -X POST http://localhost:8080/admin/routes/reload
```

#### Circuit Breaker Management

```bash
# View circuit status
curl http://localhost:8080/admin/circuits

# Reset specific circuit
curl -X POST "http://localhost:8080/admin/circuits/reset?backend=http://backend:8080"

# Reset all circuits
curl -X POST http://localhost:8080/admin/circuits/reset
```

#### Rate Limiter Status

```bash
curl http://localhost:8080/admin/ratelimit
```

#### Server Configuration

```bash
curl http://localhost:8080/admin/config
```

Response:

```json
{
  "server": {
    "version": "1.0.0",
    "features": [
      "http/1.1",
      "http/2",
      "websocket",
      "tls",
      "load_balancing",
      "health_checking",
      "circuit_breaker",
      "rate_limiting",
      "compression"
    ]
  }
}
```

### Security

⚠️ **Important**: The Admin API should be protected in production:

1. **Network isolation**: Bind to localhost only
2. **Authentication**: Add token-based auth
3. **TLS**: Always use HTTPS
4. **Firewall**: Restrict access to admin endpoints

Example protection:

```java
if(request.path().

startsWith("/admin/")){
String token = request.headers().get("Authorization");
    if(!

isValidToken(token)){
    return

unauthorized();
    }
        }
```

---

## Enhanced HTTPS Support

Full TLS/SSL support with modern features.

### Features

- **TLS 1.2 and 1.3**: Modern protocol versions
- **ALPN**: HTTP/2 protocol negotiation
- **SNI**: Server Name Indication
- **Perfect Forward Secrecy**: Ephemeral key exchange
- **Strong Ciphers**: Secure cipher suite selection

### Configuration

#### Basic HTTPS

```java
SslWrapper ssl = new SslWrapper("keystore.p12", "password");
ServerLoop httpsServer = new ServerLoop(443, router, ssl);
httpsServer.

start();
```

#### Generate Self-Signed Certificate

```bash
keytool -genkeypair \
  -alias server \
  -keyalg RSA \
  -keysize 2048 \
  -validity 365 \
  -keystore keystore.p12 \
  -storetype PKCS12 \
  -dname "CN=localhost, OU=Dev, O=MyOrg, L=City, ST=State, C=US"
```

#### Multi-Port Setup

Run HTTP and HTTPS simultaneously:

```java
// HTTP on port 80
ServerLoop httpServer = new ServerLoop(80, router);
Thread.

startVirtualThread(() ->{
    try{
    httpServer.

start();
    }catch(
IOException e){
    e.

printStackTrace();
    }
        });

// HTTPS on port 443
SslWrapper ssl = new SslWrapper("keystore.p12", "password");
ServerLoop httpsServer = new ServerLoop(443, router, ssl);
httpsServer.

start();
```

### Cipher Suites

Recommended cipher suites are automatically selected:

- `TLS_AES_256_GCM_SHA384`
- `TLS_AES_128_GCM_SHA256`
- `TLS_CHACHA20_POLY1305_SHA256`
- `TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384`
- `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`

### Testing HTTPS

```bash
# Test with curl
curl -k https://localhost:443

# Check TLS version
openssl s_client -connect localhost:443 -tls1_3

# Check ALPN negotiation
curl -v --http2 https://localhost:443
```

---

## Performance Comparison

### vs Nginx

| Feature            | NanoServer    | Nginx              |
|--------------------|---------------|--------------------|
| Virtual Threads    | ✅ Yes         | ❌ Event loop       |
| HTTP/2             | ✅ Yes         | ✅ Yes              |
| WebSocket          | ✅ Transparent | ⚠️ Module required |
| Rate Limiting      | ✅ Built-in    | ⚠️ Module required |
| Circuit Breaker    | ✅ Built-in    | ❌ External         |
| Admin API          | ✅ RESTful     | ⚠️ Limited         |
| Hot Reload         | ✅ Atomic      | ✅ Graceful         |
| Native Compilation | ✅ GraalVM     | ❌ No               |

### vs Caddy

| Feature       | NanoServer      | Caddy       |
|---------------|-----------------|-------------|
| Auto HTTPS    | ⚠️ Experimental | ✅ Built-in  |
| HTTP/2        | ✅ Yes           | ✅ Yes       |
| WebSocket     | ✅ Transparent   | ✅ Yes       |
| Performance   | ⚠️ Comparable   | ✅ Excellent |
| Configuration | ⚠️ JSON         | ✅ Caddyfile |
| Language      | ☕ Java          | 🐹 Go       |
| Memory Usage  | ⚠️ JVM          | ✅ Low       |

---

## Migration Guide

### From Nginx

**Nginx:**

```nginx
upstream backend {
    server backend1:8080;
    server backend2:8080;
    least_conn;
}

server {
    listen 443 ssl http2;
    ssl_certificate cert.pem;
    ssl_certificate_key key.pem;
    
    location /api {
        proxy_pass http://backend;
        proxy_set_header X-Forwarded-For $remote_addr;
    }
    
    location /ws {
        proxy_pass http://backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

**NanoServer:**

```java
// routes.json
{
    "routes":{
    "/api":[
    "http://backend1:8080",
    "http://backend2:8080"
    ],
    "/ws":[
    "ws://backend1:8080"
    ]
    }
    }

// Main.java
Router router = new Router(
    Path.of("routes.json"),
    LoadBalancer.Strategy.LEAST_CONNECTIONS
);

    SslWrapper ssl = new SslWrapper("keystore.p12", "password");
    ServerLoop server = new ServerLoop(443, router, ssl);
server.

    start();
```

### From Caddy

**Caddy:**

```caddyfile
example.com {
    reverse_proxy /api* backend1:8080 backend2:8080 {
        lb_policy least_conn
        health_uri /health
        health_interval 10s
    }
}
```

**NanoServer:**

```java
// Same routes.json as above
Router router = new Router(
        Path.of("routes.json"),
        LoadBalancer.Strategy.LEAST_CONNECTIONS
    );

// Auto HTTPS (experimental)
AcmeClient acme = new AcmeClient("admin@example.com", "example.com");
Path cert = acme.obtainCertificate();
acme.

startAutoRenewal();

SslWrapper ssl = new SslWrapper(cert.toString(), "changeit");
ServerLoop server = new ServerLoop(443, router, ssl);
server.

start();
```

---

## Best Practices

### 1. Production Deployment

```java
// Enable all production features
Router router = new Router(
        Path.of("/etc/nanoserver/routes.json"),
        LoadBalancer.Strategy.LEAST_CONNECTIONS
    );

RateLimiter rateLimiter = new RateLimiter(
    RateLimiter.Strategy.TOKEN_BUCKET,
    1000,
    Duration.ofSeconds(1)
);

CircuitBreaker breaker = new CircuitBreaker();

// Auto HTTPS
AcmeClient acme = new AcmeClient(
    System.getenv("ADMIN_EMAIL"),
    "example.com"
);
Path cert = acme.obtainCertificate();
acme.

startAutoRenewal();

// Start servers
SslWrapper ssl = new SslWrapper(cert.toString(), "changeit");
ServerLoop httpsServer = new ServerLoop(443, router, ssl);
ServerLoop httpServer = new ServerLoop(80, router); // Redirect to HTTPS

// Graceful shutdown
Runtime.

getRuntime().

addShutdownHook(new Thread(() ->{
    httpsServer.

stop();
    httpServer.

stop();
    breaker.

clear();
    rateLimiter.

shutdown();
}));
```

### 2. Monitoring

Set up Prometheus scraping:

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'nanoserver'
    static_configs:
      - targets: [ 'localhost:8080' ]
    metrics_path: '/metrics'
```

### 3. Load Testing

Use wrk or k6:

```bash
# wrk
wrk -t12 -c400 -d30s https://localhost:443/api

# k6
k6 run --vus 1000 --duration 30s loadtest.js
```

### 4. Tuning

```bash
# JVM options
java --enable-preview \
  -Xms2g -Xmx4g \
  -XX:+UseG1GC \
  -Djdk.virtualThreadScheduler.parallelism=16 \
  -jar nanoserver.jar

# System limits
ulimit -n 100000
sysctl -w net.core.somaxconn=4096
```

---

## Troubleshooting

### WebSocket Connections Dropping

**Problem**: WebSocket connections disconnect after a few minutes.

**Solution**: Check timeout settings and add keepalive:

```java
// Client-side: Send periodic pings
setInterval(() =>ws.

send('ping'), 30000);
```

### Circuit Breaker Too Sensitive

**Problem**: Circuit opens too frequently.

**Solution**: Increase failure threshold or timeout:

```java
CircuitBreaker breaker = new CircuitBreaker(
    10,  // Higher threshold
    Duration.ofMinutes(2),  // Longer timeout
    Duration.ofMinutes(10),
    5
);
```

### Rate Limiting False Positives

**Problem**: Legitimate traffic gets rate limited.

**Solution**: Adjust strategy or increase limits:

```java
// Use sliding window for precise limits
RateLimiter limiter = new RateLimiter(
        RateLimiter.Strategy.SLIDING_WINDOW,
        5000,  // Higher limit
        Duration.ofMinutes(1)
    );
```

### HTTPS Performance Issues

**Problem**: HTTPS is slower than expected.

**Solution**:

1. Use GraalVM native image
2. Enable TLS 1.3
3. Check cipher suite performance
4. Consider hardware acceleration

---

## Future Enhancements

- [ ] Redis-backed rate limiting for distributed setups
- [ ] Advanced caching strategies (LRU, LFU)
- [ ] Request/Response buffering options
- [ ] gRPC support
- [ ] Advanced load balancing (weighted, random)
- [ ] Custom health check configurations
- [ ] Metrics persistence and dashboards
- [ ] Docker and Kubernetes integration
- [ ] Configuration validation and testing tools

---

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

MIT License - see [LICENSE](LICENSE) for details.
