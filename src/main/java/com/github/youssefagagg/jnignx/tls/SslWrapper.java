package com.github.youssefagagg.jnignx.tls;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;

/**
 * TLS/SSL wrapper using SSLEngine for secure connections.
 *
 * <p>Provides TLS termination for HTTPS support. Uses Java's SSLEngine for
 * non-blocking TLS operations compatible with virtual threads.
 *
 * <p><b>Features:</b>
 * <ul>
 *   <li>TLS 1.2 and 1.3 support</li>
 *   <li>ALPN for HTTP/2 negotiation</li>
 *   <li>Zero-copy where possible</li>
 *   <li>Virtual thread compatible</li>
 * </ul>
 */
public final class SslWrapper {

  private final SSLContext sslContext;
  private final String[] protocols;
  private final String[] cipherSuites;

  /**
   * Creates an SSL wrapper with the given keystore.
   *
   * @param keystorePath     path to the keystore file (PKCS12 or JKS)
   * @param keystorePassword keystore password
   * @throws Exception if SSL context cannot be initialized
   */
  public SslWrapper(String keystorePath, String keystorePassword) throws Exception {
    this.sslContext = createSSLContext(keystorePath, keystorePassword);
    this.protocols = new String[] {"TLSv1.3", "TLSv1.2"};
    this.cipherSuites = null; // Use default cipher suites
  }

  /**
   * Creates an SSL context from a keystore.
   */
  private SSLContext createSSLContext(String keystorePath, String password) throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (FileInputStream fis = new FileInputStream(keystorePath)) {
      keyStore.load(fis, password.toCharArray());
    }

    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, password.toCharArray());

    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(keyStore);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

    return context;
  }

  /**
   * Wraps a socket channel with SSL/TLS.
   *
   * @param channel the socket channel to wrap
   * @return an SSL session handler
   * @throws IOException if SSL handshake fails
   */
  public SslSession wrap(SocketChannel channel) throws IOException {
    SSLEngine engine = sslContext.createSSLEngine();
    engine.setUseClientMode(false);
    engine.setEnabledProtocols(protocols);
    if (cipherSuites != null) {
      engine.setEnabledCipherSuites(cipherSuites);
    }

    // Enable ALPN for HTTP/2 if available
    try {
      javax.net.ssl.SSLParameters params = engine.getSSLParameters();
      params.setApplicationProtocols(new String[] {"h2", "http/1.1"});
      engine.setSSLParameters(params);
    } catch (Exception e) {
      // ALPN not supported, continue with HTTP/1.1 only
    }

    return new SslSession(engine, channel);
  }

  /**
   * Represents an SSL session wrapping a socket channel.
   */
  public static final class SslSession implements AutoCloseable {

    private final SSLEngine engine;
    private final SocketChannel channel;
    private final ByteBuffer inNetBuffer;
    private final ByteBuffer outNetBuffer;
    private final ByteBuffer inAppBuffer;
    private final ByteBuffer outAppBuffer;
    private boolean handshakeComplete = false;

    SslSession(SSLEngine engine, SocketChannel channel) {
      this.engine = engine;
      this.channel = channel;

      SSLSession session = engine.getSession();
      int netBufferSize = session.getPacketBufferSize();
      int appBufferSize = session.getApplicationBufferSize();

      this.inNetBuffer = ByteBuffer.allocateDirect(netBufferSize);
      this.outNetBuffer = ByteBuffer.allocateDirect(netBufferSize);
      this.inAppBuffer = ByteBuffer.allocateDirect(appBufferSize);
      this.outAppBuffer = ByteBuffer.allocateDirect(appBufferSize);
    }

    /**
     * Performs SSL handshake.
     *
     * @throws IOException if handshake fails
     */
    public void doHandshake() throws IOException {
      engine.beginHandshake();

      while (!handshakeComplete) {
        SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();

        switch (status) {
          case NEED_WRAP:
            outNetBuffer.clear();
            SSLEngineResult wrapResult = engine.wrap(outAppBuffer, outNetBuffer);
            outNetBuffer.flip();
            while (outNetBuffer.hasRemaining()) {
              channel.write(outNetBuffer);
            }
            break;

          case NEED_UNWRAP:
            int bytesRead = channel.read(inNetBuffer);
            if (bytesRead < 0) {
              throw new SSLException("Connection closed during handshake");
            }
            inNetBuffer.flip();
            SSLEngineResult unwrapResult = engine.unwrap(inNetBuffer, inAppBuffer);
            inNetBuffer.compact();
            break;

          case NEED_TASK:
            Runnable task;
            while ((task = engine.getDelegatedTask()) != null) {
              task.run();
            }
            break;

          case FINISHED:
          case NOT_HANDSHAKING:
            handshakeComplete = true;
            break;
        }
      }
    }

    /**
     * Reads decrypted data from the SSL connection.
     *
     * @param dst destination buffer
     * @return number of bytes read
     * @throws IOException if read fails
     */
    public int read(ByteBuffer dst) throws IOException {
      if (!handshakeComplete) {
        doHandshake();
      }

      // Try to unwrap from existing network buffer
      if (inNetBuffer.position() > 0) {
        inNetBuffer.flip();
        engine.unwrap(inNetBuffer, inAppBuffer);
        inNetBuffer.compact();
      }

      // Read more data if needed
      while (inAppBuffer.position() == 0) {
        inNetBuffer.clear();
        int bytesRead = channel.read(inNetBuffer);
        if (bytesRead < 0) {
          return -1;
        }
        inNetBuffer.flip();
        engine.unwrap(inNetBuffer, inAppBuffer);
        inNetBuffer.compact();
      }

      // Transfer decrypted data
      inAppBuffer.flip();
      int toCopy = Math.min(inAppBuffer.remaining(), dst.remaining());
      ByteBuffer slice = inAppBuffer.slice().limit(toCopy);
      dst.put(slice);
      inAppBuffer.position(inAppBuffer.position() + toCopy);
      inAppBuffer.compact();

      return toCopy;
    }

    /**
     * Writes encrypted data to the SSL connection.
     *
     * @param src source buffer
     * @return number of bytes written
     * @throws IOException if write fails
     */
    public int write(ByteBuffer src) throws IOException {
      if (!handshakeComplete) {
        doHandshake();
      }

      int bytesConsumed = 0;
      while (src.hasRemaining()) {
        outNetBuffer.clear();
        SSLEngineResult result = engine.wrap(src, outNetBuffer);
        bytesConsumed += result.bytesConsumed();
        outNetBuffer.flip();

        while (outNetBuffer.hasRemaining()) {
          channel.write(outNetBuffer);
        }

        if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
          // Should not happen with proper buffer sizes
          break;
        }
      }

      return bytesConsumed;
    }

    /**
     * Gets the negotiated protocol (for ALPN).
     *
     * @return the negotiated protocol (e.g., "h2", "http/1.1")
     */
    public String getNegotiatedProtocol() {
      try {
        return engine.getApplicationProtocol();
      } catch (Exception e) {
        return "http/1.1";
      }
    }

    @Override
    public void close() throws IOException {
      engine.closeOutbound();
      channel.close();
    }
  }
}
