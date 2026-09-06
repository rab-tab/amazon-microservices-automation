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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Merged header coverage — was two separate tests
 * (testRateLimitHeadersPresent, testRetryAfterHeaderIn429Response), each
 * firing its own burst via a private, drifted copy of dispatch logic
 * (still had the pre-fix random/unregistered login credentials bug).
 * Now one burst, dispatched via the shared RateLimitUtil, checks both:
 *   1. Rate-limit headers are present on a normal (non-429) response.
 *   2. Retry-After is present and positive on the 429 response the same
 *      burst produces.
 */
public class RateLimitHeadersTest extends BaseTest {

    private RateLimitUtil rateLimitUtil;

    @BeforeClass
    public void setup() {
        AuthApiClient authClient = new AuthApiClient(executor);
        GatewayApiClient gatewayClient = new GatewayApiClient(executor);
        rateLimitUtil = new RateLimitUtil(authClient, gatewayClient);
    }

    @Test(dataProvider = "allRateLimitScenarios", dataProviderClass = RateLimitDataProvider.class)
    public void testRateLimitHeaders(RateLimitConfig config) throws Exception {
        logStep("=== Testing Rate Limit Headers for: " + config.getTestName() + " ===");

        String authToken = null;
        if (config.isRequiresAuth()) {
            TestModels.AuthResponse auth = new AuthApiClient(executor).registerCustomer();
            authToken = auth.getAccessToken();
        }
        final String finalAuthToken = authToken;

        int requestsToSend = config.getBurstCapacity() + 5;
        logStep("Sending " + requestsToSend + " concurrent requests to trigger rate limit");

        AtomicReference<ServiceResponse> firstSuccessResponse = new AtomicReference<>();
        AtomicReference<ServiceResponse> lastRateLimitedResponse = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(requestsToSend);
        CountDownLatch latch = new CountDownLatch(requestsToSend);

        for (int i = 0; i < requestsToSend; i++) {
            final int requestNum = i;
            pool.submit(() -> {
                try {
                    ServiceResponse response = rateLimitUtil.sendConfiguredRequest(config, finalAuthToken, requestNum);
                    int statusCode = response.getStatusCode();

                    if (rateLimitUtil.isSuccessful(statusCode)) {
                        firstSuccessResponse.compareAndSet(null, response);
                    } else if (statusCode == 429) {
                        lastRateLimitedResponse.set(response);
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

        // --- Check 1: headers present on a normal (successful) response ---
        ServiceResponse successResponse = firstSuccessResponse.get();
        Assert.assertNotNull(successResponse, "Should have received at least one successful response");

        RateLimitUtil.RateLimitHeaders successHeaders = rateLimitUtil.extractRateLimitHeaders(successResponse);
        Assert.assertTrue(successHeaders.limit() != null || successHeaders.remaining() != null,
                "Rate limit headers should be present for " + config.getTestName());
        logStep("Success response headers: limit=" + successHeaders.limit()
                + " remaining=" + successHeaders.remaining());

        if (successHeaders.remaining() != null) {
            int remaining = Integer.parseInt(successHeaders.remaining());
            Assert.assertTrue(remaining >= 0 && remaining <= config.getBurstCapacity(),
                    "Remaining tokens should be between 0 and " + config.getBurstCapacity());
        }

        // --- Check 2: Retry-After present and positive on the 429 ---
        ServiceResponse rateLimitedResponse = lastRateLimitedResponse.get();
        Assert.assertNotNull(rateLimitedResponse, "Should have received a 429 response");

        RateLimitUtil.RateLimitHeaders limitedHeaders = rateLimitUtil.extractRateLimitHeaders(rateLimitedResponse);
        Assert.assertNotNull(limitedHeaders.retryAfter(), "Retry-After header should be present in 429 response");

        int retryAfter = Integer.parseInt(limitedHeaders.retryAfter());
        Assert.assertTrue(retryAfter > 0, "Retry-After should be positive");
        logStep("Retry-After: " + retryAfter + " seconds");

        logStep("✓ Rate limit headers and Retry-After validated\n");
    }
}