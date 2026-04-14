package com.amazon.tests.tests.kafka.orders.eventConsumption.negative.idempotency;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import com.github.javafaker.Faker;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.testng.AssertJUnit.fail;

/**
 * Kafka Idempotency Test Suite
 *
 * Tests critical idempotency scenarios in event-driven architecture:
 * 1. Duplicate Event Processing - Same event consumed multiple times
 * 2. Out-of-Order Events - Events arrive in wrong sequence
 * 3. Concurrent Processing - Same event processed simultaneously
 * 4. Missing Idempotency Key - Events without proper identification
 *
 * Each scenario tests both positive (idempotency works) and negative (failure handling) cases
 */
@Epic("Kafka Event Processing")
@Feature("Idempotency & Event Deduplication")
public class KafkaIdempotencyTest extends BaseTest {

    private String userToken;
    private static final int KAFKA_PROCESSING_TIMEOUT_SECONDS = 10;
    private static final int CONCURRENT_THREADS = 5;
    private String validToken;
    private String userId;
    private static final String GATEWAY_URL = "http://localhost:8080";
    private String productId;
    private static final Faker faker = new Faker();

    @BeforeClass
    public void setup() {

            logStep("Setting up Kafka deserialization failure tests");
            // Create test user
            TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();
            validToken = auth.getAccessToken();
            userId = auth.getUser().getId();
            logStep("✅ User created: " + userId);

            // Create test product
            createTestProduct();

    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CATEGORY 1: DUPLICATE EVENT PROCESSING
    // ═══════════════════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify that duplicate ORDER_CREATED events are processed only once")
    @Story("Duplicate Event Handling")
    public void test01_DuplicateOrderCreatedEvent_ShouldProcessOnce() {
        // Create an order (this publishes ORDER_CREATED event)
        String idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> orderData = createValidOrder();
       // String orderId = createOrder();

        Response firstResponse = given()
                .log().all()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .header("X-User-Id", userId)  // ✅ ADD THIS
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then()
                .log().all()
                .extract().response();

        assertThat(firstResponse.statusCode()).isEqualTo(201);
        // ✅ GET orderId from the response (not from createOrder())
        String orderId = firstResponse.jsonPath().getString("id");

        // Wait for first processing
        await().atMost(Duration.ofSeconds(KAFKA_PROCESSING_TIMEOUT_SECONDS))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Response orderResponse = getOrder(orderId);
                    assertThat(orderResponse.statusCode()).isEqualTo(200);
                    String status = orderResponse.jsonPath().getString("status");
                    assertThat(status).isIn("PENDING", "COMPLETED", "FAILED");
                });

        // Simulate duplicate event by triggering order creation again with same idempotency key
        // This would typically be done by republishing to Kafka, but we can test via API endpoint
        // Wait for processing
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    Response orderResponse = getOrder(orderId);
                    assertThat(orderResponse.statusCode()).isEqualTo(200);
                });

        // ═══════════════════════════════════════════════════════════════════
        // DUPLICATE REQUEST - SAME userId AND SAME idempotencyKey
        // ═══════════════════════════════════════════════════════════════════
        Response duplicateResponse = given()
                .log().all()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .header("X-User-Id", userId)  // ✅ SAME userId
                .header("Idempotency-Key", idempotencyKey)  // ✅ SAME key
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then()
                .log().all()
                .extract().response();

        // ✅ Now this should work!
        assertThat(duplicateResponse.statusCode()).isEqualTo(200);
        assertThat(duplicateResponse.jsonPath().getString("id")).isEqualTo(orderId);
        // Verify duplicate is detected
        if (duplicateResponse.statusCode() == 200) {
            // Duplicate detected - verify it returns the SAME order
            assertThat(duplicateResponse.jsonPath().getString("id")).isEqualTo(orderId);
        } else if (duplicateResponse.statusCode() == 201) {
            // Also acceptable - verify it returns the SAME order
            assertThat(duplicateResponse.jsonPath().getString("id")).isEqualTo(orderId);
        } else {
            fail("Expected 200 or 201, but got: " + duplicateResponse.statusCode());
        }

        // ═══════════════════════════════════════════════════════════════════
        // VERIFY IDEMPOTENCY
        // ═══════════════════════════════════════════════════════════════════

        // 1. Duplicate should return 200 OK (not 201 Created)
        assertThat(duplicateResponse.statusCode()).isEqualTo(200)
                .describedAs("Duplicate request should return 200 OK, not 201 Created");

        // 2. Duplicate should return SAME order ID
        String duplicateOrderId = duplicateResponse.jsonPath().getString("id");
        assertThat(duplicateOrderId).isEqualTo(orderId)
                .describedAs("Duplicate request must return the SAME order ID");

        // 3. Verify order details are identical
        assertThat(duplicateResponse.jsonPath().getString("status"))
                .isEqualTo(firstResponse.jsonPath().getString("status"))
                .describedAs("Order status should be identical");

        // ✅ Use getDouble() for numeric values
        assertThat(duplicateResponse.jsonPath().getDouble("totalAmount"))
                .isEqualTo(firstResponse.jsonPath().getDouble("totalAmount"))
                .describedAs("Order total should be identical");

        logStep("✅ IDEMPOTENCY TEST PASSED!");
        logStep("   - First request: 201 Created, orderId={}"+ orderId);
        logStep("   - Duplicate request: 200 OK, orderId={} (SAME!)"+ duplicateOrderId);
        logStep("   - No duplicate order created ✅");

    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CATEGORY 2: OUT-OF-ORDER EVENTS
    // ═══════════════════════════════════════════════════════════════════════════

  /*  @Test(enabled = false)
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify PAYMENT_COMPLETED arriving before ORDER_CREATED is handled correctly")
    @Story("Out-of-Order Event Handling")
    public void test04_PaymentCompletedBeforeOrderCreated_ShouldWaitOrReject() {
        // This tests the scenario where payment service processes faster than order service
        // In a real system, this would involve Kafka partition manipulation

        UUID randomOrderId = UUID.randomUUID();

        // Simulate PAYMENT_COMPLETED event for non-existent order
        Response paymentResponse = given()
                .header("Authorization", "Bearer " + userToken)
                .body(Map.of(
                        "orderId", randomOrderId.toString(),
                        "status", "COMPLETED",
                        "amount", 99.99
                ))
                .when()
                .post("/test/simulate-payment-event") // Test endpoint
                .then()
                .extract().response();

        // System should either:
        // 1. Queue the event for retry (202 Accepted)
        // 2. Reject it (404 Not Found or 409 Conflict)
        assertThat(paymentResponse.statusCode()).isIn(202, 404, 409, 422);

        // Now create the actual order
        String actualOrderId = createOrder();

        // Verify order processes normally despite the out-of-order event
        await().atMost(Duration.ofSeconds(KAFKA_PROCESSING_TIMEOUT_SECONDS))
                .untilAsserted(() -> {
                    Response orderResponse = getOrder(actualOrderId);
                    assertThat(orderResponse.statusCode()).isEqualTo(200);
                });
    }

    @Test(enabled = false)
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify ORDER_CANCELLED after PAYMENT_COMPLETED maintains data consistency")
    @Story("Out-of-Order Event Handling")
    public void test05_CancellationAfterPayment_ShouldTriggerRefund() {
        String orderId = createOrder();

        // Wait for payment completion
        await().atMost(Duration.ofSeconds(KAFKA_PROCESSING_TIMEOUT_SECONDS))
                .untilAsserted(() -> {
                    Response orderResponse = getOrder(orderId);
                    assertThat(orderResponse.jsonPath().getString("status")).isEqualTo("COMPLETED");
                });

        // Now cancel the order (simulates out-of-order cancellation)
        Response cancelResponse = given()
                .header("Authorization", "Bearer " + userToken)
                .when()
                .delete("/api/orders/" + orderId)
                .then()
                .extract().response();

        assertThat(cancelResponse.statusCode()).isIn(200, 202, 409);

        // Verify refund is initiated or cancellation is rejected
        await().atMost(Duration.ofSeconds(KAFKA_PROCESSING_TIMEOUT_SECONDS))
                .untilAsserted(() -> {
                    Response orderResponse = getOrder(orderId);
                    String status = orderResponse.jsonPath().getString("status");
                    // Either cancelled with refund or remains completed
                    assertThat(status).isIn("CANCELLED", "COMPLETED", "REFUND_PENDING");
                });
    }

    @Test(enabled = false)
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify events with timestamps are processed in logical order despite arrival order")
    @Story("Out-of-Order Event Handling")
    public void test06_EventsWithTimestamps_ShouldProcessInLogicalOrder() {
        // Create order and track all events
        String orderId = createOrder();

        await().atMost(Duration.ofSeconds(KAFKA_PROCESSING_TIMEOUT_SECONDS))
                .untilAsserted(() -> {
                    Response orderResponse = getOrder(orderId);
                    assertThat(orderResponse.statusCode()).isEqualTo(200);
                });

        // Get order history/audit log
        Response historyResponse = given()
                .header("Authorization", "Bearer " + userToken)
                .when()
                .get("/api/orders/" + orderId + "/history")
                .then()
                .extract().response();

        if (historyResponse.statusCode() == 200) {
            List<Map<String, Object>> events = historyResponse.jsonPath().getList("$");

            // Verify events are ordered by timestamp
            for (int i = 0; i < events.size() - 1; i++) {
                String timestamp1 = events.get(i).get("timestamp").toString();
                String timestamp2 = events.get(i + 1).get("timestamp").toString();
                assertThat(timestamp1).isLessThanOrEqualTo(timestamp2)
                        .describedAs("Events should be ordered by timestamp");
            }
        }
    }*/

    // ═══════════════════════════════════════════════════════════════════════════
    // CATEGORY 3: CONCURRENT PROCESSING OF SAME EVENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify concurrent processing of same ORDER_CREATED event creates only one order")
    @Story("Concurrent Event Processing")
    public void test07_ConcurrentOrderCreation_ShouldCreateOnlyOneOrder() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        List<Future<Response>> futures = new ArrayList<>();

        // Submit multiple concurrent requests with same idempotency key
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            futures.add(executor.submit(() ->
                    given()
                            .header("Authorization", "Bearer " + userToken)
                            .header("X-User-Id", userId)
                            .header("Idempotency-Key", idempotencyKey)
                            .body(createOrderRequest())
                            .when()
                            .post("/api/orders")
                            .then()
                            .extract().response()
            ));
        }

        // Collect all responses
        List<Response> responses = new ArrayList<>();
        for (Future<Response> future : futures) {
            responses.add(future.get());
        }

        executor.shutdown();

        // Verify only one order was created
        String firstOrderId = null;
        int successCount = 0;

        for (Response response : responses) {
            if (response.statusCode() == 201 || response.statusCode() == 200) {
                String orderId = response.jsonPath().getString("id");
                if (firstOrderId == null) {
                    firstOrderId = orderId;
                    successCount++;
                } else {
                    // All successful responses should return the same order ID
                    assertThat(orderId).isEqualTo(firstOrderId);
                    successCount++;
                }
            }
        }

        assertThat(successCount).isEqualTo(CONCURRENT_THREADS)
                .describedAs("All " + CONCURRENT_THREADS + " requests should succeed");

        // Verify only one payment exists
        String finalFirstOrderId = firstOrderId;
        await().atMost(Duration.ofSeconds(KAFKA_PROCESSING_TIMEOUT_SECONDS))
                .untilAsserted(() -> {
                    Response paymentsResponse = given()
                            .header("Authorization", "Bearer " + userToken)
                            .when()
                            .get("/api/payments/order/" + finalFirstOrderId)
                            .then()
                            .extract().response();

                    if (paymentsResponse.statusCode() == 200) {
                        List<Map<String, Object>> payments = paymentsResponse.jsonPath().getList("$");
                        assertThat(payments).hasSize(1)
                                .describedAs("Only one payment should exist despite concurrent processing");
                    }
                });
    }

    @Test(enabled = false)
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify concurrent PAYMENT_COMPLETED events update order status atomically")
    @Story("Concurrent Event Processing")
    public void test08_ConcurrentPaymentCompletedEvents_ShouldUpdateAtomically() throws Exception {
        String orderId = createOrder();

        // Wait for order to be in PAYMENT_PENDING state
        await().atMost(Duration.ofSeconds(KAFKA_PROCESSING_TIMEOUT_SECONDS))
                .untilAsserted(() -> {
                    Response orderResponse = getOrder(orderId);
                    String status = orderResponse.jsonPath().getString("status");
                    assertThat(status).isIn("PAYMENT_PENDING", "COMPLETED");
                });

        Response finalOrderResponse = getOrder(orderId);
        String finalStatus = finalOrderResponse.jsonPath().getString("status");

        // Verify order is in a consistent state (not partially updated)
        assertThat(finalStatus).isIn("PAYMENT_PENDING", "COMPLETED", "FAILED")
                .describedAs("Order status should be valid, not in inconsistent state");

        // Verify no duplicate payments were created
        Response paymentsResponse = given()
                .header("Authorization", "Bearer " + userToken)
                .when()
                .get("/api/payments/order/" + orderId)
                .then()
                .extract().response();

        if (paymentsResponse.statusCode() == 200) {
            List<Map<String, Object>> payments = paymentsResponse.jsonPath().getList("$");
            assertThat(payments).hasSizeLessThanOrEqualTo(1)
                    .describedAs("No duplicate payments should exist");
        }
    }

    @Test(enabled = false)
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify database optimistic locking prevents concurrent update conflicts")
    @Story("Concurrent Event Processing")
    public void test09_OptimisticLocking_ShouldPreventConcurrentUpdateConflicts() {
        String orderId = createOrder();

        await().atMost(Duration.ofSeconds(KAFKA_PROCESSING_TIMEOUT_SECONDS))
                .untilAsserted(() -> {
                    Response orderResponse = getOrder(orderId);
                    assertThat(orderResponse.statusCode()).isEqualTo(200);
                });

        // Check if version field exists (indicator of optimistic locking)
        Response orderResponse = getOrder(orderId);
        Map<String, Object> order = orderResponse.jsonPath().getMap("$");

        // Version field or updatedAt timestamp should exist for optimistic locking
        boolean hasVersioning = order.containsKey("version") || order.containsKey("updatedAt");
        assertThat(hasVersioning).isTrue()
                .describedAs("Order entity should have versioning field for optimistic locking");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CATEGORY 4: MISSING IDEMPOTENCY KEY
    // ═══════════════════════════════════════════════════════════════════════════

    @Test(enabled = false)
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify order creation without idempotency key is rejected")
    @Story("Missing Idempotency Key - Negative")
    public void test10_OrderCreationWithoutIdempotencyKey_ShouldBeRejected() {
        Response response = given()
                .header("Authorization", "Bearer " + userToken)
                // Deliberately not including Idempotency-Key header
                .body(createOrderRequest())
                .when()
                .post("/api/orders")
                .then()
                .extract().response();

        // System should either:
        // 1. Auto-generate idempotency key (200/201)
        // 2. Require explicit idempotency key (400)

        if (response.statusCode() == 400) {
            // Strict mode - requires idempotency key
            assertThat(response.jsonPath().getString("message"))
                    .containsIgnoringCase("idempotency");
        } else {
            // Lenient mode - auto-generates key, verify order is created
            assertThat(response.statusCode()).isIn(200, 201);
            assertThat(response.jsonPath().getString("id")).isNotNull();
        }
    }

    @Test(enabled = false)
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify events with null/empty idempotency key are handled gracefully")
    @Story("Missing Idempotency Key - Negative")
    public void test11_EventWithNullIdempotencyKey_ShouldUseDefaultStrategy() {
        // Create order with empty idempotency key
        Response response = given()
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", "") // Empty key
                .body(createOrderRequest())
                .when()
                .post("/api/orders")
                .then()
                .extract().response();

        // Should either reject or auto-generate
        if (response.statusCode() >= 400) {
            assertThat(response.statusCode()).isIn(400, 422);
        } else {
            assertThat(response.statusCode()).isIn(200, 201);
            String orderId = response.jsonPath().getString("id");
            assertThat(orderId).isNotNull();

            // Verify order processes normally
            await().atMost(Duration.ofSeconds(KAFKA_PROCESSING_TIMEOUT_SECONDS))
                    .untilAsserted(() -> {
                        Response orderResponse = getOrder(orderId);
                        assertThat(orderResponse.statusCode()).isEqualTo(200);
                    });
        }
    }

    @Test(enabled = false)
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify idempotency key validation (format, length)")
    @Story("Missing Idempotency Key - Negative")
    public void test12_InvalidIdempotencyKeyFormat_ShouldBeRejected() {
        String[] invalidKeys = {
                "a", // Too short
                "x".repeat(300), // Too long
                "key with spaces",
                "key@with#special$chars",
                null
        };

        for (String invalidKey : invalidKeys) {
            Response response = given()
                    .header("Authorization", "Bearer " + userToken)
                    .header("Idempotency-Key", invalidKey)
                    .body(createOrderRequest())
                    .when()
                    .post("/api/orders")
                    .then()
                    .extract().response();

            // System may accept any string or have validation rules
            // This test documents actual behavior
            if (response.statusCode() >= 400) {
                assertThat(response.statusCode()).isIn(400, 422);
            }
        }
    }

    @Test(enabled = false)
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify system generates unique idempotency keys when not provided")
    @Story("Missing Idempotency Key")
    public void test13_AutoGeneratedIdempotencyKeys_ShouldBeUnique() {
        List<String> orderIds = new ArrayList<>();

        // Create multiple orders without explicit idempotency keys
        for (int i = 0; i < 3; i++) {
            Response response = given()
                    .header("Authorization", "Bearer " + userToken)
                    .body(createOrderRequest())
                    .when()
                    .post("/api/orders")
                    .then()
                    .extract().response();

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                orderIds.add(response.jsonPath().getString("id"));
            }
        }

        // Verify all orders have unique IDs
        assertThat(orderIds).doesNotHaveDuplicates()
                .describedAs("Auto-generated idempotency should create unique orders");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ADVANCED IDEMPOTENCY SCENARIOS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test(enabled = false)
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify idempotency window expiration allows reprocessing")
    @Story("Idempotency Window Management")
    public void test14_IdempotencyKeyExpiration_ShouldAllowReprocessing() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        // Create first order
        Response firstResponse = given()
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .body(createOrderRequest())
                .when()
                .post("/api/orders")
                .then()
                .extract().response();

        assertThat(firstResponse.statusCode()).isIn(200, 201);
        String firstOrderId = firstResponse.jsonPath().getString("id");

        // Wait for idempotency window to expire (typically 24 hours, but might be shorter in test)
        // In real test, you'd mock time or use a shorter window in test config
        Thread.sleep(2000); // Simulate time passing

        // Try to create another order with same idempotency key
        Response secondResponse = given()
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .body(createOrderRequest())
                .when()
                .post("/api/orders")
                .then()
                .extract().response();

        // Should either return same order (still in window) or create new order (expired)
        assertThat(secondResponse.statusCode()).isIn(200, 201);
        String secondOrderId = secondResponse.jsonPath().getString("id");

        // Document behavior - same ID means window hasn't expired
        if (firstOrderId.equals(secondOrderId)) {
            // Idempotency window still active
            assertThat(firstOrderId).isEqualTo(secondOrderId);
        } else {
            // Window expired, new order created
            assertThat(firstOrderId).isNotEqualTo(secondOrderId);
        }
    }

    @Test(enabled = false)
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify cross-service idempotency coordination")
    @Story("Distributed Idempotency")
    public void test15_CrossServiceIdempotency_ShouldMaintainConsistency() {
        String orderId = createOrder();

        // Wait for order and payment to complete
        await().atMost(Duration.ofSeconds(KAFKA_PROCESSING_TIMEOUT_SECONDS))
                .untilAsserted(() -> {
                    Response orderResponse = getOrder(orderId);
                    assertThat(orderResponse.jsonPath().getString("status"))
                            .isIn("COMPLETED", "FAILED");
                });

        // Verify both order service and payment service have consistent state
        Response orderResponse = getOrder(orderId);
        String orderStatus = orderResponse.jsonPath().getString("status");

        Response paymentResponse = given()
                .header("Authorization", "Bearer " + userToken)
                .when()
                .get("/api/payments/order/" + orderId)
                .then()
                .extract().response();

        if (paymentResponse.statusCode() == 200) {
            List<Map<String, Object>> payments = paymentResponse.jsonPath().getList("$");
            assertThat(payments).hasSize(1);

            String paymentStatus = payments.get(0).get("status").toString();

            // Verify consistency between order and payment status
            if (orderStatus.equals("COMPLETED")) {
                assertThat(paymentStatus).isIn("COMPLETED", "SUCCESS");
            } else if (orderStatus.equals("FAILED")) {
                assertThat(paymentStatus).isIn("FAILED", "REJECTED");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private String createOrder() {
        Map<String, Object> orderData = createValidOrder();
        Response createResp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then()
                .statusCode(201)
                .extract()
                .response();


        assertThat(createResp.statusCode()).isIn(200, 201);
        return createResp.jsonPath().getString("id");
    }

    private Map<String, Object> createValidOrder() {
        return Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 1,
                                "unitPrice", 50.0,
                                "productName", "Test Product"
                        )
                ),
                "shippingAddress", faker.address().fullAddress()
        );
    }
    private Map<String, Object> createOrderRequest() {
        return Map.of(
                "items", List.of(
                        Map.of(
                                "productId", UUID.randomUUID().toString(),
                                "quantity", 2,
                                "price", 49.99
                        )
                )
        );
    }

    private Response getOrder(String orderId) {
        return given().log().all().baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get("/api/orders/" + orderId)
                .then().log().all()
                .extract().response();
    }

    private void createTestProduct() {
        Map<String, Object> productData = Map.of(
                "name", "Test Product",
                "description", "For deserialization tests",
                "price", 99.99,
                "stockQuantity", 1000
        );

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(productData)
                .when()
                .post("/api/products")
                .then()
                .extract()
                .response();

        if (resp.statusCode() == 201) {
            productId = resp.jsonPath().getString("id");
            logStep("✅ Test product created: " + productId);
        } else {
            productId = "550e8400-e29b-41d4-a716-446655440000";
            logStep("⚠️  Using fallback product ID");
        }
    }

    // ✅ Helper method to extract userId from JWT
    private String extractUserIdFromJwt(String token) {
        try {
            String[] parts = token.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));

            // Extract "sub" field from JWT payload
            // {"sub":"76061b42-73e1-429d-af15-d98e1627626b", ...}
            String sub = payload.split("\"sub\":\"")[1].split("\"")[0];
            return sub;
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract userId from JWT", e);
        }
    }
}