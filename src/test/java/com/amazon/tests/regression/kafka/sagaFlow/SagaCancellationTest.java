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

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Saga Cancellation - Reverse Saga Flow (Rollback After Success)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * FORWARD SAGA (Normal Flow):
 * 1. Order Created → PENDING
 * 2. Payment Processed → CONFIRMED
 *
 * REVERSE SAGA (Cancellation Flow):
 * 1. Order Cancelled → ORDER_CANCELLED event
 * 2. Payment Service receives cancellation
 * 3. Refund initiated
 * 4. REFUND_COMPLETED event published
 * 5. Order status updated → CANCELLED
 *
 * SCENARIOS COVERED:
 * 1. Cancel confirmed order → Full refund
 * 2. Cancel pending order → No refund needed
 * 3. Cancellation idempotency → Duplicate cancel requests
 *
 * KNOWN GAP: Refund-event verification (REFUND_INITIATED/REFUND_COMPLETED
 * on payment.result) is not currently asserted as a hard requirement in
 * any test below — kept as a soft/best-effort check with TODOs, since the
 * original suite could not confirm refunds are always published
 * synchronously vs. asynchronously. Worth resolving with the payment
 * service owner and converting to a hard assertion.
 */
@Slf4j
@Epic("Kafka Saga Pattern")
@Feature("Saga Cancellation & Refund")
@Test(groups = {"saga", "cancellation"})
public class SagaCancellationTest extends BaseTest {

    private KafkaTestConsumer orderEventsConsumer;
    private KafkaTestConsumer paymentResultConsumer;

    @BeforeMethod
    public void setup() {
        logStep("Setting up Saga Cancellation tests");

        orderEventsConsumer = new KafkaTestConsumer("order.events");
        paymentResultConsumer = new KafkaTestConsumer("payment.result");

        logStep("✅ Saga cancellation test setup complete");
    }

    @AfterMethod
    public void cleanup() {
        if (orderEventsConsumer != null) orderEventsConsumer.close();
        if (paymentResultConsumer != null) paymentResultConsumer.close();
        logStep("✅ Saga cancellation test cleanup complete");
    }

    private PurchaseResult setupCustomerAndProduct() {
        return PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .execute();
    }

    // ══════════════════════════════════════════════════════════════
    // TEST 1: CANCEL CONFIRMED ORDER - FULL REFUND
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("Order Cancellation with Refund")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Cancel confirmed order triggers refund Saga: CONFIRMED → CANCELLED with refund")
    public void test01_CancelConfirmedOrder_RefundInitiated() throws Exception {
        logStep("TEST 1: Cancel confirmed order - Full refund initiated");

        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        // ══════════════════════════════════════════════════════
        // STEP 1: Create Order (Forward Saga - Success Path)
        // ══════════════════════════════════════════════════════
        logStep("  STEP 1: Creating order...");

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        String orderId = order.getId();

        logStep("  ✓ Order created: " + orderId);

        // ══════════════════════════════════════════════════════
        // STEP 2: Wait for Payment Success (Order becomes CONFIRMED)
        // ══════════════════════════════════════════════════════
        logStep("  STEP 2: Waiting for payment to complete...");

        Optional<JsonNode> paymentSuccess = paymentResultConsumer.waitForMessage(
                node -> node.has("orderId")
                        && orderId.equals(node.get("orderId").asText())
                        && "SUCCESS".equals(node.get("status").asText()),
                30
        );

        assertThat(paymentSuccess).as("Payment should succeed").isPresent();

        String paymentId = paymentSuccess.get().get("paymentId").asText();
        logStep("  ✓ Payment succeeded: " + paymentId);

        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> "CONFIRMED".equals(getOrderStatusSafely(orderApiClient, token, userId, orderId)));

        logStep("  ✓ Order confirmed: " + orderId);

        // ══════════════════════════════════════════════════════
        // STEP 3: Cancel Order (Reverse Saga Initiation)
        // ══════════════════════════════════════════════════════
        logStep("  STEP 3: Cancelling order...");

        ServiceResponse cancelResponse = orderApiClient.cancelOrderRaw(token, userId, orderId);

        assertThat(cancelResponse.getStatusCode())
                .as("Order cancellation should succeed")
                .isIn(200, 204);

        logStep("  ✓ Cancellation request accepted");

        // ══════════════════════════════════════════════════════
        // STEP 4: Verify ORDER_CANCELLED Event Published
        // ══════════════════════════════════════════════════════
        logStep("  STEP 4: Verifying ORDER_CANCELLED event...");

        Optional<JsonNode> orderCancelledEvent = orderEventsConsumer.waitForMessage(
                node -> node.has("eventType")
                        && "ORDER_CANCELLED".equals(node.get("eventType").asText())
                        && orderId.equals(node.get("orderId").asText()),
                10
        );

        assertThat(orderCancelledEvent).as("ORDER_CANCELLED event should be published to order.events").isPresent();
        logStep("  ✓ ORDER_CANCELLED event published");

        // ══════════════════════════════════════════════════════
        // STEP 5: Verify Refund Initiated (soft check — see class-level TODO)
        // ══════════════════════════════════════════════════════
        logStep("  STEP 5: Waiting for refund to be initiated...");

        Optional<JsonNode> refundEvent = paymentResultConsumer.waitForMessage(
                node -> node.has("orderId")
                        && orderId.equals(node.get("orderId").asText())
                        && node.has("eventType")
                        && ("REFUND_INITIATED".equals(node.get("eventType").asText())
                        || "REFUND_COMPLETED".equals(node.get("eventType").asText())),
                30
        );

        String refundStatus = "N/A";
        if (refundEvent.isPresent()) {
            JsonNode refund = refundEvent.get();
            refundStatus = refund.get("eventType").asText();
            String refundId = refund.has("refundId") ? refund.get("refundId").asText() : null;
            logStep("  ✓ Refund event received: " + refundStatus);
            if (refundId != null) logStep("  ✓ Refund ID: " + refundId);
        } else {
            logStep("No refund event received within timeout — may be processed synchronously "
                    + "(not currently hard-asserted; see class-level TODO)");
        }

        // ══════════════════════════════════════════════════════
        // STEP 6: Verify Order Status Updated to CANCELLED
        // ══════════════════════════════════════════════════════
        logStep("  STEP 6: Verifying order status updated to CANCELLED...");

        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> "CANCELLED".equals(getOrderStatusSafely(orderApiClient, token, userId, orderId)));

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, orderId);

        assertThat(finalOrder.getStatus()).as("Order should be marked CANCELLED").isEqualTo("CANCELLED");
        assertThat(finalOrder.getPaymentId()).as("Payment ID should still be present (for refund reference)").isEqualTo(paymentId);

        logStep("  ✓ Order status: CANCELLED");

        // ══════════════════════════════════════════════════════
        // STEP 7: Summary
        // ══════════════════════════════════════════════════════
        logStep("✅ REVERSE SAGA COMPLETE - Full flow validated:");
        logStep("   1. ✅ Order created → PENDING");
        logStep("   2. ✅ Payment succeeded → CONFIRMED");
        logStep("   3. ✅ Order cancelled → ORDER_CANCELLED event");
        logStep("   4. Refund status: " + refundStatus + " (soft check)");
        logStep("   5. ✅ Order status updated → CANCELLED");
        logStep("");
        logStep("   Payment ID: " + paymentId);
    }

    // ══════════════════════════════════════════════════════════════
    // TEST 2: CANCEL PENDING ORDER - NO REFUND NEEDED
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 2)
    @Story("Cancel Pending Order")
    @Severity(SeverityLevel.NORMAL)
    @Description("Cancel order before payment completes - no refund needed")
    public void test02_CancelPendingOrder_NoRefundNeeded() throws Exception {
        logStep("TEST 2: Cancel pending order - No refund needed");

        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        // ══════════════════════════════════════════════════════
        // STEP 1: Create Order with Payment Timeout (stays PENDING)
        // ══════════════════════════════════════════════════════
        logStep("  STEP 1: Creating order with payment timeout...");

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse createResponse = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, "payment-timeout");

        assertThat(createResponse.getStatusCode()).isEqualTo(201);

        TestModels.OrderResponse order = createResponse.as(TestModels.OrderResponse.class);
        String orderId = order.getId();

        logStep("  ✓ Order created: " + orderId);
        logStep("  ✓ Status: " + order.getStatus());
        assertThat(order.getStatus()).isEqualTo("PENDING");

        // ══════════════════════════════════════════════════════
        // STEP 2: Cancel Order (while still PENDING)
        // ══════════════════════════════════════════════════════
        logStep("  STEP 2: Cancelling pending order...");

        Thread.sleep(2000); // ensure order is still pending before cancelling

        ServiceResponse cancelResponse = orderApiClient.cancelOrderRaw(token, userId, orderId);

        assertThat(cancelResponse.getStatusCode())
                .as("Pending order cancellation should succeed")
                .isIn(200, 204);

        logStep("  ✓ Cancellation accepted");

        // ══════════════════════════════════════════════════════
        // STEP 3: Verify ORDER_CANCELLED Event
        // ══════════════════════════════════════════════════════
        Optional<JsonNode> cancelEvent = orderEventsConsumer.waitForMessage(
                node -> "ORDER_CANCELLED".equals(node.path("eventType").asText())
                        && orderId.equals(node.path("orderId").asText()),
                10
        );

        assertThat(cancelEvent).isPresent();
        logStep("  ✓ ORDER_CANCELLED event published");

        // TODO: Verify NO refund event is published for a never-confirmed order.
        // Commented out in the original suite — worth re-enabling once
        // payment.result event timing/latency for this negative case is confirmed stable:
        //
        // Optional<JsonNode> refundEvent = paymentResultConsumer.waitForMessage(
        //         node -> orderId.equals(node.path("orderId").asText())
        //                 && node.has("eventType")
        //                 && node.get("eventType").asText().contains("REFUND"),
        //         5
        // );
        // assertThat(refundEvent).as("No refund event should be published for pending order").isEmpty();

        // ══════════════════════════════════════════════════════
        // STEP 4: Verify Order Status → CANCELLED
        // ══════════════════════════════════════════════════════
        await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> "CANCELLED".equals(getOrderStatusSafely(orderApiClient, token, userId, orderId)));

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, orderId);

        assertThat(finalOrder.getStatus()).isEqualTo("CANCELLED");
        assertThat(finalOrder.getPaymentId()).isNullOrEmpty();

        logStep("✅ PENDING ORDER CANCELLATION - Complete:");
        logStep("   1. ✅ Order created → PENDING");
        logStep("   2. ✅ Order cancelled while PENDING");
        logStep("   3. ✅ No refund needed (payment never completed)");
        logStep("   4. ✅ Order status → CANCELLED");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST 3: CANCELLATION IDEMPOTENCY - DUPLICATE CANCEL REQUESTS
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 3)
    @Story("Cancellation Idempotency")
    @Severity(SeverityLevel.NORMAL)
    @Description("Duplicate cancel requests don't trigger duplicate refunds")
    public void test03_DuplicateCancellation_IdempotentRefund() throws Exception {
        logStep("TEST 3: Duplicate cancellation - Idempotent refund");

        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        // ══════════════════════════════════════════════════════
        // STEP 1: Create and confirm order
        // ══════════════════════════════════════════════════════
        logStep("  STEP 1: Creating and confirming order...");

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        String orderId = order.getId();
        logStep("  ✓ Order created: " + orderId);

        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> "CONFIRMED".equals(getOrderStatusSafely(orderApiClient, token, userId, orderId)));

        logStep("  ✓ Order confirmed");

        // ══════════════════════════════════════════════════════
        // STEP 2: Cancel order (first request)
        // ══════════════════════════════════════════════════════
        logStep("  STEP 2: First cancellation request...");

        ServiceResponse cancel1 = orderApiClient.cancelOrderRaw(token, userId, orderId);
        assertThat(cancel1.getStatusCode()).isIn(200, 204);
        logStep("  ✓ First cancellation accepted");

        Thread.sleep(1000);

        // ══════════════════════════════════════════════════════
        // STEP 3: Cancel order again (duplicate request)
        // ══════════════════════════════════════════════════════
        logStep("  STEP 3: Duplicate cancellation request...");

        ServiceResponse cancel2 = orderApiClient.cancelOrderRaw(token, userId, orderId);

        assertThat(cancel2.getStatusCode())
                .as("Duplicate cancellation should be handled gracefully")
                .isIn(200, 204, 400, 409);

        logStep("  ✓ Duplicate cancellation handled: HTTP " + cancel2.getStatusCode());

        // TODO: Count refund events — should only be ONE. Requires
        // KafkaTestConsumer.countMessages(predicate, timeoutSeconds), which
        // exists on the consumer per the original file but was commented out
        // there too. Worth re-enabling once refund-event timing is confirmed:
        //
        // Thread.sleep(3000);
        // int refundEventCount = paymentResultConsumer.countMessages(
        //         node -> orderId.equals(node.path("orderId").asText())
        //                 && node.has("eventType")
        //                 && node.get("eventType").asText().contains("REFUND"),
        //         5
        // );
        // assertThat(refundEventCount).as("Only ONE refund should be processed (idempotent)").isLessThanOrEqualTo(1);

        logStep("✅ CANCELLATION IDEMPOTENCY - Complete:");
        logStep("   1. ✅ First cancellation processed");
        logStep("   2. ✅ Duplicate cancellation handled gracefully");
        logStep("   3. ⚠️  Refund-count idempotency not yet asserted (see TODO above)");
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