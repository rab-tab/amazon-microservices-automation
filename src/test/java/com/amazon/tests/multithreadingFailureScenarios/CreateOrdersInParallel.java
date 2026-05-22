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
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ========================================================================
 * SIMPLE & PRACTICAL: Create 10 Orders in Parallel
 * ========================================================================
 *
 * Real-world scenario: Create 10 test orders for your test suite
 *
 * Sequential: 10 orders × 600ms each = 6 seconds
 * Parallel (5 threads): 10 orders ÷ 5 = 2 batches × 600ms = 1.2 seconds
 *
 * Speedup: 5x faster!
 *
 * Perfect for:
 * - @BeforeClass: Create test data before test execution
 * - Bulk order generation for reporting tests
 * - Performance test data setup
 */
@Epic("Parallel Test Data Creation")
@Feature("Parallel Order Creation")
public class CreateOrdersInParallel extends BaseTest {

    private TestModels.UserResponse customer;
    private String customerToken;
    private TestModels.ProductResponse product;
    private ObjectMapper objectMapper = new ObjectMapper();

    // Track created orders
    private final List<String> createdOrderIds = Collections.synchronizedList(new ArrayList<>());

    // Metrics
    private AtomicInteger successCount = new AtomicInteger(0);
    private AtomicInteger failureCount = new AtomicInteger(0);

    @BeforeClass
    public void setupTestData() throws SeedingException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🛒 CREATE 10 ORDERS IN PARALLEL - Simple Demo");
        System.out.println("=".repeat(80));
        System.out.println("Setup: Creating test user and product...\n");

        // Create 1 customer
        customer = UserSeeder.builder(context).count(1).build().seed().getFirst();
        customerToken = context.getCached("user_token_" + customer.getId(), String.class);

        // Create 1 product
        product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        System.out.println("✅ Test data ready:");
        System.out.println("   Customer: " + customer.getId());
        System.out.println("   Product: " + product.getId());
        System.out.println("   Product Price: $" + product.getPrice());
        System.out.println("\n" + "=".repeat(80) + "\n");
    }

    /**
     * MAIN TEST: Create 10 orders IN PARALLEL using 5 threads
     */
    @Test(description = "Create 10 orders in parallel with 5 threads")
    @Story("Parallel Order Creation")
    @Severity(SeverityLevel.CRITICAL)
    public void testCreateOrdersInParallel() throws InterruptedException, ExecutionException {
        System.out.println("🚀 Creating 10 orders IN PARALLEL (5 threads)...\n");

        long startTime = System.currentTimeMillis();
        int totalOrders = 10;
        int threadPoolSize = 5;

        // ✅ STEP 1: Create thread pool with 5 threads
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        List<Future<String>> futures = new ArrayList<>();

        // ✅ STEP 2: Submit 10 order creation tasks
        for (int i = 1; i <= totalOrders; i++) {
            final int orderNumber = i;

            // Submit task to thread pool
            Future<String> future = executor.submit(() -> {
                return createOrder(orderNumber);
            });

            futures.add(future);
        }

        // ✅ STEP 3: Wait for all orders to be created
        System.out.println("⏳ Waiting for all orders to complete...\n");

        for (int i = 0; i < futures.size(); i++) {
            Future<String> future = futures.get(i);

            try {
                String orderId = future.get();  // BLOCKS until order is created

                if (orderId != null) {
                    createdOrderIds.add(orderId);
                    successCount.incrementAndGet();
                } else {
                    failureCount.incrementAndGet();
                }

            } catch (Exception e) {
                failureCount.incrementAndGet();
                System.err.println("  ❌ Order " + (i + 1) + " failed: " + e.getMessage());
            }
        }

        // ✅ STEP 4: Shutdown thread pool
        executor.shutdown();
       // executor.awaitTermination(30, TimeUnit.SECONDS);

        long duration = System.currentTimeMillis() - startTime;

        // Print results
        printResults(totalOrders, duration);
    }

    /**
     * Helper method: Create a single order
     * This runs in a worker thread
     */
    private String createOrder(int orderNumber) {
        long threadId = Thread.currentThread().getId();

        try {
            System.out.println("  [Thread-" + threadId + "] [Order " + orderNumber + "/10] Creating...");

            // Build order request
            TestModels.CreateOrderRequest orderRequest = TestModels.CreateOrderRequest.builder()
                    .items(List.of(
                            TestModels.OrderItemRequest.builder()
                                    .productId(product.getId())
                                    .productName("Product_Order_" + orderNumber)
                                    .quantity(orderNumber)  // Different quantity for each order
                                    .unitPrice(product.getPrice())
                                    .build()
                    ))
                    .build();

            // Make API call to create order
            Response response = RestAssured.given()
                    .baseUri(context.getConfig().baseUrl())
                    .header("Authorization", "Bearer " + customerToken)
                    .header("X-User-Id", customer.getId())
                    .contentType("application/json")
                    .body(objectMapper.writeValueAsString(orderRequest))
                    .post("/api/orders");

            // Check response
            if (response.statusCode() == 201) {
                String orderId = response.jsonPath().getString("id");
                System.out.println("  [Thread-" + threadId + "] [Order " + orderNumber + "/10] ✅ SUCCESS: " + orderId);
                return orderId;
            } else {
                System.err.println("  [Thread-" + threadId + "] [Order " + orderNumber + "/10] ❌ FAILED: HTTP " +
                        response.statusCode());
                return null;
            }

        } catch (Exception e) {
            System.err.println("  [Thread-" + threadId + "] [Order " + orderNumber + "/10] ❌ EXCEPTION: " +
                    e.getMessage());
            return null;
        }
    }

    /**
     * Print formatted results
     */
    private void printResults(int totalOrders, long duration) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("📊 ORDER CREATION RESULTS");
        System.out.println("=".repeat(80));

        System.out.println("\nSummary:");
        System.out.println("  Total Orders: " + totalOrders);
        System.out.println("  ✅ Success: " + successCount.get());
        System.out.println("  ❌ Failures: " + failureCount.get());
        System.out.println("  📦 Created: " + createdOrderIds.size() + " orders");

        System.out.println("\nPerformance:");
        System.out.println("  ⏱️  Total time: " + duration + "ms");
        System.out.println("  ⚡ Avg per order: " + (duration / totalOrders) + "ms");

        long sequentialEstimate = totalOrders * 600;  // Assume 600ms per order
        System.out.println("  🐌 Sequential estimate: ~" + sequentialEstimate + "ms");
        System.out.println("  🚀 Speedup: ~" + (sequentialEstimate / duration) + "x faster!");

        System.out.println("\nCreated Order IDs:");
        for (int i = 0; i < createdOrderIds.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + createdOrderIds.get(i));
        }

        System.out.println("=".repeat(80));

        if (successCount.get() == totalOrders) {
            System.out.println("\n✅✅✅ PERFECT! All 10 orders created successfully! ✅✅✅");
        } else {
            System.out.println("\n⚠️  Some orders failed - check logs above");
        }

        System.out.println("\n💡 KEY CONCEPTS DEMONSTRATED:");
        System.out.println("  1️⃣  ExecutorService with fixed thread pool (5 threads)");
        System.out.println("  2️⃣  Future to track async task completion");
        System.out.println("  3️⃣  future.get() blocks until task completes");
        System.out.println("  4️⃣  Thread-safe collections (Collections.synchronizedList)");
        System.out.println("  5️⃣  AtomicInteger for thread-safe counters");

        System.out.println("\n🎯 REAL-WORLD USAGE:");
        System.out.println("  • @BeforeClass: Create test orders before running tests");
        System.out.println("  • @BeforeSuite: Bulk data creation for entire suite");
        System.out.println("  • Performance tests: Generate 1000+ orders quickly");
        System.out.println("  • Data migration: Parallel data loading");

        System.out.println("=".repeat(80) + "\n");
    }

    @AfterClass
    public void cleanup() {
        System.out.println("🧹 Cleanup: " + createdOrderIds.size() + " orders were created");
        System.out.println("   (These can be deleted in parallel using the cleanup demo!)\n");
    }
}
