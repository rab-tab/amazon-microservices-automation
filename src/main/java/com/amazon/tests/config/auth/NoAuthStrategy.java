package com.amazon.tests.config.auth;

import com.amazon.tests.config.auth.AuthStrategy;
import io.restassured.specification.RequestSpecification;

public class NoAuthStrategy implements AuthStrategy {

    @Override
    public RequestSpecification authenticate(
            RequestSpecification requestSpecification) {

        return requestSpecification;
    }
}
