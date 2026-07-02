package com.amazon.tests.auth;

import io.restassured.specification.RequestSpecification;

public class NoAuthStrategy implements AuthStrategy {

    @Override
    public RequestSpecification authenticate(
            RequestSpecification requestSpecification) {

        return requestSpecification;
    }
}
