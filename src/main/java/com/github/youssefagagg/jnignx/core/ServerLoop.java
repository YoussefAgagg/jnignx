package com.github.youssefagagg.jnignx.core;

import com.github.youssefagagg.jnignx.tls.AcmeClient;
import com.github.youssefagagg.jnignx.tls.CertificateManager;
import com.github.youssefagagg.jnignx.tls.SslWrapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * Main server loop that accepts connections and spawns workers.
 *
 * <p>Supports HTTP, HTTPS, and auto-HTTPS with:
 * <ul>
 *   <li>Optional TLS/SSL for HTTPS</li>
 *   <li>Dual-port HTTP + HTTPS listeners for auto-HTTPS mode</li>
 *   <li>HTTP → HTTPS redirect when auto-HTTPS is enabled</li>
 *   <li>ACME HTTP-01 challenge handling on the HTTP port</li>
 * </ul>
 */
public class ServerLoop {
  private final int port;
  private final Router router;
  private final SslWrapper sslWrapper;
  private final CertificateManager certManager;
  private final int httpsPort;
  private final boolean httpToHttpsRedirect;
  private volatile boolean running;
  private ServerSocketChannel serverChannel;
  private ServerSocketChannel httpsServerChannel;

  /**
   * Creates a ServerLoop without TLS (HTTP only).
   */
  public ServerLoop(int port, Router router) {
    this(port, router, null, null, 0, false);
  }

  /**
   * Creates a ServerLoop with optional TLS support.
   *
   * @param port       the port to listen on
   * @param router     the request router
   * @param sslWrapper optional SSL wrapper for HTTPS (null for HTTP)
   */
  public ServerLoop(int port, Router router, SslWrapper sslWrapper) {
    this(port, router, sslWrapper, null, 0, false);
  }

  /**
   * Creates a ServerLoop with auto-HTTPS support (Caddy-style).
   *
   * @param httpPort            the HTTP port (for redirects and ACME challenges)
   * @param router              the request router
   * @param sslWrapper          the SSL wrapper with dynamic cert management
   * @param certManager         the certificate manager for on-demand provisioning
   * @param httpsPort           the HTTPS port to listen on
   * @param httpToHttpsRedirect whether to redirect HTTP to HTTPS
   */
  public ServerLoop(int httpPort, Router router, SslWrapper sslWrapper,
                    CertificateManager certManager, int httpsPort,
                    boolean httpToHttpsRedirect) {
    this.port = httpPort;
    this.router = router;
    this.sslWrapper = sslWrapper;
    this.certManager = certManager;
    this.httpsPort = httpsPort;
    this.httpToHttpsRedirect = httpToHttpsRedirect;
    this.running = true;
  }

  public void start() throws IOException {
    if (certManager != null && httpsPort > 0) {
      // Auto-HTTPS mode: start both HTTP and HTTPS listeners
      startDualMode();
    } else {
      // Single-port mode (HTTP or HTTPS with static cert)
      startSingleMode();
    }
  }

  /**
   * Starts dual HTTP + HTTPS listeners for auto-HTTPS mode.
   */
  private void startDualMode() throws IOException {
    // Start HTTPS listener in a virtual thread
    Thread httpsThread = Thread.startVirtualThread(() -> {
      try (ServerSocketChannel httpsChannel = ServerSocketChannel.open()) {
        this.httpsServerChannel = httpsChannel;
        httpsChannel.bind(new InetSocketAddress(httpsPort));
        System.out.println("[Server] HTTPS listening on port " + httpsPort + " (auto-HTTPS)");

        while (running && httpsChannel.isOpen()) {
          try {
            SocketChannel clientChannel = httpsChannel.accept();
            Thread.startVirtualThread(new Worker(clientChannel, router, sslWrapper));
          } catch (IOException e) {
            if (running && httpsChannel.isOpen()) {
              System.err.println("[Server] HTTPS accept error: " + e.getMessage());
            }
          }
        }
      } catch (IOException e) {
        System.err.println("[Server] Failed to start HTTPS listener: " + e.getMessage());
      }
    });

    // Start HTTP listener (for ACME challenges and optional redirect)
    try (ServerSocketChannel channel = ServerSocketChannel.open()) {
      this.serverChannel = channel;
      channel.bind(new InetSocketAddress(port));
      System.out.println("[Server] HTTP listening on port " + port
                             +
                             (httpToHttpsRedirect ? " (redirect → HTTPS)" : " (ACME challenges)"));

      while (running && channel.isOpen()) {
        try {
          SocketChannel clientChannel = channel.accept();
          Thread.startVirtualThread(() -> handleHttpRequest(clientChannel));
        } catch (IOException e) {
          if (running && channel.isOpen()) {
            System.err.println("[Server] HTTP accept error: " + e.getMessage());
          }
        }
      }
    }
  }

  /**
   * Starts a single HTTP or HTTPS listener.
   */
  private void startSingleMode() throws IOException {
    try (ServerSocketChannel channel = ServerSocketChannel.open()) {
      this.serverChannel = channel;
      channel.bind(new InetSocketAddress(port));

      String protocol = sslWrapper != null ? "HTTPS" : "HTTP";
      System.out.println("[Server] Listening on " + protocol + " port " + port);

      while (running && channel.isOpen()) {
        try {
          SocketChannel clientChannel = channel.accept();
          Thread.startVirtualThread(new Worker(clientChannel, router, sslWrapper));
        } catch (IOException e) {
          if (running && channel.isOpen()) {
            System.err.println("[Server] Accept error: " + e.getMessage());
          }
        }
      }
    }
  }

  /**
   * Handles HTTP requests on the HTTP port when auto-HTTPS is enabled.
   *
   * <p>This serves two purposes:
   * <ul>
   *   <li>Respond to ACME HTTP-01 challenges at /.well-known/acme-challenge/</li>
   *   <li>Redirect all other HTTP requests to HTTPS (if redirect is enabled)</li>
   * </ul>
   */
  private void handleHttpRequest(SocketChannel clientChannel) {
    try {
      ByteBuffer buffer = ByteBuffer.allocate(4096);
      int bytesRead = clientChannel.read(buffer);
      if (bytesRead <= 0) {
        closeQuietly(clientChannel);
        return;
      }
      buffer.flip();

      String rawRequest = StandardCharsets.UTF_8.decode(buffer).toString();

      // Parse the first line to get method and path
      String[] lines = rawRequest.split("\r\n");
      if (lines.length == 0) {
        closeQuietly(clientChannel);
        return;
      }

      String[] requestLine = lines[0].split(" ");
      if (requestLine.length < 2) {
        closeQuietly(clientChannel);
        return;
      }

      String path = requestLine[1];

      // Check for ACME HTTP-01 challenge
      if (certManager != null && AcmeClient.ChallengeHandler.isAcmeChallenge(path)) {
        handleAcmeChallenge(clientChannel, path);
        return;
      }

      // Redirect to HTTPS if enabled
      if (httpToHttpsRedirect) {
        String host = extractHost(lines);
        // Remove port from host if present
        if (host != null && host.contains(":")) {
          host = host.substring(0, host.indexOf(':'));
        }
        if (host == null) {
          host = "localhost";
        }

        String httpsUrl = "https://" + host;
        if (httpsPort != 443) {
          httpsUrl += ":" + httpsPort;
        }
        httpsUrl += path;

        sendRedirect(clientChannel, httpsUrl);
        return;
      }

      // If no redirect, pass to normal worker
      // Re-create the buffer for the worker to read
      buffer.rewind();
      Thread.startVirtualThread(new Worker(clientChannel, router));

    } catch (IOException e) {
      closeQuietly(clientChannel);
    }
  }

  /**
   * Handles an ACME HTTP-01 challenge request.
   */
  private void handleAcmeChallenge(SocketChannel clientChannel, String path) {
    try {
      String token = AcmeClient.ChallengeHandler.extractToken(path);
      AcmeClient.ChallengeHandler challengeHandler = certManager.getChallengeHandler();
      String keyAuthorization = challengeHandler.getChallenge(token);

      if (keyAuthorization != null) {
        String response = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: " + keyAuthorization.length() + "\r\n" +
            "\r\n" +
            keyAuthorization;
        clientChannel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
        System.out.println("[ACME] Served challenge for token: " + token);
      } else {
        String body = "Challenge not found";
        String response = "HTTP/1.1 404 Not Found\r\n" +
            "Content-Length: " + body.length() + "\r\n" +
            "\r\n" +
            body;
        clientChannel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
      }
    } catch (IOException e) {
      // Ignore
    } finally {
      closeQuietly(clientChannel);
    }
  }

  /**
   * Sends an HTTP 301 redirect response.
   */
  private void sendRedirect(SocketChannel clientChannel, String location) {
    try {
      String body = "<html><body>Moved to <a href=\"" + location + "\">" + location
          + "</a></body></html>";
      String response = "HTTP/1.1 301 Moved Permanently\r\n" +
          "Location: " + location + "\r\n" +
          "Content-Type: text/html\r\n" +
          "Content-Length: " + body.length() + "\r\n" +
          "Connection: close\r\n" +
          "\r\n" +
          body;
      clientChannel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
    } catch (IOException e) {
      // Ignore
    } finally {
      closeQuietly(clientChannel);
    }
  }

  /**
   * Extracts the Host header from parsed request lines.
   */
  private String extractHost(String[] lines) {
    for (String line : lines) {
      if (line.toLowerCase().startsWith("host:")) {
        return line.substring(5).trim();
      }
    }
    return null;
  }

  public void stop() {
    running = false;
    if (serverChannel != null) {
      try {
        serverChannel.close();
      } catch (IOException e) {
        // Ignore
      }
    }
    if (httpsServerChannel != null) {
      try {
        httpsServerChannel.close();
      } catch (IOException e) {
        // Ignore
      }
    }
  }

  /**
   * Checks if this server loop is configured for HTTPS.
   */
  public boolean isHttps() {
    return sslWrapper != null;
  }

  /**
   * Checks if this server loop is in auto-HTTPS mode.
   */
  public boolean isAutoHttps() {
    return certManager != null;
  }

  private void closeQuietly(SocketChannel channel) {
    try {
      if (channel != null && channel.isOpen()) {
        channel.close();
      }
    } catch (IOException ignored) {
    }
  }
}
