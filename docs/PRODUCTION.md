# Production Deployment Guide

This guide covers everything you need to deploy **NanoServer (jnignx)** to production environments.

## 🎯 Pre-Production Checklist

### Security

- [ ] Enable HTTPS/TLS with valid certificates
- [ ] Configure Admin API authentication
- [ ] Set up rate limiting
- [ ] Enable circuit breakers
- [ ] Configure CORS policies
- [ ] Review and whitelist Admin API access
- [ ] Rotate API keys regularly
- [ ] Use strong TLS cipher suites

### Performance

- [ ] Tune JVM parameters for your workload
- [ ] Configure appropriate timeout values
- [ ] Set request/response size limits
- [ ] Enable compression for text assets
- [ ] Configure connection pools
- [ ] Set up health checks
- [ ] Test under expected load

### Observability

- [ ] Configure structured logging
- [ ] Set up Prometheus metrics scraping
- [ ] Configure alerting rules
- [ ] Set up log aggregation
- [ ] Create dashboards
- [ ] Document baseline metrics

### Reliability

- [ ] Configure multiple backend servers
- [ ] Set up load balancing
- [ ] Test failover scenarios
- [ ] Configure graceful shutdown
- [ ] Set up monitoring and alerts
- [ ] Document recovery procedures

---

## 🚀 Deployment Options

### 1. JVM Deployment (Recommended for Most Use Cases)

#### Build

```bash
./gradlew build
```

#### Run with Production Settings

```bash
java --enable-preview \
  -XX:+UseZGC \
  -XX:+UseStringDeduplication \
  -Xms2g -Xmx4g \
  -XX:MaxDirectMemorySize=1g \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/jnignx/heap-dump.hprof \
  -jar build/libs/jnignx-1.0-SNAPSHOT.jar \
  8080 /etc/jnignx/routes.json
```

#### Systemd Service

Create `/etc/systemd/system/jnignx.service`:

```ini
[Unit]
Description=NanoServer Reverse Proxy
After=network.target

[Service]
Type=simple
User=jnignx
Group=jnignx
WorkingDirectory=/opt/jnignx
ExecStart=/usr/bin/java --enable-preview \
  -XX:+UseZGC \
  -Xms2g -Xmx4g \
  -jar /opt/jnignx/jnignx.jar 8080 /etc/jnignx/routes.json
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

# Security hardening
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/log/jnignx

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

### 2. Native Image Deployment (For Minimal Resource Usage)

#### Build Native Image

```bash
./gradlew nativeCompile
```

#### Run

```bash
./build/native/nativeCompile/jnignx 8080 /etc/jnignx/routes.json
```

#### Systemd Service

Similar to above, but simpler ExecStart:

```ini
ExecStart=/opt/jnignx/jnignx 8080 /etc/jnignx/routes.json
```

### 3. Docker Deployment

#### Dockerfile

```dockerfile
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app
COPY . .
RUN ./gradlew build --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /app/build/libs/jnignx-1.0-SNAPSHOT.jar ./jnignx.jar
COPY routes.json /etc/jnignx/routes.json

EXPOSE 8080
EXPOSE 8443

ENTRYPOINT ["java", "--enable-preview", "-XX:+UseZGC", "-Xms512m", "-Xmx1g", \
            "-jar", "jnignx.jar", "8080", "/etc/jnignx/routes.json"]
```

#### Build and Run

```bash
docker build -t jnignx:latest .
docker run -d \
  --name jnignx \
  -p 8080:8080 \
  -p 8443:8443 \
  -v /etc/jnignx/routes.json:/etc/jnignx/routes.json:ro \
  -v /etc/jnignx/certs:/etc/jnignx/certs:ro \
  --restart unless-stopped \
  jnignx:latest
```

### 4. Kubernetes Deployment

#### Deployment YAML

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jnignx
  labels:
    app: jnignx
spec:
  replicas: 3
  selector:
    matchLabels:
      app: jnignx
  template:
    metadata:
      labels:
        app: jnignx
    spec:
      containers:
        - name: jnignx
          image: jnignx:latest
          ports:
            - containerPort: 8080
              name: http
            - containerPort: 8443
              name: https
          env:
            - name: JAVA_OPTS
              value: "-XX:+UseZGC -Xms512m -Xmx1g"
          resources:
            requests:
              memory: "512Mi"
              cpu: "500m"
            limits:
              memory: "2Gi"
              cpu: "2000m"
          livenessProbe:
            httpGet:
              path: /admin/health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /admin/health
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 5
          volumeMounts:
            - name: config
              mountPath: /etc/jnignx
              readOnly: true
      volumes:
        - name: config
          configMap:
            name: jnignx-config
---
apiVersion: v1
kind: Service
metadata:
  name: jnignx
spec:
  type: LoadBalancer
  selector:
    app: jnignx
  ports:
    - name: http
      port: 80
      targetPort: 8080
    - name: https
      port: 443
      targetPort: 8443
```

---

## ⚙️ Production Configuration

### Sample Production routes.json

```json
{
  "routes": {
    "/api/v1": [
      "http://backend-1.internal:8080",
      "http://backend-2.internal:8080",
      "http://backend-3.internal:8080"
    ],
    "/api/v2": [
      "http://backend-v2.internal:8080"
    ],
    "/static": [
      "file:///var/www/static"
    ]
  },
  "loadBalancer": {
    "algorithm": "least-connections"
  },
  "healthCheck": {
    "enabled": true,
    "interval": 10,
    "timeout": 5,
    "failureThreshold": 3,
    "successThreshold": 2
  },
  "rateLimiter": {
    "enabled": true,
    "requestsPerSecond": 1000,
    "burstSize": 2000,
    "algorithm": "token-bucket"
  },
  "circuitBreaker": {
    "enabled": true,
    "failureThreshold": 5,
    "timeout": 30
  },
  "tls": {
    "enabled": true,
    "keystorePath": "/etc/jnignx/certs/keystore.jks",
    "keystorePassword": "${TLS_KEYSTORE_PASSWORD}",
    "protocols": [
      "TLSv1.3",
      "TLSv1.2"
    ]
  },
  "cors": {
    "enabled": true,
    "allowedOrigins": [
      "https://app.example.com",
      "https://www.example.com"
    ],
    "allowedMethods": [
      "GET",
      "POST",
      "PUT",
      "DELETE"
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
        "10.0.0.0/8",
        "127.0.0.1"
      ]
    }
  },
  "timeouts": {
    "connection": 5,
    "request": 60,
    "idle": 300,
    "keepAlive": 120
  },
  "limits": {
    "maxRequestSize": 10485760,
    "maxResponseSize": 52428800,
    "maxConnections": 10000
  }
}
```

### Environment Variables

Create `/etc/jnignx/environment`:

```bash
# TLS Configuration
TLS_KEYSTORE_PASSWORD=your-secure-password-here

# Admin API
ADMIN_API_KEY=your-secure-api-key-minimum-32-characters

# JVM Options
JAVA_OPTS="-XX:+UseZGC -Xms2g -Xmx4g -XX:MaxDirectMemorySize=1g"
```

---

## 📊 Monitoring

### Prometheus Metrics

Add scrape config to `/etc/prometheus/prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'jnignx'
    static_configs:
      - targets: [ 'localhost:8080' ]
    metrics_path: '/admin/metrics'
    basic_auth:
      username: 'metrics'
      password: 'your-metrics-password'
```

### Key Metrics to Monitor

#### Request Metrics

- `http_requests_total` - Total request count
- `http_request_duration_seconds` - Request latency
- `http_requests_in_flight` - Active requests

#### Backend Metrics

- `backend_health_status` - Backend health (0=unhealthy, 1=healthy)
- `backend_connections_active` - Active backend connections
- `backend_response_time_seconds` - Backend response time

#### Circuit Breaker Metrics

- `circuit_breaker_state` - Circuit state (0=closed, 1=open, 2=half-open)
- `circuit_breaker_failures_total` - Total failures

#### Rate Limiter Metrics

- `rate_limiter_requests_rejected_total` - Rejected requests
- `rate_limiter_tokens_available` - Available tokens

### Alerting Rules

Create `alerts.yml`:

```yaml
groups:
  - name: jnignx
    interval: 30s
    rules:
      - alert: HighErrorRate
        expr: rate(http_requests_total{status=~"5.."}[5m]) > 0.05
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "High error rate detected"

      - alert: BackendDown
        expr: backend_health_status == 0
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Backend {{ $labels.backend }} is down"

      - alert: CircuitBreakerOpen
        expr: circuit_breaker_state == 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Circuit breaker open for {{ $labels.backend }}"

      - alert: HighLatency
        expr: histogram_quantile(0.95, rate(http_request_duration_seconds_bucket[5m])) > 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High latency detected (p95 > 1s)"
```

---

## 🔒 Security Hardening

### 1. TLS/HTTPS Configuration

Generate self-signed certificate (testing only):

```bash
keytool -genkeypair -keyalg RSA -keysize 4096 \
  -validity 365 -alias jnignx \
  -keystore keystore.jks \
  -storepass changeit \
  -dname "CN=localhost, OU=IT, O=MyCompany, L=City, ST=State, C=US"
```

Production: Use Let's Encrypt or your organization's CA.

### 2. Admin API Security

Generate secure API key:

```bash
openssl rand -base64 48
```

Configure authentication in routes.json and restrict by IP:

```json
{
  "admin": {
    "authentication": {
      "apiKey": "${ADMIN_API_KEY}",
      "ipWhitelist": [
        "10.0.0.0/8"
      ],
      "users": [
        {
          "username": "admin",
          "passwordHash": "..."
        }
      ]
    }
  }
}
```

### 3. Firewall Rules

```bash
# Allow HTTP/HTTPS
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp

# Restrict admin port to internal network
sudo ufw allow from 10.0.0.0/8 to any port 8080

# Enable firewall
sudo ufw enable
```

### 4. SELinux/AppArmor

For SELinux, create policy:

```bash
semanage fcontext -a -t bin_t "/opt/jnignx/jnignx"
restorecon -v /opt/jnignx/jnignx
```

---

## 🔧 Performance Tuning

### JVM Options

For high-throughput workloads:

```bash
-XX:+UseZGC \
-XX:+UseStringDeduplication \
-XX:+OptimizeStringConcat \
-Xms4g -Xmx4g \
-XX:MaxDirectMemorySize=2g \
-XX:+AlwaysPreTouch
```

For low-latency workloads:

```bash
-XX:+UseZGC \
-XX:ZCollectionInterval=5 \
-Xms8g -Xmx8g \
-XX:MaxDirectMemorySize=4g
```

### OS Tuning

Edit `/etc/sysctl.conf`:

```bash
# Increase file descriptor limits
fs.file-max = 65536

# TCP tuning
net.core.somaxconn = 4096
net.ipv4.tcp_max_syn_backlog = 8192
net.ipv4.tcp_tw_reuse = 1
net.ipv4.tcp_fin_timeout = 30

# Increase ephemeral port range
net.ipv4.ip_local_port_range = 10000 65535
```

Apply:

```bash
sudo sysctl -p
```

Edit `/etc/security/limits.conf`:

```
jnignx soft nofile 65536
jnignx hard nofile 65536
```

---

## 📝 Logging

### Structured JSON Logs

Logs are written to stdout in JSON format. Use log aggregation:

#### With Filebeat (ELK Stack)

```yaml
filebeat.inputs:
  - type: journald
    id: jnignx
    include_matches:
      - "_SYSTEMD_UNIT=jnignx.service"
    processors:
      - decode_json_fields:
          fields: [ "message" ]
          target: ""
```

#### With Promtail (Loki)

```yaml
scrape_configs:
  - job_name: jnignx
    journal:
      matches: _SYSTEMD_UNIT=jnignx.service
      labels:
        job: jnignx
    pipeline_stages:
      - json:
          expressions:
            level: level
            timestamp: timestamp
```

---

## 🚨 Troubleshooting

### High CPU Usage

1. Check active connection count: `curl http://localhost:8080/admin/stats`
2. Review GC logs: Add `-Xlog:gc:file=/var/log/jnignx/gc.log`
3. Profile with JFR: `jcmd <pid> JFR.start`

### Memory Issues

1. Check heap usage: `jstat -gc <pid> 1000`
2. Analyze heap dump: `jmap -dump:file=heap.bin <pid>`
3. Review direct memory usage in metrics

### Connection Timeouts

1. Increase timeout values in routes.json
2. Check backend health: `curl http://localhost:8080/admin/circuits`
3. Review network connectivity to backends

### Rate Limiting Issues

1. Check rate limiter status: `curl http://localhost:8080/admin/ratelimit`
2. Adjust limits in routes.json
3. Review client IP distribution

---

## ✅ Production Readiness Checklist

- [x] Authentication enabled for Admin API
- [x] Configuration validation implemented
- [x] Request/response buffering available
- [x] CORS policies configured
- [x] Timeout management in place
- [x] Comprehensive test coverage
- [x] Monitoring and metrics setup
- [x] Structured logging configured
- [x] Security hardening applied
- [x] Performance tuning done
- [x] Documentation complete

---

## 📚 Additional Resources

- [Architecture Documentation](ARCHITECTURE.md)
- [API Reference](API.md)
- [Feature Guide](FEATURES.md)
- [Quick Start](QUICKSTART.md)

---

**Status**: Production Ready ✅  
**Version**: 1.0-SNAPSHOT  
**Last Updated**: January 16, 2026
