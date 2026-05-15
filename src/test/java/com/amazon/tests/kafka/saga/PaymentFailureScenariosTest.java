package com.amazon.tests.kafka.saga;


import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.KafkaTestConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Payment Failure Scenarios - Comprehensive Testing
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Tests all payment failure scenarios to ensure proper Saga compensation
 * for different failure types:
 *
 * 1. INSUFFICIENT_FUNDS - Retryable, customer action needed
 * 2. FRAUD - Non-retryable, order blocked
 * 3. CARD_EXPIRED - Non-retryable, update payment method
 * 4. NETWORK_ERROR - Retryable, transient failure
 *
 * Each test verifies:
 * - Order created with status PENDING
 * - Payment failure propagated correctly
 * - Order compensated to PAYMENT_FAILED
 * - Correct failure reason stored
 * - Retryable flag set appropriately
 *
 * @author Test Automation Team
 */
@Test(groups = {"saga", "payment-failures"})
public class PaymentFailureScenariosTest extends BaseTest {

    private KafkaTestConsumer orderEventsConsumer;
    private KafkaTestConsumer paymentResultConsumer;
    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;

    @BeforeClass
    public void setupClass() throws SeedingException {
        logStep("Setting up Payment Failure Scenarios tests");

        // Seed test data once for all tests
        user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        userToken = context.getCached("user_token_" + user.getId(), String.class);
        product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        // Initialize Kafka consumers
        orderEventsConsumer = new KafkaTestConsumer("order.events");
        paymentResultConsumer = new KafkaTestConsumer("payment.result");

        logStep("✅ Payment failure test setup complete");
    }

    @BeforeMethod
    public void beforeEachTest() {
        // Seek to end before each test to ignore historical events
        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();

        // Small delay to ensure seek completes
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterClass
    public void cleanup() {
        if (orderEventsConsumer != null) {
            orderEventsConsumer.close();
        }
        if (paymentResultConsumer != null) {
            paymentResultConsumer.close();
        }
        logStep("✅ Payment failure test consumers closed");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 1: INSUFFICIENT FUNDS - Retryable Failure
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 1)
    public void test01_InsufficientFunds_RetryableFailure() throws Exception {
        logStep("TEST 1: Payment failure - Insufficient funds (retryable)");

        String idempotencyKey = UUID.randomUUID().toString();
        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 2)
                .build();

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Create Order with Insufficient Funds Scenario
        // ═══════════════════════════════════════════════════════════════
        logStep("  Creating order with insufficient funds scenario...");

        Response createResponse = given()
                .contentType("application/json")
                .header("X-User-Id", user.getId().toString())
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Fault", "payment-failure")  // Maps to INSUFFICIENT_FUNDS
                .body(orderRequest)
                .when()
                .post( context.getConfig().baseUrl()+"/api/v1/orders");

        assertThat(createResponse.statusCode())
                .as("Order creation should succeed")
                .isEqualTo(201);

        String orderId = createResponse.jsonPath().getString("id");
        logStep("  ✓ Order created: " + orderId);

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Verify Payment Failure
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for payment failure...");

        Optional<JsonNode> paymentResult = paymentResultConsumer.waitForMessage(
                node -> orderId.equals(node.get("orderId").asText()) &&
                        "FAILED".equals(node.get("status").asText()),
                15
        );

        assertThat(paymentResult)
                .as("Payment failure result should be published")
                .isPresent();

        assertThat(paymentResult.get().get("failureReason").asText())
                .as("Failure reason should be insufficient funds")
                .isEqualTo("Insufficient funds");

        logStep("  ✓ Payment failed with correct reason");

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Verify Order Compensation
        // ═══════════════════════════════════════════════════════════════
        logStep("  Verifying order compensation...");

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    Response response = given()
                            .header("X-User-Id", user.getId().toString())
                            .when()
                            .get(context.getConfig().baseUrl() + "/api/v1/orders/" + orderId);

                    return "PAYMENT_FAILED".equals(response.jsonPath().getString("status"));
                });

        Response finalOrder = given()
                .header("X-User-Id", user.getId().toString())
                .when()
                .get(context.getConfig().baseUrl() + "/api/v1/orders/" + orderId);

        assertThat(finalOrder.jsonPath().getString("status"))
                .as("Order should be compensated to PAYMENT_FAILED")
                .isEqualTo("PAYMENT_FAILED");

        assertThat(finalOrder.jsonPath().getString("paymentFailureReason"))
                .as("Failure reason should be stored")
                .isEqualTo("Insufficient funds");

        assertThat(finalOrder.jsonPath().getBoolean("paymentRetryable"))
                .as("Payment should be retryable for insufficient funds")
                .isTrue();

        logStep("  ✅ Insufficient funds scenario complete - Order compensated, retryable=true");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 2: FRAUD DETECTION - Non-Retryable Failure
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 2)
    public void test02_FraudDetection_NonRetryableFailure() throws Exception {
        logStep("TEST 2: Payment failure - Fraud detection (non-retryable)");

        String idempotencyKey = UUID.randomUUID().toString();
        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Create Order with Fraud Detection Scenario
        // ═══════════════════════════════════════════════════════════════
        logStep("  Creating order with fraud detection scenario...");

        Response createResponse = given().baseUri(context.getConfig().baseUrl())
                .contentType("application/json")
                .header("X-User-Id", user.getId().toString())
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Fault", "payment-fraud")  // Maps to FRAUD
                .body(orderRequest)
                .when()
                .post( "/api/v1/orders");

        assertThat(createResponse.statusCode()).isEqualTo(201);

        String orderId = createResponse.jsonPath().getString("id");
        logStep("  ✓ Order created: " + orderId);

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Verify Fraud Detection
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for fraud detection...");

        Optional<JsonNode> paymentResult = paymentResultConsumer.waitForMessage(
                node -> orderId.equals(node.get("orderId").asText()) &&
                        "FAILED".equals(node.get("status").asText()),
                15
        );

        assertThat(paymentResult).isPresent();

        assertThat(paymentResult.get().get("failureReason").asText())
                .as("Failure reason should indicate fraud")
                .isEqualTo("Fraud detected");

        // Verify fraud score is present
        assertThat(paymentResult.get().has("fraudScore"))
                .as("Fraud score should be included")
                .isTrue();

        assertThat(paymentResult.get().get("fraudScore").asInt())
                .as("Fraud score should be high")
                .isGreaterThan(90);

        logStep("  ✓ Fraud detected with high fraud score");

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Verify Order Compensation (Non-Retryable)
        // ═══════════════════════════════════════════════════════════════
        logStep("  Verifying order compensation...");

        await().atMost(Duration.ofSeconds(10))
                .until(() -> {
                    Response response = given()
                            .header("X-User-Id", user.getId().toString())
                            .when()
                            .get(context.getConfig().baseUrl() + "/api/v1/orders/" + orderId);

                    return "PAYMENT_FAILED".equals(response.jsonPath().getString("status"));
                });

        Response finalOrder = given()
                .header("X-User-Id", user.getId().toString())
                .when()
                .get(context.getConfig().baseUrl() + "/api/v1/orders/" + orderId);

        assertThat(finalOrder.jsonPath().getString("status")).isEqualTo("PAYMENT_FAILED");
        assertThat(finalOrder.jsonPath().getString("paymentFailureReason"))
                .isEqualTo("Fraud detected");

        assertThat(finalOrder.jsonPath().getBoolean("paymentRetryable"))
                .as("Payment should NOT be retryable for fraud")
                .isFalse();

        assertThat(finalOrder.jsonPath().getInt("paymentFraudScore"))
                .as("Fraud score should be stored")
                .isGreaterThan(90);

        logStep("  ✅ Fraud detection scenario complete - Order blocked, retryable=false");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 3: CARD EXPIRED - Non-Retryable Failure
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 3)
    public void test03_CardExpired_NonRetryableFailure() throws Exception {
        logStep("TEST 3: Payment failure - Card expired (non-retryable)");

        String idempotencyKey = UUID.randomUUID().toString();
        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Create Order with Expired Card Scenario
        // ═══════════════════════════════════════════════════════════════
        logStep("  Creating order with expired card scenario...");

        Response createResponse = given()
                .contentType("application/json")
                .header("X-User-Id", user.getId().toString())
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Fault", "payment-expired-card")  // Maps to CARD_EXPIRED
                .body(orderRequest)
                .when()
                .post(context.getConfig().baseUrl() + "/api/v1/orders");

        assertThat(createResponse.statusCode()).isEqualTo(201);

        String orderId = createResponse.jsonPath().getString("id");
        logStep("  ✓ Order created: " + orderId);

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Verify Card Expired Failure
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for card expired failure...");

        Optional<JsonNode> paymentResult = paymentResultConsumer.waitForMessage(
                node -> orderId.equals(node.get("orderId").asText()) &&
                        "FAILED".equals(node.get("status").asText()),
                15
        );

        assertThat(paymentResult).isPresent();

        assertThat(paymentResult.get().get("failureReason").asText())
                .as("Failure reason should indicate expired card")
                .isEqualTo("Card expired");

        logStep("  ✓ Card expired failure detected");

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Verify Order Compensation
        // ═══════════════════════════════════════════════════════════════
        await().atMost(Duration.ofSeconds(10))
                .until(() -> {
                    Response response = given()
                            .header("X-User-Id", user.getId().toString())
                            .when()
                            .get(context.getConfig().baseUrl() + "/api/v1/orders/" + orderId);

                    return "PAYMENT_FAILED".equals(response.jsonPath().getString("status"));
                });

        Response finalOrder = given()
                .header("X-User-Id", user.getId().toString())
                .when()
                .get(context.getConfig().baseUrl() + "/api/v1/orders/" + orderId);

        assertThat(finalOrder.jsonPath().getString("status")).isEqualTo("PAYMENT_FAILED");
        assertThat(finalOrder.jsonPath().getString("paymentFailureReason"))
                .isEqualTo("Card expired");

        assertThat(finalOrder.jsonPath().getBoolean("paymentRetryable"))
                .as("Payment should NOT be retryable for expired card")
                .isFalse();

        logStep("  ✅ Card expired scenario complete - Order failed, retryable=false");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 4: NETWORK ERROR - Retryable Failure
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 4)
    public void test04_NetworkError_RetryableFailure() throws Exception {
        logStep("TEST 4: Payment failure - Network error (retryable)");

        String idempotencyKey = UUID.randomUUID().toString();
        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Create Order with Network Error Scenario
        // ═══════════════════════════════════════════════════════════════
        logStep("  Creating order with network error scenario...");

        Response createResponse = given()
                .contentType("application/json")
                .header("X-User-Id", user.getId().toString())
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Fault", "payment-network-error")  // Maps to NETWORK_ERROR
                .body(orderRequest)
                .when()
                .post(context.getConfig().baseUrl() + "/api/v1/orders");

        assertThat(createResponse.statusCode()).isEqualTo(201);

        String orderId = createResponse.jsonPath().getString("id");
        logStep("  ✓ Order created: " + orderId);

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Verify Network Error Failure
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for network error failure...");

        Optional<JsonNode> paymentResult = paymentResultConsumer.waitForMessage(
                node -> orderId.equals(node.get("orderId").asText()) &&
                        "FAILED".equals(node.get("status").asText()),
                15
        );

        assertThat(paymentResult).isPresent();

        assertThat(paymentResult.get().get("failureReason").asText())
                .as("Failure reason should indicate network error")
                .contains("Network error");

        logStep("  ✓ Network error failure detected");

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Verify Order Compensation (Retryable)
        // ═══════════════════════════════════════════════════════════════
        await().atMost(Duration.ofSeconds(10))
                .until(() -> {
                    Response response = given()
                            .header("X-User-Id", user.getId().toString())
                            .when()
                            .get(context.getConfig().baseUrl() + "/api/v1/orders/" + orderId);

                    return "PAYMENT_FAILED".equals(response.jsonPath().getString("status"));
                });

        Response finalOrder = given()
                .header("X-User-Id", user.getId().toString())
                .when()
                .get(context.getConfig().baseUrl() + "/api/v1/orders/" + orderId);

        assertThat(finalOrder.jsonPath().getString("status")).isEqualTo("PAYMENT_FAILED");
        assertThat(finalOrder.jsonPath().getString("paymentFailureReason"))
                .contains("Network error");

        assertThat(finalOrder.jsonPath().getBoolean("paymentRetryable"))
                .as("Payment should be retryable for network errors")
                .isTrue();

        logStep("  ✅ Network error scenario complete - Order failed, retryable=true");
    }
}
