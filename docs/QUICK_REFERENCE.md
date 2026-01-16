# Quick Reference Guide

## 🚀 Getting Started

### Prerequisites

- Java 25 with preview features enabled
- GraalVM (optional, for native compilation)

### Quick Start

```bash
# Clone and build
git clone <repository>
cd jnignx
./gradlew build

# Run with default config
./gradlew run

# Run with custom config
./gradlew run --args="8080 production-routes.json"
```

---

## 📝 Configuration Examples

### Basic Reverse Proxy

```json
{
  "routes": {
    "/api": [
      "http://backend-1:3000",
      "http://backend-2:3000"
    ],
    "/web": [
      "http://frontend:8080"
    ]
  }
}
```

### With All Production Features

```json
{
  "routes": {
    "/api": [
      "http://backend-1:3000",
      "http://backend-2:3000",
      "http://backend-3:3000"
    ]
  },
  "loadBalancer": "least-connections",
  "healthCheck": {
    "enabled": true,
    "interval": 10,
    "timeout": 5
  },
  "rateLimiter": {
    "enabled": true,
    "requestsPerSecond": 1000,
    "burstSize": 2000
  },
  "circuitBreaker": {
    "enabled": true,
    "failureThreshold": 5,
    "timeout": 30
  },
  "cors": {
    "enabled": true,
    "allowedOrigins": [
      "https://app.example.com"
    ],
    "allowedMethods": [
      "GET",
      "POST",
      "PUT",
      "DELETE"
    ]
  },
  "admin": {
    "authentication": {
      "apiKey": "${ADMIN_API_KEY}",
      "ipWhitelist": [
        "127.0.0.1",
        "10.0.0.0/8"
      ]
    }
  }
}
```

---

## 🔧 Common Use Cases

### 1. API Gateway

```json
{
  "routes": {
    "/api/v1/users": [
      "http://user-service:8080"
    ],
    "/api/v1/orders": [
      "http://order-service:8080"
    ],
    "/api/v1/products": [
      "http://product-service:8080"
    ]
  },
  "rateLimiter": {
    "enabled": true,
    "requestsPerSecond": 100
  }
}
```

### 2. Static Content Delivery

```json
{
  "routes": {
    "/": [
      "file:///var/www/html"
    ],
    "/assets": [
      "file:///var/www/assets"
    ],
    "/api": [
      "http://backend:3000"
    ]
  }
}
```

### 3. WebSocket Proxy

```json
{
  "routes": {
    "/ws": [
      "http://websocket-backend:8080"
    ],
    "/": [
      "http://web-frontend:3000"
    ]
  }
}
```

### 4. Load Balanced with Failover

```json
{
  "routes": {
    "/": [
      "http://backend-1:8080",
      "http://backend-2:8080",
      "http://backend-3:8080"
    ]
  },
  "loadBalancer": "round-robin",
  "healthCheck": {
    "enabled": true,
    "interval": 5,
    "timeout": 3,
    "failureThreshold": 3
  },
  "circuitBreaker": {
    "enabled": true,
    "failureThreshold": 5
  }
}
```

---

## 📊 Admin API Endpoints

### Health & Status

```bash
# Server health
curl http://localhost:8080/admin/health

# Server statistics
curl http://localhost:8080/admin/stats

# Current configuration
curl http://localhost:8080/admin/config
```

### Metrics

```bash
# Prometheus metrics
curl http://localhost:8080/admin/metrics
```

### Circuit Breakers

```bash
# Circuit breaker status
curl http://localhost:8080/admin/circuits

# Reset specific circuit
curl -X POST "http://localhost:8080/admin/circuits/reset?backend=http://backend:8080"
```

### Rate Limiter

```bash
# Rate limiter status
curl http://localhost:8080/admin/ratelimit

# Reset rate limiter
curl -X POST http://localhost:8080/admin/ratelimit/reset
```

### Configuration

```bash
# Reload routes (hot reload)
curl -X POST http://localhost:8080/admin/routes/reload

# View current routes
curl http://localhost:8080/admin/routes
```

### With Authentication

```bash
# Using API key
curl -H "Authorization: Bearer YOUR_API_KEY" \
  http://localhost:8080/admin/health

# Using Basic auth
curl -u admin:password \
  http://localhost:8080/admin/health
```

---

## 🔒 Security Configuration

### Enable Admin Authentication

```json
{
  "admin": {
    "authentication": {
      "apiKey": "your-secure-32-character-api-key-here",
      "users": [
        {"username": "admin", "password": "secure-password"}
      ],
      "ipWhitelist": ["127.0.0.1", "10.0.0.0/8"]
    }
  }
}
```

### Generate API Key

```bash
# In Java
String apiKey = AdminAuth.generateApiKey();

# Using OpenSSL
openssl rand -base64 48
```

### Enable CORS

```json
{
  "cors": {
    "enabled": true,
    "allowedOrigins": [
      "https://app.example.com",
      "https://www.example.com"
    ],
    "allowedMethods": ["GET", "POST", "PUT", "DELETE"],
    "allowedHeaders": ["Content-Type", "Authorization"],
    "allowCredentials": true,
    "maxAge": 3600
  }
}
```

### Enable HTTPS/TLS

```json
{
  "tls": {
    "enabled": true,
    "keystorePath": "/etc/jnignx/keystore.jks",
    "keystorePassword": "${TLS_PASSWORD}",
    "protocols": ["TLSv1.3", "TLSv1.2"]
  }
}
```

---

## 🎛️ JVM Tuning

### Production Settings

```bash
java --enable-preview \
  -XX:+UseZGC \
  -XX:+UseStringDeduplication \
  -Xms4g -Xmx4g \
  -XX:MaxDirectMemorySize=2g \
  -XX:+AlwaysPreTouch \
  -jar jnignx.jar 8080 routes.json
```

### Low Latency

```bash
java --enable-preview \
  -XX:+UseZGC \
  -XX:ZCollectionInterval=5 \
  -Xms8g -Xmx8g \
  -jar jnignx.jar 8080 routes.json
```

### High Throughput

```bash
java --enable-preview \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -Xms8g -Xmx8g \
  -jar jnignx.jar 8080 routes.json
```

---

## 🐳 Docker Quick Start

### Dockerfile

```dockerfile
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY jnignx.jar .
COPY routes.json .
EXPOSE 8080
CMD ["java", "--enable-preview", "-jar", "jnignx.jar", "8080", "routes.json"]
```

### Docker Compose

```yaml
version: '3.8'
services:
  jnignx:
    build: .
    ports:
      - "8080:8080"
    volumes:
      - ./routes.json:/app/routes.json:ro
    environment:
      - ADMIN_API_KEY=your-api-key-here
      - JAVA_OPTS=-Xms2g -Xmx4g
```

---

## 📈 Monitoring Setup

### Prometheus Scrape Config

```yaml
scrape_configs:
  - job_name: 'jnignx'
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/admin/metrics'
```

### Grafana Dashboard Queries

#### Request Rate

```promql
rate(http_requests_total[5m])
```

#### Error Rate

```promql
rate(http_requests_total{status=~"5.."}[5m])
```

#### Latency (p95)

```promql
histogram_quantile(0.95, rate(http_request_duration_seconds_bucket[5m]))
```

#### Backend Health

```promql
backend_health_status
```

---

## 🔍 Troubleshooting

### Check Server Status

```bash
# Health endpoint
curl http://localhost:8080/admin/health

# Statistics
curl http://localhost:8080/admin/stats | jq
```

### View Logs

```bash
# If using systemd
journalctl -u jnignx -f

# If using Docker
docker logs -f jnignx
```

### Test Backend Connectivity

```bash
# From server
curl -v http://backend:8080/

# Check DNS
nslookup backend

# Check port
nc -zv backend 8080
```

### Reload Configuration

```bash
# Graceful reload
curl -X POST http://localhost:8080/admin/routes/reload

# Or send HUP signal (if implemented)
kill -HUP $(pgrep -f jnignx)
```

---

## 🎓 Best Practices

### 1. Configuration

- ✅ Use environment variables for secrets
- ✅ Validate config before deployment
- ✅ Keep backups of working configurations
- ✅ Document all custom settings

### 2. Security

- ✅ Always enable HTTPS in production
- ✅ Use strong API keys (32+ characters)
- ✅ Enable rate limiting
- ✅ Restrict admin API by IP

### 3. Monitoring

- ✅ Set up Prometheus scraping
- ✅ Create alerting rules
- ✅ Monitor error rates
- ✅ Track latency percentiles

### 4. Operations

- ✅ Test failover scenarios
- ✅ Document deployment process
- ✅ Have rollback procedures ready
- ✅ Monitor resource usage

---

## 📚 Additional Resources

- [Architecture Documentation](ARCHITECTURE.md)
- [Production Deployment Guide](PRODUCTION.md)
- [Production Readiness Summary](PRODUCTION_READINESS.md)
- [Feature Documentation](FEATURES.md)
- [API Reference](API.md)

---

## 💡 Tips & Tricks

### Hot Reload Without Downtime

```bash
# Edit routes.json
vim routes.json

# Trigger reload
curl -X POST http://localhost:8080/admin/routes/reload
```

### Debug High Latency

```bash
# Check circuit breakers
curl http://localhost:8080/admin/circuits

# Check backend health
curl http://localhost:8080/admin/stats | jq '.backends'

# Check rate limiter
curl http://localhost:8080/admin/ratelimit
```

### Scale Horizontally

```bash
# Run multiple instances behind a load balancer
# Instance 1
java -jar jnignx.jar 8080 routes.json

# Instance 2
java -jar jnignx.jar 8081 routes.json

# Instance 3
java -jar jnignx.jar 8082 routes.json
```

---

**Version**: 1.0-SNAPSHOT  
**Last Updated**: January 16, 2026  
**Status**: Production Ready ✅
