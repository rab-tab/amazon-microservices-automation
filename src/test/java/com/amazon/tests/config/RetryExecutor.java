package com.amazon.tests.config;

import com.amazon.tests.utils.metrics.MetricsManager;
import com.amazon.tests.utils.retry.RetryHandler;
import io.restassured.response.Response;

import java.util.function.Supplier;

public class RetryExecutor {
    // ══════════════════════════════════════════════════════════════════
    // RETRY UTILITIES
    // ══════════════════════════════════════════════════════════════════

    /**
     * Execute HTTP request with default retry logic
     * Retries on: 404, 408, 429, 500, 502, 503, 504
     * Max attempts: 3
     * Policy: Exponential backoff (100ms, 200ms, 400ms)
     */
    protected Response executeWithRetry(Supplier<Response> request) {
        long start = System.currentTimeMillis();

        try {
            return RetryHandler.executeRequestWithRetry(request);
        }
        finally {

          /*  metrics.recordApiCall(
                    System.currentTimeMillis() - start
            );*/
        }
    }

    protected Response executeWithRetry(
            String endpoint,
            Supplier<Response> request) {

        long start =
                System.currentTimeMillis();

        try {

            return RetryHandler.executeRequestWithRetry(request);

        } finally {

            MetricsManager.recordApiLatency(
                    endpoint,
                    System.currentTimeMillis() - start
            );
        }
    }
}
