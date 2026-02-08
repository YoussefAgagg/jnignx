package com.github.youssefagagg.jnignx.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import javax.security.auth.x500.X500Principal;
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
    // Generate a self-signed cert using pure Java (no keytool dependency)
    Path ksPath = tempDir.resolve("testdomain.com.p12");
    createSelfSignedKeystore(ksPath, "testdomain.com");

    assertTrue(Files.exists(ksPath), "Keystore file should exist");

    // Create CertificateManager which should load the cached cert
    CertificateManager cm = new CertificateManager(
        "test@example.com", true, tempDir.toString(), List.of());

    assertTrue(cm.hasCertificate("testdomain.com"));
    assertEquals(1, cm.getCachedCertCount());
    assertNotNull(cm.getCertificateExpiry("testdomain.com"));
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

  @Test
  void testAcmeClientConstructors() {
    // Verify constructors don't throw
    AcmeClient production = new AcmeClient("test@example.com", "example.com");
    assertNotNull(production);

    AcmeClient staging = new AcmeClient("test@example.com", true, "example.com", "www.example.com");
    assertNotNull(staging);
  }

  @Test
  void testAcmeClientChallengeHandlerIntegration() {
    AcmeClient client = new AcmeClient("test@example.com", true, "example.com");
    AcmeClient.ChallengeHandler handler = new AcmeClient.ChallengeHandler();

    // Should not throw
    client.setChallengeHandler(handler);

    // Verify handler works independently
    handler.addChallenge("token123", "auth-value");
    assertEquals("auth-value", handler.getChallenge("token123"));
  }

  @Test
  void testAcmeClientCertificateExpiryWithNoCert() {
    AcmeClient client = new AcmeClient("test@example.com", true, "example.com");

    assertNull(client.getCurrentCertificate());
    assertNull(client.getCertificateExpiry());
    assertEquals(0, client.getDaysUntilExpiry());
  }

  @Test
  void testAcmeClientStopAutoRenewal() {
    AcmeClient client = new AcmeClient("test@example.com", true, "example.com");
    // Should not throw even without starting
    client.stopAutoRenewal();
  }

  /**
   * Creates a self-signed PKCS12 keystore using pure Java APIs (no keytool).
   */
  private void createSelfSignedKeystore(Path keystorePath, String cn) throws Exception {
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
    keyGen.initialize(2048);
    KeyPair keyPair = keyGen.generateKeyPair();

    // Build a self-signed X.509 certificate using basic ASN.1 DER encoding
    X509Certificate cert = generateSelfSignedCertificate(keyPair, cn, 365);

    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, null);
    keyStore.setKeyEntry("main", keyPair.getPrivate(), "changeit".toCharArray(),
                         new X509Certificate[] {cert});

    try (var fos = Files.newOutputStream(keystorePath)) {
      keyStore.store(fos, "changeit".toCharArray());
    }
  }

  /**
   * Generates a self-signed X.509 v1 certificate using pure Java.
   * Uses sun.security.x509 internal APIs which are available in the JDK.
   */
  private X509Certificate generateSelfSignedCertificate(KeyPair keyPair, String cn,
                                                        int validityDays) throws Exception {
    // Use Java's built-in CertificateBuilder approach via sun.security.x509
    // This is the standard way to create self-signed certs without external deps
    long now = System.currentTimeMillis();
    Date notBefore = new Date(now);
    Date notAfter = new Date(now + validityDays * 86400000L);

    X500Principal subject = new X500Principal("CN=" + cn);

    // Build self-signed cert using ASN.1 DER
    byte[] certDer = buildSelfSignedCertDer(keyPair, subject, notBefore, notAfter);

    java.security.cert.CertificateFactory cf =
        java.security.cert.CertificateFactory.getInstance("X.509");
    return (X509Certificate) cf.generateCertificate(
        new java.io.ByteArrayInputStream(certDer));
  }

  /**
   * Builds a self-signed X.509 v3 certificate in DER format.
   */
  private byte[] buildSelfSignedCertDer(KeyPair keyPair, X500Principal subject,
                                        Date notBefore, Date notAfter) throws Exception {
    // TBSCertificate
    byte[] version = derExplicit(0, derInteger(BigInteger.valueOf(2))); // v3
    byte[] serialNumber = derInteger(BigInteger.valueOf(System.currentTimeMillis()));
    // SHA256withRSA OID: 1.2.840.113549.1.1.11
    byte[] sha256WithRsaOid = new byte[] {
        0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b
    };
    byte[] signatureAlgorithm = derSequence(derOid(sha256WithRsaOid), derNull());
    byte[] issuer = subject.getEncoded();
    byte[] validity = derSequence(derUtcTime(notBefore), derUtcTime(notAfter));
    byte[] subjectDer = subject.getEncoded();
    byte[] subjectPublicKeyInfo = keyPair.getPublic().getEncoded();

    byte[] tbsCertificate = derSequence(
        version, serialNumber, signatureAlgorithm,
        issuer, validity, subjectDer, subjectPublicKeyInfo
    );

    // Sign
    java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
    sig.initSign(keyPair.getPrivate());
    sig.update(tbsCertificate);
    byte[] signature = sig.sign();

    // Complete certificate
    return derSequence(
        tbsCertificate,
        signatureAlgorithm,
        derBitString(signature)
    );
  }

  // ASN.1 DER encoding helpers for test certificate generation

  private byte[] derSequence(byte[]... elements) {
    return derConstructed((byte) 0x30, elements);
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

  private byte[] derExplicit(int tag, byte[] value) {
    byte[] lenBytes = derLength(value.length);
    byte[] result = new byte[1 + lenBytes.length + value.length];
    result[0] = (byte) (0xa0 | tag);
    System.arraycopy(lenBytes, 0, result, 1, lenBytes.length);
    System.arraycopy(value, 0, result, 1 + lenBytes.length, value.length);
    return result;
  }

  private byte[] derInteger(BigInteger value) {
    byte[] bytes = value.toByteArray();
    byte[] lenBytes = derLength(bytes.length);
    byte[] result = new byte[1 + lenBytes.length + bytes.length];
    result[0] = 0x02;
    System.arraycopy(lenBytes, 0, result, 1, lenBytes.length);
    System.arraycopy(bytes, 0, result, 1 + lenBytes.length, bytes.length);
    return result;
  }

  private byte[] derOid(byte[] oidBytes) {
    byte[] result = new byte[2 + oidBytes.length];
    result[0] = 0x06;
    result[1] = (byte) oidBytes.length;
    System.arraycopy(oidBytes, 0, result, 2, oidBytes.length);
    return result;
  }

  private byte[] derNull() {
    return new byte[] {0x05, 0x00};
  }

  private byte[] derBitString(byte[] value) {
    byte[] lenBytes = derLength(value.length + 1);
    byte[] result = new byte[1 + lenBytes.length + 1 + value.length];
    result[0] = 0x03;
    System.arraycopy(lenBytes, 0, result, 1, lenBytes.length);
    result[1 + lenBytes.length] = 0x00;
    System.arraycopy(value, 0, result, 2 + lenBytes.length, value.length);
    return result;
  }

  @SuppressWarnings("deprecation")
  private byte[] derUtcTime(Date date) {
    // Format: YYMMDDHHMMSSZ
    String time = String.format("%02d%02d%02d%02d%02d%02dZ",
                                date.getYear() % 100, date.getMonth() + 1, date.getDate(),
                                date.getHours(), date.getMinutes(), date.getSeconds());
    byte[] timeBytes = time.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] result = new byte[2 + timeBytes.length];
    result[0] = 0x17;
    result[1] = (byte) timeBytes.length;
    System.arraycopy(timeBytes, 0, result, 2, timeBytes.length);
    return result;
  }

  private byte[] derLength(int length) {
    if (length < 128) {
      return new byte[] {(byte) length};
    } else if (length < 256) {
      return new byte[] {(byte) 0x81, (byte) length};
    } else {
      return new byte[] {(byte) 0x82, (byte) (length >> 8), (byte) length};
    }
  }
}
