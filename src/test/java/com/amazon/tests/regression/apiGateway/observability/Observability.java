package com.amazon.tests.regression.apiGateway.observability;

import com.amazon.tests.BaseTest;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.transport.ServiceType;
import com.amazon.tests.utils.apiClients.RawApiClient;
import io.qameta.allure.*;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Amazon Microservices")
@Feature("Monitoring & Observability")
public class Observability extends BaseTest {

    private RawApiClient client;

    @BeforeClass
    public void setup() {
        client = new RawApiClient(context.getExecutor());
    }

    // ══════════════════════════════════════════════════════════════
    // 1. ACTUATOR ENDPOINTS
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("Actuator - Endpoint Discovery")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Gateway exposes required actuator endpoints (health, prometheus)")
    public void test01_ActuatorEndpointsExposed() {
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/actuator", null);
        assertThat(response.getStatusCode()).isEqualTo(200);

        String endpoints = response.getBody();
        assertThat(endpoints).as("health endpoint must be exposed").contains("health");
        assertThat(endpoints).as("prometheus endpoint must be exposed").contains("prometheus");

        logStep("Optional endpoints present: metrics=" + endpoints.contains("metrics")
                + ", circuitbreakers=" + endpoints.contains("circuitbreakers")
                + ", gateway=" + endpoints.contains("gateway")
                + ", routes=" + endpoints.contains("routes"));
    }

    // ══════════════════════════════════════════════════════════════
    // 2. HEALTH CHECKS
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 2)
    @Story("Health - Application Status")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Gateway health endpoint returns UP status")
    public void test02_HealthEndpointReturnsUp() {
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/actuator/health", null);
        assertThat(response.getStatusCode()).isEqualTo(200);

        Map<String, Object> body = response.as(Map.class);
        assertThat(body.get("status")).as("Gateway should be UP").isEqualTo("UP");
    }

    @Test(priority = 3)
    @Story("Health - Component Details")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Health endpoint exposes component statuses (Redis, Circuit Breakers)")
    public void test03_HealthComponentDetails() {
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/actuator/health", null);
        Map<String, Object> body = response.as(Map.class);

        if (!body.containsKey("components")) {
            throw new SkipException("Component details not exposed — configure show-details: always");
        }

        Map<?, ?> components = (Map<?, ?>) body.get("components");

        if (components.containsKey("redis")) {
            String redisStatus = (String) ((Map<?, ?>) components.get("redis")).get("status");
            assertThat(redisStatus).as("Redis component status").isIn("UP", "UNKNOWN");
        }
        if (components.containsKey("circuitBreakers")) {
            assertThat(components.get("circuitBreakers")).isNotNull();
        }
        if (components.containsKey("diskSpace")) {
            String diskStatus = (String) ((Map<?, ?>) components.get("diskSpace")).get("status");
            assertThat(diskStatus).isNotBlank();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 3. PROMETHEUS METRICS
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 10)
    @Story("Metrics - Prometheus Exposure")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Gateway exposes Prometheus-formatted metrics with core HTTP/JVM/system metrics")
    public void test10_PrometheusMetricsExposed() {
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/actuator/prometheus", null);
        assertThat(response.getStatusCode()).isEqualTo(200);

        String metrics = response.getBody();
        assertThat(metrics).contains("http_server_requests");
        assertThat(metrics).contains("jvm_memory");
        assertThat(metrics).contains("jvm_threads");
        assertThat(metrics).contains("system_cpu");
    }

    @Test(priority = 11)
    @Story("Metrics - Circuit Breaker Metrics")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Circuit breaker metrics are exposed in Prometheus format")
    public void test11_CircuitBreakerMetrics() {
        String metrics = client.get(ServiceType.GATEWAY, "/actuator/prometheus", null).getBody();

        boolean present = metrics.contains("resilience4j_circuitbreaker") || metrics.contains("resilience4j.circuitbreaker");
        if (!present) {
            throw new SkipException("Circuit breaker metrics not present — may require traffic first");
        }

        assertThat(metrics).as("userService circuit breaker metrics").contains("userService");
        assertThat(metrics).as("productService circuit breaker metrics").contains("productService");
        assertThat(metrics).as("orderService circuit breaker metrics").contains("orderService");
    }

    @Test(priority = 12)
    @Story("Metrics - Gateway Route Metrics")
    @Severity(SeverityLevel.NORMAL)
    @Description("Gateway-specific routing metrics are captured after traffic")
    public void test12_GatewayRouteMetrics() {
        client.get(ServiceType.GATEWAY, "/api/products", null); // generate traffic

        String metrics = client.get(ServiceType.GATEWAY, "/actuator/prometheus", null).getBody();

        assertThat(metrics).as("HTTP request metrics with URI tags").contains("http_server_requests");
        assertThat(metrics).contains("uri");
    }

    // ══════════════════════════════════════════════════════════════
    // 4. CIRCUIT BREAKER OBSERVABILITY
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 20)
    @Story("Circuit Breakers - Status Endpoint")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Circuit breaker status can be queried via actuator")
    public void test20_CircuitBreakerStatusEndpoint() {
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/actuator/circuitbreakers", null);
        if (response.getStatusCode() != 200) {
            throw new SkipException("Circuit breaker endpoint not available: " + response.getStatusCode());
        }

        String body = response.getBody();
        assertThat(body).as("userService circuit breaker registered").contains("userService");
        assertThat(body).as("productService circuit breaker registered").contains("productService");
        assertThat(body).as("orderService circuit breaker registered").contains("orderService");
    }

    @Test(priority = 21)
    @Story("Circuit Breakers - Events Endpoint")
    @Severity(SeverityLevel.NORMAL)
    @Description("Circuit breaker events are tracked and queryable")
    public void test21_CircuitBreakerEvents() {
        client.get(ServiceType.GATEWAY, "/api/products", null); // generate traffic

        ServiceResponse response = client.get(ServiceType.GATEWAY, "/actuator/circuitbreakerevents", null);
        if (response.getStatusCode() != 200) {
            throw new SkipException("Circuit breaker events endpoint not available");
        }

        assertThat(response.getBody()).as("Events should be tracked").contains("circuitBreakerEvents");
    }

    // ══════════════════════════════════════════════════════════════
    // 5. GATEWAY-SPECIFIC OBSERVABILITY
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 30)
    @Story("Gateway - Routes Endpoint")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Gateway routes can be inspected via actuator")
    public void test30_GatewayRoutesEndpoint() {
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/actuator/gateway/routes", null);
        assertThat(response.getStatusCode()).isEqualTo(200);

        String routes = response.getBody();
        assertThat(routes).contains("user-service");
        assertThat(routes).contains("product-service");
        assertThat(routes).contains("order-service");

        List<?> routeList = response.as(List.class);
        logStep("Total routes configured: " + routeList.size());
    }

    @Test(priority = 31)
    @Story("Gateway - Specific Route Details")
    @Severity(SeverityLevel.NORMAL)
    @Description("Individual route configuration can be inspected")
    public void test31_SpecificRouteDetails() {
        ServiceResponse routesResponse = client.get(ServiceType.GATEWAY, "/actuator/gateway/routes", null);
        List<Map<String, Object>> routes = routesResponse.as(List.class);

        assertThat(routes).as("At least one route should be configured").isNotEmpty();

        String firstRouteId = (String) routes.get(0).get("route_id");
        assertThat(firstRouteId).as("First route should have an id").isNotBlank();

        ServiceResponse routeResponse = client.get(ServiceType.GATEWAY, "/actuator/gateway/routes/" + firstRouteId, null);
        assertThat(routeResponse.getStatusCode()).isEqualTo(200);

        String routeDetails = routeResponse.getBody();
        assertThat(routeDetails).contains("route_id");
        assertThat(routeDetails).contains("uri");
        assertThat(routeDetails).contains("predicates");
    }

    // ══════════════════════════════════════════════════════════════
    // 6. METRICS VALIDATION WITH TRAFFIC
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 40)
    @Story("Metrics - Request Count Tracking")
    @Severity(SeverityLevel.NORMAL)
    @Description("HTTP request count for a specific route increments with actual traffic")
    public void test40_MetricsIncrementWithTraffic() {
        long before = extractProductsRequestCount();

        for (int i = 0; i < 5; i++) {
            client.get(ServiceType.GATEWAY, "/api/products", null);
        }

        long after = extractProductsRequestCount();

        assertThat(after)
                .as("Request count metric for /api/products should increase after 5 requests")
                .isGreaterThan(before);
    }

    private long extractProductsRequestCount() {
        String metrics = client.get(ServiceType.GATEWAY, "/actuator/prometheus", null).getBody();
        return metrics.lines()
                .filter(line -> line.contains("http_server_requests_seconds_count") && line.contains("uri=\"/api/products\""))
                .map(line -> line.substring(line.lastIndexOf(' ') + 1))
                .mapToLong(v -> (long) Double.parseDouble(v))
                .sum();
    }

    // ══════════════════════════════════════════════════════════════
    // 7. MICROSERVICES HEALTH CHECKS
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 50)
    @Story("Health - Microservices Status")
    @Severity(SeverityLevel.BLOCKER)
    @Description("All downstream microservices report UP health status")
    public void test50_DownstreamServicesHealth() {
        assertServiceHealthy(ServiceType.USER, "user-service");
        assertServiceHealthy(ServiceType.PRODUCT, "product-service");
        assertServiceHealthy(ServiceType.ORDER, "order-service");
    }

    @Test(priority = 4)
    @Story("Actuator - Sensitive Endpoint Protection")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify sensitive actuator endpoints (env, heapdump, threaddump) are not publicly exposed")
    public void test04_SensitiveActuatorEndpointsAreProtected() {
        for (String endpoint : List.of("/actuator/env", "/actuator/heapdump", "/actuator/threaddump")) {
            ServiceResponse response = client.get(ServiceType.GATEWAY, endpoint, null);
            assertThat(response.getStatusCode())
                    .as(endpoint + " should not be publicly accessible")
                    .isIn(401, 403, 404);
        }
    }

    @Test(priority = 5)
    @Story("Health - Downstream Dependency Awareness")
    @Severity(SeverityLevel.NORMAL)
    @Description("Gateway health check reports on downstream service dependencies, not just its own liveness")
    public void test05_HealthReflectsDownstreamDependencies() {
        Map<String, Object> body = client.get(ServiceType.GATEWAY, "/actuator/health", null).as(Map.class);

        if (!body.containsKey("components")) {
            throw new SkipException("Component details not exposed — cannot verify downstream awareness");
        }

        Map<?, ?> components = (Map<?, ?>) body.get("components");
        logStep("Health components reported: " + components.keySet());

        assertThat(components)
                .as("Health check should report on more than just the gateway's own liveness")
                .hasSizeGreaterThan(1);
    }
    @Test(priority = 13)
    @Story("Metrics - Distributed Tracing")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify gateway responses carry a trace/correlation ID for distributed tracing")
    public void test13_ResponsesCarryTraceId() {
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/api/products", null);

        boolean hasTraceHeader = response.getHeaders().keySet().stream()
                .anyMatch(h -> h.equalsIgnoreCase("X-Trace-Id")
                        || h.equalsIgnoreCase("traceparent")
                        || h.equalsIgnoreCase("X-B3-TraceId"));

        if (!hasTraceHeader) {
            throw new SkipException("No recognized tracing header found — tracing may not be configured");
        }

        logStep("✓ Trace header present on response");
    }

    private void assertServiceHealthy(ServiceType service, String label) {
        ServiceResponse response = client.get(service, "/actuator/health", null);
        assertThat(response.getStatusCode()).as(label + " health endpoint should return 200").isEqualTo(200);

        Map<String, Object> body = response.as(Map.class);
        assertThat(body.get("status")).as(label + " should report UP").isEqualTo("UP");
    }
}