package com.amazon.tests.regression.apiGateway.authentication;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.AuthApiClient;
import com.amazon.tests.utils.testData.TestDataFactory;
import io.qameta.allure.*;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Amazon Microservices")
@Feature("Authentication - Positive")
public class AuthApiTest extends BaseTest {

    private AuthApiClient authApiClient;
    private TestModels.RegisterRequest testUser;
    private String registeredEmail;
    private String registeredPassword;

    @BeforeClass
    public void setup() {
        authApiClient = new AuthApiClient(context.getExecutor());
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

        TestModels.AuthResponse authResponse = authApiClient.registerRaw(testUser)
                .as(TestModels.AuthResponse.class);

        assertThat(authResponse.getAccessToken()).isNotBlank();
        assertThat(authResponse.getTokenType()).isEqualTo("Bearer");
        assertThat(authResponse.getUser().getEmail()).isEqualTo(testUser.getEmail());
        assertThat(authResponse.getUser().getUsername()).isEqualTo(testUser.getUsername());
        assertThat(authResponse.getUser().getFirstName()).isEqualTo(testUser.getFirstName());
        assertThat(authResponse.getUser().getLastName()).isEqualTo(testUser.getLastName());
        assertThat(authResponse.getUser().getRole()).isEqualTo("CUSTOMER");
        assertThat(authResponse.getUser().getStatus()).isEqualTo("ACTIVE");
        assertThat(authResponse.getUser().getId()).isNotBlank();
    }

    @Test(priority = 2)
    @Story("User Login")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify a registered user can successfully login")
    public void testSuccessfulLogin() {
        // Independent setup — no cross-test dependency
        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
        authApiClient.registerRaw(user);

        logStep("Logging in with email: " + user.getEmail());

        TestModels.AuthResponse auth = authApiClient.loginRaw(user.getEmail(), user.getPassword())
                .as(TestModels.AuthResponse.class);

        assertThat(auth.getAccessToken()).isNotBlank();
        assertThat(auth.getTokenType()).isEqualTo("Bearer");
        assertThat(auth.getUser().getEmail()).isEqualTo(user.getEmail());
        assertThat(auth.getExpiresIn()).isGreaterThan(0);
    }
}