package com.amazon.microservices.tests;

import com.amazon.tests.config.RateLimitConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.HttpUtils;
import com.amazon.tests.utils.RateLimitDataProvider;
import com.amazon.tests.utils.TestDataFactory;
import com.amazon.tests.utils.TimeoutHelper;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Refactored Rate Limiting Tests using Parameterized approach
 *
 * Features:
 * - Single test method for all similar scenarios
 * - External DataProvider for easy scenario management
 * - Reusable HttpUtils for request handling
 * - Proper logging using logStep from BaseTest
 * - Tolerance for intermittent failures
 * - Easy to extend with new test cases
 */
public class RateLimitingTest extends BaseTest {

    /**
     * Single parameterized test for all IP-based rate limiting scenarios
     * Data provider is in separate RateLimitDataProvider class
     */
    @Test(dataProvider = "ipBasedScenarios", dataProviderClass = RateLimitDataProvider.class, priority = 1)
    public void testIPBasedRateLimiting(RateLimitConfig config) throws Exception {
        runRateLimitTest(config, null);
    }

    /**
     * Single parameterized test for all user-based rate limiting scenarios
     * Data provider is in separate RateLimitDataProvider class
     */
    @Test(dataProvider = "userBasedScenarios", dataProviderClass = RateLimitDataProvider.class, priority = 2,enabled = false)
    public void testUserBasedRateLimiting(RateLimitConfig config) throws Exception {
        // Authenticate first
        logStep("Authenticating user for: " + config.getTestName());
        String authToken = HttpUtils.authenticateUser("testuser", "TestPass123!");
        Assert.assertNotNull(authToken, "Authentication required for: " + config.getTestName());
        logStep("Authentication successful");

        runRateLimitTest(config, authToken);
    }

    /**
     * Core test execution logic (reusable for all scenarios)
     */
    private void runRateLimitTest(RateLimitConfig config, String authToken) throws Exception {

        logStep("=".repeat(60));
        logStep("Test: " + config.getTestName());
        logStep("Endpoint: " + config.getHttpMethod() + " " + config.getEndpoint());
        logStep("Rate Limit: " + config.getReplenishRate() + " req/sec, burst: "
                + config.getBurstCapacity());
        logStep("Requests: " + config.getTotalRequests() + " concurrent");
        logStep("=".repeat(60));

        // Validate configuration
        Assert.assertTrue(config.isValid(), "Invalid test configuration");

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rateLimitCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(config.getThreadPoolSize());
        CountDownLatch latch = new CountDownLatch(config.getTotalRequests());

        long startTime = System.currentTimeMillis();

        // Fire all requests concurrently (tests burst capacity)
        for (int i = 0; i < config.getTotalRequests(); i++) {
            final int requestNum = i;
            executor.submit(() -> {
                try {
                    String requestBody;

                    // Special handling for registration endpoint - use createRandomUser()
                    if (config.getEndpoint().contains("/register")) {
                        // Generate unique random user for each request
                        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                        requestBody = convertToJson(user);
                    }
                    // Special handling for login endpoint - use createLoginRequest()
                    else if (config.getEndpoint().contains("/login")) {
                        // Use random email and password (will fail auth, but tests rate limit)
                        TestModels.LoginRequest loginRequest = TestDataFactory.createLoginRequest(
                                "testuser" + requestNum + "@test.com",
                                "wrongpassword" + requestNum
                        );
                        requestBody = convertToJson(loginRequest);
                    }
                    // Use template for other endpoints
                    else if (config.getRequestBodyTemplate() != null) {
                        requestBody = String.format(config.getRequestBodyTemplate(), requestNum, requestNum);
                    }
                    // No body (GET requests)
                    else {
                        requestBody = null;
                    }

                    HttpResponse<String> response = HttpUtils.sendRequest(
                            config.getEndpoint(),
                            requestBody,
                            authToken,
                            config.getHttpMethod()
                    );

                    int statusCode = response.statusCode();

                    if (HttpUtils.isSuccessful(statusCode)) {
                        successCount.incrementAndGet();
                    } else if (HttpUtils.isRateLimited(statusCode)) {
                        rateLimitCount.incrementAndGet();
                        // Verify and log rate limit headers
                        verifyRateLimitHeaders(response, config);
                    } else {
                        otherErrorCount.incrementAndGet();
                        logStep("Unexpected status: " + statusCode + " for request #" + requestNum);
                    }

                } catch (Exception e) {
                    logStep("Request failed: " + e.getMessage());
                    otherErrorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all requests to complete (30 seconds timeout)
        boolean completed = TimeoutHelper.awaitLatch(latch, TimeoutHelper.Timeouts.THIRTY_SECONDS);
        Assert.assertTrue(completed, "Test timed out waiting for requests to complete");

        executor.shutdown();
        // Wait for executor to terminate (5 seconds timeout)
        boolean terminated = TimeoutHelper.awaitTermination(executor, TimeoutHelper.Timeouts.FIVE_SECONDS);
        if (!terminated) {
            executor.shutdownNow();
            logStep("WARNING: Executor did not terminate gracefully");
        }

        long duration = System.currentTimeMillis() - startTime;

        // Print results
        logStep("\nResults:");
        logStep("  ✓ Success: " + successCount.get());
        logStep("  ✗ Rate Limited (429): " + rateLimitCount.get());
        logStep("  ? Other Errors: " + otherErrorCount.get());
        logStep("  Duration: " + duration + "ms");

        // Assertions with tolerance for timing variations
        assertWithTolerance(
                successCount.get(),
                config.getExpectedSuccess(),
                config.getTolerance(),
                "Success count mismatch for " + config.getTestName()
        );

        assertWithTolerance(
                rateLimitCount.get(),
                config.getExpectedRejected(),
                config.getTolerance(),
                "Rate limit count mismatch for " + config.getTestName()
        );

        // Total should match
        int totalProcessed = successCount.get() + rateLimitCount.get() + otherErrorCount.get();
        Assert.assertEquals(
                totalProcessed,
                config.getTotalRequests(),
                "Total processed doesn't match sent requests"
        );

        logStep("✓ Test PASSED\n");
    }

    /**
     * Helper method to convert object to JSON string
     */
    private String convertToJson(Object obj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert object to JSON", e);
        }
    }

    /**
     * Helper: Assert with tolerance for intermittent failures
     */
    private void assertWithTolerance(int actual, int expected, int tolerance, String message) {
        int diff = Math.abs(actual - expected);

        if (diff <= tolerance) {
            logStep(String.format("✓ %s: Expected %d (±%d), got %d",
                    message, expected, tolerance, actual));
        } else {
            String errorMsg = String.format("%s: Expected %d (±%d), got %d (diff: %d)",
                    message, expected, tolerance, actual, diff);
            logStep("✗ " + errorMsg);
            Assert.fail(errorMsg);
        }
    }

    /**
     * Verify rate limit response headers
     */
    private void verifyRateLimitHeaders(HttpResponse<String> response, RateLimitConfig config) {
        HttpUtils.RateLimitInfo rateLimitInfo = HttpUtils.getRateLimitInfo(response);

        if (rateLimitInfo.hasRateLimitHeaders()) {
            logStep("  Rate Limit Headers: " + rateLimitInfo.toString());

            // Optional: Verify specific header values
            if (rateLimitInfo.getLimit() != null) {
                int limit = Integer.parseInt(rateLimitInfo.getLimit());
                Assert.assertTrue(limit > 0, "Rate limit should be positive");
            }

            if (rateLimitInfo.getRemaining() != null) {
                int remaining = Integer.parseInt(rateLimitInfo.getRemaining());
                Assert.assertTrue(remaining >= 0, "Remaining should be non-negative");
            }

            if (rateLimitInfo.getRetryAfter() != null) {
                int retryAfter = Integer.parseInt(rateLimitInfo.getRetryAfter());
                Assert.assertTrue(retryAfter > 0, "Retry-After should be positive");
            }
        } else {
            logStep("  WARNING: No rate limit headers found in 429 response");
        }
    }

    /**
     * Test: Verify rate limit recovery after token refill
     */
    @Test(priority = 3,enabled = false)
    public void testRateLimitRecovery() throws Exception {
        logStep("=== Rate Limit Recovery Test ===");

        // Use registration endpoint: 5 req/sec, burst: 10
        String endpoint = "/api/auth/register";
        int burstCapacity = 10;
        double refillRatePerSec = 5;

        // Step 1: Exhaust the bucket
        logStep("Step 1: Exhausting token bucket (sending " + (burstCapacity + 2) + " requests)");
        for (int i = 0; i < burstCapacity + 2; i++) {
            // Use random user data
            TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
            String body = convertToJson(user);

            HttpResponse<String> response = HttpUtils.post(endpoint, body, null);

            if (i < burstCapacity) {
                Assert.assertTrue(HttpUtils.isSuccessful(response.statusCode()),
                        "Request " + i + " should succeed");
            } else {
                Assert.assertTrue(HttpUtils.isRateLimited(response.statusCode()),
                        "Request " + i + " should be rate-limited");
            }
        }
        logStep("Bucket exhausted - last 2 requests were rate-limited");

        // Step 2: Wait for token refill (2 seconds = 10 tokens at 5/sec)
        int waitSeconds = 2;
        int expectedTokens = (int) (waitSeconds * refillRatePerSec);
        logStep("Step 2: Waiting " + waitSeconds + " seconds for token refill (" + expectedTokens + " tokens)");
        TimeoutHelper.sleepSeconds(waitSeconds);

        // Step 3: Verify we can make requests again
        logStep("Step 3: Verifying recovery - sending " + expectedTokens + " requests");
        int successAfterRefill = 0;
        for (int i = 0; i < expectedTokens; i++) {
            // Use random user data
            TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
            String body = convertToJson(user);

            HttpResponse<String> response = HttpUtils.post(endpoint, body, null);

            if (HttpUtils.isSuccessful(response.statusCode())) {
                successAfterRefill++;
            }
        }

        // Allow ±2 tolerance for recovery test
        assertWithTolerance(
                successAfterRefill,
                expectedTokens,
                2,
                "Requests after token refill"
        );

        logStep("✓ Rate limit recovery verified\n");
    }

    /**
     * Test: Verify users have independent rate limits
     */
    @Test(priority = 4,enabled = false)
    public void testUsersHaveIndependentRateLimits() throws Exception {
        logStep("=== Independent Rate Limits Test ===");

        // Authenticate two different users
        logStep("Authenticating user1 and user2");
        String user1Token = HttpUtils.authenticateUser("testuser1", "TestPass123!");
        String user2Token = HttpUtils.authenticateUser("testuser2", "TestPass123!");

        Assert.assertNotNull(user1Token, "User1 authentication failed");
        Assert.assertNotNull(user2Token, "User2 authentication failed");

        String endpoint = "/api/orders";
        int burstCapacity = 10;

        // User1: Exhaust rate limit
        logStep("User1: Exhausting rate limit (sending " + (burstCapacity + 2) + " requests)");
        int user1RateLimited = 0;
        for (int i = 0; i < burstCapacity + 2; i++) {
            HttpResponse<String> response = HttpUtils.get(endpoint, user1Token);
            if (HttpUtils.isRateLimited(response.statusCode())) {
                user1RateLimited++;
            }
        }
        Assert.assertTrue(user1RateLimited > 0, "User1 should be rate-limited");
        logStep("User1 is rate-limited (" + user1RateLimited + " requests blocked)");

        // User2: Should still work (independent bucket)
        logStep("User2: Verifying independent rate limit (sending " + burstCapacity + " requests)");
        int user2Success = 0;
        for (int i = 0; i < burstCapacity; i++) {
            HttpResponse<String> response = HttpUtils.get(endpoint, user2Token);
            if (HttpUtils.isSuccessful(response.statusCode())) {
                user2Success++;
            }
        }

        assertWithTolerance(
                user2Success,
                burstCapacity,
                2,
                "User2 requests (should not be affected by User1's rate limit)"
        );

        logStep("✓ Users have independent rate limits verified\n");
    }
}