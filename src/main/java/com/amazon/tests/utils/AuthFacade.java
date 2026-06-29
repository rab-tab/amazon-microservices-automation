package com.amazon.tests.utils;

import com.amazon.tests.models.TestModels;
import lombok.extern.slf4j.Slf4j;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class AuthFacade {

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

    public void login(String email, String password) {
        // ─── STEP 2: Login ─────────────────────────────────────────────

        TestModels.AuthResponse loginAuth = AuthUtils.login(
                email, password);

        assertThat(loginAuth.getAccessToken()).isNotBlank();

    }

    public TestModels.AuthResponse registerSeller(){
        TestModels.AuthResponse sellerData = AuthUtils.registerAndGetAuth();
        String sellerId = sellerData.getUser().getId();
        String sellerToken = sellerData.getAccessToken();
        return sellerData;
    }
}
