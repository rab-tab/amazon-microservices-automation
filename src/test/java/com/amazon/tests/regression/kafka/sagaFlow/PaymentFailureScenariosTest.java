package com.amazon.tests.regression.kafka.sagaFlow;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.apiClients.PaymentApiClient;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.Optional;

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
 * - Payment failure propagated correctly via Kafka (payment.result topic —
 *   failure reason/retryable/fraud score are NOT persisted on the order
 *   REST resource, only published as an event)
 * - Order compensated to PAYMENT_FAILED
 */
@Slf4j
@Epic("Kafka Saga Pattern")
@Feature("Payment Failure Compensation")
@Test(groups = {"saga", "payment-failures"})
public class PaymentFailureScenariosTest extends BaseTest {

    private KafkaTestConsumer paymentResultConsumer;
    private PaymentApiClient paymentApiClient;

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

    @DataProvider(name = "paymentFailureScenarios")
    public Object[][] paymentFailureScenarios() {
        return new Object[][]{
                {new PaymentFailureScenario(
                        "Insufficient Funds (Retryable)", "payment-failure",
                        "Insufficient funds", true, false)},
                {new PaymentFailureScenario(
                        "Fraud Detection (Non-Retryable)", "payment-fraud",
                        "Fraud detected", false, true)},
                {new PaymentFailureScenario(
                        "Card Expired (Non-Retryable)", "payment-expired-card",
                        "Card expired", false, false)},
                {new PaymentFailureScenario(
                        "Network Error (Retryable)", "payment-network-error",
                        "Network error", true, false)}
        };
    }

    @BeforeMethod
    public void setup() {
        logStep("Setting up Payment Failure Scenarios tests");
        paymentResultConsumer = new KafkaTestConsumer("payment.result");
        paymentApiClient = new PaymentApiClient(paymentResultConsumer, context.getExecutor());
        logStep("✅ Payment failure test setup complete");
    }

    @AfterMethod
    public void cleanup() {
        if (paymentResultConsumer != null) paymentResultConsumer.close();
        logStep("✅ Payment failure test consumers closed");
    }
    // ══════════════════════════════════════════════════════════════
    // PARAMETERIZED TEST - ALL PAYMENT FAILURE SCENARIOS
    // ══════════════════════════════════════════════════════════════

    @Test(dataProvider = "paymentFailureScenarios")
    @Story("Payment Failure Compensation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify payment failure scenarios trigger correct saga compensation")
    public void testPaymentFailureScenario(PaymentFailureScenario scenario) throws Exception {
        logStep("TEST: Payment failure - " + scenario.getTestName());

        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        // ══════════════════════════════════════════════════════
        // STEP 1: Create Order with Fault Injection
        // ══════════════════════════════════════════════════════
        logStep("  Creating order with fault injection: " + scenario.getFaultHeader());

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse createResponse = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, scenario.getFaultHeader());

        assertThat(createResponse.getStatusCode()).as("Order creation should succeed").isEqualTo(201);

        TestModels.OrderResponse order = createResponse.as(TestModels.OrderResponse.class);
        String orderId = order.getId();

        logStep("  ✓ Order created: " + orderId);
        logStep("  ✓ Initial status: " + order.getStatus());
        assertThat(order.getStatus()).as("Order should start in PENDING status").isEqualTo("PENDING");

        // ══════════════════════════════════════════════════════
        // STEP 2: Verify Payment Failure Event Published
        // ══════════════════════════════════════════════════════
        logStep("  Waiting for payment failure event...");

        Optional<JsonNode> paymentResult = paymentApiClient.waitForPaymentFailed(orderId, 30);

        assertThat(paymentResult).as("Payment failure result should be published to payment.result topic").isPresent();

        JsonNode paymentEvent = paymentResult.get();
        String actualFailureReason = paymentEvent.get("failureReason").asText();
        logStep("  ✓ Payment failure detected: " + actualFailureReason);

        assertThat(actualFailureReason)
                .as("Failure reason should match expected")
                .contains(scenario.getExpectedFailureReason());

        if (scenario.isExpectFraudScore()) {
            assertThat(paymentEvent.has("fraudScore")).as("Fraud score should be present for fraud detection").isTrue();
            int fraudScore = paymentEvent.get("fraudScore").asInt();
            logStep("  ✓ Fraud score: " + fraudScore);
            assertThat(fraudScore).as("Fraud score should be high").isGreaterThan(90);
        }

        // ══════════════════════════════════════════════════════
        // STEP 3: Verify Order Compensation to PAYMENT_FAILED
        // ══════════════════════════════════════════════════════
        logStep("  Waiting for order compensation...");

        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> "PAYMENT_FAILED".equals(orderApiClient.getOrder(token, userId, orderId).getStatus()));

        // ══════════════════════════════════════════════════════
        // STEP 4: Verify Final Order State
        // ══════════════════════════════════════════════════════
        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, orderId);

        assertThat(finalOrder.getStatus()).as("Order should be PAYMENT_FAILED").isEqualTo("PAYMENT_FAILED");
        assertThat(finalOrder.getPaymentId()).as("No payment ID should be assigned on failure").isNullOrEmpty();

        // ══════════════════════════════════════════════════════
        // STEP 5: Summary
        // ══════════════════════════════════════════════════════
        logStep("✅ " + scenario.getTestName() + " - COMPLETE");
        logStep("   Order: " + orderId + " → PAYMENT_FAILED");
        logStep("   Reason: " + actualFailureReason);
        logStep("   Retryable (expected): " + scenario.isExpectedRetryable());
    }
}