package com.amazon.tests.tests.order;

import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.seeders.OrderSeeder;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import io.restassured.specification.RequestSpecification;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.testng.Assert.*;

/**
 * End-to-End Order Creation Flow Tests
 * Tests complete user journey: Registration → Browse Products → Create Order
 *
 * Uses SEEDERS because we need real data in the system via API calls
 */
@Slf4j
public class OrderCreationFlowTest extends BaseTest {

    // ==========================================
    // SCENARIO 1: Happy Path - Single User, Single Product, Single Order
    // ==========================================

    @Test(description = "User registers, views product, and places single-item order")
    public void testBasicOrderCreationFlow() throws Exception {
        log.info("=== Scenario 1: Basic Order Creation ===");

        // Step 1: Register new user
        UserSeeder userSeeder = UserSeeder.builder(context)
                .count(1)
                .build();
        UserSeeder.UserSeedResult userResult = userSeeder.seed();
        TestModels.UserResponse user = userResult.getFirst();

        log.info("✓ User registered: {} ({})", user.getEmail(), user.getId());

        // Step 2: Create product
        ProductSeeder productSeeder = ProductSeeder.builder(context)
                .count(1)
                .mediumPrice()  // $20-$100
                .highStock()    // 100-1000 units
                .build();
        ProductSeeder.ProductSeedResult productResult = productSeeder.seed();
        TestModels.ProductResponse product = productResult.getFirst();

        log.info("✓ Product created: {} - ${}", product.getName(), product.getPrice());

        // Step 3: Wait for eventual consistency
        waitForDataPropagation(1000);

        // Step 4: Create order
        OrderSeeder orderSeeder = OrderSeeder.builder(context)
                .forUser(user)
                .withProducts(productResult.getProducts())
                .count(1)
                .itemsPerOrder(1, 1)  // Exactly 1 item
                .build();
        OrderSeeder.OrderSeedResult orderResult = orderSeeder.seed();
        TestModels.OrderResponse order = orderResult.getFirst();

        log.info("✓ Order created: {} with {} items", order.getId(), order.getItems().size());

        // Verify order details
        assertNotNull(order.getId(), "Order ID should be generated");
        assertEquals(order.getUserId(), user.getId(), "Order should belong to user");
        assertEquals(order.getStatus(), "PENDING", "Order should be in PENDING status");
        assertEquals(order.getItems().size(), 1, "Order should have 1 item");
        assertNotNull(order.getTotalAmount(), "Order should have total amount");
        assertTrue(order.getTotalAmount().compareTo(BigDecimal.ZERO) > 0, "Total amount should be > 0");

        log.info("✅ Scenario 1 PASSED: Order total = ${}", order.getTotalAmount());
    }

    // ==========================================
    // SCENARIO 2: Multi-Item Order
    // ==========================================

    @Test(description = "User places order with multiple different products")
    public void testMultiItemOrderCreation() throws Exception {
        log.info("=== Scenario 2: Multi-Item Order ===");

        // Step 1: Register user
        TestModels.UserResponse user = UserSeeder.builder(context)
                .count(1)
                .build()
                .seed()
                .getFirst();

        log.info("✓ User registered: {}", user.getEmail());

        // Step 2: Create 5 products in different price ranges
        ProductSeeder productSeeder = ProductSeeder.builder(context)
                .count(5)
                .parallel()
                .build();
        ProductSeeder.ProductSeedResult productResult = productSeeder.seed();

        log.info("✓ Created {} products", productResult.getCount());

        waitForDataPropagation(1000);

        // Step 3: Create order with 3-5 items
        OrderSeeder orderSeeder = OrderSeeder.builder(context)
                .forUser(user)
                .withProducts(productResult.getProducts())
                .count(1)
                .itemsPerOrder(3, 5)  // 3-5 items
                .build();
        TestModels.OrderResponse order = orderSeeder.seed().getFirst();

        log.info("✓ Order created with {} items", order.getItems().size());

        // Verify multi-item order
        assertTrue(order.getItems().size() >= 3 && order.getItems().size() <= 5,
                "Order should have 3-5 items");

        // Verify each item
        for (TestModels.OrderItemResponse item : order.getItems()) {
            assertNotNull(item.getProductId(), "Each item should have product ID");
            assertNotNull(item.getProductName(), "Each item should have product name");
            assertTrue(item.getQuantity() > 0, "Each item should have quantity > 0");
            assertTrue(item.getUnitPrice().compareTo(BigDecimal.ZERO) > 0,
                    "Each item should have unit price > 0");
        }

        log.info("✅ Scenario 2 PASSED: {} items, Total = ${}",
                order.getItems().size(), order.getTotalAmount());
    }

    // ==========================================
    // SCENARIO 3: Multiple Users, Multiple Orders
    // ==========================================

    @Test(description = "Multiple users each placing their own orders")
    public void testMultipleUsersOrdering() throws Exception {
        log.info("=== Scenario 3: Multiple Users Ordering ===");

        // Step 1: Create 3 users in parallel
        UserSeeder userSeeder = UserSeeder.builder(context)
                .count(3)
                .parallel()
                .build();
        UserSeeder.UserSeedResult userResult = userSeeder.seed();

        log.info("✓ Created {} users", userResult.getCount());

        // Step 2: Create 10 products (shared catalog)
        ProductSeeder productSeeder = ProductSeeder.builder(context)
                .count(10)
                .parallel()
                .build();
        ProductSeeder.ProductSeedResult productResult = productSeeder.seed();

        log.info("✓ Created {} products", productResult.getCount());

        waitForDataPropagation(2000);

        // Step 3: Each user places 1-2 orders
        List<TestModels.OrderResponse> allOrders = new ArrayList<>();

        for (TestModels.UserResponse user : userResult.getUsers()) {
            int orderCount = new Random().nextInt(2) + 1; // 1-2 orders

            OrderSeeder orderSeeder = OrderSeeder.builder(context)
                    .forUser(user)
                    .withProducts(productResult.getProducts())
                    .count(orderCount)
                    .itemsPerOrder(1, 3)
                    .build();

            OrderSeeder.OrderSeedResult orderResult = orderSeeder.seed();
            allOrders.addAll(orderResult.getOrders());

            log.info("✓ User {} placed {} orders", user.getEmail(), orderCount);
        }

        // Verify
        assertEquals(userResult.getCount(), 3, "Should have 3 users");
        assertTrue(allOrders.size() >= 3 && allOrders.size() <= 6,
                "Should have 3-6 total orders");

        // Verify each order belongs to correct user
        for (TestModels.OrderResponse order : allOrders) {
            boolean belongsToUser = userResult.getUsers().stream()
                    .anyMatch(u -> u.getId().equals(order.getUserId()));
            assertTrue(belongsToUser, "Order should belong to one of the users");
        }

        log.info("✅ Scenario 3 PASSED: {} users, {} orders",
                userResult.getCount(), allOrders.size());
    }

    // ==========================================
    // SCENARIO 4: Specific Product Categories
    // ==========================================

    @Test(description = "User orders products from specific categories")
    public void testOrderWithSpecificCategories() throws Exception {
        log.info("=== Scenario 4: Category-Specific Order ===");

        // Step 1: Create user
        TestModels.UserResponse user = UserSeeder.builder(context)
                .count(1)
                .build()
                .seed()
                .getFirst();

        // Step 2: Create cheap and expensive products
        ProductSeeder cheapSeeder = ProductSeeder.builder(context)
                .count(3)
                .cheap()    // $1-$20
                .highStock()
                .build();

        ProductSeeder expensiveSeeder = ProductSeeder.builder(context)
                .count(2)
                .expensive()  // $100-$1000
                .build();

        List<TestModels.ProductResponse> cheapProducts = cheapSeeder.seed().getProducts();
        List<TestModels.ProductResponse> expensiveProducts = expensiveSeeder.seed().getProducts();

        log.info("✓ Created {} cheap products", cheapProducts.size());
        log.info("✓ Created {} expensive products", expensiveProducts.size());

        waitForDataPropagation(1000);

        // Step 3: Order 1 - Only cheap products
        List<TestModels.ProductResponse> allProducts = new ArrayList<>();
        allProducts.addAll(cheapProducts);
        allProducts.addAll(expensiveProducts);

        OrderSeeder orderSeeder = OrderSeeder.builder(context)
                .forUser(user)
                .withProducts(allProducts)
                .count(1)
                .itemsPerOrder(2, 4)
                .build();

        TestModels.OrderResponse order = orderSeeder.seed().getFirst();

        log.info("✓ Order created with {} items, Total: ${}",
                order.getItems().size(), order.getTotalAmount());

        // Verify order details
        assertTrue(order.getItems().size() >= 2, "Should have at least 2 items");
        assertNotNull(order.getTotalAmount());

        log.info("✅ Scenario 4 PASSED");
    }

    // ==========================================
    // SCENARIO 5: Bulk Order (High Quantity)
    // ==========================================

    @Test(description = "User places bulk order with high quantities")
    public void testBulkOrderCreation() throws Exception {
        log.info("=== Scenario 5: Bulk Order ===");

        // Step 1: Create user
        TestModels.UserResponse user = UserSeeder.builder(context)
                .withConfig(builder -> builder
                        .withFirstName("Bulk")
                        .withLastName("Buyer"))
                .build()
                .seed()
                .getFirst();

        log.info("✓ User registered: {} {}", user.getFirstName(), user.getLastName());

        // Step 2: Create products with very high stock
        ProductSeeder productSeeder = ProductSeeder.builder(context)
                .count(2)
                .cheap()
                .highStock()  // 100-1000 units
                .build();

        ProductSeeder.ProductSeedResult productResult = productSeeder.seed();

        waitForDataPropagation(1000);

        // Step 3: Create bulk order using OrderBuilder directly for custom quantities
        TestModels.ProductResponse product1 = productResult.getProducts().get(0);
        TestModels.ProductResponse product2 = productResult.getProducts().get(1);

        TestModels.CreateOrderRequest bulkOrderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product1, 50)   // 50 units
                .addItem(product2, 75)   // 75 units
                .withNotes("Bulk order for wholesale")
                .build();

        // Get user token and create order via API
        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        RequestSpecification spec = context.getRestAssuredConfig().getOrderServiceSpec(userToken);

        TestModels.OrderResponse order = context.getRestClient().post(
                "/api/orders",
                spec,
                bulkOrderRequest,
                TestModels.OrderResponse.class
        );

        // Track for cleanup
        context.registerCleanup("Bulk Order: " + order.getId(), () -> {
            context.getRestClient().delete("/api/orders/" + order.getId(), spec);
        });

        log.info("✓ Bulk order created: {} items", order.getItems().size());

        // Verify bulk order
        assertEquals(order.getItems().size(), 2, "Should have 2 items");

        int totalQuantity = order.getItems().stream()
                .mapToInt(TestModels.OrderItemResponse::getQuantity)
                .sum();

        assertEquals(totalQuantity, 125, "Total quantity should be 125 units");

        log.info("✅ Scenario 5 PASSED: Total quantity = {}", totalQuantity);
    }

    // ==========================================
    // SCENARIO 6: Custom User Details + Order
    // ==========================================

    @Test(description = "User with custom details places order")
    public void testOrderWithCustomUserDetails() throws Exception {
        log.info("=== Scenario 6: Custom User Order ===");

        // Step 1: Create user with custom details
        UserSeeder userSeeder = UserSeeder.builder(context)
                .withConfig(builder -> builder
                        .withFirstName("John")
                        .withLastName("Doe")
                        .withEmail("john.doe@custom.com"))
                .build();

        TestModels.UserResponse user = userSeeder.seed().getFirst();

        log.info("✓ User registered: {} {}, Email: {}",
                user.getFirstName(), user.getLastName(), user.getEmail());

        // Verify custom details
        assertEquals(user.getFirstName(), "John");
        assertEquals(user.getLastName(), "Doe");
        assertTrue(user.getEmail().contains("john.doe"));

        // Step 2: Create products
        ProductSeeder productSeeder = ProductSeeder.builder(context)
                .count(3)
                .mediumPrice()
                .build();

        ProductSeeder.ProductSeedResult productResult = productSeeder.seed();

        waitForDataPropagation(1000);

        // Step 3: Create order
        OrderSeeder orderSeeder = OrderSeeder.builder(context)
                .forUser(user)
                .withProducts(productResult.getProducts())
                .count(1)
                .build();

        TestModels.OrderResponse order = orderSeeder.seed().getFirst();

        // Verify
        assertEquals(order.getUserId(), user.getId());

        log.info("✅ Scenario 6 PASSED: Custom user successfully placed order");
    }

    // ==========================================
    // SCENARIO 7: Sequential Orders for Same User
    // ==========================================

    @Test(description = "Same user places multiple orders over time")
    public void testMultipleOrdersForSameUser() throws Exception {
        log.info("=== Scenario 7: Sequential Orders ===");

        // Step 1: Create user
        TestModels.UserResponse user = UserSeeder.builder(context)
                .count(1)
                .build()
                .seed()
                .getFirst();

        // Step 2: Create product catalog
        ProductSeeder productSeeder = ProductSeeder.builder(context)
                .count(10)
                .parallel()
                .build();

        List<TestModels.ProductResponse> products = productSeeder.seed().getProducts();

        waitForDataPropagation(1000);

        // Step 3: Place 3 separate orders
        List<TestModels.OrderResponse> userOrders = new ArrayList<>();

        for (int i = 1; i <= 3; i++) {
            OrderSeeder orderSeeder = OrderSeeder.builder(context)
                    .forUser(user)
                    .withProducts(products)
                    .count(1)
                    .itemsPerOrder(1, 3)
                    .build();

            TestModels.OrderResponse order = orderSeeder.seed().getFirst();
            userOrders.add(order);

            log.info("✓ Order {} created: {} items", i, order.getItems().size());

            // Small delay between orders
            Thread.sleep(500);
        }

        // Verify all orders belong to same user
        assertEquals(userOrders.size(), 3, "Should have 3 orders");

        for (TestModels.OrderResponse order : userOrders) {
            assertEquals(order.getUserId(), user.getId(),
                    "All orders should belong to same user");
        }

        log.info("✅ Scenario 7 PASSED: User placed {} orders", userOrders.size());
    }

    // ==========================================
    // SCENARIO 8: Complete E2E Flow with Verification
    // ==========================================

    @Test(description = "Complete order flow with full verification")
    public void testCompleteOrderFlowWithVerification() throws Exception {
        log.info("=== Scenario 8: Complete Flow with Verification ===");

        // Step 1: Register user
        UserSeeder userSeeder = UserSeeder.builder(context)
                .count(1)
                .build();
        TestModels.UserResponse user = userSeeder.seed().getFirst();

        log.info("✓ Step 1: User registered");

        // Step 2: Create products
        ProductSeeder productSeeder = ProductSeeder.builder(context)
                .count(5)
                .build();
        List<TestModels.ProductResponse> products = productSeeder.seed().getProducts();

        log.info("✓ Step 2: Products created");

        // Step 3: Verify products are available
        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        RequestSpecification spec = context.getRestAssuredConfig().getBaseSpec();

        TestModels.PagedProductResponse productPage = context.getRestClient().get(
                "/api/products",
                spec,
                Map.of("page", 0, "size", 20),
                TestModels.PagedProductResponse.class
        );

        assertTrue(productPage.getProducts().size() >= 5,
                "Product catalog should have at least 5 products");

        log.info("✓ Step 3: Products verified in catalog");

        waitForDataPropagation(1000);

        // Step 4: Create order
        OrderSeeder orderSeeder = OrderSeeder.builder(context)
                .forUser(user)
                .withProducts(products)
                .count(1)
                .itemsPerOrder(2, 3)
                .build();

        TestModels.OrderResponse order = orderSeeder.seed().getFirst();

        log.info("✓ Step 4: Order created");

        // Step 5: Verify order details
        assertNotNull(order.getId());
        assertEquals(order.getUserId(), user.getId());
        assertEquals(order.getStatus(), "PENDING");
        assertNotNull(order.getShippingAddress());
        assertNotNull(order.getCreatedAt());

        // Calculate expected total
        BigDecimal expectedTotal = order.getItems().stream()
                .map(item -> item.getUnitPrice()
                        .multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(order.getTotalAmount().compareTo(expectedTotal), 0,
                "Total amount should match sum of items");

        log.info("✓ Step 5: Order details verified");

        log.info("✅ Scenario 8 PASSED: Complete flow verified");
        log.info("   User: {}", user.getEmail());
        log.info("   Order: {}", order.getId());
        log.info("   Items: {}", order.getItems().size());
        log.info("   Total: ${}", order.getTotalAmount());
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

   /* private void waitForDataPropagation(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }*/
}