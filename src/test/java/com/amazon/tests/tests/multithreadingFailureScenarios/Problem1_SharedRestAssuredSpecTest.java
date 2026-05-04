package com.amazon.tests.tests.multithreadingFailureScenarios;


import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.TestDataFactory;
import io.qameta.allure.*;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.testng.annotations.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * ========================================================================
 * PROBLEM #1: Shared RestAssured - SIMPLIFIED (NO HANGING!)
 * ========================================================================
 *
 * This version is simplified - no CountDownLatch, just pure parallel execution
 * Uses TestNG's built-in parallelization - much simpler!
 */
@Epic("Amazon Microservices - Multithreading")
@Feature("Order Management - Thread Safety")
public class Problem1_SharedRestAssuredSpecTest extends BaseTest {

    /**
     * ❌ WRONG IMPLEMENTATION - Shared RequestSpec
     */
    public static class WrongImplementation_SharedSpec extends BaseTest {

        // ❌ SHARED static spec - ALL threads use this!
        private static RequestSpecification sharedOrderSpec;
        private static String sharedCustomerToken;
        private static String sharedCustomerId;
        private static TestModels.ProductResponse testProduct;

        // Metrics
        private static AtomicInteger totalTests = new AtomicInteger(0);
        private static AtomicInteger successCount = new AtomicInteger(0);
        private static AtomicInteger failureCount = new AtomicInteger(0);
        private static AtomicInteger wrongCustomerCount = new AtomicInteger(0);
        private static ConcurrentHashMap<String, String> createdOrders = new ConcurrentHashMap<>();
        private static ConcurrentHashMap<String, String> errors = new ConcurrentHashMap<>();

        @BeforeClass
        public void setup() {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("❌ WRONG IMPLEMENTATION - SHARED REQUEST SPECIFICATION");
            System.out.println("=".repeat(80));
            System.out.println("Running 50 parallel tests with SHARED RequestSpec");
            System.out.println("=".repeat(80) + "\n");

            // Create customer
            TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
            sharedCustomerToken = customerAuth.getAccessToken();
            sharedCustomerId = customerAuth.getUser().getId();

            // ❌ Create SHARED RequestSpec
            sharedOrderSpec = RestAssuredConfig.getOrderServiceSpec(sharedCustomerToken);

            logStep("❌ SHARED OrderSpec created - will cause race conditions!");

            // Create product
            TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
            Response productResponse = given()
                    .spec(RestAssuredConfig.getProductServiceSpec())
                    .header("X-User-Id", sellerAuth.getUser().getId())
                    .body(TestDataFactory.createProductWithPrice(29.99))
                    .post("/api/v1/products")
                    .then().statusCode(201).extract().response();

            testProduct = productResponse.as(TestModels.ProductResponse.class);
            logStep("Test product created: " + testProduct.getId());

            System.out.println("\n🚀 Starting parallel execution...\n");
        }

        /**
         * ❌ WRONG: 50 threads all using shared RequestSpec
         * TestNG handles parallelization automatically!
         */
        @Test(threadPoolSize = 50, invocationCount = 50,
                description = "❌ WRONG: Create order with SHARED RequestSpec")
        @Story("Shared RequestSpec Problem")
        @Severity(SeverityLevel.CRITICAL)
        public void testCreateOrder_SharedSpec() {
            long threadId = Thread.currentThread().getId();
            int testNum = totalTests.incrementAndGet();

            try {
                System.out.println("[Thread-" + threadId + " | Test #" + testNum + "] Creating order...");

                // Create order request - unique for this thread
                TestModels.CreateOrderRequest orderRequest = TestDataFactory.createOrderRequest(
                        testProduct.getId(),
                        "Product_Thread_" + threadId + "_" + System.nanoTime(),
                        testProduct.getPrice()
                );

                // ❌ PROBLEM: All 50 threads use SAME sharedOrderSpec!
                // Race condition happens here!
                Response response = given()
                        .spec(sharedOrderSpec)  // ❌ SHARED - RACE CONDITION!
                        .header("X-User-Id", sharedCustomerId)
                        .body(orderRequest)  // ❌ Threads overwrite each other!
                        .when()
                        .post("/api/v1/orders")
                        .then()
                        .extract().response();

                int statusCode = response.statusCode();

                if (statusCode == 201) {
                    TestModels.OrderResponse order = response.as(TestModels.OrderResponse.class);
                    createdOrders.put(order.getId(), "Thread-" + threadId);

                    // Check if order was created for wrong customer
                    if (!order.getUserId().equals(sharedCustomerId)) {
                        wrongCustomerCount.incrementAndGet();
                        errors.put("Thread-" + threadId, "Wrong customer: " + order.getUserId());
                        System.err.println("[Thread-" + threadId + "] ⚠️ WRONG CUSTOMER!");
                    }

                    successCount.incrementAndGet();
                    System.out.println("[Thread-" + threadId + "] ✅ SUCCESS - Order: " + order.getId());

                } else if (statusCode == 400) {
                    failureCount.incrementAndGet();
                    errors.put("Thread-" + threadId, "400 Bad Request");
                    System.err.println("[Thread-" + threadId + "] ❌ 400 Bad Request (Race condition!)");

                } else if (statusCode == 401) {
                    failureCount.incrementAndGet();
                    errors.put("Thread-" + threadId, "401 Unauthorized");
                    System.err.println("[Thread-" + threadId + "] ❌ 401 Unauthorized (Token overwritten!)");

                } else {
                    failureCount.incrementAndGet();
                    errors.put("Thread-" + threadId, "Status: " + statusCode);
                    System.err.println("[Thread-" + threadId + "] ❌ Failed with status: " + statusCode);
                }

            } catch (AssertionError e) {
                failureCount.incrementAndGet();
                errors.put("Thread-" + threadId, "Assertion: " + e.getMessage());
                System.err.println("[Thread-" + threadId + "] ❌ ASSERTION FAILED: " + e.getMessage());
            } catch (Exception e) {
                failureCount.incrementAndGet();
                errors.put("Thread-" + threadId, "Exception: " + e.getMessage());
                System.err.println("[Thread-" + threadId + "] ❌ EXCEPTION: " + e.getMessage());
            }
        }

        @AfterClass
        public void printResults() {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("❌ WRONG IMPLEMENTATION - FINAL RESULTS");
            System.out.println("=".repeat(80));
            System.out.println("Total Tests Run       : " + totalTests.get());
            System.out.println("Expected Success      : 50");
            System.out.println("Actual Success        : " + successCount.get() +
                    (successCount.get() < 50 ? " ❌" : " ✅"));
            System.out.println("Actual Failures       : " + failureCount.get() +
                    (failureCount.get() > 0 ? " ⚠️" : ""));
            System.out.println("Wrong Customer Orders : " + wrongCustomerCount.get() +
                    (wrongCustomerCount.get() > 0 ? " ⚠️" : ""));
            System.out.println("Orders Created        : " + createdOrders.size());
            System.out.println("=".repeat(80));

            if (failureCount.get() > 0 || wrongCustomerCount.get() > 0) {
                System.out.println("\n🔥 RACE CONDITION DETECTED!");
                System.out.println("\nError Summary (first 10):");
                errors.entrySet().stream().limit(10).forEach(entry ->
                        System.out.println("  " + entry.getKey() + ": " + entry.getValue())
                );
                System.out.println("\n⚠️ This proves shared RequestSpec causes race conditions!");
                System.out.println("With 50 parallel threads, conflicts are likely.");
            } else {
                System.out.println("\n⚠️ No race condition detected this run.");
                System.out.println("Race conditions are non-deterministic.");
                System.out.println("Try running multiple times or increase thread count to 100.");
            }

            System.out.println("=".repeat(80) + "\n");
        }
    }

    /**
     * ✅ CORRECT IMPLEMENTATION - ThreadLocal RequestSpec
     */
    public static class CorrectImplementation_ThreadLocal extends BaseTest {

        // ✅ ThreadLocal - each thread gets its own!
        private static ThreadLocal<RequestSpecification> orderSpec = new ThreadLocal<>();
        private static ThreadLocal<String> customerToken = new ThreadLocal<>();
        private static ThreadLocal<String> customerId = new ThreadLocal<>();

        // Shared product (read-only, safe)
        private static TestModels.ProductResponse testProduct;

        // Metrics
        private static AtomicInteger totalTests = new AtomicInteger(0);
        private static AtomicInteger successCount = new AtomicInteger(0);
        private static AtomicInteger failureCount = new AtomicInteger(0);
        private static ConcurrentHashMap<String, String> createdOrders = new ConcurrentHashMap<>();

        @BeforeClass
        public void setupProduct() {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("✅ CORRECT IMPLEMENTATION - THREADLOCAL REQUEST SPEC");
            System.out.println("=".repeat(80));
            System.out.println("Running 50 parallel tests with THREADLOCAL RequestSpec");
            System.out.println("=".repeat(80) + "\n");

            // Create product (shared, read-only, safe)
            TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
            Response productResponse = given()
                    .spec(RestAssuredConfig.getProductServiceSpec())
                    .header("X-User-Id", sellerAuth.getUser().getId())
                    .body(TestDataFactory.createProductWithPrice(29.99))
                    .post("/api/v1/products")
                    .then().statusCode(201).extract().response();

            testProduct = productResponse.as(TestModels.ProductResponse.class);
            logStep("✅ Test product created: " + testProduct.getId());

            System.out.println("\n🚀 Starting parallel execution...\n");
        }

        @BeforeMethod
        public void setupThreadResources() {
            // ✅ Create unique customer per thread
            TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();

            // ✅ Store in ThreadLocal
            customerToken.set(auth.getAccessToken());
            customerId.set(auth.getUser().getId());

            // ✅ Create ThreadLocal RequestSpec
            orderSpec.set(RestAssuredConfig.getOrderServiceSpec(customerToken.get()));
        }

        /**
         * ✅ CORRECT: 50 threads, each with its own RequestSpec
         */
        @Test(threadPoolSize = 50, invocationCount = 50,
                description = "✅ CORRECT: Create order with ThreadLocal RequestSpec")
        @Story("ThreadLocal RequestSpec Solution")
        @Severity(SeverityLevel.BLOCKER)
        public void testCreateOrder_ThreadLocal() {
            long threadId = Thread.currentThread().getId();
            int testNum = totalTests.incrementAndGet();

            try {
                System.out.println("[Thread-" + threadId + " | Test #" + testNum + "] Creating order...");

                // Create order request
                TestModels.CreateOrderRequest orderRequest = TestDataFactory.createOrderRequest(
                        testProduct.getId(),
                        "Product_Thread_" + threadId,
                        testProduct.getPrice()
                );

                // ✅ SOLUTION: Use ThreadLocal spec - each thread has its own!
                Response response = given()
                        .spec(orderSpec.get())  // ✅ ThreadLocal - NO race condition!
                        .header("X-User-Id", customerId.get())
                        .body(orderRequest)
                        .when()
                        .post("/api/v1/orders")
                        .then()
                        .statusCode(201)  // Should ALWAYS succeed
                        .body("userId", equalTo(customerId.get()))
                        .body("status", equalTo("PENDING"))
                        .extract().response();

                TestModels.OrderResponse order = response.as(TestModels.OrderResponse.class);
                createdOrders.put(order.getId(), "Thread-" + threadId);
                successCount.incrementAndGet();

                System.out.println("[Thread-" + threadId + "] ✅ SUCCESS - Order: " + order.getId());

                // Verify
                assertThat(order.getUserId()).isEqualTo(customerId.get());
                assertThat(order.getStatus()).isEqualTo("PENDING");

            } catch (Exception e) {
                failureCount.incrementAndGet();
                System.err.println("[Thread-" + threadId + "] ❌ EXCEPTION: " + e.getMessage());
                throw e;
            }
        }

        @AfterMethod
        public void cleanupThreadResources() {
            // ✅ CRITICAL: Remove ThreadLocal to prevent memory leak
            orderSpec.remove();
            customerToken.remove();
            customerId.remove();
        }

        @AfterClass
        public void printResults() {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("✅ CORRECT IMPLEMENTATION - FINAL RESULTS");
            System.out.println("=".repeat(80));
            System.out.println("Total Tests Run    : " + totalTests.get());
            System.out.println("Expected Success   : 50");
            System.out.println("Actual Success     : " + successCount.get() + " ✅");
            System.out.println("Actual Failures    : " + failureCount.get());
            System.out.println("Orders Created     : " + createdOrders.size());
            System.out.println("=".repeat(80));

            // Verify all succeeded
            assertThat(successCount.get()).as("All tests should pass").isEqualTo(50);
            assertThat(failureCount.get()).as("No failures expected").isEqualTo(0);

            System.out.println("\n✅ ALL 50 TESTS PASSED - NO RACE CONDITIONS!");
            System.out.println("ThreadLocal approach ensures complete thread safety.");
            System.out.println("=".repeat(80) + "\n");
        }
    }
}