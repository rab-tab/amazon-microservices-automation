package com.amazon.tests.utils.apiClients;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.*;
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

    public TestModels.AuthResponse registerCustomer() {
        TestModels.RegisterRequest customerData = TestDataFactory.createRandomUser();
        TestModels.AuthResponse customerAuth = registerRaw(customerData).as(TestModels.AuthResponse.class);

        assertThat(customerAuth.getAccessToken()).isNotBlank();
        assertThat(customerAuth.getUser().getRole()).isEqualTo("CUSTOMER");

        log.info("Customer registered: " + customerAuth.getUser().getEmail());
        return customerAuth;
    }

    public TestModels.AuthResponse registerCustomer(TestModels.RegisterRequest payload) {
        TestModels.AuthResponse customerAuth = registerRaw(payload).as(TestModels.AuthResponse.class);
        assertThat(customerAuth.getAccessToken()).isNotBlank();
        assertThat(customerAuth.getUser().getRole()).isEqualTo("CUSTOMER");
        log.info("Customer registered: " + customerAuth.getUser().getEmail());
        return customerAuth;
    }



    public TestModels.AuthResponse registerSeller() {
        TestModels.RegisterRequest sellerData = TestDataFactory.createRandomUser();
        TestModels.AuthResponse sellerAuth = registerRaw(sellerData).as(TestModels.AuthResponse.class);

        assertThat(sellerAuth.getAccessToken()).isNotBlank();
        log.info("Seller registered: " + sellerAuth.getUser().getEmail());
        return sellerAuth;
    }

    public TestModels.AuthResponse login(String email, String password) {
        TestModels.AuthResponse loginAuth = loginRaw(email, password).as(TestModels.AuthResponse.class);
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
}