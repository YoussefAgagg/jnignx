package com.github.youssefagagg.jnignx;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

/**
 * NanoServer - A high-performance Reverse Proxy & Web Server ("Java Nginx").
 *
 * <p><b>Architecture Overview:</b>
 * This server is designed for maximum throughput and minimum latency using
 * modern JVM capabilities:
 *
 * <ul>
 *   <li><b>Virtual Threads (Project Loom):</b> Each incoming connection is handled
 *       by a dedicated virtual thread. Virtual threads are extremely lightweight
 *       (~1KB vs ~1MB for platform threads), allowing millions of concurrent
 *       connections without exhausting system resources.</li>
 *
 *   <li><b>Foreign Function & Memory API (Project Panama):</b> All buffer allocations
 *       use off-heap memory via Arena and MemorySegment, eliminating GC pressure
 *       and enabling more predictable latency.</li>
 *
 *   <li><b>Zero-Copy I/O:</b> Data is transferred between sockets using direct
 *       buffers and SocketChannel operations that leverage OS-level optimizations
 *       like sendfile() and splice().</li>
 *
 *   <li><b>Lock-Free Configuration:</b> Route configuration updates use AtomicReference
 *       for lock-free swapping, ensuring zero downtime during hot-reloads.</li>
 * </ul>
 *
 * <p><b>Why Virtual Threads Instead of Thread Pools or Netty:</b>
 * <ul>
 *   <li>Thread pools limit concurrency to pool size; virtual threads scale to millions</li>
 *   <li>Netty's event-loop model requires callback-based programming which is error-prone</li>
 *   <li>Virtual threads maintain simple sequential code while achieving async performance</li>
 *   <li>The JVM scheduler efficiently multiplexes virtual threads onto carrier threads</li>
 * </ul>
 *
 * <p><b>GraalVM Native Image Compatibility:</b>
 * This implementation avoids reflection and uses only GraalVM-compatible APIs,
 * enabling ahead-of-time compilation to a native binary for instant startup
 * and reduced memory footprint.
 */
public final class NanoServer {

  private static final int DEFAULT_PORT = 8080;
  private static final String DEFAULT_CONFIG = "routes.json";

  private final int port;
  private final Router router;
  private volatile boolean running;

  /**
   * Creates a new NanoServer with the specified port and configuration file.
   *
   * @param port       the port to listen on
   * @param configPath path to the routes.json configuration file
   */
  public NanoServer(int port, Path configPath) {
    this.port = port;
    this.router = new Router(configPath);
    this.running = true;
  }

  /**
   * Main entry point for the NanoServer application.
   *
   * <p>Usage: java --enable-preview NanoServer [port] [config-file]
   * <p>Defaults: port=8080, config-file=routes.json
   *
   * @param args command line arguments
   */
  static void main(String[] args) {
    int port = DEFAULT_PORT;
    String configFile = DEFAULT_CONFIG;

    // Parse command line arguments
    if (args.length >= 1) {
      try {
        port = Integer.parseInt(args[0]);
      } catch (NumberFormatException e) {
        System.err.println("Invalid port number: " + args[0]);
        System.exit(1);
      }
    }

    if (args.length >= 2) {
      configFile = args[1];
    }

    // Create and start the server
    NanoServer server = new NanoServer(port, Path.of(configFile));

    // Register shutdown hook for graceful shutdown
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("\n[Server] Received shutdown signal...");
      server.shutdown();
    }));

    try {
      server.start();
    } catch (IOException e) {
      System.err.println("[Server] Failed to start: " + e.getMessage());
      System.exit(1);
    }
  }

  /**
   * Starts the server and begins accepting connections.
   * Each connection is handled in a dedicated virtual thread.
   *
   * @throws IOException if the server cannot bind to the port
   */
  public void start() throws IOException {
    // Load initial configuration
    router.loadConfig();

    // Start hot-reload watcher in a virtual thread
    router.startHotReloadWatcher();

    try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
      serverChannel.bind(new InetSocketAddress(port));

      printStartupBanner();

      // Main accept loop - runs forever until shutdown
      while (running) {
        try {
          SocketChannel clientChannel = serverChannel.accept();

          // Spawn a new Virtual Thread for each connection
          // Virtual threads are extremely lightweight (~1KB stack)
          // compared to platform threads (~1MB stack)
          Thread.startVirtualThread(new ReverseProxyHandler(clientChannel, router));

        } catch (IOException e) {
          if (running) {
            System.err.println("[Server] Error accepting connection: " + e.getMessage());
          }
        }
      }
    } finally {
      router.stop();
    }
  }

  /**
   * Initiates graceful shutdown of the server.
   */
  public void shutdown() {
    running = false;
    router.stop();
    System.out.println("[Server] Shutdown initiated");
  }

  /**
   * Prints the startup banner with server information.
   */
  private void printStartupBanner() {
    System.out.println("""
                           
                           ╔═══════════════════════════════════════════════════════════════╗
                           ║                                                               ║
                           ║     ███╗   ██╗ █████╗ ███╗   ██╗ ██████╗ ███████╗███████╗    ║
                           ║     ████╗  ██║██╔══██╗████╗  ██║██╔═══██╗██╔════╝██╔════╝    ║
                           ║     ██╔██╗ ██║███████║██╔██╗ ██║██║   ██║███████╗█████╗      ║
                           ║     ██║╚██╗██║██╔══██║██║╚██╗██║██║   ██║╚════██║██╔══╝      ║
                           ║     ██║ ╚████║██║  ██║██║ ╚████║╚██████╔╝███████║███████╗    ║
                           ║     ╚═╝  ╚═══╝╚═╝  ╚═╝╚═╝  ╚═══╝ ╚═════╝ ╚══════╝╚══════╝    ║
                           ║                                                               ║
                           ║     Java Nginx - High Performance Reverse Proxy               ║
                           ║                                                               ║
                           ╚═══════════════════════════════════════════════════════════════╝
                           """);
    System.out.println("[Server] Starting on port " + port);
    System.out.println("[Server] Using Virtual Threads (Project Loom)");
    System.out.println("[Server] Using FFM API for off-heap memory (Project Panama)");
    System.out.println("[Server] Configuration hot-reload enabled");
    System.out.println("[Server] Ready to accept connections!");
    System.out.println();
  }
}
