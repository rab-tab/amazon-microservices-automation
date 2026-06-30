package com.amazon.tests.utils.retry;

import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Global retry handler for handling transient failures in tests
 *
 * Supports:
 * - HTTP status code-based retries (404, 503, 502, etc.)
 * - Exception-based retries (timeouts, connection errors)
 * - Configurable retry policies (exponential backoff, linear)
 * - Custom retry conditions
 */
@Slf4j
public class RetryHandler {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_INITIAL_DELAY_MS = 100;
    private static final Set<Integer> DEFAULT_RETRYABLE_STATUS_CODES = new HashSet<>(
            Arrays.asList(404, 408, 429, 500, 502, 503, 504)
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

                // Check if result is a Response and has retryable status
                if (result instanceof Response) {
                    Response response = (Response) result;
                    int statusCode = response.getStatusCode();

                    if (config.retryableStatusCodes.contains(statusCode)) {
                        if (attempt < config.maxAttempts) {
                            long delay = calculateDelay(config, attempt);
                            logRetry(config, attempt, statusCode, delay);
                            sleep(delay);
                            continue;
                        } else {
                            logMaxRetriesExceeded(config, attempt, statusCode);
                        }
                    }
                }

                // Success - return result
                if (attempt > 1 && config.logRetries) {
                    log.info("✅ Operation succeeded on attempt {}", attempt);
                }
                return result;

            } catch (Exception e) {
                lastException = e;

                // Check if exception is retryable
                boolean isRetryable = config.retryableExceptions.stream()
                        .anyMatch(exClass -> exClass.isInstance(e));

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
                    // Non-retryable exception
                    throw e;
                }
            }
        }

        // Should not reach here, but just in case
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

    /**
     * Calculate fibonacci number for retry delay
     */
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

    /**
     * Sleep for specified duration
     */
    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Retry interrupted", e);
        }
    }

    /**
     * Logging methods
     */
    private static void logRetry(RetryConfig config, int attempt, int statusCode, long delay) {
        if (config.logRetries) {
            log.warn("⚠️  Retryable status code {} on attempt {}/{}. Retrying in {}ms...",
                    statusCode, attempt, config.maxAttempts, delay);
        }
    }

    private static void logRetryException(RetryConfig config, int attempt, Exception e, long delay) {
        if (config.logRetries) {
            log.warn("⚠️  Retryable exception on attempt {}/{}: {}. Retrying in {}ms...",
                    attempt, config.maxAttempts, e.getMessage(), delay);
        }
    }

    private static void logMaxRetriesExceeded(RetryConfig config, int attempt, int statusCode) {
        if (config.logRetries) {
            log.error("❌ Max retries ({}) exceeded. Final status: {}", attempt, statusCode);
        }
    }

    private static void logMaxRetriesExceededWithException(RetryConfig config, int attempt, Exception e) {
        if (config.logRetries) {
            log.error("❌ Max retries ({}) exceeded. Final exception: {}", attempt, e.getMessage());
        }
    }

    /**
     * Custom exception for retry exhaustion
     */
    public static class RetryExhaustedException extends RuntimeException {
        public RetryExhaustedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}