package com.amazon.tests.auth;

import io.restassured.specification.RequestSpecification;

public interface AuthStrategy {
    RequestSpecification authenticate(RequestSpecification requestSpecification);
}
