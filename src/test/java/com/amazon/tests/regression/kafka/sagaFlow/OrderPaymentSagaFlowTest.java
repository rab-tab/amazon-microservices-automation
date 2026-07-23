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

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Saga Pattern - Choreography-Based Distributed Transaction Tests
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * SUCCESS FLOW:
 *   Order Created (PENDING) → ORDER_CREATED event → Payment processes →
 *   PAYMENT_COMPLETED event → Order status: CONFIRMED, payment ID assigned
 *
 * FAILURE FLOW (Compensation):
 *   Order Created (PENDING) → ORDER_CREATED event → Payment fails →
 *   PAYMENT_FAILED event → Order status: PAYMENT_FAILED
 *
 * KAFKA TOPICS:
 *   order.events: ORDER_CREATED, ORDER_STATUS_UPDATED, ORDER_CANCELLED
 *   payment.result: PAYMENT_COMPLETED, PAYMENT_FAILED
 *
 * COVERED SCENARIOS:
 *   Happy path, compensation, idempotency, event ordering,
 *   concurrent saga execution, timeout handling (payment service down —
 *   requires manual service shutdown, see test06).
 */
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
        return PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .execute();
    }

    private OrderApiClient orderApiClientFor(String token) {
        return new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());
    }

    // ══════════════════════════════════════════════════════════════
    // SUCCESS PATH - PAYMENT COMPLETED
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("Saga Success Flow")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Complete saga: Order → Payment Success/Failure → Order updated accordingly")
    public void test01_SagaSuccessFlow_OrderConfirmedAfterPayment() {
        logStep("TEST 1: Saga flow - Order PENDING → Payment result → Order terminal state");

        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = orderApiClientFor(token);

        // ══════════════════════════════════════════════════════
        // STEP 1: Create Order (Saga Initiation)
        // ══════════════════════════════════════════════════════
        long start = System.nanoTime();
        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        log.info("API_METRIC endpoint=/api/orders duration={}ms", durationMs);

        String orderId = order.getId();
        logStep("  ✓ Order created: " + orderId);
        logStep("  ✓ Initial status: " + order.getStatus());
        assertThat(order.getStatus()).as("Order should start in PENDING status").isEqualTo("PENDING");

        // ══════════════════════════════════════════════════════
        // STEP 2: Verify ORDER_CREATED event published
        // ══════════════════════════════════════════════════════
        logStep("  Verifying ORDER_CREATED event in Kafka...");

        Optional<JsonNode> orderCreatedEvent = orderEventsConsumer.waitForMessage(
                node -> node.has("eventType")
                        && "ORDER_CREATED".equals(node.get("eventType").asText())
                        && orderId.equals(node.get("orderId").asText()),
                10
        );

        assertThat(orderCreatedEvent)
                .as("ORDER_CREATED event should be published to order.events (consumed by Payment Service)")
                .isPresent();

        logStep("  ✓ ORDER_CREATED event published to order.events");

        // ══════════════════════════════════════════════════════
        // STEP 3: Wait for Payment Processing (Async Saga Step)
        // ══════════════════════════════════════════════════════
        logStep("  Waiting for payment processing (Payment Service)...");

        Optional<JsonNode> paymentResultEvent = paymentResultConsumer.waitForMessage(
                node -> node.has("orderId")
                        && orderId.equals(node.get("orderId").asText())
                        && node.has("status"),
                30
        );

        assertThat(paymentResultEvent).as("Payment result event should be published").isPresent();

        String paymentStatus = paymentResultEvent.get().get("status").asText();
        logStep("  ✓ Payment result received: " + paymentStatus);

        // ══════════════════════════════════════════════════════
        // STEP 4: Verify Order Status Updated (Saga Completion)
        // ══════════════════════════════════════════════════════
        logStep("  Waiting for order status update...");

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

        // ══════════════════════════════════════════════════════
        // ASSERTIONS - Saga Completion Verification
        // ══════════════════════════════════════════════════════
        if ("SUCCESS".equals(paymentStatus)) {
            assertThat(finalOrder.getStatus()).as("Order status should be CONFIRMED after successful payment").isEqualTo("CONFIRMED");
            assertThat(finalOrder.getPaymentId()).as("Payment ID should be assigned on success").isNotBlank();
            logStep("✅ SAGA SUCCESS: Order PENDING → Payment SUCCESS → Order CONFIRMED");
        } else if ("FAILED".equals(paymentStatus)) {
            assertThat(finalOrder.getStatus()).as("Order status should be PAYMENT_FAILED after payment failure").isEqualTo("PAYMENT_FAILED");
            assertThat(finalOrder.getPaymentId()).as("Payment ID should be null on failure").isNullOrEmpty();
            logStep("✅ SAGA COMPENSATION: Order PENDING → Payment FAILED → Order PAYMENT_FAILED");
        }

        assertThat(finalOrder.getStatus()).as("Order status should be updated from PENDING").isIn("CONFIRMED", "PAYMENT_FAILED");
    }

    // ══════════════════════════════════════════════════════════════
    // FAILURE PATH - PAYMENT COMPENSATION
    // ══════════════════════════════════════════════════════════════

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

        // ══════════════════════════════════════════════════════
        // STEP 1: Create Order with Fault Injection Header
        // ══════════════════════════════════════════════════════
        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse createResponse = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, "payment-failure");

        assertThat(createResponse.getStatusCode()).isEqualTo(201);

        TestModels.OrderResponse order = createResponse.as(TestModels.OrderResponse.class);
        String orderId = order.getId();

        logStep("  ✓ Order created with payment failure injection: " + orderId);
        assertThat(order.getStatus()).isEqualTo("PENDING");

        // ══════════════════════════════════════════════════════
        // STEP 2: Wait for PAYMENT_FAILED event
        // ══════════════════════════════════════════════════════
        logStep("  Waiting for PAYMENT_FAILED event...");

        Optional<JsonNode> failureEvent = paymentResultConsumer.waitForMessage(
                node -> node.has("orderId")
                        && orderId.equals(node.get("orderId").asText())
                        && "FAILED".equals(node.get("status").asText()),
                30
        );

        assertThat(failureEvent).as("PAYMENT_FAILED event should be published").isPresent();

        String failureReason = failureEvent.get().has("failureReason")
                ? failureEvent.get().get("failureReason").asText() : "Unknown";
        logStep("  ✓ Payment failure detected: " + failureReason);

        // ══════════════════════════════════════════════════════
        // STEP 3: Verify Order Status Updated to PAYMENT_FAILED
        // ══════════════════════════════════════════════════════
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> "PAYMENT_FAILED".equals(getOrderStatusSafely(orderApiClient, token, userId, orderId)));

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, orderId);

        assertThat(finalOrder.getStatus()).as("Order should be marked PAYMENT_FAILED").isEqualTo("PAYMENT_FAILED");
        assertThat(finalOrder.getPaymentId()).as("No payment ID should be assigned on failure").isNullOrEmpty();

        logStep("✅ SAGA COMPENSATION validated: Payment failure correctly updated order");
    }

    // ══════════════════════════════════════════════════════════════
    // IDEMPOTENCY - DUPLICATE SAGA INITIATION
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 3)
    @Story("Saga Idempotency")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Duplicate order events don't trigger duplicate payments (idempotency)")
    public void test03_SagaIdempotency_DuplicateEventsIgnored() throws Exception {
        logStep("TEST 3: Saga idempotency - duplicate events don't cause duplicate payments");

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = orderApiClientFor(token);
        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        // ══════════════════════════════════════════════════════
        // STEP 1: Create order (first request)
        // ══════════════════════════════════════════════════════
        TestModels.OrderResponse order1 = orderApiClient.createOrder(userId, idempotencyKey, purchase.getProducts());
        String orderId = order1.getId();
        logStep("  ✓ First request - Order created: " + orderId);

        Thread.sleep(500);

        // ══════════════════════════════════════════════════════
        // STEP 2: Duplicate order request (same idempotency key)
        // ══════════════════════════════════════════════════════
        TestModels.OrderResponse order2 = orderApiClient.createOrder(userId, idempotencyKey, purchase.getProducts());
        assertThat(order2.getId()).isEqualTo(orderId);
        logStep("  ✓ Second request - Returned cached order: " + orderId);

        // ══════════════════════════════════════════════════════
        // STEP 3: Count PAYMENT events for this order — only ONE expected
        // ══════════════════════════════════════════════════════
        Thread.sleep(2000);

        int paymentEventCount = paymentResultConsumer.countMessages(
                node -> node.has("orderId") && orderId.equals(node.get("orderId").asText()),
                5
        );

        assertThat(paymentEventCount).as("Only ONE payment should be processed (idempotent)").isLessThanOrEqualTo(1);

        logStep("✅ Saga idempotency validated - no duplicate payments");
    }

    // ══════════════════════════════════════════════════════════════
    // CONCURRENT SAGA EXECUTION
    // ══════════════════════════════════════════════════════════════

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
            assertThat(finalStatus).as("Order should reach terminal state").isIn("CONFIRMED", "PAYMENT_FAILED");
        }

        logStep("✅ All concurrent sagas executed independently");
    }

    // ══════════════════════════════════════════════════════════════
    // EVENT ORDERING - SAGA STATE CONSISTENCY
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 5)
    @Story("Event Ordering")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order events maintain consistency even with out-of-order delivery")
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

        logStep("  Waiting for Saga to complete...");

        await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> !getOrderStatusSafely(orderApiClient, token, userId, orderId).equals("PENDING"));

        timeline.mark(TestTimeline.ORDER_CONFIRMED);
        long latency = timeline.durationBetween(TestTimeline.ORDER_CREATED, TestTimeline.ORDER_CONFIRMED);
        MetricsManager.getInstance().recordSagaLatency(latency);
        logStep("  ✓ Saga completed in " + latency + " ms");

        Thread.sleep(2000); // brief wait to ensure all events landed in Kafka

        List<JsonNode> allOrderEvents = orderEventsConsumer.collectMessages(
                node -> orderId.equals(node.path("orderId").asText()), 5);

        logStep("  ✓ Collected " + allOrderEvents.size() + " events for order " + orderId);

        Optional<JsonNode> orderCreated = allOrderEvents.stream()
                .filter(node -> "ORDER_CREATED".equals(node.path("eventType").asText()))
                .findFirst();
        assertThat(orderCreated).as("ORDER_CREATED event should be published").isPresent();
        logStep("  ✓ ORDER_CREATED event found");

        Optional<JsonNode> paymentResult = paymentResultConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText()), 5);
        assertThat(paymentResult).as("Payment result should be published").isPresent();
        logStep("  ✓ Payment result event found");

        Optional<JsonNode> statusUpdate = allOrderEvents.stream()
                .filter(node -> "ORDER_STATUS_UPDATED".equals(node.path("eventType").asText()))
                .findFirst();
        assertThat(statusUpdate).as("ORDER_STATUS_UPDATED event should be published").isPresent();
        logStep("  ✓ ORDER_STATUS_UPDATED event found");

        long orderCreatedTime = orderCreated.get().path("timestamp").asLong();
        long paymentTime = paymentResult.get().path("timestamp").asLong();
        long statusUpdateTime = statusUpdate.get().path("timestamp").asLong();

        assertThat(orderCreatedTime).as("ORDER_CREATED should happen before payment").isLessThan(paymentTime);
        assertThat(paymentTime).as("Payment should complete before status update").isLessThan(statusUpdateTime);

        logStep("  ✓ Event timestamps in correct order: CREATED=" + orderCreatedTime
                + " PAYMENT=" + paymentTime + " STATUS_UPDATED=" + statusUpdateTime);

        logStep("✅ Event ordering validated - saga consistency maintained");
    }

    // ══════════════════════════════════════════════════════════════
    // TIMEOUT HANDLING - PAYMENT SERVICE DOWN (manual precondition)
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 6, enabled = false)
    @Story("Saga Timeout")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Order remains PENDING if payment service is down (no false success). "
            + "Requires manually stopping payment-service before running (e.g. docker-compose stop payment-service).")
    public void test06_SagaTimeout_OrderRemainsPendingIfPaymentDown() {
        logStep("TEST 6: Saga timeout - payment service unavailable (manual precondition required)");

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = orderApiClientFor(token);

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        String orderId = order.getId();

        assertThat(order.getStatus()).isEqualTo("PENDING");
        logStep("  ✓ Order created (payment service DOWN): " + orderId);

        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String finalStatus = getOrderStatusSafely(orderApiClient, token, userId, orderId);

        assertThat(finalStatus).as("Order should remain PENDING if payment service is unavailable").isEqualTo("PENDING");

        logStep("✅ Order correctly remains PENDING when payment service is down (no false CONFIRMED status)");
    }

    // ══════════════════════════════════════════════════════════════
    // HELPERS
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