package com.amazon.tests.config.auth;

import com.amazon.tests.config.auth.AuthStrategy;
import io.restassured.specification.RequestSpecification;

public class BearerAuthStrategy implements AuthStrategy {
    private final String token;

    public BearerAuthStrategy(String token) {
        this.token = token;
    }

    @Override
    public RequestSpecification authenticate(RequestSpecification requestSpecification) {
        return requestSpecification.header(
                "Authorization",
                "Bearer " + token
        );
    }
}
