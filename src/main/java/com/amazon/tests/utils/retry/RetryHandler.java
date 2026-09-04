package com.amazon.tests.utils.retry;

import com.amazon.tests.reports.ExtentReportManager;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.ConnectionPoolTimeoutException;

import java.net.ConnectException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Global retry handler for handling transient failures in tests.
 *
 * Supports:
 * - HTTP status code-based retries (502, 503, 504, etc.)
 * - Exception-based retries, split into two safety tiers:
 *     - connection-never-established failures (always safe to retry)
 *     - ambiguous failures like read-timeouts (only retried if the
 *       operation is explicitly marked idempotent — see RetryConfig#idempotentOperation)
 * - Configurable retry policies (exponential backoff, linear, fibonacci)
 * - Extent Report integration so retries are visible, not silently masked
 */
@Slf4j
public class RetryHandler {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_INITIAL_DELAY_MS = 100;

    // 404 intentionally excluded — a 404 is usually a real failure (resource
    // doesn't exist), not a transient one. Retrying it just delays surfacing
    // a real bug and masks it as "flaky infra". Opt in per-call-site via
    // addRetryableStatusCode(404) only for known eventual-consistency polling.
    private static final Set<Integer> DEFAULT_RETRYABLE_STATUS_CODES = new HashSet<>(
            Arrays.asList(408, 429, 500, 502, 503, 504)
    );

    // Exceptions meaning the request never reached the server at all —
    // nothing happened server-side, so retrying is always safe regardless
    // of HTTP method or idempotency.
    private static final Set<Class<? extends Exception>> CONNECTION_NEVER_ESTABLISHED = Set.of(
            ConnectException.class,
            ConnectTimeoutException.class,
            ConnectionPoolTimeoutException.class
    );

    /**
     * Retry configuration builder
     */
    public static class RetryConfig {
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private long initialDelayMs = DEFAULT_INITIAL_DELAY_MS;
        private RetryPolicy retryPolicy = RetryPolicy.EXPONENTIAL_BACKOFF;
        private Set<Integer> retryableStatusCodes = new HashSet<>(DEFAULT_RETRYABLE_STATUS_CODES);
        private Set<Class<? extends Exception>> retryableExceptions = new HashSet<>();
        private boolean logRetries = true;

        // Defaults to false (safest assumption). Only set true when the
        // caller can prove retrying is safe — e.g. GET/DELETE, or a POST/PUT
        // carrying a request-scoped idempotency key.
        private boolean idempotentOperation = false;

        public RetryConfig maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public RetryConfig initialDelay(long delayMs) {
            this.initialDelayMs = delayMs;
            return this;
        }

        public RetryConfig retryPolicy(RetryPolicy policy) {
            this.retryPolicy = policy;
            return this;
        }

        public RetryConfig retryOnStatusCodes(Integer... statusCodes) {
            this.retryableStatusCodes = new HashSet<>(Arrays.asList(statusCodes));
            return this;
        }

        public RetryConfig addRetryableStatusCode(int statusCode) {
            this.retryableStatusCodes.add(statusCode);
            return this;
        }

        public RetryConfig retryOnException(Class<? extends Exception> exceptionClass) {
            this.retryableExceptions.add(exceptionClass);
            return this;
        }

        public RetryConfig disableLogging() {
            this.logRetries = false;
            return this;
        }

        /**
         * Marks this operation as safe to retry even on ambiguous failures
         * (e.g. read-timeout, where the server may have already processed
         * the request). Only set true for GET/DELETE, or POST/PUT calls
         * using a request-scoped idempotency key.
         */
        public RetryConfig idempotentOperation(boolean idempotent) {
            this.idempotentOperation = idempotent;
            return this;
        }

        public RetryConfig build() {
            return this;
        }
    }

    public enum RetryPolicy {
        EXPONENTIAL_BACKOFF,  // 100ms, 200ms, 400ms, 800ms...
        LINEAR,               // 100ms, 100ms, 100ms...
        FIBONACCI             // 100ms, 100ms, 200ms, 300ms, 500ms...
    }

    /**
     * Execute operation with default retry configuration
     */
    public static <T> T executeWithRetry(Supplier<T> operation) {
        return executeWithRetry(operation, new RetryConfig());
    }

    /**
     * Execute operation with custom retry configuration
     */
    public static <T> T executeWithRetry(Supplier<T> operation, RetryConfig config) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= config.maxAttempts; attempt++) {
            try {
                T result = operation.get();

                Integer statusCode = extractStatusCode(result);
                if (statusCode != null && config.retryableStatusCodes.contains(statusCode)) {
                    if (attempt < config.maxAttempts) {
                        long delay = calculateDelay(config, attempt);
                        logRetry(config, attempt, statusCode, delay);
                        sleep(delay);
                        continue;
                    } else {
                        logMaxRetriesExceeded(config, attempt, statusCode);
                    }
                }

                if (attempt > 1 && config.logRetries) {
                    log.info("[OK] Operation succeeded on attempt {}", attempt);
                }
                return result;

            } catch (Exception e) {
                lastException = e;

                boolean isRetryable = isSafeToRetry(e, config);

                if (isRetryable && attempt < config.maxAttempts) {
                    long delay = calculateDelay(config, attempt);
                    logRetryException(config, attempt, e, delay);
                    sleep(delay);
                } else if (attempt == config.maxAttempts) {
                    logMaxRetriesExceededWithException(config, attempt, e);
                    throw new RetryExhaustedException(
                            String.format("Operation failed after %d attempts", config.maxAttempts),
                            e
                    );
                } else {
                    // not retryable at all (e.g. ambiguous failure on a
                    // non-idempotent operation) — fail fast, don't mask it
                    throw e;
                }
            }
        }

        throw new RetryExhaustedException(
                String.format("Operation failed after %d attempts", config.maxAttempts),
                lastException
        );
    }

    /**
     * Execute Response operation with default retry (convenience method)
     */
    public static Response executeRequestWithRetry(Supplier<Response> operation) {
        return executeWithRetry(operation, new RetryConfig());
    }

    /**
     * Execute Response operation with custom retry configuration
     */
    public static Response executeRequestWithRetry(Supplier<Response> operation, RetryConfig config) {
        return executeWithRetry(operation, config);
    }

    /**
     * Determines whether a caught exception is safe to retry.
     *
     * - Connection-never-established failures (pool exhaustion, connection
     *   refused, connect timeout): the request never reached the server —
     *   always safe, regardless of idempotency.
     * - Anything else configured via retryOnException(...) (e.g. read
     *   timeout): ambiguous — the server may have already processed the
     *   request. Only retried if the caller has explicitly proven this
     *   operation is idempotent via RetryConfig#idempotentOperation(true).
     */
    private static boolean isSafeToRetry(Exception e, RetryConfig config) {
        boolean requestNeverSent = CONNECTION_NEVER_ESTABLISHED.stream()
                .anyMatch(exClass -> exClass.isInstance(e));

        if (requestNeverSent) {
            return true;
        }

        boolean isConfiguredRetryable = config.retryableExceptions.stream()
                .anyMatch(exClass -> exClass.isInstance(e));

        return isConfiguredRetryable && config.idempotentOperation;
    }

    /**
     * Calculate delay based on retry policy
     */
    private static long calculateDelay(RetryConfig config, int attempt) {
        switch (config.retryPolicy) {
            case EXPONENTIAL_BACKOFF:
                return config.initialDelayMs * (long) Math.pow(2, attempt - 1);
            case LINEAR:
                return config.initialDelayMs;
            case FIBONACCI:
                return config.initialDelayMs * fibonacci(attempt);
            default:
                return config.initialDelayMs;
        }
    }

    private static long fibonacci(int n) {
        if (n <= 1) return 1;
        if (n == 2) return 1;
        long a = 1, b = 1;
        for (int i = 3; i <= n; i++) {
            long temp = a + b;
            a = b;
            b = temp;
        }
        return b;
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Retry interrupted", e);
        }
    }

    // ===== Logging — now mirrored into Extent, not just slf4j =====

    private static void logRetry(RetryConfig config, int attempt, int statusCode, long delay) {
        if (config.logRetries) {
            log.warn("[RETRY] Retryable status code {} on attempt {}/{}. Retrying in {}ms...",
                    statusCode, attempt, config.maxAttempts, delay);
            logToExtent(String.format("Retry %d/%d — status %d, backing off %dms",
                    attempt, config.maxAttempts, statusCode, delay));
        }
    }

    private static void logRetryException(RetryConfig config, int attempt, Exception e, long delay) {
        if (config.logRetries) {
            log.warn("[RETRY] Retryable exception on attempt {}/{}: {}. Retrying in {}ms...",
                    attempt, config.maxAttempts, e.getMessage(), delay);
            logToExtent(String.format("Retry %d/%d — %s: %s, backing off %dms",
                    attempt, config.maxAttempts, e.getClass().getSimpleName(), e.getMessage(), delay));
        }
    }

    private static void logMaxRetriesExceeded(RetryConfig config, int attempt, int statusCode) {
        if (config.logRetries) {
            log.error("[FAILED] Max retries ({}) exceeded. Final status: {}", attempt, statusCode);
        }
    }

    private static void logMaxRetriesExceededWithException(RetryConfig config, int attempt, Exception e) {
        if (config.logRetries) {
            log.error("[FAILED] Max retries ({}) exceeded. Final exception: {}", attempt, e.getMessage());
        }
    }

    /**
     * Best-effort mirror into Extent. Swallows failures silently — this
     * class may run outside a TestNG-managed thread (e.g. @BeforeSuite
     * setup), where no ExtentTest node exists for the current thread.
     */
    private static void logToExtent(String message) {
        try {
            ExtentReportManager.getInstance().logWarning(message);
        } catch (Exception ignored) {
            // no active test context — fine, slf4j already captured it
        }
    }

    private static Integer extractStatusCode(Object result) {
        if (result instanceof Response response) {
            return response.getStatusCode();
        }
        if (result instanceof RetryableResponse retryable) {
            return retryable.getStatusCode();
        }
        return null;
    }

    public static class RetryExhaustedException extends RuntimeException {
        public RetryExhaustedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}