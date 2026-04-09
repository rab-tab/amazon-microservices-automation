package com.amazon.tests.tests.apiGateway.resiliency;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import com.github.javafaker.Faker;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * API Gateway Rate Limiting Test Suite - CORRECTED
 *
 * All tests use PARALLEL execution to actually trigger rate limits.
 * Sequential requests allow token replenishment between requests.
 */
@Epic("Amazon Microservices")
@Feature("API Gateway - Rate Limiting")
public class RateLimiting extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final Faker faker = new Faker();

    private String validToken1;
    private String validToken2;
    private String userId1;
    private String userId2;

    @BeforeClass
    public void setup() {
        logStep("Setting up rate limiting tests");

        TestModels.AuthResponse auth1 = AuthUtils.registerAndGetAuth();
        validToken1 = auth1.getAccessToken();
        userId1 = auth1.getUser().getId();

        TestModels.AuthResponse auth2 = AuthUtils.registerAndGetAuth();
        validToken2 = auth2.getAccessToken();
        userId2 = auth2.getUser().getId();

        logStep("✅ Setup complete - User 1: " + userId1);
        logStep("                   User 2: " + userId2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // IP-BASED RATE LIMITING
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 1,enabled = false)
    @Story("Rate Limit - IP Based - Registration")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Registration endpoint: 5/sec, burst: 10 (IP-based)")
    public void test01_RegistrationRateLimiting() throws InterruptedException {
        logStep("TEST 1: /api/users/register - 5/sec, burst: 10");

        int totalRequests = 20;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(totalRequests);

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger rateLimited = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);

        logStep("  Firing " + totalRequests + " requests simultaneously...");

        for (int i = 0; i < totalRequests; i++) {
            final int requestNum = i;

            executor.submit(() -> {
                try {
                    Map<String, String> userData = Map.of(
                            "username", faker.name().username() + faker.number().digits(4),
                            "email", faker.internet().emailAddress(),
                            "password", "Test@123456",
                            "firstName", faker.name().firstName(),
                            "lastName", faker.name().lastName(),
                            "phone", "+91" + faker.number().digits(10)
                    );

                    Response resp = given()
                            .baseUri(GATEWAY_URL)
                            .contentType("application/json")
                            .body(userData)
                            .when()
                            .post("/api/users/register")
                            .then()
                            .extract()
                            .response();

                    if (resp.statusCode() == 201) {
                        success.incrementAndGet();
                    } else if (resp.statusCode() == 429) {
                        int count = rateLimited.incrementAndGet();
                        if (count == 1) {
                            logStep("    First 429 at request #" + (requestNum + 1));
                        }
                    } else {
                        errors.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        logStep("  ✓ Successful (201): " + success.get());
        logStep("  ✓ Rate limited (429): " + rateLimited.get());
        logStep("  ✓ Validation errors: " + errors.get());

        assertThat(rateLimited.get())
                .as("Should rate limit beyond burst capacity (10)")
                .isGreaterThan(5);

        logStep("✅ Registration rate limiting enforced");
    }

    @Test(priority = 2,enabled = false)
    @Story("Rate Limit - IP Based - Login")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Login endpoint: 5/sec, burst: 10 (IP-based)")
    public void test02_LoginRateLimiting() throws InterruptedException {
        logStep("TEST 2: /api/users/login - 5/sec, burst: 10");

        // Create user
        Map<String, String> userData = Map.of(
                "username", faker.name().username() + faker.number().digits(4),
                "email", faker.internet().emailAddress(),
                "password", "Test@123456",
                "firstName", faker.name().firstName(),
                "lastName", faker.name().lastName(),
                "phone", "+91" + faker.number().digits(10)
        );

        given().baseUri(GATEWAY_URL)
                .contentType("application/json")
                .body(userData)
                .when()
                .post("/api/users/register");

        final Map<String, String> loginData = Map.of(
                "username", userData.get("username"),
                "password", userData.get("password")
        );

        int totalRequests = 20;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(totalRequests);

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger rateLimited = new AtomicInteger(0);

        logStep("  Firing " + totalRequests + " login requests simultaneously...");

        for (int i = 0; i < totalRequests; i++) {
            final int requestNum = i;

            executor.submit(() -> {
                try {
                    Response resp = given().log().all()
                            .baseUri(GATEWAY_URL)
                            .contentType("application/json")
                            .body(loginData)
                            .when()
                            .post("/api/users/login")
                            .then().log().all()
                            .extract()
                            .response();

                    if (resp.statusCode() == 200) {
                        success.incrementAndGet();
                    } else if (resp.statusCode() == 429) {
                        int count = rateLimited.incrementAndGet();
                        if (count == 1) {
                            logStep("    First 429 at request #" + (requestNum + 1));
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        logStep("  ✓ Successful (200): " + success.get());
        logStep("  ✓ Rate limited (429): " + rateLimited.get());

        assertThat(rateLimited.get())
                .as("Should rate limit logins beyond burst (10)")
                .isGreaterThan(5);

        logStep("✅ Login rate limiting enforced");
    }

    @Test(priority = 3,enabled = false)
    @Story("Rate Limit - IP Based - Products")
    @Severity(SeverityLevel.NORMAL)
    @Description("GET /api/products: 50/sec, burst: 100 (IP-based)")
    public void test03_ProductListRateLimiting() throws InterruptedException {
        logStep("TEST 3: GET /api/products - 50/sec, burst: 100");

        int totalRequests = 150;
        ExecutorService executor = Executors.newFixedThreadPool(250);
        CountDownLatch latch = new CountDownLatch(totalRequests);

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger rateLimited = new AtomicInteger(0);

        logStep("  Firing " + totalRequests + " requests...");

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    Response resp = given().log().all()
                            .baseUri(GATEWAY_URL)
                            .when()
                            .get("/api/products")
                            .then().log().all()
                            .extract()
                            .response();

                    if (resp.statusCode() == 200 || resp.statusCode() == 503) {
                        success.incrementAndGet();
                    } else if (resp.statusCode() == 429) {
                        rateLimited.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        logStep("  ✓ Successful: " + success.get());
        logStep("  ✓ Rate limited (429): " + rateLimited.get());

        assertThat(rateLimited.get())
                .as("Should rate limit beyond burst (100)")
                .isGreaterThan(20);

        logStep("✅ Product list rate limiting enforced");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // USER-BASED RATE LIMITING
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 3)
    @Story("Rate Limit - User Based - Orders")
    @Severity(SeverityLevel.CRITICAL)
    @Description("GET /api/orders: 5/sec, burst: 10 (per user) - TEST CONFIG")
    public void test10_OrderListRateLimiting() throws InterruptedException {
        logStep("TEST 10: GET /api/orders - 5/sec per user, burst: 10");

        // Generate fresh token
        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();
        final String token = auth.getAccessToken();

        int totalRequests = 20;  // Send 20, burst is 10
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(totalRequests);

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger rateLimited = new AtomicInteger(0);
        AtomicInteger unauthorized = new AtomicInteger(0);

        logStep("  Firing " + totalRequests + " requests simultaneously...");

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    Response resp = given()
                            .baseUri(GATEWAY_URL)
                            .header("Authorization", "Bearer " + token)
                            .when()
                            .get("/api/orders")
                            .then()
                            .extract()
                            .response();

                    int status = resp.statusCode();

                    if (status == 200 || status == 503) {
                        success.incrementAndGet();
                    } else if (status == 429) {
                        rateLimited.incrementAndGet();
                    } else if (status == 401) {
                        unauthorized.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        logStep("  ✓ Successful: " + success.get());
        logStep("  ✓ Rate limited (429): " + rateLimited.get());
        logStep("  ✓ Unauthorized (401): " + unauthorized.get());

        // With burst=10, sending 20 should give ~10 rate limited
        assertThat(rateLimited.get())
                .as("Should rate limit beyond burst capacity (10)")
                .isGreaterThan(5);

        logStep("✅ Order list rate limiting enforced");
    }

    @Test(priority = 4,enabled = false)
    @Story("Rate Limit - User Isolation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Different users have independent rate limits")
    public void test20_UsersHaveIndependentRateLimits() throws InterruptedException {
        logStep("TEST 20: Verifying users have independent rate limits");

        // Exhaust User 1's quota
        logStep("  Step 1: Exhausting User 1's rate limit...");

        int exhaustRequests = 150;
        ExecutorService executor1 = Executors.newFixedThreadPool(50);
        CountDownLatch latch1 = new CountDownLatch(exhaustRequests);
        AtomicInteger user1Blocked = new AtomicInteger(0);

        for (int i = 0; i < exhaustRequests; i++) {
            executor1.submit(() -> {
                try {
                    int status = given()
                            .baseUri(GATEWAY_URL)
                            .header("Authorization", "Bearer " + validToken1)
                            .when()
                            .get("/api/orders")
                            .then()
                            .extract()
                            .statusCode();

                    if (status == 429) {
                        user1Blocked.incrementAndGet();
                    }
                } finally {
                    latch1.countDown();
                }
            });
        }

        latch1.await();
        executor1.shutdown();

        logStep("    User 1 blocked: " + user1Blocked.get());
        assertThat(user1Blocked.get()).isGreaterThan(20);

        // User 2 should NOT be affected
        logStep("  Step 2: Testing User 2 (should have full quota)...");

        int testRequests = 20;
        AtomicInteger user2Success = new AtomicInteger(0);

        for (int i = 0; i < testRequests; i++) {
            int status = given()
                    .baseUri(GATEWAY_URL)
                    .header("Authorization", "Bearer " + validToken2)
                    .when()
                    .get("/api/orders")
                    .then()
                    .extract()
                    .statusCode();

            if (status == 200 || status == 503) {
                user2Success.incrementAndGet();
            }
        }

        logStep("    User 2 successful: " + user2Success.get());

        assertThat(user2Success.get())
                .as("User 2 should NOT be affected by User 1's limit")
                .isGreaterThan(15);

        logStep("✅ Users have independent rate limits");
    }

    @Test(priority = 5)
    @Story("Rate Limit - Recovery")
    @Severity(SeverityLevel.NORMAL)
    @Description("Rate limit quota replenishes over time")
    public void test30_RateLimitRecovery() throws InterruptedException {
        logStep("TEST 30: Verifying rate limit recovery");

        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();
        String token = auth.getAccessToken();

        // Exhaust rate limit
        logStep("  Phase 1: Exhausting rate limit...");

        int exhaustRequests = 150;
        ExecutorService executor1 = Executors.newFixedThreadPool(50);
        CountDownLatch latch1 = new CountDownLatch(exhaustRequests);
        AtomicInteger blocked = new AtomicInteger(0);

        for (int i = 0; i < exhaustRequests; i++) {
            executor1.submit(() -> {
                try {
                    int status = given()
                            .baseUri(GATEWAY_URL)
                            .header("Authorization", "Bearer " + token)
                            .when()
                            .get("/api/orders")
                            .then()
                            .extract()
                            .statusCode();

                    if (status == 429) {
                        blocked.incrementAndGet();
                    }
                } finally {
                    latch1.countDown();
                }
            });
        }

        latch1.await();
        executor1.shutdown();

        logStep("    Blocked: " + blocked.get());
        assertThat(blocked.get()).isGreaterThan(20);

        // Wait for recovery
        logStep("  Phase 2: Waiting 2 seconds for recovery...");
        Thread.sleep(2000);

        // Should work again
        logStep("  Phase 3: Testing recovery...");
        int success = 0;

        for (int i = 0; i < 20; i++) {
            int status = given()
                    .baseUri(GATEWAY_URL)
                    .header("Authorization", "Bearer " + token)
                    .when()
                    .get("/api/orders")
                    .then()
                    .extract()
                    .statusCode();

            if (status == 200 || status == 503) {
                success++;
            }
        }

        logStep("    Successful after recovery: " + success);

        assertThat(success)
                .as("Rate limit should recover (replenishRate=50/sec)")
                .isGreaterThan(15);

        logStep("✅ Rate limit recovery works");
    }
}