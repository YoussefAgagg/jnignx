package com.github.youssefagagg.jnignx.tls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages TLS certificates with automatic provisioning and renewal.
 *
 * <p>Provides Caddy-style automatic HTTPS:
 * <ul>
 *   <li>On-demand certificate provisioning via ACME (Let's Encrypt)</li>
 *   <li>Per-domain certificate caching</li>
 *   <li>Automatic renewal before expiration (30 days before)</li>
 *   <li>Domain allowlist for on-demand issuance</li>
 *   <li>Thread-safe concurrent access</li>
 * </ul>
 *
 * <p>Flow for a new domain:
 * <pre>
 * 1. TLS ClientHello arrives with SNI hostname
 * 2. CertificateManager checks cache for existing cert
 * 3. If missing and domain is allowed → trigger ACME issuance
 * 4. Cache the new cert and return it for the handshake
 * </pre>
 */
public final class CertificateManager {

  private static final Duration RENEWAL_THRESHOLD = Duration.ofDays(30);
  private static final Duration RENEWAL_CHECK_INTERVAL = Duration.ofHours(12);

  private final String acmeEmail;
  private final boolean staging;
  private final Path certDir;
  private final Set<String> allowedDomains;
  private final boolean allowAllDomains;

  // Domain → cached KeyStore (containing cert + key)
  private final ConcurrentHashMap<String, KeyStore> certCache = new ConcurrentHashMap<>();

  // Domain → certificate expiry
  private final ConcurrentHashMap<String, Instant> certExpiry = new ConcurrentHashMap<>();

  // Per-domain lock to avoid duplicate issuance
  private final ConcurrentHashMap<String, ReentrantLock> domainLocks = new ConcurrentHashMap<>();

  // Shared ACME challenge handler for HTTP-01 challenges
  private final AcmeClient.ChallengeHandler challengeHandler = new AcmeClient.ChallengeHandler();

  private final ScheduledExecutorService renewalScheduler;

  /**
   * Creates a CertificateManager.
   *
   * @param acmeEmail      contact email for Let's Encrypt notifications
   * @param staging        use Let's Encrypt staging environment
   * @param certDir        directory for storing certificates
   * @param allowedDomains domains allowed for on-demand issuance (empty = allow all configured)
   */
  public CertificateManager(String acmeEmail, boolean staging, String certDir,
                            List<String> allowedDomains) {
    this.acmeEmail = acmeEmail;
    this.staging = staging;
    this.certDir = Path.of(certDir);
    this.allowedDomains = ConcurrentHashMap.newKeySet();
    if (allowedDomains != null) {
      allowedDomains.forEach(d -> this.allowedDomains.add(d.toLowerCase()));
    }
    this.allowAllDomains = this.allowedDomains.isEmpty();

    this.renewalScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "cert-renewal");
      t.setDaemon(true);
      return t;
    });

    try {
      Files.createDirectories(this.certDir);
    } catch (IOException e) {
      throw new RuntimeException("Failed to create certificate directory: " + certDir, e);
    }

    // Load any cached certificates from disk
    loadCachedCertificates();
  }

  /**
   * Starts the automatic certificate renewal scheduler.
   */
  public void startRenewalScheduler() {
    System.out.println("[CertManager] Starting automatic renewal scheduler");

    renewalScheduler.scheduleAtFixedRate(() -> {
      try {
        renewExpiringCertificates();
      } catch (Exception e) {
        System.err.println("[CertManager] Renewal check failed: " + e.getMessage());
      }
    }, RENEWAL_CHECK_INTERVAL.toHours(), RENEWAL_CHECK_INTERVAL.toHours(), TimeUnit.HOURS);
  }

  /**
   * Stops the renewal scheduler.
   */
  public void stop() {
    renewalScheduler.shutdown();
  }

  /**
   * Gets a KeyStore for the given domain, provisioning a certificate on-demand if needed.
   *
   * @param domain the domain name (from SNI)
   * @return KeyStore containing the domain's certificate and private key, or null if not allowed
   */
  public KeyStore getCertificate(String domain) {
    if (domain == null || domain.isBlank()) {
      return null;
    }

    String normalizedDomain = domain.toLowerCase().trim();

    // Check cache first
    KeyStore cached = certCache.get(normalizedDomain);
    if (cached != null && !isExpiringSoon(normalizedDomain)) {
      return cached;
    }

    // Check if domain is allowed
    if (!isDomainAllowed(normalizedDomain)) {
      System.out.println("[CertManager] Domain not allowed: " + normalizedDomain);
      return null;
    }

    // On-demand provisioning with per-domain lock
    ReentrantLock lock = domainLocks.computeIfAbsent(normalizedDomain, k -> new ReentrantLock());
    lock.lock();
    try {
      // Double-check after acquiring lock
      cached = certCache.get(normalizedDomain);
      if (cached != null && !isExpiringSoon(normalizedDomain)) {
        return cached;
      }

      System.out.println("[CertManager] Provisioning certificate for: " + normalizedDomain);
      return provisionCertificate(normalizedDomain);
    } catch (Exception e) {
      System.err.println("[CertManager] Failed to provision cert for " + normalizedDomain
                             + ": " + e.getMessage());
      // Return expired cert if available (better than nothing)
      return certCache.get(normalizedDomain);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Checks if a certificate exists in cache for the given domain.
   *
   * @param domain the domain to check
   * @return true if a cached certificate exists
   */
  public boolean hasCertificate(String domain) {
    return domain != null && certCache.containsKey(domain.toLowerCase().trim());
  }

  /**
   * Gets the shared ACME challenge handler for HTTP-01 challenge responses.
   *
   * @return the challenge handler
   */
  public AcmeClient.ChallengeHandler getChallengeHandler() {
    return challengeHandler;
  }

  /**
   * Checks if a domain is allowed for certificate provisioning.
   *
   * @param domain the domain to check
   * @return true if the domain is allowed
   */
  public boolean isDomainAllowed(String domain) {
    if (allowAllDomains) {
      return true;
    }
    String normalized = domain.toLowerCase().trim();
    // Check exact match
    if (allowedDomains.contains(normalized)) {
      return true;
    }
    // Check wildcard match (e.g., "*.example.com" matches "sub.example.com")
    int dot = normalized.indexOf('.');
    if (dot > 0) {
      String wildcard = "*" + normalized.substring(dot);
      return allowedDomains.contains(wildcard);
    }
    return false;
  }

  /**
   * Adds a domain to the allowed list dynamically.
   *
   * @param domain the domain to allow
   */
  public void addAllowedDomain(String domain) {
    allowedDomains.add(domain.toLowerCase().trim());
  }

  /**
   * Gets the number of cached certificates.
   */
  public int getCachedCertCount() {
    return certCache.size();
  }

  /**
   * Gets certificate expiry for a domain.
   */
  public Instant getCertificateExpiry(String domain) {
    return certExpiry.get(domain.toLowerCase().trim());
  }

  /**
   * Provisions a certificate for the given domain using ACME.
   */
  private KeyStore provisionCertificate(String domain) throws Exception {
    AcmeClient acme = new AcmeClient(acmeEmail, staging, domain);

    // Set the challenge handler so the server can respond to HTTP-01 challenges
    // during certificate issuance via the ACME HTTP-01 protocol
    acme.setChallengeHandler(challengeHandler);

    Path keystorePath = acme.obtainCertificate();

    // Load the keystore
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (var fis = Files.newInputStream(keystorePath)) {
      keyStore.load(fis, "changeit".toCharArray());
    }

    // Cache it
    certCache.put(domain, keyStore);

    // Track expiry
    X509Certificate cert = acme.getCurrentCertificate();
    if (cert != null) {
      certExpiry.put(domain, cert.getNotAfter().toInstant());
    }

    // Copy to domain-specific file for persistence
    Path domainKeystore = certDir.resolve(domain + ".p12");
    Files.copy(keystorePath, domainKeystore,
               java.nio.file.StandardCopyOption.REPLACE_EXISTING);

    System.out.println("[CertManager] Certificate provisioned for: " + domain);
    return keyStore;
  }

  /**
   * Checks if a certificate is expiring soon (within renewal threshold).
   */
  private boolean isExpiringSoon(String domain) {
    Instant expiry = certExpiry.get(domain);
    if (expiry == null) {
      return false; // No expiry info, assume OK
    }
    return Instant.now().isAfter(expiry.minus(RENEWAL_THRESHOLD));
  }

  /**
   * Renews certificates that are expiring soon.
   */
  private void renewExpiringCertificates() {
    for (var entry : certExpiry.entrySet()) {
      String domain = entry.getKey();
      if (isExpiringSoon(domain)) {
        System.out.println("[CertManager] Renewing certificate for: " + domain);
        ReentrantLock lock = domainLocks.computeIfAbsent(domain, k -> new ReentrantLock());
        if (lock.tryLock()) {
          try {
            provisionCertificate(domain);
            System.out.println("[CertManager] Certificate renewed for: " + domain);
          } catch (Exception e) {
            System.err.println("[CertManager] Renewal failed for " + domain
                                   + ": " + e.getMessage());
          } finally {
            lock.unlock();
          }
        }
      }
    }
  }

  /**
   * Loads previously cached certificates from disk.
   */
  private void loadCachedCertificates() {
    try {
      if (!Files.isDirectory(certDir)) {
        return;
      }

      try (var stream = Files.list(certDir)) {
        stream.filter(p -> p.toString().endsWith(".p12"))
              .forEach(this::loadCertificateFromDisk);
      }
    } catch (IOException e) {
      System.err.println("[CertManager] Failed to load cached certificates: " + e.getMessage());
    }
  }

  /**
   * Loads a single certificate from disk into cache.
   */
  private void loadCertificateFromDisk(Path keystorePath) {
    try {
      String filename = keystorePath.getFileName().toString();
      String domain = filename.substring(0, filename.length() - 4); // Remove .p12

      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (var fis = Files.newInputStream(keystorePath)) {
        keyStore.load(fis, "changeit".toCharArray());
      }

      // Extract certificate for expiry tracking
      var aliases = keyStore.aliases();
      while (aliases.hasMoreElements()) {
        String alias = aliases.nextElement();
        var cert = keyStore.getCertificate(alias);
        if (cert instanceof X509Certificate x509) {
          Instant expiry = x509.getNotAfter().toInstant();
          if (Instant.now().isAfter(expiry)) {
            System.out.println("[CertManager] Skipping expired cert for: " + domain);
            return; // Skip expired certificates
          }
          certExpiry.put(domain, expiry);
          break;
        }
      }

      certCache.put(domain, keyStore);
      System.out.println("[CertManager] Loaded cached cert for: " + domain);
    } catch (Exception e) {
      System.err.println("[CertManager] Failed to load cert from " + keystorePath
                             + ": " + e.getMessage());
    }
  }
}
