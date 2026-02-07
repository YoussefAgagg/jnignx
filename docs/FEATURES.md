# Features Guide

This document covers each implemented feature in detail, including current capabilities and areas for improvement.

---

## Reverse Proxy

### Current Implementation

- HTTP/1.1 request forwarding via raw `SocketChannel` connections
- Header reconstruction: replaces `Host`, adds `X-Forwarded-For`, `X-Real-IP`, `X-Forwarded-Proto`
- Body forwarding for requests with `Content-Length`
- **Chunked Transfer Encoding** — supports `Transfer-Encoding: chunked` for both requests and responses
- **Connection Pooling** — infrastructure for reusing backend connections to reduce latency
- **Retry Logic** — failed proxy attempts are retried on alternate backends when available
- **Proper Error Responses** — clients receive `502 Bad Gateway` with CORS headers when backend connections fail
- Backend response relay runs in a separate virtual thread for non-blocking bidirectional transfer
- Buffers allocated off-heap with FFM API (`Arena` / `MemorySegment`)

### Remaining Improvements

- **Request Buffering** — large request bodies are not streamed; the entire initial buffer must fit in the 8 KB
  allocation

---

## Domain-Based Routing

### Current Implementation

- Routes requests based on the `Host` header to configured backends via the `domainRoutes` config section
- Domain matching is **case-insensitive** — `App.Example.COM` matches `app.example.com`
- Automatically **strips port** from the Host header (e.g., `app.example.com:8080` → `app.example.com`)
- **Priority over path routing** — domain routes are checked first; if no domain matches, path-based routing (`routes`)
  is used as fallback
- Each domain supports **multiple backends** with full load balancing (round-robin, weighted, least-connections,
  IP-hash)
- Domain route backends are registered with the **health checker** for active and passive monitoring
- Supports all backend types: HTTP proxy, static file serving (`file://`), and WebSocket proxying
- Works with **hot-reload** — add or remove domains by editing `routes.json` without restarting
- See the [Proxy Setup Guide](proxy-setup.md) and [Configuration Reference](configuration.md) for examples

### Remaining Improvements

- **Wildcard Domains** — no support for `*.example.com` patterns; each subdomain must be listed explicitly
- **Per-Domain TLS (SNI)** — currently all domains share the same TLS certificate; SNI-based cert selection is not
  implemented

---

## Load Balancing

### Current Implementation

Four strategies are available, configured via the `loadBalancer` field:

| Strategy             | Algorithm                                   | Use Case                   |
|----------------------|---------------------------------------------|----------------------------|
| Round-Robin          | `AtomicInteger` counter mod backend count   | Uniform workloads          |
| Weighted Round-Robin | Weight-based distribution across backends   | Mixed-capacity backends    |
| Least Connections    | `AtomicLong` per-backend connection counter | Variable request durations |
| IP Hash              | `clientIp.hashCode() % backends.size()`     | Sticky sessions            |

All strategies filter out unhealthy backends before selection, falling back to all backends if none are healthy.

**Weighted round-robin** is configured via the `backendWeights` section in the config file — see the
[Configuration Reference](configuration.md) for details.

### Remaining Improvements

- **Configurable Strategy per Route** — currently one global strategy; different routes may benefit from different
  strategies
- **Slow Start** — newly recovered backends receive full traffic immediately instead of being ramped up gradually

---

## Health Checking

### Current Implementation

**Active checks:**

- Sends `HEAD` requests to each HTTP backend at a configurable interval (default: 10 seconds)
- **Configurable Health Check Path** — set via `healthCheck.path` (default: `/`); backends can expose `/healthz` or
  `/ready`
- **Expected Status Codes** — configurable range via `healthCheck.expectedStatusMin` / `expectedStatusMax`
  (default: 200–399)
- **Configuration from JSON** — all health check parameters (interval, timeout, thresholds, path, status range) are read
  from `ServerConfig` rather than hardcoded
- Backends marked unhealthy after N consecutive failures (default: 3)
- Backends marked healthy after N consecutive successes (default: 2)
- `file://` backends are skipped
- Runs in background virtual threads

**Passive checks:**

- `Router.recordProxySuccess()` / `recordProxyFailure()` called after each real request
- Failure counts feed into the `HealthChecker.BackendHealth` state

### Remaining Improvements

- **Reload Notification** — no webhook or event mechanism to notify of health state changes

---

## TLS/HTTPS

### Current Implementation

- SSL termination using `javax.net.ssl.SSLEngine`
- Supports TLS 1.2 and TLS 1.3
- ALPN protocol negotiation (advertises `h2` and `http/1.1`)
- Loads certificates from PKCS12 or JKS keystores
- `SslWrapper.SslSession` provides `read()`, `write()`, `doHandshake()`, `close()`, `getNegotiatedProtocol()`

### Remaining Improvements

- **PEM Certificate Loading** — only Java KeyStore formats are supported; direct PEM/key file loading would be more
  convenient
- **SNI (Server Name Indication)** — no support for serving different certificates based on hostname
- **OCSP Stapling** — not implemented
- **Certificate Rotation** — replacing certificates requires server restart; hot-reload of TLS config is not supported
- **ACME/Let's Encrypt** — `AcmeClient` exists but is a skeleton with placeholder methods; all core ACME operations (
  account registration, order creation, challenge completion, certificate download) return hardcoded strings

---

## WebSocket Proxying

### Current Implementation

- Detects WebSocket upgrade requests (`Upgrade: websocket`, `Connection: Upgrade`)
- Validates `Sec-WebSocket-Key` and generates `Sec-WebSocket-Accept`
- Forwards the upgrade request to the backend
- Bidirectional frame relay: reads WebSocket frames from one side and writes to the other
- Handles frame opcodes: text, binary, close, ping, pong
- Supports masked and unmasked frames

### Remaining Improvements

- **Frame Fragmentation** — continuation frames are not reassembled
- **Per-Message Compression** — `permessage-deflate` extension is not supported
- **Subprotocol Negotiation** — `Sec-WebSocket-Protocol` is not forwarded/negotiated
- **Ping/Pong Keepalive** — no periodic ping to detect dead connections

---

## Rate Limiting

### Current Implementation

Three strategies available, configured via the `rateLimiter` section:

| Strategy       | Description                                                          |
|----------------|----------------------------------------------------------------------|
| Token Bucket   | Tokens refill at a steady rate; bursts allowed up to bucket capacity |
| Sliding Window | Counts requests in a moving time window                              |
| Fixed Window   | Counts requests in fixed time intervals                              |

Rate limiting is applied per client IP and path. Clients exceeding the limit receive `429 Too Many Requests`.

**Rate limit response headers** are included on every response:

- `X-RateLimit-Limit` — maximum requests allowed
- `X-RateLimit-Remaining` — remaining requests in the current window
- `X-RateLimit-Reset` — seconds until the rate limit resets

### Remaining Improvements

- **Per-Route Rate Limits** — currently one global rate limit; different routes may need different limits
- **Distributed Rate Limiting** — state is in-memory only; not shared across multiple JNignx instances
- **Configurable Key** — rate limiting is keyed on client IP; should support keying on headers (e.g., API key) or other
  attributes

---

## Circuit Breaker

### Current Implementation

Per-backend circuit breaker with three states:

| State     | Behavior                                                 |
|-----------|----------------------------------------------------------|
| Closed    | Requests pass through; failures are counted              |
| Open      | Requests immediately fail with `503 Service Unavailable` |
| Half-Open | Limited requests pass through to test recovery           |

Transitions:

- Closed → Open: after N consecutive failures (configurable `failureThreshold`)
- Open → Half-Open: after timeout period
- Half-Open → Closed: after successful test request
- Half-Open → Open: after failed test request

**Shared State** — circuit breaker instances are shared across all `Worker` connections using thread-safe singleton
initialization (double-checked locking), ensuring that circuit state is globally consistent.

**Admin API Integration** — `/admin/circuits` returns actual circuit breaker states for all registered backends.

**Metrics** — circuit breaker state changes are recorded in the metrics collector.

### Remaining Improvements

- **Half-Open Request Limit** — configurable number of test requests in HALF_OPEN state is not exposed in the config
  file

---

## CORS

### Current Implementation

- Configurable allowed origins, methods, headers, credentials, and max-age
- Automatic `OPTIONS` preflight response handling
- Origin validation against whitelist
- CORS headers added to all responses when enabled
- **CORS headers on error responses** — `429 Too Many Requests`, `502 Bad Gateway`, and `503 Service Unavailable`
  responses include proper CORS headers

### Remaining Improvements

- **Wildcard Origins** — no support for `*` or pattern-based origin matching
- **Per-Route CORS** — one global CORS policy; different routes may need different policies

---

## Static File Serving

### Current Implementation

- Serves files from `file://` backend paths
- Automatic MIME type detection for common types (HTML, CSS, JS, JSON, images, fonts, video, audio, etc.)
- Auto-generated directory listings when no `index.html` is present
- Gzip compression for text-based content types when client sends `Accept-Encoding: gzip`
- Path traversal protection (blocks `..` in paths, validates resolved path stays under root)
- **Range Requests** — `Range` header support (HTTP 206 Partial Content) for video streaming and download resumption
- **Conditional Requests** — `ETag` and `Last-Modified` headers are generated; `If-None-Match` and
  `If-Modified-Since` conditional requests return `304 Not Modified`
- **Custom Error Pages** — configurable custom error pages for 404 and 403 responses

### Remaining Improvements

- **Brotli Compression** — falls back to gzip; real Brotli requires adding the Brotli4j dependency

---

## Admin API

### Current Implementation

REST endpoints at `/admin/*`, protected by API key, Basic auth, and/or IP whitelist.

**The admin API is disabled by default.** To enable it, set `"enabled": true` in the `admin` section of the config file.

| Endpoint                 | Method | Description                                           |
|--------------------------|--------|-------------------------------------------------------|
| `/admin/health`          | GET    | Server health status (uptime, version)                |
| `/admin/metrics`         | GET    | Prometheus-format metrics                             |
| `/admin/stats`           | GET    | Server statistics (memory, threads, request counts)   |
| `/admin/routes`          | GET    | Current route configuration                           |
| `/admin/routes/reload`   | POST   | Reload configuration from disk                        |
| `/admin/circuits`        | GET    | Circuit breaker status (actual states per backend)    |
| `/admin/circuits/reset`  | POST   | Reset circuit breakers. Query param: `?backend=<url>` |
| `/admin/ratelimit`       | GET    | Rate limiter status (actual data)                     |
| `/admin/ratelimit/reset` | POST   | Reset rate limiters                                   |
| `/admin/backends`        | GET    | Backend health status                                 |
| `/admin/config`          | GET    | Server feature list                                   |
| `/admin/config/update`   | POST   | Update server configuration at runtime                |

**Proper HTTP status codes:**

- `404 Not Found` for unknown admin endpoints
- `405 Method Not Allowed` for wrong HTTP methods

### Remaining Improvements

- **Request Body Parsing** — POST endpoints don't fully parse request bodies for parameters

---

## Prometheus Metrics

### Current Implementation

`/metrics` endpoint exports Prometheus text format:

```
nanoserver_uptime_seconds
nanoserver_requests_total
nanoserver_requests_by_status{status="200"}
nanoserver_requests_by_path{path="/api"}
nanoserver_active_connections
nanoserver_bytes_received_total
nanoserver_bytes_sent_total
nanoserver_request_duration_ms_bucket{le="10"}
nanoserver_request_duration_ms_bucket{le="50"}
nanoserver_request_duration_ms_bucket{le="100"}
nanoserver_request_duration_ms_bucket{le="500"}
nanoserver_request_duration_ms_bucket{le="1000"}
nanoserver_request_duration_ms_bucket{le="+Inf"}
nanoserver_request_duration_ms_sum
nanoserver_request_duration_ms_count
```

**Per-backend metrics:**

- `nanoserver_backend_requests_total{backend="..."}` — per-backend request counts
- `nanoserver_backend_latency_ms{backend="..."}` — per-backend latency
- `nanoserver_backend_errors_total{backend="..."}` — per-backend error counts

**Circuit breaker and rate limiter metrics:**

- `nanoserver_circuit_breaker_state_changes` — circuit breaker state transitions
- `nanoserver_rate_limit_rejections` — rate limiter rejected requests

**Connection duration tracking:**

- `nanoserver_connection_duration_ms_sum` — total connection time
- `nanoserver_connection_duration_ms_count` — number of connections tracked

### Remaining Improvements

- **`bytes_sent`** — often recorded as 0 because response size is not captured from the proxy relay

---

## Structured Logging

### Current Implementation

JSON-formatted access logs to stdout:

```json
{
  "timestamp": "2026-01-16T10:30:45.123Z",
  "level": "INFO",
  "type": "access",
  "request_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
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

Also supports error and info log types.

**Request ID / Trace ID** — each request is assigned a UUID-based request ID for tracing across the proxy and backend.

### Remaining Improvements

- **Log to File** — currently stdout only; should support configurable file output and rotation
- **Log Level Configuration** — no way to control log verbosity

---

## Hot-Reload Configuration

### Current Implementation

- File watcher polls `routes.json` every 1 second for modifications
- Uses `AtomicReference` for lock-free config swap
- Active requests use old config; new requests use updated config
- New backends are registered for health checking on reload
- **Validation Before Swap** — `ConfigValidator` validates the new configuration before applying it; invalid configs
  are logged as warnings and not swapped in

### Remaining Improvements

- **Reload Notification** — no webhook or event mechanism to notify of reload success/failure
- **Partial Reload** — all configuration is reloaded; no way to update just routes without re-reading the entire file

---

## HTTP/2 (Stubbed)

### Current State

`Http2Handler` exists with:

- Binary frame reading/writing (9-byte header + payload)
- Frame type handling: SETTINGS, HEADERS, DATA, WINDOW_UPDATE, PING, GOAWAY, RST_STREAM
- Stream management (`Http2Stream` objects)
- Settings negotiation (SETTINGS frame exchange)

### What's Missing to Complete

- **HPACK Header Compression** — `receiveHeaders()` is empty; no HPACK encoder/decoder
- **Pipeline Integration** — `Http2Handler` is never instantiated by `Worker`; ALPN negotiation in `SslWrapper` detects
  `h2` but doesn't route to `Http2Handler`
- **Stream Multiplexing** — frame dispatch to Request objects is not connected
- **Flow Control** — window update handling exists but is incomplete
- **Server Push** — `createStream()` exists but is not connected to anything
- **Priority/Dependency** — not implemented
