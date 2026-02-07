# JNignx — Java Reverse Proxy & Web Server

[![Java 25](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A high-performance reverse proxy and web server built with Java 25, leveraging Virtual Threads (Project Loom) and the
Foreign Function & Memory API (Project Panama) for high concurrency and low-latency I/O.

> **Project Status:** JNignx implements the core features needed for a reverse proxy. Some advanced features (HTTP/2,
> ACME/Let's Encrypt, Brotli) are partially implemented or stubbed.
> See [Production Readiness](docs/production-readiness.md) for a full gap analysis.

---

## Feature Status

### Fully Implemented

- **Reverse Proxy** — HTTP/1.1 request forwarding with `X-Forwarded-For`, `X-Real-IP`, `X-Forwarded-Proto` headers,
  chunked transfer encoding, retry logic with alternate backends, and proper `502 Bad Gateway` error responses
- **Virtual Threads** — one virtual thread per connection for massive concurrency
- **Off-Heap Memory (FFM API)** — `Arena` / `MemorySegment` buffers to reduce GC pressure
- **Load Balancing** — round-robin, weighted round-robin, least-connections, IP-hash (sticky sessions)
- **Health Checking** — active (periodic HEAD probes with configurable path and expected status codes) and passive
  (real-traffic failure tracking) with automatic failover and recovery
- **TLS/HTTPS** — SSL termination via `SSLEngine` with TLS 1.2/1.3 and ALPN negotiation
- **WebSocket Proxying** — upgrade detection, bidirectional frame-level relay
- **Rate Limiting** — token-bucket, sliding-window, and fixed-window strategies with `X-RateLimit-*` response headers
- **Circuit Breaker** — per-backend failure tracking with open/half-open/closed states, shared across all worker
  threads, with metrics and admin API integration
- **CORS** — configurable allowed origins, methods, headers, preflight handling, and CORS headers on error responses
- **Static File Serving** — MIME detection, directory listings, path-traversal protection, gzip/deflate compression,
  HTTP Range requests (206), conditional requests (ETag/Last-Modified → 304), custom error pages
- **Admin API** — REST endpoints for health, metrics, routes, circuit-breaker and rate-limiter management, backend
  health status, config updates; protected by API-key/Basic-auth/IP-whitelist; **disabled by default**
- **Prometheus Metrics** — `/metrics` endpoint with request counts, latency histograms, connection gauges, byte
  counters, per-backend metrics, circuit breaker/rate limiter metrics, connection duration tracking
- **Structured Logging** — JSON access logs to stdout with UUID-based request IDs for tracing
- **Hot-Reload** — file-watch on `routes.json` with atomic `AtomicReference` swap (zero-downtime), validation before
  swap
- **GraalVM Native Image** — compatible (no runtime reflection)

### Partially Implemented / Stubbed

| Feature              | Status   | Details                                                                                                         |
|----------------------|----------|-----------------------------------------------------------------------------------------------------------------|
| HTTP/2               | Stubbed  | Frame parser exists (`Http2Handler`) but lacks HPACK header decoding and is not wired into the request pipeline |
| ACME (Let's Encrypt) | Skeleton | `AcmeClient` class exists with placeholder methods; no real ACME protocol interaction                           |
| Brotli Compression   | Fallback | Attempts reflection-based Brotli4j lookup; falls back to gzip when unavailable                                  |

### Not Yet Implemented

- HTTP/3 (QUIC)
- Response caching (TTL, `Cache-Control`)
- Request/response body transformation (middleware pipeline)
- Blue/green deployment patterns
- Per-route load balancing strategy
- Per-route rate limits
- Configuration DSL ("Nanofile")

---

## Requirements

- **Java 25** with preview features enabled
- **GraalVM** (optional, for native image compilation)

## Quick Start

```bash
# Clone and build
git clone https://github.com/youssefagagg/jnignx.git
cd jnignx
./gradlew build

# Run (defaults: port 8080, config routes.json)
./gradlew run

# Custom port and config
./gradlew run --args="9090 routes-full.json"
```

Create a minimal `routes.json`:

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

See the [Quick Start Guide](docs/quickstart.md) for detailed setup instructions.

---

## Documentation

| Document                                             | Description                                                      |
|------------------------------------------------------|------------------------------------------------------------------|
| [Quick Start Guide](docs/quickstart.md)              | Installation, first run, basic configuration                     |
| [Configuration Reference](docs/configuration.md)     | All `routes.json` options explained                              |
| [Proxy Setup Guide](docs/proxy-setup.md)             | Domain-based routing, multi-app proxy setup                      |
| [Features Guide](docs/features.md)                   | Deep dive into each implemented feature and what can be improved |
| [Architecture](docs/architecture.md)                 | Internal design, threading model, memory management              |
| [Admin API Reference](docs/api.md)                   | REST endpoints for runtime management                            |
| [Production Readiness](docs/production-readiness.md) | Gap analysis, deployment guide, what's needed for production     |

---

## Contributing

Contributions are welcome! Priority areas:

1. Complete HTTP/2 support (HPACK decoding, stream multiplexing integration)
2. Implement real ACME client for automatic HTTPS
3. Add response caching
4. Per-route load balancing and rate limiting
5. Improve test coverage for edge cases

## License

MIT License
