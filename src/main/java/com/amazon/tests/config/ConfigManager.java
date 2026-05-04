package com.amazon.tests.config;

import org.aeonbits.owner.ConfigFactory;

public class ConfigManager {

    private static final ConfigManager instance = new ConfigManager();
    private final TestConfig config;

    private ConfigManager() {
        config = ConfigFactory.create(TestConfig.class,
                System.getProperties(),
                System.getenv());
    }

    public static ConfigManager getInstance() {
        return instance;
    }

    public TestConfig getConfig() {
        return config;
    }

    public String getBaseUrl() {
        return config.baseUrl();
    }

    public String getUserServiceUrl() {
        return config.userServiceUrl();
    }

    public String getProductServiceUrl() {
        return config.productServiceUrl();
    }

    public String getOrderServiceUrl() {
        return config.orderServiceUrl();
    }

    public String getPaymentServiceUrl() {
        return config.paymentServiceUrl();
    }
}
