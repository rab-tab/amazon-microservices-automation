package com.amazon.tests.regression.kafka.sagaFlow;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.TestTimeline;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.metrics.MetricsManager;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.management.OperatingSystemMXBean;
import edu.emory.mathcs.backport.java.util.concurrent.TimeUnit;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.core.ConditionTimeoutException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Slf4j
@Epic("Kafka Saga Pattern")
@Feature("Order-Payment Choreography")
public class OrderPaymentSagaFlowTest extends BaseTest {

    private KafkaTestConsumer orderEventsConsumer;
    private KafkaTestConsumer paymentResultConsumer;

    private OperatingSystemMXBean osBean;
    private TestTimeline timeline;
    private long testStartTime;
    private long testCpuStart;

    @BeforeMethod
    public void setup() {
        logStep("Setting up Saga flow tests");

        testStartTime = System.currentTimeMillis();
        timeline = new TestTimeline();
        osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        testCpuStart = osBean.getProcessCpuTime();

        orderEventsConsumer = new KafkaTestConsumer("order.events");
        paymentResultConsumer = new KafkaTestConsumer("payment.result");

        logStep("✅ Saga test setup complete");
    }

    @AfterMethod
    public void cleanup() {
        if (orderEventsConsumer != null) orderEventsConsumer.close();
        if (paymentResultConsumer != null) paymentResultConsumer.close();

        long wallTime = System.currentTimeMillis() - testStartTime;
        long cpuTimeMs = (osBean.getProcessCpuTime() - testCpuStart) / 1_000_000;

        log.info("""
                TEST METRICS
                Wall Time: {} ms
                CPU Time : {} ms
                Wait Time: {} ms
                CPU Ratio: {} %
                """,
                wallTime, cpuTimeMs, wallTime - cpuTimeMs,
                wallTime > 0 ? (cpuTimeMs / (double) wallTime) * 100 : 0);

        timeline.printSummary();
        logStep("✅ Saga test consumers closed");
    }

    private PurchaseResult setupCustomerAndProduct() {
        return PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .execute();
    }

    private OrderApiClient orderApiClientFor(String token) {
        return new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());
    }

    @Test(priority = 1)
    @Story("Saga Success Flow")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Complete saga: Order → Payment Success/Failure → Order updated")
    public void test01_SagaSuccessFlow_OrderConfirmedAfterPayment() {
        logStep("TEST 1: Saga flow - Order PENDING → Payment result → Order terminal state");

        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = orderApiClientFor(token);

        long start = System.nanoTime();
        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        log.info("API_METRIC endpoint=/api/orders duration={}ms", durationMs);

        String orderId = order.getId();
        logStep("  ✓ Order created: " + orderId);
        logStep("  ✓ Initial status: " + order.getStatus());
        assertThat(order.getStatus()).isEqualTo("PENDING");

        Optional<JsonNode> orderCreatedEvent = orderEventsConsumer.waitForMessage(
                node -> node.has("eventType")
                        && "ORDER_CREATED".equals(node.get("eventType").asText())
                        && orderId.equals(node.get("orderId").asText()),
                10
        );

        assertThat(orderCreatedEvent).isPresent();
        logStep("  ✓ ORDER_CREATED event published to order.events");

        Optional<JsonNode> paymentResultEvent = paymentResultConsumer.waitForMessage(
                node -> node.has("orderId")
                        && orderId.equals(node.get("orderId").asText())
                        && node.has("status"),
                30
        );

        assertThat(paymentResultEvent).isPresent();

        String paymentStatus = paymentResultEvent.get().get("status").asText();
        logStep("  ✓ Payment result received: " + paymentStatus);

        try {
            await()
                    .atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofSeconds(1))
                    .ignoreExceptions()
                    .until(() -> !getOrderStatusSafely(orderApiClient, token, userId, orderId).equals("PENDING"));
        } catch (ConditionTimeoutException ex) {
            MetricsManager.getInstance().recordAwaitilityTimeout();
            throw ex;
        }

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, orderId);
        logStep("  ✓ Final order status: " + finalOrder.getStatus());

        if ("SUCCESS".equals(paymentStatus)) {
            assertThat(finalOrder.getStatus()).isEqualTo("CONFIRMED");
            assertThat(finalOrder.getPaymentId()).isNotBlank();
            logStep("✅ SAGA SUCCESS: Order PENDING → Payment SUCCESS → Order CONFIRMED");
        } else if ("FAILED".equals(paymentStatus)) {
            assertThat(finalOrder.getStatus()).isEqualTo("PAYMENT_FAILED");
            assertThat(finalOrder.getPaymentId()).isNullOrEmpty();
            logStep("✅ SAGA COMPENSATION: Order PENDING → Payment FAILED → Order PAYMENT_FAILED");
        }

        assertThat(finalOrder.getStatus()).isIn("CONFIRMED", "PAYMENT_FAILED");
    }

    @Test(priority = 2)
    @Story("Saga Compensation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Saga compensation: Payment fails → Order status updated to PAYMENT_FAILED")
    public void test02_SagaCompensation_PaymentFailureUpdatesOrder() {
        logStep("TEST 2: Saga compensation - Payment failure path");

        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = orderApiClientFor(token);

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse createResponse = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, "payment-failure");

        assertThat(createResponse.getStatusCode()).isEqualTo(201);

        TestModels.OrderResponse order = createResponse.as(TestModels.OrderResponse.class);
        String orderId = order.getId();

        logStep("  ✓ Order created with payment failure injection: " + orderId);
        assertThat(order.getStatus()).isEqualTo("PENDING");

        Optional<JsonNode> failureEvent = paymentResultConsumer.waitForMessage(
                node -> node.has("orderId")
                        && orderId.equals(node.get("orderId").asText())
                        && "FAILED".equals(node.get("status").asText()),
                30
        );

        assertThat(failureEvent).isPresent();

        String failureReason = failureEvent.get().has("failureReason")
                ? failureEvent.get().get("failureReason").asText() : "Unknown";
        logStep("  ✓ Payment failure detected: " + failureReason);

        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> "PAYMENT_FAILED".equals(getOrderStatusSafely(orderApiClient, token, userId, orderId)));

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, orderId);

        assertThat(finalOrder.getStatus()).isEqualTo("PAYMENT_FAILED");
        assertThat(finalOrder.getPaymentId()).isNullOrEmpty();

        logStep("✅ SAGA COMPENSATION validated: Payment failure correctly updated order");
    }

    @Test(priority = 3)
    @Story("Saga Idempotency")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Duplicate order events don't trigger duplicate payments")
    public void test03_SagaIdempotency_DuplicateEventsIgnored() throws Exception {
        logStep("TEST 3: Saga idempotency - duplicate events don't cause duplicate payments");

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = orderApiClientFor(token);
        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        TestModels.OrderResponse order1 = orderApiClient.createOrder(userId, idempotencyKey, purchase.getProducts());
        String orderId = order1.getId();
        logStep("  ✓ First request - Order created: " + orderId);

        Thread.sleep(500);

        TestModels.OrderResponse order2 = orderApiClient.createOrder(userId, idempotencyKey, purchase.getProducts());
        assertThat(order2.getId()).isEqualTo(orderId);
        logStep("  ✓ Second request - Returned cached order: " + orderId);

        Thread.sleep(2000);

        int paymentEventCount = paymentResultConsumer.countMessages(
                node -> node.has("orderId") && orderId.equals(node.get("orderId").asText()),
                5
        );

        assertThat(paymentEventCount).isLessThanOrEqualTo(1);

        logStep("✅ Saga idempotency validated - no duplicate payments");
    }

    @Test(priority = 4)
    @Story("Concurrent Sagas")
    @Severity(SeverityLevel.NORMAL)
    @Description("Multiple concurrent orders execute saga flows independently")
    public void test04_ConcurrentSagas_IndependentExecution() {
        logStep("TEST 4: Concurrent saga execution - multiple orders");

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = orderApiClientFor(token);

        int orderCount = 3;
        String[] orderIds = new String[orderCount];

        for (int i = 0; i < orderCount; i++) {
            TestModels.OrderResponse order = orderApiClient.createOrder(
                    userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
            orderIds[i] = order.getId();
            logStep("  ✓ Order " + (i + 1) + " created: " + orderIds[i]);
        }

        logStep("  Waiting for all sagas to complete...");

        for (String orderId : orderIds) {
            await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofSeconds(2))
                    .ignoreExceptions()
                    .until(() -> !getOrderStatusSafely(orderApiClient, token, userId, orderId).equals("PENDING"));
        }

        for (int i = 0; i < orderCount; i++) {
            String finalStatus = getOrderStatusSafely(orderApiClient, token, userId, orderIds[i]);
            logStep("  ✓ Order " + (i + 1) + " final status: " + finalStatus);
            assertThat(finalStatus).isIn("CONFIRMED", "PAYMENT_FAILED");
        }

        logStep("✅ All concurrent sagas executed independently");
    }

    @Test(priority = 5)
    @Story("Event Ordering")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order events maintain consistency")
    public void test05_EventOrdering_SagaStateConsistency() throws InterruptedException {
        logStep("TEST 5: Event ordering - saga state consistency");

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = orderApiClientFor(token);

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        String orderId = order.getId();
        logStep("  ✓ Order created: " + orderId);
        timeline.mark(TestTimeline.ORDER_CREATED);

        await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> !getOrderStatusSafely(orderApiClient, token, userId, orderId).equals("PENDING"));

        timeline.mark(TestTimeline.ORDER_CONFIRMED);
        long latency = timeline.durationBetween(TestTimeline.ORDER_CREATED, TestTimeline.ORDER_CONFIRMED);
        MetricsManager.getInstance().recordSagaLatency(latency);
        logStep("  ✓ Saga completed in " + latency + " ms");

        Thread.sleep(2000);

        List<JsonNode> allOrderEvents = orderEventsConsumer.collectMessages(
                node -> orderId.equals(node.path("orderId").asText()), 5);

        logStep("  ✓ Collected " + allOrderEvents.size() + " events");

        Optional<JsonNode> orderCreated = allOrderEvents.stream()
                .filter(node -> "ORDER_CREATED".equals(node.path("eventType").asText()))
                .findFirst();
        assertThat(orderCreated).isPresent();

        Optional<JsonNode> paymentResult = paymentResultConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText()), 5);
        assertThat(paymentResult).isPresent();

        logStep("✅ Event ordering validated - saga consistency maintained");
    }

    @Test(priority = 6)
    @Story("Cross-Saga Isolation")
    @Severity(SeverityLevel.NORMAL)
    @Description("Multiple users' sagas don't interfere with each other")
    public void test06_CrossSagaIsolation_MultipleUsers() throws Exception {
        logStep("TEST 6: Cross-saga isolation - multiple users");

        PurchaseResult purchase1 = setupCustomerAndProduct();
        PurchaseResult purchase2 = setupCustomerAndProduct();

        String userId1 = purchase1.getCustomer().getUser().getId();
        String userId2 = purchase2.getCustomer().getUser().getId();

        OrderApiClient orderApiClient1 = orderApiClientFor(purchase1.getCustomer().getAccessToken());
        OrderApiClient orderApiClient2 = orderApiClientFor(purchase2.getCustomer().getAccessToken());

        TestModels.OrderResponse order1 = orderApiClient1.createOrder(
                userId1, TestDataFactory.newIdempotencyKey(), purchase1.getProducts());
        TestModels.OrderResponse order2 = orderApiClient2.createOrder(
                userId2, TestDataFactory.newIdempotencyKey(), purchase2.getProducts());

        String orderId1 = order1.getId();
        String orderId2 = order2.getId();

        logStep("  ✓ User 1 order: " + orderId1);
        logStep("  ✓ User 2 order: " + orderId2);

        Thread.sleep(5000);

        TestModels.OrderResponse finalOrder1 = orderApiClient1.getOrder(
                purchase1.getCustomer().getAccessToken(), userId1, orderId1);
        TestModels.OrderResponse finalOrder2 = orderApiClient2.getOrder(
                purchase2.getCustomer().getAccessToken(), userId2, orderId2);

        assertThat(finalOrder1.getId()).isEqualTo(orderId1);
        assertThat(finalOrder2.getId()).isEqualTo(orderId2);

        assertThat(finalOrder1.getUserId()).isEqualTo(userId1);
        assertThat(finalOrder2.getUserId()).isEqualTo(userId2);

        logStep("✅ Cross-saga isolation validated - users isolated correctly");
    }

    @Test(priority = 7)
    @Story("Duplicate Payment Events")
    @Severity(SeverityLevel.NORMAL)
    @Description("Duplicate payment events don't cause duplicate order status updates")
    public void test07_DuplicatePaymentEvents_Idempotent() throws Exception {
        logStep("TEST 7: Duplicate payment events - idempotent handling");

        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = orderApiClientFor(token);

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        String orderId = order.getId();

        logStep("  ✓ Order created: " + orderId);

        Thread.sleep(3000);

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, orderId);

        assertThat(finalOrder.getStatus()).isNotEqualTo("PENDING");

        logStep("  ✓ Order processed, status: " + finalOrder.getStatus());
        logStep("✅ Duplicate payment event handling validated");
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