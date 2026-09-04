package com.amazon.tests.utils.apiClients;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.*;
import com.amazon.tests.utils.testData.TestDataFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class AuthApiClient extends ApiClient {

    // Path differs depending on whether the request goes through the
    // gateway (which rewrites /api/users/register -> /api/v1/auth/register,
    // see user-service-register route) or directly to user-service
    // (which exposes the rewritten path natively).
    private static final String REGISTER_PATH_GATEWAY = "/api/users/register";
    private static final String REGISTER_PATH_DIRECT = "/api/v1/auth/register";
    private static final String LOGIN_PATH_GATEWAY = "/api/users/login";
    private static final String LOGIN_PATH_DIRECT = "/api/v1/auth/login";

    public AuthApiClient(RequestExecutor executor) {
        super(executor);
    }

    // ============================================================
    // Asserting convenience methods — default to GATEWAY since
    // that's how a real client of this system would interact with
    // it. Use the ServiceType overloads when a direct-to-service
    // call is specifically needed (e.g. gateway-vs-direct parity
    // checks in RoutingTest).
    // ============================================================

    public TestModels.AuthResponse registerCustomer() {
        return registerCustomer(TestDataFactory.createRandomUser(), ServiceType.GATEWAY);
    }

    public TestModels.AuthResponse registerCustomer(TestModels.RegisterRequest payload) {
        return registerCustomer(payload, ServiceType.GATEWAY);
    }

    public TestModels.AuthResponse registerCustomer(TestModels.RegisterRequest payload, ServiceType targetService) {
        ServiceResponse response = registerRaw(payload, targetService);

        // Fail loudly with the actual status/body on unexpected responses,
        // instead of silently deserializing an error body into AuthResponse
        // and only failing later on a confusing "accessToken was null"
        // assertion with no context on WHY.
        if (response.getStatusCode() != 201) {
            throw new IllegalStateException(String.format(
                    "Registration failed via %s. Expected 201 but got %d. Body: %s",
                    targetService, response.getStatusCode(), response.getBody()));
        }

        TestModels.AuthResponse customerAuth = response.as(TestModels.AuthResponse.class);
        assertThat(customerAuth.getAccessToken()).isNotBlank();
        assertThat(customerAuth.getUser().getRole()).isEqualTo("CUSTOMER");

        log.info("Customer registered via {}: {}", targetService, customerAuth.getUser().getEmail());
        return customerAuth;
    }

    public TestModels.AuthResponse registerSeller() {
        TestModels.RegisterRequest sellerData = TestDataFactory.createRandomUser();
        ServiceResponse response = registerRaw(sellerData, ServiceType.GATEWAY);

        if (response.getStatusCode() != 201) {
            throw new IllegalStateException(String.format(
                    "Seller registration failed. Expected 201 but got %d. Body: %s",
                    response.getStatusCode(), response.getBody()));
        }

        TestModels.AuthResponse sellerAuth = response.as(TestModels.AuthResponse.class);
        assertThat(sellerAuth.getAccessToken()).isNotBlank();
        log.info("Seller registered: {}", sellerAuth.getUser().getEmail());
        return sellerAuth;
    }

    public TestModels.AuthResponse login(String email, String password) {
        return login(email, password, ServiceType.GATEWAY);
    }

    public TestModels.AuthResponse login(String email, String password, ServiceType targetService) {
        ServiceResponse response = loginRaw(email, password, targetService);

        if (response.getStatusCode() != 200) {
            throw new IllegalStateException(String.format(
                    "Login failed via %s. Expected 200 but got %d. Body: %s",
                    targetService, response.getStatusCode(), response.getBody()));
        }

        TestModels.AuthResponse loginAuth = response.as(TestModels.AuthResponse.class);
        assertThat(loginAuth.getAccessToken()).isNotBlank();
        return loginAuth;
    }

    // ============================================================
    // Raw (non-asserting, non-throwing) — for rate-limit/load
    // testing, where a 429 or other non-2xx response is an
    // expected, countable outcome. These intentionally do NOT
    // check status — callers must inspect response.getStatusCode()
    // themselves.
    // ============================================================

    public ServiceResponse registerRaw(TestModels.RegisterRequest payload) {
        return registerRaw(payload, ServiceType.GATEWAY);
    }

    public ServiceResponse registerRaw(TestModels.RegisterRequest payload, ServiceType targetService) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.POST)
                .endpoint(targetService == ServiceType.GATEWAY ? REGISTER_PATH_GATEWAY : REGISTER_PATH_DIRECT)
                .payload(payload)
                .targetService(targetService)
                .build();
        return executor.execute(request);
    }

    public ServiceResponse loginRaw(String email, String password) {
        return loginRaw(email, password, ServiceType.GATEWAY);
    }

    /**
     * Login is a public, unauthenticated endpoint — you're logging in to
     * OBTAIN a token, so no token should be sent with this request. An
     * earlier version of this method accepted an extra authToken param
     * and attached it via .header("Bearer Token", authToken) — both the
     * header name and the concept were wrong (that's not a valid
     * Authorization header, and login shouldn't need one). Removed.
     */
    public ServiceResponse loginRaw(String email, String password, ServiceType targetService) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.POST)
                .endpoint(targetService == ServiceType.GATEWAY ? LOGIN_PATH_GATEWAY : LOGIN_PATH_DIRECT)
                .payload(Map.of("email", email, "password", password))
                .targetService(targetService)
                .build();
        return executor.execute(request);
    }
}