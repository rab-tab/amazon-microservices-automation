package com.amazon.tests.multithreadingFailureScenarios;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ========================================================================
 * SIMPLIFIED DEMO: Shared RequestSpecification Race Condition
 * ========================================================================
 *
 * This is a SIMPLIFIED version with only 5 threads for easy debugging:
 * - 5 CREATE threads setting request body
 * - 5 GET threads making requests
 * - All using SAME shared RequestSpecification
 *
 * WHY ONLY 5 THREADS?
 * - Easy to follow in logs
 * - Easy to debug with breakpoints
 * - Can see exactly which thread does what
 * - Still enough to demonstrate the race condition
 *
 * Run with: testng-simple-demo.xml
 */
@Epic("Multithreading - Simplified Demo")
@Feature("Shared RequestSpecification Problem")
public class SimpleSharedSpecDemo extends BaseTest {

    // ❌ SHARED static RequestSpecification - THE PROBLEM!
    private static RequestSpecification sharedOrderSpec;
    private static String userToken;
    private static TestModels.UserResponse user;
    private static TestModels.ProductResponse product;
    private static ObjectMapper objectMapper = new ObjectMapper();

    // Setup synchronization
    private static volatile boolean setupComplete = false;
    private static final Object SETUP_LOCK = new Object();

    // Synchronization
    private static ConcurrentHashMap<String, String> createdOrders = new ConcurrentHashMap<>();

    // Metrics
    private static AtomicInteger createSuccessCount = new AtomicInteger(0);
    private static AtomicInteger createFailureCount = new AtomicInteger(0);
    private static AtomicInteger getSuccessCount = new AtomicInteger(0);
    private static AtomicInteger getFailureCount = new AtomicInteger(0);

    // Setup flag to ensure one-time initialization
   // private static volatile boolean setupComplete = false;
   // private static final Object SETUP_LOCK = new Object();

    @BeforeMethod
    public void setup() throws SeedingException, InterruptedException {
        // ✅ Only first thread does the seeding
        if (!setupComplete) {
            synchronized (SETUP_LOCK) {
                if (!setupComplete) {  // Double-check pattern
                    System.out.println("\n" + "=".repeat(80));
                    System.out.println("⚙️  ONE-TIME SETUP - Thread " + Thread.currentThread().getId());
                    System.out.println("=".repeat(80));

                    // Seed test data ONCE (not per thread)
                    user = UserSeeder.builder(context).count(1).build().seed().getFirst();
                    userToken = context.getCached("user_token_" + user.getId(), String.class);
                    product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

                    waitForDataPropagation(1000);

                    System.out.println("✅ Test data seeded:");
                    System.out.println("   User: " + user.getId());
                    System.out.println("   Product: " + product.getId());
                    System.out.println("=".repeat(80) + "\n");

                    setupComplete = true;
                }
            }
        }

        // Every thread waits for setup to complete
        while (!setupComplete) {
            Thread.sleep(100);
        }

        System.out.println("Thread " + Thread.currentThread().getId() + " starting tests\n");
    }

    @BeforeMethod
    public void createSharedSpec() {
        // ❌ THE PROBLEM: Create SHARED RequestSpecification
        // This runs for each test method but still creates same shared spec
        if (sharedOrderSpec == null) {
            synchronized (SETUP_LOCK) {
                if (sharedOrderSpec == null) {
                    System.out.println("\n" + "=".repeat(80));
                    System.out.println("❌ CREATING SHARED SPEC");
                    System.out.println("=".repeat(80));

                    sharedOrderSpec = RestAssured.given()
                            .baseUri(context.getConfig().baseUrl())
                            .header("Authorization", "Bearer " + userToken)
                            .contentType("application/json");

                    System.out.println("❌ SHARED RequestSpec HashCode: " + sharedOrderSpec.hashCode());
                    System.out.println("   Base URI: " + context.getConfig().baseUrl());
                    System.out.println("   All 10 threads will use THIS spec\n");
                }
            }
        }
    }

    /**
     * CREATE TEST: 5 threads creating orders
     * Each thread sets a DIFFERENT body on the SAME shared spec
     */
    @Test(invocationCount = 5, threadPoolSize = 5,
            description = "5 threads creating orders with SHARED spec")
    @Story("Create Order - Demonstrates Race Condition")
    @Severity(SeverityLevel.CRITICAL)
    public void testCreateOrder() {
        long threadId = Thread.currentThread().getId();

        try {
            // Create UNIQUE order request for THIS thread
            TestModels.CreateOrderRequest orderRequest = TestModels.CreateOrderRequest.builder()
                    .items(java.util.List.of(
                            TestModels.OrderItemRequest.builder()
                                    .productId(product.getId())
                                    .productName("Product_Thread_" + threadId)
                                    .quantity(1)
                                    .unitPrice(product.getPrice())
                                    .build()
                    ))
                    .build();

            // ❌ THE PROBLEM: All 5 threads use SAME sharedOrderSpec
            Response response = sendOrderRequest(userToken, orderRequest);

            if (response.statusCode() == 201) {
                String orderId = response.jsonPath().getString("id");
                createdOrders.put(orderId, "Thread-" + threadId);
                createSuccessCount.incrementAndGet();

                System.out.println("[CREATE-" + threadId + "] ✅ Order: " + orderId);
            } else {
                createFailureCount.incrementAndGet();
                System.err.println("[CREATE-" + threadId + "] ❌ Status: " + response.statusCode());
            }

        } catch (Exception e) {
            createFailureCount.incrementAndGet();
            System.err.println("[CREATE-" + threadId + "] ❌ Exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * GET TEST: 5 threads getting orders
     * GET should NOT have a body, but might inherit CREATE's body!
     */
    @Test(invocationCount = 5, threadPoolSize = 5,
            description = "5 threads getting orders with SHARED spec")
    @Story("Get Order - Demonstrates Race Condition")
    @Severity(SeverityLevel.CRITICAL)
    public void testGetOrder() throws InterruptedException {
        long threadId = Thread.currentThread().getId();

        try {
            // Wait for at least one order to be created (simple retry loop)
            int attempts = 0;
            while (createdOrders.isEmpty() && attempts < 100) {
                Thread.sleep(100);  // Wait 100ms
                attempts++;
            }

            if (createdOrders.isEmpty()) {
                getFailureCount.incrementAndGet();
                throw new RuntimeException("No orders created after 10 seconds");
            }

            String orderId = createdOrders.keys().nextElement();

            // ❌ THE PROBLEM: Using SAME sharedOrderSpec as CREATE threads
            // GET might inherit body from CREATE!
            String status = getOrderStatus(orderId);

            if ("PENDING".equals(status) || "CONFIRMED".equals(status)) {
                getSuccessCount.incrementAndGet();
                System.out.println("[GET-" + threadId + "] ✅ Retrieved order");
            } else if ("UNKNOWN".equals(status)) {
                getFailureCount.incrementAndGet();
                System.err.println("[GET-" + threadId + "] ❌ Status: UNKNOWN (Request failed!)");
            } else {
                getFailureCount.incrementAndGet();
                System.err.println("[GET-" + threadId + "] ❌ Unexpected status: " + status);
            }

        } catch (Exception e) {
            getFailureCount.incrementAndGet();
            System.err.println("[GET-" + threadId + "] ❌ Exception: " + e.getMessage());
            throw e;
        }
    }

    @AfterClass
    public void printResults() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("RESULTS");
        System.out.println("=".repeat(80));

        System.out.println("CREATE: " + createSuccessCount.get() + "/5 success, " +
                createFailureCount.get() + " failures");
        System.out.println("GET   : " + getSuccessCount.get() + "/5 success, " +
                getFailureCount.get() + " failures");

        int totalFailures = createFailureCount.get() + getFailureCount.get();
        System.out.println("TOTAL : " + totalFailures + " failures, " +
                createdOrders.size() + " orders created");

        System.out.println("=".repeat(80));

        if (totalFailures > 0) {
            System.out.println("\n🔥 RACE CONDITION DETECTED!");
            System.out.println("\nProblem: All threads used SAME sharedOrderSpec");
            System.out.println("         GET requests inherited body from CREATE threads");
            System.out.println("\n💡 Next: Implement ThreadLocal solution in separate class");
        } else {
            System.out.println("\n⚠️  No failures - race conditions are random, run again");
        }

        System.out.println("=".repeat(80) + "\n");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS - Same pattern as your working test
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Send order request using the SHARED spec
     * ❌ THIS IS THE PROBLEM - all threads use same spec!
     */
    private Response sendOrderRequest(
            String userToken,
            TestModels.CreateOrderRequest orderRequest) throws Exception {

        String requestBody = objectMapper.writeValueAsString(orderRequest);

        // ❌ Using sharedOrderSpec - THE RACE CONDITION!
        return sharedOrderSpec
                .header("Authorization", "Bearer " + userToken)  // ❌ Overwrites header
                .body(requestBody)  // ❌ RACE CONDITION: Multiple threads setting body!
                .when()
                .post("/api/orders");  // API Gateway route
    }

    /**
     * Get order status - also uses SHARED spec!
     * ❌ Might inherit body from CREATE threads!
     */
    private String getOrderStatus(String orderId) {
        try {
            // ❌ Using sharedOrderSpec - might have body from CREATE!
            Response response = sharedOrderSpec
                    .header("X-User-Id", user.getId().toString())
                    .header("Authorization", "Bearer " + userToken)
                    .when()
                    .get("/api/orders/" + orderId);  // API Gateway route

            if (response.statusCode() == 200) {
                return response.jsonPath().getString("status");
            } else if (response.statusCode() == 400) {
                // Likely has body from CREATE thread!
                return "UNKNOWN";
            }
        } catch (Exception e) {
            System.err.println("Failed to get order status for " + orderId + ": " + e.getMessage());
        }
        return "UNKNOWN";
    }
}