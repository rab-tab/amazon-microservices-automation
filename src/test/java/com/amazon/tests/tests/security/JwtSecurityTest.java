package com.amazon.tests.tests.security;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.JwtTestUtils;
import io.qameta.allure.*;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;

@Epic("Amazon Microservices")
@Feature("JWT Security - Protected Endpoint via Gateway")
public class JwtSecurityTest extends BaseTest {

    private String validToken;


    private static final String SECURE_ENDPOINT = "/api/v1/test/secure";

    @BeforeClass
    public void setup() {
        // Create a new test user and get valid JWT
        validToken = AuthUtils.getNewUserToken();
    }

    // =========================
    // ✅ VALID TOKEN
    // =========================
    @Test
    @Story("Access with valid JWT")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify access to secured endpoint with valid JWT through API Gateway")
    public void testAccessWithValidToken() {
        given().spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get(SECURE_ENDPOINT)
                .then()
                .statusCode(200);
    }

    // =========================
    // ❌ AUTHENTICATION FAILURES
    // =========================
    @Test
    @Story("Missing Token")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify access is denied when token is missing")
    public void testAccessWithoutToken() {
        given().spec(RestAssuredConfig.getUserServiceSpec())
                .when()
                .get(SECURE_ENDPOINT)
                .then()
                .statusCode(401);
    }

    @Test
    @Story("Malformed Token")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify invalid token is rejected")
    public void testInvalidToken() {
        given().spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer invalid.jwt.token")
                .when()
                .get(SECURE_ENDPOINT)
                .then()
                .statusCode(401);
    }

    @Test
    @Story("Corrupted Token")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify corrupted token is rejected")
    public void testCorruptedToken() {
        String corrupted = JwtTestUtils.corruptToken(validToken);
        given().spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + corrupted)
                .when()
                .get(SECURE_ENDPOINT)
                .then()
                .statusCode(401);
    }

    // =========================
    // ⏳ EXPIRATION TESTS
    // =========================
    @Test
    @Story("Expired Token")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify expired token is rejected")
    public void testExpiredToken() {
        String expiredToken = JwtTestUtils.expireToken(validToken);
        given().spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + expiredToken)
                .when()
                .get(SECURE_ENDPOINT)
                .then()
                .statusCode(401);
    }

    @Test
    @Story("Extended Token")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify artificially extended token is rejected due to signature mismatch")
    public void testExtendedToken() {
        String extendedToken = JwtTestUtils.extendExpiration(validToken, 999999999L);
        given().spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + extendedToken)
                .when()
                .get(SECURE_ENDPOINT)
                .then()
                .statusCode(401);
    }

    // =========================
    // 🔐 TAMPERING TESTS
    // =========================
    @Test
    @Story("Role Escalation")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify role escalation attempt is rejected")
    public void testRoleTampering() {
        String tampered = JwtTestUtils.escalateRole(validToken, "ADMIN");
        given().spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + tampered)
                .when()
                .get(SECURE_ENDPOINT)
                .then()
                .statusCode(401);
    }

    @Test
    @Story("Email Tampering")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify email tampering is rejected")
    public void testEmailTampering() {
        String tampered = JwtTestUtils.modifyClaim(validToken, "email", "hacker@test.com");
        given().spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + tampered)
                .when()
                .get(SECURE_ENDPOINT)
                .then()
                .statusCode(401);
    }

    // =========================
    // 🔑 SIGNATURE VALIDATION
    // =========================
    @Test
    @Story("Alg None Attack")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify alg=none attack is rejected")
    public void testAlgNoneAttack() {
        String unsigned = JwtTestUtils.createUnsignedToken(validToken);
        given().spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + unsigned)
                .when()
                .get(SECURE_ENDPOINT)
                .then()
                .statusCode(401);
    }

    // =========================
    // 🧬 CLAIM VALIDATION
    // =========================
    @Test
    @Story("Missing Claims")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify token missing required claims is rejected")
    public void testMissingClaims() {
        String token = JwtTestUtils.removeClaim(validToken, "role");
        given().spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + token)
                .when()
                .get(SECURE_ENDPOINT)
                .then()
                .statusCode(401);
    }

    // =========================
    // 🔁 REPLAY TEST
    // =========================
    @Test
    @Story("Replay Token")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify same token can be reused (stateless JWT)")
    public void testReplayToken() {
        // First request
        given().spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get(SECURE_ENDPOINT)
                .then()
                .statusCode(200);

        // Replay request
        given().spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get(SECURE_ENDPOINT)
                .then()
                .statusCode(200);
    }
}