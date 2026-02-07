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
  "healthCheck": {
    "enabled": true,
    "interval": 10,
    "timeout": 5,
    "failureThreshold": 3,
    "successThreshold": 2
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

### `loadBalancer`

Selects how requests are distributed across backends.

| Value                 | Description                                         |
|-----------------------|-----------------------------------------------------|
| `"round-robin"`       | Default. Cycles through backends evenly             |
| `"least-connections"` | Routes to backend with fewest active connections    |
| `"ip-hash"`           | Consistent hashing on client IP for sticky sessions |

```json
{
  "loadBalancer": "least-connections"
}
```

---

### `rateLimiter`

Controls request rate limiting.

| Field               | Type    | Default          | Description                                                       |
|---------------------|---------|------------------|-------------------------------------------------------------------|
| `enabled`           | boolean | `false`          | Enable rate limiting                                              |
| `requestsPerSecond` | int     | —                | Maximum requests per second                                       |
| `burstSize`         | int     | —                | Burst allowance above steady rate                                 |
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

| Field              | Type    | Default | Description                                         |
|--------------------|---------|---------|-----------------------------------------------------|
| `enabled`          | boolean | `true`  | Enable active health checks                         |
| `interval`         | int     | `10`    | Seconds between health check probes                 |
| `timeout`          | int     | `5`     | Seconds to wait for probe response                  |
| `failureThreshold` | int     | `3`     | Consecutive failures to mark backend unhealthy      |
| `successThreshold` | int     | `2`     | Consecutive successes to mark backend healthy again |

```json
{
  "healthCheck": {
    "enabled": true,
    "interval": 10,
    "timeout": 5,
    "failureThreshold": 3,
    "successThreshold": 2
  }
}
```

Health checks send `HEAD /` requests to each HTTP backend. `file://` backends are skipped.

---

### `cors`

Cross-Origin Resource Sharing policy.

| Field              | Type     | Default | Description                                 |
|--------------------|----------|---------|---------------------------------------------|
| `enabled`          | boolean  | `false` | Enable CORS handling                        |
| `allowedOrigins`   | string[] | `[]`    | Origins allowed to make requests            |
| `allowedMethods`   | string[] | `[]`    | HTTP methods allowed                        |
| `allowedHeaders`   | string[] | `[]`    | Headers allowed in requests                 |
| `allowCredentials` | boolean  | `false` | Allow cookies/auth in cross-origin requests |
| `maxAge`           | int      | `0`     | Seconds to cache preflight response         |

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

Admin API authentication configuration. The admin API is served under `/admin/*`.

| Field                        | Type     | Description                                                                     |
|------------------------------|----------|---------------------------------------------------------------------------------|
| `authentication.apiKey`      | string   | API key for `Authorization: Bearer <key>` auth. Supports `${ENV_VAR}` expansion |
| `authentication.ipWhitelist` | string[] | IP addresses allowed to access admin API                                        |

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

1. The new file is parsed and validated
2. The configuration is atomically swapped (no downtime)
3. New backends are registered with the health checker
4. Active requests continue with the old config; new requests use the updated config

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
