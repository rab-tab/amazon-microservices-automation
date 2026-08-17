package com.amazon.tests.utils.retry;


/**
 * Named, reusable RetryConfig presets.
 * Extracted from BaseTest so retry policy lives next to RetryHandler,
 * not scattered across every test base class.
 */
public final class RetryPresets {

    private RetryPresets() {}

    /**
     * Aggressive retry for race-condition-prone reads (e.g. order not
     * indexed/visible yet immediately after creation).
     */
    public static RetryHandler.RetryConfig raceCondition() {
        return new RetryHandler.RetryConfig()
                .maxAttempts(10)
                .initialDelay(100)
                .retryPolicy(RetryHandler.RetryPolicy.LINEAR)
                .retryOnStatusCodes(404, 503)
                .build();
    }

    /**
     * Standard transient-failure retry for common flaky-network conditions.
     */
    public static RetryHandler.RetryConfig transientFailure() {
        return new RetryHandler.RetryConfig()
                .maxAttempts(5)
                .initialDelay(200)
                .retryPolicy(RetryHandler.RetryPolicy.EXPONENTIAL_BACKOFF)
                .retryOnStatusCodes(408, 429, 500, 502, 503, 504)
                .build();
    }

    /**
     * Minimal retry without logging, for high-volume/noisy test loops.
     */
    public static RetryHandler.RetryConfig silent() {
        return new RetryHandler.RetryConfig()
                .maxAttempts(3)
                .initialDelay(100)
                .disableLogging()
                .build();
    }
}