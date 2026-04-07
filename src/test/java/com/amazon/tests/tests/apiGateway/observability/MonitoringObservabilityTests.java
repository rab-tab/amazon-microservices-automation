package com.amazon.tests.tests.apiGateway.observability;

import com.amazon.tests.tests.BaseTest;
import io.qameta.allure.Description;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;


public class MonitoringObservabilityTests  extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final String USER_SERVICE_URL = "http://localhost:8081";
    private static final String PRODUCT_SERVICE_URL = "http://localhost:8082";
    private static final String ORDER_SERVICE_URL = "http://localhost:8083";


    @Test(priority = 60)
    @Story("Monitoring - Actuator Endpoints")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Gateway exposes all required actuator endpoints")
    public void test60_ActuatorEndpointsExposed() {
        logStep("TEST 60: Verifying actuator endpoints");

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/actuator")
                .then()
                .statusCode(200)
                .extract()
                .response();

        String endpoints = resp.asString();

        // Check for required endpoints
        assertThat(endpoints).contains("health");
        assertThat(endpoints).contains("prometheus");
        logStep("  ✓ health endpoint exposed");
        logStep("  ✓ prometheus endpoint exposed");

        if (endpoints.contains("circuitbreakers")) {
            logStep("  ✓ circuitbreakers endpoint exposed");
        }
        if (endpoints.contains("gateway")) {
            logStep("  ✓ gateway endpoint exposed");
        }

        logStep("✅ Required actuator endpoints are exposed");
    }

    @Test(priority = 61)
    @Story("Monitoring - Prometheus Metrics")
    @Severity(SeverityLevel.NORMAL)
    @Description("Gateway exposes Prometheus metrics")
    public void test61_PrometheusMetrics() {
        logStep("TEST 61: Verifying Prometheus metrics");

        String metrics = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/actuator/prometheus")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        // Check for important metrics
        assertThat(metrics).contains("http_server_requests");
        assertThat(metrics).contains("resilience4j");
        assertThat(metrics).contains("jvm_memory");

        logStep("  ✓ HTTP metrics present");
        logStep("  ✓ Resilience4j metrics present");
        logStep("  ✓ JVM metrics present");

        logStep("✅ Prometheus metrics correctly exposed");
    }
}
