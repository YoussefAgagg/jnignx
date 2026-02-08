package com.github.youssefagagg.jnignx.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for CertificateManager.
 */
class CertificateManagerTest {

  @TempDir
  Path tempDir;

  @Test
  void testDomainAllowedWithExplicitList() {
    CertificateManager cm = new CertificateManager(
        "test@example.com", true, tempDir.toString(),
        List.of("example.com", "app.example.com"));

    assertTrue(cm.isDomainAllowed("example.com"));
    assertTrue(cm.isDomainAllowed("app.example.com"));
    assertTrue(cm.isDomainAllowed("EXAMPLE.COM")); // Case-insensitive
    assertFalse(cm.isDomainAllowed("other.com"));
    assertFalse(cm.isDomainAllowed("sub.other.com"));
  }

  @Test
  void testDomainAllowedWithEmptyList() {
    CertificateManager cm = new CertificateManager(
        "test@example.com", true, tempDir.toString(), List.of());

    assertTrue(cm.isDomainAllowed("anything.com"));
    assertTrue(cm.isDomainAllowed("example.com"));
  }

  @Test
  void testDomainAllowedWithNullList() {
    CertificateManager cm = new CertificateManager(
        "test@example.com", true, tempDir.toString(), null);

    assertTrue(cm.isDomainAllowed("anything.com"));
  }

  @Test
  void testWildcardDomainMatching() {
    CertificateManager cm = new CertificateManager(
        "test@example.com", true, tempDir.toString(),
        List.of("*.example.com"));

    assertTrue(cm.isDomainAllowed("app.example.com"));
    assertTrue(cm.isDomainAllowed("api.example.com"));
    assertFalse(cm.isDomainAllowed("example.com")); // Wildcard doesn't match bare domain
    assertFalse(cm.isDomainAllowed("other.com"));
  }

  @Test
  void testAddAllowedDomain() {
    CertificateManager cm = new CertificateManager(
        "test@example.com", true, tempDir.toString(),
        List.of("example.com"));

    assertFalse(cm.isDomainAllowed("new.com"));
    cm.addAllowedDomain("new.com");
    assertTrue(cm.isDomainAllowed("new.com"));
  }

  @Test
  void testHasCertificateReturnsFalseForUnknownDomain() {
    CertificateManager cm = new CertificateManager(
        "test@example.com", true, tempDir.toString(), List.of());

    assertFalse(cm.hasCertificate("unknown.com"));
  }

  @Test
  void testGetCertificateReturnsNullForDisallowedDomain() {
    CertificateManager cm = new CertificateManager(
        "test@example.com", true, tempDir.toString(),
        List.of("allowed.com"));

    KeyStore result = cm.getCertificate("disallowed.com");
    assertNull(result);
  }

  @Test
  void testGetCertificateReturnsNullForNullDomain() {
    CertificateManager cm = new CertificateManager(
        "test@example.com", true, tempDir.toString(), List.of());

    assertNull(cm.getCertificate(null));
    assertNull(cm.getCertificate(""));
    assertNull(cm.getCertificate("  "));
  }

  @Test
  void testGetCachedCertCount() {
    CertificateManager cm = new CertificateManager(
        "test@example.com", true, tempDir.toString(), List.of());

    assertEquals(0, cm.getCachedCertCount());
  }

  @Test
  void testChallengeHandler() {
    CertificateManager cm = new CertificateManager(
        "test@example.com", true, tempDir.toString(), List.of());

    AcmeClient.ChallengeHandler handler = cm.getChallengeHandler();
    assertNotNull(handler);

    // Add and retrieve challenge
    handler.addChallenge("test-token", "test-key-auth");
    assertEquals("test-key-auth", handler.getChallenge("test-token"));

    // Remove challenge
    handler.removeChallenge("test-token");
    assertNull(handler.getChallenge("test-token"));
  }

  @Test
  void testLoadsCachedCertificatesFromDisk() throws Exception {
    // Generate a self-signed cert using keytool
    Path ksPath = tempDir.resolve("testdomain.com.p12");
    ProcessBuilder pb = new ProcessBuilder(
        "keytool", "-genkeypair",
        "-alias", "main",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "365",
        "-storetype", "PKCS12",
        "-keystore", ksPath.toString(),
        "-storepass", "changeit",
        "-keypass", "changeit",
        "-dname", "CN=testdomain.com"
    );
    pb.redirectErrorStream(true);
    Process process = pb.start();
    int exitCode = process.waitFor();
    assertEquals(0, exitCode, "keytool should succeed");
    assertTrue(Files.exists(ksPath), "Keystore file should exist");

    // Create CertificateManager which should load the cached cert
    CertificateManager cm = new CertificateManager(
        "test@example.com", true, tempDir.toString(), List.of());

    assertTrue(cm.hasCertificate("testdomain.com"));
    assertEquals(1, cm.getCachedCertCount());
    assertNotNull(cm.getCertificateExpiry("testdomain.com"));
  }

  @Test
  void testObtainCertificateDoesNotThrowBase64Error() throws Exception {
    // Reproduce the bug: obtainCertificate previously failed with
    // "Illegal base64 character 2e" because the placeholder PEM contained '...'
    AcmeClient acme = new AcmeClient("test@example.com", true, "test.youssefagagg.dev");
    Path keystorePath = acme.obtainCertificate();

    assertNotNull(keystorePath, "Keystore path should not be null");
    assertTrue(Files.exists(keystorePath), "Keystore file should exist");

    // Verify the keystore can be loaded
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (var fis = Files.newInputStream(keystorePath)) {
      ks.load(fis, "changeit".toCharArray());
    }
    assertTrue(ks.aliases().hasMoreElements(), "KeyStore should contain at least one entry");

    // Verify the certificate is valid
    assertNotNull(acme.getCurrentCertificate(), "Current certificate should not be null");
    assertTrue(acme.getDaysUntilExpiry() > 0, "Certificate should not be expired");

    // Cleanup
    Files.deleteIfExists(keystorePath);
  }

  @Test
  void testGetCertificateProvisionsCertSuccessfully() {
    CertificateManager cm = new CertificateManager(
        "test@example.com", true, tempDir.toString(), List.of());

    // This should trigger on-demand provisioning without the base64 error
    KeyStore ks = cm.getCertificate("test.example.com");
    assertNotNull(ks, "KeyStore should be provisioned on-demand for allowed domain");
    assertTrue(cm.hasCertificate("test.example.com"));
    assertEquals(1, cm.getCachedCertCount());
    assertNotNull(cm.getCertificateExpiry("test.example.com"));
  }

  @Test
  void testStopDoesNotThrow() {
    CertificateManager cm = new CertificateManager(
        "test@example.com", true, tempDir.toString(), List.of());

    cm.startRenewalScheduler();
    cm.stop();
  }

  @Test
  void testCertificateExpiryReturnsNullForUnknown() {
    CertificateManager cm = new CertificateManager(
        "test@example.com", true, tempDir.toString(), List.of());

    assertNull(cm.getCertificateExpiry("unknown.com"));
  }
}
