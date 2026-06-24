// RestClient.java
package com.amazon.tests.config;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Thread-safe REST client for API interactions
 * Wraps RestAssured for consistent API calls across the framework
 *
 * Features:
 * - GET, POST, PUT, PATCH, DELETE methods
 * - Automatic response extraction
 * - Request/Response logging
 * - Error handling
 *
 * @author Amazon Automation Team
 */
@Slf4j
public class RestClient {

    // ==========================================
    // GET METHODS
    // ==========================================

    /**
     * GET request with custom spec
     *
     * Example:
     * Response response = restClient.get("/api/users", spec);
     */
    public Response get(String endpoint, RequestSpecification spec) {
        log.debug("GET {}", endpoint);

        return given()
                .spec(spec)
                .when()
                .get(endpoint)
                .then()
                .log().ifError()
                .extract()
                .response();
    }

    /**
     * GET request with query parameters
     *
     * Example:
     * Map<String, Object> params = Map.of("page", 1, "size", 10);
     * Response response = restClient.get("/api/products", spec, params);
     */
    public Response get(String endpoint, RequestSpecification spec, Map<String, ?> queryParams) {
        log.debug("GET {} with query params: {}", endpoint, queryParams);

        return given()
                .spec(spec)
                .queryParams(queryParams)
                .when()
                .get(endpoint)
                .then()
                .log().ifError()
                .extract()
                .response();
    }

    /**
     * GET request and extract response body as type
     *
     * Example:
     * UserResponse user = restClient.get("/api/users/123", spec, UserResponse.class);
     */
    public <T> T get(String endpoint, RequestSpecification spec, Class<T> responseType) {
        log.debug("GET {} -> {}", endpoint, responseType.getSimpleName());

        return given()
                .spec(spec)
                .when()
                .get(endpoint)
                .then()
                .log().ifError()
                .extract()
                .as(responseType);
    }

    /**
     * GET request with query params and extract as type
     *
     * Example:
     * PagedProductResponse products = restClient.get(
     *     "/api/products", 
     *     spec, 
     *     Map.of("page", 1), 
     *     PagedProductResponse.class
     * );
     */
    public <T> T get(String endpoint, RequestSpecification spec,
                     Map<String, ?> queryParams, Class<T> responseType) {
        log.debug("GET {} with params: {} -> {}", endpoint, queryParams, responseType.getSimpleName());

        return given()
                .spec(spec)
                .queryParams(queryParams)
                .when()
                .get(endpoint)
                .then()
                .log().ifError()
                .extract()
                .as(responseType);
    }

    // ==========================================
    // POST METHODS
    // ==========================================

    /**
     * POST request without body
     *
     * Example:
     * Response response = restClient.post("/api/logout", spec);
     */
    public Response post(String endpoint, RequestSpecification spec) {
        log.debug("POST {}", endpoint);

        return given()
                .spec(spec)
                .when()
                .post(endpoint)
                .then()
                .log().ifError()
                .extract()
                .response();
    }

    /**
     * POST request with body, headers, and extract as type
     * ✅ THIS IS THE METHOD YOU NEED
     */
    public <T> T post(String endpoint, RequestSpecification spec,
                      Object body, Class<T> responseType, Map<String, String> headers) {
        log.debug("POST {} with body: {}, headers: {} -> {}",
                endpoint, body.getClass().getSimpleName(), headers, responseType.getSimpleName());

        return given()
                .spec(spec)
                .headers(headers)
                .body(body)
                .when()
                .post(endpoint)
                .then()
                .log().ifError()
                .extract()
                .as(responseType);
    }
    /**
     * POST request with body
     *
     * Example:
     * RegisterRequest request = RegisterRequest.builder()...build();
     * Response response = restClient.post("/api/auth/register", spec, request);
     */
    public Response post(String endpoint, RequestSpecification spec, Object body) {
        log.debug("POST {} with body: {}", endpoint, body.getClass().getSimpleName());

        return given()
                .spec(spec)
                .body(body)
                .when()
                .post(endpoint)
                .then()
                .log().ifError()
                .extract()
                .response();
    }

    /**
     * POST request with body and extract as type
     *
     * Example:
     * RegisterRequest request = RegisterRequest.builder()...build();
     * AuthResponse auth = restClient.post(
     *     "/api/auth/register", 
     *     spec, 
     *     request, 
     *     AuthResponse.class
     * );
     */
    public <T> T post(String endpoint, RequestSpecification spec,
                      Object body, Class<T> responseType) {
        log.info("POST {} with body: {} -> {}",
                endpoint, body.getClass().getSimpleName(), responseType.getSimpleName());


        return given().log().all()
                .spec(spec)
                .body(body)
                .when()
                .post(endpoint)
                .then()
                .log().all()
                .extract()
                .as(responseType);
    }

    /**
     * POST request with body and headers
     *
     * Example:
     * Map<String, String> headers = Map.of("X-Request-ID", "123");
     * Response response = restClient.post("/api/orders", spec, orderRequest, headers);
     */
    public Response post(String endpoint, RequestSpecification spec,
                         Object body, Map<String, String> headers) {
        log.debug("POST {} with body and headers", endpoint);

        return given()
                .spec(spec)
                .headers(headers)
                .body(body)
                .when()
                .post(endpoint)
                .then()
                .log().ifError()
                .extract()
                .response();
    }

    // ==========================================
    // PUT METHODS
    // ==========================================

    /**
     * PUT request without body
     *
     * Example:
     * Response response = restClient.put("/api/users/123/activate", spec);
     */
    public Response put(String endpoint, RequestSpecification spec) {
        log.debug("PUT {}", endpoint);

        return given()
                .spec(spec)
                .when()
                .put(endpoint)
                .then()
                .log().ifError()
                .extract()
                .response();
    }

    /**
     * PUT request with body
     *
     * Example:
     * UpdateUserRequest request = UpdateUserRequest.builder()...build();
     * Response response = restClient.put("/api/users/123", spec, request);
     */
    public Response put(String endpoint, RequestSpecification spec, Object body) {
        log.debug("PUT {} with body: {}", endpoint, body.getClass().getSimpleName());

        return given()
                .spec(spec)
                .body(body)
                .when()
                .put(endpoint)
                .then()
                .log().ifError()
                .extract()
                .response();
    }

    /**
     * PUT request with body and extract as type
     *
     * Example:
     * UpdateUserRequest request = UpdateUserRequest.builder()...build();
     * UserResponse updated = restClient.put(
     *     "/api/users/123", 
     *     spec, 
     *     request, 
     *     UserResponse.class
     * );
     */
    public <T> T put(String endpoint, RequestSpecification spec,
                     Object body, Class<T> responseType) {
        log.debug("PUT {} with body: {} -> {}",
                endpoint, body.getClass().getSimpleName(), responseType.getSimpleName());

        return given()
                .spec(spec)
                .body(body)
                .when()
                .put(endpoint)
                .then()
                .log().ifError()
                .extract()
                .as(responseType);
    }

    // ==========================================
    // PATCH METHODS
    // ==========================================

    /**
     * PATCH request with body
     *
     * Example:
     * Map<String, Object> patch = Map.of("status", "ACTIVE");
     * Response response = restClient.patch("/api/users/123", spec, patch);
     */
    public Response patch(String endpoint, RequestSpecification spec, Object body) {
        log.debug("PATCH {} with body: {}", endpoint, body.getClass().getSimpleName());

        return given()
                .spec(spec)
                .body(body)
                .when()
                .patch(endpoint)
                .then()
                .log().ifError()
                .extract()
                .response();
    }

    /**
     * PATCH request with body and extract as type
     */
    public <T> T patch(String endpoint, RequestSpecification spec,
                       Object body, Class<T> responseType) {
        log.debug("PATCH {} with body: {} -> {}",
                endpoint, body.getClass().getSimpleName(), responseType.getSimpleName());

        return given()
                .spec(spec)
                .body(body)
                .when()
                .patch(endpoint)
                .then()
                .log().ifError()
                .extract()
                .as(responseType);
    }

    // ==========================================
    // DELETE METHODS
    // ==========================================

    /**
     * DELETE request
     *
     * Example:
     * Response response = restClient.delete("/api/users/123", spec);
     */
    public Response delete(String endpoint, RequestSpecification spec) {
        log.debug("DELETE {}", endpoint);

        return given()
                .spec(spec)
                .when()
                .delete(endpoint)
                .then()
                .log().ifError()
                .extract()
                .response();
    }

    public Response delete(String endpoint, String accessToken) {
        log.debug("DELETE {}", endpoint);

        return given().log().all()
                .header("Authorization", "Bearer " + accessToken)
                .header("X-User-Role","ADMIN")
                .when()
                .delete(endpoint)
                .then()
                .log().ifError()
                .extract()
                .response();
    }


    /**
     * DELETE request with query params
     *
     * Example:
     * Map<String, Object> params = Map.of("force", true);
     * Response response = restClient.delete("/api/users/123", spec, params);
     */
    public Response delete(String endpoint, RequestSpecification spec,
                           Map<String, ?> queryParams) {
        log.debug("DELETE {} with params: {}", endpoint, queryParams);

        return given()
                .spec(spec)
                .queryParams(queryParams)
                .when()
                .delete(endpoint)
                .then()
                .log().ifError()
                .extract()
                .response();
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

    /**
     * Check if response is successful (2xx status code)
     */
    public boolean isSuccess(Response response) {
        int statusCode = response.getStatusCode();
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Extract response body as string
     */
    public String getBodyAsString(Response response) {
        return response.getBody().asString();
    }

    /**
     * Extract status code
     */
    public int getStatusCode(Response response) {
        return response.getStatusCode();
    }

    /**
     * Close the client (cleanup if needed)
     */
    public void close() {
        // RestAssured doesn't need explicit closing
        // But this method exists for interface consistency
        log.debug("RestClient closed");
    }


    public void delete(String endpoint, Map<String, String> headers) {
    }

    // RestClient.java - ADD THIS METHOD

    /**
     * POST request with full URL, body, responseType, and headers
     * (Convenience method that creates spec internally)
     */
    public <T> T post(String fullUrl, Object body, Class<T> responseType,
                      Map<String, String> headers) {
        log.debug("POST {} with body: {}, headers: {} -> {}",
                fullUrl, body.getClass().getSimpleName(), headers, responseType.getSimpleName());

        // Create a minimal spec with just the URL
        RequestSpecification spec = new RequestSpecBuilder()
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .log(LogDetail.ALL)
                .build();

        return given()
                .spec(spec)
                .headers(headers)
                .body(body)
                .when()
                .post(fullUrl)  // ← Full URL with base
                .then()
                .log().ifError()
                .extract()
                .as(responseType);
    }

    public Response delete(String endpoint) {
        log.debug("DELETE {}", endpoint);

        return given()
                .when()
                .delete(endpoint)
                .then()
                .log().ifError()
                .extract()
                .response();
    }
}