package com.amazon.tests.regression.apiGateway.authentication;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.JwtTestUtils;
import com.amazon.tests.utils.apiClients.GatewayApiClient;
import io.qameta.allure.*;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT Security Test Suite — API Gateway level.
 * Covers token tampering, signature validation, expiration, algorithm
 * attacks, role escalation, claim manipulation, and cross-user access.
 */
@Epic("Amazon Microservices")
@Feature("Security - JWT Validation")
public class JwtSecurityTest extends BaseTest {

    private static final String PROTECTED_ENDPOINT = "/api/orders/user/";
    private static final String USER_ENDPOINT = "/api/users/";

    private GatewayApiClient gatewayApiClient;
    private String validToken;
    private String userId;
    private TestModels.AuthResponse authResponse;

    @BeforeClass
    public void setup() {
        logStep("Setting up JWT security tests");

        gatewayApiClient = new GatewayApiClient(context.getExecutor());

        authResponse = AuthUtils.registerAndGetAuth();
        validToken = authResponse.getAccessToken();
        userId = authResponse.getUser().getId();

        logStep("Valid token obtained for user: " + userId);

        ServiceResponse response = gatewayApiClient.get(USER_ENDPOINT + userId, validToken);
        assertThat(response.getStatusCode()).isIn(200, 503)
                .as("Valid token should work (200 or 503 if service down)");

        logStep("✅ Setup complete - valid token verified");
    }

    // ══════════════════════════════════════════════════════════════
    // 1. BASIC TOKEN VALIDATION
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("JWT - Valid Token")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify valid JWT token is accepted by gateway")
    public void test01_ValidTokenIsAccepted() {
        ServiceResponse response = gatewayApiClient.get(PROTECTED_ENDPOINT + userId, validToken);
        assertThat(response.getStatusCode()).isIn(200, 503);
        logStep("✅ Valid token accepted - Status: " + response.getStatusCode());
    }

    @Test(priority = 2)
    @Story("JWT - Missing Token")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify request without token is rejected")
    public void test02_MissingTokenIsRejected() {
        ServiceResponse response = gatewayApiClient.get(PROTECTED_ENDPOINT + userId, null);

        assertThat(response.getStatusCode()).isEqualTo(401);
        assertThat(response.getBody()).contains("Unauthorized");
        assertThat(response.getBody()).containsAnyOf("Missing", "Authorization", "header");

        logStep("✅ Missing token rejected with 401");
    }

    @DataProvider(name = "malformedTokens")
    public Object[][] malformedTokens() {
        return new Object[][] {
                { "not-a-jwt-token" },
                { "Bearer" },
                { "InvalidToken123" },
                { "eyJhbGc.invalid.token" }
        };
    }

    @Test(priority = 3, dataProvider = "malformedTokens")
    @Story("JWT - Invalid Format")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify malformed token is rejected")
    public void test03_MalformedTokenIsRejected(String malformed) {
        ServiceResponse response = gatewayApiClient.get(PROTECTED_ENDPOINT + userId, malformed);
        assertThat(response.getStatusCode()).isEqualTo(401);
        logStep("  ✓ Rejected: " + malformed.substring(0, Math.min(20, malformed.length())));
    }

    // ══════════════════════════════════════════════════════════════
    // 2. SIGNATURE VALIDATION
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 10)
    @Story("JWT - Invalid Signature")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify token with invalid signature is rejected")
    public void test10_InvalidSignatureIsRejected() {
        String tamperedToken = JwtTestUtils.tamperSignature(validToken);
        ServiceResponse response = gatewayApiClient.get(PROTECTED_ENDPOINT + userId, tamperedToken);

        assertThat(response.getStatusCode()).isEqualTo(401);
        assertThat(response.getBody()).containsAnyOf("Invalid", "signature", "token");

        logStep("✅ Tampered signature rejected");
    }

    @Test(priority = 11)
    @Story("JWT - Algorithm None Attack")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify alg=none attack is rejected")
    public void test11_AlgorithmNoneAttackIsRejected() {
        String unsignedToken = JwtTestUtils.createUnsignedToken(userId, authResponse.getUser().getEmail());
        ServiceResponse response = gatewayApiClient.get(PROTECTED_ENDPOINT + userId, unsignedToken);

        assertThat(response.getStatusCode()).isEqualTo(401);
        logStep("✅ Alg=none attack rejected");
    }

    // ══════════════════════════════════════════════════════════════
    // 3. EXPIRATION TESTS
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 20)
    @Story("JWT - Expired Token")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify expired token is rejected")
    public void test20_ExpiredTokenIsRejected() {
        String expiredToken = JwtTestUtils.createExpiredToken(userId, authResponse.getUser().getEmail());
        ServiceResponse response = gatewayApiClient.get(PROTECTED_ENDPOINT + userId, expiredToken);

        assertThat(response.getStatusCode()).isEqualTo(401);
        assertThat(response.getBody()).containsAnyOf("expired", "Expired", "Token expired", "Invalid token");

        logStep("✅ Expired token rejected");
    }

    @Test(priority = 21)
    @Story("JWT - Extended Expiration")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify token with artificially extended expiration is rejected (signature mismatch)")
    public void test21_ExtendedExpirationIsRejected() {
        String extendedToken = JwtTestUtils.extendExpiration(validToken, 9999999999L);
        ServiceResponse response = gatewayApiClient.get(PROTECTED_ENDPOINT + userId, extendedToken);

        assertThat(response.getStatusCode()).isEqualTo(401);
        logStep("✅ Extended expiration rejected (signature invalid)");
    }

    // ══════════════════════════════════════════════════════════════
    // 4. CLAIM TAMPERING
    // ══════════════════════════════════════════════════════════════

    @DataProvider(name = "claimTamperingScenarios")
    public Object[][] claimTamperingScenarios() {
        return new Object[][] {
                { "User ID tampering", "modify", "sub", "different-user-id" },
                { "Email tampering", "modify", "email", "hacker@malicious.com" },
                { "Role escalation", "add", "role", "ADMIN" }
        };
    }

    @Test(priority = 30, dataProvider = "claimTamperingScenarios")
    @Story("JWT - Claim Tampering")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify claim tampering attempts are rejected")
    public void test30_ClaimTamperingIsRejected(String scenario, String action, String claim, String value) {
        String tamperedToken = "modify".equals(action)
                ? JwtTestUtils.modifyClaim(validToken, claim, value)
                : JwtTestUtils.addClaim(validToken, claim, value);

        ServiceResponse response = gatewayApiClient.get(PROTECTED_ENDPOINT + userId, tamperedToken);

        assertThat(response.getStatusCode()).isEqualTo(401);
        logStep("✅ " + scenario + " rejected");
    }

    // ══════════════════════════════════════════════════════════════
    // 5. TOKEN REUSE & REPLAY
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 40)
    @Story("JWT - Token Reuse")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify same token can be reused (stateless JWT - this is expected)")
    public void test40_TokenReuseIsAllowed() {
        ServiceResponse first = gatewayApiClient.get(PROTECTED_ENDPOINT + userId, validToken);
        assertThat(first.getStatusCode()).isIn(200, 503);

        ServiceResponse second = gatewayApiClient.get(PROTECTED_ENDPOINT + userId, validToken);
        assertThat(second.getStatusCode()).isIn(200, 503);

        logStep("✅ Token reuse allowed (expected for stateless JWT)");
    }

    // ══════════════════════════════════════════════════════════════
    // 6. CROSS-USER ACCESS PREVENTION
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 50)
    @Story("JWT - Cross-User Access")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify user cannot access another user's resources using their own token")
    public void test50_CannotAccessOtherUsersResources() {
        TestModels.AuthResponse otherUser = AuthUtils.registerAndGetAuth();

        ServiceResponse response = gatewayApiClient.get(USER_ENDPOINT + otherUser.getUser().getId(), validToken);

        assertThat(response.getStatusCode()).isIn(401, 403)
                .as("Should not be able to access other user's data");

        logStep("✅ Cross-user access prevented - Status: " + response.getStatusCode());
    }

    @Test(priority = 51)
    @Story("JWT - Own Resource Access")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify user can access their own resources")
    public void test51_CanAccessOwnResources() {
        ServiceResponse response = gatewayApiClient.get(USER_ENDPOINT + userId, validToken);

        assertThat(response.getStatusCode()).isIn(200, 503)
                .as("Should be able to access own data");

        if (response.getStatusCode() == 200) {
            TestModels.UserResponse user = response.as(TestModels.UserResponse.class);
            assertThat(user.getId()).isEqualTo(userId);
        }

        logStep("✅ Own resource access allowed - Status: " + response.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════
    // 7. PUBLIC ENDPOINTS (Baseline)
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 60)
    @Story("JWT - Public Endpoints")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify public endpoints don't require JWT")
    public void test60_PublicEndpointsDoNotRequireJWT() {
        ServiceResponse healthResponse = gatewayApiClient.get("/api/users/health", null);
        assertThat(healthResponse.getStatusCode()).isIn(200, 503);

        ServiceResponse productsResponse = gatewayApiClient.get("/api/products", null);
        assertThat(productsResponse.getStatusCode()).isIn(200, 503);

        logStep("✅ Public endpoints accessible without JWT");
    }
}