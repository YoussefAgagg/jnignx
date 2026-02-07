# Building and Running JNignx as a GraalVM Native Image

This guide explains how to compile JNignx into a native binary using GraalVM Native Image and deploy it
as a production reverse proxy that handles HTTP, HTTPS, WebSocket, and domain-based routing.

---

## Why Native Image?

| Feature          | JVM Mode               | Native Image         |
|------------------|------------------------|----------------------|
| Startup time     | ~1–2 seconds           | **<100 ms**          |
| Memory footprint | ~100–200 MB            | **~30–50 MB**        |
| Peak throughput  | Higher (JIT optimized) | Slightly lower (AOT) |
| Distribution     | Requires JDK installed | **Single binary**    |

Native Image is ideal for reverse proxies where **instant startup** and **low memory** matter more than
peak per-request throughput.

---

## Prerequisites

### Install GraalVM

```bash
# Option 1: SDKMAN (recommended)
sdk install java 25.ea-graal
sdk use java 25.ea-graal

# Option 2: Manual download
# Download from https://www.graalvm.org/downloads/
# Set JAVA_HOME and PATH
export JAVA_HOME=/path/to/graalvm
export PATH=$JAVA_HOME/bin:$PATH

# Verify
java --version
# Should show GraalVM
native-image --version
```

### Install Native Image Component

```bash
# GraalVM CE 22+ includes native-image by default
# For older versions:
gu install native-image

# On Linux, install build dependencies
sudo apt-get install -y build-essential libz-dev zlib1g-dev
# On macOS, Xcode command line tools are required
xcode-select --install
```

---

## Building the Native Binary

### Standard Build

```bash
cd jnignx
./gradlew nativeCompile
```

This produces a binary at `build/native/nativeCompile/jnignx`.

The `build.gradle.kts` is already configured for native image:

```kotlin
graalvmNative {
    binaries {
        named("main") {
            imageName.set("jnignx")
            mainClass.set("com.github.youssefagagg.jnignx.NanoServer")
            buildArgs.add("--enable-preview")
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }
}
```

### Optimized Production Build

For maximum performance, add these flags:

```bash
./gradlew nativeCompile \
  -Pnative.buildArgs="--enable-preview,--no-fallback,-O2,-march=native"
```

Or update `build.gradle.kts`:

```kotlin
graalvmNative {
    binaries {
        named("main") {
            imageName.set("jnignx")
            mainClass.set("com.github.youssefagagg.jnignx.NanoServer")
            buildArgs.add("--enable-preview")
            buildArgs.add("--no-fallback")
            buildArgs.add("-O2")
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }
}
```

### Build Output

```
========================================================
GraalVM Native Image: Generating 'jnignx' (executable)
========================================================
...
Finished generating 'jnignx' in Xm Xs.
```

The binary is at: `build/native/nativeCompile/jnignx`

```bash
# Check size (~30-50 MB)
ls -lh build/native/nativeCompile/jnignx

# Quick test
./build/native/nativeCompile/jnignx 8080 routes.json
```

---

## Running in Production

### Basic Startup

```bash
# Copy binary to server
scp build/native/nativeCompile/jnignx user@server:/opt/jnignx/

# Copy configuration
scp routes.json user@server:/opt/jnignx/

# Run
ssh user@server
cd /opt/jnignx
./jnignx 80 routes.json
```

### Domain Routing Configuration

Create a `routes.json` that routes traffic based on domain names to your running applications:

```json
{
  "routes": {
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
    ],
    "admin.example.com": [
      "http://localhost:4000"
    ],
    "static.example.com": [
      "file:///var/www/static"
    ]
  },
  "loadBalancer": "round-robin",
  "healthCheck": {
    "enabled": true,
    "intervalSeconds": 10,
    "path": "/health"
  },
  "rateLimiter": {
    "enabled": true,
    "requestsPerSecond": 1000,
    "strategy": "token-bucket"
  },
  "circuitBreaker": {
    "enabled": true,
    "failureThreshold": 5,
    "timeout": 30
  }
}
```

This configuration:

- Routes `app.example.com` → your frontend app on port 3000
- Routes `api.example.com` → your API servers on ports 8081/8082 (load balanced)
- Routes `admin.example.com` → your admin dashboard on port 4000
- Routes `static.example.com` → static files on disk
- Falls back to path-based routing for unmatched domains

### Running with HTTPS/TLS

```bash
# Step 1: Obtain TLS certificate (e.g., with certbot)
sudo certbot certonly --standalone -d example.com -d app.example.com -d api.example.com

# Step 2: Convert PEM to PKCS12 keystore
openssl pkcs12 -export \
  -in /etc/letsencrypt/live/example.com/fullchain.pem \
  -inkey /etc/letsencrypt/live/example.com/privkey.pem \
  -out /opt/jnignx/keystore.p12 \
  -name jnignx \
  -password pass:changeit

# Step 3: Run JNignx with TLS on port 443
sudo ./jnignx 443 routes.json \
  -Djavax.net.ssl.keyStore=/opt/jnignx/keystore.p12 \
  -Djavax.net.ssl.keyStorePassword=changeit \
  -Djavax.net.ssl.keyStoreType=PKCS12
```

### Running Both HTTP and HTTPS

Run two instances — one for HTTP (redirect to HTTPS) and one for HTTPS:

```bash
# HTTPS on port 443
sudo ./jnignx 443 routes.json \
  -Djavax.net.ssl.keyStore=keystore.p12 \
  -Djavax.net.ssl.keyStorePassword=changeit &

# HTTP on port 80 (can be used for redirect or direct traffic)
sudo ./jnignx 80 routes.json &
```

Or use iptables to redirect HTTP to HTTPS:

```bash
sudo iptables -t nat -A PREROUTING -p tcp --dport 80 -j REDIRECT --to-port 443
```

### WebSocket Proxying

JNignx automatically detects WebSocket upgrade requests and proxies them bidirectionally.
No special configuration is needed — just point the domain to a WebSocket-capable backend:

```json
{
  "domainRoutes": {
    "ws.example.com": [
      "http://localhost:9000"
    ],
    "app.example.com": [
      "http://localhost:3000"
    ]
  }
}
```

Clients connect via `ws://ws.example.com/` or `wss://ws.example.com/` (with TLS), and JNignx
handles the upgrade and full-duplex proxying.

---

## systemd Service

Create `/etc/systemd/system/jnignx.service`:

```ini
[Unit]
Description=JNignx Reverse Proxy (Native)
After=network.target

[Service]
Type=simple
User=jnignx
Group=jnignx
WorkingDirectory=/opt/jnignx
ExecStart=/opt/jnignx/jnignx 80 /opt/jnignx/routes.json
Restart=always
RestartSec=5
Environment=ADMIN_API_KEY=your-secret-key

# Security hardening
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/opt/jnignx
AmbientCapabilities=CAP_NET_BIND_SERVICE

# Performance
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
# Create service user
sudo useradd -r -s /bin/false jnignx
sudo chown -R jnignx:jnignx /opt/jnignx

# Enable and start
sudo systemctl daemon-reload
sudo systemctl enable jnignx
sudo systemctl start jnignx

# Check status
sudo systemctl status jnignx

# View logs
sudo journalctl -u jnignx -f
```

---

## Listening on Privileged Ports (80/443)

### Option 1: AmbientCapabilities (recommended for systemd)

The systemd service file above uses `AmbientCapabilities=CAP_NET_BIND_SERVICE`, which lets
the non-root `jnignx` user bind to ports below 1024.

### Option 2: setcap on the binary

```bash
sudo setcap 'cap_net_bind_service=+ep' /opt/jnignx/jnignx
# Now run as non-root
./jnignx 80 routes.json
```

### Option 3: iptables port redirect

```bash
# Run on unprivileged port
./jnignx 8080 routes.json &

# Redirect 80 → 8080 and 443 → 8443
sudo iptables -t nat -A PREROUTING -p tcp --dport 80 -j REDIRECT --to-port 8080
sudo iptables -t nat -A PREROUTING -p tcp --dport 443 -j REDIRECT --to-port 8443
```

### Option 4: macOS port forwarding

```bash
./jnignx 8080 routes.json &
echo "rdr pass on lo0 inet proto tcp from any to any port 80 -> 127.0.0.1 port 8080" | sudo pfctl -ef -
```

---

## Complete Production Example

Here is a full walkthrough for deploying JNignx on a Linux server to route traffic
for multiple domains:

### 1. Build the native binary

```bash
# On your development machine
cd jnignx
./gradlew nativeCompile

# Transfer to server
scp build/native/nativeCompile/jnignx user@server:/opt/jnignx/
```

### 2. Configure domain routing

Create `/opt/jnignx/routes.json`:

```json
{
  "routes": {
    "/": [
      "http://localhost:8080"
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
  "loadBalancer": "round-robin",
  "backendWeights": {
    "http://localhost:3000": 3,
    "http://localhost:3001": 1
  },
  "healthCheck": {
    "enabled": true,
    "intervalSeconds": 10,
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
      "https://app.example.com",
      "https://admin.example.com"
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
        "127.0.0.1"
      ]
    }
  }
}
```

### 3. Set up DNS

Point all domains to your server's public IP:

```
app.example.com     A     203.0.113.10
api.example.com     A     203.0.113.10
admin.example.com   A     203.0.113.10
```

### 4. Start your backend apps

```bash
# Frontend app (two instances for load balancing)
cd /opt/apps/frontend && java -jar app.jar --server.port=3000 &
cd /opt/apps/frontend && java -jar app.jar --server.port=3001 &

# API service (two instances)
cd /opt/apps/api && java -jar api.jar --server.port=8081 &
cd /opt/apps/api && java -jar api.jar --server.port=8082 &

# Admin dashboard
cd /opt/apps/admin && java -jar admin.jar --server.port=4000 &
```

### 5. Install and start JNignx

```bash
sudo cp /opt/jnignx/jnignx /usr/local/bin/
sudo setcap 'cap_net_bind_service=+ep' /usr/local/bin/jnignx

# Install the systemd service (from above)
sudo systemctl enable jnignx
sudo systemctl start jnignx
```

### 6. Verify

```bash
# Test domain routing
curl -H "Host: app.example.com" http://localhost/
curl -H "Host: api.example.com" http://localhost/health
curl -H "Host: admin.example.com" http://localhost/

# Check health
curl http://localhost/health

# Check metrics
curl http://localhost/metrics

# Check admin (if enabled)
curl -H "Authorization: Bearer $ADMIN_API_KEY" http://localhost/admin/backends
```

---

## Hot-Reload in Production

Edit the routes file while JNignx is running. Changes are detected within 1 second:

```bash
vim /opt/jnignx/routes.json

# Add a new domain:
# "newapp.example.com": ["http://localhost:5000"]

# JNignx detects and applies the change automatically
journalctl -u jnignx -f
# [Router] Configuration reloaded!
# [Router] Domain Routes: [app.example.com, api.example.com, admin.example.com, newapp.example.com]
```

No restart needed. Active connections continue with the old config; new connections use the updated config.

---

## Troubleshooting

### Native image build fails

```bash
# Ensure GraalVM is active
java --version  # Must show GraalVM

# Ensure native-image is available
native-image --version

# On Linux, install missing dependencies
sudo apt-get install -y build-essential libz-dev zlib1g-dev
```

### "Address already in use"

```bash
# Find the process using the port
sudo lsof -i :80
sudo kill -9 <PID>
```

### Domain routing not working

1. Verify the `Host` header is being sent: `curl -v -H "Host: app.example.com" http://server-ip/`
2. Check that DNS points to the correct IP: `dig app.example.com`
3. Ensure `domainRoutes` keys match the domain exactly (case-insensitive)
4. Check JNignx logs for routing decisions

### Backend marked unhealthy

```bash
# Test backend directly
curl http://localhost:3000/health

# Check health checker logs
journalctl -u jnignx | grep HealthChecker

# View backend status via admin API
curl -H "Authorization: Bearer $ADMIN_API_KEY" http://localhost/admin/backends
```
