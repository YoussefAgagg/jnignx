package com.github.youssefagagg.jnignx;

import java.util.List;
import java.util.Map;

/**
 * Immutable configuration record representing a route mapping.
 * Each route maps a path prefix to one or more backend URLs for load balancing.
 *
 * <p>Example configuration in routes.json:
 * <pre>
 * {
 *   "routes": {
 *     "/api": ["http://localhost:3000", "http://localhost:3001"],
 *     "/static": ["http://localhost:8080"]
 *   }
 * }
 * </pre>
 *
 * @param routes Map of path prefixes to lists of backend URLs
 */
public record RouteConfig(Map<String, List<String>> routes) {

  /**
   * Creates an empty RouteConfig with no routes defined.
   *
   * @return an empty RouteConfig instance
   */
  public static RouteConfig empty() {
    return new RouteConfig(Map.of());
  }

  /**
   * Returns the list of backends for a given path, or null if no route matches.
   * Performs longest-prefix matching to find the most specific route.
   *
   * @param path the request path to match
   * @return list of backend URLs, or null if no route matches
   */
  public List<String> getBackends(String path) {
    // Longest prefix match - find the most specific route
    String bestMatch = null;
    for (String prefix : routes.keySet()) {
      if (path.startsWith(prefix)) {
        if (bestMatch == null || prefix.length() > bestMatch.length()) {
          bestMatch = prefix;
        }
      }
    }
    return bestMatch != null ? routes.get(bestMatch) : null;
  }
}
