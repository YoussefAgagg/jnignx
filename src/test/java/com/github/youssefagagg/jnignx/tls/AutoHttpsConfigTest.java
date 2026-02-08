package com.github.youssefagagg.jnignx.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.youssefagagg.jnignx.config.ConfigLoader;
import com.github.youssefagagg.jnignx.config.ServerConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for auto-HTTPS configuration parsing.
 */
class AutoHttpsConfigTest {

  @Test
  void testAutoHttpsConfigParsing() {
    String json = """
        {
          "routes": {
            "/": ["http://localhost:3000"]
          },
          "autoHttps": {
            "enabled": true,
            "email": "admin@example.com",
            "domains": ["example.com", "www.example.com"],
            "staging": true,
            "certDir": "/tmp/certs",
            "httpsPort": 8443,
            "httpToHttpsRedirect": true,
            "allowedDomains": ["example.com", "*.example.com"]
          }
        }
        """;

    ServerConfig config = ConfigLoader.parseServerConfig(json);

    assertTrue(config.autoHttpsEnabled());
    assertEquals("admin@example.com", config.acmeEmail());
    assertEquals(List.of("example.com", "www.example.com"), config.acmeDomains());
    assertTrue(config.acmeStaging());
    assertEquals("/tmp/certs", config.acmeCertDir());
    assertEquals(8443, config.httpsPort());
    assertTrue(config.httpToHttpsRedirect());
    assertEquals(List.of("example.com", "*.example.com"), config.allowedDomains());
  }

  @Test
  void testAutoHttpsDisabledByDefault() {
    String json = """
        {
          "routes": {
            "/": ["http://localhost:3000"]
          }
        }
        """;

    ServerConfig config = ConfigLoader.parseServerConfig(json);

    assertFalse(config.autoHttpsEnabled());
    assertEquals("", config.acmeEmail());
    assertTrue(config.acmeDomains().isEmpty());
    assertFalse(config.acmeStaging());
    assertEquals("certs", config.acmeCertDir());
    assertEquals(443, config.httpsPort());
    assertTrue(config.httpToHttpsRedirect());
    assertTrue(config.allowedDomains().isEmpty());
  }

  @Test
  void testAutoHttpsMinimalConfig() {
    String json = """
        {
          "routes": {
            "/": ["http://localhost:3000"]
          },
          "autoHttps": {
            "enabled": true,
            "email": "admin@example.com"
          }
        }
        """;

    ServerConfig config = ConfigLoader.parseServerConfig(json);

    assertTrue(config.autoHttpsEnabled());
    assertEquals("admin@example.com", config.acmeEmail());
    assertTrue(config.acmeDomains().isEmpty());
    assertFalse(config.acmeStaging());
    assertEquals("certs", config.acmeCertDir());
    assertEquals(443, config.httpsPort());
    assertTrue(config.httpToHttpsRedirect());
  }

  @Test
  void testAutoHttpsWithRedirectDisabled() {
    String json = """
        {
          "routes": {
            "/": ["http://localhost:3000"]
          },
          "autoHttps": {
            "enabled": true,
            "email": "admin@example.com",
            "httpToHttpsRedirect": false
          }
        }
        """;

    ServerConfig config = ConfigLoader.parseServerConfig(json);

    assertTrue(config.autoHttpsEnabled());
    assertFalse(config.httpToHttpsRedirect());
  }

  @Test
  void testServerConfigBuilderAutoHttps() {
    ServerConfig config = ServerConfig.builder()
                                      .autoHttps(true, "test@example.com",
                                                 List.of("example.com"), true, "/tmp/certs")
                                      .httpsPort(8443)
                                      .httpToHttpsRedirect(false)
                                      .allowedDomains(List.of("example.com", "*.example.com"))
                                      .build();

    assertTrue(config.autoHttpsEnabled());
    assertEquals("test@example.com", config.acmeEmail());
    assertEquals(List.of("example.com"), config.acmeDomains());
    assertTrue(config.acmeStaging());
    assertEquals("/tmp/certs", config.acmeCertDir());
    assertEquals(8443, config.httpsPort());
    assertFalse(config.httpToHttpsRedirect());
    assertEquals(List.of("example.com", "*.example.com"), config.allowedDomains());
  }

  @Test
  void testServerConfigBuilderAutoHttpsNullSafety() {
    ServerConfig config = ServerConfig.builder()
                                      .autoHttps(true, null, null, false, null)
                                      .allowedDomains(null)
                                      .build();

    assertTrue(config.autoHttpsEnabled());
    assertEquals("", config.acmeEmail());
    assertTrue(config.acmeDomains().isEmpty());
    assertEquals("certs", config.acmeCertDir());
    assertTrue(config.allowedDomains().isEmpty());
  }

  @Test
  void testAcmeChallengeHandlerPathDetection() {
    assertTrue(AcmeClient.ChallengeHandler.isAcmeChallenge(
        "/.well-known/acme-challenge/abc123"));
    assertFalse(AcmeClient.ChallengeHandler.isAcmeChallenge("/some/other/path"));
    assertFalse(AcmeClient.ChallengeHandler.isAcmeChallenge(null));
  }

  @Test
  void testAcmeChallengeHandlerTokenExtraction() {
    assertEquals("abc123", AcmeClient.ChallengeHandler.extractToken(
        "/.well-known/acme-challenge/abc123"));
    assertEquals("token-with-dashes", AcmeClient.ChallengeHandler.extractToken(
        "/.well-known/acme-challenge/token-with-dashes"));
    assertNull(AcmeClient.ChallengeHandler.extractToken("/other/path"));
  }

  @Test
  void testAcmeChallengeHandlerAddAndGet() {
    AcmeClient.ChallengeHandler handler = new AcmeClient.ChallengeHandler();

    assertNull(handler.getChallenge("nonexistent"));

    handler.addChallenge("token1", "auth1");
    handler.addChallenge("token2", "auth2");

    assertEquals("auth1", handler.getChallenge("token1"));
    assertEquals("auth2", handler.getChallenge("token2"));

    handler.removeChallenge("token1");
    assertNull(handler.getChallenge("token1"));
    assertEquals("auth2", handler.getChallenge("token2"));
  }
}
