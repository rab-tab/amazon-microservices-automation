package com.amazon.tests.tests.apiGateway.errorHandling;

import com.amazon.tests.tests.BaseTest;
import io.qameta.allure.Description;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;

import static org.assertj.core.api.Assertions.assertThat;


public class TimeoutTests extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final String USER_SERVICE_URL = "http://localhost:8081";
    private static final String PRODUCT_SERVICE_URL = "http://localhost:8082";
    private static final String ORDER_SERVICE_URL = "http://localhost:8083";


    @Test
    @Story("Timeout - Response Timeout")
    @Severity(SeverityLevel.NORMAL)
    @Description("Gateway handles slow backend responses with timeout")
    public void test40_ResponseTimeoutHandling() {
        logStep("TEST 40: Verifying response timeout handling");

        // Call a slow endpoint that takes longer than response-timeout (3s)
        // This endpoint should exist in your services for testing
        long startTime = System.currentTimeMillis();

        Response resp = given().log().all()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/api/users/test/slow?delay=5000")  // 5 second delay
                .then().log().all()
                .extract()
                .response();

        long duration = System.currentTimeMillis() - startTime;

        logStep("  Response time: " + duration + "ms");
        logStep("  Response status: HTTP " + resp.statusCode());

        // Should timeout around 3 seconds (response-timeout config)
        // Allow some overhead (3000ms + 1000ms buffer)
        assertThat(duration)
                .as("Should timeout at configured response-timeout (3s)")
                .isLessThan(4000)  // Less than 4 seconds
                .isGreaterThan(2500);  // But more than 2.5 seconds (proving it waited)

        // Response should be 504 Gateway Timeout or 503 Service Unavailable
        assertThat(resp.statusCode()).isIn(503, 504, 500)
                .as("Should return timeout error");

        logStep("✅ Response timeout works (timed out at ~3s)");
    }

    @Test(priority = 41)
    @Story("Timeout - Connection Timeout")
    @Severity(SeverityLevel.NORMAL)
    @Description("Gateway handles unreachable backends with connection timeout")
    public void test41_ConnectionTimeoutHandling() {
        logStep("TEST 41: Verifying connection timeout handling");

        // Try to connect to a non-existent service (unreachable port)
        // This tests connect-timeout (1000ms)
        long startTime = System.currentTimeMillis();

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/api/nonexistent/service")
                .then()
                .extract()
                .response();

        long duration = System.currentTimeMillis() - startTime;

        logStep("  Response time: " + duration + "ms");
        logStep("  Response status: HTTP " + resp.statusCode());

        // Should fail quickly (within connect-timeout + some overhead)
        assertThat(duration)
                .as("Should fail quickly on connection timeout")
                .isLessThan(3000);  // Less than 3 seconds

        // Should return 404 (no route) or 503 (connection failed)
        assertThat(resp.statusCode()).isIn(404, 503, 500)
                .as("Should return error for unreachable service");

        logStep("✅ Connection timeout works (fast failure)");
    }

    @Test(priority = 42)
    @Story("Timeout - Circuit Breaker Fast Failure")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Circuit breaker provides fast failure when open (faster than timeout)")
    public void test42_CircuitBreakerFastFailure() {
        logStep("TEST 42: Verifying circuit breaker provides faster failure than timeout");

        // First, trip the circuit breaker by calling test/fail endpoint
        given()
                .baseUri("http://localhost:8081")
                .when()
                .post("/api/v1/test/circuit-breaker/fail")
                .then()
                .statusCode(200);

        logStep("  Circuit breaker set to failure mode");

        // Send multiple requests to trip the CB
        for (int i = 0; i < 6; i++) {
            given().baseUri(GATEWAY_URL).get("/api/users/health").then().extract().response();
        }

        // Wait for CB to open
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        // Now test fast failure
        long startTime = System.currentTimeMillis();

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/api/users/health")
                .then()
                .extract()
                .response();

        long duration = System.currentTimeMillis() - startTime;

        logStep("  Fast failure time: " + duration + "ms");
        logStep("  Response status: HTTP " + resp.statusCode());

        // CB should provide VERY fast failure (< 100ms, not wait for timeout)
        assertThat(duration)
                .as("Circuit breaker should provide instant failure")
                .isLessThan(100);

        assertThat(resp.statusCode()).isEqualTo(503)
                .as("Should return 503 from circuit breaker");

        logStep("✅ Circuit breaker fast failure works (" + duration + "ms)");

        // Cleanup: recover the service
        given()
                .baseUri("http://localhost:8081")
                .when()
                .post("/api/v1/test/circuit-breaker/recover")
                .then()
                .statusCode(200);
    }

    @Test(enabled = false)
    @Story("Error - 404 Handling")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Gateway returns 404 for non-existent routes")
    public void test41_NonExistentRouteHandling() {
        logStep("TEST 41: Verifying 404 handling for non-existent routes");

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/api/nonexistent/endpoint")
                .then()
                .statusCode(404)
                .extract()
                .response();

        logStep("  Response: HTTP " + resp.statusCode());
        logStep("✅ Non-existent routes return 404");
    }

    @Test(priority = 42)
    @Story("Error - 503 Service Unavailable")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Gateway returns 503 when backend services are down")
    public void test42_ServiceUnavailableHandling() {
        logStep("TEST 42: Verifying 503 handling when services down");

        // This depends on circuit breaker test setup
        // If CB is open or service is down, should get 503

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/api/users/health")
                .then()
                .extract()
                .response();

        if (resp.statusCode() == 503) {
            assertThat(resp.jsonPath().getString("error"))
                    .as("Should return meaningful error message")
                    .isNotNull();
            logStep("✅ Service unavailable handled with proper 503 response");
        } else {
            logStep("⚠️  Service is up (200) - cannot test 503 handling now");
        }
    }

}
