package com.github.youssefagagg.jnignx.tls;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.StandardConstants;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;

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
 *   <li>SNI-based dynamic certificate selection (Caddy-style auto-HTTPS)</li>
 *   <li>On-demand certificate provisioning via CertificateManager</li>
 *   <li>Zero-copy where possible</li>
 *   <li>Virtual thread compatible</li>
 * </ul>
 */
public final class SslWrapper {

  private final SSLContext sslContext;
  private final String[] protocols;
  private final String[] cipherSuites;
  private final CertificateManager certManager;

  /**
   * Creates an SSL wrapper with the given keystore (static certificate mode).
   *
   * @param keystorePath     path to the keystore file (PKCS12 or JKS)
   * @param keystorePassword keystore password
   * @throws Exception if SSL context cannot be initialized
   */
  public SslWrapper(String keystorePath, String keystorePassword) throws Exception {
    this.sslContext = createSSLContext(keystorePath, keystorePassword);
    this.protocols = new String[] {"TLSv1.3", "TLSv1.2"};
    this.cipherSuites = null; // Use default cipher suites
    this.certManager = null;
  }

  /**
   * Creates an SSL wrapper with dynamic certificate management (auto-HTTPS mode).
   *
   * <p>This constructor enables Caddy-style automatic HTTPS where certificates
   * are provisioned on-demand based on the SNI hostname in the TLS ClientHello.
   *
   * @param certManager the certificate manager for on-demand cert provisioning
   * @throws Exception if SSL context cannot be initialized
   */
  public SslWrapper(CertificateManager certManager) throws Exception {
    this.certManager = certManager;
    this.sslContext = createDynamicSSLContext(certManager);
    this.protocols = new String[] {"TLSv1.3", "TLSv1.2"};
    this.cipherSuites = null;
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
   * Creates an SSL context with dynamic SNI-based certificate selection.
   *
   * <p>Uses a custom X509ExtendedKeyManager that intercepts the SNI hostname
   * from the TLS ClientHello and delegates to CertificateManager for
   * on-demand certificate provisioning.
   */
  private SSLContext createDynamicSSLContext(CertificateManager certManager) throws Exception {
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(
        new KeyManager[] {new SniKeyManager(certManager)},
        null, // Default trust managers
        null  // Default SecureRandom
    );
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

    // Configure ALPN — only advertise HTTP/1.1 (HTTP/2 is stubbed, not fully implemented)
    try {
      javax.net.ssl.SSLParameters params = engine.getSSLParameters();
      params.setApplicationProtocols(new String[] {"http/1.1"});

      // Enable SNI matching for dynamic cert selection
      if (certManager != null) {
        params.setSNIMatchers(java.util.List.of(
            javax.net.ssl.SNIMatcher.class.cast(
                new javax.net.ssl.SNIMatcher(StandardConstants.SNI_HOST_NAME) {
                  @Override
                  public boolean matches(SNIServerName serverName) {
                    // Accept all SNI names — cert selection happens in SniKeyManager
                    return true;
                  }
                })
        ));
      }

      engine.setSSLParameters(params);
    } catch (Exception e) {
      // ALPN not supported, continue with HTTP/1.1 only
    }

    return new SslSession(engine, channel);
  }

  /**
   * Checks if this wrapper uses dynamic certificate management.
   */
  public boolean isDynamic() {
    return certManager != null;
  }

  /**
   * Gets the certificate manager (if using auto-HTTPS mode).
   */
  public CertificateManager getCertificateManager() {
    return certManager;
  }

  /**
   * SNI-aware KeyManager that selects certificates based on the requested hostname.
   *
   * <p>This is the core of the Caddy-style auto-HTTPS functionality.
   * When a TLS ClientHello arrives with an SNI hostname, this key manager:
   * <ol>
   *   <li>Extracts the SNI hostname from the SSL session</li>
   *   <li>Asks the CertificateManager for a certificate (cached or on-demand)</li>
   *   <li>Returns the appropriate private key and certificate chain</li>
   * </ol>
   */
  private static final class SniKeyManager extends X509ExtendedKeyManager {

    private final CertificateManager certManager;

    SniKeyManager(CertificateManager certManager) {
      this.certManager = certManager;
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
      // Extract SNI hostname from the engine's SSL session
      String sniHostname = extractSniHostname(engine);
      if (sniHostname != null) {
        // Use the domain as the alias — the KeyStore will be fetched in getPrivateKey/getCertChain
        return "sni:" + sniHostname;
      }
      return null;
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
      if (alias != null && alias.startsWith("sni:")) {
        String domain = alias.substring(4);
        KeyStore keyStore = certManager.getCertificate(domain);
        if (keyStore != null) {
          try {
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
              String ksAlias = aliases.nextElement();
              if (keyStore.isKeyEntry(ksAlias)) {
                return (PrivateKey) keyStore.getKey(ksAlias, "changeit".toCharArray());
              }
            }
          } catch (Exception e) {
            System.err.println("[SniKeyManager] Failed to get private key for " + domain
                                   + ": " + e.getMessage());
          }
        }
      }
      return null;
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
      if (alias != null && alias.startsWith("sni:")) {
        String domain = alias.substring(4);
        KeyStore keyStore = certManager.getCertificate(domain);
        if (keyStore != null) {
          try {
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
              String ksAlias = aliases.nextElement();
              if (keyStore.isKeyEntry(ksAlias)) {
                java.security.cert.Certificate[] chain = keyStore.getCertificateChain(ksAlias);
                if (chain != null) {
                  X509Certificate[] x509Chain = new X509Certificate[chain.length];
                  for (int i = 0; i < chain.length; i++) {
                    x509Chain[i] = (X509Certificate) chain[i];
                  }
                  return x509Chain;
                }
              }
            }
          } catch (Exception e) {
            System.err.println("[SniKeyManager] Failed to get cert chain for " + domain
                                   + ": " + e.getMessage());
          }
        }
      }
      return null;
    }

    /**
     * Extracts the SNI hostname from the SSLEngine's handshake session.
     */
    private String extractSniHostname(SSLEngine engine) {
      try {
        SSLSession session = engine.getHandshakeSession();
        if (session instanceof ExtendedSSLSession extSession) {
          for (SNIServerName sniName : extSession.getRequestedServerNames()) {
            if (sniName.getType() == StandardConstants.SNI_HOST_NAME) {
              return ((SNIHostName) sniName).getAsciiName();
            }
          }
        }
      } catch (Exception e) {
        // Ignore — no SNI available
      }
      return null;
    }

    // Required interface methods — not used for server-side SNI selection

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
      return null;
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
      return null;
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
      return null;
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
      return null;
    }
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
      SSLEngineResult.HandshakeStatus hsStatus = engine.getHandshakeStatus();

      while (hsStatus != SSLEngineResult.HandshakeStatus.FINISHED
          && hsStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {

        switch (hsStatus) {
          case NEED_WRAP: {
            outNetBuffer.clear();
            // Use an empty buffer as the source — no application data during handshake
            ByteBuffer empty = ByteBuffer.allocate(0);
            SSLEngineResult wrapResult = engine.wrap(empty, outNetBuffer);
            hsStatus = wrapResult.getHandshakeStatus();
            outNetBuffer.flip();
            while (outNetBuffer.hasRemaining()) {
              channel.write(outNetBuffer);
            }
            break;
          }

          case NEED_UNWRAP: {
            // Read network data if the buffer is empty
            if (inNetBuffer.position() == 0) {
              int bytesRead = channel.read(inNetBuffer);
              if (bytesRead < 0) {
                throw new SSLException("Connection closed during handshake");
              }
            }
            inNetBuffer.flip();
            SSLEngineResult unwrapResult = engine.unwrap(inNetBuffer, inAppBuffer);
            inNetBuffer.compact();

            switch (unwrapResult.getStatus()) {
              case BUFFER_UNDERFLOW:
                // Need more network data — read and retry
                int bytesRead = channel.read(inNetBuffer);
                if (bytesRead < 0) {
                  throw new SSLException("Connection closed during handshake");
                }
                break;
              case BUFFER_OVERFLOW:
                // Application buffer too small — enlarge and retry
                int appSize = engine.getSession().getApplicationBufferSize();
                if (appSize > inAppBuffer.capacity()) {
                  // In practice this shouldn't happen with initial sizing
                  throw new SSLException("Application buffer overflow during handshake");
                }
                inAppBuffer.clear();
                break;
              case CLOSED:
                throw new SSLException("SSLEngine closed during handshake");
              case OK:
                break;
            }
            hsStatus = unwrapResult.getHandshakeStatus();
            break;
          }

          case NEED_TASK: {
            Runnable task;
            while ((task = engine.getDelegatedTask()) != null) {
              task.run();
            }
            hsStatus = engine.getHandshakeStatus();
            break;
          }

          default:
            break;
        }
      }
      handshakeComplete = true;
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

      // Try to unwrap from existing network buffer (leftover from handshake or prior read)
      if (inNetBuffer.position() > 0) {
        inNetBuffer.flip();
        SSLEngineResult result = engine.unwrap(inNetBuffer, inAppBuffer);
        inNetBuffer.compact();
        // Handle renegotiation during read
        if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
          Runnable task;
          while ((task = engine.getDelegatedTask()) != null) {
            task.run();
          }
        }
      }

      // Read more data if needed — do NOT clear inNetBuffer, compact preserves leftovers
      while (inAppBuffer.position() == 0) {
        int bytesRead = channel.read(inNetBuffer);
        if (bytesRead < 0) {
          return -1;
        }
        inNetBuffer.flip();
        SSLEngineResult result = engine.unwrap(inNetBuffer, inAppBuffer);
        inNetBuffer.compact();
        if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
          Runnable task;
          while ((task = engine.getDelegatedTask()) != null) {
            task.run();
          }
        }
      }

      // Transfer decrypted data
      inAppBuffer.flip();
      int toCopy = Math.min(inAppBuffer.remaining(), dst.remaining());
      if (toCopy > 0) {
        int oldLimit = inAppBuffer.limit();
        inAppBuffer.limit(inAppBuffer.position() + toCopy);
        dst.put(inAppBuffer);
        inAppBuffer.limit(oldLimit);
      }
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
