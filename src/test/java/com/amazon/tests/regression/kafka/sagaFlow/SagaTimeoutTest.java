package com.amazon.tests.regression.kafka.sagaFlow;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Saga Timeout Scenarios - Async Timeout Handling
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Tests timeout scenario in Saga pattern when Payment Service doesn't respond.
 *
 * REAL-WORLD SCENARIOS:
 * - Payment gateway down
 * - Payment Service crashed
 * - Network partition between services
 * - Kafka consumer lag/down
 *
 * EXPECTED BEHAVIOR:
 * - Order created successfully (status: PENDING)
 * - ORDER_CREATED event published to Kafka
 * - NO payment result received (timeout simulation)
 * - Order REMAINS in PENDING state (no automatic compensation)
 * - Order stays queryable throughout
 * - Manual intervention or retry mechanism needed in production
 */
@Slf4j
@Epic("Kafka Saga Pattern")
@Feature("Saga Timeout Handling")
@Test(groups = {"saga", "timeout"})
public class SagaTimeoutTest extends BaseTest {

    private static final int POLL_COUNT = 10;
    private static final long POLL_INTERVAL_MS = 1000;

    private KafkaTestConsumer orderEventsConsumer;
    private KafkaTestConsumer paymentResultConsumer;

    @BeforeMethod
    public void setup() {
        logStep("Setting up Saga Timeout test");

        orderEventsConsumer = new KafkaTestConsumer("order.events");
        paymentResultConsumer = new KafkaTestConsumer("payment.result");

        logStep("✅ Saga timeout test setup complete");
    }

    @AfterMethod
    public void cleanup() {
        if (orderEventsConsumer != null) orderEventsConsumer.close();
        if (paymentResultConsumer != null) paymentResultConsumer.close();
        logStep("✅ Saga timeout test cleanup complete");
    }

    // ══════════════════════════════════════════════════════════════
    // COMPREHENSIVE TIMEOUT TEST
    // ══════════════════════════════════════════════════════════════

    @Test
    @Story("Payment Timeout")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Order remains PENDING when Payment Service doesn't respond, stays queryable, no auto-compensation")
    public void testPaymentTimeout_NoResponseFromPaymentService() throws Exception {
        logStep("TEST: Payment timeout - No response from Payment Service");

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
        // STEP 1: Create Order with Timeout Scenario
        // Payment Service will NOT publish any response
        // ══════════════════════════════════════════════════════
        logStep("  Creating order with payment timeout scenario...");

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse createResponse = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, "payment-timeout");

        assertThat(createResponse.getStatusCode()).as("Order creation should succeed").isEqualTo(201);

        TestModels.OrderResponse order = createResponse.as(TestModels.OrderResponse.class);
        String orderId = order.getId();

        logStep("  ✓ Order created: " + orderId);
        logStep("  ✓ Initial status: " + order.getStatus());
        assertThat(order.getStatus()).as("Order should start in PENDING status").isEqualTo("PENDING");

        // ══════════════════════════════════════════════════════
        // STEP 2: Verify ORDER_CREATED Event Published
        // ══════════════════════════════════════════════════════
        logStep("  Verifying ORDER_CREATED event published to Kafka...");

        Optional<JsonNode> orderCreatedEvent = orderEventsConsumer.waitForMessage(
                node -> node.has("eventType")
                        && "ORDER_CREATED".equals(node.get("eventType").asText())
                        && orderId.equals(node.get("orderId").asText()),
                10
        );

        assertThat(orderCreatedEvent).as("ORDER_CREATED event should be published to order.events").isPresent();
        logStep("  ✓ ORDER_CREATED event published successfully");

        // ══════════════════════════════════════════════════════
        // STEP 3: Verify NO Payment Result (Timeout)
        // ══════════════════════════════════════════════════════
        logStep("  Waiting for payment result (expecting timeout)...");

        Optional<JsonNode> paymentResult = paymentResultConsumer.waitForMessage(
                node -> node.has("orderId") && orderId.equals(node.get("orderId").asText()),
                15  // should time out with no result
        );

        assertThat(paymentResult).as("Payment result should NOT be published (timeout scenario)").isEmpty();
        logStep("  ✓ No payment result received (timeout confirmed)");

        // ══════════════════════════════════════════════════════
        // STEP 4: Poll Order Status Over Time - Verify Remains PENDING
        // ══════════════════════════════════════════════════════
        logStep("  Polling order status over " + POLL_COUNT + " seconds to verify it remains PENDING...");

        for (int i = 1; i <= POLL_COUNT; i++) {
            Thread.sleep(POLL_INTERVAL_MS);

            TestModels.OrderResponse polled = orderApiClient.getOrder(token, userId, orderId);

            logStep(String.format("    Poll %d/%d: Status = %s", i, POLL_COUNT, polled.getStatus()));

            assertThat(polled.getStatus()).as("Order should remain PENDING throughout polling period").isEqualTo("PENDING");
            assertThat(polled.getPaymentId()).as("No payment ID should be assigned").isNull();
        }

        logStep("  ✓ Order remained in PENDING state for entire " + POLL_COUNT + " second period");

        // ══════════════════════════════════════════════════════
        // STEP 5: Verify Order Queryability
        // ══════════════════════════════════════════════════════
        logStep("  Verifying order queryability...");

        TestModels.OrderResponse queriedOrder = orderApiClient.getOrder(token, userId, orderId);
        assertThat(queriedOrder.getId()).as("Order ID should match").isEqualTo(orderId);
        assertThat(queriedOrder.getStatus()).as("Final status check - should still be PENDING").isEqualTo("PENDING");

        logStep("  ✓ Order queryable by ID");

        // Note: user-order-list endpoint not yet modeled on OrderApiClient — see call-out below.

        // ══════════════════════════════════════════════════════
        // STEP 6: Summary
        // ══════════════════════════════════════════════════════
        logStep("✅ TIMEOUT TEST COMPLETE - All validations passed:");
        logStep("   1. ✅ Order created successfully");
        logStep("   2. ✅ ORDER_CREATED event published to Kafka");
        logStep("   3. ✅ NO payment result received (timeout confirmed)");
        logStep("   4. ✅ Order remained PENDING for " + POLL_COUNT + " seconds (no auto-compensation)");
        logStep("   5. ✅ Order queryable by ID");
        logStep("");
        logStep("PRODUCTION NOTE: This order would require manual intervention or a retry mechanism "
                + "(retry payment, cancel after threshold, or alert operations).");
    }
}