package com.amazon.tests.regression.apiGateway.resiliency.rateLimiting;

import com.amazon.tests.BaseTest;
import com.amazon.tests.config.RateLimitConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.TimeoutHelper;
import com.amazon.tests.utils.apiClients.AuthApiClient;
import com.amazon.tests.utils.apiClients.GatewayApiClient;
import com.amazon.tests.utils.rateLimit.RateLimitDataProvider;
import com.amazon.tests.utils.rateLimit.RateLimitUtil;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class RateLimitCoreTest extends BaseTest {

    private static String validToken;
    private static String userId;

    private RateLimitUtil rateLimitUtil;

    @BeforeClass
    public void setupAuthentication() {
        logStep("=== Setting up authentication for rate limit tests ===");

        AuthApiClient authClient = new AuthApiClient(executor);
        GatewayApiClient gatewayClient = new GatewayApiClient(executor);
        rateLimitUtil = new RateLimitUtil(authClient, gatewayClient);

        TestModels.AuthResponse authResponse = authClient.registerCustomer();
        validToken = authResponse.getAccessToken();
        userId = authResponse.getUser().getId();

        logStep("Authentication successful - User ID: " + userId);
        setAuthInConfigs(validToken, userId);
    }

    private void setAuthInConfigs(String token, String userId) {
        RateLimitConfig.IPBased.REGISTRATION.setAuthToken(token);
        RateLimitConfig.IPBased.REGISTRATION.setUserId(userId);
        RateLimitConfig.IPBased.LOGIN.setAuthToken(token);
        RateLimitConfig.IPBased.LOGIN.setUserId(userId);
        RateLimitConfig.IPBased.PRODUCTS_LIST.setAuthToken(token);
        RateLimitConfig.IPBased.PRODUCTS_LIST.setUserId(userId);
        RateLimitConfig.UserBased.ORDER_LIST.setAuthToken(token);
        RateLimitConfig.UserBased.ORDER_LIST.setUserId(userId);
        RateLimitConfig.UserBased.ORDER_CREATION.setAuthToken(token);
        RateLimitConfig.UserBased.ORDER_CREATION.setUserId(userId);
        RateLimitConfig.UserBased.PROFILE_UPDATE.setAuthToken(token);
        RateLimitConfig.UserBased.PROFILE_UPDATE.setUserId(userId);
    }

    @Test(dataProvider = "ipBasedScenarios", dataProviderClass = RateLimitDataProvider.class, priority = 1,enabled = false)
    public void testIPBasedRateLimiting(RateLimitConfig config) throws Exception {
        String token = config.isRequiresAuth() ? config.getAuthToken() : null;
        runRateLimitTest(config, token);
    }

    @Test(dataProvider = "userBasedScenarios", dataProviderClass = RateLimitDataProvider.class, priority = 2)
    public void testUserBasedRateLimiting(RateLimitConfig config) throws Exception {
        runRateLimitTest(config, config.getAuthToken());
    }

    private void runRateLimitTest(RateLimitConfig config, String authToken) throws Exception {
        logStep("=".repeat(60));
        logStep("Test: " + config.getTestName());
        logStep("Endpoint: " + config.getHttpMethod() + " " + config.getEndpoint());
        logStep("Rate Limit: " + config.getReplenishRate() + " req/sec, burst: " + config.getBurstCapacity());
        logStep("Requests: " + config.getTotalRequests() + " concurrent");
        logStep("=".repeat(60));

        Assert.assertTrue(config.isValid(), "Invalid test configuration");

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rateLimitCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(config.getThreadPoolSize());
        CountDownLatch latch = new CountDownLatch(config.getTotalRequests());
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < config.getTotalRequests(); i++) {
            final int requestNum = i;
            pool.submit(() -> {
                try {
                    ServiceResponse response = rateLimitUtil.sendConfiguredRequest(config, authToken, requestNum);
                    int statusCode = response.getStatusCode();

                    if (rateLimitUtil.isSuccessful(statusCode)) {
                        successCount.incrementAndGet();
                    } else if (statusCode == 429) {
                        rateLimitCount.incrementAndGet();
                        rateLimitUtil.verifyRateLimitHeaders(response, this::logStep);
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

        boolean completed = TimeoutHelper.awaitLatch(latch, TimeoutHelper.Timeouts.THIRTY_SECONDS);
        Assert.assertTrue(completed, "Test timed out waiting for requests to complete");

        pool.shutdown();
        boolean terminated = TimeoutHelper.awaitTermination(pool, TimeoutHelper.Timeouts.FIVE_SECONDS);
        if (!terminated) {
            pool.shutdownNow();
            logStep("WARNING: Executor did not terminate gracefully");
        }

        long duration = System.currentTimeMillis() - startTime;

        logStep("\nResults:");
        logStep("  ✓ Success: " + successCount.get());
        logStep("  ✗ Rate Limited (429): " + rateLimitCount.get());
        logStep("  ? Other Errors: " + otherErrorCount.get());
        logStep("  Duration: " + duration + "ms");

        rateLimitUtil.assertWithTolerance(successCount.get(), config.getExpectedSuccess(), config.getTolerance(),
                "Success count mismatch for " + config.getTestName(), this::logStep);
        rateLimitUtil.assertWithTolerance(rateLimitCount.get(), config.getExpectedRejected(), config.getTolerance(),
                "Rate limit count mismatch for " + config.getTestName(), this::logStep);

        int totalProcessed = successCount.get() + rateLimitCount.get() + otherErrorCount.get();
        Assert.assertEquals(totalProcessed, config.getTotalRequests(), "Total processed doesn't match sent requests");

        logStep("✓ Test PASSED\n");
    }
}