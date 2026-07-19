package com.amazon.tests.regression.orderCreationFlow;

import com.amazon.tests.BaseTest;
import com.amazon.tests.commonmodels.enums.ProductType;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.seeders.OrderSeeder;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.validators.OrderValidator;
import com.amazon.tests.validators.ProductValidator;
import com.amazon.tests.validators.PurchaseValidator;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.restassured.specification.RequestSpecification;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * End-to-End Order Creation Flow Tests
 * Tests complete user journey: Registration → Browse Products → Create Order
 *
 * Uses SEEDERS because we need real data in the system via API calls
 */
@Slf4j
public class OrderCreationFlowTest extends BaseTest {
    PurchaseValidator purchaseValidator=new PurchaseValidator();
    ProductValidator productValidator=new ProductValidator();
    OrderValidator orderValidator=new OrderValidator();

    // ==========================================
    // SCENARIO 1: Happy Path - Single User, Single Product, Single Order
    // ==========================================

    @Test(description = "User registers, views product, and places single-item order")
    public void testBasicOrderCreationFlow() throws Exception {
        log.info("=== Scenario 1: Basic Order Creation ===");
        PurchaseResult purchase = PurchaseWorkflow.start()
                    .registerCustomer()
                    .loginCustomer()
                    .registerSeller()
                    .createProduct(1)
                    .browseProducts()
                    .viewProduct()
                    .createOrder()
                    .execute();

        logStep("Validating Purchase Workflow");

        purchaseValidator.verifyPurchaseCompleted(purchase);

        logStep("✅ Basic Order Creation completed successfully!");

    }

    // ==========================================
    // SCENARIO 2: Multi-Item Order
    // ==========================================

    @Test(description = "User places order with multiple different products")
    public void testMultiItemOrderCreation() {

        logStep("Executing Multi Item Purchase Workflow");

        PurchaseResult purchase = PurchaseWorkflow.start()
                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProduct(5)
                .browseProducts()
                .viewProduct()
                .createOrder()
                .execute();

        logStep("Validating Multi Item Purchase");

        purchaseValidator.verifyMultiItemPurchaseCompleted(
                purchase,
                3,
                5);

        logStep("✅ Multi Item Purchase completed successfully!");
    }
    // ==========================================
    // SCENARIO 3: Multiple Users, Multiple Orders
    // ==========================================

    @Test(description = "Multiple users each placing their own orders")
    public void testMultipleUsersOrdering() {

        logStep("Executing Multiple User Purchase Flows");

        List<PurchaseResult> purchases = new ArrayList<>();

        for (int i = 0; i < 3; i++) {

            PurchaseResult purchase = PurchaseWorkflow.start()
                    .registerCustomer()
                    .loginCustomer()
                    .registerSeller()
                    .createProduct(3)
                    .browseProducts()
                    .createOrder()
                    .execute();

            purchases.add(purchase);
        }

        logStep("Validating Purchase Flows");

        purchases.forEach(purchaseValidator::verifyPurchaseCompleted);

        assertEquals(purchases.size(), 3,
                "Three users should have completed purchases");

        logStep("✅ Multiple User Purchase Flow completed successfully!");
    }

    // ==========================================
    // SCENARIO 4: Specific Product Categories
    // ==========================================

    @Test(description = "User orders products from specific categories")
    public void testOrderWithSpecificCategories() {

        logStep("Executing Category Specific Purchase Workflow");

        PurchaseResult purchase = PurchaseWorkflow.start()
                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProducts(3, ProductType.CHEAP)
                .createProducts(2, ProductType.EXPENSIVE)
                .browseProducts()
                .createOrder()
                .execute();

        logStep("Validating Category Specific Purchase");

        purchaseValidator.verifyPurchaseCompleted(purchase);

        orderValidator.verifyMinimumItems(
                purchase.getOrder(), 2);

        logStep("✅ Category Specific Purchase completed successfully!");
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


}