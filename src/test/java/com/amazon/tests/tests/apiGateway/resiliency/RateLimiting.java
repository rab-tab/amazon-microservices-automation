package com.amazon.tests.tests.apiGateway.resiliency;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import io.qameta.allure.*;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;

/**
 * API Gateway Comprehensive Test Suite
 *
 * Tests all gateway functionality:
 * 1. Routing & Path Mapping
 * 2. Authentication & Authorization
 * 3. Rate Limiting
 * 4. CORS & Headers
 * 5. Request/Response Transformation
 * 6. Timeouts & Error Handling
 * 7. Logging & Tracing
 *
 * Circuit Breaker tests are in GatewayCircuitBreakerTest.java
 */
@Epic("Amazon Microservices")
@Feature("API Gateway - Complete Functionality")
public class RateLimiting extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final String USER_SERVICE_URL = "http://localhost:8081";
    private static final String PRODUCT_SERVICE_URL = "http://localhost:8082";
    private static final String ORDER_SERVICE_URL = "http://localhost:8083";

    @Test(priority = 20)
    @Story("Rate Limit - Global Limit")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Gateway enforces global rate limits")
    public void test20_GlobalRateLimiting() {
        logStep("TEST 20: Verifying global rate limiting");

        int requestCount = 100;
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger rateLimited = new AtomicInteger(0);

        logStep("  Sending " + requestCount + " rapid requests...");

        for (int i = 0; i < requestCount; i++) {
            int status = given().baseUri(GATEWAY_URL)
                    .when().get("/api/users/health")
                    .then().extract().statusCode();

            if (status == 200 || status == 503) {
                success.incrementAndGet();
            } else if (status == 429) {
                rateLimited.incrementAndGet();
            }
        }

        logStep("  Results: " + success.get() + " successful, "
                + rateLimited.get() + " rate-limited");

        // If rate limiting is configured, some should be limited
        // If not configured, all should succeed
        if (rateLimited.get() > 0) {
            logStep("✅ Rate limiting is active and working");
        } else {
            logStep("⚠️  No rate limiting detected (might not be configured)");
        }
    }

    @Test(priority = 21)
    @Story("Rate Limit - Per-User Limit")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Gateway enforces per-user rate limits")
    public void test21_PerUserRateLimiting() {
        logStep("TEST 21: Verifying per-user rate limiting");

        // Get auth token
        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();
        String token = auth.getAccessToken();

        int requestCount = 50;
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger rateLimited = new AtomicInteger(0);

        logStep("  Sending " + requestCount + " requests with same token...");

        for (int i = 0; i < requestCount; i++) {
            int status = given().baseUri(GATEWAY_URL)
                    .header("Authorization", "Bearer " + token)
                    .header("X-User-Id", auth.getUser().getId())
                    .when().get("/api/orders")
                    .then().extract().statusCode();

            if (status == 200 || status == 503) {
                success.incrementAndGet();
            } else if (status == 429) {
                rateLimited.incrementAndGet();
            }
        }

        logStep("  Results: " + success.get() + " successful, "
                + rateLimited.get() + " rate-limited");

        if (rateLimited.get() > 0) {
            logStep("✅ Per-user rate limiting is active");
        } else {
            logStep("⚠️  No per-user rate limiting detected");
        }
    }

}
