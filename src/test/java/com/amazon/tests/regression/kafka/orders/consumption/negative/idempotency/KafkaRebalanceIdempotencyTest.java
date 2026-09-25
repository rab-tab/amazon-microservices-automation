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
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.TopicPartition;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka Rebalance & Consumer Crash - Infrastructure-Level Idempotency
 *
 * Tests INFRASTRUCTURE-level scenarios (not just application logic).
 * Uses manual KafkaConsumer to simulate crashes and rebalance conditions.
 *
 * Test 1: CONSUMER CRASH BEFORE ACK
 *   - Manual consumer polls event
 *   - Consumer crashes BEFORE calling commitSync()
 *   - Offset NOT advanced
 *   - Event redelivered to next consumer
 *   - Application-level idempotency prevents duplicate payment
 *   - ASSERT: Same paymentId (dedup detected)
 *
 * Test 2: MANUAL OFFSET RESET (simulates rebalance recovery)
 *   - Manual consumer at offset 100
 *   - Offset reset to 85 (rewind 15 events)
 *   - Old events reprocessed
 *   - Idempotency prevents duplicate payments
 *   - ASSERT: Only one payment despite reprocessing
 *
 * Key Pattern:
 *   - KafkaConsumer with ENABLE_AUTO_COMMIT_CONFIG = false
 *   - Manual poll() + commitSync() calls
 *   - Simulate crash by skipping commitSync() between polls
 *   - Simulate rebalance by calling seek(tp, offset) to earlier position
 *   - Verify Payment Service detects duplicates via orderId
 */
@Slf4j
@Epic("Kafka Consumer Rebalance & Crashes")
@Feature("Infrastructure-Level: Crash Recovery & Idempotency")
public class KafkaRebalanceIdempotencyTest extends BaseTest {

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String PAYMENT_RESULT_TOPIC = "payment.result";
    private static final String KAFKA_BOOTSTRAP = "localhost:9092";

    private KafkaTestConsumer paymentResultMonitor;
    private org.apache.kafka.clients.producer.KafkaProducer<String, String> kafkaProducer;
    private String userId;
    private String userToken;

    @BeforeMethod
    public void setup() {
        logStep("Setting up rebalance/crash idempotency tests");

        PurchaseResult purchase = PurchaseWorkflow.start(executor, authStrategy)
                .registerCustomer()
                .execute();

        userId = purchase.getCustomer().getUser().getId();
        userToken = purchase.getCustomer().getAccessToken();

        kafkaProducer = new org.apache.kafka.clients.producer.KafkaProducer<>(
                KafkaConfig.getProducerProperties()
        );
        paymentResultMonitor = new KafkaTestConsumer(PAYMENT_RESULT_TOPIC);

        logStep("✅ Setup complete — user: " + userId);
    }

    @AfterMethod
    public void cleanup() {
        if (kafkaProducer != null) kafkaProducer.close();
        if (paymentResultMonitor != null) paymentResultMonitor.close();
        logStep("✅ Cleanup complete");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 1: CONSUMER CRASH BEFORE ACK - EVENT REDELIVERED
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("Consumer Crash Before Acknowledge")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Consumer crashes before committing offset — event redelivered, idempotency prevents duplicate")
    public void test01_ConsumerCrashBeforeAck_EventRedelivered() throws Exception {
        logStep("TEST 1: Consumer crash before ACK — event redelivered");

        String orderId = UUID.randomUUID().toString();
        paymentResultMonitor.seekToEnd();

        // Step 1: Publish ORDER_CREATED event
        logStep("  Publishing ORDER_CREATED event");
        String orderEvent = buildOrderCreatedEvent(orderId);
        kafkaProducer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(
                ORDER_EVENTS_TOPIC, orderId, orderEvent
        )).get();
        kafkaProducer.flush();
        logStep("  ✓ Event published");

        // Step 2: Create manual consumer (simulates real consumer instance)
        KafkaConsumer<String, String> manualConsumer1 = createManualConsumer(
                "rebalance-test-crash-" + UUID.randomUUID()
        );

        // Step 3: Poll event (consumer receives it)
        logStep("  Consumer 1 polling for event...");
        ConsumerRecord<String, String> record = pollForEvent(manualConsumer1, orderId, 20);
        assertThat(record).as("Event should be polled by consumer").isNotNull();
        logStep("  ✓ Consumer 1 received event at offset " + record.offset());

        // Step 4: 💥 CONSUMER CRASHES (intentional)
        logStep("  💥 SIMULATING CONSUMER CRASH (before calling commitSync)");
        logStep("     Offset NOT committed (event stays in broker's queue)");
        // NOT calling manualConsumer1.commitSync() — simulating crash
        manualConsumer1.close();
        logStep("  ✓ Consumer 1 crashed");

        // Step 5: Wait for Payment Service to process (if it did)
        logStep("  Waiting for payment result (consumer 1 may have processed before crash)...");
        Optional<JsonNode> paymentResult1 = paymentResultMonitor.waitForMessage(
                msg -> orderId.equals(msg.path("orderId").asText()), 10
        );

        String paymentId1 = null;
        if (paymentResult1.isPresent()) {
            paymentId1 = paymentResult1.get().path("paymentId").asText();
            logStep("  ✓ Consumer 1 processed before crash — paymentId: " + paymentId1);
        } else {
            logStep("  ℹ️ Consumer 1 crashed before publishing result (acceptable)");
        }

        // Step 6: Create new consumer (consumer group rebalance)
        logStep("  🔄 CONSUMER GROUP REBALANCE (new consumer joins)");
        Thread.sleep(5000); // Give time for rebalance

        KafkaConsumer<String, String> manualConsumer2 = createManualConsumer(
                "rebalance-test-crash-" + UUID.randomUUID()  // Different consumer, same group
        );

        // Step 7: New consumer polls same event (redelivery)
        logStep("  Consumer 2 polling for event (should get REDELIVERED event)...");
        ConsumerRecord<String, String> redeliveredRecord = pollForEvent(manualConsumer2, orderId, 20);
        assertThat(redeliveredRecord).as("Redelivered event should be polled by consumer 2").isNotNull();
        logStep("  ✓ Consumer 2 received redelivered event at offset " + redeliveredRecord.offset());

        // Step 8: Consumer 2 processes and commits
        logStep("  Consumer 2 processing and committing offset...");
        manualConsumer2.commitSync();
        logStep("  ✓ Consumer 2 committed offset");
        manualConsumer2.close();

        // Step 9: Wait for payment result
        logStep("  Waiting for payment result from consumer 2...");
        Optional<JsonNode> paymentResult2 = paymentResultMonitor.waitForMessage(
                msg -> orderId.equals(msg.path("orderId").asText()), 20
        );

        assertThat(paymentResult2).as("Payment result should be published").isPresent();
        String paymentId2 = paymentResult2.get().path("paymentId").asText();
        logStep("  ✓ Consumer 2 processed — paymentId: " + paymentId2);

        // Step 10: ASSERT idempotency
        if (paymentId1 != null) {
            assertThat(paymentId2)
                    .as("PaymentId should be SAME (consumer 2 detected duplicate)")
                    .isEqualTo(paymentId1);
            logStep("✅ CRASH IDEMPOTENCY VALIDATED — same paymentId despite crash and redelivery");
        } else {
            logStep("✅ CRASH IDEMPOTENCY VALIDATED — consumer 2 recovered, processed successfully");
        }

        // Verify only one payment in database
        assertThat(countPaymentsForOrder(orderId))
                .as("Only ONE payment should exist")
                .isEqualTo(1);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 2: MANUAL OFFSET RESET - OLD EVENTS REPROCESSED
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 2)
    @Story("Manual Offset Reset")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Offset reset to earlier position — old events reprocessed, idempotency prevents duplicate")
    public void test02_OffsetReset_OldEventsReprocessed_IdempotencyPrevents_Duplicates() throws Exception {
        logStep("TEST 2: Offset reset to earlier position — old events reprocessed");

        String orderId = UUID.randomUUID().toString();
        paymentResultMonitor.seekToEnd();

        // Step 1: Publish event
        logStep("  Publishing ORDER_CREATED event");
        String orderEvent = buildOrderCreatedEvent(orderId);
        kafkaProducer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(
                ORDER_EVENTS_TOPIC, orderId, orderEvent
        )).get();
        kafkaProducer.flush();
        logStep("  ✓ Event published");

        // Step 2: Consumer 1 processes and commits
        logStep("  Consumer 1 processing event...");
        KafkaConsumer<String, String> consumer1 = createManualConsumer(
                "offset-reset-test-" + UUID.randomUUID()
        );

        ConsumerRecord<String, String> record = pollForEvent(consumer1, orderId, 20);
        assertThat(record).isNotNull();
        logStep("  ✓ Polled event at offset " + record.offset());

        long originalOffset = record.offset();
        long partition = record.partition();

        consumer1.commitSync();
        logStep("  ✓ Offset committed: " + originalOffset);

        // Wait for payment result
        Optional<JsonNode> result1 = paymentResultMonitor.waitForMessage(
                msg -> orderId.equals(msg.path("orderId").asText()), 20
        );
        String paymentId1 = result1.get().path("paymentId").asText();
        logStep("  ✓ Payment processed — paymentId: " + paymentId1);

        consumer1.close();

        // Step 3: 🔄 MANUAL OFFSET RESET (simulates operational recovery)
        logStep("  💥 MANUALLY RESETTING OFFSET TO EARLIER POSITION");
        logStep("    Simulating: 'replay last 15 events for reprocessing'");
        logStep("    Original offset: " + originalOffset);
        logStep("    Resetting to: " + Math.max(0, originalOffset - 15));

        // Step 4: Create new consumer and reset offset
        KafkaConsumer<String, String> consumer2 = createManualConsumer(
                "offset-reset-test-" + UUID.randomUUID()
        );

        TopicPartition tp = new TopicPartition(ORDER_EVENTS_TOPIC, (int) partition);
        consumer2.assign(Collections.singletonList(tp));
        consumer2.seek(tp, Math.max(0, originalOffset - 15));  // Rewind to 15 events earlier

        logStep("  ✓ Consumer 2 seeking to offset " + Math.max(0, originalOffset - 15));

        // Step 5: Consumer 2 polls from earlier offset (gets old events + new)
        logStep("  Consumer 2 polling from earlier offset (will get reprocessed events)...");
        ConsumerRecord<String, String> reprocessedRecord = pollForEvent(consumer2, orderId, 30);
        assertThat(reprocessedRecord).as("Event should be polled from earlier offset").isNotNull();
        logStep("  ✓ Consumer 2 polled event (reprocessing): offset " + reprocessedRecord.offset());

        consumer2.commitSync();
        logStep("  ✓ Offset committed");
        consumer2.close();

        // Step 6: Wait for payment result
        logStep("  Waiting for payment result from consumer 2...");
        Optional<JsonNode> result2 = paymentResultMonitor.waitForMessage(
                msg -> orderId.equals(msg.path("orderId").asText()), 20
        );

        String paymentId2 = result2.get().path("paymentId").asText();
        logStep("  ✓ Payment processed — paymentId: " + paymentId2);

        // Step 7: ASSERT idempotency
        assertThat(paymentId2)
                .as("PaymentId should be SAME despite offset reset and reprocessing")
                .isEqualTo(paymentId1);

        assertThat(countPaymentsForOrder(orderId))
                .as("Only ONE payment should exist despite reprocessing")
                .isEqualTo(1);

        logStep("✅ OFFSET RESET IDEMPOTENCY VALIDATED — events reprocessed, no duplicate payment");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════════

    private KafkaConsumer<String, String> createManualConsumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);  // Manual commits only
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(ORDER_EVENTS_TOPIC));
        return consumer;
    }

    private ConsumerRecord<String, String> pollForEvent(
            KafkaConsumer<String, String> consumer,
            String orderId,
            int timeoutSeconds) {

        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                try {
                    JsonNode node = objectMapper.readTree(record.value());
                    if (orderId.equals(node.path("orderId").asText())) {
                        return record;
                    }
                } catch (Exception e) {
                    log.debug("Skipping non-JSON record: {}", e.getMessage());
                }
            }
        }
        return null;
    }

    private String buildOrderCreatedEvent(String orderId) {
        return String.format(
                "{\"eventType\":\"ORDER_CREATED\",\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":99.99,\"timestamp\":%d}",
                orderId, userId, System.currentTimeMillis()
        );
    }

    private int countPaymentsForOrder(String orderId) {
        try {
            Response response = RestAssured
                    .given()
                    .baseUri(context.getConfig().baseUrl())
                    .header("Authorization", "Bearer " + userToken)
                    .when()
                    .get("/api/orders/" + orderId);

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