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
import org.testng.Assert;
import org.testng.annotations.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ========================================================================
 * COMPLEX MULTITHREADING SCENARIO
 * ========================================================================
 *
 * Combines multiple advanced TestNG features:
 * 1. parallel="methods" - Methods run in parallel
 * 2. @DataProvider - Parameterized tests with multiple datasets
 * 3. invocationCount - Some tests repeat multiple times
 * 4. dependsOnMethods - Test dependencies
 * 5. Thread-safe data sharing between dependent tests
 *
 * CHALLENGES:
 * - How does parallel execution work with dependencies?
 * - How does DataProvider work with parallel execution?
 * - How do invocationCount and DataProvider interact?
 * - How to share data between dependent tests thread-safely?
 *
 * This demonstrates REAL-WORLD complexity in test automation!
 */
@Epic("Complex Multithreading Scenarios")
@Feature("DataProvider + Dependencies + InvocationCount")
public class ComplexMultithreadingScenario extends BaseTest {

    private TestModels.UserResponse testUser;
    private String userToken;
    private TestModels.ProductResponse testProduct;
    private ObjectMapper objectMapper = new ObjectMapper();

    // ✅ Thread-safe collections for sharing data between tests
    private static final ConcurrentHashMap<String, String> createdOrders = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, TestModels.UserResponse> createdUsers = new ConcurrentHashMap<>();

    // Metrics
    private static final AtomicInteger test1ExecutionCount = new AtomicInteger(0);
    private static final AtomicInteger test2ExecutionCount = new AtomicInteger(0);
    private static final AtomicInteger test3ExecutionCount = new AtomicInteger(0);
    private static final AtomicInteger test4ExecutionCount = new AtomicInteger(0);

    @BeforeClass
    public void setupTestData() throws SeedingException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🧩 COMPLEX MULTITHREADING SCENARIO");
        System.out.println("=".repeat(80));
        System.out.println("Features: parallel + DataProvider + invocationCount + dependencies");
        System.out.println("=".repeat(80) + "\n");

        testUser = UserSeeder.builder(context).count(1).build().seed().getFirst();
        userToken = context.getCached("user_token_" + testUser.getId(), String.class);
        testProduct = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        System.out.println("✅ Base test data ready\n");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 1: DataProvider with parallel execution
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * DataProvider: Provides 3 different user types
     * This will be called 3 times (once per data set)
     */
    @DataProvider(name = "userTypes", parallel = false)
    public Object[][] userTypeProvider() {
        return new Object[][] {
                { "BUYER", "buyer@test.com" },
                { "SELLER", "seller@test.com" },
                { "ADMIN", "admin@test.com" }
        };
    }

    /**
     * TEST 1: Create users with different types using DataProvider
     * Runs 3 times (once per data set)
     *
     * With parallel="methods": All 3 invocations can run simultaneously
     */
    @Test(dataProvider = "userTypes", priority = 1,
            description = "Create users with different roles")
    @Story("Test 1 - DataProvider with Parallel Execution")
    @Severity(SeverityLevel.CRITICAL)
    public void test1_createUsersByType(String userType, String email) throws SeedingException {
        long threadId = Thread.currentThread().getId();
        int executionNum = test1ExecutionCount.incrementAndGet();

        System.out.println("[Thread-" + threadId + "] TEST 1 [" + executionNum + "/3] Creating " +
                userType + " user: " + email);

        // Create user
        TestModels.UserResponse user = UserSeeder.builder(context)
                .count(1)
                .build()
                .seed()
                .getFirst();

        // Store in thread-safe map (will be used by dependent tests)
        createdUsers.put(userType, user);

        System.out.println("[Thread-" + threadId + "] TEST 1 [" + executionNum + "/3] ✅ Created " +
                userType + ": " + user.getId());

        Assert.assertNotNull(user.getId(), "User should be created");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 2: Depends on Test 1 + Has its own DataProvider
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * DataProvider: Provides different product categories
     */
    @DataProvider(name = "productCategories")
    public Object[][] productCategoryProvider() {
        return new Object[][] {
                { "Electronics" },
                { "Books" },
                { "Clothing" }
        };
    }

    /**
     * TEST 2: Create products using users from Test 1
     *
     * DEPENDENCY: Waits for Test 1 to complete ALL invocations
     * DataProvider: Runs 3 times (once per category)
     *
     * KEY INSIGHT: With parallel="methods" + dependsOnMethods:
     * - Test 1 runs all 3 invocations (possibly in parallel)
     * - Test 2 WAITS for ALL Test 1 invocations to finish
     * - Then Test 2 runs its 3 invocations (possibly in parallel)
     */
    @Test(dataProvider = "productCategories",
            dependsOnMethods = "test1_createUsersByType",
            priority = 2,
            description = "Create products in different categories")
    @Story("Test 2 - Dependent Test with DataProvider")
    public void test2_createProductsByCategory(String category) throws Exception {
        long threadId = Thread.currentThread().getId();
        int executionNum = test2ExecutionCount.incrementAndGet();

        System.out.println("[Thread-" + threadId + "] TEST 2 [" + executionNum + "/3] Creating product in: " +
                category);

        // Wait for users to be created (dependency should handle this, but be safe)
        int attempts = 0;
        while (createdUsers.size() < 3 && attempts < 50) {
            Thread.sleep(100);
            attempts++;
        }

        // Use SELLER user created in Test 1
        TestModels.UserResponse seller = createdUsers.get("SELLER");
        Assert.assertNotNull(seller, "SELLER user should exist from Test 1");

        String sellerToken = context.getCached("user_token_" + seller.getId(), String.class);

        // Create product
        TestModels.ProductResponse productRequest = TestModels.ProductResponse.builder()
                .name("Product_" + category + "_" + System.currentTimeMillis())
                .description("Test product in " + category)
                .price(BigDecimal.valueOf(10.00 + executionNum))
               // .category(category)
                .build();

        Response response = RestAssured.given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + sellerToken)
                .header("X-User-Id", seller.getId())
                .contentType("application/json")
                .body(objectMapper.writeValueAsString(productRequest))
                .post("/api/v1/products");

        System.out.println("[Thread-" + threadId + "] TEST 2 [" + executionNum + "/3] ✅ Product created: " +
                response.jsonPath().getString("id"));

        Assert.assertEquals(response.statusCode(), 201, "Product should be created");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 3: InvocationCount + DataProvider combination
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * DataProvider: Provides different order quantities
     */
    @DataProvider(name = "orderQuantities")
    public Object[][] orderQuantityProvider() {
        return new Object[][] {
                { 1 },
                { 5 }
        };
    }

    /**
     * TEST 3: Create orders with invocationCount AND DataProvider
     *
     * COMPLEXITY: invocationCount=3 + DataProvider with 2 datasets
     * TOTAL INVOCATIONS: 3 × 2 = 6 executions!
     *
     * Each data set (1 and 5) will be run 3 times
     * With parallel execution, all 6 can run simultaneously
     */
    @Test(dataProvider = "orderQuantities",
            invocationCount = 3,
            threadPoolSize = 3,
            dependsOnMethods = "test2_createProductsByCategory",
            priority = 3,
            description = "Create orders with varying quantities")
    @Story("Test 3 - InvocationCount + DataProvider")
    public void test3_createOrdersWithQuantity(int quantity) throws Exception {
        long threadId = Thread.currentThread().getId();
        int executionNum = test3ExecutionCount.incrementAndGet();

        System.out.println("[Thread-" + threadId + "] TEST 3 [" + executionNum + "/6] Creating order with qty: " +
                quantity);

        // Use BUYER from Test 1
        TestModels.UserResponse buyer = createdUsers.get("BUYER");
        Assert.assertNotNull(buyer, "BUYER user should exist from Test 1");

        String buyerToken = context.getCached("user_token_" + buyer.getId(), String.class);

        // Create order
        TestModels.CreateOrderRequest orderRequest = TestModels.CreateOrderRequest.builder()
                .items(List.of(
                        TestModels.OrderItemRequest.builder()
                                .productId(testProduct.getId())
                                .productName("Product_Qty_" + quantity)
                                .quantity(quantity)
                                .unitPrice(testProduct.getPrice())
                                .build()
                ))
                .build();

        Response response = RestAssured.given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + buyerToken)
                .header("X-User-Id", buyer.getId())
                .contentType("application/json")
                .body(objectMapper.writeValueAsString(orderRequest))
                .post("/api/orders");

        if (response.statusCode() == 201) {
            String orderId = response.jsonPath().getString("id");
            createdOrders.put(orderId, "Quantity: " + quantity);
            System.out.println("[Thread-" + threadId + "] TEST 3 [" + executionNum + "/6] ✅ Order: " + orderId);
        }

        Assert.assertEquals(response.statusCode(), 201, "Order should be created");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 4: Final validation test (depends on all previous)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * TEST 4: Validate all created data
     *
     * DEPENDENCY: Waits for ALL previous tests to complete
     * Runs only ONCE (no DataProvider, no invocationCount)
     */
    @Test(dependsOnMethods = {"test1_createUsersByType",
            "test2_createProductsByCategory",
            "test3_createOrdersWithQuantity"},
            priority = 4,
            description = "Validate all created test data")
    @Story("Test 4 - Final Validation")
    public void test4_validateAllData() {
        long threadId = Thread.currentThread().getId();
        test4ExecutionCount.incrementAndGet();

        System.out.println("\n[Thread-" + threadId + "] TEST 4: Validating all created data...");

        // Validate users from Test 1
        Assert.assertEquals(createdUsers.size(), 3, "Should have 3 users (BUYER, SELLER, ADMIN)");
        Assert.assertTrue(createdUsers.containsKey("BUYER"), "BUYER should exist");
        Assert.assertTrue(createdUsers.containsKey("SELLER"), "SELLER should exist");
        Assert.assertTrue(createdUsers.containsKey("ADMIN"), "ADMIN should exist");

        // Validate orders from Test 3
        Assert.assertTrue(createdOrders.size() >= 6, "Should have at least 6 orders");

        System.out.println("[Thread-" + threadId + "] TEST 4: ✅ All validations passed!");
        System.out.println("   Users created: " + createdUsers.size());
        System.out.println("   Orders created: " + createdOrders.size());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Summary and Analysis
    // ══════════════════════════════════════════════════════════════════════════

    @AfterClass
    public void printAnalysis() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("📊 EXECUTION ANALYSIS");
        System.out.println("=".repeat(80));

        System.out.println("\nExecution Counts:");
        System.out.println("  Test 1 (DataProvider: 3 datasets)         : " + test1ExecutionCount.get() + " times");
        System.out.println("  Test 2 (DataProvider: 3 datasets)         : " + test2ExecutionCount.get() + " times");
        System.out.println("  Test 3 (DataProvider: 2 × Invocation: 3) : " + test3ExecutionCount.get() + " times");
        System.out.println("  Test 4 (Single execution)                 : " + test4ExecutionCount.get() + " time");

        System.out.println("\nData Created:");
        System.out.println("  Users  : " + createdUsers.size());
        System.out.println("  Orders : " + createdOrders.size());

        System.out.println("\n" + "=".repeat(80));
        System.out.println("🎓 KEY LEARNINGS");
        System.out.println("=".repeat(80));

        System.out.println("\n1️⃣  DataProvider + parallel=\"methods\":");
        System.out.println("   - Each data set runs as separate test invocation");
        System.out.println("   - All invocations CAN run in parallel");
        System.out.println("   - 3 datasets = 3 parallel executions possible");

        System.out.println("\n2️⃣  invocationCount + DataProvider:");
        System.out.println("   - MULTIPLY: invocationCount × datasets");
        System.out.println("   - 3 invocations × 2 datasets = 6 total executions");
        System.out.println("   - Each dataset runs invocationCount times");

        System.out.println("\n3️⃣  dependsOnMethods + parallel=\"methods\":");
        System.out.println("   - Dependent test WAITS for ALL invocations of dependency");
        System.out.println("   - Test 1 (3 invocations) must ALL complete");
        System.out.println("   - Then Test 2 can start its invocations");
        System.out.println("   - Order: Test1 → Test2 → Test3 → Test4");

        System.out.println("\n4️⃣  Thread-Safe Data Sharing:");
        System.out.println("   - Use ConcurrentHashMap for shared data");
        System.out.println("   - Use AtomicInteger for counters");
        System.out.println("   - Dependent tests can safely read data");

        System.out.println("\n5️⃣  Execution Order with Dependencies:");
        System.out.println("   - priority is IGNORED within dependent chain");
        System.out.println("   - Dependencies create execution order");
        System.out.println("   - parallel=\"methods\" only affects non-dependent tests");

        System.out.println("=".repeat(80) + "\n");
    }
}
