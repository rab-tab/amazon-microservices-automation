package com.amazon.tests.tests.apiGateway.authentication;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import io.qameta.allure.Description;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;


public class CORSTest extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final String USER_SERVICE_URL = "http://localhost:8081";
    private static final String PRODUCT_SERVICE_URL = "http://localhost:8082";
    private static final String ORDER_SERVICE_URL = "http://localhost:8083";

    @Test(priority = 30)
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify API Gateway handles CORS preflight requests correctly")
    @Story("Security - CORS Configuration")
    public void test30_CORSPreflightHandling() {
        logStep("Testing CORS preflight handling");

        // ═══════════════════════════════════════════════════════════════════
        // STEP 1: Send OPTIONS Preflight Request
        // ═══════════════════════════════════════════════════════════════════
        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Origin", "http://localhost:3000")  // Frontend origin
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type,Authorization")
                .when()
                .options("/api/users/register")
                .then()
                .extract()
                .response();

        logStep("OPTIONS response: HTTP " + resp.statusCode());

        // ═══════════════════════════════════════════════════════════════════
        // STEP 2: Verify Status Code
        // ═══════════════════════════════════════════════════════════════════
        assertThat(resp.statusCode()).isIn(200, 204)
                .describedAs("CORS preflight should return 200 OK or 204 No Content");

        // ═══════════════════════════════════════════════════════════════════
        // STEP 3: Verify CORS Headers
        // ═══════════════════════════════════════════════════════════════════

        // 3.1: Access-Control-Allow-Origin
        String allowOrigin = resp.header("Access-Control-Allow-Origin");
        assertThat(allowOrigin).isNotNull()
                .describedAs("Access-Control-Allow-Origin header must be present");

        assertThat(allowOrigin).isIn("*", "http://localhost:3000")
                .describedAs("Should allow localhost:3000 or all origins");

        logStep("✓ Access-Control-Allow-Origin: " + allowOrigin);

        // 3.2: Access-Control-Allow-Methods
        String allowMethods = resp.header("Access-Control-Allow-Methods");
        assertThat(allowMethods).isNotNull()
                .describedAs("Access-Control-Allow-Methods header must be present");

        assertThat(allowMethods.toUpperCase())
                .contains("POST")
                .describedAs("Must allow POST method");

        logStep("✓ Access-Control-Allow-Methods: " + allowMethods);

        // 3.3: Access-Control-Allow-Headers
        String allowHeaders = resp.header("Access-Control-Allow-Headers");
        assertThat(allowHeaders).isNotNull()
                .describedAs("Access-Control-Allow-Headers must be present");

        assertThat(allowHeaders.toLowerCase())
                .contains("content-type")
                .contains("authorization")
                .describedAs("Must allow Content-Type and Authorization headers");

        logStep("✓ Access-Control-Allow-Headers: " + allowHeaders);

        // 3.4: Access-Control-Max-Age (optional)
        String maxAge = resp.header("Access-Control-Max-Age");
        if (maxAge != null) {
            assertThat(Integer.parseInt(maxAge)).isGreaterThan(0)
                    .describedAs("Max-Age should be positive");
            logStep("✓ Access-Control-Max-Age: " + maxAge + " seconds");
        }

        logStep("✅ CORS preflight handling works correctly");
    }

    @Test
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify custom headers are propagated through API Gateway to backend service")
    @Story("Gateway - Header Propagation")
    public void test31_CustomHeaderPropagation() {
        logStep("TEST 31: Verifying custom header propagation");

        // ═══════════════════════════════════════════════════════════════════
        // SETUP: Register user and get auth token
        // ═══════════════════════════════════════════════════════════════════
        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();

        String requestId = "test-request-" + UUID.randomUUID().toString().substring(0, 8);
        String correlationId = "test-correlation-" + UUID.randomUUID().toString().substring(0, 8);
        String userId = auth.getUser().getId();

        logStep("Test headers:");
        logStep("  X-Request-ID: " + requestId);
        logStep("  X-Correlation-ID: " + correlationId);
        logStep("  X-User-Id: " + userId);

        // ═══════════════════════════════════════════════════════════════════
        // STEP 1: Send request with custom headers through gateway
        // ═══════════════════════════════════════════════════════════════════
        Response resp = given()
                .log().headers()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + auth.getAccessToken())
                .header("X-User-Id", userId)
                .header("X-Request-ID", requestId)
                .header("X-Correlation-ID", correlationId)
                .when()
                .get("/api/orders/test/echo-headers")  // Test endpoint
                .then()
                .log().all()
                .extract()
                .response();

        logStep("Response status: HTTP " + resp.statusCode());

        // ═══════════════════════════════════════════════════════════════════
        // STEP 2: Verify response status
        // ═══════════════════════════════════════════════════════════════════
        assertThat(resp.statusCode()).isEqualTo(200)
                .describedAs("Echo headers endpoint should return 200 OK");

        // ═══════════════════════════════════════════════════════════════════
        // STEP 3: Verify headers were propagated to backend
        // ═══════════════════════════════════════════════════════════════════
        Map<String, String> receivedHeaders = resp.jsonPath().getMap("$");

        logStep("Headers received by backend:");
        receivedHeaders.forEach((key, value) ->
                logStep("  " + key + ": " + value)
        );

        // 3.1: Verify X-Request-ID
        assertThat(receivedHeaders.get("x-request-id"))
                .as("X-Request-ID should be propagated")
                .isNotNull()
                .isEqualTo(requestId);

        logStep("✓ X-Request-ID propagated correctly");

        // 3.2: Verify X-Correlation-ID
        assertThat(receivedHeaders.get("x-correlation-id"))
                .as("X-Correlation-ID should be propagated")
                .isNotNull()
                .isEqualTo(correlationId);

        logStep("✓ X-Correlation-ID propagated correctly");

        // 3.3: Verify X-User-Id
        assertThat(receivedHeaders.get("x-user-id"))
                .as("X-User-Id should be propagated")
                .isNotNull()
                .isEqualTo(userId);

        logStep("✓ X-User-Id propagated correctly");

        // ═══════════════════════════════════════════════════════════════════
        // SUCCESS
        // ═══════════════════════════════════════════════════════════════════
        logStep("✅ All custom headers propagated correctly through gateway");
    }

}
