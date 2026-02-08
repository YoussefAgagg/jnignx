# Configuration Reference

JNignx is configured through a single JSON file (default: `routes.json`). This document describes every available
option.

---

## Minimal Configuration

The only required field is `routes`:

```json
{
  "routes": {
    "/api": [
      "http://localhost:3000"
    ],
    "/": [
      "http://localhost:8080"
    ]
  }
}
```

## Full Configuration Example

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
      "http://localhost:8081",
      "http://localhost:8082"
    ]
  },
  "loadBalancer": "round-robin",
  "backendWeights": {
    "http://localhost:3000": 3,
    "http://localhost:3001": 1
  },
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
  "healthCheck": {
    "enabled": true,
    "intervalSeconds": 10,
    "timeoutSeconds": 5,
    "failureThreshold": 3,
    "successThreshold": 2,
    "path": "/healthz",
    "expectedStatusMin": 200,
    "expectedStatusMax": 299
  },
  "cors": {
    "enabled": true,
    "allowedOrigins": [
      "http://localhost:3000",
      "http://localhost:8080"
    ],
    "allowedMethods": [
      "GET",
      "POST",
      "PUT",
      "DELETE",
      "OPTIONS"
    ],
    "allowedHeaders": [
      "Content-Type",
      "Authorization"
    ],
    "allowCredentials": true,
    "maxAge": 3600
  },
  "admin": {
    "enabled": true,
    "authentication": {
      "apiKey": "${ADMIN_API_KEY}",
      "ipWhitelist": [
        "127.0.0.1",
        "::1"
      ]
    }
  },
  "timeouts": {
    "connection": 5,
    "request": 30,
    "idle": 300,
    "keepAlive": 120
  },
  "limits": {
    "maxRequestSize": 10485760,
    "maxResponseSize": 52428800,
    "bufferSize": 8192
  },
  "autoHttps": {
    "enabled": true,
    "email": "admin@example.com",
    "domains": [
      "example.com",
      "www.example.com"
    ],
    "staging": false,
    "certDir": "certs",
    "httpsPort": 443,
    "httpToHttpsRedirect": true,
    "allowedDomains": [
      "example.com",
      "*.example.com"
    ]
  }
}
```

---

## Option Reference

### `routes` (required)

Maps URL path prefixes to lists of backend URLs. Longest prefix match wins.

```json
{
  "routes": {
    "/api/v2": [
      "http://api-v2:3000"
    ],
    "/api": [
      "http://api-v1:3000",
      "http://api-v1:3001"
    ],
    "/static": [
      "file:///var/www/html"
    ],
    "/": [
      "http://default-backend:8080"
    ]
  }
}
```

**Backend URL formats:**

| Format                  | Description                       |
|-------------------------|-----------------------------------|
| `http://host:port`      | Proxy to HTTP backend             |
| `file:///absolute/path` | Serve static files from directory |

When multiple backends are listed for the same path, requests are distributed according to the load balancing strategy.

---

### `domainRoutes`

Maps domain names (Host header) to lists of backend URLs. Domain routing takes priority over path routing.

```json
{
  "domainRoutes": {
    "app.example.com": [
      "http://localhost:3000"
    ],
    "api.example.com": [
      "http://localhost:8081",
      "http://localhost:8082"
    ],
    "static.example.com": [
      "file:///var/www/static"
    ]
  }
}
```

**How it works:**

1. When a request arrives, JNignx reads the `Host` header
2. The port is stripped (e.g., `app.example.com:8080` → `app.example.com`)
3. The domain is matched case-insensitively against `domainRoutes` keys
4. If a match is found, the request is routed to the configured backend(s)
5. If no match is found, path-based routing (`routes`) is used as fallback

Domain routes support the same backend URL formats and load balancing strategies as path routes.

See the [Proxy Setup Guide](proxy-setup.md) for detailed domain routing examples.

---

### `loadBalancer`

Selects how requests are distributed across backends.

| Value                    | Description                                                 |
|--------------------------|-------------------------------------------------------------|
| `"round-robin"`          | Default. Cycles through backends evenly                     |
| `"weighted-round-robin"` | Distributes based on backend weights (see `backendWeights`) |
| `"least-connections"`    | Routes to backend with fewest active connections            |
| `"ip-hash"`              | Consistent hashing on client IP for sticky sessions         |

```json
{
  "loadBalancer": "least-connections"
}
```

---

### `backendWeights`

Assigns weights to backends for the weighted round-robin strategy. Higher weight means more traffic.

```json
{
  "loadBalancer": "weighted-round-robin",
  "backendWeights": {
    "http://localhost:3000": 3,
    "http://localhost:3001": 1
  }
}
```

In this example, `localhost:3000` receives 3× more requests than `localhost:3001`.

Backends not listed in the weights map default to weight 1.

---

### `rateLimiter`

Controls request rate limiting. When enabled, rate limit headers are included on every response:
`X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`.

| Field               | Type    | Default          | Description                                                       |
|---------------------|---------|------------------|-------------------------------------------------------------------|
| `enabled`           | boolean | `false`          | Enable rate limiting                                              |
| `requestsPerSecond` | int     | `1000`           | Maximum requests per second                                       |
| `burstSize`         | int     | `2000`           | Burst allowance above steady rate                                 |
| `strategy`          | string  | `"token-bucket"` | Algorithm: `"token-bucket"`, `"sliding-window"`, `"fixed-window"` |

```json
{
  "rateLimiter": {
    "enabled": true,
    "requestsPerSecond": 1000,
    "burstSize": 2000,
    "strategy": "token-bucket"
  }
}
```

---

### `circuitBreaker`

Prevents cascading failures by stopping requests to unhealthy backends.

| Field              | Type    | Default | Description                                                  |
|--------------------|---------|---------|--------------------------------------------------------------|
| `enabled`          | boolean | `false` | Enable circuit breaker                                       |
| `failureThreshold` | int     | `5`     | Consecutive failures before opening circuit                  |
| `timeout`          | int     | `30`    | Seconds before attempting to close circuit (half-open state) |

```json
{
  "circuitBreaker": {
    "enabled": true,
    "failureThreshold": 5,
    "timeout": 30
  }
}
```

---

### `healthCheck`

Configures active backend health monitoring.

| Field               | Type    | Default | Description                                             |
|---------------------|---------|---------|---------------------------------------------------------|
| `enabled`           | boolean | `true`  | Enable active health checks                             |
| `intervalSeconds`   | int     | `10`    | Seconds between health check probes                     |
| `timeoutSeconds`    | int     | `5`     | Seconds to wait for probe response                      |
| `failureThreshold`  | int     | `3`     | Consecutive failures to mark backend unhealthy          |
| `successThreshold`  | int     | `2`     | Consecutive successes to mark backend healthy again     |
| `path`              | string  | `"/"`   | HTTP path to probe (e.g., `"/healthz"`, `"/ready"`)     |
| `expectedStatusMin` | int     | `200`   | Minimum HTTP status code considered healthy (inclusive) |
| `expectedStatusMax` | int     | `399`   | Maximum HTTP status code considered healthy (inclusive) |

```json
{
  "healthCheck": {
    "enabled": true,
    "intervalSeconds": 10,
    "timeoutSeconds": 5,
    "failureThreshold": 3,
    "successThreshold": 2,
    "path": "/healthz",
    "expectedStatusMin": 200,
    "expectedStatusMax": 299
  }
}
```

Health checks send `HEAD <path>` requests to each HTTP backend. `file://` backends are skipped.

---

### `cors`

Cross-Origin Resource Sharing policy. CORS headers are included on all responses when enabled, including error
responses (429, 502, 503).

| Field              | Type     | Default | Description                                 |
|--------------------|----------|---------|---------------------------------------------|
| `enabled`          | boolean  | `false` | Enable CORS handling                        |
| `allowedOrigins`   | string[] | `[]`    | Origins allowed to make requests            |
| `allowedMethods`   | string[] | `[]`    | HTTP methods allowed                        |
| `allowedHeaders`   | string[] | `[]`    | Headers allowed in requests                 |
| `allowCredentials` | boolean  | `false` | Allow cookies/auth in cross-origin requests |
| `maxAge`           | int      | `3600`  | Seconds to cache preflight response         |

```json
{
  "cors": {
    "enabled": true,
    "allowedOrigins": [
      "https://example.com"
    ],
    "allowedMethods": [
      "GET",
      "POST",
      "PUT",
      "DELETE",
      "OPTIONS"
    ],
    "allowedHeaders": [
      "Content-Type",
      "Authorization"
    ],
    "allowCredentials": true,
    "maxAge": 3600
  }
}
```

---

### `admin`

Admin API configuration. The admin API is served under `/admin/*` and is **disabled by default**.

| Field                        | Type     | Default | Description                                                                     |
|------------------------------|----------|---------|---------------------------------------------------------------------------------|
| `enabled`                    | boolean  | `false` | Enable the admin API endpoints                                                  |
| `authentication.apiKey`      | string   | —       | API key for `Authorization: Bearer <key>` auth. Supports `${ENV_VAR}` expansion |
| `authentication.ipWhitelist` | string[] | `[]`    | IP addresses allowed to access admin API                                        |

```json
{
  "admin": {
    "enabled": true,
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

When `enabled` is `false` (the default), all requests to `/admin/*` return `404 Not Found`.

---

### `timeouts`

Connection and request timeout values in seconds.

| Field        | Type | Default | Description                                        |
|--------------|------|---------|----------------------------------------------------|
| `connection` | int  | `5`     | Timeout for establishing backend connection        |
| `request`    | int  | `30`    | Maximum time for a complete request/response cycle |
| `idle`       | int  | `300`   | Close idle connections after this many seconds     |
| `keepAlive`  | int  | `120`   | Keep-alive timeout for persistent connections      |

```json
{
  "timeouts": {
    "connection": 5,
    "request": 30,
    "idle": 300,
    "keepAlive": 120
  }
}
```

---

### `limits`

Request and response size limits.

| Field             | Type | Default    | Description                                 |
|-------------------|------|------------|---------------------------------------------|
| `maxRequestSize`  | int  | `10485760` | Maximum request body size in bytes (10 MB)  |
| `maxResponseSize` | int  | `52428800` | Maximum response body size in bytes (50 MB) |
| `bufferSize`      | int  | `8192`     | I/O buffer size in bytes (8 KB)             |

```json
{
  "limits": {
    "maxRequestSize": 10485760,
    "maxResponseSize": 52428800,
    "bufferSize": 8192
  }
}
```

---

### `autoHttps`

Enables Caddy-style automatic HTTPS with on-demand certificate provisioning via Let's Encrypt.

JNignx includes a built-in ACME v2 client (RFC 8555) that communicates directly with Let's Encrypt to obtain and
renew TLS certificates automatically — no external tools like `certbot` or `keytool` required.

When enabled, the server runs in **dual-port mode**: an HTTP port handles ACME HTTP-01 challenges and optionally
redirects to HTTPS, while the HTTPS port terminates TLS with automatically provisioned certificates.

| Field                 | Type     | Default   | Description                                                                                         |
|-----------------------|----------|-----------|-----------------------------------------------------------------------------------------------------|
| `enabled`             | boolean  | `false`   | Enable automatic HTTPS                                                                              |
| `email`               | string   | `""`      | Contact email for Let's Encrypt notifications (required for production)                             |
| `domains`             | string[] | `[]`      | Domains to pre-provision certificates for at startup                                                |
| `staging`             | boolean  | `false`   | Use Let's Encrypt staging environment (for testing)                                                 |
| `certDir`             | string   | `"certs"` | Directory for storing cached certificates                                                           |
| `httpsPort`           | int      | `443`     | Port for the HTTPS listener                                                                         |
| `httpToHttpsRedirect` | boolean  | `true`    | Redirect HTTP requests to HTTPS with `301 Moved Permanently`                                        |
| `allowedDomains`      | string[] | `[]`      | Domains allowed for on-demand cert issuance (empty = allow all). Supports `*.example.com` wildcards |

```json
{
  "autoHttps": {
    "enabled": true,
    "email": "admin@example.com",
    "domains": [
      "example.com",
      "www.example.com"
    ],
    "staging": false,
    "certDir": "certs",
    "httpsPort": 443,
    "httpToHttpsRedirect": true,
    "allowedDomains": [
      "example.com",
      "*.example.com"
    ]
  }
}
```

**How it works:**

1. Client connects to the HTTPS port (e.g., 443)
2. The TLS ClientHello contains an SNI hostname (e.g., `app.example.com`)
3. The `SniKeyManager` checks: is there a cached certificate for this domain?
    - **YES** → use cached cert, complete the TLS handshake
    - **NO** → check if the domain is in `allowedDomains`, then obtain a certificate via ACME on-the-fly, cache it,
      and complete the handshake
4. Normal HTTP routing proceeds over the encrypted connection
5. A background scheduler renews certificates 30 days before expiration

**Minimal auto-HTTPS config:**

```json
{
  "routes": {
    "/": [
      "http://localhost:3000"
    ]
  },
  "autoHttps": {
    "enabled": true,
    "email": "admin@example.com"
  }
}
```

This listens on HTTP port 8080 (for ACME challenges + redirect) and HTTPS port 443, allowing any domain.

---

## Environment Variable Expansion

String values support `${ENV_VAR}` syntax for environment variable substitution:

```json
{
  "admin": {
    "authentication": {
      "apiKey": "${ADMIN_API_KEY}"
    }
  }
}
```

---

## Hot-Reload

The configuration file is monitored for changes. When a modification is detected (polled every 1 second):

1. The new file is parsed and validated by `ConfigValidator`
2. If validation passes, the configuration is atomically swapped (no downtime)
3. New backends are registered with the health checker
4. Active requests continue with the old config; new requests use the updated config
5. If validation fails, the old config remains active and warnings are logged

No server restart is required.

---

## Command-Line Arguments

```bash
java --enable-preview -jar jnignx.jar [port] [config-file]
```

| Argument      | Default       | Description                |
|---------------|---------------|----------------------------|
| `port`        | `8080`        | Port to listen on          |
| `config-file` | `routes.json` | Path to configuration file |

Examples:

```bash
# Default: port 8080, routes.json
./gradlew run

# Custom port
./gradlew run --args="9090"

# Custom port and config
./gradlew run --args="9090 /etc/jnignx/routes.json"
```
