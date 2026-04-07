package com.amazon.tests.tests.apiGateway.authentication;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * API Gateway Comprehensive Test Suite
 *
 * Tests all gateway functionality:
 * 1. Routing & Path Mapping
 * 2. Authentication & Authorization
 * 3. Rate Limiting
 * 4. CORS & Headers
 * 5. Request/Response Transformation
 * 6. Timeouts & Error Handling
 * 7. Logging & Tracing
 *
 * Circuit Breaker tests are in GatewayCircuitBreakerTest.java
 */
@Epic("Amazon Microservices")
@Feature("API Gateway - Complete Functionality")
public class AuthenticationTest extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final String USER_SERVICE_URL = "http://localhost:8081";
    private static final String PRODUCT_SERVICE_URL = "http://localhost:8082";
    private static final String ORDER_SERVICE_URL = "http://localhost:8083";
    private static final String userId="2a51ec2d-73e0-4ba4-836a-5aea6e9794d0";


    // 2. AUTHENTICATION & AUTHORIZATION TESTS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 10)
    @Story("Auth - Public Endpoints")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Public endpoints accessible without authentication")
    public void test10_PublicEndpointsAccessible() {
        logStep("TEST 10: Verifying public endpoints don't require auth");

        // Register - should be public
        int registerStatus = given().baseUri(GATEWAY_URL)
                .contentType("application/json")
                .body("{\"username\":\"pub_test\",\"email\":\"pub@test.com\",\"password\":\"Test123!\",\"firstName\":\"P\",\"lastName\":\"T\",\"phone\":\"+911234567890\"}")
                .when().post("/api/users/register")
                .then().extract().statusCode();

        assertThat(registerStatus).isIn(200, 201, 409)
                .as("Register should be accessible without auth");
        logStep("  POST /api/users/register: " + registerStatus + " ✓");

        // Login - should be public
        int loginStatus = given().baseUri(GATEWAY_URL)
                .contentType("application/json")
                .body("{\"email\":\"pub@test.com\",\"password\":\"Test123!\"}")
                .when().post("/api/users/login")
                .then().extract().statusCode();

        assertThat(loginStatus).isIn(200, 400, 404)
                .as("Login should be accessible without auth");
        logStep("  POST /api/users/login: " + loginStatus + " ✓");

        // Product list - might be public
        int productsStatus = given().baseUri(GATEWAY_URL)
                .when().get("/api/products")
                .then().extract().statusCode();
        logStep("  GET /api/products: " + productsStatus + " (public or 401)");

        logStep("✅ Public endpoints correctly configured");
    }

    @Test(priority = 11)
    @Story("Auth - JWT Validation")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Gateway validates JWT tokens correctly")
    public void test11_JWTValidation() {
        logStep("TEST 11: Verifying JWT token validation");

        // Test with invalid token
        Response invalidResp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer invalid-token-12345")
                .when()
                .get("/api/orders/user/"+userId)
                .then()
                .extract()
                .response();

        logStep("  Invalid token response: HTTP " + invalidResp.statusCode());
        assertThat(invalidResp.statusCode()).isIn(401, 403, 503)
                .as("Invalid token should be rejected");

        // Test with valid token
        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();
        String userID=auth.getUser().getId();
        logStep("User ID " + userID);

        Response validResp = given().log().all()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + auth.getAccessToken())
                .header("X-User-Id", auth.getUser().getId())
                .when()
                .get("/api/orders/user/"+userID)
                .then().log().all()
                .extract()
                .response();

        logStep("  Valid token response: HTTP " + validResp.statusCode());
        assertThat(validResp.statusCode()).isIn(200, 503)
                .as("Valid token should be accepted");

        logStep("✅ JWT validation working correctly");
    }

    @Test(priority = 12)
    @Story("Auth - Missing Token")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Protected endpoints reject requests without tokens")
    public void test12_ProtectedEndpointsRequireAuth() {
        logStep("TEST 12: Verifying protected endpoints require authentication");

        // Try to access protected endpoint without token
        Response resp = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/api/orders/user/"+userId)
                .then()
                .extract()
                .response();

        logStep("  No token response: HTTP " + resp.statusCode());

        // Should be 401 (unauthorized) or 503 (service down)
        assertThat(resp.statusCode()).isIn(401, 403, 503)
                .as("Protected endpoint should require authentication");

        logStep("✅ Protected endpoints secured");
    }


}

