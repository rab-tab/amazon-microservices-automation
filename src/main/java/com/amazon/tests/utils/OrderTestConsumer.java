package com.amazon.tests.utils;


import io.restassured.response.Response;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;

/**
 * Consumer: Executes order creation tests
 *
 * Responsibilities:
 * 1. Take order data from queue
 * 2. Create order via API
 * 3. Validate order creation
 * 4. Track success/failure metrics
 */
public class OrderTestConsumer implements Runnable {

    private final BlockingQueue<OrderCreationTestData> queue;
    private final String consumerId;
    private final String baseUrl;
    private final AtomicInteger successCount;
    private final AtomicInteger failureCount;
    private final AtomicInteger processedCount;
    private final ConcurrentHashMap<String, String> createdOrders;

    public OrderTestConsumer(BlockingQueue<OrderCreationTestData> queue,
                             String consumerId,
                             String baseUrl,
                             AtomicInteger successCount,
                             AtomicInteger failureCount,
                             AtomicInteger processedCount,
                             ConcurrentHashMap<String, String> createdOrders) {
        this.queue = queue;
        this.consumerId = consumerId;
        this.baseUrl = baseUrl;
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.processedCount = processedCount;
        this.createdOrders = createdOrders;
    }

    @Override
    public void run() {
        System.out.println("[" + consumerId + "] Started and waiting for orders...");

        try {
            while (true) {
                // Take data from queue (blocks if empty)
                OrderCreationTestData orderData = queue.take();

                // Check for poison pill
                if (orderData.isPoison()) {
                    System.out.println("[" + consumerId + "] Received shutdown signal");
                    break;
                }

                // Execute order creation test
                executeOrderCreationTest(orderData);

                int processed = processedCount.incrementAndGet();

                if (processed % 10 == 0) {
                    System.out.println(String.format(
                            "[%s] Progress: %d processed | %d success | %d failed",
                            consumerId, processed, successCount.get(), failureCount.get()
                    ));
                }
            }

            System.out.println("[" + consumerId + "] Shutting down");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[" + consumerId + "] Consumer interrupted");
        } catch (Exception e) {
            System.err.println("[" + consumerId + "] Unexpected error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Execute order creation test with validations
     */
    private void executeOrderCreationTest(OrderCreationTestData orderData) {
        try {
            // Step 1: Create Order
            String orderId = createOrder(orderData);

            if (orderId == null) {
                failureCount.incrementAndGet();
                return;
            }

            // Step 2: Verify Order
            boolean verified = verifyOrder(orderId, orderData);

            if (verified) {
                successCount.incrementAndGet();
                createdOrders.put(orderId, orderData.getUserId());
                System.out.println(String.format(
                        "[%s] ✅ SUCCESS: Order %s created for user %s",
                        consumerId, orderId, orderData.getUserId()
                ));
            } else {
                failureCount.incrementAndGet();
                System.err.println(String.format(
                        "[%s] ❌ FAILED: Order %s verification failed",
                        consumerId, orderId
                ));
            }

        } catch (Exception e) {
            failureCount.incrementAndGet();
            System.err.println(String.format(
                    "[%s] ❌ ERROR: %s",
                    consumerId, e.getMessage()
            ));
        }
    }

    /**
     * Create order via API
     */
    private String createOrder(OrderCreationTestData orderData) {
        try {
            String requestBody = String.format(
                    "{\"userId\":\"%s\"," +
                            "\"productSku\":\"%s\"," +
                            "\"quantity\":%d," +
                            "\"shippingAddress\":\"%s\"," +
                            "\"price\":%.2f}",
                    orderData.getUserId(),
                    orderData.getProductSku(),
                    orderData.getQuantity(),
                    orderData.getShippingAddress(),
                    orderData.getProductPrice()
            );

            Response response = given()
                    .baseUri(baseUrl)
                    .contentType("application/json")
                    .body(requestBody)
                    .when()
                    .post("/api/orders")
                    .then()
                    .extract().response();

            // Validate status code
            int statusCode = response.statusCode();

            if (statusCode == 201 || statusCode == 200) {
                // Extract order ID from response
                String orderId = response.jsonPath().getString("orderId");

                if (orderId == null || orderId.isEmpty()) {
                    orderId = response.jsonPath().getString("id");
                }

                return orderId;

            } else {
                System.err.println(String.format(
                        "[%s] Order creation failed with status %d: %s",
                        consumerId, statusCode, response.body().asString()
                ));
                return null;
            }

        } catch (Exception e) {
            System.err.println(String.format(
                    "[%s] Exception during order creation: %s",
                    consumerId, e.getMessage()
            ));
            return null;
        }
    }

    /**
     * Verify order was created correctly
     */
    private boolean verifyOrder(String orderId, OrderCreationTestData expectedData) {
        try {
            // Get order details
            Response response = given()
                    .baseUri(baseUrl)
                    .pathParam("orderId", orderId)
                    .when()
                    .get("/api/orders/{orderId}")
                    .then()
                    .extract().response();

            if (response.statusCode() != 200) {
                System.err.println(String.format(
                        "[%s] Order retrieval failed with status %d",
                        consumerId, response.statusCode()
                ));
                return false;
            }

            // Validate order details
            String actualUserId = response.jsonPath().getString("userId");
            String actualProductSku = response.jsonPath().getString("productSku");
            Integer actualQuantity = response.jsonPath().getInt("quantity");
            String actualStatus = response.jsonPath().getString("status");

            // Assertions
            boolean isValid = true;

            if (!expectedData.getUserId().equals(actualUserId)) {
                System.err.println(String.format(
                        "[%s] UserId mismatch: expected=%s, actual=%s",
                        consumerId, expectedData.getUserId(), actualUserId
                ));
                isValid = false;
            }

            if (!expectedData.getProductSku().equals(actualProductSku)) {
                System.err.println(String.format(
                        "[%s] ProductSku mismatch: expected=%s, actual=%s",
                        consumerId, expectedData.getProductSku(), actualProductSku
                ));
                isValid = false;
            }

            if (expectedData.getQuantity() != actualQuantity) {
                System.err.println(String.format(
                        "[%s] Quantity mismatch: expected=%d, actual=%d",
                        consumerId, expectedData.getQuantity(), actualQuantity
                ));
                isValid = false;
            }

            // Validate status is one of the expected values
            if (actualStatus == null ||
                    (!actualStatus.equals("CREATED") &&
                            !actualStatus.equals("PENDING") &&
                            !actualStatus.equals("CONFIRMED"))) {
                System.err.println(String.format(
                        "[%s] Invalid status: %s",
                        consumerId, actualStatus
                ));
                isValid = false;
            }

            return isValid;

        } catch (Exception e) {
            System.err.println(String.format(
                    "[%s] Exception during order verification: %s",
                    consumerId, e.getMessage()
            ));
            return false;
        }
    }
}
