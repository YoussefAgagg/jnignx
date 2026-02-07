# Admin API Reference

JNignx exposes REST endpoints under `/admin/*` for runtime management, plus two internal endpoints at the root level.

---

## Authentication

Admin endpoints (`/admin/*`) are protected when authentication is configured:

```json
{
  "admin": {
    "authentication": {
      "apiKey": "${ADMIN_API_KEY}",
      "ipWhitelist": [
        "127.0.0.1",
        "::1"
      ]
    }
  }
}
```

**Authentication methods:**

| Method       | Header                          | Example                                     |
|--------------|---------------------------------|---------------------------------------------|
| API Key      | `Authorization: Bearer <key>`   | `curl -H "Authorization: Bearer mykey" ...` |
| Basic Auth   | `Authorization: Basic <base64>` | `curl -u admin:password ...`                |
| IP Whitelist | *(automatic)*                   | Requests from whitelisted IPs bypass auth   |

If no authentication is configured, admin endpoints are open to all.

---

## Internal Endpoints

These endpoints are always available (no authentication required).

### `GET /health`

Server health check. Returns `200 OK` with a simple status.

```bash
curl http://localhost:8080/health
```

```json
{
  "status": "healthy"
}
```

### `GET /metrics`

Prometheus-compatible metrics in text exposition format.

```bash
curl http://localhost:8080/metrics
```

```
# HELP nanoserver_uptime_seconds Server uptime in seconds
# TYPE nanoserver_uptime_seconds counter
nanoserver_uptime_seconds 3600

# HELP nanoserver_requests_total Total HTTP requests
# TYPE nanoserver_requests_total counter
nanoserver_requests_total 12345

# HELP nanoserver_active_connections Current active connections
# TYPE nanoserver_active_connections gauge
nanoserver_active_connections 42

# HELP nanoserver_request_duration_ms Request duration histogram
# TYPE nanoserver_request_duration_ms histogram
nanoserver_request_duration_ms_bucket{le="10"} 5432
nanoserver_request_duration_ms_bucket{le="50"} 8765
nanoserver_request_duration_ms_bucket{le="100"} 10234
nanoserver_request_duration_ms_bucket{le="500"} 11890
nanoserver_request_duration_ms_bucket{le="1000"} 12100
nanoserver_request_duration_ms_bucket{le="+Inf"} 12345
nanoserver_request_duration_ms_sum 456789
nanoserver_request_duration_ms_count 12345

# HELP nanoserver_requests_by_status Requests by HTTP status code
# TYPE nanoserver_requests_by_status counter
nanoserver_requests_by_status{status="200"} 11000
nanoserver_requests_by_status{status="404"} 500
nanoserver_requests_by_status{status="500"} 45

# HELP nanoserver_bytes_received_total Total bytes received
# TYPE nanoserver_bytes_received_total counter
nanoserver_bytes_received_total 52428800

# HELP nanoserver_bytes_sent_total Total bytes sent
# TYPE nanoserver_bytes_sent_total counter
nanoserver_bytes_sent_total 104857600
```

---

## Admin Endpoints

All admin endpoints return JSON. All require authentication if configured.

> **Known limitation:** All responses currently return HTTP `200 OK` regardless of success/failure. Check the `success`
> field in the response body to determine the actual result.

### `GET /admin/health`

Detailed server health with uptime and version.

```bash
curl http://localhost:8080/admin/health
```

```json
{
  "status": "healthy",
  "uptime_seconds": 3600,
  "timestamp": "2026-02-07T14:00:00Z",
  "version": "1.0.0"
}
```

### `GET /admin/stats`

Server statistics including memory, threads, and request counts.

```bash
curl http://localhost:8080/admin/stats
```

```json
{
  "uptime_seconds": 3600,
  "memory": {
    "used_bytes": 52428800,
    "total_bytes": 268435456,
    "max_bytes": 2147483648,
    "free_bytes": 216006656
  },
  "threads": {
    "active": 150,
    "peak": 150,
    "total_started": 150
  },
  "requests": {
    "total": 12345,
    "active": 42
  }
}
```

### `GET /admin/metrics`

Same as `/metrics` — Prometheus-format metrics.

```bash
curl http://localhost:8080/admin/metrics
```

### `GET /admin/routes`

Returns the current route configuration (contents of the config file).

```bash
curl http://localhost:8080/admin/routes
```

```json
{
  "routes": {
    "/api": [
      "http://localhost:3000",
      "http://localhost:3001"
    ],
    "/": [
      "http://localhost:8080"
    ]
  },
  "loadBalancer": "round-robin"
}
```

### `POST /admin/routes/reload`

Triggers a configuration reload from disk.

```bash
curl -X POST http://localhost:8080/admin/routes/reload
```

```json
{
  "success": true,
  "message": "Routes reloaded successfully",
  "timestamp": "2026-02-07T14:00:00Z"
}
```

### `GET /admin/circuits`

Circuit breaker status.

> **Known limitation:** Currently returns an empty list. The circuit breaker state is not aggregated for reporting.

```bash
curl http://localhost:8080/admin/circuits
```

```json
{
  "circuits": []
}
```

### `POST /admin/circuits/reset`

Reset circuit breakers. Optionally specify a backend.

```bash
# Reset all circuit breakers
curl -X POST http://localhost:8080/admin/circuits/reset

# Reset for a specific backend
curl -X POST "http://localhost:8080/admin/circuits/reset?backend=http://localhost:3000"
```

```json
{
  "success": true,
  "message": "All circuit breakers reset",
  "timestamp": "2026-02-07T14:00:00Z"
}
```

### `GET /admin/ratelimit`

Rate limiter status.

> **Known limitation:** Currently returns placeholder data rather than actual rate limiter state.

```bash
curl http://localhost:8080/admin/ratelimit
```

```json
{
  "rate_limiter": {
    "enabled": true,
    "active_clients": 0
  }
}
```

### `POST /admin/ratelimit/reset`

Reset rate limiters.

```bash
curl -X POST http://localhost:8080/admin/ratelimit/reset
```

```json
{
  "success": true,
  "message": "Rate limiters reset",
  "timestamp": "2026-02-07T14:00:00Z"
}
```

### `GET /admin/config`

Server feature configuration summary.

```bash
curl http://localhost:8080/admin/config
```

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

### `POST /admin/config/update`

**Not implemented.** Returns an error response.

```bash
curl -X POST http://localhost:8080/admin/config/update
```

```json
{
  "success": false,
  "error": "Configuration updates not yet implemented",
  "timestamp": "2026-02-07T14:00:00Z"
}
```

---

## Error Responses

When a request fails or targets an unknown endpoint:

```json
{
  "success": false,
  "error": "Endpoint not found",
  "timestamp": "2026-02-07T14:00:00Z"
}
```

When the wrong HTTP method is used:

```json
{
  "success": false,
  "error": "Method not allowed",
  "timestamp": "2026-02-07T14:00:00Z"
}
```

When authentication fails, the server responds with `401 Unauthorized` before reaching the admin handler.
