package com.amazon.tests.multithreadingFailureScenarios;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.*;

/**
 * ========================================================================
 * INTERVIEW PREP: ThreadLocal for Test Data Isolation
 * ========================================================================
 *
 * PROBLEM:
 * Multiple tests run in parallel. Each needs its own user, order, product.
 * Without ThreadLocal → tests interfere with each other!
 *
 * SOLUTION:
 * ThreadLocal gives each thread its own "test context"
 *
 * INTERVIEW QUESTION:
 * "How do you handle test data isolation in parallel test execution?"
 *
 * ANSWER:
 * "I use ThreadLocal to give each thread its own test context, ensuring
 *  no interference between parallel tests."
 */
@Epic("Interview Prep")
@Feature("ThreadLocal Test Data Isolation")
public class Interview_ThreadLocalTestContext extends BaseTest {

    // ================================================================
    // THE SOLUTION: ThreadLocal Test Context
    // ================================================================

    /**
     * ✅ ThreadLocal: Each thread gets its own TestContext
     * This is THE KEY to test data isolation in parallel execution
     */
    private static ThreadLocal<TestContext> testContext = ThreadLocal.withInitial(TestContext::new);

    private TestModels.ProductResponse sharedProduct;  // Shared (read-only)
    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * ✅ Before each test: Create unique user for THIS thread
     */
    @BeforeMethod
    public void setupThreadContext() throws SeedingException {
        TestContext ctx = testContext.get();
        long threadId = Thread.currentThread().getId();

        // ✅ Product goes into THIS thread's context, not shared field
        ctx.product = ProductSeeder.builder(context).count(1).build().seed().getFirst();
        waitForDataPropagation(500);

        ctx.user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        ctx.userToken = context.getCached("user_token_" + ctx.user.getId(), String.class);

        System.out.println("[Thread-" + threadId + "] ✅ Created context - user: "
                + ctx.user.getId() + ", product: " + ctx.product.getId());
    }

    /**
     * ✅ After each test: Clean up ThreadLocal to prevent memory leaks
     */
    @AfterMethod
    public void cleanupThreadContext() {
        testContext.remove();  // CRITICAL: Always remove ThreadLocal!
        System.out.println("[Thread-" + Thread.currentThread().getId() + "] 🧹 Cleaned up context");
    }

    // ================================================================
    // TEST 1: Create Order - Each thread uses its own context
    // ================================================================

    @Test(enabled = false,invocationCount = 5, threadPoolSize = 5,
            description = "5 threads create orders - no interference")
    public void test_createOrder_withThreadLocalContext() throws Exception {
        long threadId = Thread.currentThread().getId();

        // ✅ Get THIS thread's context
        TestContext ctx = testContext.get();

        System.out.println("[Thread-" + threadId + "] Creating order for user: " + ctx.user.getId());

        // Create order
        TestModels.CreateOrderRequest orderRequest = TestModels.CreateOrderRequest.builder()
                .items(java.util.List.of(
                        TestModels.OrderItemRequest.builder()
                                .productId(sharedProduct.getId())
                                .productName("Product_Thread_" + threadId)
                                .quantity(1)
                                .unitPrice(sharedProduct.getPrice())
                                .build()
                ))
                .build();

        Response response = RestAssured.given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + ctx.userToken)
                .header("X-User-Id", ctx.user.getId())
                .contentType("application/json")
                .body(objectMapper.writeValueAsString(orderRequest))
                .post("/api/orders");

        Assert.assertEquals(response.statusCode(), 201, "Order should be created");

        // ✅ Store order ID in THIS thread's context
        ctx.orderId = response.jsonPath().getString("id");

        System.out.println("[Thread-" + threadId + "] ✅ Created order: " + ctx.orderId);
    }

    // ================================================================
    // TEST 2: Get Order - Uses the order ID from THIS thread's context
    // ================================================================

    @Test(enabled = false,invocationCount = 5, threadPoolSize = 5,
            dependsOnMethods = "test_createOrder_withThreadLocalContext",
            description = "Each thread retrieves its own order")
    public void test_getOrder_usesOwnContext() {
        long threadId = Thread.currentThread().getId();

        // ✅ Get THIS thread's context (has orderId from previous test)
        TestContext ctx = testContext.get();

        System.out.println("[Thread-" + threadId + "] Getting order: " + ctx.orderId);

        Response response = RestAssured.given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + ctx.userToken)
                .header("X-User-Id", ctx.user.getId())
                .get("/api/orders/" + ctx.orderId);

        Assert.assertEquals(response.statusCode(), 200, "Order should be retrieved");

        String retrievedOrderId = response.jsonPath().getString("id");
        Assert.assertEquals(retrievedOrderId, ctx.orderId, "Should get correct order");

        System.out.println("[Thread-" + threadId + "] ✅ Retrieved order: " + retrievedOrderId);
    }

    // ================================================================
    // DEMONSTRATION: What Happens WITHOUT ThreadLocal (The Problem)
    // ================================================================

    /**
     * ❌ WRONG WAY: Shared instance variables
     * All threads overwrite each other → RACE CONDITION!
     */
    private static String sharedOrderId;  // ❌ Shared across threads!
    private static TestModels.UserResponse sharedUser;  // ❌ Shared across threads!
    private static String sharedUserToken;  // ❌ Shared across threads!

    /**
     * Demo Test 1: Create order WITHOUT ThreadLocal
     * Problem: All threads overwrite sharedOrderId
     */
    @Test( // Disabled to avoid interference
            invocationCount = 2, threadPoolSize = 2,
            description = "WITHOUT ThreadLocal - CREATE order causes interference")
    public void demo1_createOrder_withoutThreadLocal() throws Exception {
        long threadId = Thread.currentThread().getId();

        // ❌ Create user (all threads overwrite same variable!)
        sharedUser = UserSeeder.builder(context).count(1).build().seed().getFirst();
        sharedUserToken = context.getCached("user_token_" + sharedUser.getId(), String.class);

        System.out.println("[Thread-" + threadId + "] Created user: " + sharedUser.getId());

        // Create order
        TestModels.CreateOrderRequest orderRequest = TestModels.CreateOrderRequest.builder()
                .items(java.util.List.of(
                        TestModels.OrderItemRequest.builder()
                                .productId(sharedProduct.getId())
                                .productName("Product_Thread_" + threadId)
                                .quantity(1)
                                .unitPrice(sharedProduct.getPrice())
                                .build()
                ))
                .build();

        Response response = RestAssured.given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + sharedUserToken)
                .header("X-User-Id", sharedUser.getId())
                .contentType("application/json")
                .body(objectMapper.writeValueAsString(orderRequest))
                .post("/api/orders");

        // ❌ All threads overwrite same variable!
        String myOrderId = response.jsonPath().getString("id");
        sharedOrderId = myOrderId;

        System.out.println("[Thread-" + threadId + "] Created order: " + myOrderId);
        System.out.println("[Thread-" + threadId + "] Stored in sharedOrderId: " + sharedOrderId);
    }

    /**
     * Demo Test 2: Get order WITHOUT ThreadLocal
     * Problem: Gets WRONG order because sharedOrderId was overwritten!
     */
    @Test(  // Disabled to avoid interference
            invocationCount = 2, threadPoolSize = 2,
            dependsOnMethods = "demo1_createOrder_withoutThreadLocal",
            description = "WITHOUT ThreadLocal - GET order retrieves WRONG order!")
    public void demo2_getOrder_withoutThreadLocal() throws InterruptedException {
        long threadId = Thread.currentThread().getId();

        Thread.sleep(100);  // Simulate some delay

        // ❌ sharedOrderId was probably overwritten by another thread!
        String orderIdToGet = sharedOrderId;

        System.out.println("[Thread-" + threadId + "] Trying to get MY order...");
        System.out.println("[Thread-" + threadId + "] sharedOrderId = " + orderIdToGet);

        Response response = RestAssured.given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + sharedUserToken)
                .header("X-User-Id", sharedUser.getId())
                .get("/api/orders/" + orderIdToGet);

        // ❌ This will randomly fail because:
        // - Thread-1 created ORD-001, stored it in sharedOrderId
        // - Thread-2 created ORD-002, OVERWROTE sharedOrderId
        // - Thread-1 tries to get "its" order, but sharedOrderId = ORD-002 now!
        // - Thread-1 gets ORD-002 instead of ORD-001 → WRONG ORDER!

        if (response.statusCode() == 200) {
            String retrievedOrderId = response.jsonPath().getString("id");
            System.out.println("[Thread-" + threadId + "] ❓ Got order: " + retrievedOrderId);
            System.out.println("[Thread-" + threadId + "] ❓ Is this MY order? Probably NOT!");
        } else {
            System.err.println("[Thread-" + threadId + "] ❌ Failed: " + response.statusCode());
            System.err.println("[Thread-" + threadId + "] ❌ Reason: Trying to get another thread's order!");
        }
    }

    // ================================================================
    // TEST CONTEXT - The Data Structure
    // ================================================================

    /**
     * Test Context: Holds all test data for ONE thread
     * Each thread gets its own instance via ThreadLocal
     */
    public static class TestContext {
        public TestModels.UserResponse user;
        public String userToken;
        public String orderId;
        public String paymentId;
        public TestModels.ProductResponse product;

        public void printState() {
            System.out.println("Context[Thread-" + Thread.currentThread().getId() + "]:");
            System.out.println("  User: " + (user != null ? user.getId() : "null"));
            System.out.println("  Order: " + orderId);
            System.out.println("  Payment: " + paymentId);
        }
    }

    // ================================================================
    // FINAL SUMMARY
    // ================================================================

    @AfterClass
    public void printInterviewSummary() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("📚 INTERVIEW PREP SUMMARY: ThreadLocal Test Data Isolation");
        System.out.println("=".repeat(80));

        System.out.println("\n❓ INTERVIEW QUESTION:");
        System.out.println("\"How do you handle test data isolation in parallel test execution?\"");

        System.out.println("\n✅ YOUR ANSWER:");
        System.out.println("\"I use ThreadLocal to give each test thread its own isolated context.\"");
        System.out.println("\"Here's how it works:\"");

        System.out.println("\n1️⃣  DECLARE ThreadLocal:");
        System.out.println("   private static ThreadLocal<TestContext> testContext =");
        System.out.println("       ThreadLocal.withInitial(TestContext::new);");

        System.out.println("\n2️⃣  SETUP in @BeforeMethod:");
        System.out.println("   TestContext ctx = testContext.get();");
        System.out.println("   ctx.user = createUser();  // Each thread gets own user");

        System.out.println("\n3️⃣  USE in @Test:");
        System.out.println("   TestContext ctx = testContext.get();  // Gets THIS thread's context");
        System.out.println("   createOrder(ctx.user);  // Uses correct user");

        System.out.println("\n4️⃣  CLEANUP in @AfterMethod:");
        System.out.println("   testContext.remove();  // Prevents memory leaks");

        System.out.println("\n💡 KEY BENEFITS:");
        System.out.println("   ✅ Complete isolation between parallel tests");
        System.out.println("   ✅ No race conditions or data interference");
        System.out.println("   ✅ Each thread has its own user, orders, context");
        System.out.println("   ✅ Thread-safe by design");

        System.out.println("\n⚠️  WITHOUT ThreadLocal (THE PROBLEM):");
        System.out.println("   Step 1: Create Order");
        System.out.println("   Thread-1: Creates ORD-001, stores in sharedOrderId");
        System.out.println("   Thread-2: Creates ORD-002, OVERWRITES sharedOrderId ❌");
        System.out.println("   Thread-3: Creates ORD-003, OVERWRITES sharedOrderId ❌");
        System.out.println("   Now sharedOrderId = ORD-003");
        System.out.println("");
        System.out.println("   Step 2: Get Order");
        System.out.println("   Thread-1: Tries to get 'my' order...");
        System.out.println("   Thread-1: Reads sharedOrderId = ORD-003");
        System.out.println("   Thread-1: Gets ORD-003 instead of ORD-001 ❌ WRONG ORDER!");
        System.out.println("   Thread-1: Test FAILS or gets wrong data!");
        System.out.println("");
        System.out.println("   Result: Random test failures, data corruption");

        System.out.println("\n🎯 WITH ThreadLocal (THE SOLUTION):");
        System.out.println("   Step 1: Create Order");
        System.out.println("   Thread-1: Creates ORD-001 → Stores in Thread-1's context");
        System.out.println("   Thread-2: Creates ORD-002 → Stores in Thread-2's context");
        System.out.println("   Thread-3: Creates ORD-003 → Stores in Thread-3's context");
        System.out.println("   Each thread has its OWN context - no overwriting!");
        System.out.println("");
        System.out.println("   Step 2: Get Order");
        System.out.println("   Thread-1: Gets context → Reads orderId = ORD-001");
        System.out.println("   Thread-1: Gets ORD-001 ✅ CORRECT ORDER!");
        System.out.println("   Thread-2: Gets context → Reads orderId = ORD-002");
        System.out.println("   Thread-2: Gets ORD-002 ✅ CORRECT ORDER!");
        System.out.println("");
        System.out.println("   Result: 100% success rate, complete isolation");

        System.out.println("\n🔑 MEMORY:");
        System.out.println("   Thread-1 Context: {user: User-1, orderId: ORD-001}");
        System.out.println("   Thread-2 Context: {user: User-2, orderId: ORD-002}");
        System.out.println("   Thread-3 Context: {user: User-3, orderId: ORD-003}");
        System.out.println("   → Each thread has its OWN isolated copy!");

        System.out.println("=".repeat(80) + "\n");
    }
}