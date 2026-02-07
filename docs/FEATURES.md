# Features Guide

This document covers each implemented feature in detail, including current capabilities and areas for improvement.

---

## Reverse Proxy

### Current Implementation

- HTTP/1.1 request forwarding via raw `SocketChannel` connections
- Header reconstruction: replaces `Host`, adds `X-Forwarded-For`, `X-Real-IP`, `X-Forwarded-Proto`
- Body forwarding for requests with `Content-Length`
- Backend response relay runs in a separate virtual thread for non-blocking bidirectional transfer
- Buffers allocated off-heap with FFM API (`Arena` / `MemorySegment`)

### Improvements Needed

- **Chunked Transfer Encoding** — the HTTP parser does not handle `Transfer-Encoding: chunked` requests or responses;
  only `Content-Length`-based bodies are forwarded correctly
- **Connection Pooling** — each request opens a new `SocketChannel` to the backend; reusing connections would
  significantly reduce latency
- **Retry Logic** — failed proxy attempts are not retried on alternate backends
- **Request Buffering** — large request bodies are not streamed; the entire initial buffer must fit in the 8 KB
  allocation
- **Error Responses** — when a backend connection fails, the client does not always receive a proper 502 Bad Gateway
  response

---

## Load Balancing

### Current Implementation

Three strategies are available, configured via the `loadBalancer` field:

| Strategy          | Algorithm                                   | Use Case                   |
|-------------------|---------------------------------------------|----------------------------|
| Round-Robin       | `AtomicInteger` counter mod backend count   | Uniform workloads          |
| Least Connections | `AtomicLong` per-backend connection counter | Variable request durations |
| IP Hash           | `clientIp.hashCode() % backends.size()`     | Sticky sessions            |

All strategies filter out unhealthy backends before selection, falling back to all backends if none are healthy.

### Improvements Needed

- **Weighted Round-Robin** — no support for assigning different weights to backends with different capacities
- **Configurable Strategy per Route** — currently one global strategy; different routes may benefit from different
  strategies
- **Slow Start** — newly recovered backends receive full traffic immediately instead of being ramped up gradually

---

## Health Checking

### Current Implementation

**Active checks:**

- Sends `HEAD /` to each HTTP backend at a configurable interval (default: 10 seconds)
- Backends marked unhealthy after N consecutive failures (default: 3)
- Backends marked healthy after N consecutive successes (default: 2)
- `file://` backends are skipped
- Runs in background virtual threads

**Passive checks:**

- `Router.recordProxySuccess()` / `recordProxyFailure()` called after each real request
- Failure counts feed into the `HealthChecker.BackendHealth` state

### Improvements Needed

- **Configurable Health Check Path** — currently hardcoded to `HEAD /`; backends may expose health at `/healthz` or
  `/ready`
- **Expected Status Codes** — any non-exception response is considered healthy; should support configuring expected
  status codes (e.g., 200-299)
- **Health Check via Config** — health check parameters are partially configurable via JSON but the `HealthChecker`
  class uses hardcoded constants rather than reading from `ServerConfig`

---

## TLS/HTTPS

### Current Implementation

- SSL termination using `javax.net.ssl.SSLEngine`
- Supports TLS 1.2 and TLS 1.3
- ALPN protocol negotiation (advertises `h2` and `http/1.1`)
- Loads certificates from PKCS12 or JKS keystores
- `SslWrapper.SslSession` provides `read()`, `write()`, `doHandshake()`, `close()`, `getNegotiatedProtocol()`

### Improvements Needed

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

### Improvements Needed

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

### Improvements Needed

- **Per-Route Rate Limits** — currently one global rate limit; different routes may need different limits
- **Response Headers** — `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` headers are not included in
  responses
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

### Improvements Needed

- **Shared State** — each `Worker` creates its own `CircuitBreaker` instance; circuit state is not shared across
  connections, reducing effectiveness
- **Admin API Integration** — `/admin/circuits` endpoint returns empty data; should report actual circuit states for all
  backends
- **Metrics** — circuit breaker state changes are not recorded in metrics

---

## CORS

### Current Implementation

- Configurable allowed origins, methods, headers, credentials, and max-age
- Automatic `OPTIONS` preflight response handling
- Origin validation against whitelist
- CORS headers added to all responses when enabled

### Improvements Needed

- **Wildcard Origins** — no support for `*` or pattern-based origin matching
- **Per-Route CORS** — one global CORS policy; different routes may need different policies
- **CORS Headers on Error Responses** — CORS headers are not always added to error responses (e.g., 502, 503)

---

## Static File Serving

### Current Implementation

- Serves files from `file://` backend paths
- Automatic MIME type detection for common types (HTML, CSS, JS, JSON, images, etc.)
- Auto-generated directory listings when no `index.html` is present
- Gzip compression for text-based content types when client sends `Accept-Encoding: gzip`
- Path traversal protection (blocks `..` in paths, validates resolved path stays under root)

### Improvements Needed

- **Range Requests** — `Range` header is not supported; needed for video streaming and download resumption
- **Cache Headers** — `ETag` and `Last-Modified` headers are not generated; `If-None-Match`/`If-Modified-Since`
  conditional requests are not handled
- **Brotli Compression** — falls back to gzip; real Brotli requires adding the Brotli4j dependency
- **Custom Error Pages** — 404 and 403 responses use hardcoded HTML; no support for custom error pages

---

## Admin API

### Current Implementation

REST endpoints at `/admin/*`, protected by API key, Basic auth, and/or IP whitelist:

| Endpoint                 | Method | Description                                           |
|--------------------------|--------|-------------------------------------------------------|
| `/admin/health`          | GET    | Server health status (uptime, version)                |
| `/admin/metrics`         | GET    | Prometheus-format metrics                             |
| `/admin/stats`           | GET    | Server statistics (memory, threads, request counts)   |
| `/admin/routes`          | GET    | Current route configuration                           |
| `/admin/routes/reload`   | POST   | Reload configuration from disk                        |
| `/admin/circuits`        | GET    | Circuit breaker status                                |
| `/admin/circuits/reset`  | POST   | Reset circuit breakers. Query param: `?backend=<url>` |
| `/admin/ratelimit`       | GET    | Rate limiter status                                   |
| `/admin/ratelimit/reset` | POST   | Reset rate limiters                                   |
| `/admin/config`          | GET    | Server feature list                                   |
| `/admin/config/update`   | POST   | **Not implemented** — returns error                   |

### Improvements Needed

- **Circuit Breaker Status** — `/admin/circuits` returns an empty list instead of actual circuit states
- **Rate Limiter Status** — `/admin/ratelimit` returns hardcoded placeholder data
- **Config Update** — `/admin/config/update` is not implemented
- **Response Status Codes** — all responses return `200 OK` even for errors; should use `400`, `404`, `405`
  appropriately
- **Backend Health in API** — no endpoint to view individual backend health status
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

### Improvements Needed

- **Backend-Specific Metrics** — no per-backend request counts, latency, or error rates
- **Circuit Breaker Metrics** — state changes not exported
- **Rate Limiter Metrics** — rejected requests not broken out
- **Connection Duration** — not tracked
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

### Improvements Needed

- **Log to File** — currently stdout only; should support configurable file output and rotation
- **Log Level Configuration** — no way to control log verbosity
- **Request ID / Trace ID** — no correlation ID for tracing requests across the proxy and backend

---

## Hot-Reload Configuration

### Current Implementation

- File watcher polls `routes.json` every 1 second for modifications
- Uses `AtomicReference` for lock-free config swap
- Active requests use old config; new requests use updated config
- New backends are registered for health checking on reload

### Improvements Needed

- **Validation Before Swap** — invalid config could be loaded; `ConfigValidator` exists but its integration with the
  reload path should be verified
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
