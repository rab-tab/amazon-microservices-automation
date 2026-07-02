package com.amazon.tests.config.auth;

import com.amazon.tests.config.auth.AuthStrategy;
import io.restassured.specification.RequestSpecification;

public class BasicAuthStrategy implements AuthStrategy {
    private final String username;
    private final String password;

    public BasicAuthStrategy(String username,
                             String password) {
        this.username = username;
        this.password = password;
    }

    @Override
    public RequestSpecification authenticate(RequestSpecification requestSpecification) {

        return requestSpecification.auth()
                .preemptive()
                .basic(username, password);
    }
}
