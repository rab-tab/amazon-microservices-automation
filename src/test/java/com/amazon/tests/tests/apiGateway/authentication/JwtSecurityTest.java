package com.amazon.tests.tests.apiGateway.authentication;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.JwtTestUtils;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

/**
 * JWT Security Test Suite
 *
 * Tests JWT security at the API Gateway level.
 * Covers all common JWT attack vectors:
 *
 * 1. Token Tampering
 * 2. Signature Validation
 * 3. Expiration Checks
 * 4. Algorithm Attacks (alg=none)
 * 5. Role Escalation
 * 6. Claim Manipulation
 *
 * All tests target the gateway (port 8080) since that's where JWT validation happens.
 */
@Epic("Amazon Microservices")
@Feature("Security - JWT Validation")
public class JwtSecurityTest extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";

    // Protected endpoint that requires authentication
    private static final String PROTECTED_ENDPOINT = "/api/orders/user/";
    private static final String USER_ENDPOINT = "/api/users/";

    private String validToken;
    private String userId;
    private TestModels.AuthResponse authResponse;

    // ══════════════════════════════════════════════════════════════════════════
    // SETUP
    // ══════════════════════════════════════════════════════════════════════════

    @BeforeClass
    public void setup() {
        logStep("Setting up JWT security tests");

        // Register and get a valid token
        authResponse = AuthUtils.registerAndGetAuth();
        validToken = authResponse.getAccessToken();
        userId = authResponse.getUser().getId();

        logStep("Valid token obtained for user: " + userId);

        // Verify token works
        int status = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get(USER_ENDPOINT+userId)
                .then()
                .extract()
                .statusCode();

        assertThat(status).isIn(200, 503)
                .as("Valid token should work (200 or 503 if service down)");

        logStep("✅ Setup complete - valid token verified");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. BASIC TOKEN VALIDATION
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("JWT - Valid Token")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify valid JWT token is accepted by gateway")
    public void test01_ValidTokenIsAccepted() {
        logStep("TEST 1: Verifying valid token works");

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get(PROTECTED_ENDPOINT+userId)
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(503)))
                .extract()
                .response();

        logStep("✅ Valid token accepted - Status: " + resp.statusCode());
    }

    @Test(priority = 2)
    @Story("JWT - Missing Token")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify request without token is rejected")
    public void test02_MissingTokenIsRejected() {
        logStep("TEST 2: Verifying missing token is rejected");

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .when()
                .get(PROTECTED_ENDPOINT+userId)
                .then()
                .statusCode(401)
                .body("status", equalTo(401))
                .body("error", equalTo("Unauthorized"))
                .extract()
                .response();

        assertThat(resp.jsonPath().getString("message"))
                .containsAnyOf("Missing", "Authorization", "header");

        logStep("✅ Missing token rejected with 401");
    }

    @Test(priority = 3)
    @Story("JWT - Invalid Format")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify malformed token is rejected")
    public void test03_MalformedTokenIsRejected() {
        logStep("TEST 3: Verifying malformed token is rejected");

        // Test various malformed tokens
        String[] malformedTokens = {
                "not-a-jwt-token",
                "Bearer",
                "InvalidToken123",
                "eyJhbGc.invalid.token"
        };

        for (String malformed : malformedTokens) {
            given()
                    .baseUri(GATEWAY_URL)
                    .header("Authorization", "Bearer " + malformed)
                    .when()
                    .get(PROTECTED_ENDPOINT+userId)
                    .then()
                    .statusCode(401);

            logStep("  ✓ Rejected: " + malformed.substring(0, Math.min(20, malformed.length())));
        }

        logStep("✅ All malformed tokens rejected");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. SIGNATURE VALIDATION
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 10)
    @Story("JWT - Invalid Signature")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify token with invalid signature is rejected")
    public void test10_InvalidSignatureIsRejected() {
        logStep("TEST 10: Verifying tampered signature is rejected");

        // Tamper with the signature (last part of JWT)
        String tamperedToken = JwtTestUtils.tamperSignature(validToken);

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + tamperedToken)
                .when()
                .get(PROTECTED_ENDPOINT+userId)
                .then()
                .statusCode(401)
                .extract()
                .response();

        assertThat(resp.jsonPath().getString("message"))
                .containsAnyOf("Invalid", "signature", "token");

        logStep("✅ Tampered signature rejected");
    }

    @Test(priority = 11)
    @Story("JWT - Algorithm None Attack")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify alg=none attack is rejected")
    public void test11_AlgorithmNoneAttackIsRejected() {
        logStep("TEST 11: Verifying alg=none attack is rejected");

        // Create unsigned token with alg=none
        String unsignedToken = JwtTestUtils.createUnsignedToken(userId, authResponse.getUser().getEmail());

        given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + unsignedToken)
                .when()
                .get(PROTECTED_ENDPOINT+userId)
                .then()
                .statusCode(401);

        logStep("✅ Alg=none attack rejected");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. EXPIRATION TESTS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 20)
    @Story("JWT - Expired Token")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify expired token is rejected")
    public void test20_ExpiredTokenIsRejected() {
        logStep("TEST 20: Verifying expired token is rejected");

        // Create an already-expired token
        String expiredToken = JwtTestUtils.createExpiredToken(userId, authResponse.getUser().getEmail());

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + expiredToken)
                .when()
                .get(PROTECTED_ENDPOINT+userId)
                .then()
                .statusCode(401)
                .extract()
                .response();

        assertThat(resp.jsonPath().getString("message"))
                .containsAnyOf("expired", "Expired", "Token expired","Invalid token");

        logStep("✅ Expired token rejected");
    }

    @Test(priority = 21)
    @Story("JWT - Extended Expiration")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify token with artificially extended expiration is rejected (signature mismatch)")
    public void test21_ExtendedExpirationIsRejected() {
        logStep("TEST 21: Verifying extended expiration tampering is rejected");

        // Try to extend token expiration (will break signature)
        String extendedToken = JwtTestUtils.extendExpiration(validToken, 9999999999L);

        given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + extendedToken)
                .when()
                .get(PROTECTED_ENDPOINT+userId)
                .then()
                .statusCode(401);

        logStep("✅ Extended expiration rejected (signature invalid)");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. CLAIM TAMPERING
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 30)
    @Story("JWT - User ID Tampering")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify user ID tampering is rejected")
    public void test30_UserIdTamperingIsRejected() {
        logStep("TEST 30: Verifying user ID tampering is rejected");

        // Try to change user ID (will break signature)
        String tamperedToken = JwtTestUtils.modifyClaim(validToken, "sub", "different-user-id");

        given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + tamperedToken)
                .when()
                .get(PROTECTED_ENDPOINT+userId)
                .then()
                .statusCode(401);

        logStep("✅ User ID tampering rejected");
    }

    @Test(priority = 31)
    @Story("JWT - Email Tampering")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify email tampering is rejected")
    public void test31_EmailTamperingIsRejected() {
        logStep("TEST 31: Verifying email tampering is rejected");

        String tamperedToken = JwtTestUtils.modifyClaim(validToken, "email", "hacker@malicious.com");

        given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + tamperedToken)
                .when()
                .get(PROTECTED_ENDPOINT+userId)
                .then()
                .statusCode(401);

        logStep("✅ Email tampering rejected");
    }

    @Test(priority = 32)
    @Story("JWT - Role Escalation")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify role escalation attempt is rejected")
    public void test32_RoleEscalationIsRejected() {
        logStep("TEST 32: Verifying role escalation is rejected");

        // Try to escalate role to ADMIN
        String tamperedToken = JwtTestUtils.addClaim(validToken, "role", "ADMIN");

        given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + tamperedToken)
                .when()
                .get(PROTECTED_ENDPOINT+userId)
                .then()
                .statusCode(401);

        logStep("✅ Role escalation attempt rejected");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. TOKEN REUSE & REPLAY
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 40)
    @Story("JWT - Token Reuse")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify same token can be reused (stateless JWT - this is expected)")
    public void test40_TokenReuseIsAllowed() {
        logStep("TEST 40: Verifying token reuse works (stateless design)");

        // First use
        given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get(PROTECTED_ENDPOINT+userId)
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(503)));

        // Second use (replay)
        given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get(PROTECTED_ENDPOINT+userId)
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(503)));

        logStep("✅ Token reuse allowed (expected for stateless JWT)");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. CROSS-USER ACCESS PREVENTION
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 50)
    @Story("JWT - Cross-User Access")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify user cannot access another user's resources using their own token")
    public void test50_CannotAccessOtherUsersResources() {
        logStep("TEST 50: Verifying cross-user access is prevented");

        // Register another user
        TestModels.AuthResponse otherUser = AuthUtils.registerAndGetAuth();

        // Try to access other user's data with your token
        Response resp = given().log().all()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get(USER_ENDPOINT  + otherUser.getUser().getId())
                .then().log().all()
                .extract()
                .response();

        // Should be either 403 (forbidden) or 401 (unauthorized)
        // depending on whether service-level authorization is implemented
        assertThat(resp.statusCode()).isIn(401, 403)
                .as("Should not be able to access other user's data");

        logStep("✅ Cross-user access prevented - Status: " + resp.statusCode());
    }

    @Test(priority = 51)
    @Story("JWT - Own Resource Access")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify user can access their own resources")
    public void test51_CanAccessOwnResources() {
        logStep("TEST 51: Verifying user can access their own resources");

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get(USER_ENDPOINT  + userId)
                .then()
                .extract()
                .response();

        // Should be 200 or 503 (if service down)
        assertThat(resp.statusCode()).isIn(200, 503)
                .as("Should be able to access own data");

        if (resp.statusCode() == 200) {
            assertThat(resp.jsonPath().getString("id"))
                    .isEqualTo(userId)
                    .as("Response should contain own user data");
        }

        logStep("✅ Own resource access allowed - Status: " + resp.statusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. PUBLIC ENDPOINTS (Baseline)
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 60)
    @Story("JWT - Public Endpoints")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify public endpoints don't require JWT")
    public void test60_PublicEndpointsDoNotRequireJWT() {
        logStep("TEST 60: Verifying public endpoints work without JWT");

        // Health endpoint
        given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/api/users/health")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(503)));

        // Product list
        given()
                .baseUri(GATEWAY_URL)
                .when()
                .get("/api/products")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(503)));

        logStep("✅ Public endpoints accessible without JWT");
    }
}