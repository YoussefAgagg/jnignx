# NanoServer Architecture & Implementation Roadmap

## 1. Project Goal

Create a high-performance, modern Java clone of **Nginx** (performance, stability) and **Caddy** (usability, automatic
HTTPS), leveraging the latest JVM features (Java 25 Preview).

## 2. Core Architecture

### 2.1. Threading Model: Virtual Threads (Project Loom)

Unlike Nginx (Event Loop/Worker Processes) or Netty (Reactive/NIO), NanoServer uses a **Thread-per-Connection** model
powered by **Virtual Threads**.

* **Why:** Virtual threads are lightweight (~1KB) and cheap to create. They allow writing simple, blocking I/O code (
  easier to maintain) while the JVM handles the underlying non-blocking OS operations (epoll/kqueue) automatically.
* **Scale:** Can handle millions of concurrent connections similar to Go routines (Caddy).

### 2.2. Memory Management: Foreign Function & Memory API (Project Panama)

Use `java.lang.foreign.MemorySegment` and `Arena` instead of `ByteBuffer`.

* **Off-Heap Storage:** Reduce GC pressure by allocating buffers off-heap.
* **Zero-Copy:** Use `FileChannel.transferTo()` and `SocketChannel` direct transfers for static files and proxying.
* **Explicit Deallocation:** Use `Arena.ofConfined()` for request-scoped memory that is automatically freed when the
  request handling (Virtual Thread) finishes.

### 2.3. Native Compilation: GraalVM

* **Goal:** Instant startup (<50ms) and low memory footprint.
* **Constraint:** Avoid runtime reflection. Use compile-time generation or handles where possible.
* **Dependencies:** Keep dependencies to zero or minimal to ensure native image compatibility and small binary size.

---

## 3. Required Features (To Implement)

To achieve parity with Nginx and Caddy, the following components must be implemented:

### 3.1. HTTP Protocol Stack

* **HTTP/1.1 (Current):** Enhance `SimpleJsonParser` to a full HTTP parser (Requests, Responses, Chunked Transfer
  Encoding).
* **HTTP/2 (Priority):**
    * Binary framing layer.
    * Stream multiplexing (multiple requests per connection).
    * Header compression (HPACK).
* **HTTP/3 (Future):** Requires QUIC (UDP). Java 25 may have standard API for this, or use FFM to bind to a native QUIC
  library (e.g., msquic) if standard library is insufficient.

### 3.2. Security & TLS (Caddy-like Features)

* **TLS Termination:** Implement `SSLHandler` using `javax.net.ssl.SSLEngine`.
    * Must handle `ALPN` (Application-Layer Protocol Negotiation) to support HTTP/2.
* **Automatic HTTPS (ACME):**
    * Implement an ACME client (RFC 8555) to talk to Let's Encrypt.
    * Challenge handling: HTTP-01 (serve specific file) and TLS-ALPN-01.
    * Certificate storage and automatic renewal.

### 3.3. Static File Server (Nginx-like Features)

* **Zero-Copy Sending:** Use `FileChannel.transferTo` to send files directly from disk to socket without userspace
  copying.
* **MIME Types:** Map file extensions to Content-Type.
* **Caching:** Support `ETag`, `Last-Modified`, and `Cache-Control` headers.
* **Range Requests:** Support `Range` header for video streaming/resuming downloads.
* **Compression:** On-the-fly Gzip/Brotli compression for text assets.

### 3.4. Advanced Reverse Proxy

* **Load Balancing Algorithms:**
    * Round Robin (Implemented).
    * Least Connections (Needs atomic counter per backend).
    * IP Hash (Sticky sessions).
* **Health Checks:** Active (pinging backends) and Passive (monitoring error rates).
* **Header Manipulation:** Add `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Proto`.
* **WebSockets:** Support upgrading connections and piping data bi-directionally.

### 3.5. Observability

* **Access Logs:** JSON structured logging (Time, Client IP, Path, Status, Duration, User-Agent).
* **Metrics:** Expose internal counters (Requests/sec, Active Connections, Memory Usage) via a `/metrics` endpoint (
  Prometheus format).

### 3.6. Configuration

* **Current:** `routes.json`.
* **Evolution:** Keep JSON for machine readability, but potentially add a "Nanofile" (DSL like Caddyfile) that compiles
  to JSON for human usability.
* **Hot Reload:** Continue supporting lock-free configuration swapping (AtomicReference).

---

## 4. Implementation Roadmap

### Phase 1: The Core (Foundation)

- [ ] **Robust HTTP/1.1 Parser:** Handle edge cases, headers limits, and body reading (content-length vs chunked).
- [ ] **Response Writer:** Helper to write responses efficiently using FFM.
- [ ] **Static File Handler:** Basic file serving with 404/403 handling.

### Phase 2: Security (The "Caddy" aspect)

- [ ] **TLS Support:** Integrate `SSLEngine` into the Virtual Thread loop.
- [ ] **Certificate Loading:** Load PEM/Key files from config.

### Phase 3: Performance & Proxy (The "Nginx" aspect)

- [ ] **Upstream Health Checks.**
- [ ] **Advanced Load Balancer.**
- [ ] **Access Logging.**

### Phase 4: Modernization

- [ ] **HTTP/2 Support.**
- [ ] **Automatic ACME (Let's Encrypt).**
- [ ] **Compression (Gzip).**

---

## 5. Directory Structure Recommendation

```text
src/main/java/com/github/youssefagagg/jnignx/
├── NanoServer.java          # Main Entry
├── config/
│   ├── ConfigLoader.java    # JSON/DSL Parser
│   └── RouteConfig.java     # Config POJOs
├── core/
│   ├── ServerLoop.java      # Main Accept Loop
│   └── Worker.java          # Virtual Thread Logic
├── http/
│   ├── HttpParser.java      # HTTP/1.1 & 2 Parser
│   ├── Request.java
│   └── Response.java
├── handlers/
│   ├── StaticHandler.java   # File Serving
│   ├── ProxyHandler.java    # Reverse Proxy
│   └── ErrorHandler.java
├── tls/
│   ├── SslWrapper.java      # SSLEngine encapsulation
│   └── AcmeClient.java      # Let's Encrypt automation
└── util/
    ├── MemoryUtils.java     # FFM Helpers
    └── Logger.java          # Structured Logger
```
