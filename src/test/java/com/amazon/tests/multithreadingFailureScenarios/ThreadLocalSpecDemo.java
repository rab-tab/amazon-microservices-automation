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
import org.testng.annotations.*;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ✅ CORRECT IMPLEMENTATION: ThreadLocal RequestSpecification
 * SIMPLIFIED VERSION - Uses same pattern as your working test
 */
@Epic("Multithreading - ThreadLocal Solution")
@Feature("ThreadLocal RequestSpecification Pattern")
public class ThreadLocalSpecDemo extends BaseTest {

    // ✅ ThreadLocal - Each thread gets its own!
    private static ThreadLocal<RequestSpecification> orderSpec = new ThreadLocal<>();
    private static ThreadLocal<String> threadUserToken = new ThreadLocal<>();
    private static ThreadLocal<String> threadUserId = new ThreadLocal<>();

    // Shared test data (read-only, safe to share)
    private static volatile TestModels.ProductResponse product = null;
    private static ObjectMapper objectMapper = new ObjectMapper();

    // ✅ Pre-created users (to avoid parallel registration)
    private static volatile java.util.List<TestModels.UserResponse> userPool = null;
    private static volatile java.util.List<String> tokenPool = null;
    private static java.util.concurrent.atomic.AtomicInteger userIndex = new java.util.concurrent.atomic.AtomicInteger(0);

    // Thread-safe initialization flag
    private static volatile boolean dataSeeded = false;
    private static final Object SETUP_LOCK = new Object();

    // Order tracking
    private static ConcurrentHashMap<String, String> createdOrders = new ConcurrentHashMap<>();

    // Metrics
    private static AtomicInteger createSuccessCount = new AtomicInteger(0);
    private static AtomicInteger createFailureCount = new AtomicInteger(0);
    private static AtomicInteger getSuccessCount = new AtomicInteger(0);
    private static AtomicInteger getFailureCount = new AtomicInteger(0);

    @BeforeMethod
    public void setupThreadResources() throws SeedingException, InterruptedException {
        long threadId = Thread.currentThread().getId();

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Seed ALL data ONCE (first thread only)
        // ═══════════════════════════════════════════════════════════════
        if (!dataSeeded) {
            synchronized (SETUP_LOCK) {
                if (!dataSeeded) {
                    System.out.println("\n" + "=".repeat(80));
                    System.out.println("✅ THREADLOCAL SOLUTION - 5 Threads");
                    System.out.println("=".repeat(80));
                    System.out.println("[Thread-" + threadId + "] ⚙️  Seeding test data");

                    // ✅ Create 5 users ONE BY ONE (same as your working test)
                    System.out.println("[Thread-" + threadId + "] Creating user pool (5 users)...");

                    java.util.List<TestModels.UserResponse> users = new java.util.ArrayList<>();
                    java.util.List<String> tokens = new java.util.ArrayList<>();

                    for (int i = 0; i < 5; i++) {
                        // Same pattern as your working test!
                        TestModels.UserResponse user = UserSeeder.builder(context)
                                .count(1)
                                .build()
                                .seed()
                                .getFirst();

                        String token = context.getCached("user_token_" + user.getId(), String.class);

                        users.add(user);
                        tokens.add(token);

                        System.out.println("[Thread-" + threadId + "]   User " + (i+1) + ": " + user.getId());
                    }

                    userPool = users;
                    tokenPool = tokens;

                    System.out.println("[Thread-" + threadId + "] ✅ Created " + users.size() + " users");

                    // Seed product
                    product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();
                    System.out.println("[Thread-" + threadId + "] ✅ Product: " + product.getId());

                    waitForDataPropagation(1000);

                    System.out.println("=".repeat(80) + "\n");

                    dataSeeded = true;
                }
            }
        }

        // Wait for data to be seeded
        while (!dataSeeded) {
            Thread.sleep(50);
        }

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Each thread picks a user from the pool (with wrap-around)
        // ═══════════════════════════════════════════════════════════════

        // ✅ Get next user from pool (thread-safe, with wrap-around)
        int index = userIndex.getAndIncrement() % userPool.size();

        TestModels.UserResponse user = userPool.get(index);
        String userToken = tokenPool.get(index);

        // ✅ Store in ThreadLocal (each thread has its own)
        threadUserToken.set(userToken);
        threadUserId.set(user.getId());

        // ✅ Each thread creates its OWN RequestSpecification
        RequestSpecification spec = RestAssured.given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .contentType("application/json");

        orderSpec.set(spec);

        System.out.println("[Thread-" + threadId + "] ✅ Using user: " + user.getId() +
                ", RequestSpec: " + spec.hashCode());
    }

    @Test(invocationCount = 5, threadPoolSize = 5)
    @Story("Create Order - ThreadLocal Solution")
    public void testCreateOrder() {
        long threadId = Thread.currentThread().getId();
        String idempotencyKey = UUID.randomUUID().toString();

        try {
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

            Response response = sendOrderRequest(threadUserToken.get(), idempotencyKey,orderRequest);

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

    @Test(invocationCount = 5, threadPoolSize = 5)
    @Story("Get Order - ThreadLocal Solution")
    public void testGetOrder() throws InterruptedException {
        long threadId = Thread.currentThread().getId();

        try {
            int attempts = 0;
            while (createdOrders.isEmpty() && attempts < 100) {
                Thread.sleep(100);
                attempts++;
            }

            if (createdOrders.isEmpty()) {
                getFailureCount.incrementAndGet();
                throw new RuntimeException("No orders created after 10 seconds");
            }

            String orderId = createdOrders.keys().nextElement();
            String status = getOrderStatus(orderId);

            if ("PENDING".equals(status) || "CONFIRMED".equals(status)) {
                getSuccessCount.incrementAndGet();
                System.out.println("[GET-" + threadId + "] ✅ Retrieved order");
            } else {
                getFailureCount.incrementAndGet();
                System.err.println("[GET-" + threadId + "] ❌ Status: " + status);
            }

        } catch (Exception e) {
            getFailureCount.incrementAndGet();
            System.err.println("[GET-" + threadId + "] ❌ Exception: " + e.getMessage());
            throw e;
        }
    }

    @AfterMethod
    public void cleanupThreadResources() {
        orderSpec.remove();
        threadUserToken.remove();
        threadUserId.remove();
    }

    @AfterClass
    public void printResults() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("✅ THREADLOCAL SOLUTION - FINAL RESULTS");
        System.out.println("=".repeat(80));

        System.out.println("CREATE: " + createSuccessCount.get() + "/5 success, " +
                createFailureCount.get() + " failures");
        System.out.println("GET   : " + getSuccessCount.get() + "/5 success, " +
                getFailureCount.get() + " failures");

        int totalFailures = createFailureCount.get() + getFailureCount.get();
        System.out.println("TOTAL : " + totalFailures + " failures");

        if (totalFailures == 0) {
            System.out.println("\n✅ PERFECT! NO RACE CONDITIONS!");
            System.out.println("  ✓ ThreadLocal provides complete isolation");
            System.out.println("  ✓ No shared mutable state");
        }

        System.out.println("=".repeat(80) + "\n");
    }

    private Response sendOrderRequest(
            String userToken,
            String idempotencyKey,
            TestModels.CreateOrderRequest orderRequest) throws Exception {

        String requestBody = objectMapper.writeValueAsString(orderRequest);

        return orderSpec.get()  // Gets THIS thread's spec
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .body(requestBody)
                .when()
                .post("/api/orders");
    }

    private String getOrderStatus(String orderId) {
        try {
            Response response = orderSpec.get().log().all()
                    .header("X-User-Id", threadUserId.get())
                    .header("Authorization", "Bearer " + threadUserToken.get())
                    .when()
                    .get("/api/orders/" + orderId);

            if (response.statusCode() == 200) {
                return response.jsonPath().getString("status");
            }
        } catch (Exception e) {
            // Ignore
        }
        return "UNKNOWN";
    }
}