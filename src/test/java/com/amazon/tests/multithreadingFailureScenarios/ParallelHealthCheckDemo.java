package com.amazon.tests.multithreadingFailureScenarios;

import com.amazon.tests.BaseTest;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ========================================================================
 * PRACTICAL USE CASE #2: Parallel Microservice Health Checks
 * ========================================================================
 *
 * Real-world scenario: Check health of 10 microservices in parallel
 *
 * Sequential: 10 services × 500ms each = 5 seconds
 * Parallel (5 threads): 10 services / 5 = 2 batches × 500ms = 1 second
 *
 * Speedup: 5x faster!
 *
 * Practical for:
 * - Pre-test environment validation
 * - Smoke tests before suite execution
 * - Quick service availability checks
 * - Integration test prerequisites
 */
@Epic("Practical Multithreading")
@Feature("Parallel Health Checks")
public class ParallelHealthCheckDemo extends BaseTest {

    // Microservices to check
    private static final Map<String, String> SERVICES = new LinkedHashMap<>();

    static {
        // Service name → Health endpoint
        SERVICES.put("User Service", "/api/v1/users/health");
        SERVICES.put("Product Service", "/api/v1/products/health");
        SERVICES.put("Order Service", "/api/v1/orders/health");
        SERVICES.put("Payment Service", "/api/v1/payments/health");
        SERVICES.put("Inventory Service", "/api/v1/inventory/health");
        SERVICES.put("Notification Service", "/api/v1/notifications/health");
        SERVICES.put("Analytics Service", "/api/v1/analytics/health");
        SERVICES.put("Search Service", "/api/v1/search/health");
        SERVICES.put("Recommendation Service", "/api/v1/recommendations/health");
        SERVICES.put("Review Service", "/api/v1/reviews/health");
    }

    // Results tracking
    private final Map<String, HealthCheckResult> results = new ConcurrentHashMap<>();
    private final AtomicInteger healthyCount = new AtomicInteger(0);
    private final AtomicInteger unhealthyCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);

    @BeforeClass
    public void setup() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🏥 PARALLEL HEALTH CHECK DEMO - Microservices");
        System.out.println("=".repeat(80));
        System.out.println("Checking " + SERVICES.size() + " microservices");
        System.out.println("Sequential vs Parallel comparison");
        System.out.println("=".repeat(80) + "\n");
    }

    /**
     * OPTION 1: Check health SEQUENTIALLY (baseline - slow)
     */
  /*  @Test(priority = 1, description = "Sequential health checks - SLOW", enabled = false)
    @Story("Sequential Health Checks")
    public void option1_checkHealthSequentially() {
        System.out.println("🐌 OPTION 1: Checking services SEQUENTIALLY...\n");

        long startTime = System.currentTimeMillis();

        int serviceNum = 1;
        for (Map.Entry<String, String> service : SERVICES.entrySet()) {
            String serviceName = service.getKey();
            String healthEndpoint = service.getValue();

            System.out.println("  [" + serviceNum + "/" + SERVICES.size() + "] Checking " + serviceName + "...");

            HealthCheckResult result = checkServiceHealth(serviceName, healthEndpoint);
            results.put(serviceName, result);

            if (result.isHealthy()) {
                System.out.println("      ✅ Healthy (" + result.getResponseTime() + "ms)");
                healthyCount.incrementAndGet();
            } else {
                System.err.println("      ❌ Unhealthy: " + result.getMessage());
                unhealthyCount.incrementAndGet();
            }

            serviceNum++;
        }

        long duration = System.currentTimeMillis() - startTime;

        printResults("SEQUENTIAL", duration);
    }*/

    /**
     * OPTION 2: Check health IN PARALLEL (optimized - fast)
     */
    @Test(priority = 2, description = "Parallel health checks - FAST")
    @Story("Parallel Health Checks")
    public void option2_checkHealthInParallel() throws InterruptedException, ExecutionException {
        System.out.println("🚀 OPTION 2: Checking services IN PARALLEL (5 threads)...\n");

        long startTime = System.currentTimeMillis();

        // ✅ Create thread pool with 5 threads
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<Future<HealthCheckResult>> futures = new ArrayList<>();

        // ✅ Submit health check tasks
        int serviceNum = 1;
        for (Map.Entry<String, String> service : SERVICES.entrySet()) {
            final String serviceName = service.getKey();
            final String healthEndpoint = service.getValue();
            final int number = serviceNum;

            // Submit task to thread pool
            Future<HealthCheckResult> future = executor.submit(() -> {
                long threadId = Thread.currentThread().getId();
                System.out.println("  [Thread-" + threadId + "] [" + number + "/" + SERVICES.size() +
                        "] Checking " + serviceName + "...");

                HealthCheckResult result = checkServiceHealth(serviceName, healthEndpoint);

                if (result.isHealthy()) {
                    System.out.println("  [Thread-" + threadId + "] [" + number + "/" + SERVICES.size() +
                            "] ✅ " + serviceName + " - Healthy (" + result.getResponseTime() + "ms)");
                } else {
                    System.err.println("  [Thread-" + threadId + "] [" + number + "/" + SERVICES.size() +
                            "] ❌ " + serviceName + " - " + result.getMessage());
                }

                return result;
            });

            futures.add(future);
            serviceNum++;
        }

        // ✅ Wait for all health checks to complete
        int index = 0;
        for (Future<HealthCheckResult> future : futures) {
            //BLOCKS (waits) until that specific task completes
            HealthCheckResult result = future.get();
            String serviceName = new ArrayList<>(SERVICES.keySet()).get(index);

            results.put(serviceName, result);

            if (result.isHealthy()) {
                healthyCount.incrementAndGet();
            } else if (result.isError()) {
                errorCount.incrementAndGet();
            } else {
                unhealthyCount.incrementAndGet();
            }

            index++;
        }

        // ✅ Shutdown thread pool
        executor.shutdown();
      //  executor.awaitTermination(30, TimeUnit.SECONDS);

        long duration = System.currentTimeMillis() - startTime;

        printResults("PARALLEL", duration);
    }

    /**
     * Helper: Check health of a single service
     */
    private HealthCheckResult checkServiceHealth(String serviceName, String healthEndpoint) {
        long startTime = System.currentTimeMillis();

        try {
            Response response = RestAssured.given()
                    .baseUri(context.getConfig().baseUrl())
                    .get(healthEndpoint);

            long responseTime = System.currentTimeMillis() - startTime;
            int statusCode = response.statusCode();

            if (statusCode == 200) {
                return new HealthCheckResult(serviceName, true, responseTime, "OK", statusCode);
            } else if (statusCode == 404) {
                return new HealthCheckResult(serviceName, false, responseTime,
                        "Endpoint not found (might not have health endpoint)", statusCode);
            } else {
                return new HealthCheckResult(serviceName, false, responseTime,
                        "Unhealthy - Status " + statusCode, statusCode);
            }

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            return new HealthCheckResult(serviceName, false, responseTime,
                    "Error: " + e.getMessage(), 0, true);
        }
    }

    /**
     * Print formatted results
     */
    private void printResults(String mode, long duration) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("📊 " + mode + " HEALTH CHECK RESULTS");
        System.out.println("=".repeat(80));

        System.out.println("\nService Status:");
        for (Map.Entry<String, HealthCheckResult> entry : results.entrySet()) {
            HealthCheckResult result = entry.getValue();
            String status = result.isHealthy() ? "✅ HEALTHY" :
                    result.isError() ? "⚠️  ERROR" : "❌ UNHEALTHY";
            System.out.println("  " + String.format("%-25s", entry.getKey()) + " : " + status +
                    " (" + result.getResponseTime() + "ms)");
        }

        System.out.println("\nSummary:");
        System.out.println("  Healthy   : " + healthyCount.get() + "/" + SERVICES.size());
        System.out.println("  Unhealthy : " + unhealthyCount.get());
        System.out.println("  Errors    : " + errorCount.get());
        System.out.println("  ⏱️  Total time: " + duration + "ms");

        if (mode.equals("PARALLEL")) {
            long sequentialEstimate = SERVICES.size() * 500;  // Assume 500ms per service
            System.out.println("  🎯 Speedup: ~" + (sequentialEstimate / duration) + "x faster!");
        }

        System.out.println("=".repeat(80) + "\n");
    }

    @AfterClass
    public void printSummary() {
        System.out.println("=".repeat(80));
        System.out.println("🎯 HEALTH CHECK DEMO COMPLETE");
        System.out.println("=".repeat(80));
        System.out.println("\nKey Learnings:");
        System.out.println("  ✅ Parallel health checks = faster test suite startup");
        System.out.println("  ✅ ExecutorService handles concurrent API calls efficiently");
        System.out.println("  ✅ Future.get() waits for all checks before proceeding");
        System.out.println("  ✅ ConcurrentHashMap for thread-safe result storage");
        System.out.println("\nPractical Applications:");
        System.out.println("  • Pre-test suite environment validation");
        System.out.println("  • Smoke tests before integration tests");
        System.out.println("  • Quick service availability checks");
        System.out.println("  • CI/CD pipeline health checks");
        System.out.println("=".repeat(80) + "\n");
    }

    /**
     * Health check result data class
     */
    private static class HealthCheckResult {
        private final String serviceName;
        private final boolean healthy;
        private final long responseTime;
        private final String message;
        private final int statusCode;
        private final boolean error;

        public HealthCheckResult(String serviceName, boolean healthy, long responseTime,
                                 String message, int statusCode) {
            this(serviceName, healthy, responseTime, message, statusCode, false);
        }

        public HealthCheckResult(String serviceName, boolean healthy, long responseTime,
                                 String message, int statusCode, boolean error) {
            this.serviceName = serviceName;
            this.healthy = healthy;
            this.responseTime = responseTime;
            this.message = message;
            this.statusCode = statusCode;
            this.error = error;
        }

        public boolean isHealthy() { return healthy; }
        public long getResponseTime() { return responseTime; }
        public String getMessage() { return message; }
        public boolean isError() { return error; }
    }
}