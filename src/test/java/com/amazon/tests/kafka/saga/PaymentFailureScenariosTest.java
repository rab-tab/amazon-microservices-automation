package com.amazon.tests.kafka.saga;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.KafkaTestConsumer;
import com.amazon.tests.utils.TestMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.*;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Payment Failure Scenarios - Data-Driven Testing
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Single parameterized test covering all payment failure scenarios:
 *
 * SCENARIOS TESTED:
 * 1. INSUFFICIENT_FUNDS - Retryable, customer action needed
 * 2. FRAUD - Non-retryable, order blocked, includes fraud score
 * 3. CARD_EXPIRED - Non-retryable, update payment method
 * 4. NETWORK_ERROR - Retryable, transient failure
 *
 * Each scenario verifies:
 * - Order created with status PENDING
 * - Payment failure propagated correctly via Kafka
 * - Order compensated to PAYMENT_FAILED
 * - Correct failure reason stored
 * - Retryable flag set appropriately
 * - Additional fields (e.g., fraud score) when applicable
 *
 * @author Test Automation Team
 */
@Slf4j
@Epic("Kafka Saga Pattern")
@Feature("Payment Failure Compensation")
@Test(groups = {"saga", "payment-failures"})
public class PaymentFailureScenariosTest extends BaseTest {

    private KafkaTestConsumer orderEventsConsumer;
    private KafkaTestConsumer paymentResultConsumer;
    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;
    private TestMetrics metrics;

    /**
     * Payment failure scenario configuration
     */
    @Getter
    @AllArgsConstructor
    public static class PaymentFailureScenario {
        private final String testName;
        private final String faultHeader;
        private final String expectedFailureReason;
        private final boolean expectedRetryable;
        private final boolean expectFraudScore;

        @Override
        public String toString() {
            return testName;
        }
    }

    /**
     * Data provider for payment failure scenarios
     */
    @DataProvider(name = "paymentFailureScenarios")
    public Object[][] paymentFailureScenarios() {
        return new Object[][]{
                {new PaymentFailureScenario(
                        "Insufficient Funds (Retryable)",
                        "payment-failure",
                        "Insufficient funds",
                        true,   // retryable
                        false   // no fraud score
                )},
                {new PaymentFailureScenario(
                        "Fraud Detection (Non-Retryable)",
                        "payment-fraud",
                        "Fraud detected",
                        false,  // not retryable
                        true    // has fraud score
                )},
                {new PaymentFailureScenario(
                        "Card Expired (Non-Retryable)",
                        "payment-expired-card",
                        "Card expired",
                        false,  // not retryable
                        false   // no fraud score
                )},
                {new PaymentFailureScenario(
                        "Network Error (Retryable)",
                        "payment-network-error",
                        "Network error",
                        true,   // retryable
                        false   // no fraud score
                )}
        };
    }

    @BeforeMethod
    public void setupClass() throws SeedingException {
        logStep("Setting up Payment Failure Scenarios tests");

        metrics=new TestMetrics();
        // Seed test data once for all tests
        user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        userToken = context.getCached("user_token_" + user.getId(), String.class);
        product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        // Initialize Kafka consumers
        orderEventsConsumer = new KafkaTestConsumer(metrics,"order.events");
        paymentResultConsumer = new KafkaTestConsumer(metrics,"payment.result");

        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();

        logStep("✅ Payment failure test setup complete");
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
    // PARAMETERIZED TEST - ALL PAYMENT FAILURE SCENARIOS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(dataProvider = "paymentFailureScenarios")
    @Story("Payment Failure Compensation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify payment failure scenarios trigger correct saga compensation")
    public void testPaymentFailureScenario(PaymentFailureScenario scenario) throws Exception {
        logStep("TEST: Payment failure - {}", scenario.getTestName());

        String idempotencyKey = UUID.randomUUID().toString();

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Create Order with Fault Injection
        // ═══════════════════════════════════════════════════════════════
        logStep("  Creating order with fault injection: {}", scenario.getFaultHeader());

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 2)
                .build();

        Response createResponse = executeWithRetry(() -> {
            try {
                return sendOrderRequestWithFault(
                        userToken,
                        idempotencyKey,
                        orderRequest,
                        scenario.getFaultHeader()
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(createResponse.statusCode())
                .as("Order creation should succeed")
                .isEqualTo(201);

        String orderId = createResponse.jsonPath().getString("id");
        String initialStatus = createResponse.jsonPath().getString("status");

        logStep("  ✓ Order created: {}", orderId);
        logStep("  ✓ Initial status: {}", initialStatus);

        assertThat(initialStatus)
                .as("Order should start in PENDING status")
                .isEqualTo("PENDING");

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Verify Payment Failure Event Published
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for payment failure event...");

        Optional<JsonNode> paymentResult = paymentResultConsumer.waitForMessage(
                node -> node.has("orderId") &&
                        orderId.equals(node.get("orderId").asText()) &&
                        "FAILED".equals(node.get("status").asText()),
                30
        );

        assertThat(paymentResult)
                .as("Payment failure result should be published to payment.result topic")
                .isPresent();

        JsonNode paymentEvent = paymentResult.get();

        // Verify failure reason
        String actualFailureReason = paymentEvent.get("failureReason").asText();
        logStep("  ✓ Payment failure detected: {}", actualFailureReason);

        assertThat(actualFailureReason)
                .as("Failure reason should match expected")
                .contains(scenario.getExpectedFailureReason());

        // Verify fraud score if expected
        if (scenario.isExpectFraudScore()) {
            assertThat(paymentEvent.has("fraudScore"))
                    .as("Fraud score should be present for fraud detection")
                    .isTrue();

            int fraudScore = paymentEvent.get("fraudScore").asInt();
            logStep("  ✓ Fraud score: {}", fraudScore);

            assertThat(fraudScore)
                    .as("Fraud score should be high")
                    .isGreaterThan(90);
        }

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Verify Order Compensation to PAYMENT_FAILED
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for order compensation...");

        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> {
                    Response response = given()
                            .header("X-User-Id", user.getId().toString())
                            .header("Authorization", "Bearer " + userToken)
                            .when()
                            .get(context.getConfig().baseUrl() + "/api/orders/" + orderId);

                    return "PAYMENT_FAILED".equals(response.jsonPath().getString("status"));
                });

        // ═══════════════════════════════════════════════════════════════
        // STEP 4: Verify Final Order State
        // ═══════════════════════════════════════════════════════════════
        Response finalOrder = given()
                .header("X-User-Id", user.getId().toString())
                .header("Authorization", "Bearer " + userToken)
                .when()
                .get(context.getConfig().baseUrl() + "/api/orders/" + orderId);

        String finalStatus = finalOrder.jsonPath().getString("status");
        String storedFailureReason = finalOrder.jsonPath().getString("paymentFailureReason");
        Boolean retryable = finalOrder.jsonPath().getBoolean("paymentRetryable");
        String paymentId = finalOrder.jsonPath().getString("paymentId");

        logStep("  ✓ Final order status: {}", finalStatus);
        logStep("  ✓ Failure reason: {}", storedFailureReason);
        logStep("  ✓ Retryable: {}", retryable);

        // Assert final state
        assertThat(finalStatus)
                .as("Order should be compensated to PAYMENT_FAILED")
                .isEqualTo("PAYMENT_FAILED");

        assertThat(storedFailureReason)
                .as("Failure reason should be stored in order")
                .contains(scenario.getExpectedFailureReason());

        assertThat(retryable)
                .as("Retryable flag should match scenario expectation")
                .isEqualTo(scenario.isExpectedRetryable());

        assertThat(paymentId)
                .as("No payment ID should be assigned on failure")
                .isNullOrEmpty();

        // Verify fraud score stored in order (if applicable)
        if (scenario.isExpectFraudScore()) {
            Integer storedFraudScore = finalOrder.jsonPath().getInt("paymentFraudScore");
            assertThat(storedFraudScore)
                    .as("Fraud score should be stored in order")
                    .isGreaterThan(90);
            logStep("  ✓ Fraud score stored: {}", storedFraudScore);
        }

        // ═══════════════════════════════════════════════════════════════
        // STEP 5: Summary
        // ═══════════════════════════════════════════════════════════════
        logStep("✅ {} - COMPLETE", scenario.getTestName());
        logStep("   Order: {} → PAYMENT_FAILED", orderId);
        logStep("   Reason: {}", storedFailureReason);
        logStep("   Retryable: {}", retryable);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════════

    private Response sendOrderRequestWithFault(
            String userToken,
            String idempotencyKey,
            TestModels.CreateOrderRequest orderRequest,
            String faultType) throws Exception {

        String requestBody = objectMapper.writeValueAsString(orderRequest);

        return RestAssured
                .given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("X-User-Id", user.getId().toString())
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Fault", faultType)
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/orders");
    }
}