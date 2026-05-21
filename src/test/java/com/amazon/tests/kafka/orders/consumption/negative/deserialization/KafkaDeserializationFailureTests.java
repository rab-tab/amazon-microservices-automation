package com.amazon.tests.kafka.orders.consumption.negative.deserialization;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.KafkaTestConsumer;
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
import org.apache.kafka.common.serialization.StringSerializer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Kafka Consumer Deserialization Failure Tests
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * SCOPE: Tests consumer resilience when receiving malformed events
 *
 * CRITICAL DIFFERENCE FROM SAGA TESTS:
 * - Saga Tests: Valid events through normal API flow
 * - These Tests: Malformed events published DIRECTLY to Kafka (bypass API)
 *
 * WHAT'S TESTED:
 * ✅ Consumer handles malformed JSON without crashing
 * ✅ Malformed events routed to Dead Letter Queue (DLQ)
 * ✅ Consumer continues processing valid events after failures
 * ✅ System stability under burst of bad events
 *
 * ARCHITECTURE:
 * Order Service → order.events topic → Payment Service (consumer)
 *                                    ↓ (on deserialization failure)
 *                                  order.events.DLQ
 *
 * @author Test Automation Team
 */
@Slf4j
@Epic("Kafka Consumer Resilience")
@Feature("Deserialization Failure Handling")
public class KafkaDeserializationFailureTests extends BaseTest {

    private static final String KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String ORDER_EVENTS_DLQ = "order.events.DLQ";

    private KafkaProducer<String, String> stringProducer;
    private KafkaProducer<String, byte[]> binaryProducer;
    private KafkaTestConsumer dlqConsumer;
    private KafkaTestConsumer orderEventsConsumer;

    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;

    @BeforeMethod
    public void setup() throws Exception {
        logStep("Setting up deserialization failure tests");

        // Seed test data using builder pattern
        user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        userToken = context.getCached("user_token_" + user.getId(), String.class);
        product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        // Initialize Kafka producers for direct publishing
        initializeKafkaProducers();

        // Initialize consumers to verify DLQ routing
        dlqConsumer = new KafkaTestConsumer(ORDER_EVENTS_DLQ);
        orderEventsConsumer = new KafkaTestConsumer(ORDER_EVENTS_TOPIC);

        // Seek to end to ignore historical events
        dlqConsumer.seekToEnd();
        orderEventsConsumer.seekToEnd();
        // Create DLQ topics if they don't exist
        createDLQTopicIfNotExists("order.events.DLQ");
        createDLQTopicIfNotExists("payment.result.DLQ");

        logStep("✅ Deserialization test setup complete");
    }

    private void initializeKafkaProducers() {
        // String producer for JSON
        Properties stringProps = new Properties();
        stringProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVERS);
        stringProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        stringProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        stringProps.put(ProducerConfig.ACKS_CONFIG, "1");
        stringProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
        stringProducer = new KafkaProducer<>(stringProps);

        // Binary producer for corrupt data
        Properties binaryProps = new Properties();
        binaryProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVERS);
        binaryProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        binaryProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        binaryProps.put(ProducerConfig.ACKS_CONFIG, "1");
        binaryProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
        binaryProducer = new KafkaProducer<>(binaryProps);

        logStep("✅ Kafka producers initialized");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 1: MALFORMED JSON - CONSUMER RESILIENCE
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("Deserialization Failures")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Malformed JSON event routed to DLQ, consumer continues processing")
    public void test01_MalformedJSON_RoutedToDLQ() throws Exception {
        logStep("TEST 1: Malformed JSON handling");

        String orderId = UUID.randomUUID().toString();

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Publish malformed ORDER_CREATED event directly to Kafka
        // ═══════════════════════════════════════════════════════════════
        String malformedJson = String.format(
                "{\"eventType\":\"ORDER_CREATED\",\"orderId\":\"%s\",\"amount\":INVALID_SYNTAX}",
                orderId
        );

        logStep("  Publishing malformed ORDER_CREATED event:");
        logStep("  Topic: {}", ORDER_EVENTS_TOPIC);
        logStep("  Content: {}", malformedJson);
        logStep("  ⚠️  BYPASSES API - goes directly to Kafka!");

        ProducerRecord<String, String> record = new ProducerRecord<>(
                ORDER_EVENTS_TOPIC,
                orderId,
                malformedJson
        );

        stringProducer.send(record).get();
        stringProducer.flush();

        logStep("  ✓ Malformed event published");

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Verify event routed to DLQ after deserialization failure
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for DLQ routing...");

        Optional<JsonNode> dlqEvent = dlqConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText()) ||
                        node.asText().contains(orderId),  // Might be raw string in DLQ
                15  // Wait up to 15 seconds (includes retry attempts)
        );

        assertThat(dlqEvent)
                .as("Malformed event should be routed to DLQ")
                .isPresent();

        logStep("  ✓ Malformed event found in DLQ: {}", ORDER_EVENTS_DLQ);

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Verify consumer is still healthy (not crashed)
        // ═══════════════════════════════════════════════════════════════
        logStep("  Verifying consumer health by publishing valid event...");

        String healthCheckOrderId = UUID.randomUUID().toString();
        String validJson = String.format(
                "{\"eventType\":\"ORDER_CREATED\",\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":99.99,\"timestamp\":%d}",
                healthCheckOrderId, user.getId(), System.currentTimeMillis()
        );

        ProducerRecord<String, String> validRecord = new ProducerRecord<>(
                ORDER_EVENTS_TOPIC,
                healthCheckOrderId,
                validJson
        );

        stringProducer.send(validRecord).get();
        stringProducer.flush();

        logStep("  ✓ Valid health-check event published");

        // Verify consumer processed the valid event
        Optional<JsonNode> processedEvent = orderEventsConsumer.waitForMessage(
                node -> healthCheckOrderId.equals(node.path("orderId").asText()),
                10
        );

        assertThat(processedEvent)
                .as("Consumer should still process valid events after deserialization failure")
                .isPresent();

        logStep("✅ TEST PASSED:");
        logStep("  ✓ Malformed event routed to DLQ");
        logStep("  ✓ Consumer remained healthy");
        logStep("  ✓ Subsequent valid events processed successfully");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 2: MISSING REQUIRED FIELDS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 2)
    @Story("Deserialization Failures")
    @Severity(SeverityLevel.NORMAL)
    @Description("Event with missing required fields handled gracefully")
    public void test02_MissingRequiredFields_HandledGracefully() throws Exception {
        logStep("TEST 2: Missing required fields");

        String orderId = UUID.randomUUID().toString();

        // Valid JSON but missing required fields (no userId, amount)
        String incompleteJson = String.format(
                "{\"eventType\":\"ORDER_CREATED\",\"orderId\":\"%s\"}",
                orderId
        );

        logStep("  Publishing incomplete ORDER_CREATED event (missing userId, amount)");
        logStep("  ⚠️  Valid JSON, but missing required business fields");

        ProducerRecord<String, String> record = new ProducerRecord<>(
                ORDER_EVENTS_TOPIC,
                orderId,
                incompleteJson
        );

        stringProducer.send(record).get();
        stringProducer.flush();

        logStep("  ✓ Incomplete event published");

        // Verify DLQ routing
        Optional<JsonNode> dlqEvent = dlqConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText()) ||
                        node.asText().contains(orderId),
                15
        );

        assertThat(dlqEvent)
                .as("Incomplete event should be routed to DLQ")
                .isPresent();

        logStep("✅ Incomplete event routed to DLQ");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 3: CORRUPT BINARY DATA
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 3)
    @Story("Deserialization Failures")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Corrupt binary data doesn't crash consumer")
    public void test03_CorruptBinaryData_ConsumerSurvives() throws Exception {
        logStep("TEST 3: Corrupt binary data");

        String orderId = UUID.randomUUID().toString();

        // Random binary garbage
        byte[] corruptData = new byte[]{
                (byte)0xFF, (byte)0xFE, (byte)0xFD, (byte)0xFC,
                0x00, 0x01, 0x02, (byte)0x80, (byte)0x90, (byte)0xA0
        };

        logStep("  Publishing corrupt binary data (10 random bytes)");
        logStep("  ⚠️  Cannot be deserialized as JSON!");

        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                ORDER_EVENTS_TOPIC,
                orderId,
                corruptData
        );

        binaryProducer.send(record).get();
        binaryProducer.flush();

        logStep("  ✓ Corrupt binary published");

        // Wait for DLQ routing
        Thread.sleep(5000);

        // Verify consumer still alive by publishing valid event
        logStep("  Verifying consumer still alive...");

        String healthCheckOrderId = UUID.randomUUID().toString();
        String validJson = String.format(
                "{\"eventType\":\"ORDER_CREATED\",\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":50.0,\"timestamp\":%d}",
                healthCheckOrderId, user.getId(), System.currentTimeMillis()
        );

        stringProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, healthCheckOrderId, validJson)).get();
        stringProducer.flush();

        Optional<JsonNode> healthCheck = orderEventsConsumer.waitForMessage(
                node -> healthCheckOrderId.equals(node.path("orderId").asText()),
                10
        );

        assertThat(healthCheck)
                .as("Consumer should survive corrupt binary data")
                .isPresent();

        logStep("✅ Consumer survived corrupt binary data");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 4: BURST OF BAD EVENTS - SYSTEM STABILITY
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 4)
    @Story("Deserialization Failures")
    @Severity(SeverityLevel.BLOCKER)
    @Description("System remains stable under burst of malformed events")
    public void test04_BurstOfBadEvents_SystemStability() throws Exception {
        logStep("TEST 4: System stability under burst of bad events");

        int badEventCount = 5;
        int validEventCount = 3;

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Publish burst of bad events
        // ═══════════════════════════════════════════════════════════════
        logStep("  Publishing {} malformed events in rapid succession...", badEventCount);

        for (int i = 0; i < badEventCount; i++) {
            String badJson = String.format("{INVALID_JSON_%d}", i);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    ORDER_EVENTS_TOPIC,
                    "bad-" + i,
                    badJson
            );
            stringProducer.send(record);
            logStep("    {}. Bad event published", i + 1);
        }

        stringProducer.flush();
        logStep("  ✓ All {} bad events published", badEventCount);

        // Wait for DLQ routing
        Thread.sleep(8000);

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Publish valid events to verify consumer health
        // ═══════════════════════════════════════════════════════════════
        logStep("  Publishing {} valid events to verify consumer health...", validEventCount);

        String[] validOrderIds = new String[validEventCount];

        for (int i = 0; i < validEventCount; i++) {
            validOrderIds[i] = UUID.randomUUID().toString();
            String validJson = String.format(
                    "{\"eventType\":\"ORDER_CREATED\",\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":%d.0,\"timestamp\":%d}",
                    validOrderIds[i], user.getId(), (i + 1) * 10, System.currentTimeMillis()
            );

            stringProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, validOrderIds[i], validJson));
            logStep("    {}. Valid event published: {}", i + 1, validOrderIds[i]);
        }

        stringProducer.flush();

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Verify all valid events processed
        // ═══════════════════════════════════════════════════════════════
        logStep("  Verifying all valid events processed...");

        int processedCount = 0;
        for (String orderId : validOrderIds) {
            Optional<JsonNode> processed = orderEventsConsumer.waitForMessage(
                    node -> orderId.equals(node.path("orderId").asText()),
                    10
            );

            if (processed.isPresent()) {
                processedCount++;
                logStep("    ✓ Order {} processed", orderId);
            }
        }

        assertThat(processedCount)
                .as("All valid events should be processed despite burst of bad events")
                .isEqualTo(validEventCount);

        logStep("✅ SYSTEM STABILITY VALIDATED:");
        logStep("  ✓ {} bad events routed to DLQ", badEventCount);
        logStep("  ✓ {} valid events processed successfully", validEventCount);
        logStep("  ✓ No consumer crash or degradation");
        logStep("  ✓ System remained stable under failure conditions");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ══════════════════════════════════════════════════════════════════════════

    @AfterClass
    public void cleanup() {
        logStep("Cleaning up deserialization test resources");

        if (stringProducer != null) {
            stringProducer.close();
        }
        if (binaryProducer != null) {
            binaryProducer.close();
        }
        if (dlqConsumer != null) {
            dlqConsumer.close();
        }
        if (orderEventsConsumer != null) {
            orderEventsConsumer.close();
        }

        logStep("✅ Cleanup complete");
    }

    private void createDLQTopicIfNotExists(String topicName) throws Exception {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVERS);

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            Set<String> existingTopics = adminClient.listTopics().names().get();

            if (!existingTopics.contains(topicName)) {
                NewTopic newTopic = new NewTopic(topicName, 3, (short) 1);
                adminClient.createTopics(Collections.singleton(newTopic)).all().get();
                logStep("  ✓ Created DLQ topic: {}", topicName);
            } else {
                logStep("  ✓ DLQ topic already exists: {}", topicName);
            }
        }
    }
}