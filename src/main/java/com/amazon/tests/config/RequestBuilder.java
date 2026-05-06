package com.amazon.tests.config;


import com.amazon.tests.dataseeding.core.SeedingContext;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import java.util.Map;


public class RequestBuilder {
    private static volatile String BASE_URI = null;
    protected static SeedingContext context = null;

   /* static {
        // RuntimeConfig must already be initialized (SuiteInitListener should run first)
        BASE_URI = RuntimeConfig.getBaseUri();
        if (BASE_URI == null) {
            throw new IllegalStateException("BASE_URI not initialized! Check SuiteInitListener");
        }
    }*/

    public RequestBuilder(SeedingContext context) {
        this.context = context;
    }

    public static void overrideBaseUri(String baseUri) {
        BASE_URI = baseUri;
    }

    public static String getBaseUri() {
        if (BASE_URI != null) {
            return BASE_URI;
        }

        synchronized (RequestBuilder.class) {
            if (BASE_URI == null) {

                // 1️⃣ Highest priority: System property (mvn -D)
                String fromSysProp = System.getProperty("gateway.base.url");
                if (fromSysProp != null && !fromSysProp.isBlank()) {
                    BASE_URI = fromSysProp;
                    return BASE_URI;
                }

                // 2️⃣ Environment variable
                String fromEnv = System.getenv("GATEWAY_BASE_URL");
                if (fromEnv != null && !fromEnv.isBlank()) {
                    BASE_URI = fromEnv;
                    return BASE_URI;
                }

                // 3️⃣ RuntimeConfig (SuiteInitListener / TestNG)
                BASE_URI = context.getConfig().baseUrl();

                if (BASE_URI == null || BASE_URI.isBlank()) {
                    throw new IllegalStateException(
                            "BASE_URI not initialized. " +
                                    "Provide via -Dgateway.base.url, env GATEWAY_BASE_URL, or SuiteInitListener"
                    );
                }
            }
        }
        return BASE_URI;
    }

    // ---------------- Base Builder (no duplication) ----------------
    private static RequestSpecBuilder base() {
        return new RequestSpecBuilder()
                .setBaseUri(getBaseUri())
                .setContentType(ContentType.JSON)
                .log(io.restassured.filter.log.LogDetail.ALL);
    }

    // ---------------- Default Spec ----------------
    public static RequestSpecification defaultSpec() {
        return base().build();
    }

    // ---------------- Request with OAuth2 access token ----------------
    public static RequestSpecification withBearerAuth(String token) {
        return base()
                .addHeader("Authorization", "Bearer " + token)
                .build();
    }

    public static RequestSpecification withBasicAuth(String username, String password) {
        return base()
                .setAuth(io.restassured.RestAssured.basic(username, password))
                .build();
    }

    // ---------------- Headers ----------------
    public static RequestSpecification withHeaders(Map<String, String> headers) {
        return base()
                .addHeaders(headers)
                .build();
    }

    // ---------------- Query & Path Params ----------------
    public static RequestSpecification withQueryParams(Map<String, ?> queryParams) {
        return base()
                .addQueryParams(queryParams)
                .build();
    }

    public static RequestSpecification withPathParams(Map<String, ?> pathParams) {
        return base()
                .addPathParams(pathParams)
                .build();
    }

    // ---------------- Body ----------------
    public static RequestSpecification withBody(Object body) {
        return base()
                .setBody(body)
                .build();
    }

    // ---------------- Cookies ----------------
    public static RequestSpecification withCookies(Map<String, ?> cookies) {
        return base()
                .addCookies(cookies)
                .build();
    }

    // ---------------- Timeouts ----------------
    public static RequestSpecification withTimeouts(long connectionTimeout, long socketTimeout) {
        return base()
                .setConfig(RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", connectionTimeout)
                        .setParam("http.socket.timeout", socketTimeout)))
                .build();
    }

    // ---------------- Custom Builder (full flexibility) ----------------
    public static RequestSpecBuilder builder() {
        return base();
    }

    // ---------------- Fully Custom Spec ----------------
    public static RequestSpecification customSpec(
            String token,
            Map<String, String> headers,
            Map<String, ?> queryParams,
            Map<String, ?> pathParams,
            Object body,
            Map<String, ?> cookies,
            Long connectionTimeout,
            Long socketTimeout
    ) {
        RequestSpecBuilder builder = new RequestSpecBuilder()
                .setBaseUri(BASE_URI)
                .setContentType(ContentType.JSON)
                .log(io.restassured.filter.log.LogDetail.ALL);

        if (token != null) builder.addHeader("Authorization", "Bearer " + token);
        if (headers != null) builder.addHeaders(headers);
        if (queryParams != null) builder.addQueryParams(queryParams);
        if (pathParams != null) builder.addPathParams(pathParams);
        if (body != null) builder.setBody(body);
        if (cookies != null) builder.addCookies(cookies);
        if (connectionTimeout != null && socketTimeout != null) {
            builder.setConfig(RestAssuredConfig.config()
                    .httpClient(HttpClientConfig.httpClientConfig()
                            .setParam("http.connection.timeout", connectionTimeout)
                            .setParam("http.socket.timeout", socketTimeout)));
        }

        return builder.build();
    }

}

