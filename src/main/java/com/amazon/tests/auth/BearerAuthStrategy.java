package com.amazon.tests.auth;

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
