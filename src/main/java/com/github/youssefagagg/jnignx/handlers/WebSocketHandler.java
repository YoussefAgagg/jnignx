package com.github.youssefagagg.jnignx.handlers;

import com.github.youssefagagg.jnignx.core.ClientConnection;
import com.github.youssefagagg.jnignx.http.Request;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * WebSocket protocol handler with proxying support.
 *
 * <p>Implements RFC 6455 WebSocket protocol for bidirectional communication.
 * Supports both server-side WebSocket endpoints and transparent proxying.
 *
 * <p><b>Features:</b>
 * <ul>
 *   <li>WebSocket handshake upgrade</li>
 *   <li>Frame encoding/decoding</li>
 *   <li>Ping/Pong support</li>
 *   <li>Transparent proxying to backend WebSocket servers</li>
 *   <li>Virtual thread compatible</li>
 * </ul>
 */
public final class WebSocketHandler {

  private static final String MAGIC_STRING = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

  // WebSocket opcodes
  private static final byte OPCODE_CONTINUATION = 0x0;
  private static final byte OPCODE_TEXT = 0x1;
  private static final byte OPCODE_BINARY = 0x2;
  private static final byte OPCODE_CLOSE = 0x8;
  private static final byte OPCODE_PING = 0x9;
  private static final byte OPCODE_PONG = 0xA;

  /**
   * Checks if a request is a WebSocket upgrade request.
   */
  public static boolean isWebSocketUpgrade(Request request) {
    String upgrade = request.headers().get("upgrade");
    String connection = request.headers().get("connection");

    return upgrade != null && upgrade.equalsIgnoreCase("websocket") &&
        connection != null && connection.toLowerCase().contains("upgrade");
  }

  /**
   * Handles WebSocket upgrade and proxying through a ClientConnection.
   *
   * @param conn       the client connection (may be TLS)
   * @param request    the upgrade request
   * @param backendUrl the backend WebSocket URL
   */
  public static void handleWebSocket(ClientConnection conn, Request request,
                                     String backendUrl)
      throws IOException {

    String key = request.headers().get("sec-websocket-key");
    if (key == null) {
      sendBadRequest(conn);
      return;
    }

    // Connect to backend WebSocket server
    URI uri = URI.create(backendUrl);
    SocketChannel backendChannel = SocketChannel.open();
    backendChannel.connect(new java.net.InetSocketAddress(uri.getHost(),
                                                          uri.getPort() == -1 ? 80 :
                                                              uri.getPort()));

    // Forward upgrade request to backend
    forwardUpgradeRequest(backendChannel, request, uri);

    // Read backend's upgrade response
    ByteBuffer backendResponse = ByteBuffer.allocate(4096);
    backendChannel.read(backendResponse);
    backendResponse.flip();

    // Send upgrade response to client
    String acceptKey = generateAcceptKey(key);
    String upgradeResponse =
        "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: " + acceptKey + "\r\n" +
            "\r\n";

    conn.write(ByteBuffer.wrap(upgradeResponse.getBytes()));

    // Start bidirectional proxying
    startBidirectionalProxy(conn, backendChannel);
  }

  /**
   * Backward-compatible overload that wraps a raw SocketChannel.
   */
  public static void handleWebSocket(SocketChannel clientChannel, Request request,
                                     String backendUrl)
      throws IOException {
    handleWebSocket(new ClientConnection(clientChannel), request, backendUrl);
  }

  /**
   * Generates the WebSocket accept key from the client's key.
   */
  private static String generateAcceptKey(String clientKey) {
    try {
      String concat = clientKey + MAGIC_STRING;
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      byte[] hash = md.digest(concat.getBytes());
      return Base64.getEncoder().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-1 not available", e);
    }
  }

  /**
   * Forwards the upgrade request to the backend server.
   */
  private static void forwardUpgradeRequest(SocketChannel backend, Request request, URI uri)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("GET ").append(uri.getPath()).append(" HTTP/1.1\r\n");
    sb.append("Host: ").append(uri.getHost()).append("\r\n");
    sb.append("Upgrade: websocket\r\n");
    sb.append("Connection: Upgrade\r\n");

    for (var entry : request.headers().entrySet()) {
      if (!entry.getKey().equalsIgnoreCase("host")) {
        sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
      }
    }

    sb.append("\r\n");
    backend.write(ByteBuffer.wrap(sb.toString().getBytes()));
  }

  /**
   * Starts bidirectional proxying between client and backend.
   */
  private static void startBidirectionalProxy(ClientConnection client, SocketChannel backend) {
    // Client -> Backend (in virtual thread)
    CompletableFuture<Void> clientToBackend = CompletableFuture.runAsync(() -> {
      try {
        proxyClientToBackend(client, backend);
      } catch (IOException e) {
        // Connection closed
      }
    }, Thread::startVirtualThread);

    // Backend -> Client (in virtual thread)
    CompletableFuture<Void> backendToClient = CompletableFuture.runAsync(() -> {
      try {
        proxyBackendToClient(backend, client);
      } catch (IOException e) {
        // Connection closed
      }
    }, Thread::startVirtualThread);

    // Wait for either direction to close
    try {
      CompletableFuture.anyOf(clientToBackend, backendToClient).join();
    } finally {
      try {
        client.close();
      } catch (IOException ignored) {
      }
      closeQuietly(backend);
    }
  }

  /**
   * Proxies data from client (possibly TLS) to backend (plain).
   */
  private static void proxyClientToBackend(ClientConnection source, SocketChannel dest)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(65536);

    while (true) {
      buffer.clear();
      int bytesRead = source.read(buffer);

      if (bytesRead == -1) {
        break;
      }

      buffer.flip();

      while (buffer.hasRemaining()) {
        dest.write(buffer);
      }
    }
  }

  /**
   * Proxies data from backend (plain) to client (possibly TLS).
   */
  private static void proxyBackendToClient(SocketChannel source, ClientConnection dest)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(65536);

    while (true) {
      buffer.clear();
      int bytesRead = source.read(buffer);

      if (bytesRead == -1) {
        break;
      }

      buffer.flip();
      dest.write(buffer);
    }
  }

  /**
   * Reads a WebSocket frame.
   */
  public static WebSocketFrame readFrame(SocketChannel channel) throws IOException {
    ByteBuffer header = ByteBuffer.allocate(2);
    int bytesRead = channel.read(header);

    if (bytesRead < 2) {
      return null;
    }

    header.flip();

    byte b1 = header.get();
    boolean fin = (b1 & 0x80) != 0;
    byte opcode = (byte) (b1 & 0x0F);

    byte b2 = header.get();
    boolean masked = (b2 & 0x80) != 0;
    long payloadLength = b2 & 0x7F;

    // Extended payload length
    if (payloadLength == 126) {
      ByteBuffer extLen = ByteBuffer.allocate(2);
      channel.read(extLen);
      extLen.flip();
      payloadLength = extLen.getShort() & 0xFFFF;
    } else if (payloadLength == 127) {
      ByteBuffer extLen = ByteBuffer.allocate(8);
      channel.read(extLen);
      extLen.flip();
      payloadLength = extLen.getLong();
    }

    // Masking key
    byte[] maskingKey = null;
    if (masked) {
      maskingKey = new byte[4];
      ByteBuffer.wrap(maskingKey).clear();
      channel.read(ByteBuffer.wrap(maskingKey));
    }

    // Payload
    ByteBuffer payload = ByteBuffer.allocate((int) payloadLength);
    channel.read(payload);
    payload.flip();

    // Unmask if needed
    if (masked && maskingKey != null) {
      byte[] data = new byte[payload.remaining()];
      payload.get(data);
      for (int i = 0; i < data.length; i++) {
        data[i] ^= maskingKey[i % 4];
      }
      payload = ByteBuffer.wrap(data);
    }

    return new WebSocketFrame(fin, opcode, payload);
  }

  /**
   * Writes a WebSocket frame.
   */
  public static void writeFrame(SocketChannel channel, WebSocketFrame frame) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(frame.payload().remaining() + 14);

    // First byte: FIN + opcode
    byte b1 = (byte) (frame.opcode() & 0x0F);
    if (frame.fin()) {
      b1 |= 0x80;
    }
    buffer.put(b1);

    // Second byte: mask flag + payload length
    int payloadLength = frame.payload().remaining();
    if (payloadLength < 126) {
      buffer.put((byte) payloadLength);
    } else if (payloadLength < 65536) {
      buffer.put((byte) 126);
      buffer.putShort((short) payloadLength);
    } else {
      buffer.put((byte) 127);
      buffer.putLong(payloadLength);
    }

    // Payload
    buffer.put(frame.payload());

    buffer.flip();
    channel.write(buffer);
  }

  private static void sendBadRequest(ClientConnection conn) throws IOException {
    String response = "HTTP/1.1 400 Bad Request\r\n\r\n";
    conn.write(ByteBuffer.wrap(response.getBytes()));
  }

  private static void closeQuietly(SocketChannel channel) {
    try {
      if (channel != null) {
        channel.close();
      }
    } catch (IOException ignored) {
    }
  }

  /**
   * Represents a WebSocket frame.
   */
  public record WebSocketFrame(boolean fin, byte opcode, ByteBuffer payload) {
  }
}
