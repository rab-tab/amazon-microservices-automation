package com.amazon.tests.config.restAsssured;

import com.amazon.tests.config.TestConfig;
import com.amazon.tests.transport.ServiceType;
import io.qameta.allure.restassured.AllureRestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

public class RestAssuredConfig {

    private final TestConfig config;


    public RestAssuredConfig(TestConfig config) {
        this.config = config;

    }

    // ---- Named services (most common case) ----
    public RequestSpecification getOrderServiceSpec(String token) {
        return buildSpec(config.orderServiceUrl(), token);
    }
    public RequestSpecification getPaymentServiceSpec(String token) {
        return buildSpec(config.paymentServiceUrl(), token);
    }
    public RequestSpecification getProductServiceSpec(String token) {
        return buildSpec(config.productServiceUrl(), token);
    }
    public RequestSpecification getUserServiceSpec(String token) {
        return buildSpec(config.userServiceUrl(), token);
    }

    // ---- Generic / gateway fallback (for anything without a named service) ----
    public RequestSpecification getGatewaySpec(String token) {
        return buildSpec(GatewayUriResolver.resolve(config), token);
    }

    // ---- Escape hatch for full flexibility (replaces customSpec) ----
    public RequestSpecification build(String baseUri, RequestSpecificationOptions options) {
        RequestSpecBuilder builder = baseBuilder(baseUri);
        applyOptions(builder, options);
        return builder.build();
    }

    private RequestSpecification buildSpec(String baseUri, String token) {
        RequestSpecBuilder builder = baseBuilder(baseUri);
        if (token != null) builder.addHeader("Authorization", "Bearer " + token);
        return builder.build();
    }

    private RequestSpecBuilder baseBuilder(String baseUri) {
        return new RequestSpecBuilder()
                .setBaseUri(baseUri)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .addFilter(new AllureRestAssured())
                .log(LogDetail.ALL);
    }

    private void applyOptions(RequestSpecBuilder builder, RequestSpecificationOptions options) {
        if (options.getToken() != null) builder.addHeader("Authorization", "Bearer " + options.getToken());
        if (options.getHeaders() != null) builder.addHeaders(options.getHeaders());
        if (options.getQueryParams() != null) builder.addQueryParams(options.getQueryParams());
        if (options.getPathParams() != null) builder.addPathParams(options.getPathParams());
        if (options.getBody() != null) builder.setBody(options.getBody());
        if (options.getCookies() != null) builder.addCookies(options.getCookies());
    }
    public RequestSpecification forService(ServiceType service, String token) {
        return switch (service) {
            case USER    -> token != null ? getUserServiceSpec(token) : getUserServiceSpec(token);
            case PRODUCT -> token != null ? getProductServiceSpec(token) : getProductServiceSpec(token);
            case ORDER   -> getOrderServiceSpec(token);
            case PAYMENT -> getPaymentServiceSpec(token);
            case GATEWAY -> buildSpec(config.baseUrl(), token);   // new
        };
    }
}
