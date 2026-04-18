package com.amazon.tests.tests.apiGateway.resiliency.rateLimiting;


import com.amazon.tests.config.RateLimitConfig;
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
    private static String validToken;
    private static String userId;
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
        RateLimitConfig.IPBased.REGISTRATION.setAuthToken(token);
        RateLimitConfig.IPBased.REGISTRATION.setUserId(userId);

        RateLimitConfig.IPBased.LOGIN.setAuthToken(token);
        RateLimitConfig.IPBased.LOGIN.setUserId(userId);

        RateLimitConfig.IPBased.PRODUCTS_LIST.setAuthToken(token);
        RateLimitConfig.IPBased.PRODUCTS_LIST.setUserId(userId);

        // User-based configs
        RateLimitConfig.UserBased.ORDER_LIST.setAuthToken(token);
        RateLimitConfig.UserBased.ORDER_LIST.setUserId(userId);

        RateLimitConfig.UserBased.ORDER_CREATION.setAuthToken(token);
        RateLimitConfig.UserBased.ORDER_CREATION.setUserId(userId);

        RateLimitConfig.UserBased.PROFILE_UPDATE.setAuthToken(token);
        RateLimitConfig.UserBased.PROFILE_UPDATE.setUserId(userId);
    }

    /**
     * Single parameterized test for all IP-based rate limiting scenarios
     * Data provider is in separate RateLimitDataProvider class
     */
    @Test(dataProvider = "ipBasedScenarios", dataProviderClass = RateLimitDataProvider.class, priority = 1)
    public void testIPBasedRateLimiting(RateLimitConfig config) throws Exception {
        // Config already has auth token, use it based on requiresAuth
        String token = config.isRequiresAuth() ? config.getAuthToken() : null;
        runRateLimitTest(config, token);
    }

    /**
     * Single parameterized test for all user-based rate limiting scenarios
     * Data provider is in separate RateLimitDataProvider class
     */
    @Test(dataProvider = "userBasedScenarios", dataProviderClass = RateLimitDataProvider.class, priority = 2)
    public void testUserBasedRateLimiting(RateLimitConfig config) throws Exception {
        // All user-based endpoints require authentication
        runRateLimitTest(config, config.getAuthToken());
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
                    // Special handling for order creation endpoint
                    else if (config.getEndpoint().equals("/api/orders") &&
                            config.getHttpMethod().equals("POST")) {
                        // Generate random order with UUID productId
                        requestBody = createOrderRequestBody();
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
     * Helper method to create order request body
     * Matches the format: items with productId (UUID), quantity, unitPrice, productName
     */
    private String createOrderRequestBody() {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();

            com.github.javafaker.Faker faker = new com.github.javafaker.Faker();

            // Generate a valid UUID for productId
            java.util.UUID productId = java.util.UUID.randomUUID();

            java.util.Map<String, Object> orderRequest = java.util.Map.of(
                    "items", java.util.List.of(
                            java.util.Map.of(
                                    "productId", productId.toString(),  // UUID as string
                                    "quantity", 1,
                                    "unitPrice", 50.0,
                                    "productName", "Test Product"
                            )
                    ),
                    "shippingAddress", faker.address().fullAddress()
            );

            return mapper.writeValueAsString(orderRequest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create order request body", e);
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
    @Test(priority = 3)
    public void testRateLimitRecovery() throws Exception {
        logStep("=== Rate Limit Recovery Test ===");

        // Use registration endpoint: 5 req/sec, burst: 10
        String endpoint = "/api/users/register";
        int burstCapacity = 10;
        double refillRatePerSec = 5;

        // Step 1: Exhaust the bucket - send concurrently to trigger rate limit
        int requestsToSend = burstCapacity + 5;  // 15 requests
        logStep("Step 1: Exhausting token bucket (sending " + requestsToSend + " concurrent requests)");

        AtomicInteger rateLimitedCount = new AtomicInteger(0);
        AtomicInteger allowedCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(requestsToSend);
        CountDownLatch latch = new CountDownLatch(requestsToSend);

        // Send all requests concurrently (this triggers burst limit)
        for (int i = 0; i < requestsToSend; i++) {
            executor.submit(() -> {
                try {
                    TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                    String body = convertToJson(user);

                    HttpResponse<String> response = HttpUtils.post(endpoint, body, null);
                    int statusCode = response.statusCode();

                    if (HttpUtils.isRateLimited(statusCode)) {
                        rateLimitedCount.incrementAndGet();
                    } else {
                        allowedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    logStep("Request failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all requests to complete
        boolean completed = TimeoutHelper.awaitLatch(latch, TimeoutHelper.Timeouts.THIRTY_SECONDS);
        Assert.assertTrue(completed, "Exhaustion phase timed out");

        executor.shutdown();
        TimeoutHelper.awaitTermination(executor, TimeoutHelper.Timeouts.FIVE_SECONDS);

        logStep("Sent " + requestsToSend + " concurrent requests: " + allowedCount.get() +
                " allowed, " + rateLimitedCount.get() + " rate-limited");

        // Verify we hit the rate limit
        Assert.assertTrue(rateLimitedCount.get() > 0,
                "Should have triggered rate limit. Got " + rateLimitedCount.get() + " rate-limited requests");

        logStep("Bucket exhausted - " + rateLimitedCount.get() + " requests were rate-limited");

        // Step 2: Wait for token refill
        int waitSeconds = 3;
        int expectedTokens = (int) (waitSeconds * refillRatePerSec);
        logStep("Step 2: Waiting " + waitSeconds + " seconds for token refill (" +
                expectedTokens + " tokens expected)");
        TimeoutHelper.sleepSeconds(waitSeconds);

        // Step 3: Verify recovery - send requests sequentially this time
        int requestsAfterRefill = Math.min(expectedTokens, burstCapacity);
        logStep("Step 3: Verifying recovery - sending " + requestsAfterRefill + " requests");

        int notRateLimitedAfterRefill = 0;
        for (int i = 0; i < requestsAfterRefill; i++) {
            TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
            String body = convertToJson(user);

            HttpResponse<String> response = HttpUtils.post(endpoint, body, null);
            int statusCode = response.statusCode();

            // Count anything that's NOT 429 as success
            if (!HttpUtils.isRateLimited(statusCode)) {
                notRateLimitedAfterRefill++;
            }
        }

        logStep("After refill: " + notRateLimitedAfterRefill + " requests succeeded (not rate-limited)");

        // Most requests should succeed (allow tolerance for timing)
        assertWithTolerance(
                notRateLimitedAfterRefill,
                requestsAfterRefill,
                3,
                "Requests after token refill (should not be rate-limited)"
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
        authResponse = AuthUtils.registerAndGetAuth();
        String user1Token = authResponse.getAccessToken();

        logStep("Creating and authenticating user2");
        authResponse = AuthUtils.registerAndGetAuth();
        String user2Token = authResponse.getAccessToken();

        Assert.assertNotNull(user1Token, "User1 authentication failed");
        Assert.assertNotNull(user2Token, "User2 authentication failed");

        String endpoint = "/api/orders";
        int burstCapacity = 10;

        int requestsToSend = burstCapacity + 3;  // Send more than burst to trigger rate limit

        // User1: Exhaust rate limit with concurrent requests
        logStep("User1: Exhausting rate limit (sending " + requestsToSend + " concurrent requests)");

        AtomicInteger user1RateLimited = new AtomicInteger(0);
        ExecutorService executor1 = Executors.newFixedThreadPool(requestsToSend);
        CountDownLatch latch1 = new CountDownLatch(requestsToSend);

        for (int i = 0; i < requestsToSend; i++) {
            executor1.submit(() -> {
                try {
                    HttpResponse<String> response = HttpUtils.get(endpoint, user1Token);
                    if (HttpUtils.isRateLimited(response.statusCode())) {
                        user1RateLimited.incrementAndGet();
                    }
                } catch (Exception e) {
                    logStep("User1 request failed: " + e.getMessage());
                } finally {
                    latch1.countDown();
                }
            });
        }

        TimeoutHelper.awaitLatch(latch1, TimeoutHelper.Timeouts.THIRTY_SECONDS);
        executor1.shutdown();

        Assert.assertTrue(user1RateLimited.get() > 0,
                "User1 should be rate-limited. Got " + user1RateLimited.get() + " rate-limited out of " + requestsToSend);
        logStep("User1 is rate-limited (" + user1RateLimited.get() + " requests blocked)");

        // User2: Should still work (independent bucket) - send concurrent requests
        logStep("User2: Verifying independent rate limit (sending " + burstCapacity + " concurrent requests)");

        AtomicInteger user2Success = new AtomicInteger(0);
        ExecutorService executor2 = Executors.newFixedThreadPool(burstCapacity);
        CountDownLatch latch2 = new CountDownLatch(burstCapacity);

        for (int i = 0; i < burstCapacity; i++) {
            executor2.submit(() -> {
                try {
                    HttpResponse<String> response = HttpUtils.get(endpoint, user2Token);
                    if (HttpUtils.isSuccessful(response.statusCode())) {
                        user2Success.incrementAndGet();
                    }
                } catch (Exception e) {
                    logStep("User2 request failed: " + e.getMessage());
                } finally {
                    latch2.countDown();
                }
            });
        }

        TimeoutHelper.awaitLatch(latch2, TimeoutHelper.Timeouts.THIRTY_SECONDS);
        executor2.shutdown();

        assertWithTolerance(
                user2Success.get(),
                burstCapacity,
                2,
                "User2 requests (should not be affected by User1's rate limit)"
        );

        logStep("✓ Users have independent rate limits verified\n");
    }
}