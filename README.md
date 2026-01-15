# NanoServer (jnignx) - Java Nginx

A high-performance Reverse Proxy & Web Server built with Java 25, leveraging modern JVM capabilities for maximum
throughput and minimum latency.

## 🚀 Features

- **Virtual Threads (Project Loom)**: One virtual thread per connection for massive concurrency
- **FFM API (Project Panama)**: Off-heap memory allocation to minimize GC pressure
- **Zero-Copy I/O**: Direct buffer transfers without JVM heap copies
- **Hot-Reload Configuration**: Atomic route configuration updates with zero downtime
- **Round-Robin Load Balancing**: High-throughput load distribution using lock-free counters
- **GraalVM Native Image Compatible**: No reflection, ready for AOT compilation

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
      "http://localhost:8081"
    ],
    "/": [
      "http://localhost:8081"
    ]
  }
}
```

- **Path Prefix Matching**: Routes are matched by longest prefix
- **Multiple Backends**: Array of URLs enables round-robin load balancing
- **Hot-Reload**: Modify the file while the server is running; changes are detected automatically

## 🏛️ Architecture

### Components

1. **NanoServer**: Main server class that accepts connections and spawns virtual threads
2. **Router**: Dynamic routing with hot-reload capability using AtomicReference
3. **ReverseProxyHandler**: Handles individual connections with zero-copy data transfer
4. **RouteConfig**: Immutable configuration record with longest-prefix matching
5. **SimpleJsonParser**: Reflection-free JSON parser for GraalVM compatibility

### Performance Optimizations

#### Virtual Threads vs Thread Pools

| Aspect          | Thread Pool           | Virtual Threads     |
|-----------------|-----------------------|---------------------|
| Stack Size      | ~1MB per thread       | ~1KB per thread     |
| Max Connections | Limited by pool size  | Millions            |
| Context Switch  | Expensive (OS kernel) | Cheap (JVM managed) |
| Code Style      | Callback/async        | Sequential          |

#### Foreign Function & Memory (FFM) API

Traditional Java I/O with `byte[]` arrays creates performance bottlenecks:

1. **GC Pressure**: Heap allocations trigger garbage collection
2. **Memory Copies**: Data copied kernel → heap → kernel

FFM API advantages:

- Allocates buffers in native memory (off-heap)
- Deterministic deallocation via Arena
- Direct ByteBuffers avoid heap copies

```
Traditional:  Socket → Kernel → JVM Heap byte[] → Kernel → Socket  (4 copies)
FFM/Direct:   Socket → Kernel → Native Buffer ──→ Kernel → Socket  (2 copies)
```

#### Zero-Copy Transfer

Uses `SocketChannel` with direct buffers that can leverage OS-level optimizations like `sendfile()` and `splice()` when
available, moving data between file descriptors without entering user space.

## 📁 Project Structure

```
jnignx/
├── build.gradle.kts          # Gradle build config with GraalVM plugin
├── routes.json               # Sample routing configuration
├── README.md                 # This file
└── src/main/java/com/github/youssefagagg/jnignx/
    ├── NanoServer.java       # Main server with virtual threads
    ├── Router.java           # Hot-reload router with round-robin LB
    ├── ReverseProxyHandler.java  # Zero-copy proxy handler
    ├── RouteConfig.java      # Immutable route configuration
    └── SimpleJsonParser.java # Reflection-free JSON parser
```

## 📈 Performance Tips

1. **Tune Virtual Thread Carrier Threads**: Set `-Djdk.virtualThreadScheduler.parallelism=N`
2. **Increase File Descriptors**: `ulimit -n 100000`
3. **Use Native Image**: 10x faster startup, 5x lower memory
4. **Buffer Size**: Adjust `BUFFER_SIZE` in ReverseProxyHandler for your workload

## 📜 License

MIT License

## 🔧 Troubleshooting

### "Address already in use"

Another process is using the port. Find it with `lsof -i :8080` and kill it, or use a different port.

### "No route configured"

Add a route in `routes.json` that matches your request path. Remember routes use prefix matching.

### Native Image Build Fails

Ensure you're using GraalVM with native-image installed: `gu install native-image`
