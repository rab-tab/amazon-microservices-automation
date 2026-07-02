package com.amazon.tests.auth;

import io.restassured.specification.RequestSpecification;

public class JwtAuthStrategy implements AuthStrategy {

    private final String jwtToken;

    public JwtAuthStrategy(String jwtToken) {
        this.jwtToken = jwtToken;
    }

    @Override
    public RequestSpecification authenticate(RequestSpecification requestSpecification) {
        return requestSpecification.header(
                "Authorization",
                "Bearer " + jwtToken
        );

    }
}
