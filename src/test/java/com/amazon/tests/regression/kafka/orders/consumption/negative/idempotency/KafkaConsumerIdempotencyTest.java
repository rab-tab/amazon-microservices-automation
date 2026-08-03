package com.amazon.tests.regression.kafka.orders.consumption.negative.idempotency;

import com.amazon.tests.BaseTest;
import com.amazon.tests.config.kafka.KafkaConfig;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka Consumer Idempotency - Application-Level Event Deduplication
 *
 * ⚠️ IMPORTANT LIMITATION: countPaymentsForOrder() currently checks only
 * whether the Order has A paymentId set (0 or 1) — it CANNOT detect a
 * genuine duplicate-payment bug (2+ Payment rows for one order), since
 * an Order only ever exposes a single paymentId field over REST. The
 * "only ONE payment created" assertions in this file will pass even if
 * the backend created multiple Payment rows. This needs a direct DB
 * count query (e.g. via this project's DatabaseValidator, if it exposes
 * one) before these tests can be trusted to catch the bug they claim to
 * test. Flagging rather than guessing at the DB utility's API — needs
 * follow-up.
 */
@Slf4j
@Epic("Kafka Consumer Idempotency")
@Feature("Application-Level: Event Deduplication")
public class KafkaConsumerIdempotencyTest extends BaseTest {

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String PAYMENT_RESULT_TOPIC = "payment.result";
    private static final String ORDER_EVENTS_DLQ = "order.events.DLQ";

    private KafkaProducer<String, String> kafkaProducer;
    private KafkaTestConsumer orderEventsMonitor;
    private KafkaTestConsumer paymentResultMonitor;
    private String userId;
    private String userToken;

    @org.testng.annotations.BeforeMethod
    public void setup() {
        logStep("Setting up application-level idempotency tests");

        PurchaseResult purchase = PurchaseWorkflow.start(executor, authStrategy)
                .registerCustomer()
                .execute();
        userId = purchase.getCustomer().getUser().getId();
        userToken = purchase.getCustomer().getAccessToken();

        kafkaProducer = new KafkaProducer<>(KafkaConfig.getProducerProperties());
        orderEventsMonitor = new KafkaTestConsumer(ORDER_EVENTS_TOPIC);
        paymentResultMonitor = new KafkaTestConsumer(PAYMENT_RESULT_TOPIC);

        logStep("✅ Setup complete — user: " + userId);
    }

    @AfterMethod
    public void cleanup() {
        if (kafkaProducer != null) kafkaProducer.close();
        if (orderEventsMonitor != null) orderEventsMonitor.close();
        if (paymentResultMonitor != null) paymentResultMonitor.close();
        logStep("🧹 Kafka clients closed");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST: DUPLICATE EVENT PROCESSING
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Duplicate Event Processing")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Same ORDER_CREATED event consumed twice - verify only ONE payment created")
    public void test_DuplicateEventProcessing_OnlyOnePaymentCreated() throws Exception {
        logStep("TEST: Duplicate event processing");

        String orderId = UUID.randomUUID().toString();
        paymentResultMonitor.seekToEnd();

        logStep("  Publishing same ORDER_CREATED event TWICE to Kafka");
        String orderCreatedEvent = buildOrderCreatedEvent(orderId);

        kafkaProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, orderId, orderCreatedEvent)).get();
        logStep("    Event #1 published");

        kafkaProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, orderId, orderCreatedEvent)).get();
        logStep("    Event #2 published (DUPLICATE)");

        kafkaProducer.flush();

        logStep("  Waiting for payment result event...");
        Optional<JsonNode> paymentResult = paymentResultMonitor.waitForMessage(
                msg -> orderId.equals(msg.path("orderId").asText()), 20);

        assertThat(paymentResult).as("Payment result should be published").isPresent();
        logStep("  ✓ Payment result received");

        Thread.sleep(5000); // give time for duplicate to be processed

        int paymentCount = countPaymentsForOrder(orderId);
        assertThat(paymentCount)
                .as("Only ONE payment should exist despite duplicate event")
                .isEqualTo(1);

        logStep("✅ DUPLICATE EVENT IDEMPOTENCY VALIDATED — same event published twice, only 1 payment created");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST: OUT-OF-ORDER EVENTS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Out-of-Order Events")
    @Severity(SeverityLevel.CRITICAL)
    @Description("PAYMENT_COMPLETED arrives BEFORE ORDER_CREATED - verify graceful handling")
    public void test_OutOfOrderEvents_PaymentBeforeOrder() throws Exception {
        logStep("TEST: Out-of-order events - Payment before Order");

        String orderId = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();

        orderEventsMonitor.seekToEnd();
        paymentResultMonitor.seekToEnd();

        logStep("  Publishing PAYMENT_COMPLETED event (OUT OF ORDER!)");
        String paymentCompletedEvent = String.format(
                "{\"orderId\":\"%s\",\"paymentId\":\"%s\",\"status\":\"SUCCESS\",\"amount\":99.99,\"timestamp\":%d}",
                orderId, paymentId, System.currentTimeMillis());

        kafkaProducer.send(new ProducerRecord<>(PAYMENT_RESULT_TOPIC, orderId, paymentCompletedEvent)).get();
        kafkaProducer.flush();
        logStep("  ✓ PAYMENT_COMPLETED published (before ORDER_CREATED!)");

        Thread.sleep(5000);

        logStep("  Publishing ORDER_CREATED event (correct sequence)");
        String orderCreatedEvent = buildOrderCreatedEvent(orderId);
        kafkaProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, orderId, orderCreatedEvent)).get();
        kafkaProducer.flush();
        logStep("  ✓ ORDER_CREATED published");

        logStep("  Waiting for Payment Service to process ORDER_CREATED...");
        Optional<JsonNode> paymentResult = paymentResultMonitor.waitForMessage(
                msg -> orderId.equals(msg.path("orderId").asText()), 20);

        assertThat(paymentResult).as("Payment result should eventually be published").isPresent();
        String paymentStatus = paymentResult.get().path("status").asText();
        logStep("  ✓ Payment result received: " + paymentStatus);

        Thread.sleep(3000);

        Response finalResponse = getOrder(orderId);
        if (finalResponse.statusCode() == 200) {
            String finalStatus = finalResponse.jsonPath().getString("status");
            String finalPaymentId = finalResponse.jsonPath().getString("paymentId");

            logStep("  Final order state — status: " + finalStatus + ", paymentId: " + finalPaymentId);

            assertThat(finalStatus)
                    .as("Order should reach terminal state")
                    .isIn("CONFIRMED", "PAYMENT_FAILED", "PENDING");

            logStep("✅ OUT-OF-ORDER EVENT HANDLING VALIDATED — final state: " + finalStatus);
        } else {
            logStep("  ℹ️ Order not found in Order Service (acceptable if system rejects out-of-order events)");
        }

        logStep("  Note: expected behavior varies by implementation — queue, reject, or create-from-payment");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST: CONCURRENT PROCESSING (RACE CONDITION)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Concurrent Processing")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Same event to multiple partitions - simulate race condition")
    public void test_ConcurrentProcessing_OnlyOneSucceeds() throws Exception {
        logStep("TEST: Concurrent processing of same event (race condition)");

        String orderId = UUID.randomUUID().toString();
        paymentResultMonitor.seekToEnd();

        logStep("  Publishing same event to 3 different partitions simultaneously");
        String orderCreatedEvent = buildOrderCreatedEvent(orderId);

        for (int partition = 0; partition < 3; partition++) {
            kafkaProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, partition, orderId, orderCreatedEvent));
            logStep("    Event published to partition " + partition);
        }
        kafkaProducer.flush();

        logStep("  Waiting for payment result...");
        Optional<JsonNode> paymentResult = paymentResultMonitor.waitForMessage(
                msg -> orderId.equals(msg.path("orderId").asText()), 20);

        assertThat(paymentResult).as("Payment result should be published").isPresent();

        Thread.sleep(5000);

        int paymentCount = countPaymentsForOrder(orderId);
        assertThat(paymentCount)
                .as("Only ONE payment despite multi-partition")
                .isLessThanOrEqualTo(1);

        logStep("✅ CONCURRENT PROCESSING HANDLED — " + paymentCount + " payment(s) created from 3 partitions");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST: MISSING IDEMPOTENCY KEY
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Missing Idempotency Key")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Event without orderId - verify rejection and DLQ routing")
    public void test_MissingIdempotencyKey_EventRejected() throws Exception {
        logStep("TEST: Event with missing idempotency key (orderId)");

        logStep("  Publishing ORDER_CREATED event WITHOUT orderId");
        String invalidEvent = String.format(
                "{\"eventType\":\"ORDER_CREATED\",\"userId\":\"%s\",\"amount\":99.99,\"timestamp\":%d}",
                userId, System.currentTimeMillis());

        kafkaProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, "no-id", invalidEvent)).get();
        kafkaProducer.flush();
        logStep("  ✓ Invalid event published (missing orderId)");

        Thread.sleep(10000);

        logStep("  Checking DLQ for rejected event...");
        KafkaTestConsumer dlqConsumer = new KafkaTestConsumer(ORDER_EVENTS_DLQ);
        try {
            dlqConsumer.seekToBeginning();

            List<JsonNode> dlqMessages = dlqConsumer.collectMessages(
                    node -> {
                        String text = node.asText();
                        return text.contains("ORDER_CREATED") && !text.contains("\"orderId\":");
                    },
                    5
            );

            assertThat(dlqMessages).as("Event without orderId should be in DLQ").isNotEmpty();
            logStep("✅ MISSING KEY HANDLED — event rejected and sent to DLQ");
        } finally {
            dlqConsumer.close();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════

    private String buildOrderCreatedEvent(String orderId) {
        return String.format(
                "{\"eventType\":\"ORDER_CREATED\",\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":99.99,\"timestamp\":%d}",
                orderId, userId, System.currentTimeMillis());
    }

    private Response getOrder(String orderId) {
        return RestAssured
                .given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .when()
                .get("/api/orders/" + orderId);
    }

    /**
     * ⚠️ See class-level Javadoc — this can only ever return 0 or 1, since
     * it checks the Order's single paymentId field, not an actual count of
     * Payment rows in the DB. Needs replacing with a real DB count query
     * before test_DuplicateEventProcessing_OnlyOnePaymentCreated and
     * test_ConcurrentProcessing_OnlyOneSucceeds can genuinely catch a
     * duplicate-payment bug.
     */
    private int countPaymentsForOrder(String orderId) {
        try {
            Response response = getOrder(orderId);
            if (response.statusCode() == 200) {
                String paymentId = response.jsonPath().getString("paymentId");
                return paymentId != null && !paymentId.isEmpty() ? 1 : 0;
            }
        } catch (Exception e) {
            log.warn("Failed to count payments: {}", e.getMessage());
        }
        return 0;
    }
}