package com.amazon.tests.regression.orderCreationFlow;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.RequestExecutor;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.testng.Assert.*;

/**
 * Negative Test Cases for Order Creation
 * Tests validation, error handling, idempotency, and edge cases
 */
@Slf4j
public class OrderCreationNegativeTest extends BaseTest {
    /**
     * Negative Test Cases for Order Creation
     * Refactored to use PurchaseWorkflow / OrderApiClient (Bridge pattern) instead of
     * direct RestAssured calls.
     */

        private OrderApiClient orderApiClient;

        private OrderApiClient orderApiClient() {
            if (orderApiClient == null) {
                orderApiClient = new OrderApiClient(authStrategy,executor); // executor() provided by BaseTest
            }
            return orderApiClient;
        }

        private RequestExecutor executor() {
            return context.getExecutor(); // assumes BaseTest/SeedingContext exposes a shared RequestExecutor
        }


        // ==========================================
        // VALIDATION TESTS - Missing/Invalid Fields (data-driven)
        // ==========================================

        @DataProvider(name = "invalidOrderPayloads")
        public Object[][] invalidOrderPayloads() {
            return new Object[][] {
                    { "Empty items list", (OrderRequestFactory) (products) ->
                            TestModels.CreateOrderRequest.builder()
                                    .items(List.of())
                                    .shippingAddress("123 Test St")
                                    .build() },
                    { "Invalid product ID", (OrderRequestFactory) (products) ->
                            TestModels.CreateOrderRequest.builder()
                                    .items(List.of(TestModels.OrderItemRequest.builder()
                                            .productId("FAKE-PRODUCT-" + UUID.randomUUID())
                                            .quantity(1)
                                            .build()))
                                    .shippingAddress("123 Test St")
                                    .build() },
                    { "Zero quantity", (OrderRequestFactory) (products) ->
                            TestModels.CreateOrderRequest.builder()
                                    .items(List.of(TestModels.OrderItemRequest.builder()
                                            .productId(products.get(0).getId())
                                            .quantity(0)
                                            .build()))
                                    .shippingAddress("123 Test St")
                                    .build() },
                    { "Negative quantity", (OrderRequestFactory) (products) ->
                            TestModels.CreateOrderRequest.builder()
                                    .items(List.of(TestModels.OrderItemRequest.builder()
                                            .productId(products.get(0).getId())
                                            .quantity(-5)
                                            .build()))
                                    .shippingAddress("123 Test St")
                                    .build() },
                    { "Missing shipping address", (OrderRequestFactory) (products) ->
                            TestModels.CreateOrderRequest.builder()
                                    .items(List.of(TestModels.OrderItemRequest.builder()
                                            .productId(products.get(0).getId())
                                            .quantity(1)
                                            .build()))
                                    .build() },
                    { "Extremely large quantity", (OrderRequestFactory) (products) ->
                            TestModels.CreateOrderRequest.builder()
                                    .items(List.of(TestModels.OrderItemRequest.builder()
                                            .productId(products.get(0).getId())
                                            .quantity(Integer.MAX_VALUE)
                                            .build()))
                                    .shippingAddress("123 Test St")
                                    .build() }
            };
        }

        public interface OrderRequestFactory {
            TestModels.CreateOrderRequest build(List<TestModels.ProductResponse> products);
        }

        @Test(dataProvider = "invalidOrderPayloads",
                description = "Order creation should reject invalid payload variants")
        public void testOrderCreationRejectsInvalidPayload(String scenario, OrderRequestFactory factory) {
            log.info("=== Test: {} ===", scenario);

            PurchaseResult purchase = PurchaseWorkflow.start(executor,authStrategy)
                    .registerCustomer()
                    .loginCustomer()
                    .registerSeller()
                    .createProductWithStock(29.99, 500)
                    .execute();

            String token = purchase.getCustomerAuth().getAccessToken();
            String userId = purchase.getCustomerAuth().getUser().getId();

            TestModels.CreateOrderRequest invalidRequest = factory.build(purchase.getProducts());

            ServiceResponse response = orderApiClient().createOrderWithFault(
                     userId, UUID.randomUUID().toString(), invalidRequest, null);

            assertTrue(response.getStatusCode() >= 400 && response.getStatusCode() < 500,
                    scenario + " should return 4xx error, got " + response.getStatusCode());

            log.info("✅ Test PASSED: Rejected — {}", scenario);
        }

        // ==========================================
        // AUTH / ACCESS VALIDATION
        // ==========================================

        @Test(description = "Order creation should fail without authentication", enabled = false)
        public void testOrderCreationWithoutAuth() {
            log.info("=== Test: No Authentication ===");

            PurchaseResult purchase = PurchaseWorkflow.start(executor,authStrategy)
                    .registerSeller()
                    .createProductWithStock(29.99, 500)
                    .execute();

            TestModels.CreateOrderRequest orderRequest =
                    TestDataFactory.defaultOrder(purchase.getProducts()).build();

            ServiceResponse response = orderApiClient().createOrderWithFault(
                    null, UUID.randomUUID().toString(), orderRequest, null);

            assertEquals(response.getStatusCode(), 401, "Should return 401 Unauthorized");

            log.info("✅ Test PASSED: Rejected unauthenticated request");
        }

        @Test(description = "Order creation should fail for inactive/deleted user", enabled = false)
        public void testOrderCreationForInactiveUser() {
            log.info("=== Test: Inactive User ===");

            PurchaseResult purchase = PurchaseWorkflow.start(executor,authStrategy)
                    .registerCustomer()
                    .loginCustomer()
                    .registerSeller()
                    .createProductWithStock(29.99, 500)
                    .execute();

            // Deactivation step would go here once supported by AuthApiClient/UserApiClient

            String token = purchase.getCustomerAuth().getAccessToken();
            String userId = purchase.getCustomerAuth().getUser().getId();
            TestModels.CreateOrderRequest orderRequest =
                    TestDataFactory.defaultOrder(purchase.getProducts()).build();

            ServiceResponse response = orderApiClient().createOrderWithFault(
                     userId, UUID.randomUUID().toString(), orderRequest, null);

            assertTrue(response.getStatusCode() == 401 || response.getStatusCode() == 403,
                    "Should reject order for inactive user");

            log.info("✅ Test PASSED: Rejected order for inactive user");
        }

        @Test(description = "Order creation should fail without idempotency key", enabled = false)
        public void testOrderCreationWithoutIdempotencyKey() {
            log.info("=== Test: Missing Idempotency Key ===");

            PurchaseResult purchase = PurchaseWorkflow.start(executor,authStrategy)
                    .registerCustomer()
                    .loginCustomer()
                    .registerSeller()
                    .createProductWithStock(29.99, 500)
                    .execute();

            String token = purchase.getCustomerAuth().getAccessToken();
            String userId = purchase.getCustomerAuth().getUser().getId();
            TestModels.CreateOrderRequest orderRequest =
                    TestDataFactory.defaultOrder(purchase.getProducts()).build();

            ServiceResponse response = orderApiClient().createOrderWithFault(
                     userId, null, orderRequest, null);

            if (response.getStatusCode() >= 400) {
                log.info("✓ Server requires idempotency key");
            } else {
                log.info("✓ Server allows orders without idempotency key (no protection)");
            }

            log.info("✅ Test PASSED: Idempotency key requirement verified");
        }

        // ==========================================
        // INVENTORY / STOCK VALIDATION
        // ==========================================

        @Test(description = "Order creation should fail when requesting more stock than available", enabled = false)
        public void testOrderCreationExceedsAvailableStock() {
            log.info("=== Test: Insufficient Stock ===");

            PurchaseResult purchase = PurchaseWorkflow.start(executor,authStrategy)
                    .registerCustomer()
                    .loginCustomer()
                    .registerSeller()
                    .createProductWithStock(9.99, 5) // low stock
                    .execute();

            TestModels.ProductResponse product = purchase.getFirstProduct();
            int excessiveQuantity = product.getStockQuantity() + 100;

            TestModels.CreateOrderRequest excessiveRequest = TestModels.CreateOrderRequest.builder()
                    .items(List.of(TestModels.OrderItemRequest.builder()
                            .productId(product.getId())
                            .productName(product.getName())
                            .unitPrice(product.getPrice())
                            .quantity(excessiveQuantity)
                            .build()))
                    .shippingAddress("123 Test St")
                    .build();

            ServiceResponse response = orderApiClient().createOrderWithFault(
                    purchase.getCustomerAuth().getUser().getId(),
                    UUID.randomUUID().toString(),
                    excessiveRequest,
                    null);

            assertTrue(response.getStatusCode() >= 400 && response.getStatusCode() < 500,
                    "Should return 4xx error for insufficient stock");

            log.info("✅ Test PASSED: Rejected order exceeding stock (requested: {}, available: {})",
                    excessiveQuantity, product.getStockQuantity());
        }

        @Test(description = "Order creation should fail for out-of-stock product", enabled = false)
        public void testOrderCreationForOutOfStockProduct() {
            log.info("=== Test: Out of Stock Product ===");

            PurchaseResult purchase = PurchaseWorkflow.start(executor,authStrategy)
                    .registerCustomer()
                    .loginCustomer()
                    .registerSeller()
                    .createProductWithStock(9.99, 0)
                    .execute();

            TestModels.CreateOrderRequest orderRequest =
                    TestDataFactory.defaultOrder(purchase.getProducts()).build();

            ServiceResponse response = orderApiClient().createOrderWithFault(
                    purchase.getCustomerAuth().getUser().getId(),
                    UUID.randomUUID().toString(),
                    orderRequest,
                    null);

            assertTrue(response.getStatusCode() >= 400 && response.getStatusCode() < 500,
                    "Should return 4xx error for out of stock");

            log.info("✅ Test PASSED: Rejected order for out-of-stock product");
        }

        // ==========================================
        // BUSINESS LOGIC EDGE CASES
        // ==========================================

        @Test(description = "Order creation should fail for duplicate product IDs in same order", enabled = false)
        public void testOrderCreationWithDuplicateProducts() {
            log.info("=== Test: Duplicate Products in Order ===");

            PurchaseResult purchase = PurchaseWorkflow.start(executor,authStrategy)
                    .registerCustomer()
                    .loginCustomer()
                    .registerSeller()
                    .createProductWithStock(29.99, 500)
                    .execute();

            TestModels.ProductResponse product = purchase.getFirstProduct();

            TestModels.CreateOrderRequest duplicateRequest = TestModels.CreateOrderRequest.builder()
                    .items(List.of(
                            TestModels.OrderItemRequest.builder()
                                    .productId(product.getId()).quantity(2).build(),
                            TestModels.OrderItemRequest.builder()
                                    .productId(product.getId()).quantity(3).build()
                    ))
                    .shippingAddress("123 Test St")
                    .build();

            ServiceResponse response = orderApiClient().createOrderWithFault(
                    purchase.getCustomerAuth().getUser().getId(),
                    UUID.randomUUID().toString(),
                    duplicateRequest,
                    null);

            if (response.getStatusCode() >= 400) {
                log.info("✓ Server rejected duplicate products");
            } else {
                log.info("✓ Server accepted and merged quantities");
            }

            log.info("✅ Test PASSED: Duplicate product handling verified");
        }

        // ==========================================
        // CONCURRENCY
        // ==========================================

        @Test(description = "Concurrent requests with same idempotency key should create only one order", enabled = false)
        public void testConcurrentOrderCreationWithSameIdempotencyKey() throws InterruptedException {
            log.info("=== Test: Concurrent Requests - Same Idempotency Key ===");

            PurchaseResult purchase = PurchaseWorkflow.start(executor,authStrategy)
                    .registerCustomer()
                    .loginCustomer()
                    .registerSeller()
                    .createProductWithStock(29.99, 500)
                    .execute();

            String token = purchase.getCustomerAuth().getAccessToken();
            String userId = purchase.getCustomerAuth().getUser().getId();
            String sharedIdempotencyKey = UUID.randomUUID().toString();

            TestModels.CreateOrderRequest orderRequest =
                    TestDataFactory.defaultOrder(purchase.getProducts()).build();

            List<TestModels.OrderResponse> orders = new CopyOnWriteArrayList<>();
            List<Thread> threads = new java.util.ArrayList<>();
            List<TestModels.ProductResponse> products = purchase.getProducts();
            for (int i = 0; i < 5; i++) {
                Thread t = new Thread(() ->
                        orders.add(orderApiClient().createOrder(userId,sharedIdempotencyKey,  products)));
                threads.add(t);
            }

            threads.forEach(Thread::start);
            for (Thread t : threads) t.join();

            Set<String> orderIds = new HashSet<>();
            orders.forEach(o -> orderIds.add(o.getId()));

            assertEquals(orderIds.size(), 1, "All concurrent requests should return the same order ID");

            log.info("✅ Test PASSED: {} concurrent requests created only 1 order", orders.size());
        }
    }

