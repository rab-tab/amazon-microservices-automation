package com.amazon.tests.tests;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.TestDataFactory;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

@Epic("Amazon Microservices")
@Feature("Authentication")
public class AuthApiTest extends BaseTest {

    private TestModels.RegisterRequest testUser;
    private String registeredEmail;
    private String registeredPassword;
    private String authToken;

    @BeforeClass
    public void setup() {
        testUser = TestDataFactory.createRandomUser();
        registeredEmail = testUser.getEmail();
        registeredPassword = testUser.getPassword();
    }

    @Test(priority = 1)
    @Story("User Registration")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify a new user can successfully register")
    public void testSuccessfulRegistration() {
        logStep("Registering new user with email: " + testUser.getEmail());

        Response response = given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .body(testUser)
                .when()
                .post("/api/v1/auth/register")
                .then()
                .statusCode(201)
                .body("accessToken", notNullValue())
                .body("tokenType", equalTo("Bearer"))
                .body("user.email", equalTo(testUser.getEmail()))
                .body("user.username", equalTo(testUser.getUsername()))
                .body("user.firstName", equalTo(testUser.getFirstName()))
                .body("user.lastName", equalTo(testUser.getLastName()))
                .body("user.role", equalTo("CUSTOMER"))
                .body("user.status", equalTo("ACTIVE"))
                .body("user.id", notNullValue())
                .extract().response();

        TestModels.AuthResponse authResponse = response.as(TestModels.AuthResponse.class);
        assertThat(authResponse.getAccessToken()).isNotBlank();
        assertThat(authResponse.getUser().getId()).isNotBlank();

        authToken = authResponse.getAccessToken();
    }

    @Test(priority = 2, dependsOnMethods = "testSuccessfulRegistration")
    @Story("User Registration")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify duplicate email registration is rejected")
    public void testDuplicateEmailRegistration() {
        logStep("Attempting to register with duplicate email: " + registeredEmail);

        given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .body(testUser) // Same user
                .when()
                .post("/api/v1/auth/register")
                .then()
                .statusCode(409)
                .body("message", containsString("already registered"));
    }

    @Test(priority = 3)
    @Story("User Registration")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify registration fails with invalid email format")
    public void testRegistrationWithInvalidEmail() {
        TestModels.RegisterRequest invalidUser = TestDataFactory.createRandomUser();
        invalidUser.setEmail("not-a-valid-email");

        given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .body(invalidUser)
                .when()
                .post("/api/v1/auth/register")
                .then()
                .statusCode(400)
                .body("validationErrors.email", notNullValue());
    }

    @Test(priority = 4)
    @Story("User Registration")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify registration fails with short password")
    public void testRegistrationWithShortPassword() {
        TestModels.RegisterRequest weakPassword = TestDataFactory.createRandomUser();
        weakPassword.setPassword("123");

        given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .body(weakPassword)
                .when()
                .post("/api/v1/auth/register")
                .then()
                .statusCode(400)
                .body("validationErrors.password", notNullValue());
    }

    @Test(priority = 5)
    @Story("User Registration")
    @Severity(SeverityLevel.MINOR)
    @Description("Verify registration fails when required fields are missing")
    public void testRegistrationWithMissingFields() {
        TestModels.RegisterRequest emptyRequest = TestModels.RegisterRequest.builder().build();

        given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .body(emptyRequest)
                .when()
                .post("/api/v1/auth/register")
                .then()
                .statusCode(400)
                .body("validationErrors", notNullValue());
    }

    @Test(priority = 6, dependsOnMethods = "testSuccessfulRegistration")
    @Story("User Login")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify a registered user can successfully login")
    public void testSuccessfulLogin() {
        logStep("Logging in with email: " + registeredEmail);

        Response response = given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .body(TestDataFactory.createLoginRequest(registeredEmail, registeredPassword))
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(200)
                .body("accessToken", notNullValue())
                .body("tokenType", equalTo("Bearer"))
                .body("expiresIn", greaterThan(0L))
                .body("user.email", equalTo(registeredEmail))
                .extract().response();

        TestModels.AuthResponse auth = response.as(TestModels.AuthResponse.class);
        assertThat(auth.getAccessToken()).isNotBlank();
        authToken = auth.getAccessToken();
    }

    @Test(priority = 7)
    @Story("User Login")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify login fails with wrong password")
    public void testLoginWithWrongPassword() {
        given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .body(TestDataFactory.createLoginRequest(registeredEmail, "wrongpassword"))
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(401)
                .body("message", notNullValue());
    }

    @Test(priority = 8)
    @Story("User Login")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify login fails with non-existent email")
    public void testLoginWithNonExistentEmail() {
        given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .body(TestDataFactory.createLoginRequest("nonexistent@test.com", "somepassword"))
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(401);
    }
}
