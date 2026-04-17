package com.amazon.tests.utils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;

/**
 * HTTP Utility class for sending requests across test classes
 * Provides reusable methods for GET, POST, PUT, DELETE with authentication support
 */
public class HttpUtils {

    private static final String BASE_URL = "http://localhost:8080";
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Send HTTP request with full control
     *
     * @param endpoint API endpoint (e.g., "/api/orders")
     * @param body Request body (null for GET/DELETE)
     * @param authToken Bearer token (null for anonymous requests)
     * @param method HTTP method (GET, POST, PUT, DELETE)
     * @return HTTP response
     */
    public static HttpResponse<String> sendRequest(
            String endpoint,
            String body,
            String authToken,
            String method
    ) throws Exception {

        StringBuilder logBuilder = new StringBuilder();

        // Build request log
        buildRequestLog(logBuilder, method, endpoint, body, authToken);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + endpoint))
                .timeout(Duration.ofSeconds(10));

        // Add authorization header if token provided
        if (authToken != null && !authToken.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + authToken);
        }

        // Add content-type for requests with body
        if (body != null && !body.isEmpty()) {
            requestBuilder.header("Content-Type", "application/json");
        }

        // Set HTTP method with body
        switch (method.toUpperCase()) {
            case "GET":
                requestBuilder.GET();
                break;
            case "POST":
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body != null ? body : ""));
                break;
            case "PUT":
                requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(body != null ? body : ""));
                break;
            case "DELETE":
                requestBuilder.DELETE();
                break;
            case "PATCH":
                requestBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(body != null ? body : ""));
                break;
            default:
                throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        }

        // Send request and get response
        long startTime = System.currentTimeMillis();
        HttpResponse<String> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString()
        );
        long duration = System.currentTimeMillis() - startTime;

        // Build response log
        buildResponseLog(logBuilder, response, duration);

        // Print combined log
        if (LOGGING_ENABLED) {
            System.out.println(logBuilder.toString());
        }

        return response;
    }

    /**
     * Send GET request
     */
    public static HttpResponse<String> get(String endpoint, String authToken) throws Exception {
        return sendRequest(endpoint, null, authToken, "GET");
    }

    /**
     * Send POST request
     */
    public static HttpResponse<String> post(String endpoint, String body, String authToken) throws Exception {
        return sendRequest(endpoint, body, authToken, "POST");
    }

    /**
     * Send PUT request
     */
    public static HttpResponse<String> put(String endpoint, String body, String authToken) throws Exception {
        return sendRequest(endpoint, body, authToken, "PUT");
    }

    /**
     * Send DELETE request
     */
    public static HttpResponse<String> delete(String endpoint, String authToken) throws Exception {
        return sendRequest(endpoint, null, authToken, "DELETE");
    }

    /**
     * Authenticate user and get JWT token
     *
     * @param username User's username
     * @param password User's password
     * @return JWT token or null if authentication fails
     */
    public static String authenticateUser(String username, String password) throws Exception {
        String loginBody = String.format(
                "{\"username\":\"%s\",\"password\":\"%s\"}",
                username, password
        );

        HttpResponse<String> response = post("/api/auth/login", loginBody, null);

        if (response.statusCode() == 200) {
            return extractToken(response.body());
        }

        return null;
    }

    /**
     * Extract JWT token from authentication response
     * Supports multiple response formats
     */
    private static String extractToken(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return null;
        }

        try {
            // Format 1: {"token":"eyJhbGc..."}
            if (responseBody.contains("\"token\"")) {
                int start = responseBody.indexOf("\"token\":\"") + 9;
                int end = responseBody.indexOf("\"", start);
                if (start > 8 && end > start) {
                    return responseBody.substring(start, end);
                }
            }

            // Format 2: {"accessToken":"..."}
            if (responseBody.contains("\"accessToken\"")) {
                int start = responseBody.indexOf("\"accessToken\":\"") + 15;
                int end = responseBody.indexOf("\"", start);
                if (start > 14 && end > start) {
                    return responseBody.substring(start, end);
                }
            }

            // Format 3: {"access_token":"..."}
            if (responseBody.contains("\"access_token\"")) {
                int start = responseBody.indexOf("\"access_token\":\"") + 16;
                int end = responseBody.indexOf("\"", start);
                if (start > 15 && end > start) {
                    return responseBody.substring(start, end);
                }
            }
        } catch (Exception e) {
            // Token extraction failed
            return null;
        }

        return null;
    }

    /**
     * Get base URL for tests
     */
    public static String getBaseUrl() {
        return BASE_URL;
    }

    /**
     * Check if response is successful (2xx status code)
     */
    public static boolean isSuccessful(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Check if response is rate limited
     */
    public static boolean isRateLimited(int statusCode) {
        return statusCode == 429;
    }

    /**
     * Extract rate limit information from response headers
     */
    public static RateLimitInfo getRateLimitInfo(HttpResponse<String> response) {
        return new RateLimitInfo(
                response.headers().firstValue("X-RateLimit-Limit").orElse(null),
                response.headers().firstValue("X-RateLimit-Remaining").orElse(null),
                response.headers().firstValue("X-RateLimit-Reset").orElse(null),
                response.headers().firstValue("Retry-After").orElse(null)
        );
    }


    // Logging configuration - set to true to enable request/response logging
    private static boolean LOGGING_ENABLED = true;

    /**
     * Enable or disable request/response logging
     *
     * @param enabled true to enable logging, false to disable
     */
    public static void setLoggingEnabled(boolean enabled) {
        LOGGING_ENABLED = enabled;
    }

    /**
     * Build request portion of the log (like RestAssured log.all())
     */
    private static void buildRequestLog(StringBuilder log, String method, String endpoint,
                                        String body, String authToken) {
        if (!LOGGING_ENABLED) {
            return;
        }

        log.append("\n");
        log.append("┌────────────────────────────────────────────────────────────────────────────────\n");
        log.append("│ REQUEST\n");
        log.append("├────────────────────────────────────────────────────────────────────────────────\n");
        log.append(String.format("│ %-15s %s %s\n", "Method:", method, endpoint));
        log.append(String.format("│ %-15s %s\n", "URI:", BASE_URL + endpoint));

        // Headers
        log.append("│\n");
        log.append("│ Headers:\n");
        log.append("│     Content-Type: application/json\n");

        if (authToken != null && !authToken.isEmpty()) {
            String maskedToken = authToken.length() > 15
                    ? authToken.substring(0, 15) + "..."
                    : "***";
            log.append(String.format("│     Authorization: Bearer %s\n", maskedToken));
        }

        // Body
        if (body != null && !body.isEmpty()) {
            log.append("│\n");
            log.append("│ Body:\n");
            String[] bodyLines = body.split("\n");
            for (String line : bodyLines) {
                log.append(String.format("│     %s\n", line));
            }
        }
    }

    /**
     * Build response portion of the log (like RestAssured log.all())
     */
    private static void buildResponseLog(StringBuilder log, HttpResponse<String> response, long duration) {
        if (!LOGGING_ENABLED) {
            return;
        }

        int statusCode = response.statusCode();

        log.append("├────────────────────────────────────────────────────────────────────────────────\n");
        log.append("│ RESPONSE\n");
        log.append("├────────────────────────────────────────────────────────────────────────────────\n");
        log.append(String.format("│ %-15s %d %s\n", "Status Code:", statusCode, getStatusText(statusCode)));
        log.append(String.format("│ %-15s %d ms\n", "Time:", duration));

        // Headers
        log.append("│\n");
        log.append("│ Headers:\n");

        response.headers().firstValue("Content-Type").ifPresent(ct ->
                log.append(String.format("│     Content-Type: %s\n", ct))
        );

        // Rate limit headers
        RateLimitInfo rateLimitInfo = getRateLimitInfo(response);
        if (rateLimitInfo.hasRateLimitHeaders()) {
            if (rateLimitInfo.getLimit() != null) {
                log.append(String.format("│     X-RateLimit-Limit: %s\n", rateLimitInfo.getLimit()));
            }
            if (rateLimitInfo.getRemaining() != null) {
                log.append(String.format("│     X-RateLimit-Remaining: %s\n", rateLimitInfo.getRemaining()));
            }
            if (rateLimitInfo.getReset() != null) {
                log.append(String.format("│     X-RateLimit-Reset: %s\n", rateLimitInfo.getReset()));
            }
            if (rateLimitInfo.getRetryAfter() != null) {
                log.append(String.format("│     Retry-After: %s\n", rateLimitInfo.getRetryAfter()));
            }
        }

        // Body
        String responseBody = response.body();
        if (responseBody != null && !responseBody.isEmpty()) {
            log.append("│\n");
            log.append("│ Body:\n");

            if (responseBody.length() > 1000) {
                log.append(String.format("│     %s\n", responseBody.substring(0, 1000)));
                log.append("│     ... (response truncated)\n");
            } else {
                String[] bodyLines = responseBody.split("\n");
                for (String line : bodyLines) {
                    log.append(String.format("│     %s\n", line));
                }
            }
        }

        log.append("└────────────────────────────────────────────────────────────────────────────────\n");
    }

    /**
     * Get human-readable status text for HTTP status codes
     */
    private static String getStatusText(int statusCode) {
        switch (statusCode) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "";
        }
    }

    /**
     * Rate limit information holder
     */
    public static class RateLimitInfo {
        private final String limit;
        private final String remaining;
        private final String reset;
        private final String retryAfter;

        public RateLimitInfo(String limit, String remaining, String reset, String retryAfter) {
            this.limit = limit;
            this.remaining = remaining;
            this.reset = reset;
            this.retryAfter = retryAfter;
        }

        public String getLimit() { return limit; }
        public String getRemaining() { return remaining; }
        public String getReset() { return reset; }
        public String getRetryAfter() { return retryAfter; }

        public boolean hasRateLimitHeaders() {
            return limit != null || remaining != null || reset != null || retryAfter != null;
        }

        @Override
        public String toString() {
            return String.format("RateLimit[limit=%s, remaining=%s, reset=%s, retryAfter=%s]",
                    limit, remaining, reset, retryAfter);
        }
    }
}