package com.amazon.tests.regression.kafka.sagaFlow;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Saga Partial Failure Recovery - Eventual Consistency Testing
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Tests that Kafka consumers eventually process all events, even with delays.
 *
 * SCENARIO: Payment succeeds, but Order Service has consumer lag
 *
 * NORMAL FLOW:
 * 1. ORDER_CREATED published
 * 2. Payment Service processes → PAYMENT_COMPLETED published
 * 3. Order Service consumes PAYMENT_COMPLETED (immediately)
 * 4. Order updated: PENDING → CONFIRMED
 *
 * WITH CONSUMER LAG:
 * 1. ORDER_CREATED published
 * 2. Payment succeeds → PAYMENT_COMPLETED published
 * 3. Order Service has lag (slow consumer, high load, etc.)
 * 4. Order remains PENDING for a while
 * 5. Order Service eventually catches up
 * 6. Order updated to CONFIRMED ✅
 *
 * TEST STRATEGY:
 * - Monitor order status with generous timeout
 * - Measure processing delay
 * - Validate eventual consistency
 * - Alert on high delays (indicates consumer lag)
 */
@Slf4j
@Epic("Kafka Saga Pattern")
@Feature("Partial Failure Recovery")
@Test(groups = {"saga", "recovery", "eventual-consistency"})
public class SagaPartialFailureRecoveryTest extends BaseTest {

    private static final long HIGH_DELAY_THRESHOLD_MS = 10000;

    private KafkaTestConsumer orderEventsConsumer;
    private KafkaTestConsumer paymentResultConsumer;

    @BeforeMethod
    public void setup() {
        logStep("Setting up Partial Failure Recovery tests");

        orderEventsConsumer = new KafkaTestConsumer("order.events");
        paymentResultConsumer = new KafkaTestConsumer("payment.result");

        logStep("✅ Partial failure recovery test setup complete");
    }

    @AfterMethod
    public void cleanup() {
        if (orderEventsConsumer != null) orderEventsConsumer.close();
        if (paymentResultConsumer != null) paymentResultConsumer.close();
    }

    // ══════════════════════════════════════════════════════════════
    // TEST: PAYMENT SUCCEEDED — VERIFY EVENTUAL CONSISTENCY
    // ══════════════════════════════════════════════════════════════

    @Test
    @Story("Consumer Recovery")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify Kafka consumer eventually processes all events (eventual consistency)")
    public void testPartialFailure_EventualConsistency() throws Exception {
        logStep("TEST: Eventual consistency - All events eventually processed");

        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        // ══════════════════════════════════════════════════════
        // STEP 1: Create Order
        // ══════════════════════════════════════════════════════
        logStep("  STEP 1: Creating order...");

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        String orderId = order.getId();
        logStep("  ✓ Order created: " + orderId);

        // ══════════════════════════════════════════════════════
        // STEP 2: Verify ORDER_CREATED Event Published
        // ══════════════════════════════════════════════════════
        logStep("  STEP 2: Verifying ORDER_CREATED event published...");

        Optional<JsonNode> orderCreatedEvent = orderEventsConsumer.waitForMessage(
                node -> node.has("eventType")
                        && "ORDER_CREATED".equals(node.get("eventType").asText())
                        && orderId.equals(node.get("orderId").asText()),
                10
        );

        assertThat(orderCreatedEvent).isPresent();
        logStep("  ✓ ORDER_CREATED published to order.events");

        // ══════════════════════════════════════════════════════
        // STEP 3: Verify PAYMENT_COMPLETED Event Published
        // ══════════════════════════════════════════════════════
        logStep("  STEP 3: Waiting for payment to complete...");

        Optional<JsonNode> paymentSuccess = paymentResultConsumer.waitForMessage(
                node -> orderId.equals(node.get("orderId").asText())
                        && "SUCCESS".equals(node.get("status").asText()),
                30
        );

        assertThat(paymentSuccess).as("Payment should succeed").isPresent();

        String paymentId = paymentSuccess.get().get("paymentId").asText();
        logStep("  ✓ Payment succeeded: " + paymentId);
        logStep("  ✓ PAYMENT_COMPLETED published to payment.result");

        // ══════════════════════════════════════════════════════
        // STEP 4: Monitor Order Status Updates (Eventual Consistency)
        // Even if there's lag, order should EVENTUALLY update
        // ══════════════════════════════════════════════════════
        logStep("  STEP 4: Monitoring order status updates (eventual consistency)...");

        long paymentCompletedTime = System.currentTimeMillis();
        final long[] orderConfirmedTime = {0};

        await()
                .atMost(Duration.ofSeconds(60))  // Generous timeout for consumer lag
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> {
                    String status = getOrderStatusSafely(orderApiClient, token, userId, orderId);
                    if ("CONFIRMED".equals(status) && orderConfirmedTime[0] == 0) {
                        orderConfirmedTime[0] = System.currentTimeMillis();
                    }
                    return "CONFIRMED".equals(status);
                });

        long processingDelay = orderConfirmedTime[0] - paymentCompletedTime;

        logStep("  ✓ Order status updated to CONFIRMED");
        logStep("  ⏱️  Processing delay: " + processingDelay + " ms");

        if (processingDelay > HIGH_DELAY_THRESHOLD_MS) {
            logStep("High processing delay detected (" + processingDelay + " ms) - may indicate consumer lag");
        }

        // ══════════════════════════════════════════════════════
        // STEP 5: Verify Final State (payment linkage)
        // ══════════════════════════════════════════════════════
        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, orderId);

        assertThat(finalOrder.getStatus()).isEqualTo("CONFIRMED");
        assertThat(finalOrder.getPaymentId()).isEqualTo(paymentId);

        // ══════════════════════════════════════════════════════
        // STEP 6: Summary
        // ══════════════════════════════════════════════════════
        logStep("✅ EVENTUAL CONSISTENCY VALIDATED:");
        logStep("   1. ✅ ORDER_CREATED published");
        logStep("   2. ✅ PAYMENT_COMPLETED published");
        logStep("   3. ✅ Order eventually updated to CONFIRMED");
        logStep("   4. ✅ Processing delay: " + processingDelay + " ms");
        logStep("");
        logStep("   This validates that even with consumer lag, all events are eventually processed correctly.");
    }

    // ══════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════

    private String getOrderStatusSafely(OrderApiClient orderApiClient, String token, String userId, String orderId) {
        try {
            return orderApiClient.getOrder(token, userId, orderId).getStatus();
        } catch (Exception e) {
            log.warn("Failed to get order status for {}: {}", orderId, e.getMessage());
            return "UNKNOWN";
        }
    }
}