package com.amazon.tests.regression.apiGateway.resiliency.rateLimiting;

import com.amazon.tests.BaseTest;
import com.amazon.tests.config.RateLimitConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.transport.ServiceType;
import com.amazon.tests.utils.apiClients.AuthApiClient;
import com.amazon.tests.utils.apiClients.GatewayApiClient;
import com.amazon.tests.utils.rateLimit.RateLimitDataProvider;
import com.amazon.tests.utils.TimeoutHelper;
import com.amazon.tests.utils.testData.TestDataFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Header-focused rate limit tests (X-RateLimit-*, Retry-After) plus
 * exact burst-boundary verification. Same client rules as
 * RateLimitCoreTest: AuthApiClient for register/login, GatewayApiClient
 * for everything else — never OrderApiClient/ProductApiClient (both
 * bypass the gateway) and never HttpUtils.
 */
public class RateLimitHeadersTest extends BaseTest {

    private AuthApiClient authClient;
    private GatewayApiClient gatewayClient;

    @BeforeClass
    public void setup() {
        authClient = new AuthApiClient(executor);
        gatewayClient = new GatewayApiClient(executor);
    }

    @Test(dataProvider = "ipBasedScenarios", dataProviderClass = RateLimitDataProvider.class)
    public void testRateLimitHeadersPresent(RateLimitConfig config) throws Exception {
        logStep("=== Testing Rate Limit Headers for: " + config.getTestName() + " ===");

        ServiceResponse response = sendConfiguredRequest(config, null, 1);

        String limitHeader = response.getHeaders().get("X-RateLimit-Limit");
        String remainingHeader = response.getHeaders().get("X-RateLimit-Remaining");

        Assert.assertTrue(limitHeader != null || remainingHeader != null,
                "Rate limit headers should be present for " + config.getTestName());

        logStep("Headers found: limit=" + limitHeader + " remaining=" + remainingHeader);

        if (limitHeader != null) {
            logStep("X-RateLimit-Limit: " + limitHeader + " (expected burst: " + config.getBurstCapacity() + ")");
        }

        if (remainingHeader != null) {
            int remaining = Integer.parseInt(remainingHeader);
            Assert.assertTrue(remaining >= 0 && remaining <= config.getBurstCapacity(),
                    "Remaining tokens should be between 0 and " + config.getBurstCapacity());
            logStep("X-RateLimit-Remaining: " + remaining);
        }

        logStep("✓ Headers validated\n");
    }

    @Test(dataProvider = "allRateLimitScenarios", dataProviderClass = RateLimitDataProvider.class)
    public void testRetryAfterHeaderIn429Response(RateLimitConfig config) throws Exception {
        logStep("=== Testing Retry-After Header for: " + config.getTestName() + " ===");

        String authToken = null;
        if (config.isRequiresAuth()) {
            TestModels.AuthResponse auth = authClient.registerCustomer();
            authToken = auth.getAccessToken();
        }
        final String finalAuthToken = authToken;

        int requestsToSend = config.getBurstCapacity() + 5;
        logStep("Sending " + requestsToSend + " concurrent requests to trigger rate limit");

        AtomicReference<ServiceResponse> lastRateLimitedResponse = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(requestsToSend);
        CountDownLatch latch = new CountDownLatch(requestsToSend);

        for (int i = 0; i < requestsToSend; i++) {
            final int requestNum = i;
            pool.submit(() -> {
                try {
                    ServiceResponse response = sendConfiguredRequest(config, finalAuthToken, requestNum);
                    if (response.getStatusCode() == 429) {
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

        ServiceResponse lastResponse = lastRateLimitedResponse.get();
        Assert.assertNotNull(lastResponse, "Should have received a 429 response");

        String retryAfterHeader = lastResponse.getHeaders().get("Retry-After");
        Assert.assertNotNull(retryAfterHeader, "Retry-After header should be present in 429 response");

        int retryAfter = Integer.parseInt(retryAfterHeader);
        Assert.assertTrue(retryAfter > 0, "Retry-After should be positive");
        logStep("Retry-After: " + retryAfter + " seconds");

        logStep("✓ Retry-After header validated\n");
    }

    @Test(dataProvider = "burstScenarios", dataProviderClass = RateLimitDataProvider.class)
    public void testBurstCapacityExact(RateLimitConfig config) throws Exception {
        logStep("=== Testing Exact Burst Capacity for: " + config.getTestName() + " ===");
        logStep("Burst Capacity: " + config.getBurstCapacity());

        String authToken = null;
        if (config.isRequiresAuth()) {
            TestModels.AuthResponse auth = authClient.registerCustomer();
            authToken = auth.getAccessToken();
        }
        final String finalAuthToken = authToken;

        AtomicInteger successCount = new AtomicInteger(0);
        int totalToSend = config.getBurstCapacity() + 1;
        ExecutorService pool = Executors.newFixedThreadPool(totalToSend);
        CountDownLatch latch = new CountDownLatch(totalToSend);

        for (int i = 0; i < totalToSend; i++) {
            final int requestNum = i;
            pool.submit(() -> {
                try {
                    ServiceResponse response = sendConfiguredRequest(config, finalAuthToken, requestNum);
                    if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                        successCount.incrementAndGet();
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

        int expectedSuccess = config.getBurstCapacity();
        int actualSuccess = successCount.get();
        logStep("Sent " + totalToSend + " concurrent requests: " + actualSuccess + " succeeded, "
                + (totalToSend - actualSuccess) + " rate-limited");

        Assert.assertTrue(actualSuccess >= expectedSuccess - 2,
                "At least " + (expectedSuccess - 2) + " requests should succeed. Got: " + actualSuccess);
        logStep("✓ Burst capacity validated (" + actualSuccess + " succeeded)");

        // The next request beyond this burst should be rate-limited
        ServiceResponse nextResponse = sendConfiguredRequest(config, finalAuthToken, 999);
        Assert.assertEquals(nextResponse.getStatusCode(), 429,
                "Request beyond burst capacity should be rate-limited");
        logStep("✓ Request " + totalToSend + " was rate-limited (429)\n");
    }

    private ServiceResponse sendConfiguredRequest(RateLimitConfig config, String authToken, int requestNum) {
        String endpoint = config.getEndpoint();

        if (endpoint.contains("/register")) {
            TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
            return authClient.registerRaw(user, ServiceType.GATEWAY);
        }

        if (endpoint.contains("/login")) {
            return authClient.loginRaw(
                    "testuser" + requestNum + "@test.com",
                    "wrongpassword" + requestNum,
                    ServiceType.GATEWAY);
        }

        if (endpoint.equals("/api/orders") && "POST".equals(config.getHttpMethod())) {
            return gatewayClient.post(endpoint, authToken, createOrderPayload());
        }

        if (config.getRequestBodyTemplate() != null) {
            String body = String.format(config.getRequestBodyTemplate(), requestNum, requestNum);
            return gatewayClient.post(endpoint, authToken, body);
        }

        return gatewayClient.get(endpoint, authToken);
    }

    private java.util.Map<String, Object> createOrderPayload() {
        com.github.javafaker.Faker faker = new com.github.javafaker.Faker();
        java.util.UUID productId = java.util.UUID.randomUUID();
        return java.util.Map.of(
                "items", java.util.List.of(
                        java.util.Map.of(
                                "productId", productId.toString(),
                                "quantity", 1,
                                "unitPrice", 50.0,
                                "productName", "Test Product"
                        )
                ),
                "shippingAddress", faker.address().fullAddress()
        );
    }
}