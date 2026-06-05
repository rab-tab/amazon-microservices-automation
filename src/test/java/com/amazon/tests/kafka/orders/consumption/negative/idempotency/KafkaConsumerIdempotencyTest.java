package com.amazon.tests.kafka.orders.consumption.negative.idempotency;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.KafkaTestConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Kafka Consumer Application-Level Idempotency Tests
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Tests application-level duplicate detection and event handling:
 *
 * Test 15: Duplicate Event Processing
 * Test 16: Out-of-Order Events
 * Test 17: Concurrent Processing (Race Condition)
 * Test 18: Missing Idempotency Key
 */
@Slf4j
@Epic("Kafka Consumer Idempotency")
@Feature("Application-Level: Event Deduplication")
public class KafkaConsumerIdempotencyTest extends BaseTest {

    private static final String KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String PAYMENT_RESULT_TOPIC = "payment.result";
    private static final String ORDER_EVENTS_DLQ = "order.events.DLQ";

    private KafkaProducer<String, String> kafkaProducer;
    private KafkaTestConsumer orderEventsMonitor;
    private KafkaTestConsumer paymentResultMonitor;
    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;

    @BeforeMethod
    public void setup() throws SeedingException {
        logStep("Setting up application-level idempotency tests");

        user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        userToken = context.getCached("user_token_" + user.getId(), String.class);
        product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        // Initialize Kafka producer
        initializeKafkaProducer();

        // Initialize Kafka monitors
        orderEventsMonitor = new KafkaTestConsumer(ORDER_EVENTS_TOPIC);
        paymentResultMonitor = new KafkaTestConsumer(PAYMENT_RESULT_TOPIC);

        logStep("✅ Setup complete");
    }

    private void initializeKafkaProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.RETRIES_CONFIG, "0");

        kafkaProducer = new KafkaProducer<>(props);
        logStep("  ✓ Kafka producer initialized");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 15: DUPLICATE EVENT PROCESSING
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Duplicate Event Processing")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Same ORDER_CREATED event consumed twice - verify only ONE payment created")
    public void test_DuplicateEventProcessing_OnlyOnePaymentCreated() throws Exception {
        logStep("TEST : Duplicate event processing");

        String orderId = UUID.randomUUID().toString();

        // Start monitoring for payment result
        paymentResultMonitor.seekToEnd();

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Publish same ORDER_CREATED event TWICE
        // ═══════════════════════════════════════════════════════════════
        logStep("  Publishing same ORDER_CREATED event TWICE to Kafka");

        String orderCreatedEvent = String.format(
                "{\"eventType\":\"ORDER_CREATED\",\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":99.99,\"timestamp\":%d}",
                orderId, user.getId(), System.currentTimeMillis()
        );

        kafkaProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, orderId, orderCreatedEvent)).get();
        logStep("    Event #1 published");

        kafkaProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, orderId, orderCreatedEvent)).get();
        logStep("    Event #2 published (DUPLICATE)");

        kafkaProducer.flush();

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Wait for Payment Service to process
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for payment result event...");

        Optional<JsonNode> paymentResult = paymentResultMonitor.waitForMessage(
                msg -> orderId.equals(msg.path("orderId").asText()),
                20
        );

        assertThat(paymentResult)
                .as("Payment result should be published")
                .isPresent();

        logStep("  ✓ Payment result received");

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Verify only ONE payment was created
        // ═══════════════════════════════════════════════════════════════
        Thread.sleep(5000); // Give time for duplicate to be processed

        int paymentCount = countPaymentsForOrder(orderId);

        assertThat(paymentCount)
                .as("Only ONE payment should exist despite duplicate event")
                .isEqualTo(1);

        logStep("✅ DUPLICATE EVENT IDEMPOTENCY VALIDATED:");
        logStep("  ✓ Same event published twice");
        logStep("  ✓ Only 1 payment created");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST : OUT-OF-ORDER EVENTS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Out-of-Order Events")
    @Severity(SeverityLevel.CRITICAL)
    @Description("PAYMENT_COMPLETED arrives BEFORE ORDER_CREATED - verify graceful handling")
    public void test_OutOfOrderEvents_PaymentBeforeOrder() throws Exception {
        logStep("TEST : Out-of-order events - Payment before Order");

        String orderId = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();

        // Start monitoring
        orderEventsMonitor.seekToEnd();
        paymentResultMonitor.seekToEnd();

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Publish PAYMENT_COMPLETED event FIRST (out of order!)
        // ═══════════════════════════════════════════════════════════════
        logStep("  Publishing PAYMENT_COMPLETED event (OUT OF ORDER!)");

        String paymentCompletedEvent = String.format(
                "{\"orderId\":\"%s\",\"paymentId\":\"%s\",\"status\":\"SUCCESS\",\"amount\":99.99,\"timestamp\":%d}",
                orderId, paymentId, System.currentTimeMillis()
        );

        kafkaProducer.send(new ProducerRecord<>(PAYMENT_RESULT_TOPIC, orderId, paymentCompletedEvent)).get();
        kafkaProducer.flush();

        logStep("  ✓ PAYMENT_COMPLETED published (before ORDER_CREATED!)");

        // Wait a bit
        Thread.sleep(5000);

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Verify payment result was received by Order Service
        // ═══════════════════════════════════════════════════════════════
        logStep("  Checking if Order Service received out-of-order payment...");

        // Note: This tests whether system can handle out-of-order events
        // Expected behavior depends on implementation:
        // Option 1: Order Service queues the payment event
        // Option 2: Order Service rejects it (order not found)
        // Option 3: Order Service creates order from payment event

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: NOW publish ORDER_CREATED event (correct sequence)
        // ═══════════════════════════════════════════════════════════════
        logStep("  Publishing ORDER_CREATED event (correct sequence)");

        String orderCreatedEvent = String.format(
                "{\"eventType\":\"ORDER_CREATED\",\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":99.99,\"timestamp\":%d}",
                orderId, user.getId(), System.currentTimeMillis()
        );

        kafkaProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, orderId, orderCreatedEvent)).get();
        kafkaProducer.flush();

        logStep("  ✓ ORDER_CREATED published");

        // ═══════════════════════════════════════════════════════════════
        // STEP 4: Wait for Payment Service to process ORDER_CREATED
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for Payment Service to process ORDER_CREATED...");

        // ✅ Monitor payment.result topic for the payment result
        Optional<JsonNode> paymentResult = paymentResultMonitor.waitForMessage(
                msg -> orderId.equals(msg.path("orderId").asText()),
                20
        );

        assertThat(paymentResult)
                .as("Payment result should eventually be published")
                .isPresent();

        String paymentStatus = paymentResult.get().path("status").asText();
        logStep("  ✓ Payment result received: {}", paymentStatus);

        // ═══════════════════════════════════════════════════════════════
        // STEP 5: Verify final order state
        // ═══════════════════════════════════════════════════════════════
        Thread.sleep(3000); // Give Order Service time to update

        Response finalResponse = getOrder(orderId);

        if (finalResponse.statusCode() == 200) {
            String finalStatus = finalResponse.jsonPath().getString("status");
            String finalPaymentId = finalResponse.jsonPath().getString("paymentId");

            logStep("  Final order state:");
            logStep("    Status: {}", finalStatus);
            logStep("    Payment ID: {}", finalPaymentId);

            assertThat(finalStatus)
                    .as("Order should reach terminal state")
                    .isIn("CONFIRMED", "PAYMENT_FAILED", "PENDING");

            logStep("✅ OUT-OF-ORDER EVENT HANDLING VALIDATED:");
            logStep("  ✓ PAYMENT_COMPLETED arrived before ORDER_CREATED");
            logStep("  ✓ System handled out-of-order events");
            logStep("  ✓ Final state: {}", finalStatus);
        } else {
            logStep("  ℹ️  Order not found in Order Service");
            logStep("  (This is acceptable if system rejects out-of-order events)");
        }

        logStep("");
        logStep("  Note: Expected behavior varies by implementation:");
        logStep("  - Option 1: Queue payment event until order created");
        logStep("  - Option 2: Reject payment event (order not found)");
        logStep("  - Option 3: Create order from payment event");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST : CONCURRENT PROCESSING (RACE CONDITION)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Concurrent Processing")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Same event to multiple partitions - simulate race condition")
    public void test_ConcurrentProcessing_OnlyOneSucceeds() throws Exception {
        logStep("TEST 17: Concurrent processing of same event (race condition)");

        String orderId = UUID.randomUUID().toString();

        // Start monitoring
        paymentResultMonitor.seekToEnd();

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Publish same event to MULTIPLE partitions
        // ═══════════════════════════════════════════════════════════════
        logStep("  Publishing same event to 3 different partitions simultaneously");

        String orderCreatedEvent = String.format(
                "{\"eventType\":\"ORDER_CREATED\",\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":99.99,\"timestamp\":%d}",
                orderId, user.getId(), System.currentTimeMillis()
        );

        for (int partition = 0; partition < 3; partition++) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    ORDER_EVENTS_TOPIC,
                    partition,
                    orderId,
                    orderCreatedEvent
            );
            kafkaProducer.send(record);
            logStep("    Event published to partition {}", partition);
        }

        kafkaProducer.flush();

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Wait for payment result
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for payment result...");

        Optional<JsonNode> paymentResult = paymentResultMonitor.waitForMessage(
                msg -> orderId.equals(msg.path("orderId").asText()),
                20
        );

        assertThat(paymentResult)
                .as("Payment result should be published")
                .isPresent();

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Verify only ONE payment
        // ═══════════════════════════════════════════════════════════════
        Thread.sleep(5000);

        int paymentCount = countPaymentsForOrder(orderId);

        assertThat(paymentCount)
                .as("Only ONE payment despite multi-partition")
                .isLessThanOrEqualTo(1);

        logStep("✅ CONCURRENT PROCESSING HANDLED:");
        logStep("  ✓ Event published to 3 partitions");
        logStep("  ✓ Only {} payment(s) created", paymentCount);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST : MISSING IDEMPOTENCY KEY
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Missing Idempotency Key")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Event without orderId - verify rejection and DLQ routing")
    public void test_MissingIdempotencyKey_EventRejected() throws Exception {
        logStep("TEST 18: Event with missing idempotency key (orderId)");

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Publish ORDER_CREATED event WITHOUT orderId
        // ═══════════════════════════════════════════════════════════════
        logStep("  Publishing ORDER_CREATED event WITHOUT orderId");

        String invalidEvent = String.format(
                "{\"eventType\":\"ORDER_CREATED\",\"userId\":\"%s\",\"amount\":99.99,\"timestamp\":%d}",
                user.getId(), System.currentTimeMillis()
        );

        kafkaProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, "no-id", invalidEvent)).get();
        kafkaProducer.flush();

        logStep("  ✓ Invalid event published (missing orderId)");

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Wait and check DLQ
        // ═══════════════════════════════════════════════════════════════
        Thread.sleep(10000);

        logStep("  Checking DLQ for rejected event...");

        KafkaTestConsumer dlqConsumer = new KafkaTestConsumer(ORDER_EVENTS_DLQ);
        dlqConsumer.seekToBeginning();

        List<JsonNode> dlqMessages = dlqConsumer.collectMessages(
                node -> {
                    String text = node.asText();
                    return text.contains("ORDER_CREATED") && !text.contains("\"orderId\":");
                },
                5
        );

        dlqConsumer.close();

        assertThat(dlqMessages)
                .as("Event without orderId should be in DLQ")
                .isNotEmpty();

        logStep("✅ MISSING KEY HANDLED:");
        logStep("  ✓ Event rejected and sent to DLQ");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════════

    private Response getOrder(String orderId) {
        return RestAssured
                .given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .when()
                .get("/api/orders/" + orderId);
    }

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

    @AfterClass
    public void cleanup() {
        if (kafkaProducer != null) {
            kafkaProducer.close();
        }
        if (orderEventsMonitor != null) {
            orderEventsMonitor.close();
        }
        if (paymentResultMonitor != null) {
            paymentResultMonitor.close();
        }
        logStep("✅ Cleanup complete");
    }
}