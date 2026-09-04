package com.amazon.tests.regression.apiGateway.resiliency.rateLimiting;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.transport.ServiceType;
import com.amazon.tests.utils.apiClients.AuthApiClient;
import com.amazon.tests.utils.apiClients.GatewayApiClient;
import com.amazon.tests.utils.TimeoutHelper;
import com.amazon.tests.utils.testData.TestDataFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate limiting scenarios not covered by the parameterized
 * RateLimitCoreTest / RateLimitHeadersTest classes. See those files'
 * class-level comments for what was deduplicated from the original
 * three-file layout (RateLimitingTest, RateLimitHeadersTest,
 * IPBasedRateLimitingTests).
 *
 * Client usage: AuthApiClient for register/login, GatewayApiClient for
 * everything else — never OrderApiClient/ProductApiClient (both bypass
 * the gateway via hardcoded targetService(ServiceType.ORDER/.PRODUCT),
 * which would make rate limiting untestable) and never HttpUtils.
 */
public class RateLimitBehaviorTest extends BaseTest {

    private static String validToken;
    private static String userId;

    private AuthApiClient authClient;
    private GatewayApiClient gatewayClient;

    @BeforeClass
    public void setupAuthentication() {
        authClient = new AuthApiClient(executor);
        gatewayClient = new GatewayApiClient(executor);

        TestModels.AuthResponse authResponse = authClient.registerCustomer();
        validToken = authResponse.getAccessToken();
        userId = authResponse.getUser().getId();
        logStep("Authentication successful - User ID: " + userId);
    }

    // ============================================================
    // Sustained rate (replenish rate over time)
    // ============================================================

    @Test(priority = 1, description = "Verify sustained rate matches replenish rate over time")
    public void testSustainedRateMatchesReplenishRate() throws Exception {
        logStep("=== Sustained Rate Limit (Replenish Rate) ===");

        int replenishRate = 5; // req/sec
        int testSeconds = 4;
        int requestsPerSecond = replenishRate + 3;

        logStep("Waiting 2 seconds to ensure token bucket is full...");
        Thread.sleep(2000);

        int successCount = 0;
        int rateLimitedCount = 0;

        for (int sec = 0; sec < testSeconds; sec++) {
            long secStart = System.currentTimeMillis();

            for (int i = 0; i < requestsPerSecond; i++) {
                TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                ServiceResponse response = authClient.registerRaw(user, ServiceType.GATEWAY);

                if (response.getStatusCode() == 201) {
                    successCount++;
                } else if (response.getStatusCode() == 429) {
                    rateLimitedCount++;
                }
            }

            long elapsed = System.currentTimeMillis() - secStart;
            if (elapsed < 1000) {
                Thread.sleep(1000 - elapsed);
            }
        }

        int expectedSuccess = replenishRate * testSeconds;
        logStep("Results: " + successCount + " success, " + rateLimitedCount + " rate-limited " +
                "(expected ~" + expectedSuccess + " successful)");

        assertWithTolerance(successCount, expectedSuccess, 4,
                "Sustained rate over " + testSeconds + " seconds");
        Assert.assertTrue(rateLimitedCount >= 8,
                "Should rate-limit excess requests, got " + rateLimitedCount);

        logStep("✅ PASSED\n");
    }

    // ============================================================
    // Rate limit recovery (token refill)
    // ============================================================

    @Test(priority = 2, description = "Verify token bucket refills after waiting")
    public void testRateLimitRecoveryAfterWait() throws Exception {
        logStep("=== Rate Limit Recovery (Token Refill) ===");

        int burstCapacity = 10;
        int replenishRate = 5;

        logStep("Step 1: Exhausting token bucket (15 concurrent requests)");
        AtomicInteger rateLimitedInStep1 = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(15);
        CountDownLatch latch = new CountDownLatch(15);

        for (int i = 0; i < 15; i++) {
            pool.submit(() -> {
                try {
                    TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                    ServiceResponse response = authClient.registerRaw(user, ServiceType.GATEWAY);
                    if (response.getStatusCode() == 429) {
                        rateLimitedInStep1.incrementAndGet();
                    }
                } catch (Exception e) {
                    logStep("Request failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        TimeoutHelper.awaitLatch(latch, TimeoutHelper.Timeouts.THIRTY_SECONDS);
        pool.shutdown();

        Assert.assertTrue(rateLimitedInStep1.get() > 0,
                "Should have hit rate limit, got " + rateLimitedInStep1.get());
        logStep("Bucket exhausted (" + rateLimitedInStep1.get() + " requests rate-limited)");

        int waitSeconds = 3;
        int expectedTokens = waitSeconds * replenishRate;
        logStep("Step 2: Waiting " + waitSeconds + "s for refill (" + expectedTokens + " tokens expected)");
        Thread.sleep(waitSeconds * 1000L);

        int requestsAfterRefill = Math.min(expectedTokens, burstCapacity);
        logStep("Step 3: Sending " + requestsAfterRefill + " requests after refill");

        int successAfterRefill = 0;
        for (int i = 0; i < requestsAfterRefill; i++) {
            TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
            ServiceResponse response = authClient.registerRaw(user, ServiceType.GATEWAY);
            if (response.getStatusCode() != 429) {
                successAfterRefill++;
            }
        }

        logStep("After refill: " + successAfterRefill + " requests succeeded");
        assertWithTolerance(successAfterRefill, requestsAfterRefill, 3,
                "Requests after token refill");

        logStep("✅ PASSED - Rate limit recovered after waiting\n");
    }

    // ============================================================
    // Independent buckets — per-user
    // ============================================================

    @Test(priority = 3, description = "Verify user-based rate limit buckets are independent per user")
    public void testUsersHaveIndependentRateLimits() throws Exception {
        logStep("=== Independent Rate Limits Test (per-user) ===");

        TestModels.AuthResponse user1Auth = authClient.registerCustomer();
        String user1Token = user1Auth.getAccessToken();

        TestModels.AuthResponse user2Auth = authClient.registerCustomer();
        String user2Token = user2Auth.getAccessToken();

        String endpoint = "/api/orders";
        int burstCapacity = 10;
        int requestsToSend = burstCapacity + 3;

        logStep("User1: Exhausting rate limit (" + requestsToSend + " concurrent requests)");
        AtomicInteger user1RateLimited = new AtomicInteger(0);
        ExecutorService executor1 = Executors.newFixedThreadPool(requestsToSend);
        CountDownLatch latch1 = new CountDownLatch(requestsToSend);

        for (int i = 0; i < requestsToSend; i++) {
            executor1.submit(() -> {
                try {
                    ServiceResponse response = gatewayClient.get(endpoint, user1Token);
                    if (response.getStatusCode() == 429) {
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
                "User1 should be rate-limited, got " + user1RateLimited.get() + " of " + requestsToSend);
        logStep("User1 rate-limited (" + user1RateLimited.get() + " blocked)");

        logStep("User2: Verifying independent bucket (" + burstCapacity + " concurrent requests)");
        AtomicInteger user2Success = new AtomicInteger(0);
        ExecutorService executor2 = Executors.newFixedThreadPool(burstCapacity);
        CountDownLatch latch2 = new CountDownLatch(burstCapacity);

        for (int i = 0; i < burstCapacity; i++) {
            executor2.submit(() -> {
                try {
                    ServiceResponse response = gatewayClient.get(endpoint, user2Token);
                    if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
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

        assertWithTolerance(user2Success.get(), burstCapacity, 2,
                "User2 requests (should be unaffected by User1's rate limit)");

        logStep("✅ PASSED - Users have independent rate limit buckets\n");
    }

    @Test(priority = 4, description = "Verify user-based endpoints are unaffected when an IP-based bucket is exhausted")
    public void testIpBasedAndUserBasedBucketsAreIndependent() throws Exception {
        logStep("=== IP-based vs User-based Bucket Independence ===");

        logStep("Exhausting IP-based endpoint: /api/users/register");
        for (int i = 0; i < 15; i++) {
            TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
            authClient.registerRaw(user, ServiceType.GATEWAY);
        }

        logStep("Testing user-based endpoint (different key resolver): /api/orders");
        ServiceResponse userBasedResponse = gatewayClient.get("/api/orders", validToken);

        Assert.assertTrue(userBasedResponse.getStatusCode() >= 200 && userBasedResponse.getStatusCode() < 300,
                "User-based endpoint should work even when the IP-based bucket is exhausted, " +
                        "got " + userBasedResponse.getStatusCode());

        logStep("✅ PASSED - IP-based and user-based rate limit keys are independent\n");
    }

    // ============================================================
    // Cross-endpoint independence within the same key type (IP)
    // ============================================================

    @Test(priority = 5, description = "Different IP-based endpoints have independent rate limit buckets")
    public void testDifferentIpBasedEndpointsIndependentLimits() throws Exception {
        logStep("=== Different IP-Based Endpoints — Independent Buckets ===");

        logStep("Step 1: Exhausting rate limit on /api/users/register");
        AtomicInteger registerLimited = new AtomicInteger(0);
        ExecutorService executor1 = Executors.newFixedThreadPool(15);
        CountDownLatch latch1 = new CountDownLatch(15);

        for (int i = 0; i < 15; i++) {
            executor1.submit(() -> {
                try {
                    TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                    ServiceResponse response = authClient.registerRaw(user, ServiceType.GATEWAY);
                    if (response.getStatusCode() == 429) {
                        registerLimited.incrementAndGet();
                    }
                } catch (Exception e) {
                    logStep("Request failed: " + e.getMessage());
                } finally {
                    latch1.countDown();
                }
            });
        }

        TimeoutHelper.awaitLatch(latch1, TimeoutHelper.Timeouts.THIRTY_SECONDS);
        executor1.shutdown();

        Assert.assertTrue(registerLimited.get() > 0, "Should have triggered rate limit on registration");
        logStep("Registration endpoint rate limited (" + registerLimited.get() + " blocked)");

        logStep("Step 2: Testing /api/users/login (separate route, own RequestRateLimiter config — " +
                "expected independent bucket, not shared with registration)");

        TestModels.AuthResponse authForLoginCheck = authClient.registerCustomer();
        ServiceResponse loginResponse = authClient.loginRaw(
                authForLoginCheck.getUser().getEmail(), "wrongpassword-deliberate", ServiceType.GATEWAY);

        logStep("Login response status: " + loginResponse.getStatusCode());

        Assert.assertNotEquals(loginResponse.getStatusCode(), 429,
                "Login should have its own independent rate limit bucket, separate from registration's");
        Assert.assertTrue(loginResponse.getStatusCode() == 200 || loginResponse.getStatusCode() == 401,
                "Login should be reachable (200 or 401 for wrong password), got " + loginResponse.getStatusCode());

        logStep("✅ PASSED - Registration and login have independent buckets\n");
    }

    // ============================================================
    // Brute-force resistance
    // ============================================================

    @Test(priority = 6, description = "Rate limiter prevents brute force login attacks")
    public void testBruteForceAttackPrevention() throws Exception {
        logStep("=== Brute Force Attack Prevention ===");

        int attackAttempts = 20;
        AtomicInteger failedAuth = new AtomicInteger(0);
        AtomicInteger rateLimited = new AtomicInteger(0);

        TestModels.AuthResponse targetUser = authClient.registerCustomer();
        String targetEmail = targetUser.getUser().getEmail();

        ExecutorService pool = Executors.newFixedThreadPool(attackAttempts);
        CountDownLatch latch = new CountDownLatch(attackAttempts);

        logStep("Launching " + attackAttempts + " concurrent password attempts against a fixed target...");

        for (int i = 0; i < attackAttempts; i++) {
            final int attempt = i;
            pool.submit(() -> {
                try {
                    ServiceResponse response = authClient.loginRaw(
                            targetEmail, "guess-" + attempt, ServiceType.GATEWAY);

                    if (response.getStatusCode() == 401) failedAuth.incrementAndGet();
                    else if (response.getStatusCode() == 429) rateLimited.incrementAndGet();
                } catch (Exception e) {
                    logStep("Request failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        TimeoutHelper.awaitLatch(latch, TimeoutHelper.Timeouts.THIRTY_SECONDS);
        pool.shutdown();

        logStep("Results: " + failedAuth.get() + " failed auth, " + rateLimited.get() + " blocked");
        Assert.assertTrue(rateLimited.get() > 0, "SECURITY: brute force was not rate limited");

        logStep("✅ PASSED - " + rateLimited.get() + " attack attempts blocked\n");
    }

    // ============================================================
    // Health check exclusion
    // ============================================================

    @Test(priority = 7, description = "Health check endpoint is not affected by rate limiting on other routes")
    public void testHealthCheckNotRateLimited() throws Exception {
        logStep("=== Health Check Excluded From Rate Limiting ===");

        TestModels.AuthResponse dummyUser = authClient.registerCustomer();

        logStep("Exhausting rate limit on /api/users/login...");
        for (int i = 0; i < 15; i++) {
            authClient.loginRaw(dummyUser.getUser().getEmail(), "wrongpassword" + i, ServiceType.GATEWAY);
        }

        logStep("Testing health check endpoint...");
        ServiceResponse healthResponse = gatewayClient.get("/api/users/health", null);

        Assert.assertEquals(healthResponse.getStatusCode(), 200,
                "Health check should not be rate limited");

        logStep("✅ PASSED - Health check excluded from rate limiting\n");
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void assertWithTolerance(int actual, int expected, int tolerance, String message) {
        int diff = Math.abs(actual - expected);
        if (diff <= tolerance) {
            logStep(String.format("  ✓ %s: Expected %d (±%d), got %d", message, expected, tolerance, actual));
        } else {
            String errorMsg = String.format("%s: Expected %d (±%d), got %d (diff: %d)",
                    message, expected, tolerance, actual, diff);
            logStep("  ✗ " + errorMsg);
            Assert.fail(errorMsg);
        }
    }
}