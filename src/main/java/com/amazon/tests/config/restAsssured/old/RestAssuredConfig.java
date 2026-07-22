package com.amazon.tests.config.restAsssured.old;

import com.amazon.tests.config.ConfigManager;
import io.qameta.allure.restassured.AllureRestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;

/**
 * RestAssured specification builder using thread-safe ConfigManager.
 * All methods are thread-safe for parallel test execution.
 */
public class RestAssuredConfig {

    // ✅ ThreadLocal for thread-safe caching in parallel execution
    private static ThreadLocal<RequestSpecification> baseSpec = new ThreadLocal<>();

    // ✅ Private constructor prevents instantiation
    private RestAssuredConfig() {
        throw new IllegalStateException("Utility class - do not instantiate");
    }

    /**
     * Get base specification with common configuration.
     * Thread-safe - each thread gets its own cached instance.
     */
    public static RequestSpecification getBaseSpec() {
        if (baseSpec.get() == null) {
            baseSpec.set(createBaseSpec());
        }
        return baseSpec.get();
    }

    /**
     * Get authenticated specification.
     * Creates new spec each time (token might change).
     */
    public static RequestSpecification getAuthSpec(String token) {
        return new RequestSpecBuilder()
                .addRequestSpecification(getBaseSpec())
                .addHeader("Authorization", "Bearer " + token)
                .build();
    }

    /**
     * Get User Service specification.
     */
    public static RequestSpecification getUserServiceSpec() {
        return createSpec(ConfigManager.getInstance().getUserServiceUrl());
    }

    /**
     * Get authenticated User Service specification.
     */
    public static RequestSpecification getUserServiceSpec(String token) {
        return createAuthSpec(ConfigManager.getInstance().getUserServiceUrl(), token);
    }

    /**
     * Get Product Service specification.
     */
    public static RequestSpecification getProductServiceSpec() {
        return createSpec(ConfigManager.getInstance().getProductServiceUrl());
    }

    /**
     * Get Order Service specification with authentication.
     */
    public static RequestSpecification getOrderServiceSpec(String token) {
        return createAuthSpec(ConfigManager.getInstance().getOrderServiceUrl(), token);
    }

    /**
     * Get Payment Service specification with authentication.
     */
    public static RequestSpecification getPaymentServiceSpec(String token) {
        return createAuthSpec(ConfigManager.getInstance().getPaymentServiceUrl(), token);
    }

    /**
     * Get success response specification.
     */
    public static ResponseSpecification getSuccessResponseSpec() {
        return new ResponseSpecBuilder()
                .expectContentType(ContentType.JSON)
                .log(LogDetail.ALL)
                .build();
    }

    /**
     * Clear ThreadLocal cache (call in @AfterMethod to prevent memory leaks).
     */
    public static void clearCache() {
        baseSpec.remove();
    }

    // ===== PRIVATE HELPER METHODS (DRY principle) =====

    /**
     * Create base specification with common settings.
     */
    private static RequestSpecification createBaseSpec() {
        return new RequestSpecBuilder()
                .setBaseUri(ConfigManager.getInstance().getBaseUrl())
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .addFilter(new AllureRestAssured())
                .log(LogDetail.ALL)
                .build();
    }

   /* private static RequestSpecification createBaseSpec() {
        PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
        connManager.setMaxTotal(50);
        connManager.setDefaultMaxPerRoute(20);   // raised well above the classic default of 2

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connManager)
                .setConnectionManagerShared(true)   // don't let this client's .close() tear down a pool other threads still use
                .build();

        return new RequestSpecBuilder()
                .setBaseUri(ConfigManager.getInstance().getBaseUrl())
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .addFilter(new AllureRestAssured())
                .log(LogDetail.ALL)
                .setConfig(io.restassured.config.RestAssuredConfig.config()
                        .httpClient(io.restassured.config.HttpClientConfig.httpClientConfig()
                                .httpClientFactory(() -> httpClient)
                                .reuseHttpClientInstance()))
                .build();
    }*/
    /**
     * Create specification for a specific service URL.
     */
    private static RequestSpecification createSpec(String baseUri) {
        return new RequestSpecBuilder()
                .setBaseUri(baseUri)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .addFilter(new AllureRestAssured())
                .log(LogDetail.ALL)
                .build();
    }

    /**
     * Create authenticated specification for a specific service URL.
     */
    private static RequestSpecification createAuthSpec(String baseUri, String token) {
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