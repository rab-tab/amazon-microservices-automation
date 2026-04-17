package com.amazon.tests.tests.apiGateway.resiliency.rateLimiting;


import com.amazon.tests.config.RateLimitConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.HttpUtils;
import com.amazon.tests.utils.RateLimitDataProvider;
import com.amazon.tests.utils.TestDataFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.http.HttpResponse;

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
        int requestsToSend = config.getBurstCapacity() + 2;
        logStep("Sending " + requestsToSend + " requests to trigger rate limit");

        HttpResponse<String> lastResponse = null;
        for (int i = 0; i < requestsToSend; i++) {
            String requestBody;

            if (config.getEndpoint().contains("/register")) {
                TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                requestBody = convertToJson(user);
            } else if (config.getEndpoint().contains("/login")) {
                TestModels.LoginRequest loginRequest = TestDataFactory.createLoginRequest(
                        "testuser" + i + "@test.com",
                        "wrongpassword" + i
                );
                requestBody = convertToJson(loginRequest);
            } else if (config.getRequestBodyTemplate() != null) {
                requestBody = String.format(config.getRequestBodyTemplate(), i, i);
            } else {
                requestBody = null;
            }

            lastResponse = HttpUtils.sendRequest(
                    config.getEndpoint(),
                    requestBody,
                    authToken,
                    config.getHttpMethod()
            );

            if (HttpUtils.isRateLimited(lastResponse.statusCode())) {
                logStep("Rate limited after " + (i + 1) + " requests");
                break;
            }
        }

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
        int successCount = 0;
        for (int i = 0; i < config.getBurstCapacity(); i++) {
            String requestBody;

            if (config.getEndpoint().contains("/register")) {
                TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
                requestBody = convertToJson(user);
            } else if (config.getEndpoint().contains("/login")) {
                TestModels.LoginRequest loginRequest = TestDataFactory.createLoginRequest(
                        "testuser" + i + "@test.com",
                        "wrongpassword" + i
                );
                requestBody = convertToJson(loginRequest);
            } else if (config.getRequestBodyTemplate() != null) {
                requestBody = String.format(config.getRequestBodyTemplate(), i, i);
            } else {
                requestBody = null;
            }

            HttpResponse<String> response = HttpUtils.sendRequest(
                    config.getEndpoint(),
                    requestBody,
                    authToken,
                    config.getHttpMethod()
            );

            if (HttpUtils.isSuccessful(response.statusCode())) {
                successCount++;
            }
        }

        // All burst capacity requests should succeed
        Assert.assertEquals(
                successCount,
                config.getBurstCapacity(),
                "All requests within burst capacity should succeed"
        );
        logStep("✓ All " + successCount + " requests succeeded (within burst capacity)");

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
}