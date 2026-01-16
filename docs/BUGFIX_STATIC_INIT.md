# Bug Fix Summary

## Issue

When starting NanoServer, the application crashed with the following exception:

```
Exception in thread "main" java.lang.ExceptionInInitializerError
	at com.github.youssefagagg.jnignx.core.Worker.<init>(Worker.java:28)
	at com.github.youssefagagg.jnignx.core.ServerLoop.start(ServerLoop.java:29)
	at com.github.youssefagagg.jnignx.NanoServer.start(NanoServer.java:126)
	at com.github.youssefagagg.jnignx.NanoServer.main(NanoServer.java:103)
Caused by: java.lang.NullPointerException: Cannot read the array length because 
"com.github.youssefagagg.jnignx.util.MetricsCollector.DURATION_BUCKETS" is null
	at com.github.youssefagagg.jnignx.util.MetricsCollector.<init>(MetricsCollector.java:39)
	at com.github.youssefagagg.jnignx.util.MetricsCollector.<clinit>(MetricsCollector.java:26)
	... 4 more
```

## Root Cause

**Static Initialization Order Problem in `MetricsCollector.java`**

The class had the following field declarations:

```java
public final class MetricsCollector {
  private static final MetricsCollector INSTANCE = new MetricsCollector();  // Line 26
  
  // ... other fields ...
  
  // Duration buckets declared AFTER INSTANCE
  private static final long[] DURATION_BUCKETS = {10, 50, 100, 500, 1000, 5000, 10000, Long.MAX_VALUE};
  private final LongAdder[] durationBuckets = new LongAdder[DURATION_BUCKETS.length];
  
  private MetricsCollector() {
    for (int i = 0; i < durationBuckets.length; i++) {  // Line 39 - tries to use DURATION_BUCKETS
      durationBuckets[i] = new LongAdder();
    }
  }
}
```

**The Problem:**

1. When the class is loaded, static fields are initialized in declaration order
2. `INSTANCE` is declared first, so it gets initialized first
3. Initializing `INSTANCE` calls the constructor
4. The constructor tries to create the `durationBuckets` array using `DURATION_BUCKETS.length`
5. But `DURATION_BUCKETS` hasn't been initialized yet (it's declared after `INSTANCE`)
6. Result: `NullPointerException`

This is a classic static initialization order dependency problem in Java.

## Solution

**Move the `DURATION_BUCKETS` static field BEFORE the `INSTANCE` field:**

```java
public final class MetricsCollector {
  // Duration buckets declared FIRST
  private static final long[] DURATION_BUCKETS = {10, 50, 100, 500, 1000, 5000, 10000, Long.MAX_VALUE};
  
  // Now INSTANCE can safely use DURATION_BUCKETS in its constructor
  private static final MetricsCollector INSTANCE = new MetricsCollector();
  
  // ... other fields ...
  
  private final LongAdder[] durationBuckets = new LongAdder[DURATION_BUCKETS.length];
  
  private MetricsCollector() {
    for (int i = 0; i < durationBuckets.length; i++) {
      durationBuckets[i] = new LongAdder();
    }
  }
}
```

**Why This Works:**

1. `DURATION_BUCKETS` is initialized first (as a constant array)
2. Then `INSTANCE` is initialized, calling the constructor
3. The constructor can now safely access `DURATION_BUCKETS` because it's already initialized
4. No more `NullPointerException`

## Verification

### Build Success

```bash
./gradlew build
BUILD SUCCESSFUL in 1s
```

### Tests Pass

```bash
./gradlew test
BUILD SUCCESSFUL in 3s
```

### Server Starts Successfully

```bash
./gradlew run
[Server] Starting on port 8080
[Server] Ready to accept connections!
```

### Metrics Endpoint Works

```bash
curl http://localhost:8080/metrics
# HELP nanoserver_uptime_seconds Server uptime in seconds
# TYPE nanoserver_uptime_seconds counter
nanoserver_uptime_seconds 0
...
```

## Lessons Learned

### Static Initialization Order Matters

In Java, static fields are initialized in the order they are declared. When you have dependencies between static fields,
you must declare them in the correct order.

**Rule of Thumb:**

- Declare static constants (like arrays, strings) BEFORE static instances
- Static instances that use those constants should come AFTER

### Similar Patterns to Watch For

```java
// BAD - Won't work
class Example {
    private static final Example INSTANCE = new Example();
    private static final int[] CONFIG = {1, 2, 3};  // Used in constructor
    
    Example() {
        int len = CONFIG.length;  // NullPointerException!
    }
}

// GOOD - Works correctly
class Example {
    private static final int[] CONFIG = {1, 2, 3};  // Declare first
    private static final Example INSTANCE = new Example();
    
    Example() {
        int len = CONFIG.length;  // CONFIG is already initialized
    }
}
```

### Alternative Solutions

1. **Lazy Initialization:**

```java
private static volatile MetricsCollector INSTANCE;

public static MetricsCollector getInstance() {
    if (INSTANCE == null) {
        synchronized (MetricsCollector.class) {
            if (INSTANCE == null) {
                INSTANCE = new MetricsCollector();
            }
        }
    }
    return INSTANCE;
}
```

2. **Static Initialization Block:**

```java
private static final long[] DURATION_BUCKETS;
private static final MetricsCollector INSTANCE;

static {
    DURATION_BUCKETS = new long[]{10, 50, 100, 500, 1000, 5000, 10000, Long.MAX_VALUE};
    INSTANCE = new MetricsCollector();
}
```

3. **Enum Singleton (Best Practice):**

```java
public enum MetricsCollector {
  INSTANCE;

  private static final long[] DURATION_BUCKETS = {10, 50, 100, 500, 1000, 5000, 10000, Long.MAX_VALUE};
  // ... rest of implementation
}
```

However, for this case, simply reordering the field declarations was the simplest and clearest fix.

## Impact

**Files Changed:** 1

- `src/main/java/com/github/youssefagagg/jnignx/util/MetricsCollector.java`

**Lines Changed:** 2 lines moved

**Breaking Changes:** None

**Test Coverage:** All existing tests pass

## Status

✅ **FIXED** - Server now starts successfully and all features work as expected.

---

**Date:** January 16, 2026
**Fixed By:** Static field reordering in MetricsCollector
**Severity:** Critical (prevented server startup)
**Resolution Time:** <5 minutes
