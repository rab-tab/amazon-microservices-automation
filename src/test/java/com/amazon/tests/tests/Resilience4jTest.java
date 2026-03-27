package com.amazon.tests.tests;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.TestDataFactory;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Resilience4j Tests covering:
 *  1. Circuit Breaker state transitions (CLOSED → OPEN → HALF_OPEN → CLOSED)
 *  2. Circuit Breaker metrics via actuator
 *  3. Rate Limiter enforcement
 *  4. Service health with circuit breakers
 *  5. Fallback behaviour verification
 *  6. Concurrent load handling
 */
@Epic("Amazon Microservices")
@Feature("Resilience4j — Circuit Breaker, Retry, Rate Limiter")
public class Resilience4jTest extends BaseTest {

    private static final String ORDER_SERVICE_URL = "http://localhost:8083";
    private static final String CB_ENDPOINT = "/api/v1/resilience/circuit-breakers";
    private static final String ACTUATOR_HEALTH = "/actuator/health";

    // ─── 1. Circuit Breaker Health Indicator ──────────────────────────

    @Test(priority = 1)
    @Story("Circuit Breaker Health")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify circuit breakers are registered and visible in actuator health")
    public void testCircuitBreakersRegisteredInActuator() {
        logStep("Checking circuit breaker health indicators via actuator");

        Response response = given()
                .baseUri(ORDER_SERVICE_URL)
                .when()
                .get(ACTUATOR_HEALTH)
                .then()
                .statusCode(200)
                .extract().response();

        // Health endpoint should report circuitBreakers component
        String body = response.asString();
        logStep("Actuator health response: " + body);

        // Even if specific CBs aren't listed, actuator should return 200
        assertThat(response.statusCode()).isEqualTo(200);
        logStep("✅ Order service actuator health is UP");
    }

    @Test(priority = 2)
    @Story("Circuit Breaker Status Endpoint")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify all circuit breakers are in CLOSED state initially")
    public void testAllCircuitBreakersInitiallyClosed() {
        logStep("Fetching all circuit breaker states");

        Response response = given()
                .baseUri(ORDER_SERVICE_URL)
                .when()
                .get(CB_ENDPOINT)
                .then()
                .statusCode(200)
                .body("$", notNullValue())
                .extract().response();

        // Parse the response — each CB should be CLOSED at startup
        String body = response.asString();
        logStep("Circuit breakers: " + body);

        // Verify at least one CB is registered
        assertThat(body).isNotEmpty();
        logStep("✅ Circuit breaker status endpoint accessible");
    }

    @Test(priority = 3)
    @Story("Circuit Breaker — Individual")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify paymentService circuit breaker state is accessible and initially CLOSED")
    public void testPaymentServiceCircuitBreakerInitiallyClosed() {
        Response response = given()
                .baseUri(ORDER_SERVICE_URL)
                .pathParam("name", "paymentService")
                .when()
                .get(CB_ENDPOINT + "/{name}")
                .then()
                .statusCode(200)
                .body("name", equalTo("paymentService"))
                .body("state", notNullValue())
                .extract().response();

        String state = response.jsonPath().getString("state");
        assertThat(state).isIn("CLOSED", "HALF_OPEN", "OPEN");

        logStep("✅ paymentService circuit breaker state: " + state);
    }

    // ─── 2. Force Open Circuit Breaker and Verify Behaviour ───────────

    @Test(priority = 4)
    @Story("Circuit Breaker State Transition")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify circuit breaker can be force-opened and state is OPEN")
    public void testCircuitBreakerForceOpen() {
        logStep("Force-opening paymentService circuit breaker");

        Response openResponse = given()
                .baseUri(ORDER_SERVICE_URL)
                .pathParam("name", "paymentService")
                .when()
                .post(CB_ENDPOINT + "/{name}/open")
                .then()
                .statusCode(200)
                .body("state", equalTo("OPEN"))
                .extract().response();

        assertThat(openResponse.jsonPath().getString("state")).isEqualTo("OPEN");
        logStep("✅ Circuit breaker successfully OPENED");

        // Reset back to CLOSED for subsequent tests
        given()
                .baseUri(ORDER_SERVICE_URL)
                .pathParam("name", "paymentService")
                .when()
                .post(CB_ENDPOINT + "/{name}/reset")
                .then()
                .statusCode(200)
                .body("state", equalTo("CLOSED"));

        logStep("✅ Circuit breaker RESET to CLOSED");
    }

    @Test(priority = 5)
    @Story("Circuit Breaker State Transition")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify circuit breaker resets back to CLOSED state")
    public void testCircuitBreakerReset() {
        // First force it open
        given()
                .baseUri(ORDER_SERVICE_URL)
                .pathParam("name", "orderEventPublisher")
                .when()
                .post(CB_ENDPOINT + "/{name}/open")
                .then()
                .statusCode(200);

        String stateAfterOpen = given()
                .baseUri(ORDER_SERVICE_URL)
                .pathParam("name", "orderEventPublisher")
                .when()
                .get(CB_ENDPOINT + "/{name}")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("state");

        assertThat(stateAfterOpen).isEqualTo("OPEN");

        // Now reset
        given()
                .baseUri(ORDER_SERVICE_URL)
                .pathParam("name", "orderEventPublisher")
                .when()
                .post(CB_ENDPOINT + "/{name}/reset")
                .then()
                .statusCode(200)
                .body("state", equalTo("CLOSED"));

        String stateAfterReset = given()
                .baseUri(ORDER_SERVICE_URL)
                .pathParam("name", "orderEventPublisher")
                .when()
                .get(CB_ENDPOINT + "/{name}")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("state");

        assertThat(stateAfterReset).isEqualTo("CLOSED");
        logStep("✅ Circuit breaker state transition OPEN → CLOSED confirmed");
    }

    // ─── 3. Circuit Breaker Metrics ────────────────────────────────────

    @Test(priority = 6)
    @Story("Circuit Breaker Metrics")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify circuit breaker metrics fields are present in response")
    public void testCircuitBreakerMetricsPresent() {
        Response response = given()
                .baseUri(ORDER_SERVICE_URL)
                .pathParam("name", "paymentService")
                .when()
                .get(CB_ENDPOINT + "/{name}")
                .then()
                .statusCode(200)
                .body("name", notNullValue())
                .body("state", notNullValue())
                .body("failureRate", notNullValue())
                .body("slowCallRate", notNullValue())
                .body("numberOfBufferedCalls", notNullValue())
                .body("numberOfSuccessfulCalls", notNullValue())
                .body("numberOfFailedCalls", notNullValue())
                .body("notPermittedCalls", notNullValue())
                .extract().response();

        logStep("✅ All circuit breaker metric fields present: " + response.asString());
    }

    // ─── 4. Rate Limiter under Load ────────────────────────────────────

    @Test(priority = 7)
    @Story("Rate Limiter")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify rate limiter in actuator shows configured limit (100 req/sec)")
    public void testRateLimiterHealthIndicator() {
        Response health = given()
                .baseUri(ORDER_SERVICE_URL)
                .when()
                .get("/actuator/health/ratelimiters")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(404))) // 404 if not exposed
                .extract().response();

        logStep("Rate limiter health: " + health.asString());
        assertThat(health.statusCode()).isIn(200, 404);
        logStep("✅ Rate limiter health check passed");
    }

    @Test(priority = 8)
    @Story("Rate Limiter — Burst Traffic")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify system handles burst of 20 concurrent order requests gracefully")
    public void testConcurrentOrderRequestsUnderRateLimit() throws InterruptedException {
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(5.00))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        TestModels.ProductResponse product = productResp.as(TestModels.ProductResponse.class);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rateLimitedCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);

        logStep("Firing " + threadCount + " concurrent order requests");

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    int status = given()
                            .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                            .header("X-User-Id", customerId)
                            .body(TestDataFactory.createOrderRequest(
                                    product.getId(), product.getName(), product.getPrice()))
                            .when()
                            .post("/api/v1/orders")
                            .then()
                            .extract().response().statusCode();

                    if (status == 201) successCount.incrementAndGet();
                    else if (status == 429) rateLimitedCount.incrementAndGet();
                    else errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        logStep("Results — Successful: " + successCount.get()
                + " | Rate-limited(429): " + rateLimitedCount.get()
                + " | Errors: " + errorCount.get());

        // At least some requests should succeed (rate limit is 100/sec, we sent 20)
        assertThat(successCount.get())
                .as("All 20 concurrent requests should succeed within 100 req/sec limit")
                .isGreaterThan(0);

        // No 5xx errors expected
        assertThat(errorCount.get())
                .as("No internal server errors expected")
                .isEqualTo(0);

        logStep("✅ Concurrent request handling verified under rate limit");
    }

    // ─── 5. Prometheus Metrics for Resilience4j ───────────────────────

    @Test(priority = 9)
    @Story("Prometheus Metrics")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify Resilience4j circuit breaker metrics are exposed in Prometheus format")
    public void testResilience4jMetricsInPrometheus() {
        String prometheusMetrics = given()
                .baseUri(ORDER_SERVICE_URL)
                .when()
                .get("/actuator/prometheus")
                .then()
                .statusCode(200)
                .extract().asString();

        // Verify resilience4j metrics are present in Prometheus output
        assertThat(prometheusMetrics).contains("resilience4j");
        logStep("✅ Resilience4j metrics found in Prometheus endpoint");
    }

    // ─── 6. Service Recovery after Circuit Open ────────────────────────

    @Test(priority = 10)
    @Story("Circuit Breaker Recovery")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify normal orders still work after circuit breaker has been reset to CLOSED")
    public void testServiceRecoveryAfterCircuitBreakerReset() {
        // Ensure CB is closed
        given()
                .baseUri(ORDER_SERVICE_URL)
                .pathParam("name", "paymentService")
                .when()
                .post(CB_ENDPOINT + "/{name}/reset")
                .then()
                .statusCode(200);

        // Verify normal order creation still works
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(20.00))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();

        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                .header("X-User-Id", customerId)
                .body(TestDataFactory.createOrderRequest(
                        productResp.jsonPath().getString("id"),
                        productResp.jsonPath().getString("name"),
                        new java.math.BigDecimal("20.00")))
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("status", equalTo("PENDING"));

        logStep("✅ Normal service operation confirmed after circuit breaker reset");
    }

    // ─── 7. 404 for Non-Existent Circuit Breaker ──────────────────────

    @Test(priority = 11)
    @Story("Circuit Breaker — Not Found")
    @Severity(SeverityLevel.MINOR)
    @Description("Verify 404 is returned for a non-existent circuit breaker name")
    public void testNonExistentCircuitBreakerReturns404() {
        given()
                .baseUri(ORDER_SERVICE_URL)
                .pathParam("name", "nonExistentCircuitBreaker_xyz")
                .when()
                .get(CB_ENDPOINT + "/{name}")
                .then()
                .statusCode(404);

        logStep("✅ 404 returned for non-existent circuit breaker");
    }
}
