// RestAssuredConfig.java
package com.amazon.tests.config;

import io.qameta.allure.restassured.AllureRestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import lombok.extern.slf4j.Slf4j;

/**
 * RestAssured specification builder using Owner's TestConfig
 * Thread-safe for parallel test execution
 *
 * @author Amazon Automation Team
 */
@Slf4j
public class RestAssuredConfigFinal {

    // ThreadLocal for thread-safe caching in parallel execution
    private static final ThreadLocal<RequestSpecification> baseSpecCache = new ThreadLocal<>();

    private final TestConfig config;

    // Constructor for dependency injection
    public RestAssuredConfigFinal(TestConfig config) {
        this.config = config;
    }

    // ==========================================
    // BASE SPECIFICATIONS
    // ==========================================

    /**
     * Get base specification with common configuration
     * Thread-safe - each thread gets its own cached instance
     *
     * Example:
     * RequestSpecification spec = restAssuredConfig.getBaseSpec();
     */
    public RequestSpecification getBaseSpec() {
        if (baseSpecCache.get() == null) {
            baseSpecCache.set(createBaseSpec());
        }
        return baseSpecCache.get();
    }

    /**
     * Get authenticated specification with Bearer token
     *
     * Example:
     * RequestSpecification spec = restAssuredConfig.getAuthSpec(token);
     */
    public RequestSpecification getAuthSpec(String token) {
        return new RequestSpecBuilder()
                .addRequestSpecification(getBaseSpec())
                .addHeader("Authorization", "Bearer " + token)
                .build();
    }

    // ==========================================
    // SERVICE-SPECIFIC SPECIFICATIONS
    // ==========================================

    /**
     * Get User Service specification (unauthenticated)
     * For public endpoints like registration
     */
    public RequestSpecification getUserServiceSpec() {
        return createServiceSpec(config.baseUrl());
    }

    /**
     * Get authenticated User Service specification
     */
    public RequestSpecification getUserServiceSpec(String token) {
        return createAuthServiceSpec(config.userServiceUrl(), token);
    }

    /**
     * Get Product Service specification (unauthenticated)
     * For public product browsing
     */
    public RequestSpecification getProductServiceSpec() {
        return createServiceSpec(config.productServiceUrl());
    }

    /**
     * Get authenticated Product Service specification
     * For seller/admin operations
     */
    public RequestSpecification getProductServiceSpec(String token) {
        return createAuthServiceSpec(config.productServiceUrl(), token);
    }

    /**
     * Get Order Service specification (always requires auth)
     */
    public RequestSpecification getOrderServiceSpec(String token) {
        return createAuthServiceSpec(config.orderServiceUrl(), token);
    }

    /**
     * Get Payment Service specification (always requires auth)
     */
    public RequestSpecification getPaymentServiceSpec(String token) {
        return createAuthServiceSpec(config.paymentServiceUrl(), token);
    }

    // ==========================================
    // RESPONSE SPECIFICATIONS
    // ==========================================

    /**
     * Get success response specification (2xx status codes)
     */
    public ResponseSpecification getSuccessResponseSpec() {
        return new ResponseSpecBuilder()
                .expectContentType(ContentType.JSON)
                .expectStatusCode(200)
                .log(LogDetail.ALL)
                .build();
    }

    /**
     * Get created response specification (201 status)
     */
    public ResponseSpecification getCreatedResponseSpec() {
        return new ResponseSpecBuilder()
                .expectContentType(ContentType.JSON)
                .expectStatusCode(201)
                .log(LogDetail.ALL)
                .build();
    }

    /**
     * Get no content response specification (204 status)
     */
    public ResponseSpecification getNoContentResponseSpec() {
        return new ResponseSpecBuilder()
                .expectStatusCode(204)
                .log(LogDetail.ALL)
                .build();
    }

    /**
     * Get bad request response specification (400 status)
     */
    public ResponseSpecification getBadRequestResponseSpec() {
        return new ResponseSpecBuilder()
                .expectContentType(ContentType.JSON)
                .expectStatusCode(400)
                .log(LogDetail.ALL)
                .build();
    }

    /**
     * Get unauthorized response specification (401 status)
     */
    public ResponseSpecification getUnauthorizedResponseSpec() {
        return new ResponseSpecBuilder()
                .expectContentType(ContentType.JSON)
                .expectStatusCode(401)
                .log(LogDetail.ALL)
                .build();
    }

    /**
     * Get not found response specification (404 status)
     */
    public ResponseSpecification getNotFoundResponseSpec() {
        return new ResponseSpecBuilder()
                .expectContentType(ContentType.JSON)
                .expectStatusCode(404)
                .log(LogDetail.ALL)
                .build();
    }

    // ==========================================
    // CLEANUP
    // ==========================================

    /**
     * Clear ThreadLocal cache to prevent memory leaks
     * Call this in @AfterMethod or @AfterClass
     */
    public static void clearCache() {
        baseSpecCache.remove();
        log.debug("RestAssuredConfig cache cleared");
    }

    // ==========================================
    // PRIVATE HELPER METHODS
    // ==========================================

    /**
     * Create base specification with common settings
     */
    private RequestSpecification createBaseSpec() {
        log.debug("Creating base spec for: {}", config.baseUrl());

        return new RequestSpecBuilder()
                .setBaseUri(config.baseUrl())
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .addFilter(new AllureRestAssured())
                .log(LogDetail.ALL)
                .build();
    }

    /**
     * Create specification for a specific service URL
     */
    private RequestSpecification createServiceSpec(String baseUri) {
        log.debug("Creating service spec for: {}", baseUri);

        return new RequestSpecBuilder()
                .setBaseUri(baseUri)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .addFilter(new AllureRestAssured())
                .log(LogDetail.ALL)
                .build();
    }

    /**
     * Create authenticated specification for a specific service URL
     */
    private RequestSpecification createAuthServiceSpec(String baseUri, String token) {
        log.debug("Creating authenticated service spec for: {}", baseUri);

        return new RequestSpecBuilder()
                .setBaseUri(baseUri)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .addHeader("Authorization", "Bearer " + token)
                .addFilter(new AllureRestAssured())
                .log(LogDetail.ALL)
                .build();
    }
}