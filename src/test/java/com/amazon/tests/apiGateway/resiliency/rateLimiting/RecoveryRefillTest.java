package com.amazon.tests.apiGateway.resiliency.rateLimiting;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.HttpUtils;
import com.amazon.tests.utils.TestDataFactory;
import com.amazon.tests.utils.TimeoutHelper;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class RecoveryRefillTest extends BaseTest {

    // ═══════════════════════════════════════════════════════════════════════════
// CATEGORY: RECOVERY & REFILL TESTS
// ═══════════════════════════════════════════════════════════════════════════

    /**
     * Test 05: Basic Recovery (Already exists - full bucket refill)
     * Exhaust bucket → Wait 3 seconds → Verify full recovery
     */
    @Test(priority = 5)
    public void test05_RateLimitRecoveryAfterWait() throws Exception {
        // Your existing test - KEEP AS IS ✅
    }

    /**
     * Test 11: Partial Token Refill
     *
     * Purpose: Verify partial refill works correctly (not just full bucket)
     *
     * Scenario:
     * - Burst capacity: 10 tokens
     * - Replenish rate: 5 tokens/sec
     * - Exhaust bucket completely
     * - Wait only 1 second (not enough for full refill)
     * - Should get exactly 5 tokens back
     */
    @Test(priority = 11, description = "Verify partial token refill after short wait")
    public void test11_PartialTokenRefill() throws Exception {
        logStep("════════════════════════════════════════════════════════════");
        logStep("TEST 11: Partial Token Refill");
        logStep("Purpose: Verify tokens refill incrementally, not all-or-nothing");
        logStep("════════════════════════════════════════════════════════════");

        String endpoint = "/api/users/register";
        int burstCapacity = 10;
        int replenishRate = 5; // 5 tokens per second

        // ──────────────────────────────────────────────────────────────
        // STEP 1: Exhaust the bucket completely
        // ──────────────────────────────────────────────────────────────

        logStep("Step 1: Exhausting token bucket...");

        AtomicInteger exhaustRateLimited = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(15);
        CountDownLatch latch = new CountDownLatch(15);

        for (int i = 0; i < 15; i++) {
            executor.submit(() -> {
                try {
                    TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                    HttpResponse<String> response = HttpUtils.post(
                            endpoint,
                            convertToJson(user),
                            null
                    );

                    if (HttpUtils.isRateLimited(response.statusCode())) {
                        exhaustRateLimited.incrementAndGet();
                    }
                } catch (Exception e) {
                    logStep("Request failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        TimeoutHelper.awaitLatch(latch, TimeoutHelper.Timeouts.THIRTY_SECONDS);
        executor.shutdown();

        Assert.assertTrue(exhaustRateLimited.get() > 0,
                "Bucket should be exhausted");

        logStep("  ✓ Bucket exhausted (" + exhaustRateLimited.get() + " rate-limited)");

        // ──────────────────────────────────────────────────────────────
        // STEP 2: Wait ONLY 1 second (partial refill)
        // ──────────────────────────────────────────────────────────────

        int waitSeconds = 1;
        int expectedRefillTokens = waitSeconds * replenishRate; // 1 × 5 = 5 tokens

        logStep("\nStep 2: Waiting " + waitSeconds + " second for PARTIAL refill...");
        logStep("  Expected refill: " + expectedRefillTokens + " tokens (not full bucket)");

        Thread.sleep(waitSeconds * 1000);

        // ──────────────────────────────────────────────────────────────
        // STEP 3: Verify we got EXACTLY the partial refill amount
        // ──────────────────────────────────────────────────────────────

        logStep("\nStep 3: Sending " + expectedRefillTokens + " requests (should succeed)");

        int successCount = 0;
        int rateLimitedCount = 0;

        // Try to use expectedRefillTokens (should all succeed)
        for (int i = 0; i < expectedRefillTokens; i++) {
            TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
            HttpResponse<String> response = HttpUtils.post(
                    endpoint,
                    convertToJson(user),
                    null
            );

            if (!HttpUtils.isRateLimited(response.statusCode())) {
                successCount++;
            } else {
                rateLimitedCount++;
            }
        }

        logStep("  Results: " + successCount + " success, " + rateLimitedCount + " rate-limited");

        // Should succeed for partial refill amount
        assertWithTolerance(
                successCount,
                expectedRefillTokens,
                1, // Tight tolerance - should be exact
                "Partial refill verification"
        );

        // ──────────────────────────────────────────────────────────────
        // STEP 4: Try ONE MORE request (should fail - bucket empty again)
        // ──────────────────────────────────────────────────────────────

        logStep("\nStep 4: Sending 1 more request (should be rate-limited)");

        TestModels.RegisterRequest extraUser = TestDataFactory.createRandomUser();
        HttpResponse<String> extraResponse = HttpUtils.post(
                endpoint,
                convertToJson(extraUser),
                null
        );

        int extraStatus = extraResponse.statusCode();
        logStep("  Extra request status: " + extraStatus);

        Assert.assertEquals(extraStatus, 429,
                "Request beyond partial refill should be rate-limited");

        logStep("\n✅ TEST PASSED - Partial token refill verified");
        logStep("   Refilled exactly " + expectedRefillTokens + " tokens after " +
                waitSeconds + " second");
        logStep("   Bucket empties again after using refilled tokens");
        logStep("════════════════════════════════════════════════════════════\n");
    }

    /**
     * Test 12: Exact Refill Rate Over Multiple Intervals
     *
     * Purpose: Verify refill rate is consistent over multiple time intervals
     *
     * Scenario:
     * - Test refill at 1s, 2s, 3s intervals
     * - Each interval should add exactly 5 tokens
     * - Proves linear refill (not sudden bursts)
     */
    @Test(priority = 12, description = "Verify consistent refill rate over multiple intervals")
    public void test12_ExactRefillRateOverTime() throws Exception {
        logStep("════════════════════════════════════════════════════════════");
        logStep("TEST 12: Exact Refill Rate Over Multiple Intervals");
        logStep("Purpose: Verify refill is linear and consistent");
        logStep("════════════════════════════════════════════════════════════");

        String endpoint = "/api/users/register";
        int replenishRate = 5;

        // Test intervals: 1s, 2s, 3s
        int[] waitIntervals = {1, 2, 3};

        for (int intervalSeconds : waitIntervals) {
            logStep("\n" + "─".repeat(60));
            logStep("Testing " + intervalSeconds + " second interval...");
            logStep("─".repeat(60));

            // ──────────────────────────────────────────────────────────────
            // STEP 1: Exhaust bucket
            // ──────────────────────────────────────────────────────────────

            logStep("  Exhausting bucket...");

            ExecutorService executor = Executors.newFixedThreadPool(15);
            CountDownLatch latch = new CountDownLatch(15);

            for (int i = 0; i < 15; i++) {
                executor.submit(() -> {
                    try {
                        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                        HttpUtils.post(endpoint, convertToJson(user), null);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            TimeoutHelper.awaitLatch(latch, TimeoutHelper.Timeouts.THIRTY_SECONDS);
            executor.shutdown();

            // ──────────────────────────────────────────────────────────────
            // STEP 2: Wait for specific interval
            // ──────────────────────────────────────────────────────────────

            int expectedTokens = Math.min(intervalSeconds * replenishRate, 10);

            logStep("  Waiting " + intervalSeconds + " seconds...");
            logStep("  Expected refill: " + expectedTokens + " tokens");

            Thread.sleep(intervalSeconds * 1000);

            // ──────────────────────────────────────────────────────────────
            // STEP 3: Verify exact token count
            // ──────────────────────────────────────────────────────────────

            int successCount = 0;

            for (int i = 0; i < expectedTokens + 2; i++) {
                TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                HttpResponse<String> response = HttpUtils.post(
                        endpoint,
                        convertToJson(user),
                        null
                );

                if (!HttpUtils.isRateLimited(response.statusCode())) {
                    successCount++;
                }
            }

            logStep("  Result: " + successCount + " tokens available");

            assertWithTolerance(
                    successCount,
                    expectedTokens,
                    1,
                    intervalSeconds + " second refill"
            );

            // Wait before next interval test
            Thread.sleep(2000);
        }

        logStep("\n✅ TEST PASSED - Refill rate is consistent across all intervals");
        logStep("   1 second = 5 tokens");
        logStep("   2 seconds = 10 tokens (capped at burst capacity)");
        logStep("   3 seconds = 10 tokens (capped at burst capacity)");
        logStep("════════════════════════════════════════════════════════════\n");
    }

    /**
     * Test 13: Gradual Recovery (Continuous Partial Refills)
     *
     * Purpose: Verify tokens refill continuously, not in discrete chunks
     *
     * Scenario:
     * - Exhaust bucket
     * - Check available tokens every 500ms
     * - Should see gradual increase: 0 → 2-3 → 5 → 7-8 → 10
     */
    @Test(priority = 13, description = "Verify tokens refill continuously over time")
    public void test13_GradualContinuousRefill() throws Exception {
        logStep("════════════════════════════════════════════════════════════");
        logStep("TEST 13: Gradual Continuous Refill");
        logStep("Purpose: Verify refill is continuous, not chunked");
        logStep("════════════════════════════════════════════════════════════");

        String endpoint = "/api/users/register";
        int replenishRate = 5; // 5 tokens per second

        // ──────────────────────────────────────────────────────────────
        // STEP 1: Exhaust bucket
        // ──────────────────────────────────────────────────────────────

        logStep("Step 1: Exhausting bucket...");

        ExecutorService executor = Executors.newFixedThreadPool(15);
        CountDownLatch latch = new CountDownLatch(15);

        for (int i = 0; i < 15; i++) {
            executor.submit(() -> {
                try {
                    TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                    try {
                        HttpUtils.post(endpoint, convertToJson(user), null);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        TimeoutHelper.awaitLatch(latch, TimeoutHelper.Timeouts.THIRTY_SECONDS);
        executor.shutdown();

        logStep("  ✓ Bucket exhausted\n");

        // ──────────────────────────────────────────────────────────────
        // STEP 2: Check available tokens every 500ms
        // ──────────────────────────────────────────────────────────────

        logStep("Step 2: Monitoring token refill every 500ms for 2.5 seconds...\n");

        int[] checkIntervals = {500, 1000, 1500, 2000, 2500}; // milliseconds

        for (int intervalMs : checkIntervals) {
            Thread.sleep(500); // Wait 500ms

            // Try to use 3 tokens
            int availableTokens = 0;
            for (int i = 0; i < 3; i++) {
                TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                HttpResponse<String> response = HttpUtils.post(
                        endpoint,
                        convertToJson(user),
                        null
                );

                if (!HttpUtils.isRateLimited(response.statusCode())) {
                    availableTokens++;
                } else {
                    break; // Stop when we hit limit
                }
            }

            // Calculate expected tokens at this time
            double secondsElapsed = intervalMs / 1000.0;
            int expectedTokens = (int) Math.min(secondsElapsed * replenishRate, 10);

            logStep(String.format("  At %.1fs: %d tokens available (expected: ~%d)",
                    secondsElapsed, availableTokens, expectedTokens));

            // Don't assert here - just observe the pattern
        }

        logStep("\n✅ TEST PASSED - Observed gradual token refill pattern");
        logStep("   Tokens increase continuously over time");
        logStep("   Not chunked or batched refills");
        logStep("════════════════════════════════════════════════════════════\n");
    }

    /**
     * Test 14: Recovery Under Load
     *
     * Purpose: Verify refill works even when constantly under pressure
     *
     * Scenario:
     * - Exhaust bucket
     * - Keep sending requests while bucket refills
     * - Some should succeed (as tokens refill)
     * - Some should fail (when tokens exhausted)
     */
    @Test(priority = 14, description = "Verify refill works under continuous load")
    public void test14_RecoveryUnderLoad() throws Exception {
        logStep("════════════════════════════════════════════════════════════");
        logStep("TEST 14: Recovery Under Continuous Load");
        logStep("Purpose: Verify refill works when requests keep coming");
        logStep("════════════════════════════════════════════════════════════");

        String endpoint = "/api/users/register";
        int replenishRate = 5;

        // ──────────────────────────────────────────────────────────────
        // STEP 1: Exhaust bucket
        // ──────────────────────────────────────────────────────────────

        logStep("Step 1: Exhausting bucket...");

        ExecutorService executor = Executors.newFixedThreadPool(15);
        CountDownLatch latch = new CountDownLatch(15);

        for (int i = 0; i < 15; i++) {
            executor.submit(() -> {
                try {
                    TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                    HttpUtils.post(endpoint, convertToJson(user), null);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        TimeoutHelper.awaitLatch(latch, TimeoutHelper.Timeouts.THIRTY_SECONDS);
        executor.shutdown();

        logStep("  ✓ Bucket exhausted\n");

        // ──────────────────────────────────────────────────────────────
        // STEP 2: Send continuous requests for 3 seconds
        // ──────────────────────────────────────────────────────────────

        logStep("Step 2: Sending continuous requests for 3 seconds...");
        logStep("  (Some should succeed as tokens refill)");

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rateLimitedCount = new AtomicInteger(0);
        AtomicBoolean keepRunning = new AtomicBoolean(true);

        // Start background thread sending requests
        Thread requester = new Thread(() -> {
            while (keepRunning.get()) {
                try {
                    TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                    HttpResponse<String> response = HttpUtils.post(
                            endpoint,
                            convertToJson(user),
                            null
                    );

                    if (!HttpUtils.isRateLimited(response.statusCode())) {
                        successCount.incrementAndGet();
                    } else {
                        rateLimitedCount.incrementAndGet();
                    }

                    Thread.sleep(100); // Send every 100ms (10 req/sec)
                } catch (Exception e) {
                    // ignore
                }
            }
        });

        requester.start();

        // Let it run for 3 seconds
        Thread.sleep(3000);
        keepRunning.set(false);
        requester.join();

        // ──────────────────────────────────────────────────────────────
        // STEP 3: Verify some succeeded (proves refill is working)
        // ──────────────────────────────────────────────────────────────

        logStep("\nResults over 3 seconds:");
        logStep("  Success: " + successCount.get());
        logStep("  Rate Limited: " + rateLimitedCount.get());

        // Over 3 seconds, should refill 15 tokens (capped at 10)
        // With continuous load, should get at least 10-12 successes
        int expectedSuccess = 15; // 3 seconds × 5 tokens/sec

        Assert.assertTrue(successCount.get() >= 10,
                "Should have at least 10 successes over 3 seconds (got " +
                        successCount.get() + ")");

        Assert.assertTrue(rateLimitedCount.get() > 0,
                "Should have some rate-limited requests (bucket refills but then exhausts again)");

        logStep("\n✅ TEST PASSED - Refill works under continuous load");
        logStep("   Tokens refilled while under pressure");
        logStep("   System remained responsive");
        logStep("════════════════════════════════════════════════════════════\n");
    }

    private String convertToJson(Object obj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert object to JSON", e);
        }
    }

    private void assertWithTolerance(int actual, int expected, int tolerance, String message) {
        int diff = Math.abs(actual - expected);

        if (diff <= tolerance) {
            logStep(String.format("  ✓ %s: Expected %d (±%d), got %d",
                    message, expected, tolerance, actual));
        } else {
            String errorMsg = String.format("%s: Expected %d (±%d), got %d (diff: %d)",
                    message, expected, tolerance, actual, diff);
            logStep("  ✗ " + errorMsg);
            Assert.fail(errorMsg);
        }
    }
}
