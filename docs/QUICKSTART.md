# Quick Start Guide

Get JNignx running in minutes.

---

## Prerequisites

- **Java 25** or later with preview features enabled
- A backend server to proxy to (e.g., any HTTP service running locally)

## Build

```bash
git clone https://github.com/youssefagagg/jnignx.git
cd jnignx
./gradlew build
```

## Configure

Create a `routes.json` file (or use the included example):

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
  },
  "domainRoutes": {
    "app.example.com": [
      "http://localhost:3000"
    ],
    "api.example.com": [
      "http://localhost:8081"
    ]
  }
}
```

**Path-based routing** (`routes`): Each key is a URL path prefix. Requests matching the prefix are forwarded to one
of the backends using round-robin load balancing.

**Domain-based routing** (`domainRoutes`): Routes requests by the `Host` header. Domain routing takes priority over
path routing. If no domain matches, path-based routing is used as fallback.

Use `file://` URLs to serve static files from a local directory.

For all configuration options, see the [Configuration Reference](configuration.md).

## Run

```bash
# Default: port 8080, reads routes.json
./gradlew run

# Custom port and config file
./gradlew run --args="9090 routes-full.json"
```

On startup the server will:

1. Load the configuration
2. Start health checking all HTTP backends
3. Begin accepting connections
4. Watch the config file for changes (hot-reload)

## Verify

```bash
# Proxy a request
curl http://localhost:8080/api/some-endpoint

# Check server health
curl http://localhost:8080/health

# View Prometheus metrics
curl http://localhost:8080/metrics
```

## Common Patterns

### Domain Routing — Multi-App Server

Route different domains to different applications running on the same server:

```json
{
  "routes": {
    "/": ["http://localhost:8080"]
  },
  "domainRoutes": {
    "app.example.com": ["http://localhost:3000"],
    "api.example.com": ["http://localhost:8081", "http://localhost:8082"],
    "admin.example.com": ["http://localhost:4000"]
  }
}
```

### API Gateway

Route different paths to different microservices:

```json
{
  "routes": {
    "/users": ["http://user-service:3000", "http://user-service:3001"],
    "/orders": ["http://order-service:4000"],
    "/payments": ["http://payment-service:5000"],
    "/": ["http://frontend:8080"]
  }
}
```

### Static + Dynamic Content

Serve static assets from disk and proxy API calls to a backend:

```json
{
  "routes": {
    "/api": ["http://api-server:3000"],
    "/assets": ["file:///var/www/static"],
    "/": ["http://app-server:8080"]
  }
}
```

### Automatic HTTPS with Let's Encrypt

Enable automatic TLS certificate provisioning — no manual certificate management needed:

```json
{
  "routes": {
    "/": ["http://localhost:3000"]
  },
  "domainRoutes": {
    "app.example.com": ["http://localhost:3000"],
    "api.example.com": ["http://localhost:8081"]
  },
  "autoHttps": {
    "enabled": true,
    "email": "admin@example.com",
    "staging": false,
    "httpsPort": 443,
    "httpToHttpsRedirect": true
  }
}
```

Start with:

```bash
sudo ./gradlew run --args="80 routes.json"
```

JNignx will automatically obtain certificates from Let's Encrypt, redirect HTTP to HTTPS,
and renew certificates before they expire. Use `"staging": true` for testing.

### Full-Featured Setup

Enable rate limiting, circuit breaker, CORS, and admin API:

```json
{
  "routes": {
    "/api": ["http://localhost:3000", "http://localhost:3001"],
    "/": ["http://localhost:8080"]
  },
  "loadBalancer": "round-robin",
  "rateLimiter": {
    "enabled": true,
    "requestsPerSecond": 1000,
    "burstSize": 2000,
    "strategy": "token-bucket"
  },
  "circuitBreaker": {
    "enabled": true,
    "failureThreshold": 5,
    "timeout": 30
  },
  "cors": {
    "enabled": true,
    "allowedOrigins": ["http://localhost:3000"],
    "allowedMethods": ["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    "allowedHeaders": ["Content-Type", "Authorization"],
    "allowCredentials": true,
    "maxAge": 3600
  },
  "admin": {
    "enabled": true,
    "authentication": {
      "apiKey": "${ADMIN_API_KEY}",
      "ipWhitelist": ["127.0.0.1", "::1"]
    }
  }
}
```

## Hot-Reload

Edit `routes.json` while the server is running. Changes are detected within 1 second and applied with zero downtime:

```bash
# Server is running...
vim routes.json   # Edit and save

# Check logs for confirmation:
# [Router] Configuration reloaded!
```

Active requests continue with the old config. New requests use the updated config.

## GraalVM Native Image (Optional)

Build a native binary for instant startup:

```bash
# Requires GraalVM
./gradlew nativeCompile

# Run (starts in <100ms)
./build/native/nativeCompile/jnignx 8080 routes.json
```

## Troubleshooting

### "Address already in use"

Another process is using the port:

```bash
lsof -i :8080
kill -9 <PID>
# Or use a different port:
./gradlew run --args="9090"
```

### Backends marked unhealthy

Health checks send `HEAD /` every 10 seconds. Ensure your backend responds to that request:

```bash
curl -I http://localhost:3000/
```

### High memory usage

Virtual threads are lightweight (~1 KB each), but check JVM heap:

```bash
jcmd <PID> GC.heap_info
# Consider ZGC for lower latency:
java -XX:+UseZGC --enable-preview -jar jnignx.jar
```

## Next Steps

- [Proxy Setup Guide](proxy-setup.md) — domain-based routing, multi-app proxy setup, production deployment
- [GraalVM Native Guide](graalvm-native-guide.md) — build native binary, deploy with HTTP/HTTPS/WebSocket
- [Configuration Reference](configuration.md) — all options explained
- [Features Guide](features.md) — deep dive into each feature
- [Admin API Reference](api.md) — runtime management endpoints
- [Architecture](architecture.md) — internal design
- [Production Readiness](production-readiness.md) — gap analysis and deployment guide
