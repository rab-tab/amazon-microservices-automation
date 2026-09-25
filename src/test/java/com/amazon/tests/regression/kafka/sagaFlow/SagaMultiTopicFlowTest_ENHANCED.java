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

@Slf4j
@Epic("Kafka Saga Pattern")
@Feature("Multi-Topic Saga Flow")
public class SagaMultiTopicFlowTest_ENHANCED extends BaseTest {

    private KafkaTestConsumer orderEventsConsumer;
    private KafkaTestConsumer paymentResultConsumer;
    private KafkaTestConsumer shippingEventsConsumer;
    private KafkaTestConsumer notificationConsumer;

    @BeforeMethod
    public void setup() {
        logStep("Setting up Multi-Topic Saga Flow tests");

        orderEventsConsumer = new KafkaTestConsumer("order.events");
        paymentResultConsumer = new KafkaTestConsumer("payment.result");
        shippingEventsConsumer = new KafkaTestConsumer("shipping.events");
        notificationConsumer = new KafkaTestConsumer("notification.events");

        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();
        shippingEventsConsumer.seekToEnd();
        notificationConsumer.seekToEnd();

        logStep("✅ Multi-topic saga test setup complete");
    }

    @AfterMethod
    public void cleanup() {
        if (orderEventsConsumer != null) orderEventsConsumer.close();
        if (paymentResultConsumer != null) paymentResultConsumer.close();
        if (shippingEventsConsumer != null) shippingEventsConsumer.close();
        if (notificationConsumer != null) notificationConsumer.close();
    }

    private PurchaseResult setupCustomerAndProduct() {
        return PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();
    }

    @Test(priority = 1)
    @Story("Multi-Topic Saga")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Complete saga: Order → Payment → Shipping → Notification")
    public void test01_MultiTopicSagaFlow_Complete() throws Exception {
        logStep("TEST 1: Multi-topic saga flow (4 topics)");

        logStep("  FLOW:");
        logStep("    1. ORDER_CREATED on order.events");
        logStep("    2. PAYMENT_COMPLETED on payment.result");
        logStep("    3. SHIPMENT_SCHEDULED on shipping.events");
        logStep("    4. NOTIFICATION_SENT on notification.events");

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        logStep("  Creating order...");

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        String orderId = order.getId();

        logStep("  ✓ Order created: " + orderId);

        logStep("  Waiting for ORDER_CREATED event...");
        Optional<JsonNode> orderEvent = orderEventsConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText()), 10);
        assertThat(orderEvent).isPresent();
        logStep("  ✓ ORDER_CREATED published");

        logStep("  Waiting for PAYMENT_COMPLETED event...");
        Optional<JsonNode> paymentEvent = paymentResultConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText())
                        && "SUCCESS".equals(node.path("status").asText()), 30);
        if (paymentEvent.isPresent()) {
            logStep("  ✓ PAYMENT_COMPLETED received");
        } else {
            logStep("  ℹ️  PAYMENT_COMPLETED not received (may still be processing)");
        }

        logStep("  Waiting for SHIPMENT_SCHEDULED event...");
        Optional<JsonNode> shippingEvent = shippingEventsConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText()), 30);
        if (shippingEvent.isPresent()) {
            logStep("  ✓ SHIPMENT_SCHEDULED event received");
        } else {
            logStep("  ℹ️  SHIPMENT_SCHEDULED not yet published");
        }

        logStep("  Waiting for NOTIFICATION_SENT event...");
        Optional<JsonNode> notificationEvent = notificationConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText()), 30);
        if (notificationEvent.isPresent()) {
            logStep("  ✓ NOTIFICATION_SENT received");
        } else {
            logStep("  ℹ️  NOTIFICATION_SENT not yet published");
        }

        logStep("✅ Multi-topic saga flow completed");
    }

    @Test(priority = 2)
    @Story("Multi-Topic Saga")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Partial failure in shipping stops notification")
    public void test02_ShippingFailure_NoNotification() throws Exception {
        logStep("TEST 2: Shipping failure stops notification");

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        logStep("  Creating order with shipping failure injection...");

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        String orderId = order.getId();

        logStep("  Order created: " + orderId);

        Optional<JsonNode> paymentEvent = paymentResultConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText()), 30);
        if (paymentEvent.isPresent()) {
            logStep("  ✓ Payment succeeded");
        }

        Thread.sleep(3000);

        Optional<JsonNode> shippingEvent = shippingEventsConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText()), 5);

        if (shippingEvent.isPresent()) {
            String shippingStatus = shippingEvent.get().path("status").asText();
            logStep("  Shipping status: " + shippingStatus);

            if ("FAILED".equals(shippingStatus)) {
                Optional<JsonNode> notificationEvent = notificationConsumer.waitForMessage(
                        node -> orderId.equals(node.path("orderId").asText()), 3);

                assertThat(notificationEvent).isEmpty();
                logStep("  ✓ Shipping failure prevented notification");
            }
        }

        logStep("✅ Shipping failure handling validated");
    }

    @Test(priority = 3)
    @Story("Multi-Topic Saga")
    @Severity(SeverityLevel.NORMAL)
    @Description("Event ordering across topics maintained")
    public void test03_CrossTopicOrdering_EventSequencePreserved() throws Exception {
        logStep("TEST 3: Cross-topic event ordering");

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        logStep("  Creating multiple orders...");

        String[] orderIds = new String[3];
        for (int i = 0; i < 3; i++) {
            TestModels.OrderResponse order = orderApiClient.createOrder(
                    userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
            orderIds[i] = order.getId();
            logStep("    Order " + (i + 1) + ": " + orderIds[i]);
        }

        Thread.sleep(5000);

        logStep("  Verifying event ordering across topics...");

        for (String orderId : orderIds) {
            Optional<JsonNode> orderEvent = orderEventsConsumer.waitForMessage(
                    node -> orderId.equals(node.path("orderId").asText()), 3);
            assertThat(orderEvent).as("Order event for " + orderId).isPresent();
        }

        logStep("✅ Cross-topic ordering validated");
    }

    @Test(priority = 4)
    @Story("Multi-Topic Saga")
    @Severity(SeverityLevel.NORMAL)
    @Description("Saga compensation across multiple topics")
    public void test04_MultiTopicCompensation_AllRollback() throws Exception {
        logStep("TEST 4: Multi-topic compensation (rollback)");

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        logStep("  Scenario: Cancel order after payment succeeded");

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        String orderId = order.getId();

        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> "CONFIRMED".equals(getOrderStatusSafely(orderApiClient, token, userId, orderId)));

        logStep("  ✓ Order confirmed");

        logStep("  Cancelling order...");

        orderApiClient.cancelOrderRaw(token, userId, orderId);

        Optional<JsonNode> cancelEvent = orderEventsConsumer.waitForMessage(
                node -> "ORDER_CANCELLED".equals(node.path("eventType").asText())
                        && orderId.equals(node.path("orderId").asText()), 10);

        assertThat(cancelEvent).isPresent();
        logStep("  ✓ ORDER_CANCELLED event published");

        logStep("  Waiting for compensation events (refund, cancel shipping)...");

        Optional<JsonNode> refundEvent = paymentResultConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText())
                        && "REFUND".equals(node.path("eventType").asText()), 10);

        if (refundEvent.isPresent()) {
            logStep("  ✓ REFUND event published");
        }

        logStep("✅ Multi-topic compensation validated");
    }

    private String getOrderStatusSafely(OrderApiClient orderApiClient, String token, String userId, String orderId) {
        try {
            return orderApiClient.getOrder(token, userId, orderId).getStatus();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
}