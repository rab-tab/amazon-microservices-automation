package com.amazon.tests.multithreadingFailureScenarios;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ========================================================================
 * PRACTICAL USE CASE: Parallel Data Cleanup
 * ========================================================================
 *
 * Real-world scenario: Delete 20 test orders in parallel instead of sequentially
 *
 * Sequential: 20 orders × 200ms each = 4 seconds
 * Parallel (5 threads): 20 orders / 5 = 4 batches × 200ms = 800ms
 *
 * Speedup: 5x faster!
 *
 * This demonstrates:
 * - ExecutorService for parallel execution
 * - Future for tracking async tasks
 * - Proper thread pool management
 * - Real performance improvement
 */
@Epic("Practical Multithreading")
@Feature("Parallel Data Cleanup")
public class ParallelCleanupDemo extends BaseTest {

    private TestModels.UserResponse user;
    private String userToken;
    private TestModels.ProductResponse product;
    private ObjectMapper objectMapper = new ObjectMapper();

    // Track created orders
    private List<String> createdOrderIds = new ArrayList<>();

    // Metrics
    private AtomicInteger deleteSuccessCount = new AtomicInteger(0);
    private AtomicInteger deleteFailureCount = new AtomicInteger(0);

    @BeforeClass
    public void setup() throws SeedingException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🧹 PARALLEL CLEANUP DEMO - Real-world Use Case");
        System.out.println("=".repeat(80));
        System.out.println("Scenario: Clean up 20 test orders");
        System.out.println("Sequential vs Parallel comparison");
        System.out.println("=".repeat(80) + "\n");

        // Seed test data
        user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        userToken = context.getCached("user_token_" + user.getId(), String.class);
        product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        System.out.println("✅ Test data ready");
        System.out.println("   User: " + user.getId());
        System.out.println("   Product: " + product.getId() + "\n");
    }

    /**
     * STEP 1: Create 20 test orders (setup phase)
     */
    @Test(priority = 1, description = "Create 20 test orders for cleanup demo")
    @Story("Setup - Create Test Data")
    public void step1_createTestOrders() throws Exception {
        System.out.println("📦 STEP 1: Creating 20 test orders...\n");

        long startTime = System.currentTimeMillis();

        for (int i = 1; i <= 20; i++) {
            TestModels.CreateOrderRequest orderRequest = TestModels.CreateOrderRequest.builder()
                    .items(List.of(
                            TestModels.OrderItemRequest.builder()
                                    .productId(product.getId())
                                    .productName("TestProduct_" + i)
                                    .quantity(1)
                                    .unitPrice(product.getPrice())
                                    .build()
                    ))
                    .build();

            Response response = RestAssured.given()
                    .baseUri(context.getConfig().baseUrl())
                    .header("Authorization", "Bearer " + userToken)
                    .header("X-User-Id", user.getId())
                    .contentType("application/json")
                    .body(objectMapper.writeValueAsString(orderRequest))
                    .post("/api/orders");

            if (response.statusCode() == 201) {
                String orderId = response.jsonPath().getString("id");
                createdOrderIds.add(orderId);
                System.out.println("  [" + i + "/20] Created order: " + orderId);
            } else {
                System.err.println("  [" + i + "/20] ❌ Failed: " + response.statusCode());
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        System.out.println("\n✅ Created " + createdOrderIds.size() + " orders");
        System.out.println("⏱️  Time taken: " + duration + "ms\n");
    }



    /**
     * STEP 3: Delete orders IN PARALLEL (optimized)
     */
    @Test(priority = 3, description = "Delete orders in parallel - FAST")
    @Story("Parallel Cleanup - Optimized")
    public void step3_deleteInParallel() throws InterruptedException, ExecutionException {
        System.out.println("🚀 STEP 3: Deleting orders IN PARALLEL (5 threads)...\n");

        long startTime = System.currentTimeMillis();


        // ✅ Create thread pool with 5 threads
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<Future<Boolean>> futures = new ArrayList<>();

        // ✅ Submit deletion tasks to thread pool
        for (int i = 0; i < createdOrderIds.size(); i++) {
            final String orderId = createdOrderIds.get(i);
            final int orderNumber = i + 1;

            // Submit task to thread pool
            Future<Boolean> future = executor.submit(() -> {
                return deleteOrder(orderId, orderNumber);
            });

            futures.add(future);
        }

        // ✅ Wait for all tasks to complete
        for (Future<Boolean> future : futures) {
            Boolean success = future.get();  // Blocks until task completes
            if (success) {
                deleteSuccessCount.incrementAndGet();
            } else {
                deleteFailureCount.incrementAndGet();
            }
        }

        // ✅ Shutdown thread pool
        executor.shutdown();
        //executor.awaitTermination(30, TimeUnit.SECONDS);

        long duration = System.currentTimeMillis() - startTime;

        System.out.println("\n📊 PARALLEL CLEANUP RESULTS:");
        System.out.println("   Success: " + deleteSuccessCount.get() + "/20");
        System.out.println("   Failures: " + deleteFailureCount.get());
        System.out.println("   ⏱️  Total time: " + duration + "ms");
        System.out.println("   Speed: " + (duration / createdOrderIds.size()) + "ms per order");
        System.out.println("   🎯 Speedup: ~" + (20 * 200 / duration) + "x faster than sequential!\n");
    }

    /**
     * Helper method to delete a single order
     */
    private boolean deleteOrder(String orderId, int orderNumber) {
        try {
            long threadId = Thread.currentThread().getId();

            Response response = RestAssured.given()
                    .baseUri(context.getConfig().baseUrl())
                    .header("Authorization", "Bearer " + userToken)
                    .header("X-User-Id", user.getId())
                    .delete("/api/orders/" + orderId);

            boolean success = (response.statusCode() == 204 || response.statusCode() == 200);

            if (success) {
                System.out.println("  [Thread-" + threadId + "] [" + orderNumber + "/20] ✅ Deleted: " + orderId);
            } else {
                System.err.println("  [Thread-" + threadId + "] [" + orderNumber + "/20] ❌ Failed: " +
                        response.statusCode());
            }

            return success;

        } catch (Exception e) {
            System.err.println("  [" + orderNumber + "/20] ❌ Exception: " + e.getMessage());
            return false;
        }
    }

    @AfterClass
    public void printSummary() {
        System.out.println("=".repeat(80));
        System.out.println("🎯 DEMO COMPLETE");
        System.out.println("=".repeat(80));
        System.out.println("\nKey Learnings:");
        System.out.println("  ✅ ExecutorService manages thread pools efficiently");
        System.out.println("  ✅ Future tracks async task completion");
        System.out.println("  ✅ Parallel execution = significant speedup for I/O operations");
        System.out.println("  ✅ Always shutdown() executor to clean up threads");
        System.out.println("\nPractical Use Cases:");
        System.out.println("  • Parallel data cleanup");
        System.out.println("  • Bulk API operations");
        System.out.println("  • Mass data validation");
        System.out.println("  • Concurrent test data creation");
        System.out.println("=".repeat(80) + "\n");
    }
}
