package com.amazon.tests.regression.apiGateway.errorHandling;

import com.amazon.tests.BaseTest;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.transport.ServiceType;
import com.amazon.tests.utils.apiClients.RawApiClient;
import io.qameta.allure.*;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway Timeout Tests
 *
 * Config under test:
 *   connect-timeout:  1s (TCP connection phase)
 *   response-timeout: 3s (HTTP response phase)
 *   timelimiter:      3s (total request time)
 *
 * Note: response-timeout and timelimiter are both 3s, so tests can't
 * distinguish which one enforces a given timeout — both are exercised
 * together and treated as one observable 3s ceiling.
 */
@Epic("API Gateway")
@Feature("Timeout Handling")
public class TimeoutTests extends BaseTest {

    private static final String SLOW_ENDPOINT = "/api/users/test/slow";

    private RawApiClient client;

    @BeforeClass
    public void setup() {
        client = new RawApiClient(context.getExecutor());
    }

    @Test(priority = 1)
    @Story("Timeout - No Infinite Hang")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Requests to non-existent routes should fail quickly, not hang")
    public void testNoInfiniteHang() {
        logStep("=== Test 1: No Infinite Hang ===");
        logStep("Note: verifies absence of an indefinite hang only — a 404 here");
        logStep("      resolves at the routing layer and does not exercise the");
        logStep("      1s connect-timeout itself (that requires an unreachable");
        logStep("      backend host, not currently configured for this suite).");

        long startTime = System.currentTimeMillis();
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/api/completely-nonexistent-route", null);
        long duration = System.currentTimeMillis() - startTime;

        logStep("Duration: " + duration + "ms | Status: HTTP " + response.getStatusCode());

        assertThat(duration).as("Should not hang indefinitely").isLessThan(5000L);
        assertThat(response.getStatusCode()).as("Should return a 404 (no route matched)").isEqualTo(404);
    }

    @Test(priority = 2)
    @Story("Timeout - Response/TimeLimiter Timeout")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Slow responses should timeout at 3 seconds")
    public void testResponseTimeout() {
        logStep("=== Test 2: Response/TimeLimiter Timeout (3s) ===");
        skipIfSlowEndpointUnavailable();

        long startTime = System.currentTimeMillis();
        ServiceResponse response = client.get(ServiceType.GATEWAY, SLOW_ENDPOINT + "?delay=5000", null);
        long duration = System.currentTimeMillis() - startTime;

        logStep("Duration: " + duration + "ms | Status: HTTP " + response.getStatusCode());

        assertThat(duration).as("Should timeout at ~3s").isBetween(2500L, 4000L);
        assertThat(response.getStatusCode()).as("Should return a timeout error").isIn(503, 504);
    }

    @Test(priority = 3)
    @Story("Timeout - Fast Response")
    @Severity(SeverityLevel.NORMAL)
    @Description("Fast responses should complete successfully")
    public void testFastResponse() {
        logStep("=== Test 3: Fast Response (1s) ===");
        skipIfSlowEndpointUnavailable();

        long startTime = System.currentTimeMillis();
        ServiceResponse response = client.get(ServiceType.GATEWAY, SLOW_ENDPOINT + "?delay=1000", null);
        long duration = System.currentTimeMillis() - startTime;

        logStep("Duration: " + duration + "ms | Status: HTTP " + response.getStatusCode());

        assertThat(duration).as("Should complete in ~1s").isBetween(800L, 2000L);
        assertThat(response.getStatusCode()).isEqualTo(200);
    }

    @Test(priority = 4)
    @Story("Timeout - Boundary Test")
    @Severity(SeverityLevel.NORMAL)
    @Description("Requests just under the timeout threshold should succeed")
    public void testBoundaryUnderTimeout() {
        logStep("=== Test 4: Boundary Test (2.9s) ===");
        skipIfSlowEndpointUnavailable();

        long startTime = System.currentTimeMillis();
        ServiceResponse response = client.get(ServiceType.GATEWAY, SLOW_ENDPOINT + "?delay=2900", null);
        long duration = System.currentTimeMillis() - startTime;

        logStep("Duration: " + duration + "ms | Status: HTTP " + response.getStatusCode());

        assertThat(duration).as("Should complete just under 3s").isLessThan(3500L);
        assertThat(response.getStatusCode()).isEqualTo(200);
    }

    @Test(priority = 5)
    @Story("Timeout - Normal Performance")
    @Severity(SeverityLevel.NORMAL)
    @Description("Normal requests should complete well under the timeout, as a baseline")
    public void testNormalRequestPerformance() {
        logStep("=== Test 5: Normal Request Performance ===");

        long startTime = System.currentTimeMillis();
        ServiceResponse response = client.get(ServiceType.GATEWAY, "/api/users/health", null);
        long duration = System.currentTimeMillis() - startTime;

        logStep("Duration: " + duration + "ms | Status: HTTP " + response.getStatusCode());

        assertThat(duration).as("Normal requests should complete quickly").isLessThan(1000L);
    }

    @Test(priority = 6)
    @Story("Timeout - Concurrent Isolation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("A slow request in flight should not block unrelated fast requests")
    public void testSlowRequestDoesNotBlockConcurrentFastRequest() {
        logStep("=== Test 6: Concurrent Request Isolation ===");
        skipIfSlowEndpointUnavailable();

        CompletableFuture<Long> slowCall = CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            client.get(ServiceType.GATEWAY, SLOW_ENDPOINT + "?delay=3000", null);
            return System.currentTimeMillis() - start;
        });

        // Give the slow call a head start so it's in flight when the fast call fires
        sleepQuietly(200);

        long fastStart = System.currentTimeMillis();
        ServiceResponse fastResponse = client.get(ServiceType.GATEWAY, "/api/users/health", null);
        long fastDuration = System.currentTimeMillis() - fastStart;

        logStep("Fast request completed in " + fastDuration + "ms while slow request was in flight");

        assertThat(fastResponse.getStatusCode()).isEqualTo(200);
        assertThat(fastDuration)
                .as("Fast request should not be blocked by a concurrent slow request")
                .isLessThan(1000L);

        slowCall.join(); // don't leak the async call past the test
    }

    // ══════════════════════════════════════════════════════════════

    private void skipIfSlowEndpointUnavailable() {
        ServiceResponse check = client.get(ServiceType.GATEWAY, SLOW_ENDPOINT + "?delay=100", null);
        if (check.getStatusCode() == 404) {
            logStep("⚠ SKIPPED: " + SLOW_ENDPOINT + " not found — add TimeoutTestController to user-service to enable");
            throw new SkipException("Slow endpoint not available");
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