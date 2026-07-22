package com.amazon.tests.regression.orderCreationFlow.idempotency;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.RedisValidator;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.concurrency.ConcurrencyTestHelper;
import com.amazon.tests.utils.retry.RetryHandler;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.utils.validators.DatabaseValidator;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.testng.Assert.*;

@Slf4j
@Epic("Order Service")
@Feature("Distributed Idempotency")
public class OrderIdempotencyTest extends BaseTest {

    private OrderApiClient orderApiClient(String token) {
        return new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 1 — Single-user idempotency behavior (data-driven: same key vs different keys)
    // ══════════════════════════════════════════════════════════════════════════

    @DataProvider(name = "idempotencyKeyScenarios")
    public Object[][] idempotencyKeyScenarios() {
        return new Object[][] {
                { "Same key twice returns same order", true },
                { "Different keys create different orders", false }
        };
    }

    @Test(priority = 1, dataProvider = "idempotencyKeyScenarios")
    @Story("Idempotency Key Behavior")
    @Description("Verify idempotency key reuse vs. distinct keys for the same user")
    public void testIdempotencyKeyBehavior(String scenario, boolean reuseSameKey) throws Exception {
        log.info("=== TEST 1: {} ===", scenario);

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String userId = purchase.getCustomerAuth().getUser().getId();
        String token = purchase.getCustomerAuth().getAccessToken();
        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        String key1 = UUID.randomUUID().toString();
        String key2 = reuseSameKey ? key1 : UUID.randomUUID().toString();

        log.info("📤 Sending first request with key: {}", key1);
        ServiceResponse response1 = retryServiceCall(() ->
                orderApiClient(token).createOrderWithFault(userId, key1, orderRequest, null));

        assertEquals(response1.getStatusCode(), 201, "First request should create order");
        String orderId1 = response1.as(TestModels.OrderResponse.class).getId();
        log.info("✅ First order created: {}", orderId1);

        if (reuseSameKey) {
            String cacheKey = "idempotency:order:" + userId + ":" + key1;
            assertTrue(RedisValidator.keyExists(cacheKey), "Should be cached in Redis");
        }

        log.info("📤 Sending second request with key: {}", key2);
        ServiceResponse response2 = retryServiceCall(() ->
                        orderApiClient(token).createOrderWithFault(userId, key2, orderRequest, null),
                raceConditionRetryConfig());

        String orderId2 = response2.as(TestModels.OrderResponse.class).getId();

        if (reuseSameKey) {
            assertEquals(response2.getStatusCode(), 200, "Duplicate should return 200 OK");
            assertEquals(orderId2, orderId1, "Should return same order ID");
            log.info("✅ Same order returned for reused key: {}", orderId2);
        } else {
            assertEquals(response2.getStatusCode(), 201, "Different key should create new order");
            assertNotEquals(orderId2, orderId1, "Different keys should create different orders");
            log.info("✅ Different orders for different keys: {} / {}", orderId1, orderId2);
        }

        log.info("════════════════════════════════════════════════════════");
        log.info("✅ TEST PASSED: {}", scenario);
        log.info("════════════════════════════════════════════════════════");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 2: Multi-Instance Race Condition with Retry — FAIL
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 2)
    @Story("Multi-Instance Concurrency")
    @Description("10 concurrent requests with same idempotency key should create only 1 order")
    public void testMultipleInstancesRaceCondition() throws Exception {
        log.info("=== TEST 2: Multi-Instance Race Condition (with Retry) ===");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        final String userId = purchase.getCustomerAuth().getUser().getId();
        final String token = purchase.getCustomerAuth().getAccessToken();
        final String idempotencyKey = UUID.randomUUID().toString();
        final TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        log.info("🔑 Idempotency Key: {}", idempotencyKey);

        RetryHandler.RetryConfig retryConfig = new RetryHandler.RetryConfig()
                .maxAttempts(10)
                .initialDelay(100)
                .retryPolicy(RetryHandler.RetryPolicy.LINEAR)
                .retryOnStatusCodes(404, 503)
                .build();

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(10);

        List<ServiceResponse> responses = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 10; i++) {
            final int requestNum = i + 1;
            executor.submit(() -> {
                try {
                    startGate.await();
                    log.info("🚀 Thread {} sending request with retry...", requestNum);

                    ServiceResponse response = retryServiceCall(() ->
                                    orderApiClient(token).createOrderWithFault(userId, idempotencyKey, orderRequest, null),
                            retryConfig);

                    responses.add(response);
                    log.info("✓ Thread {} completed: status={}", requestNum, response.getStatusCode());
                } catch (Exception e) {
                    log.error("❌ Thread {} failed after all retries: {}", requestNum, e.getMessage(), e);
                } finally {
                    endGate.countDown();
                }
            });
        }

        log.info("🏁 Releasing all 10 threads...");
        startGate.countDown();
        ConcurrencyTestHelper.waitForLatch(endGate, 60000);
        executor.shutdown();
        ConcurrencyTestHelper.waitForExecutorTermination(executor, 10000);

        log.info("════════════════════════════════════════════════════════");
        log.info("📊 Test Results: Total requests: {}", responses.size());

        Map<Integer, Long> statusCounts = responses.stream()
                .collect(Collectors.groupingBy(ServiceResponse::getStatusCode, Collectors.counting()));
        statusCounts.forEach((status, count) -> log.info("   HTTP {}: {} requests", status, count));

        long errorCount = responses.stream().filter(r -> r.getStatusCode() >= 400).count();
        if (errorCount > 0) {
            responses.stream().filter(r -> r.getStatusCode() >= 400)
                    .forEach(r -> log.error("   Status {}: {}", r.getStatusCode(), r.getBody()));
            fail(String.format("%d requests failed with errors", errorCount));
        }

        assertEquals(responses.size(), 10, "Should have 10 responses");

        long successCount = responses.stream()
                .filter(r -> r.getStatusCode() == 200 || r.getStatusCode() == 201)
                .count();
        assertEquals(successCount, 10L, "All 10 requests should succeed after retries");

        Set<String> orderIds = responses.stream()
                .map(r -> r.as(TestModels.OrderResponse.class).getId())
                .collect(Collectors.toSet());

        log.info("   Unique orders created: {}", orderIds.size());
        assertEquals(orderIds.size(), 1, "Should create exactly 1 order");

        long created = statusCounts.getOrDefault(201, 0L);
        long ok = statusCounts.getOrDefault(200, 0L);
        log.info("   201 Created: {} | 200 OK: {}", created, ok);
        assertTrue(created >= 1, "Should have at least 1 '201 Created'");
        assertTrue(ok >= 7, "Should have at least 7 '200 OK'");

        log.info("✅ PASSED: Race condition handled correctly with retry");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 3: Redis Cache Expiry with Retry — PASS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 3)
    @Story("Cache Expiry Fallback")
    @Description("After Redis cache expires, should fallback to database")
    public void testRedisCacheExpiry() throws Exception {
        log.info("=== TEST 3: Redis Cache Expiry (with Retry) ===");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String userId = purchase.getCustomerAuth().getUser().getId();
        String token = purchase.getCustomerAuth().getAccessToken();
        String idempotencyKey = UUID.randomUUID().toString();
        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        log.info("🔑 Idempotency Key: {}", idempotencyKey);

        ServiceResponse response1 = retryServiceCall(() ->
                orderApiClient(token).createOrderWithFault(userId, idempotencyKey, orderRequest, null));

        assertEquals(response1.getStatusCode(), 201);
        String orderId1 = response1.as(TestModels.OrderResponse.class).getId();
        log.info("✅ Order created: {}", orderId1);

        String cacheKey = "idempotency:order:" + userId + ":" + idempotencyKey;
        assertTrue(RedisValidator.keyExists(cacheKey), "Should be cached");
        long ttl = RedisValidator.getTtl(cacheKey);
        log.info("⏱️  Cache TTL: {} seconds", ttl);

        log.info("⏳ Waiting {} seconds for cache to expire...", ttl + 1);
        Thread.sleep((ttl + 1) * 1000);

        assertFalse(RedisValidator.keyExists(cacheKey), "Cache should be expired");
        log.info("✓ Redis cache expired");

        ServiceResponse response2 = retryServiceCall(() ->
                        orderApiClient(token).createOrderWithFault(userId, idempotencyKey, orderRequest, null),
                raceConditionRetryConfig());

        assertEquals(response2.getStatusCode(), 200, "Should return existing order from DB");
        String orderId2 = response2.as(TestModels.OrderResponse.class).getId();
        assertEquals(orderId2, orderId1, "Should return same order from DB");
        log.info("✅ Database fallback returned same order");

        assertTrue(RedisValidator.keyExists(cacheKey), "Cache should be rebuilt");
        log.info("✅ PASSED: Cache expiry → DB fallback → Cache rebuild");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 4: Complete Key Expiry (Order Deleted) — PASS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 4)
    @Story("Idempotency Key Reuse After Cleanup")
    @Description("After order is deleted, same idempotency key can create new order")
    public void testIdempotencyKeyCompleteExpiry() throws Exception {
        log.info("=== TEST 4: Idempotency Key Complete Expiry (with Retry) ===");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String userId = purchase.getCustomerAuth().getUser().getId();
        String token = purchase.getCustomerAuth().getAccessToken();
        String idempotencyKey = UUID.randomUUID().toString();
        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        log.info("🔑 Idempotency Key: {}", idempotencyKey);
        log.info("👤 User ID: {}", userId);

        ServiceResponse response1 = retryServiceCall(() ->
                orderApiClient(token).createOrderWithFault(userId, idempotencyKey, orderRequest, null));

        assertEquals(response1.getStatusCode(), 201);
        String orderId1 = response1.as(TestModels.OrderResponse.class).getId();
        log.info("✅ First order created: {}", orderId1);

        assertTrue(DatabaseValidator.getInstance().orderExistsById(orderId1), "Order should exist in database");
        long count = DatabaseValidator.getInstance().countOrdersByIdempotencyKey(userId, idempotencyKey);
        assertEquals(count, 1L, "Should have 1 order with this idempotency key");

        String cacheKey = "idempotency:order:" + userId + ":" + idempotencyKey;
        long ttl = RedisValidator.getTtl(cacheKey);
        log.info("⏳ Waiting {} seconds for Redis expiry...", ttl + 1);
        Thread.sleep((ttl + 1) * 1000);
        assertFalse(RedisValidator.keyExists(cacheKey), "Redis cache should be expired");
        log.info("✅ Redis cache expired");

        deleteOrderDirectly(orderId1);
        assertFalse(DatabaseValidator.getInstance().orderExistsById(orderId1), "Order should be deleted from database");
        count = DatabaseValidator.getInstance().countOrdersByIdempotencyKey(userId, idempotencyKey);
        assertEquals(count, 0L, "Should have 0 orders with this idempotency key");
        log.info("✅ Order deleted from database");

        ServiceResponse response2 = retryServiceCall(() ->
                orderApiClient(token).createOrderWithFault(userId, idempotencyKey, orderRequest, null));

        assertEquals(response2.getStatusCode(), 201, "Should create NEW order");
        String orderId2 = response2.as(TestModels.OrderResponse.class).getId();
        assertNotEquals(orderId2, orderId1, "Should be a DIFFERENT order");
        log.info("✅ New order created: {}", orderId2);

        assertTrue(DatabaseValidator.getInstance().orderExistsById(orderId2), "New order should exist in database");

        log.info("✅ PASSED: Idempotency key reusable after cleanup");
        log.info("   First order (deleted): {} | New order (created): {}", orderId1, orderId2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 5: User-Scoped Idempotency — FAIL
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 5)
    @Story("User-Scoped Idempotency")
    @Description("Same idempotency key for different users should create different orders")
    public void testIdempotencyKeyScopedToUser() throws Exception {
        log.info("=== TEST 5: Idempotency Key Scoped to User (with Retry) ===");

        PurchaseResult purchase1 = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        PurchaseResult purchase2 = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .loginCustomer()
                .execute();

        String userId1 = purchase1.getCustomerAuth().getUser().getId();
        String token1 = purchase1.getCustomerAuth().getAccessToken();
        String userId2 = purchase2.getCustomerAuth().getUser().getId();
        String token2 = purchase2.getCustomerAuth().getAccessToken();

        String sharedIdempotencyKey = UUID.randomUUID().toString();
        log.info("🔑 Shared Idempotency Key: {}", sharedIdempotencyKey);
        log.info("👤 User 1: {} | 👤 User 2: {}", userId1, userId2);

        TestModels.CreateOrderRequest orderRequest1 =
                TestDataFactory.defaultOrder(purchase1.getProducts()).build();
        TestModels.CreateOrderRequest orderRequest2 =
                TestDataFactory.defaultOrder(purchase1.getProducts()).build(); // user2 orders from user1's seller catalog

        ServiceResponse response1 = retryServiceCall(() ->
                orderApiClient(token1).createOrderWithFault(userId1, sharedIdempotencyKey, orderRequest1, null));
        assertEquals(response1.getStatusCode(), 201);
        String orderId1 = response1.as(TestModels.OrderResponse.class).getId();
        log.info("✅ User 1 order created: {}", orderId1);

        ServiceResponse response2 = retryServiceCall(() ->
                orderApiClient(token2).createOrderWithFault(userId2, sharedIdempotencyKey, orderRequest2, null));
        assertEquals(response2.getStatusCode(), 201, "User 2 should create different order");
        String orderId2 = response2.as(TestModels.OrderResponse.class).getId();
        log.info("✅ User 2 order created: {}", orderId2);

        assertNotEquals(orderId1, orderId2, "Different users should create different orders");
        assertEquals(response1.as(TestModels.OrderResponse.class).getUserId(), userId1);
        assertEquals(response2.as(TestModels.OrderResponse.class).getUserId(), userId2);

        log.info("✅ PASSED: Idempotency key is user-scoped");
        log.info("   User 1: {} → Order: {} | User 2: {} → Order: {}", userId1, orderId1, userId2, orderId2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════════

    private ServiceResponse retryServiceCall(Supplier<ServiceResponse> action) {
        return RetryHandler.executeWithRetry(action, defaultRetryConfig());
    }

    private ServiceResponse retryServiceCall(Supplier<ServiceResponse> action, RetryHandler.RetryConfig config) {
        return RetryHandler.executeWithRetry(action, config);
    }

    private RetryHandler.RetryConfig defaultRetryConfig() {
        return new RetryHandler.RetryConfig()
                .maxAttempts(3)
                .initialDelay(200)
                .retryPolicy(RetryHandler.RetryPolicy.LINEAR)
                .build();
    }


    private void deleteOrderDirectly(String orderId) {
        log.info("🗑️  Deleting order from database: {}", orderId);
        boolean deleted = DatabaseValidator.getInstance().deleteOrderById(orderId);
        if (deleted) {
            log.info("✅ Order deleted successfully from database");
        } else {
            log.warn("⚠️  Order not found in database (may have been already deleted)");
        }
        boolean exists = DatabaseValidator.getInstance().orderExistsById(orderId);
        if (exists) {
            throw new RuntimeException("Order still exists after deletion: " + orderId);
        }
        log.info("✅ Verified: Order no longer exists in database");
    }

}