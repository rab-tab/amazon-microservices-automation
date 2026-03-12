package com.amazon.tests.config;

import org.aeonbits.owner.Config;
import org.aeonbits.owner.Config.Sources;

@Sources({
    "classpath:${env}.properties",
    "classpath:local.properties"
})
public interface TestConfig extends Config {

    @Key("base.url")
    @DefaultValue("http://localhost:8080")
    String baseUrl();

    @Key("user.service.url")
    @DefaultValue("http://localhost:8081")
    String userServiceUrl();

    @Key("product.service.url")
    @DefaultValue("http://localhost:8082")
    String productServiceUrl();

    @Key("order.service.url")
    @DefaultValue("http://localhost:8083")
    String orderServiceUrl();

    @Key("payment.service.url")
    @DefaultValue("http://localhost:8084")
    String paymentServiceUrl();

    @Key("request.timeout")
    @DefaultValue("30000")
    int requestTimeout();

    @Key("test.admin.email")
    @DefaultValue("admin@amazon-test.com")
    String adminEmail();

    @Key("test.admin.password")
    @DefaultValue("Admin@12345")
    String adminPassword();
}
