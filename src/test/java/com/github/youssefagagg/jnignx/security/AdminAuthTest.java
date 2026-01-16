package com.github.youssefagagg.jnignx.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for AdminAuth authentication and authorization.
 */
class AdminAuthTest {

  private AdminAuth auth;

  @BeforeEach
  void setUp() {
    auth = new AdminAuth();
  }

  @Test
  void testAuthenticationDisabledByDefault() {
    assertFalse(auth.isEnabled());
    assertTrue(auth.authenticate(null, "192.168.1.1"));
  }

  @Test
  void testApiKeyAuthentication() {
    String apiKey = "test-api-key-12345678901234567890";
    auth.setApiKey(apiKey);

    assertTrue(auth.isEnabled());
    assertTrue(auth.authenticate("Bearer " + apiKey, "192.168.1.1"));
    assertFalse(auth.authenticate("Bearer wrong-key", "192.168.1.1"));
    assertFalse(auth.authenticate(null, "192.168.1.1"));
  }

  @Test
  void testBasicAuthentication() {
    auth.addUser("admin", "password123");

    assertTrue(auth.isEnabled());

    // Valid credentials (admin:password123 in base64)
    String validAuth = "Basic YWRtaW46cGFzc3dvcmQxMjM=";
    assertTrue(auth.authenticate(validAuth, "192.168.1.1"));

    // Invalid credentials
    String invalidAuth = "Basic YWRtaW46d3JvbmcxMjM=";
    assertFalse(auth.authenticate(invalidAuth, "192.168.1.1"));
  }

  @Test
  void testIPWhitelisting() {
    auth.whitelistIP("127.0.0.1");
    auth.whitelistIP("192.168.1.100");

    assertTrue(auth.isEnabled());
    assertTrue(auth.authenticate(null, "127.0.0.1"));
    assertTrue(auth.authenticate(null, "192.168.1.100"));
    assertFalse(auth.authenticate(null, "10.0.0.1"));
  }

  @Test
  void testCIDRWhitelisting() {
    auth.whitelistIP("10.0.0.0/8");

    assertTrue(auth.authenticate(null, "10.0.0.1"));
    assertTrue(auth.authenticate(null, "10.255.255.255"));
    assertFalse(auth.authenticate(null, "11.0.0.1"));
  }

  @Test
  void testMultipleAuthMethods() {
    auth.setApiKey("api-key-12345678901234567890");
    auth.addUser("admin", "password123");
    auth.whitelistIP("127.0.0.1");

    // Should accept any valid method
    assertTrue(auth.authenticate("Bearer api-key-12345678901234567890", "192.168.1.1"));
    assertTrue(auth.authenticate("Basic YWRtaW46cGFzc3dvcmQxMjM=", "192.168.1.1"));
    assertTrue(auth.authenticate(null, "127.0.0.1"));
  }

  @Test
  void testApiKeyGeneration() {
    String key1 = AdminAuth.generateApiKey();
    String key2 = AdminAuth.generateApiKey();

    assertNotNull(key1);
    assertNotNull(key2);
    assertNotEquals(key1, key2);
    assertTrue(key1.length() >= 32);
  }

  @Test
  void testShortApiKeyWarning() {
    // Should work but log warning
    auth.setApiKey("short");
    assertTrue(auth.isEnabled());
  }

  @Test
  void testInvalidInputs() {
    assertThrows(IllegalArgumentException.class, () -> auth.setApiKey(""));
    assertThrows(IllegalArgumentException.class, () -> auth.setApiKey(null));
    assertThrows(IllegalArgumentException.class, () -> auth.addUser("", "password"));
    assertThrows(IllegalArgumentException.class, () -> auth.addUser("user", "short"));
    assertThrows(IllegalArgumentException.class, () -> auth.whitelistIP(""));
  }

  @Test
  void testAuthChallenge() {
    auth.setApiKey("test-key-12345678901234567890");
    assertEquals("Bearer realm=\"Admin API\"", auth.getAuthChallenge());

    AdminAuth basicAuth = new AdminAuth();
    basicAuth.addUser("admin", "password123");
    assertEquals("Basic realm=\"Admin API\"", basicAuth.getAuthChallenge());
  }

  @Test
  void testPasswordHashing() {
    auth.addUser("user1", "password123");
    auth.addUser("user2", "password123");

    // Same password should work for both users
    assertTrue(
        auth.authenticate("Basic dXNlcjE6cGFzc3dvcmQxMjM=", "192.168.1.1")); // user1:password123
    assertTrue(
        auth.authenticate("Basic dXNlcjI6cGFzc3dvcmQxMjM=", "192.168.1.1")); // user2:password123
  }
}
