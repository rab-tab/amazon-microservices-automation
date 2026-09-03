package com.amazon.tests.regression.apiGateway.resiliency.timeOut;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.transport.ServiceType;
import com.amazon.tests.utils.apiClients.RawApiClient;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway resilience/timeout coverage — merged from the original
 * TimeoutTests class and a separately-drafted GatewayTimeoutTest class.
 *
 * IMPORTANT CONTEXT (found during investigation, see commit history):
 * A global auth filter runs BEFORE route resolution for every request,
 * including unmapped routes and diagnostic-only routes like
 * /api/test/connect-timeout. A request sent with a null token fails
 * almost instantly with 401 — it never reaches routing, the backend,
 * or any artificial delay. Every test in the original TimeoutTests
 * file used a null token, which means several of them could never
 * have exercised the behavior they claimed to test (e.g.
 * testResponseTimeout's "duration between 2500-4000ms" assertion
 * would instead see a near-instant 401). All tests below use
 * validToken accordingly. If the auth filter is ever rescoped to
 * exclude diagnostic/unmapped routes, testNoInfiniteHang's expected
 * status should be revisited (see inline note).
 *
 * Also confirmed during investigation: user-service-test and
 * order-service-test had a RewritePath bug producing an unreachable
 * path (missing on payment/product, which had no dedicated /test/**
 * route at all). Fixed in gateway YAML; test_VerifyTestRoutePathRewriting
 * below guards against regression.
 */
public class GatewayTimeoutTest extends BaseTest {

    private static final String SLOW_ENDPOINT = "/api/users/test/slow";

    private RawApiClient client;
    private String validToken;

    @BeforeClass
    public void setup() {
        client = new RawApiClient(executor);

        PurchaseResult purchase = PurchaseWorkflow.start(executor, authStrategy)
                .registerCustomer()
                .execute();
        TestModels.AuthResponse auth = purchase.getCustomer();
        validToken = auth.getAccessToken();
    }

    // ============================================================
    // Route rewrite correctness — /test/** endpoints
    //
    // All four services' TimeoutTestController live at the flat path
    // /api/v1/test/** (no service-name segment). Guards against the
    // RewritePath regression found during investigation.
    // ============================================================

    @DataProvider(name = "testRouteServices")
    public Object[][] testRouteServices() {
        return new Object[][] {
                { "/api/users/test/slow", ServiceType.USER },
                { "/api/orders/test/slow", ServiceType.ORDER },
                { "/api/payments/test/slow", ServiceType.PAYMENT },
                { "/api/products/test/slow", ServiceType.PRODUCT },
        };
    }

    @Test(priority = 1, dataProvider = "testRouteServices")
    @Story("Routing - Path Rewriting")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify /api/{service}/test/slow rewrites to the flat /api/v1/test/slow path " +
            "(TimeoutTestController), reachable through the gateway with a short delay")
    public void test_VerifyTestRoutePathRewriting(String gatewayPath, ServiceType serviceType) {
        ServiceResponse gatewayResp = client.get(ServiceType.GATEWAY, gatewayPath + "?delay=100", validToken);
        assertThat(gatewayResp.getStatusCode())
                .as("Gateway should correctly rewrite " + gatewayPath + " to reach TimeoutTestController")
                .isEqualTo(200);

        ServiceResponse directResp = client.get(serviceType, "/api/v1/test/slow?delay=100", null);
        assertThat(directResp.getStatusCode()).isEqualTo(200);

        assertThat(gatewayResp.as(Map.class).get("message"))
                .as("Gateway-routed response should match TimeoutTestController's direct response")
                .isEqualTo(directResp.as(Map.class).get("message"));
    }

    // ============================================================
    // connect-timeout (1000ms) — pre-built diagnostic route pointing
    // at an unroutable IP (10.255.255.1:9999).
    // ============================================================

    @Test(priority = 2)
    @Story("Gateway - Timeout Handling")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify gateway aborts fast (near configured 1000ms connect-timeout) when the backend " +
            "is unreachable, rather than hanging indefinitely")
    public void test_ConnectTimeoutFailsFast() {
        long start = System.currentTimeMillis();
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/api/test/connect-timeout", validToken);
        long elapsed = System.currentTimeMillis() - start;

        logStep("Duration: " + elapsed + "ms | Status: HTTP " + response.getStatusCode());

        assertThat(elapsed)
                .as("Should fail near the configured 1000ms connect-timeout, not hang indefinitely " +
                        "(allowing buffer for JVM/network overhead)")
                .isLessThan(3000L);

        assertThat(response.getStatusCode())
                .as("Connection failure should surface as a server-side error, not a silent success")
                .isIn(500, 502, 503, 504);
    }

    // ============================================================
    // No-infinite-hang on unmapped routes
    // ============================================================

    @Test(priority = 3)
    @Story("Timeout - No Infinite Hang")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Requests to non-existent routes should fail quickly, not hang")
    public void testNoInfiniteHang() {
        // NOTE: expects 404 because validToken is supplied, so the global
        // auth filter passes and the request actually reaches route
        // resolution. With a null token this would 401 before ever
        // reaching the router — see class-level note.
        long startTime = System.currentTimeMillis();
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/api/completely-nonexistent-route", validToken);
        long duration = System.currentTimeMillis() - startTime;

        logStep("Duration: " + duration + "ms | Status: HTTP " + response.getStatusCode());

        assertThat(duration).as("Should not hang indefinitely").isLessThan(5000L);
        assertThat(response.getStatusCode()).as("Should return a 404 (no route matched)").isEqualTo(404);
    }

    // ============================================================
    // response-timeout (3s) — backend connects successfully but
    // responds slower than the configured response-timeout.
    // ============================================================

    @Test(priority = 4)
    @Story("Timeout - Response/TimeLimiter Timeout")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Slow responses should timeout at ~3 seconds (configured response-timeout)")
    public void testResponseTimeout() {
        skipIfSlowEndpointUnavailable();

        long startTime = System.currentTimeMillis();
        ServiceResponse response = client.get(ServiceType.GATEWAY, SLOW_ENDPOINT + "?delay=5000", validToken);
        long duration = System.currentTimeMillis() - startTime;

        logStep("Duration: " + duration + "ms | Status: HTTP " + response.getStatusCode());

        assertThat(duration).as("Should timeout at ~3s").isBetween(2500L, 4000L);
        assertThat(response.getStatusCode()).as("Should return a timeout error").isIn(500, 502, 503, 504);
    }

    @Test(priority = 5)
    @Story("Timeout - Fast Response")
    @Severity(SeverityLevel.NORMAL)
    @Description("Fast responses should complete successfully")
    public void testFastResponse() {
        skipIfSlowEndpointUnavailable();

        long startTime = System.currentTimeMillis();
        ServiceResponse response = client.get(ServiceType.GATEWAY, SLOW_ENDPOINT + "?delay=1000", validToken);
        long duration = System.currentTimeMillis() - startTime;

        logStep("Duration: " + duration + "ms | Status: HTTP " + response.getStatusCode());

        assertThat(duration).as("Should complete in ~1s").isBetween(800L, 2000L);
        assertThat(response.getStatusCode()).isEqualTo(200);
    }

    @Test(priority = 6)
    @Story("Timeout - Boundary Test")
    @Severity(SeverityLevel.NORMAL)
    @Description("Requests just under the timeout threshold should succeed")
    public void testBoundaryUnderTimeout() {
        skipIfSlowEndpointUnavailable();

        long startTime = System.currentTimeMillis();
        ServiceResponse response = client.get(ServiceType.GATEWAY, SLOW_ENDPOINT + "?delay=2900", validToken);
        long duration = System.currentTimeMillis() - startTime;

        logStep("Duration: " + duration + "ms | Status: HTTP " + response.getStatusCode());

        assertThat(duration).as("Should complete just under 3s").isLessThan(3500L);
        assertThat(response.getStatusCode()).isEqualTo(200);
    }

    // ============================================================
    // Baseline performance
    // ============================================================

    @Test(priority = 7)
    @Story("Timeout - Normal Performance")
    @Severity(SeverityLevel.NORMAL)
    @Description("Normal requests should complete well under the timeout, as a baseline")
    public void testNormalRequestPerformance() {
        long startTime = System.currentTimeMillis();
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/api/users/health", validToken);
        long duration = System.currentTimeMillis() - startTime;

        logStep("Duration: " + duration + "ms | Status: HTTP " + response.getStatusCode());

        assertThat(response.getStatusCode())
                .as("Baseline request should succeed, not just be fast")
                .isEqualTo(200);
        assertThat(duration).as("Normal requests should complete quickly").isLessThan(1000L);
    }

    // ============================================================
    // Concurrency isolation
    // ============================================================

    @Test(priority = 8)
    @Story("Timeout - Concurrent Isolation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("A slow request in flight should not block unrelated fast requests")
    public void testSlowRequestDoesNotBlockConcurrentFastRequest() {
        skipIfSlowEndpointUnavailable();

        CompletableFuture<Long> slowCall = CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            client.get(ServiceType.GATEWAY, SLOW_ENDPOINT + "?delay=3000", validToken);
            return System.currentTimeMillis() - start;
        });

        // Give the slow call a head start so it's in flight when the fast call fires
        sleepQuietly(200);

        long fastStart = System.currentTimeMillis();
        ServiceResponse fastResponse = client.get(ServiceType.GATEWAY, "/api/users/health", validToken);
        long fastDuration = System.currentTimeMillis() - fastStart;

        logStep("Fast request completed in " + fastDuration + "ms while slow request was in flight");

        assertThat(fastResponse.getStatusCode()).isEqualTo(200);
        assertThat(fastDuration)
                .as("Fast request should not be blocked by a concurrent slow request")
                .isLessThan(1000L);

        long slowDuration = slowCall.join(); // don't leak the async call past the test
        assertThat(slowDuration)
                .as("Slow call should have actually taken ~3s (proves it wasn't itself failing fast, " +
                        "e.g. on auth, which would make this isolation check meaningless)")
                .isGreaterThan(2500L);
    }

    // ============================================================
    // Consistency across services
    // ============================================================

    @Test(priority = 9)
    @Story("Gateway - Timeout Handling")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify response-timeout behavior is consistent across all four services " +
            "(rules out a per-route timeout misconfiguration affecting only some services)")
    public void test_ResponseTimeoutConsistentAcrossServices() {
        String[] paths = {
                "/api/users/test/slow?delay=5000",
                "/api/orders/test/slow?delay=5000",
                "/api/payments/test/slow?delay=5000",
                "/api/products/test/slow?delay=5000"
        };

        for (String path : paths) {
            ServiceResponse response = client.get(ServiceType.GATEWAY, path, validToken);
            assertThat(response.getStatusCode())
                    .as(path + " should time out consistently with the other services")
                    .isIn(500, 502, 503, 504);
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void skipIfSlowEndpointUnavailable() {
        // Distinguishes "route genuinely unreachable" (500 — the actual
        // failure mode found during investigation, from the RewritePath
        // bug) from "route not found" (404) from "not authorized" (401,
        // which shouldn't happen here since validToken is supplied — if
        // it does, that's a real problem worth failing loudly on, not
        // silently skipping).
        ServiceResponse check = client.get(ServiceType.GATEWAY, SLOW_ENDPOINT + "?delay=100", validToken);
        int status = check.getStatusCode();

        if (status == 401) {
            throw new AssertionError(
                    "Slow endpoint check got 401 with a valid token — auth filter or token setup is broken, " +
                            "not a missing-endpoint situation. Failing rather than skipping.");
        }
        if (status == 404 || status == 500) {
            logStep("⚠ SKIPPED: " + SLOW_ENDPOINT + " unreachable (status " + status + ") — " +
                    "check TimeoutTestController exists on user-service and the gateway RewritePath " +
                    "target is /api/v1/test/** (not /api/v1/users/test/**)");
            throw new SkipException("Slow endpoint not available (status " + status + ")");
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}