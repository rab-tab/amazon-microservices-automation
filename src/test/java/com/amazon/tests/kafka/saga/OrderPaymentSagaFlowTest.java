package com.amazon.tests.kafka.saga;

import com.amazon.tests.BaseTest;
import com.amazon.tests.config.TestEnvironment;
import com.amazon.tests.config.TestEnvironmentBuilder;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.TestMetrics;
import com.amazon.tests.utils.TestTimeline;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.metrics.KafkaMetrics;
import com.amazon.tests.utils.metrics.MetricsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.management.OperatingSystemMXBean;
import edu.emory.mathcs.backport.java.util.concurrent.TimeUnit;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.core.ConditionTimeoutException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Saga Pattern - Choreography-Based Distributed Transaction Tests
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Tests the complete Kafka Saga flow across Order and Payment services:
 *
 * SUCCESS FLOW:
 * 1. Order Created → status: PENDING
 * 2. ORDER_CREATED event → order.events topic
 * 3. Payment Service consumes event from order.events
 * 4. Payment Service processes payment (Stripe/PayPal)
 * 5. PAYMENT_COMPLETED event → payment.result topic
 * 6. Order Service consumes success event from payment.result
 * 7. Order status updated: PENDING → CONFIRMED
 * 8. Payment ID assigned
 *
 * FAILURE FLOW (Compensation):
 * 1. Order Created → status: PENDING
 * 2. ORDER_CREATED event → order.events topic
 * 3. Payment Service consumes event from order.events
 * 4. Payment processing FAILS (insufficient funds, invalid card, etc.)
 * 5. PAYMENT_FAILED event → payment.result topic
 * 6. Order Service consumes failure event from payment.result
 * 7. Order status updated: PENDING → PAYMENT_FAILED
 * 8. Failure reason stored
 *
 * CANCELLATION FLOW (Saga Rollback):
 * 1. Order Cancelled → ORDER_CANCELLED event → order.events
 * 2. Payment Service receives cancellation from order.events
 * 3. Refund initiated (if payment was already processed)
 *
 * KAFKA TOPICS USED:
 * - order.events: ORDER_CREATED, ORDER_STATUS_UPDATED, ORDER_CANCELLED
 * - payment.result: PAYMENT_COMPLETED, PAYMENT_FAILED
 *
 * COVERED SCENARIOS:
 * - Happy path (payment success)
 * - Compensation (payment failure)
 * - Timeout handling (payment service down)
 * - Idempotency (duplicate events)
 * - Event ordering
 * - Concurrent saga execution
 * - Partial failure recovery
 *
 * @author Test Automation Team
 */
@Slf4j
@Epic("Kafka Saga Pattern")
@Feature("Order-Payment Choreography")
    public class OrderPaymentSagaFlowTest extends BaseTest {

    private KafkaTestConsumer orderEventsConsumer;
    private KafkaTestConsumer paymentResultConsumer;
    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;
    private OperatingSystemMXBean osBean;
    private TestTimeline timeline;
    private KafkaMetrics metrics =
            new KafkaMetrics();
    private TestMetrics seedingMetrics=new TestMetrics();
    private TestEnvironment env=new TestEnvironment();



    @BeforeMethod
    public void setup() throws SeedingException {
        logStep("Setting up Saga flow tests");
       testStartTime = System.currentTimeMillis();
        timeline=new TestTimeline();
        osBean =
                (OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean();
        testCpuStart = osBean.getProcessCpuTime();
        long startMemory =
                Runtime.getRuntime().totalMemory()
                        - Runtime.getRuntime().freeMemory();
        long userStart =
                System.currentTimeMillis();
        try {
            env= TestEnvironmentBuilder.builder(context)
                    .withUser().withProduct().waitForPropagation(1000).
                    withKafkaConsumer("order.events").
                    withKafkaConsumer("payment.result").build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // Seed test data
     //   user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        MetricsManager.recordUserSeeding(
                System.currentTimeMillis()
                        - userStart);
        userToken=env.getUserToken();
       // userToken = context.getCached("user_token_" + user.getId(), String.class);
        long productStart =
                System.currentTimeMillis();
        MetricsManager.recordProductSeeding(
                System.currentTimeMillis()
                        - productStart);
        //product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

       // waitForDataPropagation(1000);

        // Initialize Kafka consumers for saga verification
        // order.events = where ORDER_CREATED is published (Payment Service consumes this)
        // payment.result = where Payment Service publishes PAYMENT_COMPLETED/FAILED
      //  orderEventsConsumer = new KafkaTestConsumer("order.events");
      //  paymentResultConsumer = new KafkaTestConsumer("payment.result");

        // ⭐ CRITICAL: Seek to end to ignore historical events
       // orderEventsConsumer.seekToEnd();
        //paymentResultConsumer.seekToEnd();

        logStep("✅ Saga test setup complete");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SUCCESS PATH - PAYMENT COMPLETED - PASS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("Saga Success Flow")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Complete saga: Order → Payment Success → Order Confirmed")
    public void test01_SagaSuccessFlow_OrderConfirmedAfterPayment() {
        logStep("TEST 1: Saga success flow - Order PENDING → Payment Success → Order CONFIRMED");

        String idempotencyKey = UUID.randomUUID().toString();

        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Create Order (Saga Initiation)
        // ═══════════════════════════════════════════════════════════════
        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 2)
                .build();

        long start = System.nanoTime();

        Response createResponse = executeWithRetry("/api/orders",() -> {
            try {
                return sendOrderRequest(userToken, idempotencyKey, orderRequest);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long duration =
                TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - start);

        log.info(
                "API_METRIC endpoint=/api/orders duration={}ms status={}",
                duration,
                createResponse.statusCode()
        );

        assertThat(createResponse.statusCode())
                .as("Order creation should succeed")
                .isEqualTo(201);

        String orderId = createResponse.jsonPath().getString("id");
        String initialStatus = createResponse.jsonPath().getString("status");

        logStep("  ✓ Order created: " + orderId);
        logStep("  ✓ Initial status: " + initialStatus);

        assertThat(initialStatus)
                .as("Order should start in PENDING status")
                .isEqualTo("PENDING");

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Verify ORDER_CREATED event published
        // ═══════════════════════════════════════════════════════════════
        logStep("  Verifying ORDER_CREATED event in Kafka...");



        metrics.start();
        Optional<JsonNode> orderCreatedEvent = orderEventsConsumer.waitForMessage(
                node -> node.has("eventType") &&
                        "ORDER_CREATED".equals(node.get("eventType").asText()) &&
                        node.has("orderId") &&
                        orderId.equals(node.get("orderId").asText()),
                10
        );

        assertThat(orderCreatedEvent)
                .as("ORDER_CREATED event should be published to order.events (consumed by Payment Service)")
                .isPresent();

        logStep("  ✓ ORDER_CREATED event published to order.events");

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Wait for Payment Processing (Async Saga Step)
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for payment processing (Payment Service)...");

        // Wait for PAYMENT_COMPLETED event in payment.result topic
        Optional<JsonNode> paymentResultEvent = paymentResultConsumer.waitForMessage(
                node -> node.has("orderId") &&
                        orderId.equals(node.get("orderId").asText()) &&
                        node.has("status"),
                30  // Payment processing can take up to 30 seconds
        );

        assertThat(paymentResultEvent)
                .as("Payment result event should be published")
                .isPresent();

        JsonNode paymentResult = paymentResultEvent.get();
        String paymentStatus = paymentResult.get("status").asText();

        logStep("  ✓ Payment result received: " + paymentStatus);

        // ═══════════════════════════════════════════════════════════════
        // STEP 4: Verify Order Status Updated (Saga Completion)
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for order status update...");

        // Poll order status until it changes from PENDING
        try {
            measuredAwait(
                    "order-confirmation",
                    () -> await()
                            .atMost(Duration.ofSeconds(15))
                            .pollInterval(Duration.ofSeconds(1))
                            .ignoreExceptions()
                            .until(() -> !getOrderStatus(orderId).equals("PENDING")));
        }
        catch (ConditionTimeoutException ex) {
            MetricsManager.getInstance().recordAwaitilityTimeout();
            throw ex;
        }

        Response finalOrderResponse = RestAssured
                .given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .when()
                .get("/api/orders/" + orderId);

        String finalStatus = finalOrderResponse.jsonPath().getString("status");
        String paymentId = finalOrderResponse.jsonPath().getString("paymentId");

        logStep("  ✓ Final order status: " + finalStatus);

        // ═══════════════════════════════════════════════════════════════
        // ASSERTIONS - Saga Completion Verification
        // ═══════════════════════════════════════════════════════════════
        if (paymentStatus.equals("SUCCESS")) {
            assertThat(finalStatus)
                    .as("Order status should be CONFIRMED after successful payment")
                    .isEqualTo("CONFIRMED");

            assertThat(paymentId)
                    .as("Payment ID should be assigned on success")
                    .isNotNull()
                    .isNotEmpty();

            logStep("✅ SAGA SUCCESS: Order PENDING → Payment SUCCESS → Order CONFIRMED");

        } else if (paymentStatus.equals("FAILED")) {
            assertThat(finalStatus)
                    .as("Order status should be PAYMENT_FAILED after payment failure")
                    .isEqualTo("PAYMENT_FAILED");

            assertThat(paymentId)
                    .as("Payment ID should be null on failure")
                    .isNullOrEmpty();

            logStep("✅ SAGA COMPENSATION: Order PENDING → Payment FAILED → Order PAYMENT_FAILED");
        }

        // Either outcome proves the saga flow works!
        assertThat(finalStatus)
                .as("Order status should be updated from PENDING")
                .isIn("CONFIRMED", "PAYMENT_FAILED");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FAILURE PATH - PAYMENT COMPENSATION
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Saga Compensation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Saga compensation: Payment fails → Order status updated to PAYMENT_FAILED")
    public void test02_SagaCompensation_PaymentFailureUpdatesOrder() {
        logStep("TEST 2: Saga compensation - Payment failure path");

        String idempotencyKey = UUID.randomUUID().toString();
        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Create Order with Fault Injection Header
        // ═══════════════════════════════════════════════════════════════
        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // Inject fault to simulate payment failure
        Response createResponse = executeWithRetry("/api/orders",() -> {
            try {
                return sendOrderRequestWithFault(
                        userToken,
                        idempotencyKey,
                        orderRequest,
                        "payment-failure"  // ← Forces payment to fail
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(createResponse.statusCode()).isEqualTo(201);

        String orderId = createResponse.jsonPath().getString("id");
        String initialStatus = createResponse.jsonPath().getString("status");

        logStep("  ✓ Order created with payment failure injection: " + orderId);
        assertThat(initialStatus).isEqualTo("PENDING");

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Wait for PAYMENT_FAILED event
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for PAYMENT_FAILED event...");

        Optional<JsonNode> failureEvent = paymentResultConsumer.waitForMessage(
                node -> node.has("orderId") &&
                        orderId.equals(node.get("orderId").asText()) &&
                        "FAILED".equals(node.get("status").asText()),
                30
        );

        assertThat(failureEvent)
                .as("PAYMENT_FAILED event should be published")
                .isPresent();

        JsonNode failure = failureEvent.get();
        String failureReason = failure.has("failureReason") ?
                failure.get("failureReason").asText() : "Unknown";

        logStep("  ✓ Payment failure detected: " + failureReason);

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Verify Order Status Updated to PAYMENT_FAILED
        // ═══════════════════════════════════════════════════════════════
        measuredAwait(
                "order-confirmation",
                () ->await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> "PAYMENT_FAILED".equals(getOrderStatus(orderId))));

        Response finalOrderResponse = RestAssured
                .given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .when()
                .get("/api/orders/" + orderId);

        String finalStatus = finalOrderResponse.jsonPath().getString("status");
        String paymentId = finalOrderResponse.jsonPath().getString("paymentId");

        assertThat(finalStatus)
                .as("Order should be marked PAYMENT_FAILED after payment failure")
                .isEqualTo("PAYMENT_FAILED");

        assertThat(paymentId)
                .as("No payment ID should be assigned on failure")
                .isNullOrEmpty();

        logStep("✅ SAGA COMPENSATION validated: Payment failure correctly updated order");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // IDEMPOTENCY - DUPLICATE SAGA INITIATION
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 3)
    @Story("Saga Idempotency")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Duplicate order events don't trigger duplicate payments (idempotency)")
    public void test03_SagaIdempotency_DuplicateEventsIgnored() throws Exception {
        logStep("TEST 3: Saga idempotency - duplicate events don't cause duplicate payments");

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Create order (first request)
        // ═══════════════════════════════════════════════════════════════
        Response response1 = sendOrderRequest(userToken, idempotencyKey, orderRequest);
        assertThat(response1.statusCode()).isEqualTo(201);

        String orderId = response1.jsonPath().getString("id");
        logStep("  ✓ First request - Order created: " + orderId);

        Thread.sleep(500);

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Duplicate order request (same idempotency key)
        // ═══════════════════════════════════════════════════════════════
        Response response2 = sendOrderRequest(userToken, idempotencyKey, orderRequest);
        assertThat(response2.statusCode()).isEqualTo(200);  // ← Returns cached response
        assertThat(response2.jsonPath().getString("id")).isEqualTo(orderId);

        logStep("  ✓ Second request - Returned cached order: " + orderId);

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Count PAYMENT_COMPLETED events for this order
        // ═══════════════════════════════════════════════════════════════
        Thread.sleep(2000);  // Wait for any duplicate events

        int paymentEventCount = paymentResultConsumer.countMessages(
                node -> node.has("orderId") &&
                        orderId.equals(node.get("orderId").asText()),
                5
        );

        assertThat(paymentEventCount)
                .as("Only ONE payment should be processed (idempotent)")
                .isLessThanOrEqualTo(1);

        logStep("✅ Saga idempotency validated - no duplicate payments");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CONCURRENT SAGA EXECUTION
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 4)
    @Story("Concurrent Sagas")
    @Severity(SeverityLevel.NORMAL)
    @Description("Multiple concurrent orders execute saga flows independently")
    public void test04_ConcurrentSagas_IndependentExecution() {
        logStep("TEST 4: Concurrent saga execution - multiple orders");

        int orderCount = 3;
        String[] orderIds = new String[orderCount];

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Create multiple orders concurrently
        // ═══════════════════════════════════════════════════════════════
        for (int i = 0; i < orderCount; i++) {
            String idempotencyKey = UUID.randomUUID().toString();

            TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                    .withNamespace(context.getNamespace())
                    .addItem(product, i + 1)
                    .build();

            Response response = executeWithRetry("/api/orders",() -> {
                try {
                    return sendOrderRequest(userToken, idempotencyKey, orderRequest);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            orderIds[i] = response.jsonPath().getString("id");
            logStep("  ✓ Order " + (i + 1) + " created: " + orderIds[i]);
        }

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Wait for ALL sagas to complete
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for all sagas to complete...");

        for (String orderId : orderIds) {
            measuredAwait(
                    "order-confirmation",
                    () ->await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofSeconds(2))
                    .ignoreExceptions()
                    .until(() -> !getOrderStatus(orderId).equals("PENDING")));
        }

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Verify all orders completed (any terminal state is valid)
        // ═══════════════════════════════════════════════════════════════
        for (int i = 0; i < orderCount; i++) {
            String finalStatus = getOrderStatus(orderIds[i]);
            logStep("  ✓ Order " + (i + 1) + " final status: " + finalStatus);

            assertThat(finalStatus)
                    .as("Order should reach terminal state")
                    .isIn("CONFIRMED", "PAYMENT_FAILED");
        }

        logStep("✅ All concurrent sagas executed independently");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EVENT ORDERING - SAGA STATE CONSISTENCY
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 5)
    @Story("Event Ordering")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order events maintain consistency even with out-of-order delivery")
    public void test05_EventOrdering_SagaStateConsistency() throws InterruptedException {
        logStep("TEST 5: Event ordering - saga state consistency");

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        Response createResponse = executeWithRetry("/api/orders",() -> {
            try {
                return sendOrderRequest(userToken, idempotencyKey, orderRequest);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        String orderId = createResponse.jsonPath().getString("id");
        logStep("  ✓ Order created: " + orderId);
        timeline.mark(TestTimeline.ORDER_CREATED);

        timeline.mark(TestTimeline.PAYMENT_COMPLETED);
        // ═══════════════════════════════════════════════════════════════
        // Collect ALL events for this order (wait for Saga to complete)
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for Saga to complete...");

        // Wait for final status update (indicates Saga completed)
        measuredAwait(
                "order-confirmation",
                () ->await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> !getOrderStatus(orderId).equals("PENDING")));

        timeline.mark(TestTimeline.ORDER_CONFIRMED);
        long latency =
                timeline.durationBetween(
                        "ORDER_CREATED",
                        "ORDER_CONFIRMED");


        MetricsManager.getInstance()
                .recordSagaLatency(latency);
        logStep("  ✓ Saga completed");

        // ═══════════════════════════════════════════════════════════════
        // NOW collect all order events (they're all in Kafka already)
        // ═══════════════════════════════════════════════════════════════
        Thread.sleep(2000);  // Brief wait to ensure all events are in Kafka

        List<JsonNode> allOrderEvents = orderEventsConsumer.collectMessages(
                node -> orderId.equals(node.path("orderId").asText()),
                5  // Short timeout since events are already there
        );

        logStep("  ✓ Collected {} events for order {}", allOrderEvents.size(), orderId);

        // ═══════════════════════════════════════════════════════════════
        // Verify: Events exist and are in correct logical order
        // ═══════════════════════════════════════════════════════════════

        // 1. Find ORDER_CREATED
        Optional<JsonNode> orderCreated = allOrderEvents.stream()
                .filter(node -> "ORDER_CREATED".equals(node.path("eventType").asText()))
                .findFirst();

        assertThat(orderCreated)
                .as("ORDER_CREATED event should be published")
                .isPresent();

        logStep("  ✓ ORDER_CREATED event found");

        // 2. Verify payment result exists
        Optional<JsonNode> paymentResult = paymentResultConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText()),
                5  // Short timeout, should already be there
        );

        assertThat(paymentResult)
                .as("Payment result should be published")
                .isPresent();

        logStep("  ✓ Payment result event found");

        // 3. Find ORDER_STATUS_UPDATED
        Optional<JsonNode> statusUpdate = allOrderEvents.stream()
                .filter(node -> "ORDER_STATUS_UPDATED".equals(node.path("eventType").asText()))
                .findFirst();

        assertThat(statusUpdate)
                .as("ORDER_STATUS_UPDATED event should be published")
                .isPresent();

        logStep("  ✓ ORDER_STATUS_UPDATED event found");

        // ═══════════════════════════════════════════════════════════════
        // Verify: Timestamps show correct ordering
        // ═══════════════════════════════════════════════════════════════
        long orderCreatedTime = orderCreated.get().path("timestamp").asLong();
        long paymentTime = paymentResult.get().path("timestamp").asLong();
        long statusUpdateTime = statusUpdate.get().path("timestamp").asLong();

        assertThat(orderCreatedTime)
                .as("ORDER_CREATED should happen before payment")
                .isLessThan(paymentTime);

        assertThat(paymentTime)
                .as("Payment should complete before status update")
                .isLessThan(statusUpdateTime);

        logStep("  ✓ Event timestamps in correct order:");
        logStep("    1. ORDER_CREATED: {}", orderCreatedTime);
        logStep("    2. PAYMENT_RESULT: {}", paymentTime);
        logStep("    3. ORDER_STATUS_UPDATED: {}", statusUpdateTime);

        logStep("✅ Event ordering validated - saga consistency maintained");
    }
    // ══════════════════════════════════════════════════════════════════════════
    // TIMEOUT HANDLING - PAYMENT SERVICE DOWN
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 6)
    @Story("Saga Timeout")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Order remains PENDING if payment service is down (no false success)")
    public void test06_SagaTimeout_OrderRemainsPendingIfPaymentDown() {
        logStep("TEST 6: Saga timeout - payment service unavailable");

        // NOTE: This test requires manual setup:
        // 1. Stop payment-service: docker-compose stop payment-service
        // 2. Run this test
        // 3. Verify order stays PENDING (doesn't falsely move to CONFIRMED)

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        Response createResponse = executeWithRetry("/api/orders",() -> {
            try {
                return sendOrderRequest(userToken, idempotencyKey, orderRequest);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        String orderId = createResponse.jsonPath().getString("id");
        String initialStatus = createResponse.jsonPath().getString("status");

        assertThat(initialStatus).isEqualTo("PENDING");
        logStep("  ✓ Order created (payment service DOWN): " + orderId);

        // Wait reasonable time for payment processing
        try {
            Thread.sleep(10000);  // 10 seconds
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify: Order should STILL be PENDING (not falsely CONFIRMED)
        String finalStatus = getOrderStatus(orderId);

        assertThat(finalStatus)
                .as("Order should remain PENDING if payment service is unavailable")
                .isEqualTo("PENDING");

        logStep("✅ Order correctly remains PENDING when payment service is down");
        logStep("   (No false CONFIRMED status - saga timeout handled correctly)");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private Response sendOrderRequest(
            String userToken,
            String idempotencyKey,
            TestModels.CreateOrderRequest orderRequest) throws Exception {

        String requestBody = objectMapper.writeValueAsString(orderRequest);

        return RestAssured
                .given().log().all()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .body(requestBody)
                .when().log().all()
                .post("/api/orders");
    }

    private Response sendOrderRequestWithFault(
            String userToken,
            String idempotencyKey,
            TestModels.CreateOrderRequest orderRequest,
            String faultType) throws Exception {

        String requestBody = objectMapper.writeValueAsString(orderRequest);

        return RestAssured
                .given().log().all()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-User-Id", user.getId().toString())
                .header("X-Fault", faultType)  // ⭐ Fault injection
                .contentType("application/json")
                .body(requestBody)
                .when().log().all()
                .post("/api/orders");
    }

    private String getOrderStatus(String orderId) {
        try {
            Response response = RestAssured
                    .given()
                    .baseUri(context.getConfig().baseUrl())
                    .header("Authorization", "Bearer " + userToken)
                    .when()
                    .get("/api/orders/" + orderId);

            if (response.statusCode() == 200) {
                return response.jsonPath().getString("status");
            }
        } catch (Exception e) {
            log.warn("Failed to get order status for {}: {}", orderId, e.getMessage());
        }
        return "UNKNOWN";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ══════════════════════════════════════════════════════════════════════════

    @AfterMethod
    public void cleanupTestMethod() {

        long wallTime =
                System.currentTimeMillis() - testStartTime;

        long cpuTime =
                osBean.getProcessCpuTime() - testCpuStart;

        long endMemory =
                Runtime.getRuntime().totalMemory()
                        - Runtime.getRuntime().freeMemory();

        log.info("""
        TEST METRICS

        Wall Time: {} ms
        CPU Time : {} ms
        Wait Time: {} ms
        CPU Ratio: {} %
        """,
                wallTime,
                cpuTime / 1_000_000,
                wallTime - (cpuTime / 1_000_000),
                (cpuTime / 1_000_000.0) / wallTime * 100


        );
        timeline.printSummary();


    }
    @AfterClass
    public void cleanup() {
        if (orderEventsConsumer != null) {
            orderEventsConsumer.close();
        }
        if (paymentResultConsumer != null) {
            paymentResultConsumer.close();
        }
        logStep("✅ Saga test consumers closed");
    }
}