package com.github.youssefagagg.jnignx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.youssefagagg.jnignx.core.Router;
import com.github.youssefagagg.jnignx.core.ServerLoop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProxyHangTest {

  private static final int BACKEND_PORT = 8881;
  private static final int PROXY_PORT = 9991;
  private Thread backendThread;
  private Thread proxyThread;
  private ServerLoop serverLoop;
  private Router router;
  private Path configPath;
  private volatile boolean backendRunning = true;

  @BeforeEach
  void setup() throws IOException {
    configPath = Files.createTempFile("routes", ".json");
    String config = """
        {
          "routes": {
            "/": [
              "http://localhost:%d"
            ]
          }
        }
        """.formatted(BACKEND_PORT);
    Files.writeString(configPath, config);

    backendThread = new Thread(() -> {
      try (ServerSocket serverSocket = new ServerSocket(BACKEND_PORT)) {
        while (backendRunning) {
          try {
            Socket socket = serverSocket.accept();
            handleBackendConnection(socket);
          } catch (IOException e) {
            // ignore
          }
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    });
    backendThread.start();

    proxyThread = new Thread(() -> {
      router = new Router(configPath);
      try {
        router.loadConfig();
        serverLoop = new ServerLoop(PROXY_PORT, router);
        serverLoop.start();
      } catch (IOException e) {
        // ignore
      }
    });
    proxyThread.start();

    try {
      Thread.sleep(2000); // Wait for server and health checker to fully start
    } catch (InterruptedException e) {
    }
  }

  @AfterEach
  void tearDown() {
    backendRunning = false;
    if (serverLoop != null) {
      serverLoop.stop();
    }
    if (router != null) {
      router.stop();
    }
    try {
      Files.deleteIfExists(configPath);
    } catch (IOException e) {
    }
  }

  private void handleBackendConnection(Socket socket) {
    new Thread(() -> {
      try {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String line;
        boolean connectionClose = false;
        // Read headers
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
          if (line.toLowerCase().contains("connection: close")) {
            connectionClose = true;
          }
        }

        OutputStream out = socket.getOutputStream();
        String response = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();

        if (connectionClose) {
          socket.close();
        } else {
          // Simulate Keep-Alive: wait a bit then close
          Thread.sleep(2000);
          socket.close();
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }).start();
  }

  @Test
  @org.junit.jupiter.api.Disabled("Test needs adjustment for health checker integration")
  void testProxyHangsWithoutConnectionClose() throws IOException {
    try (Socket client = new Socket("localhost", PROXY_PORT)) {
      client.setSoTimeout(5000);
      OutputStream out = client.getOutputStream();
      out.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.UTF_8));
      out.flush();

      InputStream in = client.getInputStream();
      byte[] buffer = new byte[1024];
      int read = in.read(buffer);
      assertTrue(read > 0);

      long start = System.currentTimeMillis();
      int eof = in.read(); // Should be -1
      long duration = System.currentTimeMillis() - start;

      assertEquals(-1, eof);

      // Before fix: duration ~2000ms
      // After fix: duration < 500ms
      if (duration > 1500) {
        fail("Proxy hung waiting for backend timeout. Duration: " + duration + "ms");
      }
    }
  }
}
