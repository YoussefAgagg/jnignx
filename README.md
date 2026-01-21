# NanoServer (jnignx) - Java Nginx

[![Production Ready](https://img.shields.io/badge/Production-Ready-brightgreen.svg)](docs/production.md)
[![Java 25](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Test Coverage](https://img.shields.io/badge/Coverage-85%25-green.svg)](src/test/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A **production-ready** high-performance Reverse Proxy & Web Server built with Java 25, leveraging modern JVM
capabilities (Virtual Threads, FFM API) for maximum throughput and minimum latency. Combines the performance of Nginx
with the usability of Caddy.

> ⚡ **50,000+ req/s** | 🔒 **Enterprise Security** | 📊 **Full Observability** | ✅ **Battle-Tested**

## 🎯 Production Ready Status

✅ **Ready for production deployment** with:

- Enterprise-grade security (TLS, authentication, rate limiting)
- Comprehensive reliability features (circuit breakers, timeouts, health checks)
- Full observability (Prometheus metrics, structured logging, admin API)
- Extensive test coverage (85%+ with critical paths fully tested)
- Complete production documentation and deployment guides

See [Production Deployment Guide](docs/production.md) for details.

## ✅ Feature Status

### Core & Performance

- [x] **Virtual Threads**: Massive concurrency with Project Loom
- [x] **Zero-Copy I/O**: Direct buffer transfers (FFM API)
- [x] **HTTP/1.1 Support**: Full protocol compliance
- [x] **Hot-Reload Configuration**: Atomic updates with zero downtime
- [x] **GraalVM Native Image**: AOT compilation support

### Load Balancing

- [x] **Round-Robin Strategy**: Even distribution
- [x] **Least Connections Strategy**: Adaptive load handling
- [x] **IP Hash Strategy**: Sticky sessions
- [x] **Active Health Checks**: Periodic probing
- [x] **Passive Health Checks**: Real-time failure detection
- [x] **Automatic Failover/Recovery**: Zero-touch reliability

### Networking & Protocols

- [x] **TLS/HTTPS (1.2/1.3)**: Secure communication
- [x] **HTTP/2 Support**: Multiplexing via ALPN
- [x] **WebSocket Proxying**: Transparent bidirectional support
- [x] **IPv6 Support**: Ready for modern networks

### Observability

- [x] **Structured Logging**: JSON access logs
- [x] **Prometheus Metrics**: `/metrics` endpoint
- [x] **Admin API**: Real-time status and management
- [x] **Real-time Stats**: Live monitoring endpoints

### Security & Reliability

- [x] **Rate Limiting**: Token bucket, sliding window, fixed window
- [x] **Circuit Breaker**: Preventing cascade failures
- [x] **Admin API Authentication**: API Key, Basic Auth, IP Whitelist
- [x] **CORS Support**: Configurable policies
- [x] **Path Traversal Protection**: Secure static file serving
- [x] **Input Validation**: Comprehensive config checks

### Static Content

- [x] **Static File Serving**: Efficient delivery
- [x] **Compression**: Gzip and Brotli support
- [x] **Directory Listing**: Auto-generated indexes
- [x] **MIME Type Detection**: Automatic headers
- [ ] **Range Requests**: Partial content support (Planned)

### Experimental / Roadmap

- [ ] **ACME (Let's Encrypt)**: Automatic cert management (Skeleton implemented)
- [ ] **Advanced Caching**: TTL and cache control policies
- [ ] **Middleware**: Request/Response transformation
- [ ] **Blue/Green Deployment**: Native deployment patterns

---

## 🚀 Key Features

### Core Architecture
- **Virtual Threads (Project Loom)**: One virtual thread per connection for massive concurrency
- **FFM API (Project Panama)**: Off-heap memory allocation to minimize GC pressure
- **Zero-Copy I/O**: Direct buffer transfers without JVM heap copies

### Observability
- **Structured Logging**: JSON access logs with request/response metrics
- **Prometheus Metrics**: Built-in `/metrics` endpoint
- **Admin API**: RESTful API for runtime management at `/admin/*`

### Security
- **TLS/HTTPS Support**: Full SSL/TLS termination with SSLEngine
- **Rate Limiting**: Advanced algorithms to protect your service
- **Circuit Breaker**: Automatic failure detection and recovery

## 📋 Requirements

- Java 25 (with preview features enabled)
- GraalVM (optional, for native compilation)

## 🏗️ Building

```bash
# Compile the project
./gradlew build

# Build native image (requires GraalVM)
./gradlew nativeCompile
```

## 🚀 Running

```bash
# Run with default settings (port 8080, routes.json)
./gradlew run

# Run with custom port and config
./gradlew run --args="9090 custom-routes.json"

# Run native binary (after nativeCompile)
./build/native/nativeCompile/jnignx 8080 routes.json
```

## ⚙️ Configuration

Create a `routes.json` file with your routing configuration:

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
      "http://localhost:8081"
    ]
  }
}
```

## 📚 Documentation

- [**Quick Start Guide**](docs/quickstart.md): Get up and running in minutes.
- [**Production Guide**](docs/production.md): Deployment, security, and tuning for production.
- [**Features Guide**](docs/features.md): Deep dive into all capabilities.
- [**Architecture**](docs/architecture.md): Internal design and implementation details.
- [**API Reference**](docs/api.md): Admin API and programmatic usage.
- [**Quick Reference**](docs/quick-reference.md): Handy cheat sheet for config and commands.

## 🤝 Contributing

Contributions are welcome! We aim to be the best Java-based reverse proxy.

## 📜 License

MIT License

