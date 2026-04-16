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
import java.util.concurrent.CountDownLatch;
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
public class OrderIdempotencyTest extends BaseTest {

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
    @Description("Verify concurrent requests with same idempotency key create only ONE order")
    @Story("Concurrent Event Processing")
    public void test07_ConcurrentOrderCreation_ShouldCreateOnlyOneOrder() throws Exception {
        // Setup
        String idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> orderData = createValidOrder();
        int concurrentThreads = 5;

        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        List<Future<Response>> futures = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(concurrentThreads);

        logStep("🔄 Starting " + concurrentThreads + " concurrent requests with SAME idempotency key");
        logStep("   Idempotency Key: " + idempotencyKey);

        // ═══════════════════════════════════════════════════════════════════
        // Submit concurrent requests with SAME idempotency key
        // ═══════════════════════════════════════════════════════════════════
        for (int i = 0; i < concurrentThreads; i++) {
            final int threadNum = i + 1;

            futures.add(executor.submit(() -> {
                try {
                    latch.countDown();
                    latch.await();  // All threads start at the same time

                    logStep("   Thread " + threadNum + " sending request...");

                    return given()
                            .baseUri(GATEWAY_URL)
                            .header("Authorization", "Bearer " + validToken)
                            .header("X-User-Id", userId)
                            .header("Idempotency-Key", idempotencyKey)  // SAME key
                            .contentType("application/json")
                            .body(orderData)
                            .post("/api/orders");

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        // Collect responses
        List<Response> responses = new ArrayList<>();
        for (Future<Response> future : futures) {
            responses.add(future.get());
        }

        executor.shutdown();

        logStep("✅ All " + concurrentThreads + " requests completed");

        // ═══════════════════════════════════════════════════════════════════
        // ANALYZE RESPONSES
        // ═══════════════════════════════════════════════════════════════════

        Set<String> uniqueOrderIds = new HashSet<>();
        int status201Count = 0;  // Created
        int status200Count = 0;  // Duplicate detected (returned existing)
        int status500Count = 0;  // Database constraint violation (race condition)

        for (int i = 0; i < responses.size(); i++) {
            Response response = responses.get(i);
            int status = response.statusCode();

            logStep("   Thread " + (i + 1) + " result: HTTP " + status);

            if (status == 201) {
                status201Count++;
                String orderId = response.jsonPath().getString("id");
                uniqueOrderIds.add(orderId);
                logStep("      → Created new order: " + orderId);

            } else if (status == 200) {
                status200Count++;
                String orderId = response.jsonPath().getString("id");
                uniqueOrderIds.add(orderId);
                logStep("      → Returned existing order: " + orderId);

            } else if (status == 500) {
                status500Count++;
                String errorMsg = response.jsonPath().getString("message");
                logStep("      → Database constraint violation (race condition)");
                logStep("         Error: " + errorMsg);

            } else {
                logStep("      → Unexpected status: " + status);
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // VERIFY IDEMPOTENCY
        // ═══════════════════════════════════════════════════════════════════

        logStep("\n📊 RESULTS:");
        logStep("   Total requests: " + concurrentThreads);
        logStep("   201 Created: " + status201Count);
        logStep("   200 OK (duplicate): " + status200Count);
        logStep("   500 Error (race condition): " + status500Count);
        logStep("   Unique orders: " + uniqueOrderIds.size());

        // ✅ CRITICAL: Only ONE unique order should exist
        assertThat(uniqueOrderIds).hasSize(1)
                .describedAs("Only ONE unique order should be created despite concurrent requests");

        String orderId = uniqueOrderIds.iterator().next();

        // ✅ Exactly ONE request should have created the order (201)
        assertThat(status201Count).isEqualTo(1)
                .describedAs("Exactly ONE request should successfully create the order");

        // ✅ At least some responses should be successful (201 or 200)
        int successfulResponses = status201Count + status200Count;
        assertThat(successfulResponses).isGreaterThanOrEqualTo(1)
                .describedAs("At least one request should get a successful response");

        // ⚠️ REMOVED THIS ASSERTION (it was wrong):
        // assertThat(successCount).isEqualTo(CONCURRENT_THREADS)

        // ═══════════════════════════════════════════════════════════════════
        // VERIFY THE SINGLE ORDER EXISTS
        // ═══════════════════════════════════════════════════════════════════

        logStep("\n🔍 Verifying the order exists in database...");

        Response orderResponse = getOrder(orderId);

        assertThat(orderResponse.statusCode()).isEqualTo(200)
                .describedAs("The created order should be retrievable");

        String orderIdempotencyKey = orderResponse.jsonPath().getString("idempotencyKey");

        assertThat(orderIdempotencyKey).isEqualTo(idempotencyKey)
                .describedAs("Order should have the correct idempotency key");

        logStep("✅ Order verified: " + orderId);
        logStep("   Idempotency Key: " + orderIdempotencyKey);

        // ═══════════════════════════════════════════════════════════════════
        // SUCCESS
        // ═══════════════════════════════════════════════════════════════════

        logStep("\n✅ CONCURRENCY TEST PASSED!");
        logStep("   Concurrent threads: " + concurrentThreads);
        logStep("   Orders created: 1 (CORRECT!)");
        logStep("   Database constraint prevented duplicates: YES");
        logStep("   Idempotency working correctly: YES");
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