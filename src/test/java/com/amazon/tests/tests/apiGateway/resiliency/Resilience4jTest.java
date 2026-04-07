package com.amazon.tests.tests.apiGateway.resiliency;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.TestDataFactory;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.awaitility.Awaitility;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Resilience4j circuit breaker tests.
 *
 * These tests run against the cb-test Spring profile which configures:
 *   sliding-window-size: 3
 *   minimum-number-of-calls: 3
 *   failure-rate-threshold: 50%  →  2+ failures out of 3 = OPEN
 *   wait-duration-in-open-state: 2s  (not 30s)
 *   permitted-number-of-calls-in-half-open-state: 2
 *
 * WHY cb-test profile and not mock?
 * ─────────────────────────────────
 * Option A — Mock (e.g. Mockito / WireMock):
 *   Pro: fully isolated, no infra needed, instant
 *   Con: you're testing that your mock behaves like a CB, not that the CB
 *        itself is wired correctly. The most common real bug — CB config not
 *        applied because a @Bean overrides the YAML — is invisible to mocks.
 *
 * Option B — Real CB with production thresholds:
 *   Pro: tests the real thing
 *   Con: slidingWindow=10, minimumCalls=5, waitDuration=30s makes tests
 *        take 3-5 minutes and require 10+ infra calls per scenario.
 *
 * Option C (chosen) — Real CB with cb-test profile thresholds:
 *   Pro: tests the real wiring, real state machine, real metrics
 *        Each scenario completes in 3-5 seconds
 *        Zero mocking — nothing is faked
 *   Con: requires the service to start with cb-test profile
 *        (set SPRING_PROFILES_ACTIVE=local-test,cb-test in docker-compose)
 *
 * The /test/simulate-failure endpoint decorates a throwing supplier through
 * the named CB — identical path to production Kafka publish failures.
 * It does NOT call transitionToOpenState() which bypasses CB mechanics.
 *
 * Test order is intentional:
 *   1-2:  baseline — CB starts CLOSED, metrics at zero
 *   3-5:  CLOSED → OPEN transition via real failures
 *   6-7:  OPEN state — calls rejected, 503 returned
 *   8-10: OPEN → HALF_OPEN automatic transition (wait 2s)
 *   11-13: HALF_OPEN → CLOSED via probe successes
 *   14-15: HALF_OPEN → OPEN via probe failures (re-trip)
 *   16-17: rate limiter exhaustion and recovery
 *   18:   retry count verification
 *   19:   Prometheus metrics presence
 */
@Epic("Amazon Microservices")
@Feature("Resilience4j — Circuit Breaker State Machine")
public class Resilience4jTest extends BaseTest {

    private static final String ORDER_SERVICE_URL = "http://localhost:8083";
    private static final String CB_ENDPOINT   = "/api/v1/resilience/circuit-breakers";
    private static final String BASE          = "/api/v1/resilience";
    // WHY kafkaPaymentPublisher not paymentService?
    // The gateway also has a CB named paymentService (protects the HTTP route).
    // This CB protects Kafka publish calls inside order-service — a completely
    // different layer. The distinct name removes ambiguity.
    private static final String CB_PAYMENT    = "kafkaPaymentPublisher";
    private static final String CB_ORDER_EVT  = "orderEventPublisher";

    // Dedicated paths per CB — no query param, no accidental CB creation
    private static final String PAY_FAIL  = BASE + "/test/payment-cb/failure";
    private static final String PAY_OK    = BASE + "/test/payment-cb/success";
    private static final String ORD_FAIL  = BASE + "/test/order-event-cb/failure";
    private static final String ORD_OK    = BASE + "/test/order-event-cb/success";
    private static final String RL_EXHAUST = BASE + "/test/rate-limit/exhaust";

    // ── Setup / Teardown ──────────────────────────────────────────────────────

    @BeforeMethod(alwaysRun = true)
    public void resetCircuitBreakers() {
        // Wipe both CBs before every test — cb.reset() clears the sliding
        // window so no residual failures bleed across tests.
        for (String name : List.of(CB_PAYMENT, CB_ORDER_EVT)) {
            given().baseUri(ORDER_SERVICE_URL)
                    .pathParam("name", name)
                    .when().post(CB_ENDPOINT + "/{name}/reset")
                    .then().statusCode(200);
        }
        logStep("Circuit breakers reset to CLOSED before test");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Drive N failures through the named CB. */
    private void driveFailures(String cbName, int count) {
        String path = CB_PAYMENT.equals(cbName) ? PAY_FAIL : ORD_FAIL;
        for (int i = 0; i < count; i++) {
            given().baseUri(ORDER_SERVICE_URL)
                    .when().post(path)
                    .then().statusCode(anyOf(equalTo(200), equalTo(503)));
        }
    }

    /** Drive N successes through the named CB. Used to drive HALF_OPEN → CLOSED. */
    private void driveSuccesses(String cbName, int count) {
        String path = CB_PAYMENT.equals(cbName) ? PAY_OK : ORD_OK;
        for (int i = 0; i < count; i++) {
            given().baseUri(ORDER_SERVICE_URL)
                    .when().post(path)
                    .then().statusCode(200)
                    .body("callOutcome", equalTo("success-recorded"));
        }
    }

    /** Poll until the named CB reaches the expected state or timeout. */
    private void waitForState(String cbName, String expectedState, Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() ->
                        given().baseUri(ORDER_SERVICE_URL)
                                .pathParam("name", cbName)
                                .when().get(CB_ENDPOINT + "/{name}")
                                .then().statusCode(200)
                                .body("state", equalTo(expectedState))
                );
    }

    private String getCbState(String cbName) {
        return given().baseUri(ORDER_SERVICE_URL)
                .pathParam("name", cbName)
                .when().get(CB_ENDPOINT + "/{name}")
                .then().statusCode(200)
                .extract().jsonPath().getString("state");
    }

    // ── 1. Baseline: CB starts CLOSED ────────────────────────────────────────

    @Test(priority = 1)
    @Story("CLOSED state — baseline")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Both circuit breakers are CLOSED at startup with zero buffered calls")
    public void testCircuitBreakersInitiallyClosedWithCleanMetrics() {
        logStep("Verifying both CBs start CLOSED with empty sliding window");

        for (String name : List.of(CB_PAYMENT, CB_ORDER_EVT)) {
            Response resp = given().baseUri(ORDER_SERVICE_URL)
                    .pathParam("name", name)
                    .when().get(CB_ENDPOINT + "/{name}")
                    .then().statusCode(200)
                    .body("state", equalTo("CLOSED"))
                    .body("numberOfBufferedCalls", equalTo(0))
                    .body("numberOfFailedCalls", equalTo(0))
                    .extract().response();

            // failureRate is -1.0 when window not yet filled — not 0.0
            // -1.0 means "not enough calls to evaluate" in Resilience4j
            float failureRate = resp.jsonPath().getFloat("failureRate");
            assertThat(failureRate).isLessThan(0)
                    .as("failureRate should be -1.0 (not evaluated) before minimumNumberOfCalls");

            logStep("✅ " + name + " CLOSED, failureRate=" + failureRate
                    + " (unevaluated — sliding window empty)");
        }
    }

    // ── 2. CLOSED → stays CLOSED below threshold ─────────────────────────────

    @Test(priority = 2)
    @Story("CLOSED state — partial failures below threshold")
    @Severity(SeverityLevel.NORMAL)
    @Description("One failure in a window of 3 keeps CB CLOSED — rate is 33%, threshold is 50%")
    public void testCircuitBreakerStaysClosedBelowThreshold() {
        logStep("Recording 1 failure + 2 successes — below 50% threshold");

        // 1 failure
        given().baseUri(ORDER_SERVICE_URL)
                .when().post(PAY_FAIL)
                .then().statusCode(200)
                .body("state", equalTo("CLOSED"))
                .body("callOutcome", equalTo("failure-recorded"));

        // 2 successes — fills the window of 3
        driveSuccesses(CB_PAYMENT, 2);

        Response resp = given().baseUri(ORDER_SERVICE_URL)
                .pathParam("name", CB_PAYMENT)
                .when().get(CB_ENDPOINT + "/{name}")
                .then().statusCode(200)
                .body("state", equalTo("CLOSED"))
                .extract().response();

        float failureRate = resp.jsonPath().getFloat("failureRate");
        assertThat(failureRate).isBetween(30f, 40f)
                .as("1 of 3 calls failed → ~33% failure rate");
        assertThat(getCbState(CB_PAYMENT)).isEqualTo("CLOSED");

        logStep("✅ CB CLOSED with failureRate=" + failureRate + "% (below 50% threshold)");
    }

    // ── 3. CLOSED → OPEN: real failure-rate threshold trip ───────────────────

    @Test(priority = 3)
    @Story("CLOSED → OPEN transition")
    @Severity(SeverityLevel.BLOCKER)
    @Description("CB trips OPEN after 2 of 3 calls fail — exactly at the 50% threshold")
    public void testCircuitBreakerTripsOpenAfterFailureThreshold() {
        logStep("Driving 2 failures + 1 success to fill sliding window");

        // Failure 1
        given().baseUri(ORDER_SERVICE_URL)
                .when().post(PAY_FAIL)
                .then().statusCode(200)
                .body("state", equalTo("CLOSED"))
                .body("numberOfFailedCalls", equalTo(1));

        // Failure 2
        given().baseUri(ORDER_SERVICE_URL)
                .when().post(PAY_FAIL)
                .then().statusCode(200)
                .body("state", equalTo("CLOSED"))
                .body("numberOfFailedCalls", equalTo(2));

        // Failure 3 — window = 3, failures = 3, rate = 100% > 50% threshold → OPEN
        given().baseUri(ORDER_SERVICE_URL)
                .when().post(PAY_FAIL)
                .then().statusCode(anyOf(equalTo(200), equalTo(503)));
        // At the boundary the 3rd call may be the one that tips it;
        // state is OPEN after this call completes

        waitForState(CB_PAYMENT, "OPEN", Duration.ofSeconds(3));

        Response openResp = given().baseUri(ORDER_SERVICE_URL)
                .pathParam("name", CB_PAYMENT)
                .when().get(CB_ENDPOINT + "/{name}")
                .then().statusCode(200)
                .body("state", equalTo("OPEN"))
                .extract().response();

        float failureRate = openResp.jsonPath().getFloat("failureRate");
        assertThat(failureRate).isGreaterThanOrEqualTo(50f)
                .as("CB should trip at or above 50% failure rate");

        logStep("✅ CB OPEN — failureRate=" + failureRate
                + "%, notPermittedCalls=" + openResp.jsonPath().getInt("notPermittedCalls"));
    }

    // ── 4. OPEN: calls are rejected (not just failed) ─────────────────────────

    @Test(priority = 4, dependsOnMethods = "testCircuitBreakerTripsOpenAfterFailureThreshold")
    @Story("OPEN state — calls rejected")
    @Severity(SeverityLevel.BLOCKER)
    @Description("While CB is OPEN, simulate endpoint returns 503 and notPermittedCalls increments")
    public void testOpenCircuitBreakerRejectsCallsWithout503() {
        // Ensure CB is OPEN (depends on previous test)
        assertThat(getCbState(CB_PAYMENT)).isEqualTo("OPEN");

        int notPermittedBefore = given().baseUri(ORDER_SERVICE_URL)
                .pathParam("name", CB_PAYMENT)
                .when().get(CB_ENDPOINT + "/{name}")
                .then().extract().jsonPath().getInt("notPermittedCalls");

        logStep("CB is OPEN — firing 3 calls, all should be rejected");

        for (int i = 0; i < 3; i++) {
            given().baseUri(ORDER_SERVICE_URL)
                    .when().post(PAY_FAIL)
                    .then()
                    // 503 = CallNotPermittedException — CB rejected the call
                    .statusCode(503)
                    .body("callOutcome", equalTo("rejected-circuit-open"))
                    .body("state", equalTo("OPEN"));
        }

        int notPermittedAfter = given().baseUri(ORDER_SERVICE_URL)
                .pathParam("name", CB_PAYMENT)
                .when().get(CB_ENDPOINT + "/{name}")
                .then().extract().jsonPath().getInt("notPermittedCalls");

        assertThat(notPermittedAfter).isGreaterThan(notPermittedBefore)
                .as("notPermittedCalls must increment for each rejected call");

        logStep("✅ OPEN CB rejected calls. notPermittedCalls: "
                + notPermittedBefore + " → " + notPermittedAfter);
    }

    // ── 5. OPEN → HALF_OPEN: automatic after waitDuration ────────────────────

    @Test(priority = 5)
    @Story("OPEN → HALF_OPEN transition")
    @Severity(SeverityLevel.BLOCKER)
    @Description("CB automatically transitions to HALF_OPEN after waitDurationInOpenState (2s in cb-test profile)")
    public void testOpenCircuitBreakerTransitionsToHalfOpenAutomatically() {
        logStep("Tripping CB to OPEN then waiting 2s for automatic HALF_OPEN transition");

        // Trip CB to OPEN
        driveFailures(CB_PAYMENT, 3);
        waitForState(CB_PAYMENT, "OPEN", Duration.ofSeconds(3));
        logStep("CB is now OPEN. Waiting 2s for automatic HALF_OPEN transition...");

        // cb-test profile sets waitDurationInOpenState: 2s
        // Poll for HALF_OPEN with 5s timeout (generous margin for CI)
        waitForState(CB_PAYMENT, "HALF_OPEN", Duration.ofSeconds(5));

        Response halfOpenResp = given().baseUri(ORDER_SERVICE_URL)
                .pathParam("name", CB_PAYMENT)
                .when().get(CB_ENDPOINT + "/{name}")
                .then().statusCode(200)
                .body("state", equalTo("HALF_OPEN"))
                .extract().response();

        // In HALF_OPEN, bufferedCalls resets to 0 — fresh probe window
        assertThat(halfOpenResp.jsonPath().getInt("numberOfBufferedCalls"))
                .isEqualTo(0)
                .as("HALF_OPEN resets the sliding window for probe calls");

        logStep("✅ CB automatically moved to HALF_OPEN after waitDuration");
    }

    // ── 6. HALF_OPEN → CLOSED: probe successes ───────────────────────────────

    @Test(priority = 6)
    @Story("HALF_OPEN → CLOSED transition")
    @Severity(SeverityLevel.BLOCKER)
    @Description("CB closes after permittedNumberOfCallsInHalfOpenState (2) probe calls succeed")
    public void testHalfOpenCircuitBreakerClosesAfterProbeSuccesses() {
        // Get to HALF_OPEN
        driveFailures(CB_PAYMENT, 3);
        waitForState(CB_PAYMENT, "OPEN", Duration.ofSeconds(3));
        waitForState(CB_PAYMENT, "HALF_OPEN", Duration.ofSeconds(5));
        logStep("CB is HALF_OPEN — sending 2 probe successes");

        // Probe success 1
        given().baseUri(ORDER_SERVICE_URL)
                .when().post(PAY_OK)
                .then().statusCode(200)
                .body("callOutcome", equalTo("success-recorded"))
                .body("state", anyOf(equalTo("HALF_OPEN"), equalTo("CLOSED")));

        // Probe success 2 — fills the half-open probe window (permittedCalls=2)
        given().baseUri(ORDER_SERVICE_URL)
                .when().post(PAY_OK)
                .then().statusCode(200)
                .body("callOutcome", equalTo("success-recorded"));

        waitForState(CB_PAYMENT, "CLOSED", Duration.ofSeconds(3));

        Response closedResp = given().baseUri(ORDER_SERVICE_URL)
                .pathParam("name", CB_PAYMENT)
                .when().get(CB_ENDPOINT + "/{name}")
                .then().statusCode(200)
                .body("state", equalTo("CLOSED"))
                .extract().response();

        // After closing, the sliding window resets
        assertThat(closedResp.jsonPath().getInt("numberOfFailedCalls"))
                .isEqualTo(0)
                .as("Failure count resets when CB closes from HALF_OPEN");

        logStep("✅ HALF_OPEN → CLOSED after 2 probe successes");
    }

    // ── 7. HALF_OPEN → OPEN: probe failures re-trip the CB ───────────────────

    @Test(priority = 7)
    @Story("HALF_OPEN → OPEN re-trip")
    @Severity(SeverityLevel.CRITICAL)
    @Description("CB goes back to OPEN if probe calls in HALF_OPEN exceed failure threshold")
    public void testHalfOpenCircuitBreakerRetripsOnProbeFailures() {
        // Get to HALF_OPEN
        driveFailures(CB_PAYMENT, 3);
        waitForState(CB_PAYMENT, "OPEN", Duration.ofSeconds(3));
        waitForState(CB_PAYMENT, "HALF_OPEN", Duration.ofSeconds(5));
        logStep("CB is HALF_OPEN — sending 2 probe FAILURES");

        // Probe failure 1
        given().baseUri(ORDER_SERVICE_URL)
                .when().post(PAY_FAIL)
                .then().statusCode(anyOf(equalTo(200), equalTo(503)));

        // Probe failure 2 — 2/2 failures = 100% > 50% threshold → back to OPEN
        given().baseUri(ORDER_SERVICE_URL)
                .when().post(PAY_FAIL)
                .then().statusCode(anyOf(equalTo(200), equalTo(503)));

        waitForState(CB_PAYMENT, "OPEN", Duration.ofSeconds(3));

        logStep("✅ HALF_OPEN → OPEN confirmed after probe failures re-tripped the CB");
    }

    // ── 8. Full CLOSED → OPEN → HALF_OPEN → CLOSED cycle ────────────────────

    @Test(priority = 8)
    @Story("Full CB state cycle")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Complete CLOSED → OPEN → HALF_OPEN → CLOSED state machine cycle with metrics at each stage")
    public void testFullCircuitBreakerStateCycle() {
        logStep("Starting full CB state machine cycle for: " + CB_ORDER_EVT);

        // Stage 1: CLOSED
        assertThat(getCbState(CB_ORDER_EVT)).isEqualTo("CLOSED");
        logStep("Stage 1: CLOSED ✓");

        // Stage 2: Drive failures → OPEN
        driveFailures(CB_ORDER_EVT, 3);
        waitForState(CB_ORDER_EVT, "OPEN", Duration.ofSeconds(3));

        Response openMetrics = given().baseUri(ORDER_SERVICE_URL)
                .pathParam("name", CB_ORDER_EVT)
                .when().get(CB_ENDPOINT + "/{name}")
                .then().body("state", equalTo("OPEN"))
                .body("failureRate", greaterThanOrEqualTo(50f))
                .body("numberOfFailedCalls", greaterThanOrEqualTo(2))
                .extract().response();
        logStep("Stage 2: OPEN — failureRate=" + openMetrics.jsonPath().getFloat("failureRate") + "% ✓");

        // Verify OPEN rejects calls immediately
        given().baseUri(ORDER_SERVICE_URL)
                .when().post(ORD_OK)
                .then().statusCode(503)
                .body("callOutcome", equalTo("rejected-circuit-open"));
        logStep("Stage 2b: OPEN rejected probe call ✓");

        // Stage 3: Wait for HALF_OPEN
        waitForState(CB_ORDER_EVT, "HALF_OPEN", Duration.ofSeconds(5));
        logStep("Stage 3: HALF_OPEN ✓");

        // Stage 4: Probe successes → CLOSED
        driveSuccesses(CB_ORDER_EVT, 2); // permittedCalls=2 in cb-test
        waitForState(CB_ORDER_EVT, "CLOSED", Duration.ofSeconds(3));

        Response closedMetrics = given().baseUri(ORDER_SERVICE_URL)
                .pathParam("name", CB_ORDER_EVT)
                .when().get(CB_ENDPOINT + "/{name}")
                .then().body("state", equalTo("CLOSED"))
                .extract().response();

        logStep("Stage 4: CLOSED — recovered ✓ failureRate="
                + closedMetrics.jsonPath().getFloat("failureRate"));

        logStep("✅ Full CLOSED → OPEN → HALF_OPEN → CLOSED cycle verified");
    }

    // ── 9. Failure rate metric accuracy ──────────────────────────────────────

    @Test(priority = 9)
    @Story("CB metrics accuracy")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify failure rate is calculated correctly from the sliding window")
    public void testFailureRateCalculatedCorrectly() {
        logStep("Recording exactly 3 calls: 2 success + 1 failure = 33.3%");

        driveSuccesses(CB_PAYMENT, 2);

        given().baseUri(ORDER_SERVICE_URL)
                .when().post(PAY_FAIL)
                .then().statusCode(200);

        Response resp = given().baseUri(ORDER_SERVICE_URL)
                .pathParam("name", CB_PAYMENT)
                .when().get(CB_ENDPOINT + "/{name}")
                .then().statusCode(200)
                .body("numberOfBufferedCalls", equalTo(3))
                .body("numberOfSuccessfulCalls", equalTo(2))
                .body("numberOfFailedCalls", equalTo(1))
                .extract().response();

        float rate = resp.jsonPath().getFloat("failureRate");
        assertThat(rate).isBetween(30f, 35f)
                .as("1 failure / 3 calls = 33.33% failure rate");
        assertThat(resp.jsonPath().getString("state")).isEqualTo("CLOSED")
                .as("33% is below the 50% threshold — CB stays CLOSED");

        logStep("✅ Failure rate = " + rate + "% — metrics accurate, CB still CLOSED");
    }

    // ── 10. notPermittedCalls increments correctly while OPEN ─────────────────

    @Test(priority = 10)
    @Story("OPEN state — notPermittedCalls counter")
    @Severity(SeverityLevel.NORMAL)
    @Description("notPermittedCalls counter increments for every rejected call while CB is OPEN")
    public void testNotPermittedCallsCounterWhileOpen() {
        driveFailures(CB_PAYMENT, 3);
        waitForState(CB_PAYMENT, "OPEN", Duration.ofSeconds(3));

        int rejectedCalls = 5;
        logStep("Firing " + rejectedCalls + " calls at OPEN CB — all should be rejected");

        for (int i = 0; i < rejectedCalls; i++) {
            given().baseUri(ORDER_SERVICE_URL)
                    .when().post(PAY_OK)
                    .then().statusCode(503);
        }

        int notPermitted = given().baseUri(ORDER_SERVICE_URL)
                .pathParam("name", CB_PAYMENT)
                .when().get(CB_ENDPOINT + "/{name}")
                .then().extract().jsonPath().getInt("notPermittedCalls");

        assertThat(notPermitted).isGreaterThanOrEqualTo(rejectedCalls)
                .as("Each rejected call should increment notPermittedCalls");

        logStep("✅ notPermittedCalls=" + notPermitted + " after " + rejectedCalls + " rejections");
    }

    // ── 11. Rate limiter: within limit ────────────────────────────────────────

    @Test(priority = 11)
    @Story("Rate limiter — within limit")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Calls within the rate limit (5/10s in cb-test) all get permitted")
    public void testRateLimiterPermitsCallsWithinLimit() {
        logStep("Sending 5 calls — exactly at the cb-test limit of 5/10s");

        Response resp = given().baseUri(ORDER_SERVICE_URL)
                .queryParam("calls", 5)
                .when().post(RL_EXHAUST)
                .then().statusCode(200)
                .body("permitted", equalTo(5))
                .body("rejected", equalTo(0))
                .extract().response();

        logStep("✅ All 5 calls permitted. Available=" + resp.jsonPath().getInt("availablePermissions"));
    }

    // ── 12. Rate limiter: exceeds limit ───────────────────────────────────────

    @Test(priority = 12)
    @Story("Rate limiter — limit exceeded")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Calls over the rate limit (>5/10s in cb-test) are rejected with 429 semantics")
    public void testRateLimiterRejectsCallsOverLimit() {
        logStep("Sending 8 calls against a 5/10s rate limiter");

        Response resp = given().baseUri(ORDER_SERVICE_URL)
                .queryParam("calls", 8)
                .when().post(RL_EXHAUST)
                .then().statusCode(200)
                .extract().response();

        int permitted = resp.jsonPath().getInt("permitted");
        int rejected  = resp.jsonPath().getInt("rejected");

        assertThat(permitted).isLessThanOrEqualTo(5)
                .as("At most 5 calls permitted in a 10s window");
        assertThat(rejected).isGreaterThanOrEqualTo(3)
                .as("At least 3 of 8 calls should be rate-limited");
        assertThat(permitted + rejected).isEqualTo(8);

        logStep("✅ Rate limiter enforced: permitted=" + permitted + ", rejected=" + rejected);
    }

    // ── 13. Concurrent orders: some succeed, none cause 5xx ──────────────────

    @Test(priority = 13)
    @Story("Rate limiter — concurrent burst")
    @Severity(SeverityLevel.CRITICAL)
    @Description("20 concurrent order creation requests: some may be 429, none should be 5xx")
    public void testConcurrentOrdersBurstNoneServerError() throws InterruptedException {
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(10.00))
                .when().post("/api/v1/products")
                .then().statusCode(201).extract().response();

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger success201 = new AtomicInteger();
        AtomicInteger rateLimit429 = new AtomicInteger();
        AtomicInteger serverError5xx = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    int status = given()
                            .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                            .header("X-User-Id", customerId)
                            .body(TestDataFactory.createOrderRequest(
                                    productResp.jsonPath().getString("id"),
                                    productResp.jsonPath().getString("name"),
                                    new java.math.BigDecimal("10.00")))
                            .when().post("/api/v1/orders")
                            .then().extract().response().statusCode();

                    if (status == 201)      success201.incrementAndGet();
                    else if (status == 429) rateLimit429.incrementAndGet();
                    else if (status >= 500) serverError5xx.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

       // latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        logStep("Results — 201: " + success201.get()
                + " | 429: " + rateLimit429.get()
                + " | 5xx: " + serverError5xx.get());

        assertThat(serverError5xx.get())
                .as("No 5xx errors — CB fallback or rate limiter should handle overload gracefully")
                .isEqualTo(0);
        assertThat(success201.get())
                .as("At least some requests must succeed")
                .isGreaterThan(0);

        logStep("✅ Burst of 20 concurrent requests handled — no 5xx errors");
    }

    // ── 14. Prometheus metrics contain CB and RL metrics ─────────────────────

    @Test(priority = 14)
    @Story("Prometheus metrics")
    @Severity(SeverityLevel.NORMAL)
    @Description("Prometheus endpoint exposes resilience4j CB and rate limiter metrics")
    public void testResilience4jMetricsInPrometheus() {
        String metrics = given().baseUri(ORDER_SERVICE_URL)
                .when().get("/actuator/prometheus")
                .then().statusCode(200)
                .extract().asString();

        // CB metrics
        assertThat(metrics).contains("resilience4j_circuitbreaker_state")
                .as("CB state metric must be present");
        assertThat(metrics).contains("resilience4j_circuitbreaker_calls_total")
                .as("CB call counter must be present");
        assertThat(metrics).contains("resilience4j_circuitbreaker_failure_rate")
                .as("CB failure rate gauge must be present");

        // Rate limiter metrics
        assertThat(metrics).contains("resilience4j_ratelimiter_available_permissions")
                .as("Rate limiter available permissions gauge must be present");

        logStep("✅ All Resilience4j Prometheus metrics present");
    }

    // ── 15. 404 for unknown CB name ───────────────────────────────────────────

    @Test(priority = 15)
    @Story("CB not found")
    @Severity(SeverityLevel.MINOR)
    @Description("Requesting a non-existent circuit breaker name returns 404")
    public void testUnknownCircuitBreakerReturns404() {
        given().baseUri(ORDER_SERVICE_URL)
                .pathParam("name", "doesNotExist_xyz_abc")
                .when().get(CB_ENDPOINT + "/{name}")
                .then().statusCode(404);

        logStep("✅ 404 for unknown circuit breaker name");
    }
}