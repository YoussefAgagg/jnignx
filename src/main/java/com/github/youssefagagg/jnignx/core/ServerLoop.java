package com.github.youssefagagg.jnignx.core;

import com.github.youssefagagg.jnignx.tls.SslWrapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * Main server loop that accepts connections and spawns workers.
 *
 * <p>Supports both HTTP and HTTPS with optional TLS/SSL.
 */
public class ServerLoop {
  private final int port;
  private final Router router;
  private final SslWrapper sslWrapper;
  private volatile boolean running;

  /**
   * Creates a ServerLoop without TLS (HTTP only).
   */
  public ServerLoop(int port, Router router) {
    this(port, router, null);
  }

  /**
   * Creates a ServerLoop with optional TLS support.
   *
   * @param port       the port to listen on
   * @param router     the request router
   * @param sslWrapper optional SSL wrapper for HTTPS (null for HTTP)
   */
  public ServerLoop(int port, Router router, SslWrapper sslWrapper) {
    this.port = port;
    this.router = router;
    this.sslWrapper = sslWrapper;
    this.running = true;
  }

  public void start() throws IOException {
    try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
      serverChannel.bind(new InetSocketAddress(port));

      String protocol = sslWrapper != null ? "HTTPS" : "HTTP";
      System.out.println("[Server] Listening on " + protocol + " port " + port);

      while (running) {
        try {
          SocketChannel clientChannel = serverChannel.accept();
          Thread.startVirtualThread(new Worker(clientChannel, router, sslWrapper));
        } catch (IOException e) {
          if (running) {
            System.err.println("[Server] Accept error: " + e.getMessage());
          }
        }
      }
    }
  }

  public void stop() {
    running = false;
  }

  /**
   * Checks if this server loop is configured for HTTPS.
   */
  public boolean isHttps() {
    return sslWrapper != null;
  }
}
