package com.amazon.tests.tests.apiGateway.resiliency.rateLimiting;


import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.*;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
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
 * - Authentication support for protected endpoints
 */
public class RateLimitingTest extends BaseTest {

    // Shared authentication token for all tests

    private String validToken;
    private String userId;
    private TestModels.AuthResponse authResponse;

    /**
     * Set up authentication before all tests
     * All endpoints except register require authentication
     */
    @BeforeClass
    public void setupAuthentication() throws Exception {
        logStep("=== Setting up authentication for rate limit tests ===");

        // Register a new user and get authentication token
        authResponse = AuthUtils.registerAndGetAuth();
        validToken = authResponse.getAccessToken();
        userId = authResponse.getUser().getId();

        logStep("Authentication successful - User ID: " + userId);
        logStep("Token: " + validToken.substring(0, Math.min(20, validToken.length())) + "...");

        // Set authentication in all configs
        setAuthInConfigs(validToken, userId);

        logStep("=".repeat(60) + "\n");
    }

    /**
     * Set authentication details in all RateLimitConfig objects
     */
    private void setAuthInConfigs(String token, String userId) {
        // IP-based configs
       // com.amazon.microservices.config.RateLimitConfig.IPBased.REGISTRATION.setAuthToken(token);
        com.amazon.microservices.config.RateLimitConfig.IPBased.REGISTRATION.setUserId(userId);

        com.amazon.microservices.config.RateLimitConfig.IPBased.LOGIN.setAuthToken(token);
        com.amazon.microservices.config.RateLimitConfig.IPBased.LOGIN.setUserId(userId);

        com.amazon.microservices.config.RateLimitConfig.IPBased.PRODUCTS_LIST.setAuthToken(token);
        com.amazon.microservices.config.RateLimitConfig.IPBased.PRODUCTS_LIST.setUserId(userId);

        // User-based configs
        com.amazon.microservices.config.RateLimitConfig.UserBased.ORDER_LIST.setAuthToken(token);
        com.amazon.microservices.config.RateLimitConfig.UserBased.ORDER_LIST.setUserId(userId);

        com.amazon.microservices.config.RateLimitConfig.UserBased.ORDER_CREATION.setAuthToken(token);
        com.amazon.microservices.config.RateLimitConfig.UserBased.ORDER_CREATION.setUserId(userId);

        com.amazon.microservices.config.RateLimitConfig.UserBased.PROFILE_UPDATE.setAuthToken(token);
        com.amazon.microservices.config.RateLimitConfig.UserBased.PROFILE_UPDATE.setUserId(userId);
    }

    /**
     * Single parameterized test for all IP-based rate limiting scenarios
     * Data provider is in separate RateLimitDataProvider class
     */
    @Test(dataProvider = "ipBasedScenarios", dataProviderClass = RateLimitDataProvider.class, priority = 1)
    public void testIPBasedRateLimiting(com.amazon.microservices.config.RateLimitConfig config) throws Exception {
        // Config already has auth token, use it based on requiresAuth
        String token = config.isRequiresAuth() ? config.getAuthToken() : null;
        runRateLimitTest(config, token);
    }

    /**
     * Single parameterized test for all user-based rate limiting scenarios
     * Data provider is in separate RateLimitDataProvider class
     */
    @Test(dataProvider = "userBasedScenarios", dataProviderClass = RateLimitDataProvider.class, priority = 2)
    public void testUserBasedRateLimiting(com.amazon.microservices.config.RateLimitConfig config) throws Exception {
        // All user-based endpoints require authentication
        runRateLimitTest(config, config.getAuthToken());
    }

    /**
     * Core test execution logic (reusable for all scenarios)
     */
    private void runRateLimitTest(com.amazon.microservices.config.RateLimitConfig config, String authToken) throws Exception {

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
    private void verifyRateLimitHeaders(HttpResponse<String> response, com.amazon.microservices.config.RateLimitConfig config) {
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
    @Test(priority = 3)
    public void testRateLimitRecovery() throws Exception {
        logStep("=== Rate Limit Recovery Test ===");

        // Use login endpoint (requires auth): 10 req/sec, burst: 20
        String endpoint = "/api/auth/login";
        int burstCapacity = 20;
        double refillRatePerSec = 10;

        // Step 1: Exhaust the bucket
        logStep("Step 1: Exhausting token bucket (sending " + (burstCapacity + 2) + " requests)");
        for (int i = 0; i < burstCapacity + 2; i++) {
            // Use login requests (authentication is at gateway level, login itself is public)
            TestModels.LoginRequest loginRequest = TestDataFactory.createLoginRequest(
                    "testuser" + i + "@test.com",
                    "wrongpassword" + i
            );
            String body = convertToJson(loginRequest);

            HttpResponse<String> response = HttpUtils.post(endpoint, body, null);

            if (i < burstCapacity) {
                // Should succeed (401 Unauthorized is still a successful rate limit check)
                Assert.assertTrue(response.statusCode() == 401 || HttpUtils.isSuccessful(response.statusCode()),
                        "Request " + i + " should succeed (rate limit allows it)");
            } else {
                Assert.assertTrue(HttpUtils.isRateLimited(response.statusCode()),
                        "Request " + i + " should be rate-limited");
            }
        }
        logStep("Bucket exhausted - last 2 requests were rate-limited");

        // Step 2: Wait for token refill (2 seconds = 20 tokens at 10/sec)
        int waitSeconds = 2;
        int expectedTokens = (int) (waitSeconds * refillRatePerSec);
        logStep("Step 2: Waiting " + waitSeconds + " seconds for token refill (" + expectedTokens + " tokens)");
        TimeoutHelper.sleepSeconds(waitSeconds);

        // Step 3: Verify we can make requests again
        logStep("Step 3: Verifying recovery - sending " + expectedTokens + " requests");
        int successAfterRefill = 0;
        for (int i = 0; i < expectedTokens; i++) {
            TestModels.LoginRequest loginRequest = TestDataFactory.createLoginRequest(
                    "recovery" + i + "@test.com",
                    "wrongpassword" + i
            );
            String body = convertToJson(loginRequest);

            HttpResponse<String> response = HttpUtils.post(endpoint, body, null);

            // Success means not rate-limited (401 is ok, it's just wrong password)
            if (response.statusCode() == 401 || HttpUtils.isSuccessful(response.statusCode())) {
                successAfterRefill++;
            }
        }

        // Allow ±2 tolerance for recovery test
        assertWithTolerance(
                successAfterRefill,
                expectedTokens,
                3,
                "Requests after token refill"
        );

        logStep("✓ Rate limit recovery verified\n");
    }

    /**
     * Test: Verify users have independent rate limits
     */
    @Test(priority = 4)
    public void testUsersHaveIndependentRateLimits() throws Exception {
        logStep("=== Independent Rate Limits Test ===");

        // Create two separate authenticated users
        logStep("Creating and authenticating user1");
        authResponse  = AuthUtils.registerAndGetAuth();
        String user1Token = authResponse.getAccessToken();

        logStep("Creating and authenticating user2");
        authResponse = AuthUtils.registerAndGetAuth();
        String user2Token = authResponse.getAccessToken();

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