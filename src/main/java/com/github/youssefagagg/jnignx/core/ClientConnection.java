package com.github.youssefagagg.jnignx.core;

import com.github.youssefagagg.jnignx.tls.SslWrapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Abstracts a client connection that may or may not be TLS-encrypted.
 *
 * <p>All handlers should use this instead of raw {@link SocketChannel} so that
 * writes are properly encrypted when the connection uses HTTPS.
 */
public final class ClientConnection {

  private final SocketChannel channel;
  private final SslWrapper.SslSession sslSession;

  /**
   * Creates a plain (non-TLS) client connection.
   */
  public ClientConnection(SocketChannel channel) {
    this(channel, null);
  }

  /**
   * Creates a client connection with optional TLS.
   *
   * @param channel    the underlying socket channel
   * @param sslSession the SSL session, or null for plain HTTP
   */
  public ClientConnection(SocketChannel channel, SslWrapper.SslSession sslSession) {
    this.channel = channel;
    this.sslSession = sslSession;
  }

  /**
   * Reads data from the connection (decrypting if TLS).
   *
   * @param dst the destination buffer
   * @return the number of bytes read, or -1 if EOF
   * @throws IOException if an I/O error occurs
   */
  public int read(ByteBuffer dst) throws IOException {
    if (sslSession != null) {
      return sslSession.read(dst);
    }
    return channel.read(dst);
  }

  /**
   * Writes data to the connection (encrypting if TLS).
   *
   * @param src the source buffer
   * @return the number of bytes written
   * @throws IOException if an I/O error occurs
   */
  public int write(ByteBuffer src) throws IOException {
    if (sslSession != null) {
      return sslSession.write(src);
    }
    int written = 0;
    while (src.hasRemaining()) {
      written += channel.write(src);
    }
    return written;
  }

  /**
   * Gets the underlying socket channel.
   *
   * <p>Use this only for operations that need the raw channel
   * (e.g., getting remote address, setting socket options).
   * Do NOT use this for reading/writing data — use {@link #read} and {@link #write} instead.
   */
  public SocketChannel rawChannel() {
    return channel;
  }

  /**
   * Checks if this connection uses TLS.
   */
  public boolean isTls() {
    return sslSession != null;
  }

  /**
   * Checks if the underlying channel is open.
   */
  public boolean isOpen() {
    return channel.isOpen();
  }

  /**
   * Closes the connection.
   */
  public void close() throws IOException {
    if (sslSession != null) {
      sslSession.close();
    } else {
      channel.close();
    }
  }
}
