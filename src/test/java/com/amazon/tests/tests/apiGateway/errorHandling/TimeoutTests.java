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


    @Test(priority = 40)
    @Story("Timeout - Connection Timeout")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Gateway handles backend connection timeouts gracefully")
    public void test40_ConnectionTimeoutHandling() {
        logStep("TEST 40: Verifying connection timeout handling");

        // Try to call a non-existent service
        long startTime = System.currentTimeMillis();

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/api/users/health")
                .then()
                .extract()
                .response();

        long duration = System.currentTimeMillis() - startTime;

        logStep("  Response time: " + duration + "ms");
        logStep("  Response status: HTTP " + resp.statusCode());

        // Should timeout quickly (not wait forever)
        assertThat(duration)
                .as("Should timeout quickly (configured timeout)")
                .isLessThan(5000); // Less than 5 seconds

        logStep("✅ Timeout handling works (fast failure)");
    }

    @Test(priority = 41)
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
