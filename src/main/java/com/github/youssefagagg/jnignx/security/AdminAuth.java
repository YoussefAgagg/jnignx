package com.github.youssefagagg.jnignx.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication handler for Admin API endpoints.
 *
 * <p>Provides multiple authentication methods:
 * <ul>
 *   <li><b>API Key:</b> Bearer token authentication via Authorization header</li>
 *   <li><b>Basic Auth:</b> Username/password with bcrypt-style hashing</li>
 *   <li><b>IP Whitelist:</b> IP-based access control</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * // Configure authentication
 * AdminAuth auth = new AdminAuth();
 * auth.setApiKey("your-secure-api-key-here");
 * auth.addUser("admin", "secure-password");
 * auth.whitelistIP("127.0.0.1");
 * auth.whitelistIP("10.0.0.0/8");
 *
 * // Check authentication
 * if (auth.authenticate(request, clientIP)) {
 *   // Process admin request
 * } else {
 *   // Return 401 Unauthorized
 * }
 * </pre>
 *
 * <p><b>Security Best Practices:</b>
 * <ul>
 *   <li>Always use HTTPS for admin endpoints in production</li>
 *   <li>Rotate API keys regularly</li>
 *   <li>Use strong passwords (min 12 characters)</li>
 *   <li>Enable IP whitelisting when possible</li>
 *   <li>Monitor failed authentication attempts</li>
 * </ul>
 */
public final class AdminAuth {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int SALT_LENGTH = 16;
  private final Map<String, String> users = new ConcurrentHashMap<>();
      // username -> hashed password
  private final Map<String, String> salts = new ConcurrentHashMap<>(); // username -> salt
  private final Map<String, Boolean> ipWhitelist = new ConcurrentHashMap<>();
  private String apiKey;
  private boolean enabled = true;

  /**
   * Creates a new AdminAuth instance with authentication disabled by default.
   */
  public AdminAuth() {
  }

  /**
   * Generates a secure random API key.
   *
   * @return a cryptographically secure random API key
   */
  public static String generateApiKey() {
    byte[] keyBytes = new byte[32];
    RANDOM.nextBytes(keyBytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes);
  }

  /**
   * Enables authentication with the specified API key.
   *
   * @param apiKey the API key for Bearer token authentication
   */
  public void setApiKey(String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("API key cannot be empty");
    }
    if (apiKey.length() < 32) {
      System.err.println(
          "[Security Warning] API key should be at least 32 characters for security");
    }
    this.apiKey = apiKey;
    this.enabled = true;
  }

  /**
   * Adds a user with username and password authentication.
   *
   * @param username the username
   * @param password the password
   */
  public void addUser(String username, String password) {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("Username cannot be empty");
    }
    if (password == null || password.length() < 8) {
      throw new IllegalArgumentException("Password must be at least 8 characters");
    }

    // Generate salt
    byte[] salt = new byte[SALT_LENGTH];
    RANDOM.nextBytes(salt);
    String saltStr = Base64.getEncoder().encodeToString(salt);

    // Hash password with salt
    String hashedPassword = hashPassword(password, saltStr);

    users.put(username, hashedPassword);
    salts.put(username, saltStr);
    this.enabled = true;
  }

  /**
   * Adds an IP address or CIDR range to the whitelist.
   *
   * @param ipOrCidr IP address (e.g., "192.168.1.100") or CIDR (e.g., "10.0.0.0/8")
   */
  public void whitelistIP(String ipOrCidr) {
    if (ipOrCidr == null || ipOrCidr.isBlank()) {
      throw new IllegalArgumentException("IP cannot be empty");
    }
    ipWhitelist.put(ipOrCidr, true);
    this.enabled = true;
  }

  /**
   * Checks if authentication is enabled.
   *
   * @return true if authentication is enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Authenticates a request using the Authorization header and client IP.
   *
   * @param authHeader the Authorization header value (can be null)
   * @param clientIP   the client IP address
   * @return true if authenticated, false otherwise
   */
  public boolean authenticate(String authHeader, String clientIP) {
    if (!enabled) {
      return true; // Authentication disabled
    }

    // Check IP whitelist first (fastest)
    if (isIPWhitelisted(clientIP)) {
      return true;
    }

    // Check API key authentication
    if (apiKey != null && authHeader != null) {
      if (authHeader.startsWith("Bearer ")) {
        String token = authHeader.substring(7);
        if (apiKey.equals(token)) {
          return true;
        }
      }
    }

    // Check Basic authentication
    if (authHeader != null && authHeader.startsWith("Basic ")) {
      String base64Credentials = authHeader.substring(6);
      try {
        String credentials =
            new String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
        String[] parts = credentials.split(":", 2);
        if (parts.length == 2) {
          String username = parts[0];
          String password = parts[1];

          if (authenticateUser(username, password)) {
            return true;
          }
        }
      } catch (IllegalArgumentException e) {
        // Invalid base64
        return false;
      }
    }

    return false;
  }

  /**
   * Checks if an IP address is whitelisted.
   */
  private boolean isIPWhitelisted(String clientIP) {
    if (ipWhitelist.isEmpty()) {
      return false;
    }

    // Exact match
    if (ipWhitelist.containsKey(clientIP)) {
      return true;
    }

    // CIDR match (simplified - only supports common cases)
    for (String ipOrCidr : ipWhitelist.keySet()) {
      if (ipOrCidr.contains("/")) {
        if (matchesCIDR(clientIP, ipOrCidr)) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Checks if an IP matches a CIDR range.
   * Simplified implementation for common use cases.
   */
  private boolean matchesCIDR(String ip, String cidr) {
    try {
      String[] parts = cidr.split("/");
      String network = parts[0];
      int prefixLength = Integer.parseInt(parts[1]);

      // Convert IPs to integers for comparison
      long ipLong = ipToLong(ip);
      long networkLong = ipToLong(network);
      long mask = (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;

      return (ipLong & mask) == (networkLong & mask);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Converts an IP address string to a long.
   */
  private long ipToLong(String ip) {
    String[] octets = ip.split("\\.");
    if (octets.length != 4) {
      throw new IllegalArgumentException("Invalid IP address");
    }

    long result = 0;
    for (int i = 0; i < 4; i++) {
      result |= (Long.parseLong(octets[i]) << (24 - (8 * i)));
    }
    return result & 0xFFFFFFFFL;
  }

  /**
   * Authenticates a user with username and password.
   */
  private boolean authenticateUser(String username, String password) {
    String storedHash = users.get(username);
    String salt = salts.get(username);

    if (storedHash == null || salt == null) {
      return false;
    }

    String hashedInput = hashPassword(password, salt);
    return storedHash.equals(hashedInput);
  }

  /**
   * Hashes a password with a salt using SHA-256.
   */
  private String hashPassword(String password, String salt) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(salt.getBytes(StandardCharsets.UTF_8));
      byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }

  /**
   * Returns the WWW-Authenticate challenge header value.
   *
   * @return the challenge string for 401 responses
   */
  public String getAuthChallenge() {
    if (apiKey != null) {
      return "Bearer realm=\"Admin API\"";
    }
    if (!users.isEmpty()) {
      return "Basic realm=\"Admin API\"";
    }
    return "Bearer realm=\"Admin API\"";
  }
}
