package com.amazon.tests.tests.apiGateway.resiliency.circuitBreaker;

import com.amazon.tests.tests.BaseTest;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.amazon.tests.utils.CircuitBreakerUtil.*;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Automated Gateway Circuit Breaker Tests
 *
 * FULLY AUTOMATED - Zero manual intervention required!
 *
 * Prerequisites:
 *   1. Add CircuitBreakerTestController.java to each service
 *   2. Add CircuitBreakerTestFilter.java to each service
 *   3. Add CircuitBreakerTestState.java to each service
 *   4. Gateway running on port 8080
 *   5. All services running (user:8081, product:8082, order:8083)
 *
 * Test Control Endpoints (called by this test):
 *   POST http://localhost:8081/api/v1/test/circuit-breaker/fail     → Make service "down"
 *   POST http://localhost:8081/api/v1/test/circuit-breaker/recover  → Make service "up"
 *   GET  http://localhost:8081/api/v1/test/circuit-breaker/status   → Check status
 */
@Epic("Amazon Microservices")
@Feature("API Gateway - Automated Circuit Breaker Tests")
public class CircuitBreakerTest extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final String USER_SERVICE_URL = "http://localhost:8081";
    private static final String PRODUCT_SERVICE_URL = "http://localhost:8082";
    private static final String ORDER_SERVICE_URL = "http://localhost:8083";

    private static final String USER_HEALTH_VIA_GATEWAY = "/api/users/health";
    private static final String PRODUCT_LIST_VIA_GATEWAY = "/api/products";
    private static final String ORDER_LIST_VIA_GATEWAY = "/api/orders";

    private static final String CB_USER = "userService";
    private static final String CB_PRODUCT = "productService";
    private static final String CB_ORDER = "orderService";

    // Service configurations
    private static final String[] SERVICE_URLS = {
            USER_SERVICE_URL, PRODUCT_SERVICE_URL, ORDER_SERVICE_URL
    };
    private static final String[] SERVICE_NAMES = {
            "user-service", "product-service", "order-service"
    };

    // ── Setup / Teardown ──────────────────────────────────────────────────────

    @BeforeMethod(alwaysRun = true)
    public void ensureAllServicesNormal() {
        logStep("Ensuring all services in normal mode before test");
        resetAllServices(SERVICE_URLS, SERVICE_NAMES);
    }

    @AfterMethod(alwaysRun = true)
    public void cleanupServicesAfterTest() {
        logStep("Cleaning up - recovering all services after test");
        resetAllServices(SERVICE_URLS, SERVICE_NAMES);
    }

    // ── TESTS ─────────────────────────────────────────────────────────────────

    /**
     * Test 1: Verify Test Control Endpoints Available
     *
     * VALIDITY: ⚠️ PARTIALLY VALID
     *
     * Purpose: Pre-flight check to ensure CircuitBreakerTestController is installed
     * on all services before running actual circuit breaker tests.
     *
     * Issues:
     * 1. This is a SETUP VERIFICATION test, not a circuit breaker behavior test
     * 2. Should be @BeforeClass or @BeforeSuite, not a numbered test case
     * 3. If this fails, all other tests will fail - should block suite execution
     *
     * Recommendation: Move to @BeforeClass and throw exception if endpoints missing
     */
    @Test(priority = 1)
    @Story("Setup Verification")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify all services have test control endpoints available")
    public void test01_VerifyTestControlEndpointsAvailable() {
        logStep("TEST 1: Verifying test control endpoints on all services");

        List<Map.Entry<String, String>> services = List.of(
                Map.entry(USER_SERVICE_URL, "user-service"),
                Map.entry(PRODUCT_SERVICE_URL, "product-service"),
                Map.entry(ORDER_SERVICE_URL, "order-service")
        );

        boolean allAvailable = true;
        StringBuilder missingServices = new StringBuilder();

        for (Map.Entry<String, String> entry : services) {
            String url = entry.getKey();
            String name = entry.getValue();

            if (!isTestControlEndpointAvailable(url, name)) {
                allAvailable = false;
                missingServices.append(name).append(", ");
            }
        }

        if (!allAvailable) {
            String error = "Test control endpoints missing on: " +
                    missingServices.toString().replaceAll(", $", "");
            logStep("❌ TEST 1 FAILED: " + error);
            throw new RuntimeException(error +
                    "\n\nSetup Required:" +
                    "\n1. Add CircuitBreakerTestController to each service" +
                    "\n2. Add CircuitBreakerTestFilter to each service" +
                    "\n3. Add CircuitBreakerTestState to each service");
        }

        logStep("✅ TEST 1 PASSED: All test control endpoints available");
    }

    @Test(priority = 2)
    @Story("Failure Simulation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify service correctly simulates failure when /fail is called")
    public void test02_VerifyFailureSimulationWorks() {
        logStep("TEST 2: Testing failure simulation");

        // Ensure normal mode
        assertThat(isServiceInFailureMode(USER_SERVICE_URL)).isFalse();

        // Put in failure mode
        failService(USER_SERVICE_URL, "user-service");

        // Verify status shows failure mode
        assertThat(isServiceInFailureMode(USER_SERVICE_URL)).isTrue();

        // Call service directly - should get 503
        given().baseUri(USER_SERVICE_URL)
                .when().get("/api/v1/users/health")
                .then().log().all()
                .statusCode(503)
                .body("testMode", equalTo(true));

        logStep("✅ Service returns 503 in failure mode");

        // Recover service
        recoverService(USER_SERVICE_URL, "user-service");

        // Verify status shows normal mode
        assertThat(isServiceInFailureMode(USER_SERVICE_URL)).isFalse();

        // Call service - should work
        given().baseUri(USER_SERVICE_URL)
                .when().get("/api/v1/users/health")
                .then().statusCode(200);

        logStep("✅ TEST 2 PASSED: Failure simulation works correctly");
    }

    @Test
    public void debug_CheckCBEndpoint() {
        System.out.println("\n=== DEBUG: Testing CB Endpoint ===");

        // Test 1: Direct curl equivalent
        Response resp = given()
                .baseUri("http://localhost:8080")
                .when()
                .get("/cb/status/userService")
                .then()
                .log().all()
                .extract()
                .response();

        System.out.println("Status Code: " + resp.statusCode());
        System.out.println("Response Body: " + resp.asString());

        // Test 2: Using the utility method
        String state = getCircuitBreakerState("http://localhost:8080", "userService");
        System.out.println("State from utility: " + state);

        // Test 3: Trigger failure and check
        failService(USER_SERVICE_URL, "user-service");

        for (int i = 0; i < 6; i++) {
            given().baseUri("http://localhost:8080")
                    .get("/api/users/health")
                    .then().statusCode(503);
            sleep(200);
        }

        sleep(1000);

        // Check state after failures
        String stateAfter = getCircuitBreakerState("http://localhost:8080", "userService");
        System.out.println("State after 6 failures: " + stateAfter);

        // Check manually
        Response respAfter = given()
                .baseUri("http://localhost:8080")
                .get("/cb/status/userService")
                .then().extract().response();

        System.out.println("Full response after failures: " + respAfter.asString());
    }

    @Test(priority = 3)
    @Story("CB State Transition - CLOSED → OPEN")
    @Severity(SeverityLevel.BLOCKER)
    @Description("CB trips to OPEN after 6 failures (50% threshold)")
    public void test03_CBTripsToOpenOnFailures() {
        logStep("TEST 3: Tripping CB to OPEN");

        String initialState = getCircuitBreakerState(GATEWAY_URL, CB_USER);
        logStep("Initial CB state: " + initialState);

        // Make service fail
        failService(USER_SERVICE_URL, "user-service");
        sleep(500);

        // Send 6 requests through gateway
        logStep("Sending 6 failing requests through gateway...");
        for (int i = 1; i <= 6; i++) {
            given().baseUri(GATEWAY_URL)
                    .when().get(USER_HEALTH_VIA_GATEWAY)
                    .then().statusCode(503);

            logStep("  Request " + i + ": 503");

            // CHECK STATE AFTER EACH REQUEST
            String currentState = getCircuitBreakerState(GATEWAY_URL, CB_USER);
            Response cbResp = given().baseUri(GATEWAY_URL)
                    .get("/cb/status/userService")
                    .then().extract().response();
            logStep("  CB Metrics: " + cbResp.asString());

            sleep(200);
        }

        // Verify CB is OPEN
        waitForCircuitBreakerState(GATEWAY_URL, CB_USER, "OPEN", Duration.ofSeconds(7));

        logStep("✅ TEST 3 PASSED: CB tripped to OPEN");
    }

    @Test(priority = 4, dependsOnMethods = "test03_CBTripsToOpenOnFailures",enabled = false)
    @Story("CB State - OPEN Behavior")
    @Severity(SeverityLevel.CRITICAL)
    @Description("OPEN CB rejects calls immediately (fast failure)")
    public void test04_OpenCBRejectsImmediately() {
        logStep("TEST 4: Verifying OPEN CB fast failure");

        assertThat(getCircuitBreakerState(GATEWAY_URL, CB_USER)).isEqualTo("OPEN");

        long start = System.currentTimeMillis();
        given().baseUri(GATEWAY_URL)
                .when().get(USER_HEALTH_VIA_GATEWAY)
                .then().statusCode(503);
        long duration = System.currentTimeMillis() - start;

        logStep("Response time: " + duration + "ms");
        assertThat(duration).isLessThan(100);

        logStep("✅ TEST 4 PASSED: OPEN CB provides fast failure");
    }

    @Test(priority = 5, dependsOnMethods = "test04_OpenCBRejectsImmediately",enabled = false)
    @Story("CB State Transition - OPEN → HALF_OPEN")
    @Severity(SeverityLevel.BLOCKER)
    @Description("CB auto-transitions to HALF_OPEN after 10 seconds")
    public void test05_CBTransitionsToHalfOpen() {
        logStep("TEST 5: Waiting for HALF_OPEN transition (10 sec)...");

        waitForCircuitBreakerState(GATEWAY_URL, CB_USER, "HALF_OPEN", Duration.ofSeconds(15));

        logStep("✅ TEST 5 PASSED: CB transitioned to HALF_OPEN");
    }

    @Test(priority = 6, dependsOnMethods = "test05_CBTransitionsToHalfOpen",enabled = false)
    @Story("CB State Transition - HALF_OPEN → CLOSED")
    @Severity(SeverityLevel.BLOCKER)
    @Description("CB closes after 3 successful probe calls")
    public void test06_HalfOpenCBClosesOnSuccess() {
        logStep("TEST 6: Recovering CB to CLOSED");

        // Recover service
        recoverService(USER_SERVICE_URL, "user-service");
        sleep(1000);
        assertThat(isServiceInFailureMode(USER_SERVICE_URL)).isFalse();

        // Send 3 successful requests
        logStep("Sending 3 probe successes...");
        for (int i = 1; i <= 3; i++) {
            given().baseUri(GATEWAY_URL)
                    .when().get(USER_HEALTH_VIA_GATEWAY)
                    .then().statusCode(200);
            logStep("  Probe " + i + ": 200");
            sleep(300);
        }

        // Verify CB closed
        waitForCircuitBreakerState(GATEWAY_URL, CB_USER, "CLOSED", Duration.ofSeconds(5));

        logStep("✅ TEST 6 PASSED: CB recovered to CLOSED");
    }

    @Test(priority = 7,enabled = false)
    @Story("CB State Transition - HALF_OPEN → OPEN Re-trip")
    @Severity(SeverityLevel.CRITICAL)
    @Description("CB goes back to OPEN if probe calls fail")
    public void test07_HalfOpenCBRetripsOnFailures() {
        logStep("TEST 7: Testing HALF_OPEN → OPEN re-trip");

        // Trip product CB to OPEN
        failService(PRODUCT_SERVICE_URL, "product-service");
        sendRequestsThroughGateway(GATEWAY_URL, PRODUCT_LIST_VIA_GATEWAY, 6);
        waitForCircuitBreakerState(GATEWAY_URL, CB_PRODUCT, "OPEN", Duration.ofSeconds(5));

        // Wait for HALF_OPEN
        waitForCircuitBreakerState(GATEWAY_URL, CB_PRODUCT, "HALF_OPEN", Duration.ofSeconds(15));

        // Service still failing - send probes
        assertThat(isServiceInFailureMode(PRODUCT_SERVICE_URL)).isTrue();
        logStep("Sending 3 failing probes...");
        for (int i = 1; i <= 3; i++) {
            given().baseUri(GATEWAY_URL)
                    .when().get(PRODUCT_LIST_VIA_GATEWAY)
                    .then().statusCode(503);
            sleep(200);
        }

        // CB should re-trip to OPEN
        waitForCircuitBreakerState(GATEWAY_URL, CB_PRODUCT, "OPEN", Duration.ofSeconds(5));

        logStep("✅ TEST 7 PASSED: CB re-tripped to OPEN");
        recoverService(PRODUCT_SERVICE_URL, "product-service");
    }

    @Test(priority = 8,enabled = false)
    @Story("Complete CB Lifecycle")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Full automated cycle: CLOSED → OPEN → HALF_OPEN → CLOSED")
    public void test08_CompleteCBLifecycle() {
        logStep("TEST 8: Complete CB lifecycle (automated)");

        // Stage 1: CLOSED
        recoverService(ORDER_SERVICE_URL, "order-service");
        sleep(1000);
        logStep("Stage 1: CLOSED ✓");

        // Stage 2: Trip to OPEN
        failService(ORDER_SERVICE_URL, "order-service");
        sendRequestsThroughGateway(GATEWAY_URL, ORDER_LIST_VIA_GATEWAY, 6);
        waitForCircuitBreakerState(GATEWAY_URL, CB_ORDER, "OPEN", Duration.ofSeconds(5));
        logStep("Stage 2: OPEN ✓");

        // Stage 3: Wait for HALF_OPEN
        waitForCircuitBreakerState(GATEWAY_URL, CB_ORDER, "HALF_OPEN", Duration.ofSeconds(15));
        logStep("Stage 3: HALF_OPEN ✓");

        // Stage 4: Recover to CLOSED
        recoverService(ORDER_SERVICE_URL, "order-service");
        sleep(1000);
        for (int i = 0; i < 3; i++) {
            given().baseUri(GATEWAY_URL)
                    .when().get(ORDER_LIST_VIA_GATEWAY)
                    .then().statusCode(200);
            sleep(200);
        }
        waitForCircuitBreakerState(GATEWAY_URL, CB_ORDER, "CLOSED", Duration.ofSeconds(5));
        logStep("Stage 4: CLOSED ✓");

        logStep("✅ TEST 8 PASSED: Complete lifecycle verified");
    }

    @Test(priority = 9,enabled = false)
    @Story("CB Independence")
    @Severity(SeverityLevel.NORMAL)
    @Description("Multiple CBs operate independently")
    public void test09_CBsOperateIndependently() {
        logStep("TEST 9: Verifying CB independence");

        // Recover all
        recoverService(USER_SERVICE_URL, "user-service");
        recoverService(PRODUCT_SERVICE_URL, "product-service");
        recoverService(ORDER_SERVICE_URL, "order-service");
        sleep(2000);

        // Trip only user CB
        failService(USER_SERVICE_URL, "user-service");
        sendRequestsThroughGateway(GATEWAY_URL, USER_HEALTH_VIA_GATEWAY, 6);
        sleep(1000);

        String userState = getCircuitBreakerState(GATEWAY_URL, CB_USER);
        String productState = getCircuitBreakerState(GATEWAY_URL, CB_PRODUCT);
        String orderState = getCircuitBreakerState(GATEWAY_URL, CB_ORDER);

        logStep("userService: " + userState);
        logStep("productService: " + productState);
        logStep("orderService: " + orderState);

        assertThat(userState).isIn("OPEN", "HALF_OPEN");
        assertThat(productState).isIn("CLOSED", "HALF_OPEN");

        logStep("✅ TEST 9 PASSED: CBs operate independently");
    }
}