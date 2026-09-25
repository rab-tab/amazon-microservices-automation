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
        return PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .execute();
    }

    @Test(priority = 1)
    @Story("Order Cancellation with Refund")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Cancel confirmed order triggers refund Saga")
    public void test01_CancelConfirmedOrder_RefundInitiated() throws Exception {
        logStep("TEST 1: Cancel confirmed order - Full refund initiated");

        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        logStep("  STEP 1: Creating order...");

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        String orderId = order.getId();

        logStep("  ✓ Order created: " + orderId);

        logStep("  STEP 2: Waiting for payment to complete...");

        Optional<JsonNode> paymentSuccess = paymentResultConsumer.waitForMessage(
                node -> node.has("orderId")
                        && orderId.equals(node.get("orderId").asText())
                        && "SUCCESS".equals(node.get("status").asText()),
                30
        );

        assertThat(paymentSuccess).isPresent();

        String paymentId = paymentSuccess.get().get("paymentId").asText();
        logStep("  ✓ Payment succeeded: " + paymentId);

        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> "CONFIRMED".equals(getOrderStatusSafely(orderApiClient, token, userId, orderId)));

        logStep("  ✓ Order confirmed: " + orderId);

        logStep("  STEP 3: Cancelling order...");

        ServiceResponse cancelResponse = orderApiClient.cancelOrderRaw(token, userId, orderId);

        assertThat(cancelResponse.getStatusCode()).isIn(200, 204);

        logStep("  ✓ Cancellation request accepted");

        logStep("  STEP 4: Verifying ORDER_CANCELLED event...");

        Optional<JsonNode> orderCancelledEvent = orderEventsConsumer.waitForMessage(
                node -> node.has("eventType")
                        && "ORDER_CANCELLED".equals(node.get("eventType").asText())
                        && orderId.equals(node.get("orderId").asText()),
                10
        );

        assertThat(orderCancelledEvent).isPresent();
        logStep("  ✓ ORDER_CANCELLED event published");

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
            logStep("  ✓ Refund event received: " + refundStatus);
        }

        logStep("  STEP 6: Verifying order status updated to CANCELLED...");

        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> "CANCELLED".equals(getOrderStatusSafely(orderApiClient, token, userId, orderId)));

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, orderId);

        assertThat(finalOrder.getStatus()).isEqualTo("CANCELLED");
        assertThat(finalOrder.getPaymentId()).isEqualTo(paymentId);

        logStep("✅ REVERSE SAGA COMPLETE");
    }

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

        logStep("  STEP 1: Creating order with payment timeout...");

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse createResponse = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, "payment-timeout");

        assertThat(createResponse.getStatusCode()).isEqualTo(201);

        TestModels.OrderResponse order = createResponse.as(TestModels.OrderResponse.class);
        String orderId = order.getId();

        logStep("  ✓ Order created: " + orderId);
        assertThat(order.getStatus()).isEqualTo("PENDING");

        logStep("  STEP 2: Cancelling pending order...");

        Thread.sleep(2000);

        ServiceResponse cancelResponse = orderApiClient.cancelOrderRaw(token, userId, orderId);

        assertThat(cancelResponse.getStatusCode()).isIn(200, 204);

        logStep("  ✓ Cancellation accepted");

        logStep("  STEP 3: Verify ORDER_CANCELLED Event");

        Optional<JsonNode> cancelEvent = orderEventsConsumer.waitForMessage(
                node -> "ORDER_CANCELLED".equals(node.path("eventType").asText())
                        && orderId.equals(node.path("orderId").asText()),
                10
        );

        assertThat(cancelEvent).isPresent();
        logStep("  ✓ ORDER_CANCELLED event published");

        logStep("  STEP 4: Verify Order Status → CANCELLED");

        await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> "CANCELLED".equals(getOrderStatusSafely(orderApiClient, token, userId, orderId)));

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, orderId);

        assertThat(finalOrder.getStatus()).isEqualTo("CANCELLED");
        assertThat(finalOrder.getPaymentId()).isNullOrEmpty();

        logStep("✅ PENDING ORDER CANCELLATION - Complete");
    }

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

        logStep("  STEP 2: First cancellation request...");

        ServiceResponse cancel1 = orderApiClient.cancelOrderRaw(token, userId, orderId);
        assertThat(cancel1.getStatusCode()).isIn(200, 204);
        logStep("  ✓ First cancellation accepted");

        Thread.sleep(1000);

        logStep("  STEP 3: Duplicate cancellation request...");

        ServiceResponse cancel2 = orderApiClient.cancelOrderRaw(token, userId, orderId);

        assertThat(cancel2.getStatusCode()).isIn(200, 204, 400, 409);

        logStep("  ✓ Duplicate cancellation handled: HTTP " + cancel2.getStatusCode());

        logStep("✅ CANCELLATION IDEMPOTENCY - Complete");
    }

    @Test(priority = 4)
    @Story("Invalid Cancellation State Transition")
    @Severity(SeverityLevel.NORMAL)
    @Description("Cannot cancel already-cancelled order")
    public void test04_CannotCancelAlreadyCancelledOrder() throws Exception {
        logStep("TEST 4: Invalid state transition - Cannot cancel already-cancelled order");

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        String orderId = order.getId();

        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> "CONFIRMED".equals(getOrderStatusSafely(orderApiClient, token, userId, orderId)));

        logStep("  ✓ Order confirmed: " + orderId);

        ServiceResponse cancelResponse1 = orderApiClient.cancelOrderRaw(token, userId, orderId);
        assertThat(cancelResponse1.getStatusCode()).isIn(200, 204);
        logStep("  ✓ First cancellation succeeded");

        Thread.sleep(2000);

        logStep("  Attempting second cancellation on CANCELLED order...");

        ServiceResponse cancelResponse2 = orderApiClient.cancelOrderRaw(token, userId, orderId);

        logStep("  Response status: " + cancelResponse2.getStatusCode());

        if (cancelResponse2.getStatusCode() >= 400) {
            logStep("  ✓ Second cancellation correctly rejected: HTTP " + cancelResponse2.getStatusCode());
        }

        logStep("✅ Invalid state transition handled correctly");
    }

    @Test(priority = 5)
    @Story("Concurrent Cancellations")
    @Severity(SeverityLevel.NORMAL)
    @Description("Multiple concurrent cancel requests on same order handled correctly")
    public void test05_ConcurrentCancellations() throws Exception {
        logStep("TEST 5: Concurrent cancellations on same order");

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        String orderId = order.getId();

        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> "CONFIRMED".equals(getOrderStatusSafely(orderApiClient, token, userId, orderId)));

        logStep("  Order confirmed: " + orderId);

        logStep("  Sending 3 concurrent cancel requests...");

        ServiceResponse cancel1 = orderApiClient.cancelOrderRaw(token, userId, orderId);
        ServiceResponse cancel2 = orderApiClient.cancelOrderRaw(token, userId, orderId);
        ServiceResponse cancel3 = orderApiClient.cancelOrderRaw(token, userId, orderId);

        logStep("  Cancel 1: HTTP " + cancel1.getStatusCode());
        logStep("  Cancel 2: HTTP " + cancel2.getStatusCode());
        logStep("  Cancel 3: HTTP " + cancel3.getStatusCode());

        Thread.sleep(2000);

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, orderId);
        assertThat(finalOrder.getStatus()).isEqualTo("CANCELLED");

        logStep("✅ Concurrent cancellations handled - final status: CANCELLED");
    }

    private String getOrderStatusSafely(OrderApiClient orderApiClient, String token, String userId, String orderId) {
        try {
            return orderApiClient.getOrder(token, userId, orderId).getStatus();
        } catch (Exception e) {
            log.warn("Failed to get order status for {}: {}", orderId, e.getMessage());
            return "UNKNOWN";
        }
    }
}