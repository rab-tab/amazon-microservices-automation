package com.amazon.tests.orderCreationFlow;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.validators.DatabaseValidator;
import com.amazon.tests.utils.RedisValidator;
import com.amazon.tests.utils.retry.RetryHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.Test;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.testng.Assert.*;

@Slf4j
@Epic("Order Service")
@Feature("Distributed Idempotency")
public class DistributedIdempotencyTest extends BaseTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 1: Basic Idempotency with Retry
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("Idempotency Key Prevents Duplicates")
    @Description("Verify that same idempotency key returns same order")
    public void testIdempotencyDuplicatePrevention() throws Exception {
        log.info("=== TEST 1: Idempotency Duplicate Prevention (with Retry) ===");

        // Seed data
        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        String idempotencyKey = UUID.randomUUID().toString();

        log.info("🔑 Idempotency Key: {}", idempotencyKey);

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // ⭐ FIRST REQUEST with default retry
        log.info("📤 Sending first request...");
        Response response1 = executeWithRetry(() ->
                {
                    try {
                        return sendOrderRequest(userToken, idempotencyKey, orderRequest);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
        );

        assertEquals(response1.getStatusCode(), 201, "First request should create order");
        String orderId1 = response1.as(TestModels.OrderResponse.class).getId();
        log.info("✅ First order created: {}", orderId1);

        // Verify Redis cache
        String cacheKey = "idempotency:order:" + user.getId() + ":" + idempotencyKey;
        assertTrue(RedisValidator.keyExists(cacheKey), "Should be cached in Redis");
        log.info("✅ Order cached in Redis");

        // ⭐ SECOND REQUEST with retry (handles race conditions)
        log.info("📤 Sending duplicate request...");
        Response response2 = executeWithRetry(() ->
                {
                    try {
                        return sendOrderRequest(userToken, idempotencyKey, orderRequest);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                raceConditionRetryConfig()  // Use aggressive retry for duplicates
        );

        assertEquals(response2.getStatusCode(), 200, "Duplicate should return 200 OK");
        String orderId2 = response2.as(TestModels.OrderResponse.class).getId();
        log.info("✅ Second request returned existing order: {}", orderId2);

        assertEquals(orderId1, orderId2, "Should return same order ID");

        log.info("════════════════════════════════════════════════════════");
        log.info("✅ TEST PASSED: Idempotency prevents duplicates");
        log.info("   Created once: {}", orderId1);
        log.info("   Returned twice: Same order");
        log.info("════════════════════════════════════════════════════════");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 2: Multi-Instance Race Condition with Retry- FAIL
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 2)
    @Story("Multi-Instance Concurrency")
    @Description("10 concurrent requests with same idempotency key should create only 1 order")
    public void testMultipleInstancesRaceCondition() throws Exception {
        log.info("=== TEST 2: Multi-Instance Race Condition (with Retry) ===");

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        // ⭐ Extract ALL values BEFORE threading
        final String userToken = context.getCached("user_token_" + user.getId(), String.class);
        final String idempotencyKey = UUID.randomUUID().toString();
        final String baseUrl = context.getConfig().baseUrl();

        log.info("🔑 Idempotency Key: {}", idempotencyKey);
        log.info("🔗 Base URL: {}", baseUrl);

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 2)
                .build();

        // ⭐ Pre-serialize the request body
        final String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(orderRequest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request", e);
        }

        // ⭐ Create retry config for race conditions (aggressive retry on 404)
        RetryHandler.RetryConfig retryConfig = new RetryHandler.RetryConfig()
                .maxAttempts(10)       // More attempts for race conditions
                .initialDelay(100)     // 100ms between retries
                .retryPolicy(RetryHandler.RetryPolicy.LINEAR)  // Constant delay
                .retryOnStatusCodes(404, 503)  // Retry on 404 (order not found yet)
                .build();

        // ═══════════════════════════════════════════════════════════════
        // Execute 10 concurrent POST requests with RETRY LOGIC
        // ═══════════════════════════════════════════════════════════════
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(10);

        List<Response> responses = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 10; i++) {
            final int requestNum = i + 1;

            executor.submit(() -> {
                try {
                    startGate.await();  // Wait for all threads

                    log.info("🚀 Thread {} sending request with retry...", requestNum);

                    // ⭐ CRITICAL: Each request has its own retry logic
                    Response response = RetryHandler.executeRequestWithRetry(
                            () -> RestAssured
                                    .given().log().all()
                                    .baseUri(baseUrl)
                                    .header("Authorization", "Bearer " + userToken)
                                    .header("Idempotency-Key", idempotencyKey)
                                    .contentType("application/json")
                                    .accept("application/json")
                                    .body(requestBody)
                                    .when()
                                    .post("/api/orders"),
                            retryConfig
                    );

                    responses.add(response);

                    log.info("✓ Thread {} completed: status={}", requestNum, response.getStatusCode());

                } catch (Exception e) {
                    log.error("❌ Thread {} failed after all retries: {}", requestNum, e.getMessage(), e);
                } finally {
                    endGate.countDown();
                }
            });
        }

        // Release all threads simultaneously
        log.info("🏁 Releasing all 10 threads...");
        startGate.countDown();

        // Wait for completion (increased timeout for retries)
        waitForLatch(endGate, 60000);  // 60 seconds

        executor.shutdown();
        waitForExecutorTermination(executor, 10000);

        // ═══════════════════════════════════════════════════════════════
        // Analyze Results
        // ═══════════════════════════════════════════════════════════════
        log.info("════════════════════════════════════════════════════════");
        log.info("📊 Test Results:");
        log.info("   Total requests: {}", responses.size());

        Map<Integer, Long> statusCounts = responses.stream()
                .collect(Collectors.groupingBy(Response::getStatusCode, Collectors.counting()));

        statusCounts.forEach((status, count) ->
                log.info("   HTTP {}: {} requests", status, count)
        );

        // Check for errors
        long errorCount = responses.stream()
                .filter(r -> r.getStatusCode() >= 400)
                .count();

        if (errorCount > 0) {
            log.error("❌ {} requests failed with errors:", errorCount);
            responses.stream()
                    .filter(r -> r.getStatusCode() >= 400)
                    .forEach(r -> {
                        log.error("   Status {}: {}", r.getStatusCode(), r.getBody().asString());
                    });
            fail(String.format("%d requests failed with errors", errorCount));
        }

        // Verify all succeeded
        assertEquals(responses.size(), 10, "Should have 10 responses");

        long successCount = responses.stream()
                .filter(r -> r.getStatusCode() == 200 || r.getStatusCode() == 201)
                .count();

        assertEquals(successCount, 10L, "All 10 requests should succeed after retries");
        log.info("   ✓ All requests succeeded");

        // Verify only 1 unique order
        Set<String> orderIds = responses.stream()
                .map(r -> r.as(TestModels.OrderResponse.class).getId())
                .collect(Collectors.toSet());

        log.info("   Unique orders created: {}", orderIds.size());
        orderIds.forEach(id -> log.info("     - Order: {}", id));

        assertEquals(orderIds.size(), 1, "Should create exactly 1 order");

        // Verify status distribution
        long created = statusCounts.getOrDefault(201, 0L);
        long ok = statusCounts.getOrDefault(200, 0L);

        log.info("   Status distribution:");
        log.info("     - 201 Created: {} (new order)", created);
        log.info("     - 200 OK: {} (duplicates)", ok);

        assertTrue(created >= 1, "Should have at least 1 '201 Created'");
        assertTrue(ok >= 7, "Should have at least 7 '200 OK'");

        log.info("════════════════════════════════════════════════════════");
        log.info("✅ PASSED: Race condition handled correctly with retry");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 3: Redis Cache Expiry with Retry - PASS
    //This test proves that idempotency works even when Redis cache expires,
    // because the database serves as a durable fallback and the cache is automatically rebuilt.
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 3)
    @Story("Cache Expiry Fallback")
    @Description("After Redis cache expires, should fallback to database")
    public void testRedisCacheExpiry() throws Exception {
        log.info("=== TEST 3: Redis Cache Expiry (with Retry) ===");

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        String idempotencyKey = UUID.randomUUID().toString();

        log.info("🔑 Idempotency Key: {}", idempotencyKey);

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // ⭐ Create order with retry
        log.info("📤 Creating order...");
        Response response1 = executeWithRetry(() ->
                {
                    try {
                        return sendOrderRequest(userToken, idempotencyKey, orderRequest);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
        );

        assertEquals(response1.getStatusCode(), 201);
        String orderId1 = response1.as(TestModels.OrderResponse.class).getId();
        log.info("✅ Order created: {}", orderId1);

        // Verify cached
        String cacheKey = "idempotency:order:" + user.getId() + ":" + idempotencyKey;
        assertTrue(RedisValidator.keyExists(cacheKey), "Should be cached");
        long ttl = RedisValidator.getTtl(cacheKey);
        log.info("⏱️  Cache TTL: {} seconds", ttl);

        // Wait for cache expiry
        log.info("⏳ Waiting {} seconds for cache to expire...", ttl + 1);
        Thread.sleep((ttl + 1) * 1000);

        assertFalse(RedisValidator.keyExists(cacheKey), "Cache should be expired");
        log.info("✓ Redis cache expired");

        // ⭐ Retry with same key (with retry for database lookup)
        log.info("📤 Sending request after cache expiry...");
        Response response2 = executeWithRetry(() ->
                {
                    try {
                        return sendOrderRequest(userToken, idempotencyKey, orderRequest);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                raceConditionRetryConfig()  // Aggressive retry for DB lookup
        );

        assertEquals(response2.getStatusCode(), 200, "Should return existing order from DB");
        String orderId2 = response2.as(TestModels.OrderResponse.class).getId();

        assertEquals(orderId2, orderId1, "Should return same order from DB");
        log.info("✅ Database fallback returned same order");

        // Verify cache rebuilt
        assertTrue(RedisValidator.keyExists(cacheKey), "Cache should be rebuilt");
        log.info("✅ Cache rebuilt after DB lookup");

        log.info("════════════════════════════════════════════════════════");
        log.info("✅ PASSED: Cache expiry → DB fallback → Cache rebuild");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 4: Complete Key Expiry (Order Deleted) - PASS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 4)
    @Story("Idempotency Key Reuse After Cleanup")
    @Description("After order is deleted, same idempotency key can create new order")
    public void testIdempotencyKeyCompleteExpiry() throws Exception {
        log.info("=== TEST 4: Idempotency Key Complete Expiry (with Retry) ===");

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        String idempotencyKey = UUID.randomUUID().toString();

        log.info("🔑 Idempotency Key: {}", idempotencyKey);
        log.info("👤 User ID: {}", user.getId());

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // ⭐ STEP 1: Create first order
        Response response1 = executeWithRetry(() -> {
            try {
                return sendOrderRequest(userToken, idempotencyKey, orderRequest);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertEquals(response1.getStatusCode(), 201);
        String orderId1 = response1.as(TestModels.OrderResponse.class).getId();
        log.info("✅ First order created: {}", orderId1);

        // Verify order exists in database
        assertTrue(DatabaseValidator.getInstance().orderExistsById(orderId1),
                "Order should exist in database");

        // Verify idempotency record exists
        long count = DatabaseValidator.getInstance()
                .countOrdersByIdempotencyKey(user.getId(), idempotencyKey);
        assertEquals(count, 1L, "Should have 1 order with this idempotency key");

        // ⭐ STEP 2: Wait for Redis expiry
        String cacheKey = "idempotency:order:" + user.getId() + ":" + idempotencyKey;
        long ttl = RedisValidator.getTtl(cacheKey);
        log.info("⏳ Waiting {} seconds for Redis expiry...", ttl + 1);
        Thread.sleep((ttl + 1) * 1000);

        // Verify Redis expired
        assertFalse(RedisValidator.keyExists(cacheKey), "Redis cache should be expired");
        log.info("✅ Redis cache expired");

        // ⭐ STEP 3: Delete order from database (simulates cleanup job)
        deleteOrderDirectly(orderId1);

        // Verify deletion
        assertFalse(DatabaseValidator.getInstance().orderExistsById(orderId1),
                "Order should be deleted from database");

        count = DatabaseValidator.getInstance()
                .countOrdersByIdempotencyKey(user.getId(), idempotencyKey);
        assertEquals(count, 0L, "Should have 0 orders with this idempotency key");

        log.info("✅ Order deleted from database");

        // ⭐ STEP 4: Create new order with SAME idempotency key
        Response response2 = executeWithRetry(() -> {
            try {
                return sendOrderRequest(userToken, idempotencyKey, orderRequest);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertEquals(response2.getStatusCode(), 201, "Should create NEW order");
        String orderId2 = response2.as(TestModels.OrderResponse.class).getId();

        assertNotEquals(orderId2, orderId1, "Should be a DIFFERENT order");
        log.info("✅ New order created: {}", orderId2);

        // Verify new order exists
        assertTrue(DatabaseValidator.getInstance().orderExistsById(orderId2),
                "New order should exist in database");

        log.info("════════════════════════════════════════════════════════");
        log.info("✅ PASSED: Idempotency key reusable after cleanup");
        log.info("   First order (deleted): {}", orderId1);
        log.info("   New order (created):   {}", orderId2);
        log.info("════════════════════════════════════════════════════════");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 5: User-Scoped Idempotency - FAIL
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 5)
    @Story("User-Scoped Idempotency")
    @Description("Same idempotency key for different users should create different orders")
    public void testIdempotencyKeyScopedToUser() throws Exception {
        log.info("=== TEST 5: Idempotency Key Scoped to User (with Retry) ===");

        // ✅ Create two users - FIXED
        List<TestModels.UserResponse> users = UserSeeder.builder(context)
                .count(2)
                .build()
                .seed()
                .getUsers();  // ⭐ THIS WAS MISSING

        TestModels.UserResponse user1 = users.get(0);
        TestModels.UserResponse user2 = users.get(1);

        TestModels.ProductResponse product = ProductSeeder.builder(context)
                .count(1)
                .highStock()
                .build()
                .seed()
                .getFirst();

        waitForDataPropagation(1000);

        String token1 = context.getCached("user_token_" + user1.getId(), String.class);
        String token2 = context.getCached("user_token_" + user2.getId(), String.class);

        // SAME idempotency key for BOTH users
        String sharedIdempotencyKey = UUID.randomUUID().toString();

        log.info("🔑 Shared Idempotency Key: {}", sharedIdempotencyKey);
        log.info("👤 User 1: {}", user1.getId());
        log.info("👤 User 2: {}", user2.getId());

        TestModels.CreateOrderRequest orderRequest1 = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        TestModels.CreateOrderRequest orderRequest2 = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 2)
                .build();

        // User 1 creates order
        Response response1 = executeWithRetry(() -> {
            try {
                return sendOrderRequest(token1, sharedIdempotencyKey, orderRequest1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertEquals(response1.getStatusCode(), 201);
        String orderId1 = response1.as(TestModels.OrderResponse.class).getId();
        log.info("✅ User 1 order created: {}", orderId1);

        // User 2 creates order with SAME key
        Response response2 = executeWithRetry(() -> {
            try {
                return sendOrderRequest(token2, sharedIdempotencyKey, orderRequest2);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertEquals(response2.getStatusCode(), 201, "User 2 should create different order");
        String orderId2 = response2.as(TestModels.OrderResponse.class).getId();
        log.info("✅ User 2 order created: {}", orderId2);

        // Verify different orders
        assertNotEquals(orderId1, orderId2, "Different users should create different orders");

        // Verify ownership
        assertEquals(response1.as(TestModels.OrderResponse.class).getUserId(), user1.getId());
        assertEquals(response2.as(TestModels.OrderResponse.class).getUserId(), user2.getId());

        log.info("════════════════════════════════════════════════════════");
        log.info("✅ PASSED: Idempotency key is user-scoped");
        log.info("   User 1: {} → Order: {}", user1.getId(), orderId1);
        log.info("   User 2: {} → Order: {}", user2.getId(), orderId2);
        log.info("════════════════════════════════════════════════════════");
    }
    // ══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Send single order request (without retry - to be wrapped by executeWithRetry)
     */
    private Response sendOrderRequest(
            String userToken,
            String idempotencyKey,
            TestModels.CreateOrderRequest orderRequest) throws Exception {

        String requestBody = objectMapper.writeValueAsString(orderRequest);

        return RestAssured
                .given().log().all()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .accept("application/json")
                .body(requestBody)
                .when().log().all()
                .post("/api/orders");
    }

    /**
     * Delete order directly from database (for testing cleanup scenarios)
     */
    private void deleteOrderDirectly(String orderId) {
        log.info("🗑️  Deleting order from database: {}", orderId);

        boolean deleted = DatabaseValidator.getInstance().deleteOrderById(orderId);

        if (deleted) {
            log.info("✅ Order deleted successfully from database");
        } else {
            log.warn("⚠️  Order not found in database (may have been already deleted)");
        }

        // Verify deletion
        boolean exists = DatabaseValidator.getInstance().orderExistsById(orderId);
        if (exists) {
            throw new RuntimeException("Order still exists after deletion: " + orderId);
        }

        log.info("✅ Verified: Order no longer exists in database");
    }

    /**
     * Alternative: Delete by idempotency key (useful if you don't have order ID)
     */
    private void deleteOrderByIdempotencyKey(String userId, String idempotencyKey) {
        log.info("🗑️  Deleting order by idempotency key: {}", idempotencyKey);

        boolean deleted = DatabaseValidator.getInstance()
                .deleteOrderByIdempotencyKey(userId, idempotencyKey);

        if (deleted) {
            log.info("✅ Order deleted successfully");
        } else {
            log.warn("⚠️  No order found with idempotency key: {}", idempotencyKey);
        }
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================
    // ═══════════════════════════════════════════════════════════════════════
    // ⭐ HELPER METHODS - NO TimeUnit NEEDED
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Wait for Future to complete WITHOUT using TimeUnit
     *
     * @param future Future to wait for
     * @param timeoutMillis Timeout in milliseconds
     * @return Response or null if timeout/error
     */
    private <T> T waitForFuture(Future<T> future, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;

        while (!future.isDone()) {
            if (System.currentTimeMillis() > deadline) {
                log.error("Future timed out after {}ms", timeoutMillis);
                future.cancel(true);
                return null;
            }

            try {
                Thread.sleep(50); // Check every 50ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        try {
            return future.get();
        } catch (Exception e) {
            log.error("Error getting future result", e);
            return null;
        }
    }

    /**
     * Wait for ExecutorService to terminate WITHOUT using TimeUnit
     *
     * @param executor ExecutorService to wait for
     * @param timeoutMillis Timeout in milliseconds
     */
    private void waitForExecutorTermination(ExecutorService executor, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;

        while (!executor.isTerminated()) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("Executor did not terminate within {}ms, forcing shutdown", timeoutMillis);
                executor.shutdownNow();
                break;
            }

            try {
                Thread.sleep(100); // Check every 100ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
                break;
            }
        }
    }

    /**
     * Wait for CountDownLatch WITHOUT using TimeUnit
     *
     * @param latch Latch to wait for
     * @param timeoutMillis Timeout in milliseconds
     */
    private void waitForLatch(CountDownLatch latch, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;

        while (latch.getCount() > 0) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("Latch timed out after {}ms", timeoutMillis);
                break;
            }

            try {
                Thread.sleep(50); // Check every 50ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}