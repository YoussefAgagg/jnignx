package com.github.youssefagagg.jnignx.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.youssefagagg.jnignx.NanoServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for the complete jnignx system.
 * Tests the full request/response flow with real network connections.
 */
class IntegrationTest {

  private static final int PROXY_PORT = 19991;
  private static final int BACKEND_PORT = 18881;
  @TempDir
  Path tempDir;
  private Thread backendThread;
  private Thread proxyThread;
  private volatile boolean backendRunning = true;
  private volatile boolean serverReady = false;

  @BeforeEach
  void setup() throws Exception {
    // Start mock backend server
    startBackendServer();

    // Create config file
    Path configFile = tempDir.resolve("config.json");
    String config = String.format("""
                                      {
                                        "port": %d,
                                        "routes": {
                                          "/": ["http://localhost:%d"],
                                          "/api": ["http://localhost:%d"]
                                        },
                                        "loadBalancer": "ROUND_ROBIN",
                                        "healthCheck": {
                                          "enabled": false
                                        }
                                      }
                                      """, PROXY_PORT, BACKEND_PORT, BACKEND_PORT);
    Files.writeString(configFile, config);

    // Start proxy server
    startProxyServer(configFile);

    // Wait for servers to be ready
    assertTrue(waitForServer(BACKEND_PORT, 5000), "Backend server failed to start");
    assertTrue(waitForServer(PROXY_PORT, 5000), "Proxy server failed to start");
  }

  @AfterEach
  void tearDown() {
    backendRunning = false;

    if (backendThread != null) {
      backendThread.interrupt();
    }
    if (proxyThread != null) {
      proxyThread.interrupt();
    }
  }

  @Test
  void testBasicProxyRequest() throws Exception {
    String response = sendGetRequest("http://localhost:" + PROXY_PORT + "/test");

    assertNotNull(response);
    assertTrue(response.contains("HTTP/1.1 200") || response.contains("Hello from backend"));
  }

  @Test
  void testMultipleRequests() throws Exception {
    for (int i = 0; i < 10; i++) {
      String response = sendGetRequest("http://localhost:" + PROXY_PORT + "/test");
      assertNotNull(response, "Request " + i + " failed");
    }
  }

  @Test
  void testPostRequest() throws Exception {
    String response = sendPostRequest("http://localhost:" + PROXY_PORT + "/api/data",
                                      "{\"key\":\"value\"}");

    assertNotNull(response);
  }

  @Test
  void testLargeResponse() throws Exception {
    String response = sendGetRequest("http://localhost:" + PROXY_PORT + "/large");
    assertNotNull(response);
  }

  @Test
  void testConcurrentRequests() throws Exception {
    int numThreads = 10;
    CountDownLatch latch = new CountDownLatch(numThreads);
    Thread[] threads = new Thread[numThreads];

    for (int i = 0; i < numThreads; i++) {
      final int threadId = i;
      threads[i] = new Thread(() -> {
        try {
          String response = sendGetRequest("http://localhost:" + PROXY_PORT + "/test");
          assertNotNull(response, "Thread " + threadId + " got null response");
        } catch (Exception e) {
          fail("Thread " + threadId + " failed: " + e.getMessage());
        } finally {
          latch.countDown();
        }
      });
      threads[i].start();
    }

    assertTrue(latch.await(30, TimeUnit.SECONDS), "Not all threads completed");
  }

  @Test
  void testHealthEndpoint() throws Exception {
    String response = sendGetRequest("http://localhost:" + PROXY_PORT + "/health");
    assertNotNull(response);
    assertTrue(response.contains("200") || response.contains("ok"));
  }

  @Test
  void testMetricsEndpoint() throws Exception {
    // Generate some traffic first
    sendGetRequest("http://localhost:" + PROXY_PORT + "/test");

    String response = sendGetRequest("http://localhost:" + PROXY_PORT + "/metrics");
    assertNotNull(response);
  }

  @Test
  void testInvalidPath() throws Exception {
    String response = sendGetRequest("http://localhost:" + PROXY_PORT + "/nonexistent");
    assertNotNull(response);
    // Backend will handle or return error
  }

  @Test
  void testConnectionPersistence() throws Exception {
    // Test multiple requests on same connection
    try (Socket socket = new Socket("localhost", PROXY_PORT)) {
      PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
      BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

      // Request 1
      out.print("GET /test HTTP/1.1\r\n");
      out.print("Host: localhost\r\n");
      out.print("Connection: keep-alive\r\n");
      out.print("\r\n");
      out.flush();

      StringBuilder response1 = new StringBuilder();
      String line;
      while ((line = in.readLine()) != null && !line.isEmpty()) {
        response1.append(line).append("\n");
      }

      assertFalse(response1.toString().isEmpty());

      // Request 2 on same connection
      out.print("GET /test HTTP/1.1\r\n");
      out.print("Host: localhost\r\n");
      out.print("Connection: close\r\n");
      out.print("\r\n");
      out.flush();

      StringBuilder response2 = new StringBuilder();
      while ((line = in.readLine()) != null && !line.isEmpty()) {
        response2.append(line).append("\n");
      }

      assertFalse(response2.toString().isEmpty());
    }
  }

  private void startBackendServer() {
    backendThread = new Thread(() -> {
      try (ServerSocket serverSocket = new ServerSocket(BACKEND_PORT)) {
        serverSocket.setSoTimeout(1000);

        while (backendRunning) {
          try {
            Socket socket = serverSocket.accept();
            handleBackendConnection(socket);
          } catch (IOException e) {
            // Timeout or interrupted - continue
          }
        }
      } catch (IOException e) {
        if (backendRunning) {
          e.printStackTrace();
        }
      }
    });
    backendThread.setDaemon(true);
    backendThread.start();
  }

  private void handleBackendConnection(Socket socket) {
    new Thread(() -> {
      try (socket;
           BufferedReader in = new BufferedReader(
               new InputStreamReader(socket.getInputStream()));
           PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

        // Read request
        String line;
        StringBuilder request = new StringBuilder();
        while ((line = in.readLine()) != null && !line.isEmpty()) {
          request.append(line).append("\n");
        }

        // Send response
        String responseBody = "Hello from backend";
        if (request.toString().contains("/large")) {
          responseBody = "Large data: " + "x".repeat(10000);
        }

        out.print("HTTP/1.1 200 OK\r\n");
        out.print("Content-Type: text/plain\r\n");
        out.print("Content-Length: " + responseBody.length() + "\r\n");
        out.print("\r\n");
        out.print(responseBody);
        out.flush();
      } catch (IOException e) {
        // Connection closed
      }
    }).start();
  }

  private void startProxyServer(Path configFile) {
    proxyThread = new Thread(() -> {
      try {
        NanoServer.main(new String[] {configFile.toString()});
      } catch (Exception e) {
        if (backendRunning) {
          e.printStackTrace();
        }
      }
    });
    proxyThread.setDaemon(true);
    proxyThread.start();
  }

  private boolean waitForServer(int port, long timeoutMs) {
    long start = System.currentTimeMillis();
    while (System.currentTimeMillis() - start < timeoutMs) {
      try (Socket socket = new Socket("localhost", port)) {
        return true;
      } catch (IOException e) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException ie) {
          return false;
        }
      }
    }
    return false;
  }

  private String sendGetRequest(String urlString) throws Exception {
    URL url = new URL(urlString);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(5000);

    try {
      int responseCode = conn.getResponseCode();
      InputStream is = responseCode < 400 ? conn.getInputStream() : conn.getErrorStream();

      if (is != null) {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(is, StandardCharsets.UTF_8))) {
          StringBuilder response = new StringBuilder();
          String line;
          while ((line = reader.readLine()) != null) {
            response.append(line).append("\n");
          }
          return response.toString();
        }
      }
      return "HTTP/1.1 " + responseCode;
    } finally {
      conn.disconnect();
    }
  }

  private String sendPostRequest(String urlString, String body) throws Exception {
    URL url = new URL(urlString);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(5000);
    conn.setRequestProperty("Content-Type", "application/json");

    try (OutputStream os = conn.getOutputStream()) {
      os.write(body.getBytes(StandardCharsets.UTF_8));
    }

    try {
      int responseCode = conn.getResponseCode();
      InputStream is = responseCode < 400 ? conn.getInputStream() : conn.getErrorStream();

      if (is != null) {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(is, StandardCharsets.UTF_8))) {
          StringBuilder response = new StringBuilder();
          String line;
          while ((line = reader.readLine()) != null) {
            response.append(line).append("\n");
          }
          return response.toString();
        }
      }
      return "HTTP/1.1 " + responseCode;
    } finally {
      conn.disconnect();
    }
  }
}
