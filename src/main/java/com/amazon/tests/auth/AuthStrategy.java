package com.amazon.tests.auth;

import java.util.Map;

public interface AuthStrategy {
    String getToken();                     // for Authorization: Bearer <token>
    default Map<String, String> extraAuthHeaders() {
        return Map.of();                   // for strategies needing more than a bearer token
    }
}
