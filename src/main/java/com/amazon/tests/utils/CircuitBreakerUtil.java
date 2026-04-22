package com.amazon.tests.utils;

import io.restassured.response.Response;
import org.awaitility.Awaitility;
import org.testng.log4testng.Logger;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Circuit Breaker Test Utilities
 *
 * Helper methods for automated circuit breaker testing.
 * Provides methods to control service failure simulation and monitor CB state.
 */
public class CircuitBreakerUtil {

    private static final Logger logger = Logger.getLogger(CircuitBreakerUtil.class);
    private static final String CB_TEST_ENDPOINT = "/api/v1/test/circuit-breaker";
    private static final String CB_HEALTH_ENDPOINT = "/actuator/health/circuitbreakers";

    /**
     * Put service in failure mode
     * Calls: POST http://serviceUrl/api/v1/test/circuit-breaker/fail
     *
     * @param serviceUrl Base URL of the service
     * @param serviceName Display name for logging
     * @throws RuntimeException if endpoint not available
     */
    public static void failService(String serviceUrl, String serviceName) {
        try {
            given().baseUri(serviceUrl)
                    .when().post(CB_TEST_ENDPOINT + "/fail")
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("failure-mode-active"))
                    .body("isSimulatingFailure", equalTo(true));

            logger.info("🔴 " + serviceName + " → FAILURE MODE (will return 503)");
        } catch (Exception e) {
            String error = "Cannot put " + serviceName + " in failure mode. " +
                    "Ensure CircuitBreakerTestController is added to the service.";
            logger.error(error);
            throw new RuntimeException(error, e);
        }
    }

    /**
     * Recover service to normal operation mode
     * Calls: POST http://serviceUrl/api/v1/test/circuit-breaker/recover
     *
     * @param serviceUrl Base URL of the service
     * @param serviceName Display name for logging
     */
    public static void recoverService(String serviceUrl, String serviceName) {
        try {
            given().baseUri(serviceUrl)
                    .when().post(CB_TEST_ENDPOINT + "/recover")
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("normal-operation"))
                    .body("isSimulatingFailure", equalTo(false));

            logger.info("🟢 " + serviceName + " → NORMAL MODE");
        } catch (Exception e) {
            logger.warn("⚠️  Could not recover " + serviceName + " - may not be running");
        }
    }

    /**
     * Check if service is currently in failure simulation mode
     * Calls: GET http://serviceUrl/api/v1/test/circuit-breaker/status
     *
     * @param serviceUrl Base URL of the service
     * @return true if simulating failure, false otherwise
     */
    public static boolean isServiceInFailureMode(String serviceUrl) {
        try {
            Response resp = given().baseUri(serviceUrl)
                    .when().get(CB_TEST_ENDPOINT + "/status")
                    .then().statusCode(200)
                    .extract().response();

            return resp.jsonPath().getBoolean("isSimulatingFailure");
        } catch (Exception e) {
            logger.warn("Could not check failure mode for " + serviceUrl);
            return false;
        }
    }

    /**
     * Verify test control endpoint is available on service
     *
     * @param serviceUrl Base URL of the service
     * @param serviceName Display name for logging
     * @return true if endpoint available, false otherwise
     */
    public static boolean isTestControlEndpointAvailable(String serviceUrl, String serviceName) {
        try {
            Response resp = given().baseUri(serviceUrl)
                    .when().get(CB_TEST_ENDPOINT + "/status")
                    .then()
                    .statusCode(200)
                    .extract().response();

            boolean isFailure = resp.jsonPath().getBoolean("isSimulatingFailure");
            logger.info("✅ " + serviceName + " endpoint OK (mode: " +
                    (isFailure ? "FAILURE" : "NORMAL") + ")");
            return true;
        } catch (Exception e) {
            logger.error("❌ " + serviceName + " test endpoint NOT available: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get current state of circuit breaker from gateway
     *
     * @param gatewayUrl Gateway base URL
     * @param cbName Circuit breaker name (e.g., "userService")
     * @return CB state (CLOSED, OPEN, HALF_OPEN) or null if not found
     */
    public static String getCircuitBreakerState(String gatewayUrl, String cbName) {
        try {
            Response resp = given().baseUri(gatewayUrl)
                    .when().get("/cb/status/" + cbName)  // ← Changed from /actuator/health/circuitbreakers
                    .then().extract().response();

            if (resp.statusCode() != 200) {
                logger.warn("CB status endpoint returned " + resp.statusCode());
                return null;
            }

            return resp.jsonPath().getString("state");  // ← Changed from components.X.details.state
        } catch (Exception e) {
            logger.error("Failed to get CB state: " + e.getMessage());
            return null;
        }
    }

    /**
     * Wait for circuit breaker to reach expected state
     *
     * @param gatewayUrl Gateway base URL
     * @param cbName Circuit breaker name
     * @param expectedState Expected state (CLOSED, OPEN, HALF_OPEN)
     * @param timeout Maximum time to wait
     */
    public static void waitForCircuitBreakerState(String gatewayUrl, String cbName,
                                                  String expectedState, Duration timeout) {
        logger.info("Waiting for " + cbName + " → " + expectedState + "...");

        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    String state = getCircuitBreakerState(gatewayUrl, cbName);
                    assertThat(state).isEqualTo(expectedState);
                });

        logger.info("✅ " + cbName + " is now " + expectedState);
    }

    /**
     * Send multiple requests through gateway
     *
     * @param gatewayUrl Gateway base URL
     * @param path Endpoint path
     * @param count Number of requests to send
     */
    public static void sendRequestsThroughGateway(String gatewayUrl, String path, int count) {
        for (int i = 0; i < count; i++) {
            given().baseUri(gatewayUrl)
                    .when().get(path)
                    .then().statusCode(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.equalTo(200),
                            org.hamcrest.Matchers.equalTo(503)
                    ));
        }
    }

    /**
     * Sleep for specified milliseconds
     *
     * @param millis Milliseconds to sleep
     */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reset all services to normal operation mode
     *
     * @param serviceUrls Array of service URLs to reset
     * @param serviceNames Array of service names (for logging)
     */
    public static void resetAllServices(String[] serviceUrls, String[] serviceNames) {
        for (int i = 0; i < serviceUrls.length; i++) {
            recoverService(serviceUrls[i], serviceNames[i]);
        }
        sleep(1000);
    }
}