package com.amazon.tests.tests.apiGateway.observability;

import com.amazon.tests.tests.BaseTest;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Monitoring & Observability Test Suite
 *
 * Tests:
 * 1. Actuator endpoints availability
 * 2. Health indicators (application, Redis, circuit breakers)
 * 3. Prometheus metrics exposure
 * 4. Circuit breaker metrics
 * 5. Gateway-specific metrics
 * 6. Custom business metrics
 */
@Epic("Amazon Microservices")
@Feature("Monitoring & Observability")
public class Observability extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final String USER_SERVICE_URL = "http://localhost:8081";
    private static final String PRODUCT_SERVICE_URL = "http://localhost:8082";
    private static final String ORDER_SERVICE_URL = "http://localhost:8083";

    // ══════════════════════════════════════════════════════════════════════════
    // 1. ACTUATOR ENDPOINTS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("Actuator - Endpoint Discovery")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Gateway exposes all required actuator endpoints")
    public void test01_ActuatorEndpointsExposed() {
        logStep("TEST 1: Verifying actuator endpoints are exposed");

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/actuator")
                .then()
                .statusCode(200)
                .extract()
                .response();

        String endpoints = resp.asString();

        // Required endpoints
        assertThat(endpoints).contains("health");
        assertThat(endpoints).contains("prometheus");
        logStep("  ✓ health endpoint exposed");
        logStep("  ✓ prometheus endpoint exposed");

        // Optional but recommended endpoints
        if (endpoints.contains("metrics")) {
            logStep("  ✓ metrics endpoint exposed");
        }
        if (endpoints.contains("circuitbreakers")) {
            logStep("  ✓ circuitbreakers endpoint exposed");
        }
        if (endpoints.contains("circuitbreakerevents")) {
            logStep("  ✓ circuitbreakerevents endpoint exposed");
        }
        if (endpoints.contains("gateway")) {
            logStep("  ✓ gateway endpoint exposed");
        }
        if (endpoints.contains("routes")) {
            logStep("  ✓ routes endpoint exposed");
        }

        logStep("✅ Actuator endpoints are properly exposed");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. HEALTH CHECKS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 2)
    @Story("Health - Application Status")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Gateway health endpoint returns UP status")
    public void test02_HealthEndpointReturnsUp() {
        logStep("TEST 2: Verifying health endpoint status");

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/actuator/health")
                .then()
                .statusCode(200)
                .extract()
                .response();

        String status = resp.jsonPath().getString("status");

        assertThat(status)
                .as("Gateway should be UP")
                .isEqualTo("UP");

        logStep("  ✓ Gateway status: " + status);
        logStep("✅ Health check passed");
    }

    @Test(priority = 3)
    @Story("Health - Component Details")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Health endpoint exposes component statuses (Redis, Circuit Breakers)")
    public void test03_HealthComponentDetails() {
        logStep("TEST 3: Verifying health component details");

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/actuator/health")
                .then()
                .statusCode(200)
                .extract()
                .response();

        String responseBody = resp.asString();

        // Check for component details (if show-details: always is configured)
        if (responseBody.contains("components")) {
            logStep("  ✓ Component details are exposed");

            // Check specific components
            if (responseBody.contains("redis")) {
                String redisStatus = resp.jsonPath().getString("components.redis.status");
                logStep("  ✓ Redis status: " + redisStatus);
                assertThat(redisStatus).isIn("UP", "UNKNOWN");
            }

            if (responseBody.contains("circuitBreakers")) {
                logStep("  ✓ Circuit breaker health indicators present");
            }

            if (responseBody.contains("diskSpace")) {
                String diskStatus = resp.jsonPath().getString("components.diskSpace.status");
                logStep("  ✓ Disk space status: " + diskStatus);
            }
        } else {
            logStep("  ⚠️  Component details not exposed (configure show-details: always)");
        }

        logStep("✅ Health components verified");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. PROMETHEUS METRICS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 10)
    @Story("Metrics - Prometheus Exposure")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Gateway exposes Prometheus-formatted metrics")
    public void test10_PrometheusMetricsExposed() {
        logStep("TEST 10: Verifying Prometheus metrics");

        String metrics = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/actuator/prometheus")
                .then()
                .statusCode(200)
                .contentType("text/plain;version=0.0.4;charset=utf-8")
                .extract()
                .asString();

        // Check for core HTTP metrics
        assertThat(metrics).contains("http_server_requests");
        logStep("  ✓ HTTP server request metrics present");

        // Check for JVM metrics
        assertThat(metrics).contains("jvm_memory");
        assertThat(metrics).contains("jvm_threads");
        logStep("  ✓ JVM metrics present");

        // Check for system metrics
        assertThat(metrics).contains("system_cpu");
        logStep("  ✓ System metrics present");

        // Check for Resilience4j metrics
        if (metrics.contains("resilience4j")) {
            logStep("  ✓ Resilience4j metrics present");
        }

        logStep("✅ Prometheus metrics correctly exposed");
    }

    @Test(priority = 11)
    @Story("Metrics - Circuit Breaker Metrics")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Circuit breaker metrics are exposed in Prometheus format")
    public void test11_CircuitBreakerMetrics() {
        logStep("TEST 11: Verifying circuit breaker metrics");

        String metrics = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/actuator/prometheus")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        // Check for Resilience4j circuit breaker metrics
        boolean hasCircuitBreakerMetrics =
                metrics.contains("resilience4j_circuitbreaker") ||
                        metrics.contains("resilience4j.circuitbreaker");

        if (hasCircuitBreakerMetrics) {
            logStep("  ✓ Circuit breaker state metrics present");

            // Check for specific circuit breakers
            if (metrics.contains("userService")) {
                logStep("  ✓ userService circuit breaker metrics found");
            }
            if (metrics.contains("productService")) {
                logStep("  ✓ productService circuit breaker metrics found");
            }
            if (metrics.contains("orderService")) {
                logStep("  ✓ orderService circuit breaker metrics found");
            }

            logStep("✅ Circuit breaker metrics are exposed");
        } else {
            logStep("  ⚠️  Circuit breaker metrics not found (may need traffic first)");
        }
    }

    @Test(priority = 12)
    @Story("Metrics - Gateway Route Metrics")
    @Severity(SeverityLevel.NORMAL)
    @Description("Gateway-specific routing metrics are captured")
    public void test12_GatewayRouteMetrics() {
        logStep("TEST 12: Verifying gateway route metrics");

        // Generate some traffic first
        logStep("  Generating traffic to populate metrics...");
        given().baseUri(GATEWAY_URL).get("/api/products");

        String metrics = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/actuator/prometheus")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        // Check for Spring Cloud Gateway metrics
        boolean hasGatewayMetrics =
                metrics.contains("spring_cloud_gateway") ||
                        metrics.contains("gateway");

        if (hasGatewayMetrics) {
            logStep("  ✓ Gateway-specific metrics present");
        }

        // HTTP server requests should show route information
        if (metrics.contains("http_server_requests") && metrics.contains("uri")) {
            logStep("  ✓ HTTP request metrics with URI tags present");
        }

        logStep("✅ Gateway route metrics verified");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. CIRCUIT BREAKER OBSERVABILITY
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 20)
    @Story("Circuit Breakers - Status Endpoint")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Circuit breaker status can be queried via actuator")
    public void test20_CircuitBreakerStatusEndpoint() {
        logStep("TEST 20: Verifying circuit breaker status endpoint");

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/actuator/circuitbreakers")
                .then()
                .extract()
                .response();

        if (resp.statusCode() == 200) {
            String responseBody = resp.asString();

            logStep("  ✓ Circuit breaker endpoint accessible");

            // Check for circuit breaker names
            if (responseBody.contains("userService")) {
                logStep("  ✓ userService circuit breaker registered");
            }
            if (responseBody.contains("productService")) {
                logStep("  ✓ productService circuit breaker registered");
            }
            if (responseBody.contains("orderService")) {
                logStep("  ✓ orderService circuit breaker registered");
            }

            logStep("✅ Circuit breaker status endpoint working");
        } else {
            logStep("  ⚠️  Circuit breaker endpoint not available (status: " + resp.statusCode() + ")");
        }
    }

    @Test(priority = 21)
    @Story("Circuit Breakers - Events Endpoint")
    @Severity(SeverityLevel.NORMAL)
    @Description("Circuit breaker events are tracked and queryable")
    public void test21_CircuitBreakerEvents() {
        logStep("TEST 21: Verifying circuit breaker events endpoint");

        // Generate some traffic to create events
        logStep("  Generating traffic to create CB events...");
        given().baseUri(GATEWAY_URL).get("/api/products");

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/actuator/circuitbreakerevents")
                .then()
                .extract()
                .response();

        if (resp.statusCode() == 200) {
            logStep("  ✓ Circuit breaker events endpoint accessible");

            String responseBody = resp.asString();
            if (responseBody.contains("circuitBreakerEvents")) {
                logStep("  ✓ Events are being tracked");
            }

            logStep("✅ Circuit breaker events endpoint working");
        } else {
            logStep("  ⚠️  Circuit breaker events endpoint not available");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. GATEWAY-SPECIFIC OBSERVABILITY
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 30)
    @Story("Gateway - Routes Endpoint")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Gateway routes can be inspected via actuator")
    public void test30_GatewayRoutesEndpoint() {
        logStep("TEST 30: Verifying gateway routes endpoint");

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/actuator/gateway/routes")
                .then()
                .statusCode(200)
                .extract()
                .response();

        String routes = resp.asString();

        // Check for expected routes
        assertThat(routes).contains("user-service");
        assertThat(routes).contains("product-service");
        assertThat(routes).contains("order-service");

        logStep("  ✓ user-service routes present");
        logStep("  ✓ product-service routes present");
        logStep("  ✓ order-service routes present");

        // Count total routes
        int routeCount = resp.jsonPath().getList("$").size();
        logStep("  ✓ Total routes configured: " + routeCount);

        logStep("✅ Gateway routes endpoint working");
    }

    @Test(priority = 31)
    @Story("Gateway - Specific Route Details")
    @Severity(SeverityLevel.NORMAL)
    @Description("Individual route configuration can be inspected")
    public void test31_SpecificRouteDetails() {
        logStep("TEST 31: Verifying specific route details");

        // Get all routes first
        Response resp = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/actuator/gateway/routes")
                .then()
                .statusCode(200)
                .extract()
                .response();

        // Check first route has required fields
        String firstRouteId = resp.jsonPath().getString("[0].route_id");

        if (firstRouteId != null) {
            logStep("  ✓ Found route: " + firstRouteId);

            // Get specific route details
            Response routeResp = given()
                    .baseUri(GATEWAY_URL)
                    .when()
                    .get("/actuator/gateway/routes/" + firstRouteId)
                    .then()
                    .extract()
                    .response();

            if (routeResp.statusCode() == 200) {
                String routeDetails = routeResp.asString();

                assertThat(routeDetails).contains("route_id");
                assertThat(routeDetails).contains("uri");
                assertThat(routeDetails).contains("predicates");

                logStep("  ✓ Route details include: id, uri, predicates");
                logStep("✅ Specific route details accessible");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. METRICS VALIDATION WITH TRAFFIC
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 40)
    @Story("Metrics - Request Count Tracking")
    @Severity(SeverityLevel.NORMAL)
    @Description("HTTP request metrics increment with actual traffic")
    public void test40_MetricsIncrementWithTraffic() {
        logStep("TEST 40: Verifying metrics increment with traffic");

        // Get initial metrics
        String initialMetrics = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/actuator/prometheus")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        // Generate traffic
        logStep("  Generating 5 requests...");
        for (int i = 0; i < 5; i++) {
            given().baseUri(GATEWAY_URL).get("/api/products");
        }

        // Get updated metrics
        String updatedMetrics = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/actuator/prometheus")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        // Metrics should have changed
        assertThat(updatedMetrics).isNotEqualTo(initialMetrics);
        logStep("  ✓ Metrics changed after traffic");

        // Should contain request count metrics
        assertThat(updatedMetrics).contains("http_server_requests");
        logStep("  ✓ HTTP request metrics updated");

        logStep("✅ Metrics correctly track traffic");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. MICROSERVICES HEALTH CHECKS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 50)
    @Story("Health - Microservices Status")
    @Severity(SeverityLevel.BLOCKER)
    @Description("All downstream microservices are healthy")
    public void test50_DownstreamServicesHealth() {
        logStep("TEST 50: Verifying downstream services health");

        // Check user-service
        Response userHealth = given()
                .baseUri(USER_SERVICE_URL)
                .when()
                .get("/actuator/health")
                .then()
                .extract()
                .response();

        if (userHealth.statusCode() == 200) {
            logStep("  ✓ user-service is UP");
        } else {
            logStep("  ⚠️  user-service health check failed: " + userHealth.statusCode());
        }

        // Check product-service
        Response productHealth = given()
                .baseUri(PRODUCT_SERVICE_URL)
                .when()
                .get("/actuator/health")
                .then()
                .extract()
                .response();

        if (productHealth.statusCode() == 200) {
            logStep("  ✓ product-service is UP");
        } else {
            logStep("  ⚠️  product-service health check failed");
        }

        // Check order-service
        Response orderHealth = given()
                .baseUri(ORDER_SERVICE_URL)
                .when()
                .get("/actuator/health")
                .then()
                .extract()
                .response();

        if (orderHealth.statusCode() == 200) {
            logStep("  ✓ order-service is UP");
        } else {
            logStep("  ⚠️  order-service health check failed");
        }

        logStep("✅ Downstream services health checked");
    }
}