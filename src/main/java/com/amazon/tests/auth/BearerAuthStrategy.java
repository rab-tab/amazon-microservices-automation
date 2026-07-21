package com.amazon.tests.auth;

public class BearerAuthStrategy implements AuthStrategy {
    private final String token;

    public BearerAuthStrategy(String token) {
        this.token = token;
    }

    @Override
    public String getToken() {
        return token;
    }
}
