package com.github.youssefagagg.jnignx.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

  @Test
  void testTokenBucketAllowsRequests() {
    RateLimiter limiter = new RateLimiter(
        RateLimiter.Strategy.TOKEN_BUCKET,
        10,
        Duration.ofSeconds(1)
    );

    String clientIp = "192.168.1.1";

    // First 10 requests should be allowed
    for (int i = 0; i < 10; i++) {
      assertTrue(limiter.allowRequest(clientIp),
                 "Request " + i + " should be allowed");
    }

    // 11th request should be rate limited
    assertFalse(limiter.allowRequest(clientIp),
                "Request 11 should be rate limited");
  }

  @Test
  void testSlidingWindowAllowsRequests() {
    RateLimiter limiter = new RateLimiter(
        RateLimiter.Strategy.SLIDING_WINDOW,
        5,
        Duration.ofSeconds(1)
    );

    String clientIp = "192.168.1.2";

    // First 5 requests should be allowed
    for (int i = 0; i < 5; i++) {
      assertTrue(limiter.allowRequest(clientIp),
                 "Request " + i + " should be allowed");
    }

    // 6th request should be rate limited
    assertFalse(limiter.allowRequest(clientIp),
                "Request 6 should be rate limited");
  }

  @Test
  void testDifferentClientsIndependent() {
    RateLimiter limiter = new RateLimiter(
        RateLimiter.Strategy.TOKEN_BUCKET,
        2,
        Duration.ofSeconds(1)
    );

    String client1 = "192.168.1.1";
    String client2 = "192.168.1.2";

    // Client 1: Use up quota
    assertTrue(limiter.allowRequest(client1));
    assertTrue(limiter.allowRequest(client1));
    assertFalse(limiter.allowRequest(client1));

    // Client 2: Should have independent quota
    assertTrue(limiter.allowRequest(client2));
    assertTrue(limiter.allowRequest(client2));
    assertFalse(limiter.allowRequest(client2));
  }

  @Test
  void testPerPathLimiting() {
    RateLimiter limiter = new RateLimiter(
        RateLimiter.Strategy.TOKEN_BUCKET,
        2,
        Duration.ofSeconds(1)
    );

    String clientIp = "192.168.1.1";
    String path1 = "/api/v1";
    String path2 = "/api/v2";

    // Path 1: Use up quota
    assertTrue(limiter.allowRequest(clientIp, path1));
    assertTrue(limiter.allowRequest(clientIp, path1));
    assertFalse(limiter.allowRequest(clientIp, path1));

    // Path 2: Should have independent quota
    assertTrue(limiter.allowRequest(clientIp, path2));
    assertTrue(limiter.allowRequest(clientIp, path2));
    assertFalse(limiter.allowRequest(clientIp, path2));
  }

  @Test
  void testTokenBucketRefill() throws InterruptedException {
    RateLimiter limiter = new RateLimiter(
        RateLimiter.Strategy.TOKEN_BUCKET,
        3,
        Duration.ofMillis(100)
    );

    String clientIp = "192.168.1.1";

    // Use up tokens
    assertTrue(limiter.allowRequest(clientIp));
    assertTrue(limiter.allowRequest(clientIp));
    assertTrue(limiter.allowRequest(clientIp));
    assertFalse(limiter.allowRequest(clientIp));

    // Wait for refill
    Thread.sleep(150);

    // Should have tokens again
    assertTrue(limiter.allowRequest(clientIp),
               "Tokens should have been refilled");
  }

  @Test
  void testSlidingWindowExpiry() throws InterruptedException {
    RateLimiter limiter = new RateLimiter(
        RateLimiter.Strategy.SLIDING_WINDOW,
        2,
        Duration.ofMillis(200)
    );

    String clientIp = "192.168.1.1";

    // Use up quota
    assertTrue(limiter.allowRequest(clientIp));
    assertTrue(limiter.allowRequest(clientIp));
    assertFalse(limiter.allowRequest(clientIp));

    // Wait for window to slide
    Thread.sleep(250);

    // Should allow requests again
    assertTrue(limiter.allowRequest(clientIp),
               "Window should have expired");
  }

  @Test
  void testZeroLimit() {
    RateLimiter limiter = new RateLimiter(
        RateLimiter.Strategy.TOKEN_BUCKET,
        0,
        Duration.ofSeconds(1)
    );

    String clientIp = "192.168.1.1";

    // No requests should be allowed
    assertFalse(limiter.allowRequest(clientIp));
    assertFalse(limiter.allowRequest(clientIp));
  }

  @Test
  void testHighVolumeStability() {
    RateLimiter limiter = new RateLimiter(
        RateLimiter.Strategy.TOKEN_BUCKET,
        100,
        Duration.ofSeconds(1)
    );

    String clientIp = "192.168.1.1";
    int allowed = 0;
    int denied = 0;

    // Try 150 requests
    for (int i = 0; i < 150; i++) {
      if (limiter.allowRequest(clientIp)) {
        allowed++;
      } else {
        denied++;
      }
    }

    assertEquals(100, allowed, "Should allow exactly 100 requests");
    assertEquals(50, denied, "Should deny exactly 50 requests");
  }

  @Test
  void testShutdown() {
    RateLimiter limiter = new RateLimiter(
        RateLimiter.Strategy.TOKEN_BUCKET,
        10,
        Duration.ofSeconds(1)
    );

    limiter.allowRequest("192.168.1.1");

    // Shutdown should not throw exception
    assertDoesNotThrow(() -> limiter.shutdown());
  }
}
