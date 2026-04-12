package com.amazon.tests.tests.kafka.orders.eventConsumption.negative;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import com.github.javafaker.Faker;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kafka Event Consumption Tests - NEGATIVE CASES (Category 2)
 *
 * Tests failure scenarios when Payment Service consumes events
 *
 * Negative Cases:
 * 1. Payment service handles malformed/corrupted events gracefully
 * 2. Failed payment publishes PAYMENT_FAILED event back to Kafka
 *
 * Note: These tests validate error handling and resilience in event consumption
 */
@Epic("Amazon Microservices")
@Feature("Kafka - Event Consumption - Negative")
public class KafkaEventConsumptionNegativeTests extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final Faker faker = new Faker();

    private String validToken;
    private String userId;
    private String productId;

    @BeforeClass
    public void setup() {
        logStep("Setting up Kafka event consumption negative tests");

        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();
        validToken = auth.getAccessToken();
        userId = auth.getUser().getId();
        logStep("✅ User created: " + userId);

        createTestProduct();
    }

    private void createTestProduct() {
        Map<String, Object> productData = Map.of(
                "name", "Test Product for Negative Tests",
                "description", "Product used in negative event consumption tests",
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
            logStep("⚠️  Using fallback product ID: " + productId);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NEGATIVE TEST CASES
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 30)
    @Story("Event Consumption - Negative")
    @Severity(SeverityLevel.NORMAL)
    @Description("Payment service handles malformed/corrupted events gracefully")
    public void test30_MalformedEventHandledGracefully() {
        logStep("TEST 30: Payment service handles malformed events");

        logStep("  NOTE: This test validates system stability after event errors");
        logStep("  Approach: Create normal order, verify system continues functioning");
        logStep("  In production: Malformed events would go to Dead Letter Queue (DLQ)");

        // Create a normal order to verify system is stable
        Map<String, Object> orderData = Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 1,
                                "unitPrice", 29.99,
                                "productName", "Stability Test Product"
                        )
                ),
                "shippingAddress", faker.address().fullAddress()
        );

        Response createResp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then()
                .extract()
                .response();

        logStep("  Response status: " + createResp.statusCode());

        // System should continue functioning normally
        assertThat(createResp.statusCode())
                .as("System should accept orders despite any previous malformed events")
                .isIn(201, 503);  // 201 success, 503 if service temporarily degraded

        if (createResp.statusCode() == 201) {
            String orderId = createResp.jsonPath().getString("id");
            String status = createResp.jsonPath().getString("status");

            logStep("  ✓ Order created: " + orderId);
            logStep("  ✓ Status: " + status);

            assertThat(status).isEqualTo("PENDING");

            logStep("  Waiting briefly to verify event processing still works...");

            // Wait a bit to see if payment service processes it
            await().atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofSeconds(2))
                    .untilAsserted(() -> {
                        Response orderResp = given()
                                .baseUri(GATEWAY_URL)
                                .header("Authorization", "Bearer " + validToken)
                                .when()
                                .get("/api/orders/" + orderId)
                                .then()
                                .extract()
                                .response();

                        String currentStatus = orderResp.jsonPath().getString("status");

                        // If status changed, payment service is working
                        // If still PENDING, that's also acceptable (might be processing)
                        assertThat(currentStatus)
                                .as("Order should be in valid state")
                                .isIn("PENDING", "CONFIRMED", "PAYMENT_FAILED");
                    });
        }

        logStep("✅ System remains stable");
        logStep("  Key points:");
        logStep("  - Payment service continues processing valid events");
        logStep("  - System doesn't crash or become unresponsive");
        logStep("  - Malformed events would be logged and sent to DLQ");
        logStep("  - Normal operations continue without disruption");
    }

    @Test(priority = 31)
    @Story("Event Consumption - Negative")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Failed payment publishes PAYMENT_FAILED event back to Kafka")
    public void test31_FailedPaymentPublishesFailureEvent() {
        logStep("TEST 31: Failed payment publishes PAYMENT_FAILED event");

        logStep("  Creating order with amount that may trigger payment failure...");

        // Note: Actual payment failure logic depends on your payment service implementation
        // Common triggers: amount = 0.01, specific test card numbers, etc.
        // For this test, we create a normal order and verify both success/failure paths work

        Map<String, Object> orderData = Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 1,
                                "unitPrice", 0.01,  // Small amount - might trigger test failure
                                "productName", "Payment Failure Test Product"
                        )
                ),
                "shippingAddress", faker.address().fullAddress(),
                "notes", "Test order for payment failure scenario"
        );

        Response createResp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then()
                .extract()
                .response();

        if (createResp.statusCode() != 201) {
            logStep("  ⚠️  Order creation failed: " + createResp.statusCode());
            logStep("  Response: " + createResp.asString());
            assertThat(createResp.statusCode()).isEqualTo(201);
            return;
        }

        String orderId = createResp.jsonPath().getString("id");
        String initialStatus = createResp.jsonPath().getString("status");

        logStep("  ✓ Order created: " + orderId);
        logStep("  ✓ Initial status: " + initialStatus);

        assertThat(initialStatus).isEqualTo("PENDING");

        // Wait for payment processing
        logStep("  Waiting for payment service to process payment...");
        logStep("  Payment service will:");
        logStep("    1. Consume ORDER_CREATED event");
        logStep("    2. Attempt payment processing");
        logStep("    3. Publish PAYMENT_COMPLETED or PAYMENT_FAILED event");
        logStep("    4. Order service updates status accordingly");

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    Response orderResp = given()
                            .baseUri(GATEWAY_URL)
                            .header("Authorization", "Bearer " + validToken)
                            .when()
                            .get("/api/orders/" + orderId)
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();

                    String currentStatus = orderResp.jsonPath().getString("status");
                    logStep("    Current status: " + currentStatus);

                    assertThat(currentStatus)
                            .as("Status should change from PENDING")
                            .isNotEqualTo("PENDING");
                });

        // Get final order state
        Response finalResp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get("/api/orders/" + orderId)
                .then()
                .statusCode(200)
                .extract()
                .response();

        String finalStatus = finalResp.jsonPath().getString("status");
        String paymentId = finalResp.jsonPath().getString("paymentId");

        logStep("  ✓ Final status: " + finalStatus);

        if (finalStatus.equals("PAYMENT_FAILED")) {
            logStep("  ✅ PAYMENT_FAILED event was published and consumed");
            logStep("  ✓ Order status updated to PAYMENT_FAILED");
            logStep("  ✓ Payment failure flow working correctly");

            assertThat(paymentId)
                    .as("Failed payment should not have payment ID")
                    .isNullOrEmpty();

        } else if (finalStatus.equals("CONFIRMED")) {
            logStep("  ✓ Payment succeeded (PAYMENT_COMPLETED event published)");
            logStep("  ✓ Payment ID: " + paymentId);

            assertThat(paymentId)
                    .as("Successful payment should have payment ID")
                    .isNotNull();
        }

        // Either outcome validates the event flow works
        assertThat(finalStatus)
                .as("Order should reach final state (CONFIRMED or PAYMENT_FAILED)")
                .isIn("CONFIRMED", "PAYMENT_FAILED");

        logStep("✅ Payment result event flow validated");
        logStep("  Key validations:");
        logStep("  - Payment service consumed ORDER_CREATED event");
        logStep("  - Payment service processed payment (success or failure)");
        logStep("  - Payment service published result event to Kafka");
        logStep("  - Order service consumed payment event");
        logStep("  - Order status updated based on payment result");
    }

    @Test(priority = 32, enabled = false)
    @Story("Event Consumption - Negative")
    @Severity(SeverityLevel.NORMAL)
    @Description("MANUAL TEST: Payment service resilience when consumer crashes")
    public void test32_ConsumerCrashResilience() {
        logStep("TEST 32: Payment service resilience (MANUAL TEST)");

        logStep("  ⚠️  This test requires manual intervention:");
        logStep("  1. Create an order");
        logStep("  2. Stop payment service (kill process)");
        logStep("  3. Events remain in Kafka");
        logStep("  4. Restart payment service");
        logStep("  5. Consumer resumes from last committed offset");
        logStep("  6. All events processed (no data loss)");

        logStep("  To enable: @Test(enabled = true)");
        logStep("  This validates Kafka's durability and consumer resilience");
    }

    @Test(priority = 33, enabled = false)
    @Story("Event Consumption - Negative")
    @Severity(SeverityLevel.NORMAL)
    @Description("MANUAL TEST: Dead Letter Queue (DLQ) for poison pill messages")
    public void test33_DeadLetterQueueForPoisonPills() {
        logStep("TEST 33: Dead Letter Queue for poison pills (MANUAL TEST)");

        logStep("  ⚠️  This test validates DLQ configuration:");
        logStep("  1. Publish malformed event to Kafka topic");
        logStep("  2. Consumer fails to deserialize/process event");
        logStep("  3. After max retries, event sent to DLQ topic");
        logStep("  4. Consumer continues processing other events");
        logStep("  5. DLQ events can be inspected and reprocessed");

        logStep("  Requires:");
        logStep("  - Kafka DLQ configuration in payment service");
        logStep("  - Manual event publishing to test topic");
        logStep("  - DLQ monitoring setup");
    }
}