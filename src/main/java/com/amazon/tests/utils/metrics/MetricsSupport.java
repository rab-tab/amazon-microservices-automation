package com.amazon.tests.utils.metrics;


import io.restassured.response.Response;

import java.util.function.Supplier;

/**
 * Thin timing/metrics wrapper around MetricsManager.
 * Extracted from BaseTest so instrumentation concerns don't live
 * inside the test lifecycle base class, and so non-test code
 * (e.g. workflows, api clients) can record metrics the same way.
 *
 * Static, matching RetryPresets style — this class is stateless
 * (MetricsManager itself is the singleton underneath).
 */
public final class MetricsSupport {

    private MetricsSupport() {}

    /**
     * Execute an HTTP call and record its latency against a named endpoint.
     */
    public static Response timedRequest(String endpoint, Supplier<Response> request) {
        long start = System.currentTimeMillis();
        try {
            return request.get();
        } finally {
            MetricsManager.recordApiLatency(endpoint, System.currentTimeMillis() - start);
        }
    }

    /**
     * Execute an operation and record it under a named await/wait bucket.
     */
    public static void timedAwait(String operation, Runnable waitLogic) {
        long start = System.currentTimeMillis();
        try {
            waitLogic.run();
        } finally {
            MetricsManager.recordAwaitility(operation, System.currentTimeMillis() - start);
        }
    }

    public static void recordTestDuration(long durationMs) {
        MetricsManager.getInstance().recordTestDuration(durationMs);
    }

    public static void pushToPrometheus(String job) {
        MetricsPushService.pushToPrometheus(job);
    }
}