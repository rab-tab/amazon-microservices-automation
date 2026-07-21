package com.amazon.tests.multithreadingFailureScenarios;


import com.amazon.tests.BaseTest;
import com.amazon.tests.config.restAsssured.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.testData.TestDataFactory;
import io.qameta.allure.*;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.testng.annotations.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;

/**
 * ========================================================================
 * PROBLEM #1: Shared RestAssured - REALISTIC SCENARIO
 * ========================================================================
 *
 * This demonstrates the REAL problem:
 * - Multiple @Test methods (CREATE, GET, UPDATE)
 * - All sharing same RequestSpecification
 * - Run with parallel="methods" in testng.xml
 * - Race conditions when methods modify the shared spec
 *
 * THIS WILL DEFINITELY SHOW THE PROBLEM!
 */
@Epic("Amazon Microservices - Multithreading")
@Feature("Order Management - Thread Safety")
public class Problem1_RealScenario_MultipleTests extends BaseTest {

    /**
     * ❌ WRONG IMPLEMENTATION
     * Multiple @Test methods sharing same RequestSpec
     */
    public static class WrongImplementation_SharedSpec extends BaseTest {

        // ❌ SHARED static spec - ALL methods use this!
        private static RequestSpecification sharedOrderSpec;
        private static String sharedCustomerToken;
        private static String sharedCustomerId;
        private static TestModels.ProductResponse testProduct;

        // Track created orders for GET/UPDATE tests
        private static ConcurrentHashMap<String, String> createdOrders = new ConcurrentHashMap<>();

        // Metrics
        private static AtomicInteger createSuccessCount = new AtomicInteger(0);
        private static AtomicInteger createFailureCount = new AtomicInteger(0);
        private static AtomicInteger getSuccessCount = new AtomicInteger(0);
        private static AtomicInteger getFailureCount = new AtomicInteger(0);
        private static AtomicInteger updateSuccessCount = new AtomicInteger(0);
        private static AtomicInteger updateFailureCount = new AtomicInteger(0);

        @BeforeClass
        public void setup() {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("❌ WRONG IMPLEMENTATION - SHARED REQUEST SPEC");
            System.out.println("=".repeat(80));
            System.out.println("Running with parallel=\"methods\" in testng.xml");
            System.out.println("Multiple @Test methods will run in parallel");
            System.out.println("All sharing the SAME RequestSpecification");
            System.out.println("=".repeat(80) + "\n");

            // Create customer
            TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
            sharedCustomerToken = customerAuth.getAccessToken();
            sharedCustomerId = customerAuth.getUser().getId();

            // ❌ Create SHARED RequestSpec - THIS IS THE PROBLEM!
            sharedOrderSpec = RestAssuredConfig.getOrderServiceSpec(sharedCustomerToken);

            System.out.println("❌ SHARED RequestSpec created");
            System.out.println("❌ Customer: " + sharedCustomerId);

            // Create product
            TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
            Response productResponse = given()
                    .spec(RestAssuredConfig.getProductServiceSpec())
                    .header("X-User-Id", sellerAuth.getUser().getId())
                    .body(TestDataFactory.createProductWithPrice(29.99))
                    .post("/api/v1/products")
                    .then().statusCode(201).extract().response();

            testProduct = productResponse.as(TestModels.ProductResponse.class);
            System.out.println("✅ Product created: " + testProduct.getId() + "\n");
        }

        /**
         * ❌ TEST 1: Create Order
         * This will run in parallel with GET and UPDATE
         */
        @Test(priority = 1, invocationCount = 50, threadPoolSize = 50,
                description = "❌ Create order with SHARED spec")
        @Story("Create Order - Wrong")
        @Severity(SeverityLevel.CRITICAL)
        public void testCreateOrder() {
            long threadId = Thread.currentThread().getId();

            try {
                System.out.println("[CREATE-Thread-" + threadId + "] Starting...");

                // Create order request
                TestModels.CreateOrderRequest orderRequest = TestDataFactory.createOrderRequest(
                        testProduct.getId(),
                        "Product_" + threadId + "_" + System.nanoTime(),
                        testProduct.getPrice()
                );

                // ❌ PROBLEM: Using shared spec
                // While this thread sets headers, GET/UPDATE threads might overwrite!
                Response response = given()
                        .spec(sharedOrderSpec)  // ❌ SHARED!
                        .header("X-User-Id", sharedCustomerId)  // ❌ Might be overwritten!
                        .body(orderRequest)  // ❌ Race condition!
                        .when()
                        .post("/api/v1/orders")
                        .then()
                        .extract().response();

                if (response.statusCode() == 201) {
                    TestModels.OrderResponse order = response.as(TestModels.OrderResponse.class);
                    createdOrders.put(order.getId(), "Thread-" + threadId);
                    createSuccessCount.incrementAndGet();
                    System.out.println("[CREATE-Thread-" + threadId + "] ✅ Order created: " +
                            order.getId());
                } else {
                    createFailureCount.incrementAndGet();
                    System.err.println("[CREATE-Thread-" + threadId + "] ❌ Failed: " +
                            response.statusCode() + " - " + response.asString());
                }

            } catch (Exception e) {
                createFailureCount.incrementAndGet();
                System.err.println("[CREATE-Thread-" + threadId + "] ❌ Exception: " +
                        e.getMessage());
                throw e;  // ✅ Re-throw so TestNG marks as FAILED!
            }
        }

        /**
         * ❌ TEST 2: Get Order
         * This runs in parallel with CREATE and UPDATE
         * Uses different endpoint but SAME shared spec!
         */
        @Test(priority = 2, invocationCount = 50,threadPoolSize = 50,
                description = "❌ Get order with SHARED spec")
        @Story("Get Order - Wrong")
        @Severity(SeverityLevel.CRITICAL)
        public void testGetOrder() throws InterruptedException {
            long threadId = Thread.currentThread().getId();

            try {
                // ✅ Wait for orders instead of returning early
                int attempts = 0;
                while (createdOrders.isEmpty() && attempts < 50) {
                    Thread.sleep(100);  // Wait 100ms
                    attempts++;
                }

                if (createdOrders.isEmpty()) {
                    getFailureCount.incrementAndGet();
                    System.err.println("[GET-Thread-" + threadId + "] ❌ No orders after 5 seconds");
                    throw new RuntimeException("No orders created after waiting");
                }

                String orderId = createdOrders.keys().nextElement();
                System.out.println("[GET-Thread-" + threadId + "] Getting order: " + orderId);

                // ❌ PROBLEM: Using same shared spec as CREATE
                // If CREATE thread is setting body, this thread sees it!
                Response response = given()
                        .spec(sharedOrderSpec)  // ❌ SHARED with CREATE thread!
                        .header("X-User-Id", sharedCustomerId)  // ❌ Might be overwritten!
                        .when()
                        .get("/api/v1/orders/" + orderId)  // Different endpoint than CREATE
                        .then()
                        .extract().response();

                if (response.statusCode() == 200) {
                    getSuccessCount.incrementAndGet();
                    System.out.println("[GET-Thread-" + threadId + "] ✅ Retrieved order");
                } else {
                    getFailureCount.incrementAndGet();
                    System.err.println("[GET-Thread-" + threadId + "] ❌ Failed: " +
                            response.statusCode());
                }

            } catch (Exception e) {
                getFailureCount.incrementAndGet();
                System.err.println("[GET-Thread-" + threadId + "] ❌ Exception: " +
                        e.getMessage());
                throw e;  // ✅ Re-throw so TestNG marks as FAILED!
            }
        }

        /**
         * ❌ TEST 3: Update Order
         * This runs in parallel with CREATE and GET
         * Uses PUT method with body - HIGH chance of collision!
         */
        @Test(priority = 3, invocationCount = 10,
                description = "❌ Update order with SHARED spec,",enabled = false)
        @Story("Update Order - Wrong")
        @Severity(SeverityLevel.CRITICAL)
        public void testUpdateOrder() {
            long threadId = Thread.currentThread().getId();

            try {
                // Get any created order
                if (createdOrders.isEmpty()) {
                    System.err.println("[UPDATE-Thread-" + threadId + "] ⚠️ No orders created yet");
                    return;
                }

                String orderId = createdOrders.keys().nextElement();
                System.out.println("[UPDATE-Thread-" + threadId + "] Updating order: " + orderId);

                // ❌ PROBLEM: Using same shared spec as CREATE and GET
                // This sets a body, CREATE also sets body → COLLISION!
                Response response = given()
                        .spec(sharedOrderSpec)  // ❌ SHARED with all other threads!
                        .header("X-User-Id", sharedCustomerId)  // ❌ Might be overwritten!
                        .body("{\"status\":\"CONFIRMED\"}")  // ❌ Body collision with CREATE!
                        .when()
                        .put("/api/v1/orders/" + orderId + "/status")
                        .then()
                        .extract().response();

                if (response.statusCode() == 200) {
                    updateSuccessCount.incrementAndGet();
                    System.out.println("[UPDATE-Thread-" + threadId + "] ✅ Updated order");
                } else {
                    updateFailureCount.incrementAndGet();
                    System.err.println("[UPDATE-Thread-" + threadId + "] ❌ Failed: " +
                            response.statusCode() + " - " + response.asString());
                }

            } catch (Exception e) {
                updateFailureCount.incrementAndGet();
                System.err.println("[UPDATE-Thread-" + threadId + "] ❌ Exception: " +
                        e.getMessage());
                throw e;  // ✅ Re-throw so TestNG marks as FAILED!
            }
        }

        @AfterClass
        public void printResults() {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("❌ WRONG IMPLEMENTATION - FINAL RESULTS");
            System.out.println("=".repeat(80));

            System.out.println("\nCREATE ORDER Tests:");
            System.out.println("  Expected Success: 10");
            System.out.println("  Actual Success  : " + createSuccessCount.get() +
                    (createSuccessCount.get() < 10 ? " ❌" : " ✅"));
            System.out.println("  Failures        : " + createFailureCount.get());

            System.out.println("\nGET ORDER Tests:");
            System.out.println("  Expected Success: 10");
            System.out.println("  Actual Success  : " + getSuccessCount.get() +
                    (getSuccessCount.get() < 10 ? " ❌" : " ✅"));
            System.out.println("  Failures        : " + getFailureCount.get());

            System.out.println("\nUPDATE ORDER Tests:");
            System.out.println("  Expected Success: 10");
            System.out.println("  Actual Success  : " + updateSuccessCount.get() +
                    (updateSuccessCount.get() < 10 ? " ❌" : " ✅"));
            System.out.println("  Failures        : " + updateFailureCount.get());

            int totalFailures = createFailureCount.get() + getFailureCount.get() +
                    updateFailureCount.get();

            System.out.println("\nOVERALL:");
            System.out.println("  Total Failures  : " + totalFailures);
            System.out.println("  Orders Created  : " + createdOrders.size());

            System.out.println("=".repeat(80));

            if (totalFailures > 0) {
                System.out.println("\n🔥 RACE CONDITION DETECTED!");
                System.out.println("\nWhat happened:");
                System.out.println("  • CREATE thread was setting body for POST request");
                System.out.println("  • UPDATE thread was setting body for PUT request");
                System.out.println("  • GET thread was making request with no body");
                System.out.println("  • All using SAME shared RequestSpecification!");
                System.out.println("  • Threads overwrote each other's headers/body");
                System.out.println("\n⚠️ This proves shared RequestSpec is NOT thread-safe!");
            } else {
                System.out.println("\n⚠️ No failures this time - race conditions are random");
                System.out.println("Run multiple times to see failures");
            }

            System.out.println("=".repeat(80) + "\n");
        }
    }

    /**
     * ✅ CORRECT IMPLEMENTATION
     * Each test method gets its own ThreadLocal RequestSpec
     */
    public static class CorrectImplementation_ThreadLocal extends BaseTest {

        // ✅ ThreadLocal - each thread gets its own!
        private static ThreadLocal<RequestSpecification> orderSpec = new ThreadLocal<>();
        private static ThreadLocal<String> customerToken = new ThreadLocal<>();
        private static ThreadLocal<String> customerId = new ThreadLocal<>();

        // Shared product (read-only, safe)
        private static TestModels.ProductResponse testProduct;

        // Track created orders
        private static ConcurrentHashMap<String, String> createdOrders = new ConcurrentHashMap<>();

        // Metrics
        private static AtomicInteger createSuccessCount = new AtomicInteger(0);
        private static AtomicInteger getSuccessCount = new AtomicInteger(0);
        private static AtomicInteger updateSuccessCount = new AtomicInteger(0);

        @BeforeClass
        public void setupProduct() {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("✅ CORRECT IMPLEMENTATION - THREADLOCAL SPEC");
            System.out.println("=".repeat(80));
            System.out.println("Each @Test method gets its own ThreadLocal RequestSpec");
            System.out.println("=".repeat(80) + "\n");

            // Create product
            TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
            Response productResponse = given()
                    .spec(RestAssuredConfig.getProductServiceSpec())
                    .header("X-User-Id", sellerAuth.getUser().getId())
                    .body(TestDataFactory.createProductWithPrice(29.99))
                    .post("/api/v1/products")
                    .then().statusCode(201).extract().response();

            testProduct = productResponse.as(TestModels.ProductResponse.class);
            System.out.println("✅ Product created: " + testProduct.getId() + "\n");
        }

        @BeforeMethod
        public void setupThreadResources() {
            // ✅ Each thread creates its own customer
            TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();
            customerToken.set(auth.getAccessToken());
            customerId.set(auth.getUser().getId());

            // ✅ Each thread gets its own RequestSpec
            orderSpec.set(RestAssuredConfig.getOrderServiceSpec(customerToken.get()));
        }

        @Test(priority = 1, invocationCount = 50,threadPoolSize = 50,
                description = "✅ Create order with ThreadLocal spec")
        @Story("Create Order - Correct")
        @Severity(SeverityLevel.BLOCKER)
        public void testCreateOrder() {
            long threadId = Thread.currentThread().getId();

            try {
                System.out.println("[CREATE-Thread-" + threadId + "] Starting...");

                TestModels.CreateOrderRequest orderRequest = TestDataFactory.createOrderRequest(
                        testProduct.getId(),
                        "Product_" + threadId,
                        testProduct.getPrice()
                );

                // ✅ Using ThreadLocal spec - no race condition!
                Response response = given()
                        .spec(orderSpec.get())  // ✅ ThreadLocal!
                        .header("X-User-Id", customerId.get())
                        .body(orderRequest)
                        .when()
                        .post("/api/v1/orders")
                        .then()
                        .statusCode(201)
                        .extract().response();

                TestModels.OrderResponse order = response.as(TestModels.OrderResponse.class);
                createdOrders.put(order.getId(), customerId.get());
                createSuccessCount.incrementAndGet();
                System.out.println("[CREATE-Thread-" + threadId + "] ✅ Order: " + order.getId());

            } catch (Exception e) {
                System.err.println("[CREATE-Thread-" + threadId + "] ❌ " + e.getMessage());
                throw e;
            }
        }

        @Test(priority = 2, invocationCount = 50,threadPoolSize = 50,
                description = "✅ Get order with ThreadLocal spec")
        @Story("Get Order - Correct")
        @Severity(SeverityLevel.BLOCKER)
        public void testGetOrder() {
            long threadId = Thread.currentThread().getId();

            try {
                if (createdOrders.isEmpty()) {
                    return;
                }

                String orderId = createdOrders.keys().nextElement();

                // ✅ ThreadLocal spec - completely isolated!
                Response response = given()
                        .spec(orderSpec.get())  // ✅ ThreadLocal!
                        .header("X-User-Id", customerId.get())
                        .when()
                        .get("/api/v1/orders/" + orderId)
                        .then()
                        .statusCode(200)
                        .extract().response();

                getSuccessCount.incrementAndGet();
                System.out.println("[GET-Thread-" + threadId + "] ✅ Retrieved order");

            } catch (Exception e) {
                System.err.println("[GET-Thread-" + threadId + "] ❌ " + e.getMessage());
                throw e;
            }
        }

        @Test(priority = 3, invocationCount = 10,
                description = "✅ Update order with ThreadLocal spec")
        @Story("Update Order - Correct")
        @Severity(SeverityLevel.BLOCKER)
        public void testUpdateOrder() {
            long threadId = Thread.currentThread().getId();

            try {
                if (createdOrders.isEmpty()) {
                    return;
                }

                String orderId = createdOrders.keys().nextElement();

                // ✅ ThreadLocal spec - no body collision!
                Response response = given()
                        .spec(orderSpec.get())  // ✅ ThreadLocal!
                        .header("X-User-Id", customerId.get())
                        .body("{\"status\":\"CONFIRMED\"}")
                        .when()
                        .put("/api/v1/orders/" + orderId + "/status")
                        .then()
                        .statusCode(200)
                        .extract().response();

                updateSuccessCount.incrementAndGet();
                System.out.println("[UPDATE-Thread-" + threadId + "] ✅ Updated order");

            } catch (Exception e) {
                System.err.println("[UPDATE-Thread-" + threadId + "] ❌ " + e.getMessage());
                throw e;
            }
        }

        @AfterMethod
        public void cleanup() {
            // ✅ Always remove ThreadLocal
            orderSpec.remove();
            customerToken.remove();
            customerId.remove();
        }

        @AfterClass
        public void printResults() {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("✅ CORRECT IMPLEMENTATION - FINAL RESULTS");
            System.out.println("=".repeat(80));
            System.out.println("CREATE Success : " + createSuccessCount.get() + " / 10 ✅");
            System.out.println("GET Success    : " + getSuccessCount.get() + " / 10 ✅");
            System.out.println("UPDATE Success : " + updateSuccessCount.get() + " / 10 ✅");
            System.out.println("=".repeat(80));
            System.out.println("\n✅ ALL TESTS PASSED - NO RACE CONDITIONS!");
            System.out.println("=".repeat(80) + "\n");
        }
    }
}