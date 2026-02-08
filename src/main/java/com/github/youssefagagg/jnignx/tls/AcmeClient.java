package com.github.youssefagagg.jnignx.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ACME (Automatic Certificate Management Environment) client for Let's Encrypt.
 *
 * <p>Implements the ACME v2 protocol (RFC 8555) for automatic SSL/TLS certificate
 * issuance and renewal. Compatible with Let's Encrypt and other ACME-compliant CAs.
 *
 * <p><b>Features:</b>
 * <ul>
 *   <li>Full ACME v2 protocol implementation using JWS (JSON Web Signature)</li>
 *   <li>Automatic certificate issuance via Let's Encrypt</li>
 *   <li>Automatic renewal before expiration</li>
 *   <li>HTTP-01 challenge support</li>
 *   <li>Zero-downtime certificate updates</li>
 *   <li>Multi-domain (SAN) certificates</li>
 *   <li>No external dependencies — pure Java implementation</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * AcmeClient acme = new AcmeClient("admin@example.com", "example.com");
 * acme.setChallengeHandler(challengeHandler);
 * acme.obtainCertificate();
 * acme.startAutoRenewal();
 * }</pre>
 */
public final class AcmeClient {

  // Let's Encrypt production and staging endpoints
  private static final String PRODUCTION_DIRECTORY =
      "https://acme-v02.api.letsencrypt.org/directory";
  private static final String STAGING_DIRECTORY =
      "https://acme-staging-v02.api.letsencrypt.org/directory";

  private static final String KEYSTORE_PASSWORD = "changeit";
  private static final int MAX_POLL_ATTEMPTS = 30;
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

  private final String email;
  private final String[] domains;
  private final String directoryUrl;
  private final Path certPath;
  private final HttpClient httpClient;
  private final ScheduledExecutorService scheduler;

  private KeyPair accountKeyPair;
  private String accountUrl;
  private volatile X509Certificate currentCert;
  private ChallengeHandler challengeHandler;

  // ACME directory URLs (fetched from directory endpoint)
  private String newNonceUrl;
  private String newAccountUrl;
  private String newOrderUrl;

  /**
   * Creates an ACME client for Let's Encrypt production.
   *
   * @param email   contact email for certificate notifications
   * @param domains domain names to obtain certificates for
   */
  public AcmeClient(String email, String... domains) {
    this(email, false, domains);
  }

  /**
   * Creates an ACME client.
   *
   * @param email   contact email
   * @param staging use Let's Encrypt staging environment (for testing)
   * @param domains domain names
   */
  public AcmeClient(String email, boolean staging, String... domains) {
    this.email = email;
    this.domains = domains;
    this.directoryUrl = staging ? STAGING_DIRECTORY : PRODUCTION_DIRECTORY;
    this.certPath = Path.of("certs");
    this.httpClient = HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(30))
                                .build();
    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "ACME-Renewal");
      t.setDaemon(true);
      return t;
    });

    try {
      Files.createDirectories(certPath);
    } catch (IOException e) {
      throw new RuntimeException("Failed to create certificate directory", e);
    }
  }
  // Temporary storage for the certificate key pair between finalize and download
  private KeyPair lastCertKeyPair;

  /**
   * Sets the challenge handler for HTTP-01 challenge responses.
   * This must be set before calling {@link #obtainCertificate()}.
   *
   * @param handler the challenge handler shared with the server
   */
  public void setChallengeHandler(ChallengeHandler handler) {
    this.challengeHandler = handler;
  }

  /**
   * Starts automatic certificate renewal.
   * Checks daily and renews if certificate expires in less than 30 days.
   */
  public void startAutoRenewal() {
    System.out.println("[ACME] Starting automatic renewal service");

    scheduler.scheduleAtFixedRate(() -> {
      try {
        if (shouldRenew()) {
          System.out.println("[ACME] Certificate renewal triggered");
          obtainCertificate();
        }
      } catch (Exception e) {
        System.err.println("[ACME] Renewal failed: " + e.getMessage());
      }
    }, 1, 24, TimeUnit.HOURS);
  }

  /**
   * Stops the automatic renewal service.
   */
  public void stopAutoRenewal() {
    scheduler.shutdown();
  }

  /**
   * Checks if certificate should be renewed.
   */
  private boolean shouldRenew() {
    if (currentCert == null) {
      return true;
    }

    Instant expiry = currentCert.getNotAfter().toInstant();
    Instant renewAt = expiry.minus(Duration.ofDays(30));

    return Instant.now().isAfter(renewAt);
  }

  /**
   * Obtains a certificate from Let's Encrypt using the ACME v2 protocol.
   *
   * <p>The full flow:
   * <ol>
   *   <li>Fetch ACME directory to discover endpoint URLs</li>
   *   <li>Create or reuse an account key pair</li>
   *   <li>Register account with the ACME server</li>
   *   <li>Create a new certificate order for the requested domains</li>
   *   <li>Complete HTTP-01 challenges for domain validation</li>
   *   <li>Submit a CSR to finalize the order</li>
   *   <li>Download the issued certificate chain</li>
   *   <li>Save to a PKCS12 keystore</li>
   * </ol>
   *
   * @return path to the certificate keystore
   * @throws Exception if certificate issuance fails
   */
  public Path obtainCertificate() throws Exception {
    System.out.println("[ACME] Starting certificate issuance for: " + String.join(", ", domains));

    // 1. Fetch ACME directory
    fetchDirectory();

    // 2. Create or load account key
    if (accountKeyPair == null) {
      accountKeyPair = generateKeyPair();
    }

    // 3. Register account (if not already registered)
    if (accountUrl == null) {
      registerAccount();
    }

    // 4. Create new order
    String orderUrl = createOrder();

    // 5. Get authorizations and complete challenges
    String[] authUrls = getAuthorizations(orderUrl);
    for (String authUrl : authUrls) {
      completeChallenge(authUrl);
    }

    // 6. Finalize order (generate and submit CSR)
    String certUrl = finalizeOrder(orderUrl);

    // 7. Download certificate and save to keystore
    return downloadAndSaveCertificate(certUrl);
  }

  // ──────────────────────────────────────────────────────────────────────
  //  ACME v2 Protocol Implementation
  // ──────────────────────────────────────────────────────────────────────

  /**
   * Generates an RSA key pair for ACME account or certificate.
   */
  private KeyPair generateKeyPair() throws Exception {
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
    keyGen.initialize(2048);
    return keyGen.generateKeyPair();
  }

  /**
   * Fetches the ACME directory to discover endpoint URLs.
   */
  private void fetchDirectory() throws Exception {
    System.out.println("[ACME] Fetching directory from: " + directoryUrl);

    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(directoryUrl))
                                     .GET()
                                     .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    String body = response.body();

    newNonceUrl = extractJsonString(body, "newNonce");
    newAccountUrl = extractJsonString(body, "newAccount");
    newOrderUrl = extractJsonString(body, "newOrder");

    System.out.println("[ACME] Directory fetched successfully");
  }

  /**
   * Fetches a fresh anti-replay nonce from the ACME server.
   */
  private String fetchNonce() throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(newNonceUrl))
                                     .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                     .build();

    HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    return response.headers().firstValue("Replay-Nonce").orElseThrow(
        () -> new IOException("No Replay-Nonce header in response"));
  }

  /**
   * Registers a new account with the ACME server using JWS.
   */
  private void registerAccount() throws Exception {
    System.out.println("[ACME] Registering account for: " + email);

    String nonce = fetchNonce();

    // Build the account registration payload
    String payload = """
        {"termsOfServiceAgreed":true,"contact":["mailto:%s"]}""".formatted(email);

    // Sign with JWK (no kid yet)
    String jws = signJws(newAccountUrl, nonce, payload, true);

    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(newAccountUrl))
                                     .header("Content-Type", "application/jose+json")
                                     .POST(HttpRequest.BodyPublishers.ofString(jws))
                                     .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    int status = response.statusCode();

    if (status != 200 && status != 201) {
      throw new IOException(
          "Account registration failed (HTTP " + status + "): " + response.body());
    }

    // Extract account URL from Location header
    accountUrl = response.headers().firstValue("Location").orElse(null);
    if (accountUrl == null) {
      throw new IOException("No Location header in account registration response");
    }

    System.out.println("[ACME] Account registered: " + accountUrl);
  }

  /**
   * Creates a new certificate order for the configured domains.
   *
   * @return the order URL
   */
  private String createOrder() throws Exception {
    System.out.println("[ACME] Creating order for domains: " + String.join(", ", domains));

    String nonce = fetchNonce();

    // Build identifiers array
    StringBuilder identifiers = new StringBuilder("[");
    for (int i = 0; i < domains.length; i++) {
      if (i > 0) {
        identifiers.append(",");
      }
      identifiers.append("{\"type\":\"dns\",\"value\":\"").append(domains[i]).append("\"}");
    }
    identifiers.append("]");

    String payload = "{\"identifiers\":" + identifiers + "}";

    String jws = signJws(newOrderUrl, nonce, payload, false);

    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(newOrderUrl))
                                     .header("Content-Type", "application/jose+json")
                                     .POST(HttpRequest.BodyPublishers.ofString(jws))
                                     .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    int status = response.statusCode();

    if (status != 201) {
      throw new IOException("Order creation failed (HTTP " + status + "): " + response.body());
    }

    String orderUrl = response.headers().firstValue("Location").orElseThrow(
        () -> new IOException("No Location header in order response"));

    System.out.println("[ACME] Order created: " + orderUrl);
    return orderUrl;
  }

  /**
   * Gets authorization URLs from an order.
   */
  private String[] getAuthorizations(String orderUrl) throws Exception {
    String nonce = fetchNonce();

    // POST-as-GET to fetch order details
    String jws = signJws(orderUrl, nonce, "", false);

    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(orderUrl))
                                     .header("Content-Type", "application/jose+json")
                                     .POST(HttpRequest.BodyPublishers.ofString(jws))
                                     .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    String body = response.body();

    // Extract authorization URLs from the response
    List<String> authUrls = extractJsonArray(body, "authorizations");
    return authUrls.toArray(new String[0]);
  }

  /**
   * Completes an HTTP-01 challenge for domain authorization.
   *
   * <p>Flow:
   * <ol>
   *   <li>Fetch the authorization to get the challenge token</li>
   *   <li>Compute the key authorization (token + account key thumbprint)</li>
   *   <li>Register the challenge with the HTTP server</li>
   *   <li>Notify the ACME server that the challenge is ready</li>
   *   <li>Poll until the authorization is valid</li>
   * </ol>
   */
  private void completeChallenge(String authUrl) throws Exception {
    System.out.println("[ACME] Processing authorization: " + authUrl);

    String nonce = fetchNonce();

    // POST-as-GET to fetch authorization details
    String jws = signJws(authUrl, nonce, "", false);

    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(authUrl))
                                     .header("Content-Type", "application/jose+json")
                                     .POST(HttpRequest.BodyPublishers.ofString(jws))
                                     .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    String body = response.body();

    // Check if already valid
    String authStatus = extractJsonString(body, "status");
    if ("valid".equals(authStatus)) {
      System.out.println("[ACME] Authorization already valid");
      return;
    }

    // Find HTTP-01 challenge
    String challengeUrl = null;
    String token = null;

    // Parse challenges array to find http-01
    int challengesStart = body.indexOf("\"challenges\"");
    if (challengesStart >= 0) {
      String challengesSection = body.substring(challengesStart);
      // Find http-01 challenge
      int http01Idx = challengesSection.indexOf("\"http-01\"");
      if (http01Idx >= 0) {
        // Find the challenge object containing http-01
        // Look backwards for the opening brace
        int objStart = challengesSection.lastIndexOf("{", http01Idx);
        int objEnd = challengesSection.indexOf("}", http01Idx);
        if (objStart >= 0 && objEnd >= 0) {
          String challengeObj = challengesSection.substring(objStart, objEnd + 1);
          challengeUrl = extractJsonString(challengeObj, "url");
          token = extractJsonString(challengeObj, "token");
        }
      }
    }

    if (challengeUrl == null || token == null) {
      throw new IOException("No HTTP-01 challenge found in authorization response");
    }

    // Compute key authorization = token + "." + base64url(SHA-256(JWK thumbprint))
    String thumbprint = computeAccountKeyThumbprint();
    String keyAuthorization = token + "." + thumbprint;

    // Register challenge with the HTTP server
    if (challengeHandler != null) {
      challengeHandler.addChallenge(token, keyAuthorization);
    }

    System.out.println("[ACME] Challenge ready, token: " + token);

    // Notify ACME server that challenge is ready
    nonce = fetchNonce();
    String challengeJws = signJws(challengeUrl, nonce, "{}", false);

    HttpRequest challengeRequest = HttpRequest.newBuilder()
                                              .uri(URI.create(challengeUrl))
                                              .header("Content-Type", "application/jose+json")
                                              .POST(
                                                  HttpRequest.BodyPublishers.ofString(challengeJws))
                                              .build();

    httpClient.send(challengeRequest, HttpResponse.BodyHandlers.ofString());

    // Poll authorization until valid
    for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
      Thread.sleep(POLL_INTERVAL.toMillis());

      nonce = fetchNonce();
      String pollJws = signJws(authUrl, nonce, "", false);

      HttpRequest pollRequest = HttpRequest.newBuilder()
                                           .uri(URI.create(authUrl))
                                           .header("Content-Type", "application/jose+json")
                                           .POST(HttpRequest.BodyPublishers.ofString(pollJws))
                                           .build();

      HttpResponse<String> pollResponse = httpClient.send(pollRequest,
                                                          HttpResponse.BodyHandlers.ofString());
      String pollBody = pollResponse.body();
      String status = extractJsonString(pollBody, "status");

      if ("valid".equals(status)) {
        System.out.println("[ACME] Authorization validated successfully");
        if (challengeHandler != null) {
          challengeHandler.removeChallenge(token);
        }
        return;
      } else if ("invalid".equals(status)) {
        if (challengeHandler != null) {
          challengeHandler.removeChallenge(token);
        }
        throw new IOException("Challenge validation failed: " + pollBody);
      }

      System.out.println("[ACME] Waiting for validation... (attempt " + (i + 1) + ")");
    }

    if (challengeHandler != null) {
      challengeHandler.removeChallenge(token);
    }
    throw new IOException(
        "Challenge validation timed out after " + MAX_POLL_ATTEMPTS + " attempts");
  }

  /**
   * Finalizes the order by submitting a CSR.
   *
   * @return the certificate download URL
   */
  private String finalizeOrder(String orderUrl) throws Exception {
    System.out.println("[ACME] Finalizing order");

    // First, fetch the order to get the finalize URL
    String nonce = fetchNonce();
    String jws = signJws(orderUrl, nonce, "", false);

    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(orderUrl))
                                     .header("Content-Type", "application/jose+json")
                                     .POST(HttpRequest.BodyPublishers.ofString(jws))
                                     .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    String body = response.body();

    String finalizeUrl = extractJsonString(body, "finalize");
    if (finalizeUrl == null) {
      throw new IOException("No finalize URL in order response");
    }

    // Generate a new key pair for the certificate
    KeyPair certKeyPair = generateKeyPair();

    // Build CSR (PKCS#10) using pure Java
    byte[] csrDer = buildCsr(certKeyPair, domains);
    String csrBase64 = base64UrlEncode(csrDer);

    // Submit CSR
    nonce = fetchNonce();
    String finalizePayload = "{\"csr\":\"" + csrBase64 + "\"}";
    String finalizeJws = signJws(finalizeUrl, nonce, finalizePayload, false);

    HttpRequest finalizeRequest = HttpRequest.newBuilder()
                                             .uri(URI.create(finalizeUrl))
                                             .header("Content-Type", "application/jose+json")
                                             .POST(HttpRequest.BodyPublishers.ofString(finalizeJws))
                                             .build();

    HttpResponse<String> finalizeResponse = httpClient.send(finalizeRequest,
                                                            HttpResponse.BodyHandlers.ofString());

    // Poll order until certificate is ready
    String certUrl = null;
    for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
      nonce = fetchNonce();
      String pollJws = signJws(orderUrl, nonce, "", false);

      HttpRequest pollRequest = HttpRequest.newBuilder()
                                           .uri(URI.create(orderUrl))
                                           .header("Content-Type", "application/jose+json")
                                           .POST(HttpRequest.BodyPublishers.ofString(pollJws))
                                           .build();

      HttpResponse<String> pollResponse = httpClient.send(pollRequest,
                                                          HttpResponse.BodyHandlers.ofString());
      String pollBody = pollResponse.body();
      String status = extractJsonString(pollBody, "status");

      if ("valid".equals(status)) {
        certUrl = extractJsonString(pollBody, "certificate");
        break;
      } else if ("invalid".equals(status)) {
        throw new IOException("Order finalization failed: " + pollBody);
      }

      Thread.sleep(POLL_INTERVAL.toMillis());
    }

    if (certUrl == null) {
      throw new IOException("Order finalization timed out");
    }

    // Store the cert key pair for later use when saving
    this.lastCertKeyPair = certKeyPair;
    System.out.println("[ACME] Order finalized, certificate URL: " + certUrl);
    return certUrl;
  }

  /**
   * Downloads the issued certificate and saves it to a PKCS12 keystore.
   *
   * <p>Downloads the certificate chain in PEM format from the ACME server,
   * parses it, and stores it along with the private key in a PKCS12 keystore.
   */
  private Path downloadAndSaveCertificate(String certUrl) throws Exception {
    System.out.println("[ACME] Downloading certificate from: " + certUrl);

    String nonce = fetchNonce();
    String jws = signJws(certUrl, nonce, "", false);

    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(certUrl))
                                     .header("Content-Type", "application/jose+json")
                                     .header("Accept", "application/pem-certificate-chain")
                                     .POST(HttpRequest.BodyPublishers.ofString(jws))
                                     .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new IOException("Certificate download failed (HTTP " + response.statusCode()
                                + "): " + response.body());
    }

    String pemChain = response.body();

    // Parse PEM certificate chain
    List<X509Certificate> certs = parsePemCertificateChain(pemChain);
    if (certs.isEmpty()) {
      throw new IOException("No certificates found in response");
    }

    // Build the keystore
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, null);

    // Store the private key with the certificate chain
    X509Certificate[] certChain = certs.toArray(new X509Certificate[0]);
    keyStore.setKeyEntry("main", lastCertKeyPair.getPrivate(),
                         KEYSTORE_PASSWORD.toCharArray(), certChain);

    // Save to disk
    Path keystorePath = certPath.resolve("acme-cert.p12");
    Files.deleteIfExists(keystorePath);
    try (var fos = Files.newOutputStream(keystorePath)) {
      keyStore.store(fos, KEYSTORE_PASSWORD.toCharArray());
    }

    currentCert = certs.get(0);

    System.out.println("[ACME] Certificate saved to: " + keystorePath);
    System.out.println("[ACME] Subject: " + currentCert.getSubjectX500Principal());
    System.out.println("[ACME] Expires: " + currentCert.getNotAfter().toInstant());
    return keystorePath;
  }

  // ──────────────────────────────────────────────────────────────────────
  //  JWS (JSON Web Signature) Implementation
  // ──────────────────────────────────────────────────────────────────────

  /**
   * Creates a JWS (JSON Web Signature) for ACME requests.
   *
   * @param url     the ACME endpoint URL
   * @param nonce   the anti-replay nonce
   * @param payload the request payload (empty string for POST-as-GET)
   * @param useJwk  true to include JWK in header (for account registration),
   *                false to use kid (for subsequent requests)
   * @return the JWS JSON string
   */
  private String signJws(String url, String nonce, String payload, boolean useJwk)
      throws Exception {
    // Build protected header
    String protectedHeader;
    if (useJwk) {
      String jwk = buildJwk();
      protectedHeader = """
          {"alg":"RS256","nonce":"%s","url":"%s","jwk":%s}""".formatted(nonce, url, jwk);
    } else {
      protectedHeader = """
          {"alg":"RS256","nonce":"%s","url":"%s","kid":"%s"}""".formatted(nonce, url, accountUrl);
    }

    String encodedProtected = base64UrlEncode(protectedHeader.getBytes(StandardCharsets.UTF_8));
    String encodedPayload = payload.isEmpty()
        ? ""
        : base64UrlEncode(payload.getBytes(StandardCharsets.UTF_8));

    // Sign
    String signingInput = encodedProtected + "." + encodedPayload;
    Signature sig = Signature.getInstance("SHA256withRSA");
    sig.initSign(accountKeyPair.getPrivate());
    sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
    byte[] signature = sig.sign();

    String encodedSignature = base64UrlEncode(signature);

    // Build flattened JWS JSON
    return "{\"protected\":\"" + encodedProtected
        + "\",\"payload\":\"" + encodedPayload
        + "\",\"signature\":\"" + encodedSignature + "\"}";
  }

  /**
   * Builds the JWK (JSON Web Key) representation of the account public key.
   */
  private String buildJwk() {
    RSAPublicKey pubKey = (RSAPublicKey) accountKeyPair.getPublic();
    String n = base64UrlEncode(toUnsignedByteArray(pubKey.getModulus()));
    String e = base64UrlEncode(toUnsignedByteArray(pubKey.getPublicExponent()));
    return "{\"e\":\"" + e + "\",\"kty\":\"RSA\",\"n\":\"" + n + "\"}";
  }

  /**
   * Computes the JWK thumbprint (RFC 7638) for the account key.
   * Used in key authorization for HTTP-01 challenges.
   */
  private String computeAccountKeyThumbprint() throws Exception {
    // JWK thumbprint uses lexicographically sorted members: e, kty, n
    String jwk = buildJwk();
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(jwk.getBytes(StandardCharsets.UTF_8));
    return base64UrlEncode(hash);
  }

  // ──────────────────────────────────────────────────────────────────────
  //  CSR (Certificate Signing Request) Builder
  // ──────────────────────────────────────────────────────────────────────

  /**
   * Builds a PKCS#10 Certificate Signing Request (CSR) in DER format.
   *
   * <p>Uses pure Java ASN.1 DER encoding without external libraries.
   */
  private byte[] buildCsr(KeyPair keyPair, String[] domains) throws Exception {
    // Build the subject DN: CN=<primary domain>
    byte[] cnValue = domains[0].getBytes(StandardCharsets.UTF_8);
    byte[] cnOid = new byte[] {0x55, 0x04, 0x03}; // OID 2.5.4.3 (commonName)
    byte[] cnAttrValue = derSequence(
        derSet(
            derSequence(
                derOid(cnOid),
                derUtf8String(cnValue)
            )
        )
    );

    // Build Subject Alternative Names extension for multi-domain support
    byte[] sanExtension = buildSanExtension(domains);

    // Build CertificationRequestInfo
    byte[] version = new byte[] {0x02, 0x01, 0x00}; // INTEGER 0
    byte[] subjectPublicKeyInfo = keyPair.getPublic().getEncoded();

    // extensionRequest attribute (OID 1.2.840.113549.1.9.14)
    byte[] extReqOid = new byte[] {
        0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x09, 0x0e
    };

    byte[] attributes = derContext(0,
                                   derSequence(
                                       derOid(extReqOid),
                                       derSet(
                                           derSequence(sanExtension)
                                       )
                                   )
    );

    byte[] certRequestInfo = derSequence(
        version,
        cnAttrValue,
        subjectPublicKeyInfo,
        attributes
    );

    // Sign the CertificationRequestInfo
    Signature sig = Signature.getInstance("SHA256withRSA");
    sig.initSign(keyPair.getPrivate());
    sig.update(certRequestInfo);
    byte[] signature = sig.sign();

    // SHA256withRSA algorithm identifier
    byte[] sha256WithRsaOid = new byte[] {
        0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b
    };
    byte[] signatureAlgorithm = derSequence(derOid(sha256WithRsaOid), derNull());

    // Build the complete CSR
    return derSequence(
        certRequestInfo,
        signatureAlgorithm,
        derBitString(signature)
    );
  }

  /**
   * Builds a Subject Alternative Names (SAN) extension.
   */
  private byte[] buildSanExtension(String[] domains) {
    // SAN OID: 2.5.29.17
    byte[] sanOid = new byte[] {0x55, 0x1d, 0x11};

    // Build GeneralNames sequence
    List<byte[]> dnsNames = new ArrayList<>();
    for (String domain : domains) {
      byte[] nameBytes = domain.getBytes(StandardCharsets.UTF_8);
      // context [2] for dNSName
      byte[] dnsName = new byte[2 + nameBytes.length];
      dnsName[0] = (byte) 0x82; // context [2] implicit
      dnsName[1] = (byte) nameBytes.length;
      System.arraycopy(nameBytes, 0, dnsName, 2, nameBytes.length);
      dnsNames.add(dnsName);
    }

    byte[] generalNames = derSequence(dnsNames.toArray(new byte[0][]));

    return derSequence(
        derOid(sanOid),
        derOctetString(generalNames)
    );
  }

  // ──────────────────────────────────────────────────────────────────────
  //  ASN.1 DER Encoding Helpers
  // ──────────────────────────────────────────────────────────────────────

  private byte[] derSequence(byte[]... elements) {
    return derConstructed((byte) 0x30, elements);
  }

  private byte[] derSet(byte[]... elements) {
    return derConstructed((byte) 0x31, elements);
  }

  private byte[] derContext(int tag, byte[]... elements) {
    return derConstructed((byte) (0xa0 | tag), elements);
  }

  private byte[] derConstructed(byte tag, byte[]... elements) {
    int totalLen = 0;
    for (byte[] e : elements) {
      totalLen += e.length;
    }
    byte[] lenBytes = derLength(totalLen);
    byte[] result = new byte[1 + lenBytes.length + totalLen];
    result[0] = tag;
    System.arraycopy(lenBytes, 0, result, 1, lenBytes.length);
    int offset = 1 + lenBytes.length;
    for (byte[] e : elements) {
      System.arraycopy(e, 0, result, offset, e.length);
      offset += e.length;
    }
    return result;
  }

  private byte[] derOid(byte[] oidBytes) {
    byte[] result = new byte[2 + oidBytes.length];
    result[0] = 0x06;
    result[1] = (byte) oidBytes.length;
    System.arraycopy(oidBytes, 0, result, 2, oidBytes.length);
    return result;
  }

  private byte[] derUtf8String(byte[] value) {
    byte[] result = new byte[2 + value.length];
    result[0] = 0x0c;
    result[1] = (byte) value.length;
    System.arraycopy(value, 0, result, 2, value.length);
    return result;
  }

  private byte[] derOctetString(byte[] value) {
    byte[] lenBytes = derLength(value.length);
    byte[] result = new byte[1 + lenBytes.length + value.length];
    result[0] = 0x04;
    System.arraycopy(lenBytes, 0, result, 1, lenBytes.length);
    System.arraycopy(value, 0, result, 1 + lenBytes.length, value.length);
    return result;
  }

  private byte[] derBitString(byte[] value) {
    byte[] lenBytes = derLength(value.length + 1);
    byte[] result = new byte[1 + lenBytes.length + 1 + value.length];
    result[0] = 0x03;
    System.arraycopy(lenBytes, 0, result, 1, lenBytes.length);
    result[1 + lenBytes.length] = 0x00; // no unused bits
    System.arraycopy(value, 0, result, 2 + lenBytes.length, value.length);
    return result;
  }

  private byte[] derNull() {
    return new byte[] {0x05, 0x00};
  }

  private byte[] derLength(int length) {
    if (length < 128) {
      return new byte[] {(byte) length};
    } else if (length < 256) {
      return new byte[] {(byte) 0x81, (byte) length};
    } else if (length < 65536) {
      return new byte[] {(byte) 0x82, (byte) (length >> 8), (byte) length};
    } else {
      return new byte[] {(byte) 0x83, (byte) (length >> 16), (byte) (length >> 8), (byte) length};
    }
  }

  // ──────────────────────────────────────────────────────────────────────
  //  PEM Parsing
  // ──────────────────────────────────────────────────────────────────────

  /**
   * Parses a PEM certificate chain into a list of X509Certificate objects.
   */
  private List<X509Certificate> parsePemCertificateChain(String pem) throws Exception {
    List<X509Certificate> certs = new ArrayList<>();
    CertificateFactory cf = CertificateFactory.getInstance("X.509");

    String BEGIN = "-----BEGIN CERTIFICATE-----";
    String END = "-----END CERTIFICATE-----";

    int start = 0;
    while ((start = pem.indexOf(BEGIN, start)) >= 0) {
      int end = pem.indexOf(END, start);
      if (end < 0) {
        break;
      }
      end += END.length();

      String certPem = pem.substring(start, end);
      // Extract base64 content
      String base64 = certPem
          .replace(BEGIN, "")
          .replace(END, "")
          .replaceAll("\\s+", "");

      byte[] derBytes = Base64.getDecoder().decode(base64);
      X509Certificate cert = (X509Certificate) cf.generateCertificate(
          new ByteArrayInputStream(derBytes));
      certs.add(cert);

      start = end;
    }

    return certs;
  }

  // ──────────────────────────────────────────────────────────────────────
  //  Utility Methods
  // ──────────────────────────────────────────────────────────────────────

  /**
   * Base64 URL-safe encoding without padding (as required by ACME/JWS).
   */
  private String base64UrlEncode(byte[] data) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
  }

  /**
   * Converts a BigInteger to an unsigned byte array (no leading zero byte for sign).
   */
  private byte[] toUnsignedByteArray(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes[0] == 0 && bytes.length > 1) {
      byte[] trimmed = new byte[bytes.length - 1];
      System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
      return trimmed;
    }
    return bytes;
  }

  /**
   * Simple JSON string value extractor.
   * Extracts the value of a key like "key":"value" from a JSON string.
   */
  private String extractJsonString(String json, String key) {
    String searchKey = "\"" + key + "\"";
    int keyIdx = json.indexOf(searchKey);
    if (keyIdx < 0) {
      return null;
    }

    int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
    if (colonIdx < 0) {
      return null;
    }

    // Skip whitespace after colon
    int valueStart = colonIdx + 1;
    while (valueStart < json.length() && json.charAt(valueStart) == ' ') {
      valueStart++;
    }

    if (valueStart >= json.length()) {
      return null;
    }

    if (json.charAt(valueStart) == '"') {
      // String value
      int valueEnd = json.indexOf('"', valueStart + 1);
      if (valueEnd < 0) {
        return null;
      }
      return json.substring(valueStart + 1, valueEnd);
    }

    return null;
  }

  /**
   * Extracts a JSON array of strings from a JSON object.
   */
  private List<String> extractJsonArray(String json, String key) {
    List<String> result = new ArrayList<>();
    String searchKey = "\"" + key + "\"";
    int keyIdx = json.indexOf(searchKey);
    if (keyIdx < 0) {
      return result;
    }

    int bracketStart = json.indexOf('[', keyIdx);
    if (bracketStart < 0) {
      return result;
    }

    int bracketEnd = json.indexOf(']', bracketStart);
    if (bracketEnd < 0) {
      return result;
    }

    String arrayContent = json.substring(bracketStart + 1, bracketEnd);
    // Extract quoted strings
    int pos = 0;
    while (pos < arrayContent.length()) {
      int quoteStart = arrayContent.indexOf('"', pos);
      if (quoteStart < 0) {
        break;
      }
      int quoteEnd = arrayContent.indexOf('"', quoteStart + 1);
      if (quoteEnd < 0) {
        break;
      }
      result.add(arrayContent.substring(quoteStart + 1, quoteEnd));
      pos = quoteEnd + 1;
    }

    return result;
  }

  /**
   * Gets the current certificate.
   */
  public X509Certificate getCurrentCertificate() {
    return currentCert;
  }

  /**
   * Gets the certificate expiration date.
   */
  public Instant getCertificateExpiry() {
    return currentCert != null ? currentCert.getNotAfter().toInstant() : null;
  }

  /**
   * Gets days until certificate expiration.
   */
  public long getDaysUntilExpiry() {
    Instant expiry = getCertificateExpiry();
    if (expiry == null) {
      return 0;
    }
    return Duration.between(Instant.now(), expiry).toDays();
  }

  /**
   * HTTP-01 challenge handler for integration with the web server.
   *
   * <p>The server's HTTP listener checks incoming requests for the ACME challenge
   * path prefix ({@code /.well-known/acme-challenge/}) and serves the key
   * authorization string registered here by the ACME client during certificate
   * issuance.
   */
  public static class ChallengeHandler {
    private final ConcurrentHashMap<String, String> challenges = new ConcurrentHashMap<>();

    /**
     * Checks if a path is an ACME challenge.
     */
    public static boolean isAcmeChallenge(String path) {
      return path != null && path.startsWith("/.well-known/acme-challenge/");
    }

    /**
     * Extracts token from ACME challenge path.
     */
    public static String extractToken(String path) {
      if (isAcmeChallenge(path)) {
        return path.substring("/.well-known/acme-challenge/".length());
      }
      return null;
    }

    /**
     * Adds a challenge response.
     */
    public void addChallenge(String token, String keyAuthorization) {
      challenges.put(token, keyAuthorization);
    }

    /**
     * Gets a challenge response.
     */
    public String getChallenge(String token) {
      return challenges.get(token);
    }

    /**
     * Removes a challenge.
     */
    public void removeChallenge(String token) {
      challenges.remove(token);
    }
  }
}
