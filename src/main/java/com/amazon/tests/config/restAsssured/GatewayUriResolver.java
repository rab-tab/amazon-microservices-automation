package com.amazon.tests.config.restAsssured;

import com.amazon.tests.config.TestConfig;

final class GatewayUriResolver {
    static String resolve(TestConfig config) {
        String fromSysProp = System.getProperty("gateway.base.url");
        if (fromSysProp != null && !fromSysProp.isBlank()) return fromSysProp;

        String fromEnv = System.getenv("GATEWAY_BASE_URL");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;

        String fromConfig = config.baseUrl();
        if (fromConfig == null || fromConfig.isBlank()) {
            throw new IllegalStateException("Base URI not resolved from sys prop, env var, or TestConfig");
        }
        return fromConfig;
    }
}