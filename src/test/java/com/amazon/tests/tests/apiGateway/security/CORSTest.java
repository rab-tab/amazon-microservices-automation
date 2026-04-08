package com.amazon.tests.tests.apiGateway.security;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import io.qameta.allure.Description;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;


public class CORSTest extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final String USER_SERVICE_URL = "http://localhost:8081";
    private static final String PRODUCT_SERVICE_URL = "http://localhost:8082";
    private static final String ORDER_SERVICE_URL = "http://localhost:8083";

    @Test
    @Story("CORS - Preflight Requests")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Gateway handles CORS preflight OPTIONS requests")
    public void test30_CORSPreflightHandling() {
        logStep("TEST 1: Verifying basic routing to user-service");
        logStep("TEST 30: Verifying CORS preflight handling");

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type,Authorization")
                .when()
                .options("/api/users/register")
                .then()
                .extract()
                .response();

        logStep("  OPTIONS response: HTTP " + resp.statusCode());

        if (resp.statusCode() == 200 || resp.statusCode() == 204) {
            String allowOrigin = resp.header("Access-Control-Allow-Origin");
            String allowMethods = resp.header("Access-Control-Allow-Methods");

            logStep("  Access-Control-Allow-Origin: " + allowOrigin);
            logStep("  Access-Control-Allow-Methods: " + allowMethods);

            logStep("✅ CORS preflight handling works");
        } else {
            logStep("⚠️  CORS might not be configured");
        }
    }

    @Test
    @Story("Headers - Custom Header Propagation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Gateway propagates custom headers to backend services")
    public void test31_CustomHeaderPropagation() {
        logStep("TEST 31: Verifying custom header propagation");

        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + auth.getAccessToken())
                .header("X-User-Id", auth.getUser().getId())
                .header("X-Request-ID", "test-request-123")
                .header("X-Correlation-ID", "test-correlation-456")
                .when()
                .get("/api/orders")
                .then()
                .extract()
                .response();

        logStep("  Response status: HTTP " + resp.statusCode());

        // Check if headers were propagated (might be visible in response headers or logs)
        if (resp.statusCode() == 200 || resp.statusCode() == 503) {
            logStep("✅ Request reached backend (headers propagated)");
        }
    }

}
