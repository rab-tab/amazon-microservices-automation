package com.amazon.tests.config.restAsssured;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Thread-safe REST client for API interactions.
 * Wraps RestAssured for consistent API calls across the framework.
 *
 * @author Amazon Automation Team
 */
@Slf4j
public class RestClient {

    // ==========================================
    // GET
    // ==========================================

    public Response get(String endpoint, RequestSpecification spec) {
        return execute("GET", endpoint, spec, null, Map.of());
    }

    public Response get(String endpoint, RequestSpecification spec, Map<String, ?> queryParams) {
        return execute("GET", endpoint, spec, null, queryParams);
    }

    public <T> T get(String endpoint, RequestSpecification spec, Class<T> responseType) {
        return get(endpoint, spec, Map.of(), responseType);
    }

    public <T> T get(String endpoint, RequestSpecification spec,
                     Map<String, ?> queryParams, Class<T> responseType) {
        return execute("GET", endpoint, spec, null, queryParams).as(responseType);
    }

    // ==========================================
    // POST
    // ==========================================

    public Response post(String endpoint, RequestSpecification spec) {
        return execute("POST", endpoint, spec, null, Map.of());
    }

    public Response post(String endpoint, RequestSpecification spec, Object body) {
        return execute("POST", endpoint, spec, body, Map.of());
    }

    public Response post(String endpoint, RequestSpecification spec, Object body, Map<String, String> headers) {
        return execute("POST", endpoint, spec, body, Map.of(), headers);
    }

    public <T> T post(String endpoint, RequestSpecification spec, Object body, Class<T> responseType) {
        return execute("POST", endpoint, spec, body, Map.of()).as(responseType);
    }

    public <T> T post(String endpoint, RequestSpecification spec,
                      Object body, Class<T> responseType, Map<String, String> headers) {
        return execute("POST", endpoint, spec, body, Map.of(), headers).as(responseType);
    }

    // ==========================================
    // PUT
    // ==========================================

    public Response put(String endpoint, RequestSpecification spec) {
        return execute("PUT", endpoint, spec, null, Map.of());
    }

    public Response put(String endpoint, RequestSpecification spec, Object body) {
        return execute("PUT", endpoint, spec, body, Map.of());
    }

    public <T> T put(String endpoint, RequestSpecification spec, Object body, Class<T> responseType) {
        return execute("PUT", endpoint, spec, body, Map.of()).as(responseType);
    }

    // ==========================================
    // PATCH
    // ==========================================

    public Response patch(String endpoint, RequestSpecification spec, Object body) {
        return execute("PATCH", endpoint, spec, body, Map.of());
    }

    public <T> T patch(String endpoint, RequestSpecification spec, Object body, Class<T> responseType) {
        return execute("PATCH", endpoint, spec, body, Map.of()).as(responseType);
    }

    // ==========================================
    // DELETE
    // ==========================================

    public Response delete(String endpoint, RequestSpecification spec) {
        return execute("DELETE", endpoint, spec, null, Map.of());
    }

    public Response delete(String endpoint, RequestSpecification spec, Map<String, ?> queryParams) {
        return execute("DELETE", endpoint, spec, null, queryParams);
    }

    // ==========================================
    // HELPERS
    // ==========================================

    public boolean isSuccess(Response response) {
        int statusCode = response.getStatusCode();
        return statusCode >= 200 && statusCode < 300;
    }

    public String getBodyAsString(Response response) {
        return response.getBody().asString();
    }

    public int getStatusCode(Response response) {
        return response.getStatusCode();
    }

    // ==========================================
    // PRIVATE CORE — single source of truth for every call
    // ==========================================

    private Response execute(String method, String endpoint, RequestSpecification spec,
                             Object body, Map<String, ?> queryParams) {
        return execute(method, endpoint, spec, body, queryParams, Map.of());
    }

    private Response execute(String method, String endpoint, RequestSpecification spec,
                             Object body, Map<String, ?> queryParams, Map<String, String> extraHeaders) {
        log.debug("{} {}", method, endpoint);

        var request = given().spec(spec);

        if (!queryParams.isEmpty()) request = request.queryParams(queryParams);
        if (!extraHeaders.isEmpty()) request = request.headers(extraHeaders);
        if (body != null) request = request.body(body);

        Response response = switch (method) {
            case "GET"    -> request.when().get(endpoint);
            case "POST"   -> request.when().post(endpoint);
            case "PUT"    -> request.when().put(endpoint);
            case "PATCH"  -> request.when().patch(endpoint);
            case "DELETE" -> request.when().delete(endpoint);
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        };

        response.then().log().ifError();
        return response;
    }
}