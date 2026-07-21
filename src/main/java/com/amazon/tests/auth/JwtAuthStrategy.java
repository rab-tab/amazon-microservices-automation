package com.amazon.tests.auth;

public class JwtAuthStrategy implements AuthStrategy {

    private final String jwtToken;

    public JwtAuthStrategy(String jwtToken) {
        this.jwtToken = jwtToken;
    }


    @Override
    public String getToken() {
        return jwtToken;
    }
}
