package com.amazon.tests.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

public class BasicAuthStrategy implements AuthStrategy {
    private final String username;
    private final String password;

    public BasicAuthStrategy(String username, String password) {
        this.username = username;
        this.password = password;
    }

    @Override
    public String getToken() {
        return null;   // no bearer token
    }

    @Override
    public Map<String, String> extraAuthHeaders() {
        String encoded = Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return Map.of("Authorization", "Basic " + encoded);
    }
}