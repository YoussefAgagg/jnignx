# Architecture

This document describes the internal design and implementation of JNignx.

---

## Overview

JNignx is a reverse proxy built on three pillars of modern Java:

1. **Virtual Threads (Project Loom)** — simple thread-per-connection model that scales to millions of concurrent
   connections
2. **Foreign Function & Memory API (Project Panama)** — off-heap buffer allocation that eliminates GC pressure from I/O
3. **GraalVM Native Image** — ahead-of-time compilation for instant startup and low memory footprint

---

## Threading Model

Unlike Nginx (event-loop with worker processes) or Netty (reactor pattern with callbacks), JNignx uses a **blocking
thread-per-connection** model powered by virtual threads.

```
Client connections
    │
    ▼
┌──────────────┐
│  ServerLoop   │  Blocking accept() on ServerSocketChannel
│  (main loop)  │
└──────┬───────┘
       │ For each accepted connection:
       │ Thread.startVirtualThread(new Worker(...))
       │
       ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│  Worker VT    │  │  Worker VT    │  │  Worker VT    │  ...millions
│  (virtual     │  │  (virtual     │  │  (virtual     │
│   thread)     │  │   thread)     │  │   thread)     │
└──────────────┘  └──────────────┘  └──────────────┘
```

**Why virtual threads instead of event loops:**

- Virtual threads cost ~1 KB each (vs ~1 MB for platform threads)
- Blocking I/O code is straightforward to write and reason about
- The JVM scheduler automatically multiplexes virtual threads onto carrier (OS) threads
- No callback chains, no reactive operators, no channel pipelines

---

## Request Pipeline

Each `Worker` virtual thread processes a single connection through this pipeline:

```
1. TLS Handshake (if HTTPS)
         │
2. Read & Parse HTTP Request (HttpParser)
         │
3. CORS Preflight Check
         │
4. Rate Limiting Check
         │
5. Admin API Check ──────► AdminHandler
         │
6. Internal Endpoints ───► /health, /metrics
         │
7. Route Resolution ─────► Router → LoadBalancer
         │
8. Circuit Breaker Check
         │
9. Request Dispatch:
   ├─ WebSocket Upgrade ─► WebSocketHandler (bidirectional proxy)
   ├─ Static File ────────► StaticHandler (file:// backends)
   └─ HTTP Proxy ─────────► ProxyHandler (reverse proxy to backend)
         │
10. Access Logging & Metrics Recording
         │
11. Keep-Alive Loop (repeat from step 2) or Close
```

---

## Memory Management

All I/O buffers are allocated off-heap using the Foreign Function & Memory API:

```java
try (Arena arena = Arena.ofConfined()) {
    MemorySegment buffer = arena.allocate(8192);
    ByteBuffer bb = buffer.asByteBuffer();
    // Use buffer for request/response I/O
} // Deterministic deallocation — no GC involved
```

**Benefits:**

- No GC pressure from I/O buffers
- Deterministic deallocation when the Arena closes
- Better cache locality for sequential I/O

---

## Key Components

### Package: `core/`

| Class            | Responsibility                                                                                             |
|------------------|------------------------------------------------------------------------------------------------------------|
| `ServerLoop`     | Main accept loop; binds `ServerSocketChannel`, spawns `Worker` virtual threads                             |
| `Worker`         | Full request pipeline: TLS, parsing, routing, CORS, rate limiting, circuit breaking, dispatch              |
| `Router`         | Route resolution, config loading, hot-reload file watcher, delegates to `LoadBalancer` and `HealthChecker` |
| `LoadBalancer`   | Backend selection: round-robin (AtomicInteger), least-connections (AtomicLong counters), IP-hash           |
| `HealthChecker`  | Active health probes (HEAD requests every 10s) + passive tracking from real traffic                        |
| `RateLimiter`    | Per-client/path rate limiting: token-bucket, sliding-window, fixed-window                                  |
| `CircuitBreaker` | Per-backend failure tracking with closed → open → half-open state machine                                  |

### Package: `handlers/`

| Class              | Responsibility                                                                                                            |
|--------------------|---------------------------------------------------------------------------------------------------------------------------|
| `ProxyHandler`     | Opens `SocketChannel` to backend, reconstructs headers with `X-Forwarded-*`, bidirectional data transfer                  |
| `StaticHandler`    | Serves files from `file://` backends with MIME detection, directory listings, gzip compression, path-traversal protection |
| `WebSocketHandler` | Detects `Upgrade: websocket`, performs handshake, relays frames bidirectionally between client and backend                |
| `AdminHandler`     | REST API for runtime management (health, metrics, routes, circuit breakers, rate limiters)                                |

### Package: `http/`

| Class            | Responsibility                                                               |
|------------------|------------------------------------------------------------------------------|
| `HttpParser`     | Parses HTTP/1.1 request line + headers from `MemorySegment`                  |
| `Request`        | Immutable record: method, path, version, headers, header length, body length |
| `Response`       | Response builder utility                                                     |
| `ResponseWriter` | Writes HTTP responses to `SocketChannel`                                     |
| `CorsConfig`     | CORS policy: allowed origins, methods, headers, credentials, max-age         |
| `BufferManager`  | Buffer pooling utilities                                                     |
| `Http2Handler`   | **Stubbed** — frame parser without HPACK; not integrated into pipeline       |

### Package: `tls/`

| Class        | Responsibility                                                            |
|--------------|---------------------------------------------------------------------------|
| `SslWrapper` | Wraps `SocketChannel` with `SSLEngine` for TLS termination; supports ALPN |
| `AcmeClient` | **Skeleton** — placeholder ACME protocol client for Let's Encrypt         |

### Package: `security/`

| Class       | Responsibility                                              |
|-------------|-------------------------------------------------------------|
| `AdminAuth` | Admin API authentication: API key, Basic auth, IP whitelist |

### Package: `util/`

| Class              | Responsibility                                                                                   |
|--------------------|--------------------------------------------------------------------------------------------------|
| `AccessLogger`     | JSON structured logging to stdout                                                                |
| `MetricsCollector` | Singleton; collects request counts, latency histograms, byte counters; exports Prometheus format |
| `CompressionUtil`  | Gzip and deflate compression; Brotli falls back to gzip                                          |
| `TimeoutManager`   | Connection and request timeout management                                                        |

### Package: `config/`

| Class             | Responsibility                                                                                                               |
|-------------------|------------------------------------------------------------------------------------------------------------------------------|
| `ConfigLoader`    | Hand-written JSON parser for `routes.json` (no external dependencies)                                                        |
| `ConfigValidator` | Validates configuration: URL formats, port ranges, threshold values                                                          |
| `RouteConfig`     | Route mapping record: path → list of backend URLs                                                                            |
| `ServerConfig`    | Full server config record: routes, load balancer strategy, rate limiter, circuit breaker, CORS, admin auth, timeouts, limits |

---

## Configuration Hot-Reload

```
routes.json modified on disk
        │
        ▼
Router.checkAndReload()        ← runs in a virtual thread, polls every 1 second
        │
        ▼
ConfigLoader.load(path)        ← parse new JSON
        │
        ▼
AtomicReference.set(newConfig) ← lock-free swap
        │
        ▼
HealthChecker.start(newBackends) ← register new backends for monitoring
```

Active requests continue using the old config reference; new requests pick up the new config. No locks, no downtime.

---

## Data Flow: Reverse Proxy

```
Client ──TCP──► JNignx Worker ──TCP──► Backend Server
  │                  │                      │
  │  HTTP Request    │  Reconstructed       │
  │ ──────────────►  │  Request + Headers   │
  │                  │ ──────────────────►   │
  │                  │                      │
  │                  │  HTTP Response        │
  │  HTTP Response   │ ◄────────────────    │
  │ ◄──────────────  │                      │
```

The `ProxyHandler`:

1. Opens a `SocketChannel` to the backend
2. Reconstructs request headers, adding `X-Forwarded-For`, `X-Real-IP`, `X-Forwarded-Proto`, updating `Host`
3. Forwards the request body
4. Spawns a virtual thread to relay the backend response back to the client
5. Waits for the response relay to complete

---

## Directory Structure

```
src/main/java/com/github/youssefagagg/jnignx/
├── NanoServer.java            # Entry point, CLI argument parsing, startup
├── config/
│   ├── ConfigLoader.java      # JSON parser (zero dependencies)
│   ├── ConfigValidator.java   # Configuration validation
│   ├── RouteConfig.java       # Route mapping record
│   └── ServerConfig.java      # Full config record
├── core/
│   ├── ServerLoop.java        # Accept loop
│   ├── Worker.java            # Request pipeline
│   ├── Router.java            # Routing + hot-reload
│   ├── LoadBalancer.java      # 3 strategies
│   ├── HealthChecker.java     # Active + passive checks
│   ├── RateLimiter.java       # 3 strategies
│   └── CircuitBreaker.java    # State machine
├── handlers/
│   ├── ProxyHandler.java      # Reverse proxy
│   ├── StaticHandler.java     # File serving
│   ├── WebSocketHandler.java  # WebSocket relay
│   └── AdminHandler.java      # Admin REST API
├── http/
│   ├── HttpParser.java        # HTTP/1.1 parser
│   ├── Request.java           # Request record
│   ├── Response.java          # Response builder
│   ├── ResponseWriter.java    # Response writer
│   ├── CorsConfig.java        # CORS policy
│   ├── BufferManager.java     # Buffer pooling
│   └── Http2Handler.java      # Stubbed HTTP/2
├── tls/
│   ├── SslWrapper.java        # TLS termination
│   └── AcmeClient.java        # Skeleton ACME client
├── security/
│   └── AdminAuth.java         # Admin authentication
└── util/
    ├── AccessLogger.java      # JSON logging
    ├── MetricsCollector.java   # Prometheus metrics
    ├── CompressionUtil.java   # Gzip/Deflate compression
    └── TimeoutManager.java    # Timeout management
```
