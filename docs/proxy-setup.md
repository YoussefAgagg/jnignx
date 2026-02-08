# Proxy Setup Guide

This guide explains how to set up JNignx as a reverse proxy on a server, configure domain-based routing, and route
incoming requests to the correct applications running on the machine.

---

## Overview

JNignx acts as a **front-facing reverse proxy** that sits between the internet and your backend applications. All
incoming HTTP/HTTPS traffic hits JNignx on ports 80/443, and JNignx routes each request to the correct backend
application based on the **domain name** (Host header) and/or **URL path**.

```
                        ┌─────────────────────────────────────┐
                        │           JNignx (port 80/443)      │
   Internet Traffic ──► │                                     │
                        │  app.example.com  ──► App A :3000   │
                        │  api.example.com  ──► App B :8081   │
                        │  /static/*        ──► files on disk │
                        │  /*               ──► App C :8080   │
                        └─────────────────────────────────────┘
```

---

## Step 1: Install and Build JNignx

```bash
# Prerequisites: Java 25 with preview features
java --version  # Should show 25+

# Clone and build
git clone https://github.com/youssefagagg/jnignx.git
cd jnignx
./gradlew build
```

---

## Step 2: Start Your Backend Applications

Start each application on its own port. For example:

```bash
# App A — API service on port 3000
cd /opt/apps/api-service
java -jar api-service.jar --server.port=3000 &

# App B — Admin dashboard on port 4000
cd /opt/apps/admin-dashboard
java -jar admin-dashboard.jar --server.port=4000 &

# App C — Main website on port 8080
cd /opt/apps/website
java -jar website.jar --server.port=8080 &
```

Verify each app is running:

```bash
curl http://localhost:3000/health
curl http://localhost:4000/health
curl http://localhost:8080/health
```

---

## Step 3: Configure JNignx Routes

Create a `routes.json` file that maps URL paths to your backend applications:

```json
{
  "routes": {
    "/api": [
      "http://localhost:3000"
    ],
    "/dashboard": [
      "http://localhost:4000"
    ],
    "/static": [
      "file:///var/www/static"
    ],
    "/": [
      "http://localhost:8080"
    ]
  }
}
```

**How routing works:**

- JNignx uses **longest prefix match** — a request to `/api/users/123` matches `/api` (not `/`)
- The **catch-all** route `/` handles any request that doesn't match a more specific prefix
- `file://` routes serve static files directly from disk without a backend application
- Routes are evaluated by prefix length, not by order in the file

---

## Step 4: Start JNignx on Port 80

Run JNignx on port 80 so it receives all incoming HTTP traffic:

```bash
# Run on port 80 (requires root or CAP_NET_BIND_SERVICE on Linux)
sudo java --enable-preview -jar build/libs/jnignx.jar 80 routes.json
```

Or use the Gradle wrapper:

```bash
sudo ./gradlew run --args="80 routes.json"
```

> **Note:** Binding to ports below 1024 requires elevated privileges on most systems. See
> [Running on Privileged Ports](#running-on-privileged-ports) below for alternatives.

---

## Step 5: Configure DNS

Point your domain(s) to your server's IP address. In your DNS provider, create A/AAAA records:

```
example.com       A     203.0.113.10
www.example.com   A     203.0.113.10
api.example.com   A     203.0.113.10
```

Once DNS propagates, all traffic for these domains reaches your server on port 80, where JNignx handles it.

---

## Domain-Based Routing

JNignx supports routing requests to different backends based on the **domain name** (Host header). This is the
recommended approach for serving multiple applications from a single JNignx instance.

### Basic Domain Routing

Use the `domainRoutes` configuration to map domain names to backends:

```json
{
  "routes": {
    "/": ["http://localhost:8080"]
  },
  "domainRoutes": {
    "app.example.com": ["http://localhost:3000"],
    "api.example.com": ["http://localhost:8081"],
    "blog.example.com": ["http://localhost:4000"],
    "static.example.com": ["file:///var/www/static"]
  }
}
```

**How domain routing works:**

- When a request arrives, JNignx checks the `Host` header against `domainRoutes` first
- If the domain matches, the request is forwarded to the configured backend(s)
- If no domain matches, JNignx falls back to **path-based routing** using `routes`
- Domain matching is **case-insensitive** and automatically strips the port from the Host header
- Each domain can have multiple backends for load balancing

### Example: Multi-App Server

Suppose you have three applications running on your server:

| Application     | Port | Domain            |
|-----------------|------|-------------------|
| Frontend App    | 3000 | app.example.com   |
| REST API        | 8081 | api.example.com   |
| Admin Dashboard | 4000 | admin.example.com |

Configure JNignx:

```json
{
  "routes": {
    "/": ["http://localhost:3000"]
  },
  "domainRoutes": {
    "app.example.com": ["http://localhost:3000"],
    "api.example.com": ["http://localhost:8081", "http://localhost:8082"],
    "admin.example.com": ["http://localhost:4000"]
  },
  "loadBalancer": "round-robin",
  "healthCheck": {
    "enabled": true,
    "intervalSeconds": 10,
    "path": "/health"
  }
}
```

Then set up DNS records pointing all subdomains to your server IP:

```
app.example.com     A     203.0.113.10
api.example.com     A     203.0.113.10
admin.example.com   A     203.0.113.10
```

### Domain + Path Routing Combined

You can use both `domainRoutes` and `routes` together. Domain routing takes priority:

```json
{
  "routes": {
    "/api": ["http://localhost:8081"],
    "/assets": ["file:///var/www/assets"],
    "/": ["http://localhost:8080"]
  },
  "domainRoutes": {
    "app.example.com": ["http://localhost:3000"],
    "api.example.com": ["http://localhost:8081"]
  }
}
```

- `app.example.com/anything` → `http://localhost:3000` (domain match)
- `api.example.com/anything` → `http://localhost:8081` (domain match)
- `unknown.example.com/api/users` → `http://localhost:8081` (path fallback)
- `unknown.example.com/` → `http://localhost:8080` (path fallback)

### WebSocket Support with Domain Routing

Domain routing works with WebSocket connections too. If a client sends a WebSocket upgrade
request to `ws.example.com`, JNignx detects the upgrade headers and proxies the full
bidirectional WebSocket connection to the backend:

```json
{
  "domainRoutes": {
    "ws.example.com": ["http://localhost:9000"],
    "app.example.com": ["http://localhost:3000"]
  }
}
```

---

## Path-Based Routing

For simpler setups or when you don't need domain-based routing, use path-based routing only:

```json
{
  "routes": {
    "/api/v1": [
      "http://localhost:3000",
      "http://localhost:3001"
    ],
    "/api/v2": [
      "http://localhost:3002"
    ],
    "/blog": [
      "http://localhost:4000"
    ],
    "/assets": [
      "file:///var/www/assets"
    ],
    "/": [
      "http://localhost:8080"
    ]
  }
}
```

JNignx uses **longest prefix match** — a request to `/api/v1/users` matches `/api/v1` (not `/api` or `/`).

---

## Load Balancing Multiple Backends

For high availability, run multiple instances of each application and list them as backends:

```json
{
  "routes": {
    "/api": [
      "http://localhost:3000",
      "http://localhost:3001",
      "http://localhost:3002"
    ],
    "/": [
      "http://localhost:8080",
      "http://localhost:8081"
    ]
  },
  "loadBalancer": "round-robin",
  "healthCheck": {
    "enabled": true,
    "intervalSeconds": 10,
    "path": "/health",
    "expectedStatusMin": 200,
    "expectedStatusMax": 299
  }
}
```

JNignx distributes requests across healthy backends. If a backend goes down, it is automatically removed from the
rotation and re-added when it recovers.

**Load balancing strategies:**

| Strategy                 | Best For                                       |
|--------------------------|------------------------------------------------|
| `"round-robin"`          | Equal-capacity backends                        |
| `"weighted-round-robin"` | Backends with different capacity (see below)   |
| `"least-connections"`    | Backends handling requests of varying duration |
| `"ip-hash"`              | Sticky sessions (same client → same backend)   |

**Weighted round-robin example** — give more traffic to a more powerful backend:

```json
{
  "routes": {
    "/api": [
      "http://localhost:3000",
      "http://localhost:3001"
    ]
  },
  "loadBalancer": "weighted-round-robin",
  "backendWeights": {
    "http://localhost:3000": 3,
    "http://localhost:3001": 1
  }
}
```

---

## Adding HTTPS/TLS

### Automatic HTTPS with Let's Encrypt (Recommended)

JNignx includes a built-in ACME v2 client that automatically obtains and renews TLS certificates
from Let's Encrypt. Add the `autoHttps` section to your `routes.json`:

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
    "domains": ["app.example.com", "api.example.com"],
    "staging": false,
    "httpsPort": 443,
    "httpToHttpsRedirect": true,
    "allowedDomains": ["*.example.com"]
  }
}
```

Start the server (requires ports 80 and 443):

```bash
sudo java --enable-preview -jar jnignx.jar 80 routes.json
```

JNignx will:

1. Listen on port 80 for ACME HTTP-01 challenges and HTTP→HTTPS redirects
2. Listen on port 443 for HTTPS connections
3. Automatically obtain certificates from Let's Encrypt when a new domain connects
4. Cache certificates to disk and renew them 30 days before expiration

> **Tip:** Use `"staging": true` during development to avoid Let's Encrypt rate limits.

### Manual TLS with Existing Certificates

If you already have certificates (e.g., from a corporate CA), convert them to PKCS12 format:

```bash
# Convert PEM certificate + key to PKCS12
openssl pkcs12 -export \
  -in fullchain.pem \
  -inkey privkey.pem \
  -out keystore.p12 \
  -name jnignx \
  -password pass:changeit
```

Then use the programmatic API to start with manual TLS:

```java
SslWrapper ssl = new SslWrapper("keystore.p12", "changeit");
ServerLoop server = new ServerLoop(443, router, ssl);
```

---

## Production Configuration Example

A complete production setup with domain routing, load balancing, and all features enabled:

```json
{
  "routes": {
    "/static": [
      "file:///var/www/static"
    ],
    "/": [
      "http://localhost:8080",
      "http://localhost:8081"
    ]
  },
   "domainRoutes": {
      "app.example.com": [
         "http://localhost:3000",
         "http://localhost:3001"
      ],
      "api.example.com": [
         "http://localhost:8081",
         "http://localhost:8082"
      ],
      "admin.example.com": [
         "http://localhost:4000"
      ]
   },
  "loadBalancer": "least-connections",
  "healthCheck": {
    "enabled": true,
    "intervalSeconds": 10,
    "timeoutSeconds": 5,
    "failureThreshold": 3,
    "successThreshold": 2,
    "path": "/health",
    "expectedStatusMin": 200,
    "expectedStatusMax": 299
  },
  "rateLimiter": {
    "enabled": true,
    "requestsPerSecond": 500,
    "burstSize": 1000,
    "strategy": "token-bucket"
  },
  "circuitBreaker": {
    "enabled": true,
    "failureThreshold": 5,
    "timeout": 30
  },
  "cors": {
    "enabled": true,
    "allowedOrigins": [
      "https://example.com",
      "https://www.example.com"
    ],
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

Start with:

```bash
export ADMIN_API_KEY="your-secret-key-here"
sudo java --enable-preview -jar jnignx.jar 80 routes.json
```

---

## Running on Privileged Ports

Binding to ports 80/443 requires root. Here are safer alternatives:

### Option 1: Port Redirect with iptables (Linux)

Run JNignx on an unprivileged port and redirect:

```bash
# Run JNignx on port 8080
java --enable-preview -jar jnignx.jar 8080 routes.json &

# Redirect port 80 → 8080
sudo iptables -t nat -A PREROUTING -p tcp --dport 80 -j REDIRECT --to-port 8080
sudo iptables -t nat -A PREROUTING -p tcp --dport 443 -j REDIRECT --to-port 8443
```

### Option 2: CAP_NET_BIND_SERVICE (Linux)

Grant the Java binary the capability to bind to low ports:

```bash
sudo setcap 'cap_net_bind_service=+ep' $(readlink -f $(which java))
java --enable-preview -jar jnignx.jar 80 routes.json
```

### Option 3: macOS Port Forwarding

On macOS, use `pfctl`:

```bash
# Run JNignx on port 8080
java --enable-preview -jar jnignx.jar 8080 routes.json &

# Forward port 80 → 8080
echo "rdr pass on lo0 inet proto tcp from any to any port 80 -> 127.0.0.1 port 8080" | sudo pfctl -ef -
```

---

## Running as a System Service

### systemd (Linux)

Create `/etc/systemd/system/jnignx.service`:

```ini
[Unit]
Description=JNignx Reverse Proxy
After=network.target

[Service]
Type=simple
User=jnignx
Group=jnignx
WorkingDirectory=/opt/jnignx
ExecStart=/usr/bin/java --enable-preview -jar /opt/jnignx/jnignx.jar 80 /opt/jnignx/routes.json
Restart=always
RestartSec=5
Environment=ADMIN_API_KEY=your-secret-key

# Security
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/opt/jnignx
AmbientCapabilities=CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable jnignx
sudo systemctl start jnignx
sudo systemctl status jnignx
```

### GraalVM Native Image

For faster startup and lower memory usage in production:

```bash
./gradlew nativeCompile
sudo cp build/native/nativeCompile/jnignx /opt/jnignx/jnignx
# Update ExecStart in the service file to:
# ExecStart=/opt/jnignx/jnignx 80 /opt/jnignx/routes.json
```

---

## Hot-Reload: Updating Routes Without Downtime

JNignx watches the config file for changes. To add a new application or change routing:

```bash
# Edit the configuration
vim /opt/jnignx/routes.json

# JNignx detects the change within 1 second and applies it
# Check logs for confirmation:
journalctl -u jnignx -f
# [Router] Configuration reloaded!
```

Active connections continue with the old config. New connections use the updated config. No restart needed.

---

## Monitoring

### Health Check

```bash
curl http://localhost/health
# {"status":"healthy"}
```

### Prometheus Metrics

```bash
curl http://localhost/metrics
```

Scrape this endpoint with Prometheus for dashboarding in Grafana.

### Admin API (when enabled)

```bash
# Check backend health
curl -H "Authorization: Bearer $ADMIN_API_KEY" http://localhost/admin/backends

# View circuit breaker states
curl -H "Authorization: Bearer $ADMIN_API_KEY" http://localhost/admin/circuits

# Reload config manually
curl -X POST -H "Authorization: Bearer $ADMIN_API_KEY" http://localhost/admin/routes/reload
```

See the [Admin API Reference](api.md) for all available endpoints.

---

## Troubleshooting

### Backend not receiving requests

1. Check that the backend is running: `curl http://localhost:3000/`
2. Check JNignx health checks: look for `[HealthChecker]` log entries
3. Verify the route prefix matches: `/api` matches `/api/anything` but not `/apiv2`

### 502 Bad Gateway errors

1. The backend is down or not responding
2. Check backend logs for errors
3. Check if the circuit breaker is open: `curl http://localhost/admin/circuits`

### 503 Service Unavailable

1. The circuit breaker has tripped for the backend
2. Wait for the timeout (default: 30s) or reset manually:
   `curl -X POST http://localhost/admin/circuits/reset`

### High latency

1. Check if load balancing is spreading traffic evenly
2. Consider switching to `"least-connections"` strategy
3. Review metrics: `curl http://localhost/metrics | grep latency`
