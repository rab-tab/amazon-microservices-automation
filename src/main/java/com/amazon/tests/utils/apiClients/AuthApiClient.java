package com.amazon.tests.utils.apiClients;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.*;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.testData.TestDataFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
@Slf4j
public class AuthApiClient {
    private final RequestExecutor executor;

    public AuthApiClient(RequestExecutor executor) {
        this.executor = executor;
    }
    public static String customerToken;
    public static String customerId;
    public  TestModels.RegisterRequest registerCustomer() {
        // ─── STEP 1: Register new customer ─────────────────────────────

        TestModels.RegisterRequest customerData = TestDataFactory.createRandomUser();
        TestModels.AuthResponse customerAuth = AuthUtils.registerUser(customerData);

        assertThat(customerAuth.getAccessToken()).isNotBlank();
        assertThat(customerAuth.getUser().getRole()).isEqualTo("CUSTOMER");

        customerToken = customerAuth.getAccessToken();
        customerId = customerAuth.getUser().getId();
        log.info("Customer registered: " + customerAuth.getUser().getEmail());
        return customerData;

    }

    public TestModels.AuthResponse login(String email, String password) {
        // ─── STEP 2: Login ─────────────────────────────────────────────

        TestModels.AuthResponse loginAuth = AuthUtils.login(
                email, password);

        assertThat(loginAuth.getAccessToken()).isNotBlank();
        return loginAuth;

    }

    public ServiceResponse registerRaw(TestModels.RegisterRequest payload) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.POST)
                .endpoint("/api/v1/auth/register")
                .payload(payload)
                .targetService(ServiceType.USER)
                .build();
        return executor.execute(request);
    }

    public ServiceResponse loginRaw(String email, String password) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.POST)
                .endpoint("/api/v1/auth/login")
                .payload(Map.of("email", email, "password", password))
                .targetService(ServiceType.USER)
                .build();
        return executor.execute(request);
    }

    public TestModels.AuthResponse registerSeller(){
        TestModels.AuthResponse sellerData = AuthUtils.registerAndGetAuth();
        String sellerId = sellerData.getUser().getId();
        String sellerToken = sellerData.getAccessToken();
        return sellerData;
    }
}
