package com.github.youssefagagg.jnignx.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * Main server loop that accepts connections and spawns workers.
 */
public class ServerLoop {
  private final int port;
  private final Router router;
  private volatile boolean running;

  public ServerLoop(int port, Router router) {
    this.port = port;
    this.router = router;
    this.running = true;
  }

  public void start() throws IOException {
    try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
      serverChannel.bind(new InetSocketAddress(port));

      while (running) {
        try {
          SocketChannel clientChannel = serverChannel.accept();
          Thread.startVirtualThread(new Worker(clientChannel, router));
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
}
