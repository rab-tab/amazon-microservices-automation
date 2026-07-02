package com.amazon.tests.config.auth;

import io.restassured.specification.RequestSpecification;

public interface AuthStrategy {
    RequestSpecification authenticate(RequestSpecification requestSpecification);
}
