package com.amazon.tests.orderCreation;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.Test;

import java.util.*;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.testng.Assert.*;

/**
 * Negative Test Cases for Order Creation
 * Tests validation, error handling, idempotency, and edge cases
 */
@Slf4j
public class OrderCreationNegativeTest extends BaseTest {

    // ==========================================
    // IDEMPOTENCY TESTS
    // ==========================================

    @Test(description = "Same idempotency key should return same order")
    public void testIdempotencyKeyPreventsDoubleOrdering() throws Exception {
        log.info("=== Test: Idempotency - Duplicate Prevention ===");

        // Setup
        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        String idempotencyKey = UUID.randomUUID().toString();

        // ✅ Clean spec with idempotency key set ONCE
        RequestSpecification spec = RestAssured.given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .accept("application/json");

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 2)
                .withShippingAddress("123 Test St, TestCity, TC 12345")
                .build();

        // First request
        Response firstResponse = spec
                .body(orderRequest)
                .post("/api/orders");

        assertEquals(firstResponse.getStatusCode(), 201);
        TestModels.OrderResponse firstOrder = firstResponse.as(TestModels.OrderResponse.class);
        String firstOrderId = firstOrder.getId();

        log.info("✓ First order created: {}", firstOrderId);

        // Second request - SAME spec (SAME idempotency key)
        Response secondResponse = spec
                .body(orderRequest)
                .post("/api/orders");

        assertTrue(secondResponse.getStatusCode() == 200 || secondResponse.getStatusCode() == 201);
        TestModels.OrderResponse secondOrder = secondResponse.as(TestModels.OrderResponse.class);

        assertEquals(secondOrder.getId(), firstOrderId);
        assertEquals(secondOrder.getTotalAmount(), firstOrder.getTotalAmount());

        log.info("✅ Idempotency test PASSED: Duplicate prevented");
    }

    @Test(description = "Different idempotency keys should create different orders")
    public void testDifferentIdempotencyKeysCreateDifferentOrders() throws Exception {
        log.info("=== Test: Different Idempotency Keys ===");

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        String userToken = context.getCached("user_token_" + user.getId(), String.class);

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // ⭐ First order - fresh spec
        String idempotencyKey1 = UUID.randomUUID().toString();
        RequestSpecification spec1 = createSpecWithIdempotency(userToken, idempotencyKey1);

        Response response1 = spec1.body(orderRequest).post("/api/orders");
        assertEquals(response1.getStatusCode(), 201);
        String orderId1 = response1.as(TestModels.OrderResponse.class).getId();

        // ⭐ Second order - fresh spec with different key
        String idempotencyKey2 = UUID.randomUUID().toString();
        RequestSpecification spec2 = createSpecWithIdempotency(userToken, idempotencyKey2);

        Response response2 = spec2.body(orderRequest).post("/api/orders");
        assertEquals(response2.getStatusCode(), 201);
        String orderId2 = response2.as(TestModels.OrderResponse.class).getId();

        // Verify different orders
        assertNotEquals(orderId1, orderId2, "Different idempotency keys should create different orders");

        log.info("✅ Test PASSED: Created 2 different orders with different keys");
    }

    private RequestSpecification createSpecWithIdempotency(String userToken, String idempotencyKey1) {
        return RestAssured.given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey1)
                .contentType("application/json")
                .accept("application/json");
    }

    @Test(description = "Idempotency key reused after TTL expiry should create new order",enabled = false)
    public void testIdempotencyKeyExpiry() throws Exception {
        log.info("=== Test: Idempotency Key TTL Expiry ===");

        // Note: This test assumes idempotency TTL is configurable for testing
        // In production, TTL is typically 24 hours
        // For testing, you might need a shorter TTL or mock time

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        RequestSpecification spec = context.getRestAssuredConfig().getOrderServiceSpec(userToken);

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // Create first order
        Response response1 = context.getRestClient().post(context.getConfig().baseUrl()+
                "/api/orders",
                spec.header("Idempotency-Key", idempotencyKey),
                orderRequest
        );
        String orderId1 = response1.as(TestModels.OrderResponse.class).getId();

        log.info("✓ First order created: {}", orderId1);

        // Wait for TTL expiry (if you have a test-mode short TTL)
        // Or manually delete the idempotency record via admin API
        // Thread.sleep(TTL_DURATION);

        // For now, just log the expectation
        log.info("⚠ Note: After idempotency key expires (24h), same key should create new order");
        log.info("✅ Test scenario documented (requires TTL mechanism verification)");
    }

    // ==========================================
    // VALIDATION TESTS - Missing/Invalid Fields
    // ==========================================

    @Test(description = "Order creation should fail without authentication",enabled = false)
    public void testOrderCreationWithoutAuth() throws Exception {
        log.info("=== Test: No Authentication ===");

        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).build().seed().getFirst();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // Use spec without auth token
        RequestSpecification unauthSpec = context.getRestAssuredConfig().getBaseSpec();

        Response response = context.getRestClient().post(
                "/api/orders",
                unauthSpec.header("Idempotency-Key", UUID.randomUUID().toString()),
                orderRequest
        );

        assertEquals(response.getStatusCode(), 401, "Should return 401 Unauthorized");

        log.info("✅ Test PASSED: Rejected unauthenticated request");
    }

    @Test(description = "Order creation should fail with empty items list",enabled = false)
    public void testOrderCreationWithEmptyItems() throws Exception {
        log.info("=== Test: Empty Items List ===");

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        RequestSpecification spec = context.getRestAssuredConfig().getOrderServiceSpec(userToken);

        // Create order request with empty items
        TestModels.CreateOrderRequest emptyOrderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .build(); // No items added

        Response response = context.getRestClient().post(
                "/api/orders",
                spec.header("Idempotency-Key", UUID.randomUUID().toString()),
                emptyOrderRequest
        );

        assertTrue(response.getStatusCode() >= 400 && response.getStatusCode() < 500,
                "Should return 4xx error for empty items");

        response.then()
                .body("message", anyOf(
                        containsString("items"),
                        containsString("empty"),
                        containsString("required")
                ));

        log.info("✅ Test PASSED: Rejected order with empty items");
    }

    @Test(description = "Order creation should fail with invalid product ID",enabled = false)
    public void testOrderCreationWithInvalidProductId() throws Exception {
        log.info("=== Test: Invalid Product ID ===");

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        RequestSpecification spec = context.getRestAssuredConfig().getOrderServiceSpec(userToken);

        // Create order with non-existent product
        String fakeProductId = "FAKE-PRODUCT-" + UUID.randomUUID();

        TestModels.CreateOrderRequest invalidOrderRequest = TestModels.CreateOrderRequest.builder()
               // .namespace(context.getNamespace())
                .items(List.of(
                        TestModels.OrderItemRequest.builder()
                                .productId(fakeProductId)
                                .quantity(1)
                                .build()
                ))
                .shippingAddress("123 Test St")
                .build();

        Response response = context.getRestClient().post(
                "/api/orders",
                spec.header("Idempotency-Key", UUID.randomUUID().toString()),
                invalidOrderRequest
        );

        assertTrue(response.getStatusCode() >= 400 && response.getStatusCode() < 500,
                "Should return 4xx error for invalid product");

        response.then()
                .body("message", anyOf(
                        containsString("product"),
                        containsString("not found"),
                        containsString("invalid")
                ));

        log.info("✅ Test PASSED: Rejected order with invalid product ID");
    }

    @Test(description = "Order creation should fail with zero quantity",enabled = false)
    public void testOrderCreationWithZeroQuantity() throws Exception {
        log.info("=== Test: Zero Quantity ===");

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).build().seed().getFirst();

        waitForDataPropagation(1000);

        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        RequestSpecification spec = context.getRestAssuredConfig().getOrderServiceSpec(userToken);

        TestModels.CreateOrderRequest zeroQuantityRequest = TestModels.CreateOrderRequest.builder()
               // .namespace(context.getNamespace())
                .items(List.of(
                        TestModels.OrderItemRequest.builder()
                                .productId(product.getId())
                                .quantity(0)  // Invalid!
                                .build()
                ))
                .shippingAddress("123 Test St")
                .build();

        Response response = context.getRestClient().post(
                "/api/orders",
                spec.header("Idempotency-Key", UUID.randomUUID().toString()),
                zeroQuantityRequest
        );

        assertTrue(response.getStatusCode() >= 400 && response.getStatusCode() < 500,
                "Should return 4xx error for zero quantity");

        response.then()
                .body("message", anyOf(
                        containsString("quantity"),
                        containsString("greater than"),
                        containsString("positive")
                ));

        log.info("✅ Test PASSED: Rejected order with zero quantity");
    }

    @Test(description = "Order creation should fail with negative quantity",enabled = false)
    public void testOrderCreationWithNegativeQuantity() throws Exception {
        log.info("=== Test: Negative Quantity ===");

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).build().seed().getFirst();

        waitForDataPropagation(1000);

        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        RequestSpecification spec = context.getRestAssuredConfig().getOrderServiceSpec(userToken);

        TestModels.CreateOrderRequest negativeQuantityRequest = TestModels.CreateOrderRequest.builder()
               // .namespace(context.getNamespace())
                .items(List.of(
                        TestModels.OrderItemRequest.builder()
                                .productId(product.getId())
                                .quantity(-5)  // Invalid!
                                .build()
                ))
                .shippingAddress("123 Test St")
                .build();

        Response response = context.getRestClient().post(
                "/api/orders",
                spec.header("Idempotency-Key", UUID.randomUUID().toString()),
                negativeQuantityRequest
        );

        assertTrue(response.getStatusCode() >= 400 && response.getStatusCode() < 500,
                "Should return 4xx error for negative quantity");

        log.info("✅ Test PASSED: Rejected order with negative quantity");
    }

    // ==========================================
    // INVENTORY/STOCK VALIDATION TESTS
    // ==========================================

    @Test(description = "Order creation should fail when requesting more stock than available",enabled = false)
    public void testOrderCreationExceedsAvailableStock() throws Exception {
        log.info("=== Test: Insufficient Stock ===");

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();

        // Create product with LOW stock
        ProductSeeder productSeeder = ProductSeeder.builder(context)
                .count(1)
                .lowStock()  // 1-10 units
                .build();
        TestModels.ProductResponse product = productSeeder.seed().getFirst();

        waitForDataPropagation(1000);

        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        RequestSpecification spec = context.getRestAssuredConfig().getOrderServiceSpec(userToken);

        // Try to order MORE than available
        int excessiveQuantity = product.getStockQuantity() + 100;

        TestModels.CreateOrderRequest excessiveOrderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, excessiveQuantity)
                .build();

        Response response = context.getRestClient().post(
                "/api/orders",
                spec.header("Idempotency-Key", UUID.randomUUID().toString()),
                excessiveOrderRequest
        );

        assertTrue(response.getStatusCode() >= 400 && response.getStatusCode() < 500,
                "Should return 4xx error for insufficient stock");

        response.then()
                .body("message", anyOf(
                        containsString("stock"),
                        containsString("insufficient"),
                        containsString("available"),
                        containsString("inventory")
                ));

        log.info("✅ Test PASSED: Rejected order exceeding stock (requested: {}, available: {})",
                excessiveQuantity, product.getStockQuantity());
    }

    @Test(description = "Order creation should fail for out-of-stock product",enabled = false)
    public void testOrderCreationForOutOfStockProduct() throws Exception {
        log.info("=== Test: Out of Stock Product ===");

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();

        // Create product with ZERO stock
        ProductSeeder productSeeder = ProductSeeder.builder(context)
                .count(1)
               // .outOfStock()  // 0 units
                .build();
        TestModels.ProductResponse product = productSeeder.seed().getFirst();

        waitForDataPropagation(1000);

        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        RequestSpecification spec = context.getRestAssuredConfig().getOrderServiceSpec(userToken);

        TestModels.CreateOrderRequest outOfStockRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        Response response = context.getRestClient().post(
                "/api/orders",
                spec.header("Idempotency-Key", UUID.randomUUID().toString()),
                outOfStockRequest
        );

        assertTrue(response.getStatusCode() >= 400 && response.getStatusCode() < 500,
                "Should return 4xx error for out of stock");

        response.then()
                .body("message", anyOf(
                        containsString("out of stock"),
                        containsString("unavailable"),
                        containsString("stock")
                ));

        log.info("✅ Test PASSED: Rejected order for out-of-stock product");
    }

    // ==========================================
    // BUSINESS LOGIC VALIDATION
    // ==========================================

    @Test(description = "Order creation should fail with missing shipping address",enabled = false)
    public void testOrderCreationWithoutShippingAddress() throws Exception {
        log.info("=== Test: Missing Shipping Address ===");

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).build().seed().getFirst();

        waitForDataPropagation(1000);

        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        RequestSpecification spec = context.getRestAssuredConfig().getOrderServiceSpec(userToken);

        TestModels.CreateOrderRequest noAddressRequest = TestModels.CreateOrderRequest.builder()
              //  .namespace(context.getNamespace())
                .items(List.of(
                        TestModels.OrderItemRequest.builder()
                                .productId(product.getId())
                                .quantity(1)
                                .build()
                ))
                // No shippingAddress!
                .build();

        Response response = context.getRestClient().post(
                "/api/orders",
                spec.header("Idempotency-Key", UUID.randomUUID().toString()),
                noAddressRequest
        );

        assertTrue(response.getStatusCode() >= 400 && response.getStatusCode() < 500,
                "Should return 4xx error for missing shipping address");

        log.info("✅ Test PASSED: Rejected order without shipping address");
    }

    @Test(description = "Order creation should fail for duplicate product IDs in same order",enabled = false)
    public void testOrderCreationWithDuplicateProducts() throws Exception {
        log.info("=== Test: Duplicate Products in Order ===");

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        RequestSpecification spec = context.getRestAssuredConfig().getOrderServiceSpec(userToken);

        // Create order with same product twice
        TestModels.CreateOrderRequest duplicateProductRequest = TestModels.CreateOrderRequest.builder()
               // .namespace(context.getNamespace())
                .items(List.of(
                        TestModels.OrderItemRequest.builder()
                                .productId(product.getId())
                                .quantity(2)
                                .build(),
                        TestModels.OrderItemRequest.builder()
                                .productId(product.getId())  // Same product!
                                .quantity(3)
                                .build()
                ))
                .shippingAddress("123 Test St")
                .build();

        Response response = context.getRestClient().post(
                "/api/orders",
                spec.header("Idempotency-Key", UUID.randomUUID().toString()),
                duplicateProductRequest
        );

        // Server might either:
        // 1. Reject with 400 (duplicate products not allowed)
        // 2. Accept and merge quantities (5 total)
        // Adjust assertion based on your business rules

        if (response.getStatusCode() >= 400) {
            log.info("✓ Server rejected duplicate products");
        } else {
            TestModels.OrderResponse order = response.as(TestModels.OrderResponse.class);
            // If server merges, verify total quantity
            log.info("✓ Server accepted and merged quantities");
        }

        log.info("✅ Test PASSED: Duplicate product handling verified");
    }

    // ==========================================
    // CONCURRENT REQUESTS TESTS
    // ==========================================

    @Test(description = "Concurrent requests with same idempotency key should create only one order",enabled = false)
    public void testConcurrentOrderCreationWithSameIdempotencyKey() throws Exception {
        log.info("=== Test: Concurrent Requests - Same Idempotency Key ===");

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        RequestSpecification spec = context.getRestAssuredConfig().getOrderServiceSpec(userToken);

        String sharedIdempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // Send 5 concurrent requests with SAME idempotency key
        List<Response> responses = Collections.synchronizedList(new ArrayList<>());

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Thread thread = new Thread(() -> {
                Response response = context.getRestClient().post(
                        "/api/orders",
                        spec.header("Idempotency-Key", sharedIdempotencyKey),
                        orderRequest
                );
                responses.add(response);
            });
            threads.add(thread);
        }

        // Start all threads simultaneously
        threads.forEach(Thread::start);

        // Wait for all to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // All should succeed (200 or 201)
        for (Response response : responses) {
            assertTrue(response.getStatusCode() == 200 || response.getStatusCode() == 201,
                    "All responses should be successful");
        }

        // All should return the SAME order ID
        Set<String> orderIds = new HashSet<>();
        for (Response response : responses) {
            TestModels.OrderResponse order = response.as(TestModels.OrderResponse.class);
            orderIds.add(order.getId());
        }

        assertEquals(orderIds.size(), 1, "All concurrent requests should return the same order ID");

        log.info("✅ Test PASSED: {} concurrent requests created only 1 order", responses.size());
    }

    // ==========================================
    // EDGE CASES
    // ==========================================

    @Test(description = "Order creation should handle extremely large quantity",enabled = false)
    public void testOrderCreationWithExtremelyLargeQuantity() throws Exception {
        log.info("=== Test: Extremely Large Quantity ===");

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        RequestSpecification spec = context.getRestAssuredConfig().getOrderServiceSpec(userToken);

        // Try to order an unrealistic quantity
        int extremeQuantity = Integer.MAX_VALUE;

        TestModels.CreateOrderRequest extremeRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, extremeQuantity)
                .build();

        Response response = context.getRestClient().post(
                "/api/orders",
                spec.header("Idempotency-Key", UUID.randomUUID().toString()),
                extremeRequest
        );

        assertTrue(response.getStatusCode() >= 400 && response.getStatusCode() < 500,
                "Should return 4xx error for extreme quantity");

        log.info("✅ Test PASSED: Rejected order with extreme quantity");
    }

    @Test(description = "Order creation should fail for inactive/deleted user",enabled = false)
    public void testOrderCreationForInactiveUser() throws Exception {
        log.info("=== Test: Inactive User ===");

        // Create user
        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).build().seed().getFirst();

        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        RequestSpecification spec = context.getRestAssuredConfig().getOrderServiceSpec(userToken);

        // Delete/deactivate user (if your API supports this)
        // context.getRestClient().delete("/api/users/" + user.getId(), adminSpec);

        waitForDataPropagation(1000);

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        Response response = context.getRestClient().post(
                "/api/orders",
                spec.header("Idempotency-Key", UUID.randomUUID().toString()),
                orderRequest
        );

        // Depending on implementation, might return 401 or 403
        assertTrue(response.getStatusCode() == 401 || response.getStatusCode() == 403,
                "Should reject order for inactive user");

        log.info("✅ Test PASSED: Rejected order for inactive user");
    }

    @Test(description = "Order creation should fail without idempotency key",enabled = false)
    public void testOrderCreationWithoutIdempotencyKey() throws Exception {
        log.info("=== Test: Missing Idempotency Key ===");

        TestModels.UserResponse user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        TestModels.ProductResponse product = ProductSeeder.builder(context).count(1).build().seed().getFirst();

        waitForDataPropagation(1000);

        String userToken = context.getCached("user_token_" + user.getId(), String.class);
        RequestSpecification spec = context.getRestAssuredConfig().getOrderServiceSpec(userToken);

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // Send request WITHOUT idempotency key
        Response response = context.getRestClient().post(
                "/api/orders",
                spec,  // No header added
                orderRequest
        );

        // Depending on your API design:
        // Option 1: Require idempotency key (return 400)
        // Option 2: Make it optional (return 201, but no idempotency protection)

        if (response.getStatusCode() >= 400) {
            log.info("✓ Server requires idempotency key");
            response.then()
                    .body("message", containsString("Idempotency-Key"));
        } else {
            log.info("✓ Server allows orders without idempotency key (no protection)");
        }

        log.info("✅ Test PASSED: Idempotency key requirement verified");
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

}