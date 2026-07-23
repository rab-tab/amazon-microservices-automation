package com.amazon.tests.regression.apiGateway.authentication;


import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.apiClients.AuthApiClient;
import com.amazon.tests.utils.testData.TestDataFactory;
import io.qameta.allure.*;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Amazon Microservices")
@Feature("Authentication - Negative")
public class AuthApiNegativeTest extends BaseTest {

    private AuthApiClient authApiClient;

    @BeforeClass
    public void setup() {
        authApiClient = new AuthApiClient(context.getExecutor());
    }

    // ══════════════════════════════════════════════════════════════
    // Data-driven: field-level validation failures on registration
    // ══════════════════════════════════════════════════════════════

    @DataProvider(name = "invalidRegistrationPayloads")
    public Object[][] invalidRegistrationPayloads() {
        return new Object[][] {
                {
                        "Invalid email format",
                        (UnaryOperator<TestModels.RegisterRequest>) req -> {
                            req.setEmail("not-a-valid-email");
                            return req;
                        },
                        "email"
                },
                {
                        "Short password",
                        (UnaryOperator<TestModels.RegisterRequest>) req -> {
                            req.setPassword("123");
                            return req;
                        },
                        "password"
                }
        };
    }

    @Test(dataProvider = "invalidRegistrationPayloads")
    @Story("User Registration")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify registration fails with invalid field values")
    public void testRegistrationFieldValidation(String scenario, UnaryOperator<TestModels.RegisterRequest> mutator,
                                                String invalidField) {
        logStep("=== " + scenario + " ===");

        TestModels.RegisterRequest invalidUser = mutator.apply(TestDataFactory.createRandomUser());

        ServiceResponse response = authApiClient.registerRaw(invalidUser);

        assertThat(response.getStatusCode()).isEqualTo(400);
        Map<String, Object> body = response.as(Map.class);
        Map<?, ?> validationErrors = (Map<?, ?>) body.get("validationErrors");
        assertThat(validationErrors.get(invalidField))
                .as("Should have validation error for field: " + invalidField)
                .isNotNull();

        logStep("✅ " + scenario + " rejected correctly");
    }

    @Test
    @Story("User Registration")
    @Severity(SeverityLevel.MINOR)
    @Description("Verify registration fails when required fields are missing")
    public void testRegistrationWithMissingFields() {
        TestModels.RegisterRequest emptyRequest = TestModels.RegisterRequest.builder().build();

        ServiceResponse response = authApiClient.registerRaw(emptyRequest);

        assertThat(response.getStatusCode()).isEqualTo(400);
        Map<String, Object> body = response.as(Map.class);
        assertThat(body.get("validationErrors")).isNotNull();
    }

    // ══════════════════════════════════════════════════════════════
    // Duplicate email — needs a pre-existing user, different shape
    // ══════════════════════════════════════════════════════════════

    @Test
    @Story("User Registration")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify duplicate email registration is rejected")
    public void testDuplicateEmailRegistration() {
        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
        authApiClient.registerRaw(user); // first registration succeeds

        logStep("Attempting to register with duplicate email: " + user.getEmail());

        ServiceResponse response = authApiClient.registerRaw(user); // same user again

        assertThat(response.getStatusCode()).isEqualTo(409);
        assertThat(response.getBody()).contains("already registered");
    }

    // ══════════════════════════════════════════════════════════════
    // Login failures
    // ══════════════════════════════════════════════════════════════

    @Test
    @Story("User Login")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify login fails with wrong password")
    public void testLoginWithWrongPassword() {
        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
        authApiClient.registerRaw(user);

        ServiceResponse response = authApiClient.loginRaw(user.getEmail(), "wrongpassword");

        assertThat(response.getStatusCode()).isEqualTo(401);
        assertThat(response.getBody()).isNotBlank();
    }

    @Test
    @Story("User Login")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify login fails with non-existent email")
    public void testLoginWithNonExistentEmail() {
        ServiceResponse response = authApiClient.loginRaw("nonexistent@test.com", "somepassword");

        assertThat(response.getStatusCode()).isEqualTo(401);
    }
}