package com.amazon.tests.utils.rateLimit;

import com.amazon.tests.config.RateLimitConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.transport.ServiceType;
import com.amazon.tests.utils.apiClients.AuthApiClient;
import com.amazon.tests.utils.apiClients.GatewayApiClient;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.github.javafaker.Faker;
import org.testng.Assert;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Shared helpers for the rate-limiting test suite (RateLimitCoreTest,
 * RateLimitHeadersTest, RateLimitBehaviorTest). Centralizes request
 * dispatch, tolerance-based assertions, order payload construction, and
 * rate-limit header extraction.
 *
 * Client rules: register/login go through AuthApiClient (registerRaw/
 * loginRaw — non-asserting, required since a 429/401 body has no
 * accessToken, and the asserting registerCustomer()/login() would throw
 * mid-loop); everything else goes through GatewayApiClient. Never
 * OrderApiClient/ProductApiClient (both hardcode targetService to their
 * own service, bypassing the gateway — rate limiting is a gateway-only
 * filter, so a direct call could never be rate-limited regardless of
 * load), and never HttpUtils.
 */
public class RateLimitUtil {

    private final AuthApiClient authClient;
    private final GatewayApiClient gatewayClient;

    // Dedicated, race-free credentials for login-scenario requests. NOT
    // the same as any 'user' field the register-scenario burst writes to
    // concurrently — a field written by many threads at once during a
    // burst offers no guarantee the last write actually succeeded
    // server-side (could be one of the rate-limited/429 attempts).
    // knownLoginEmail/knownLoginPassword are set exactly once,
    // synchronously, with a verified 201 before any login burst runs.
    private volatile String knownLoginEmail;
    private volatile String knownLoginPassword;

    public RateLimitUtil(AuthApiClient authClient, GatewayApiClient gatewayClient) {
        this.authClient = authClient;
        this.gatewayClient = gatewayClient;
    }

    /**
     * Dispatches a single request for the given rate-limit scenario
     * config. requestNum varies generated data (unique emails/usernames)
     * across concurrent requests in the same burst where relevant.
     */
    public ServiceResponse sendConfiguredRequest(RateLimitConfig config, String authToken, int requestNum) {
        String endpoint = config.getEndpoint();

        if (endpoint.contains("/register")) {
            TestModels.RegisterRequest randomUser = TestDataFactory.createRandomUser();
            return authClient.registerRaw(randomUser, ServiceType.GATEWAY);
        }

        if (endpoint.contains("/login")) {
            // Ensures one verified, real account exists before any login
            // attempt fires — every concurrent request in the burst reuses
            // these exact credentials, so all attempts target the same
            // real account (the realistic shape of what a login rate
            // limiter defends against) rather than racing against a
            // shared field that may point at a never-actually-registered
            // user.
            ensureKnownLoginUser();
            return authClient.loginRaw(knownLoginEmail, knownLoginPassword, ServiceType.GATEWAY);
        }

        if (endpoint.equals("/api/orders") && "POST".equals(config.getHttpMethod())) {
            return gatewayClient.post(endpoint, authToken, createOrderPayload());
        }

        if (config.getRequestBodyTemplate() != null) {
            String body = String.format(config.getRequestBodyTemplate(), requestNum, requestNum);
            return gatewayClient.post(endpoint, authToken, body);
        }

        // GET requests (products list, order list, health, etc.)
        return gatewayClient.get(endpoint, authToken);
    }

    /**
     * Registers one real, verified user for login-scenario tests to
     * authenticate against. Idempotent and safe to call from every
     * concurrent login request — only the first call (per RateLimitUtil
     * instance) actually registers; subsequent calls see
     * knownLoginEmail already set and return immediately. synchronized
     * because burst tests call sendConfiguredRequest from many threads
     * at once — without this, multiple threads could race into the
     * null-check and register redundant users, or read the fields
     * mid-write.
     */
    private synchronized void ensureKnownLoginUser() {
        if (knownLoginEmail != null) {
            return; // already set up by an earlier call
        }

        TestModels.RegisterRequest req = TestDataFactory.createRandomUser();
        ServiceResponse response = authClient.registerRaw(req, ServiceType.GATEWAY);

        if (response.getStatusCode() != 201) {
            throw new IllegalStateException(
                    "Failed to set up known login user for rate-limit tests. Status: "
                            + response.getStatusCode() + " Body: " + response.getBody());
        }

        knownLoginEmail = req.getEmail();
        knownLoginPassword = req.getPassword();
    }

    public Map<String, Object> createOrderPayload() {
        Faker faker = new Faker();
        UUID productId = UUID.randomUUID();
        return Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId.toString(),
                                "quantity", 1,
                                "unitPrice", 50.0,
                                "productName", "Test Product"
                        )
                ),
                "shippingAddress", faker.address().fullAddress()
        );
    }

    public boolean isSuccessful(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Asserts actual is within ±tolerance of expected. Logs via the
     * supplied logger function (pass BaseTest::logStep from the caller)
     * so this stays independent of BaseTest.
     */
    public void assertWithTolerance(int actual, int expected, int tolerance, String message,
                                    Consumer<String> logger) {
        int diff = Math.abs(actual - expected);
        if (diff <= tolerance) {
            logger.accept(String.format("✓ %s: Expected %d (±%d), got %d", message, expected, tolerance, actual));
        } else {
            String errorMsg = String.format("%s: Expected %d (±%d), got %d (diff: %d)",
                    message, expected, tolerance, actual, diff);
            logger.accept("✗ " + errorMsg);
            Assert.fail(errorMsg);
        }
    }

    /**
     * NOTE: assumes ServiceResponse exposes response headers via
     * getHeader(String) — verify against your actual ServiceResponse
     * source and adjust the accessor if it differs.
     */
    public RateLimitHeaders extractRateLimitHeaders(ServiceResponse response) {
        return new RateLimitHeaders(
                response.getHeaders().get("X-RateLimit-Limit"),
                response.getHeaders().get("X-RateLimit-Remaining"),
                response.getHeaders().get("Retry-After")
        );
    }

    public void verifyRateLimitHeaders(ServiceResponse response, Consumer<String> logger) {
        RateLimitHeaders headers = extractRateLimitHeaders(response);

        if (headers.limit() == null && headers.remaining() == null && headers.retryAfter() == null) {
            logger.accept("  WARNING: No rate limit headers found in 429 response");
            return;
        }

        logger.accept("  Rate Limit Headers: limit=" + headers.limit() + " remaining=" + headers.remaining()
                + " retryAfter=" + headers.retryAfter());

        if (headers.limit() != null) {
            Assert.assertTrue(Integer.parseInt(headers.limit()) > 0, "Rate limit should be positive");
        }
        if (headers.remaining() != null) {
            Assert.assertTrue(Integer.parseInt(headers.remaining()) >= 0, "Remaining should be non-negative");
        }
        if (headers.retryAfter() != null) {
            Assert.assertTrue(Integer.parseInt(headers.retryAfter()) > 0, "Retry-After should be positive");
        }
    }

    public record RateLimitHeaders(String limit, String remaining, String retryAfter) {
    }
}