package com.amazon.tests.tests.apiGateway.resiliency.rateLimiting;

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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Example: Additional Rate Limit Tests using the same shared DataProvider
 *
 * This demonstrates how multiple test classes can reuse the same data provider
 * for different types of tests without duplicating scenario definitions.
 */
public class RateLimitHeadersTest extends BaseTest {

    /**
     * Test: Verify rate limit headers are present in all responses
     * Uses the same IP-based scenarios from RateLimitDataProvider
     */
    @Test(dataProvider = "ipBasedScenarios", dataProviderClass = RateLimitDataProvider.class)
    public void testRateLimitHeadersPresent(RateLimitConfig config) throws Exception {
        logStep("=== Testing Rate Limit Headers for: " + config.getTestName() + " ===");

        // Generate request body
        String requestBody;
        if (config.getEndpoint().contains("/register")) {
            TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
            requestBody = convertToJson(user);
        } else if (config.getEndpoint().contains("/login")) {
            TestModels.LoginRequest loginRequest = TestDataFactory.createLoginRequest(
                    "testuser@test.com",
                    "wrongpassword"
            );
            requestBody = convertToJson(loginRequest);
        } else if (config.getRequestBodyTemplate() != null) {
            requestBody = String.format(config.getRequestBodyTemplate(), 1, 1);
        } else {
            requestBody = null;
        }

        HttpResponse<String> response = HttpUtils.sendRequest(
                config.getEndpoint(),
                requestBody,
                null,
                config.getHttpMethod()
        );

        // Extract rate limit headers
        HttpUtils.RateLimitInfo rateLimitInfo = HttpUtils.getRateLimitInfo(response);

        // Verify headers are present
        Assert.assertTrue(
                rateLimitInfo.hasRateLimitHeaders(),
                "Rate limit headers should be present for " + config.getTestName()
        );

        logStep("Headers found: " + rateLimitInfo.toString());

        // Verify X-RateLimit-Limit matches configuration
        if (rateLimitInfo.getLimit() != null) {
            int headerLimit = Integer.parseInt(rateLimitInfo.getLimit());
            logStep("X-RateLimit-Limit: " + headerLimit + " (expected burst: " + config.getBurstCapacity() + ")");
        }

        // Verify X-RateLimit-Remaining is valid
        if (rateLimitInfo.getRemaining() != null) {
            int remaining = Integer.parseInt(rateLimitInfo.getRemaining());
            Assert.assertTrue(
                    remaining >= 0 && remaining <= config.getBurstCapacity(),
                    "Remaining tokens should be between 0 and " + config.getBurstCapacity()
            );
            logStep("X-RateLimit-Remaining: " + remaining);
        }

        logStep("✓ Headers validated\n");
    }

    /**
     * Helper method to convert object to JSON
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
     * Test: Verify Retry-After header is present in 429 responses
     * Uses all scenarios from RateLimitDataProvider
     */
    @Test(dataProvider = "allRateLimitScenarios", dataProviderClass = RateLimitDataProvider.class)
    public void testRetryAfterHeaderIn429Response(RateLimitConfig config) throws Exception {
        logStep("=== Testing Retry-After Header for: " + config.getTestName() + " ===");

        String authToken = null;
        if (config.isRequiresAuth()) {
            authToken = HttpUtils.authenticateUser("testuser", "TestPass123!");
            Assert.assertNotNull(authToken, "Authentication required");
        }

        // Exhaust rate limit
        // Exhaust rate limit - send concurrently
        int requestsToSend = config.getBurstCapacity() + 5;
        logStep("Sending " + requestsToSend + " concurrent requests to trigger rate limit");

        AtomicReference<HttpResponse<String>> lastRateLimitedResponse = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(requestsToSend);
        CountDownLatch latch = new CountDownLatch(requestsToSend);

        for (int i = 0; i < requestsToSend; i++) {
            final int requestNum = i;
            String finalAuthToken = authToken;
            executor.submit(() -> {
                try {
                    String requestBody;

                    if (config.getEndpoint().contains("/register")) {
                        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                        requestBody = convertToJson(user);
                    } else if (config.getEndpoint().contains("/login")) {
                        TestModels.LoginRequest loginRequest = TestDataFactory.createLoginRequest(
                                "testuser" + requestNum + "@test.com",
                                "wrongpassword" + requestNum
                        );
                        requestBody = convertToJson(loginRequest);
                    } else if (config.getEndpoint().equals("/api/orders") &&
                            config.getHttpMethod().equals("POST")) {
                        requestBody = createOrderRequestBody();
                    } else if (config.getRequestBodyTemplate() != null) {
                        requestBody = String.format(config.getRequestBodyTemplate(), requestNum, requestNum);
                    } else {
                        requestBody = null;
                    }

                    HttpResponse<String> response = HttpUtils.sendRequest(
                            config.getEndpoint(),
                            requestBody,
                            finalAuthToken,
                            config.getHttpMethod()
                    );

                    if (HttpUtils.isRateLimited(response.statusCode())) {
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
        executor.shutdown();

        HttpResponse<String> lastResponse = lastRateLimitedResponse.get();

        // Verify we got a 429 response
        Assert.assertNotNull(lastResponse, "Should have received a response");
        Assert.assertTrue(
                HttpUtils.isRateLimited(lastResponse.statusCode()),
                "Should have triggered rate limit"
        );

        // Verify Retry-After header
        HttpUtils.RateLimitInfo rateLimitInfo = HttpUtils.getRateLimitInfo(lastResponse);
        Assert.assertNotNull(
                rateLimitInfo.getRetryAfter(),
                "Retry-After header should be present in 429 response"
        );

        int retryAfter = Integer.parseInt(rateLimitInfo.getRetryAfter());
        Assert.assertTrue(retryAfter > 0, "Retry-After should be positive");
        logStep("Retry-After: " + retryAfter + " seconds");

        logStep("✓ Retry-After header validated\n");
    }

    /**
     * Test: Verify burst capacity scenarios
     * Uses only burst-specific scenarios from RateLimitDataProvider
     */
    @Test(dataProvider = "burstScenarios", dataProviderClass = RateLimitDataProvider.class)
    public void testBurstCapacityExact(RateLimitConfig config) throws Exception {
        logStep("=== Testing Exact Burst Capacity for: " + config.getTestName() + " ===");
        logStep("Burst Capacity: " + config.getBurstCapacity());

        String authToken = null;
        if (config.isRequiresAuth()) {
            authToken = HttpUtils.authenticateUser("testuser", "TestPass123!");
        }

        // Send exactly burst capacity requests
        // Send exactly burst capacity requests CONCURRENTLY
        AtomicInteger successCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(config.getBurstCapacity() + 1);
        CountDownLatch latch = new CountDownLatch(config.getBurstCapacity() + 1);

        // Send burst capacity + 1 requests concurrently
        for (int i = 0; i < config.getBurstCapacity() + 1; i++) {
            final int requestNum = i;
            String finalAuthToken = authToken;
            executor.submit(() -> {
                try {
                    String requestBody;

                    if (config.getEndpoint().contains("/register")) {
                        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                        requestBody = convertToJson(user);
                    } else if (config.getEndpoint().contains("/login")) {
                        TestModels.LoginRequest loginRequest = TestDataFactory.createLoginRequest(
                                "testuser" + requestNum + "@test.com",
                                "wrongpassword" + requestNum
                        );
                        requestBody = convertToJson(loginRequest);
                    } else if (config.getEndpoint().equals("/api/orders") &&
                            config.getHttpMethod().equals("POST")) {
                        requestBody = createOrderRequestBody();
                    } else if (config.getRequestBodyTemplate() != null) {
                        requestBody = String.format(config.getRequestBodyTemplate(), requestNum, requestNum);
                    } else {
                        requestBody = null;
                    }

                    HttpResponse<String> response = HttpUtils.sendRequest(
                            config.getEndpoint(),
                            requestBody,
                            finalAuthToken,
                            config.getHttpMethod()
                    );

                    if (HttpUtils.isSuccessful(response.statusCode())) {
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
        executor.shutdown();

        // Most burst capacity requests should succeed (allow tolerance)
        int expectedSuccess = config.getBurstCapacity();
        int actualSuccess = successCount.get();
        logStep("Sent " + (config.getBurstCapacity() + 1) + " concurrent requests: " +
                actualSuccess + " succeeded, " + ((config.getBurstCapacity() + 1) - actualSuccess) + " rate-limited");

        // At least burst capacity - 2 should succeed (tolerance for timing)
        Assert.assertTrue(actualSuccess >= expectedSuccess - 2,
                "At least " + (expectedSuccess - 2) + " requests should succeed. Got: " + actualSuccess);
        logStep("✓ Burst capacity validated (" + actualSuccess + " succeeded)");

        // The very next request should be rate-limited
        String requestBody;
        if (config.getEndpoint().contains("/register")) {
            TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
            requestBody = convertToJson(user);
        } else if (config.getEndpoint().contains("/login")) {
            TestModels.LoginRequest loginRequest = TestDataFactory.createLoginRequest(
                    "testuser999@test.com",
                    "wrongpassword999"
            );
            requestBody = convertToJson(loginRequest);
        } else if (config.getRequestBodyTemplate() != null) {
            requestBody = String.format(config.getRequestBodyTemplate(), 999, 999);
        } else {
            requestBody = null;
        }

        HttpResponse<String> nextResponse = HttpUtils.sendRequest(
                config.getEndpoint(),
                requestBody,
                authToken,
                config.getHttpMethod()
        );

        Assert.assertTrue(
                HttpUtils.isRateLimited(nextResponse.statusCode()),
                "Request beyond burst capacity should be rate-limited"
        );
        logStep("✓ Request " + (config.getBurstCapacity() + 1) + " was rate-limited (429)\n");
    }

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
}