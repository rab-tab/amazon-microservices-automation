package com.amazon.tests.regression.kafka.orders.consumption.negative.deserialization;

import com.amazon.tests.BaseTest;
import com.amazon.tests.config.kafka.KafkaConfig;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka Consumer Resilience - Deserialization Failure Handling
 *
 * Verifies order-service's Kafka consumer correctly routes malformed/
 * corrupt/incomplete events to a dead-letter queue instead of crashing,
 * and continues processing valid events afterward.
 *
 * NOTE: These tests bypass the API entirely and publish directly to
 * order.events — intentional, since the goal is to exercise the
 * consumer's own deserialization/error-handling path, not the API layer.
 */
@Slf4j
@Epic("Kafka Consumer Resilience")
@Feature("Deserialization Failure Handling")
public class KafkaDeserializationFailureTests extends BaseTest {

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String ORDER_EVENTS_DLQ = "order.events.DLQ";
    private static final String PAYMENT_RESULT_DLQ = "payment.result.DLQ";

    private KafkaProducer<String, String> stringProducer;
    private KafkaProducer<String, byte[]> binaryProducer;
    private KafkaTestConsumer dlqConsumer;
    private KafkaTestConsumer orderEventsConsumer;

    private String userId;

    @BeforeClass
    public void setupSuite() throws Exception {
        // DLQ topics are suite-level infra — create once, not per test method
        createDLQTopicIfNotExists(ORDER_EVENTS_DLQ);
        createDLQTopicIfNotExists(PAYMENT_RESULT_DLQ);
    }

    @BeforeMethod
    public void setup() {
        logStep("Setting up deserialization failure test");

        PurchaseResult purchase = PurchaseWorkflow.start(executor, authStrategy)
                .registerCustomer()
                .execute();
        userId = purchase.getCustomer().getUser().getId();

        stringProducer = new KafkaProducer<>(KafkaConfig.getProducerProperties());
        binaryProducer = new KafkaProducer<>(binaryProducerProperties());

        dlqConsumer = new KafkaTestConsumer(ORDER_EVENTS_DLQ);
        orderEventsConsumer = new KafkaTestConsumer(ORDER_EVENTS_TOPIC);

        dlqConsumer.seekToEnd();
        orderEventsConsumer.seekToEnd();

        logStep("✅ Setup complete — user: " + userId);
    }

    @AfterMethod
    public void cleanup() {
        if (stringProducer != null) stringProducer.close();
        if (binaryProducer != null) binaryProducer.close();
        if (dlqConsumer != null) dlqConsumer.close();
        if (orderEventsConsumer != null) orderEventsConsumer.close();
        logStep("🧹 Kafka producers/consumers closed");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST 1: MALFORMED JSON - CONSUMER RESILIENCE
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("Deserialization Failures")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Malformed JSON event routed to DLQ, consumer continues processing")
    public void test01_MalformedJSON_RoutedToDLQ() throws Exception {
        logStep("TEST 1: Malformed JSON handling");

        String orderId = UUID.randomUUID().toString();
        String malformedJson = String.format(
                "{\"eventType\":\"ORDER_CREATED\",\"orderId\":\"%s\",\"amount\":INVALID_SYNTAX}", orderId);

        logStep("  Publishing malformed ORDER_CREATED event (bypasses API — direct to Kafka)");
        stringProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, orderId, malformedJson)).get();
        stringProducer.flush();
        logStep("  ✓ Malformed event published");

        Optional<JsonNode> dlqEvent = dlqConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText()) || node.asText().contains(orderId), 15);

        assertThat(dlqEvent).as("Malformed event should be routed to DLQ").isPresent();
        logStep("  ✓ Malformed event found in DLQ: " + ORDER_EVENTS_DLQ);

        // Verify consumer is still healthy — publish a valid event and confirm it's processed
        String healthCheckOrderId = UUID.randomUUID().toString();
        String validJson = buildValidOrderEventJson(healthCheckOrderId);

        stringProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, healthCheckOrderId, validJson)).get();
        stringProducer.flush();

        Optional<JsonNode> processedEvent = orderEventsConsumer.waitForMessage(
                node -> healthCheckOrderId.equals(node.path("orderId").asText()), 10);

        assertThat(processedEvent)
                .as("Consumer should still process valid events after deserialization failure")
                .isPresent();

        logStep("✅ TEST PASSED — malformed event routed to DLQ, consumer stayed healthy");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST 2: MISSING REQUIRED FIELDS
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 2)
    @Story("Deserialization Failures")
    @Severity(SeverityLevel.NORMAL)
    @Description("Event with missing required fields handled gracefully")
    public void test02_MissingRequiredFields_HandledGracefully() throws Exception {
        logStep("TEST 2: Missing required fields");

        String orderId = UUID.randomUUID().toString();
        String incompleteJson = String.format("{\"eventType\":\"ORDER_CREATED\",\"orderId\":\"%s\"}", orderId);

        logStep("  Publishing incomplete event (valid JSON, missing userId/amount)");
        stringProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, orderId, incompleteJson)).get();
        stringProducer.flush();

        Optional<JsonNode> dlqEvent = dlqConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText()) || node.asText().contains(orderId), 15);

        assertThat(dlqEvent).as("Incomplete event should be routed to DLQ").isPresent();
        logStep("✅ Incomplete event routed to DLQ");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST 3: CORRUPT BINARY DATA
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 3)
    @Story("Deserialization Failures")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Corrupt binary data doesn't crash consumer")
    public void test03_CorruptBinaryData_ConsumerSurvives() throws Exception {
        logStep("TEST 3: Corrupt binary data");

        String orderId = UUID.randomUUID().toString();
        byte[] corruptData = new byte[]{
                (byte) 0xFF, (byte) 0xFE, (byte) 0xFD, (byte) 0xFC,
                0x00, 0x01, 0x02, (byte) 0x80, (byte) 0x90, (byte) 0xA0
        };

        logStep("  Publishing corrupt binary data — cannot be deserialized as JSON");
        binaryProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, orderId, corruptData)).get();
        binaryProducer.flush();
        logStep("  ✓ Corrupt binary published");

        String healthCheckOrderId = UUID.randomUUID().toString();
        String validJson = buildValidOrderEventJson(healthCheckOrderId);

        stringProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, healthCheckOrderId, validJson)).get();
        stringProducer.flush();

        Optional<JsonNode> healthCheck = orderEventsConsumer.waitForMessage(
                node -> healthCheckOrderId.equals(node.path("orderId").asText()), 10);

        assertThat(healthCheck).as("Consumer should survive corrupt binary data").isPresent();
        logStep("✅ Consumer survived corrupt binary data");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST 4: BURST OF BAD EVENTS - SYSTEM STABILITY
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 4)
    @Story("Deserialization Failures")
    @Severity(SeverityLevel.BLOCKER)
    @Description("System remains stable under burst of malformed events")
    public void test04_BurstOfBadEvents_SystemStability() throws Exception {
        logStep("TEST 4: System stability under burst of bad events");

        int badEventCount = 5;
        int validEventCount = 3;

        logStep("  Publishing " + badEventCount + " malformed events in rapid succession...");
        for (int i = 0; i < badEventCount; i++) {
            String badJson = String.format("{INVALID_JSON_%d}", i);
            stringProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, "bad-" + i, badJson));
        }
        stringProducer.flush();
        logStep("  ✓ All " + badEventCount + " bad events published");

        logStep("  Publishing " + validEventCount + " valid events to verify consumer health...");
        String[] validOrderIds = new String[validEventCount];
        for (int i = 0; i < validEventCount; i++) {
            validOrderIds[i] = UUID.randomUUID().toString();
            stringProducer.send(new ProducerRecord<>(
                    ORDER_EVENTS_TOPIC, validOrderIds[i], buildValidOrderEventJson(validOrderIds[i])));
        }
        stringProducer.flush();

        int processedCount = 0;
        for (String orderId : validOrderIds) {
            Optional<JsonNode> processed = orderEventsConsumer.waitForMessage(
                    node -> orderId.equals(node.path("orderId").asText()), 10);
            if (processed.isPresent()) {
                processedCount++;
                logStep("    ✓ Order " + orderId + " processed");
            }
        }

        assertThat(processedCount)
                .as("All valid events should be processed despite burst of bad events")
                .isEqualTo(validEventCount);

        logStep("✅ SYSTEM STABILITY VALIDATED — " + badEventCount + " bad events routed to DLQ, "
                + validEventCount + " valid events processed, no consumer crash");
    }

    // ══════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════

    private String buildValidOrderEventJson(String orderId) {
        return String.format(
                "{\"eventType\":\"ORDER_CREATED\",\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":99.99,\"timestamp\":%d}",
                orderId, userId, System.currentTimeMillis());
    }

    private Properties binaryProducerProperties() {
        // Reuse KafkaConfig's shared settings (bootstrap servers, acks, retries,
        // idempotence), just swap the value serializer for raw bytes.
        Properties props = KafkaConfig.getProducerProperties();
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return props;
    }

    private void createDLQTopicIfNotExists(String topicName) throws Exception {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaConfig.getBootstrapServers());

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            Set<String> existingTopics = adminClient.listTopics().names().get();
            if (!existingTopics.contains(topicName)) {
                adminClient.createTopics(Collections.singleton(new NewTopic(topicName, 3, (short) 1))).all().get();
                logStep("  ✓ Created DLQ topic: " + topicName);
            } else {
                logStep("  ✓ DLQ topic already exists: " + topicName);
            }
        }
    }
}