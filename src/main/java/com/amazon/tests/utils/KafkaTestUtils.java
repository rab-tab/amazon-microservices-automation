package com.amazon.tests.utils;

// src/main/java/com/amazon/tests/utils/KafkaTestUtils.java


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka utilities for testing payment flows.
 *
 * This class provides methods to publish mock Kafka messages for testing purposes.
 * It does NOT simulate Payment Service business logic - it only publishes test messages
 * to Kafka topics to verify how the Order Service handles payment results.
 */
@Slf4j
public class KafkaTestUtils {

    private static final String PAYMENT_RESULT_TOPIC = "payment.result";

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper;

    public KafkaTestUtils(KafkaProducer<String, String> producer) {
        this.producer = producer;
        this.objectMapper = new ObjectMapper();
        log.info("✅ KafkaTestUtils initialized");
    }

    /**
     * Publish a successful payment result to payment.result topic
     *
     * @param orderId The order ID
     * @param amount The payment amount
     */
    public void publishSuccessfulPayment(String orderId, double amount) {
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("paymentId", UUID.randomUUID().toString());
        result.put("status", "SUCCESS");
        result.put("amount", amount);
        result.put("transactionId", "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        publishToKafka(orderId, result);
        log.info("✅ Published SUCCESS payment for order: {}", orderId);
    }

    /**
     * Publish a failed payment result (insufficient funds)
     *
     * @param orderId The order ID
     * @param amount The payment amount
     */
    public void publishFailedPayment(String orderId, double amount) {
        publishFailedPayment(orderId, amount, "Insufficient funds");
    }

    /**
     * Publish a failed payment result with custom reason
     *
     * @param orderId The order ID
     * @param amount The payment amount
     * @param reason The failure reason
     */
    public void publishFailedPayment(String orderId, double amount, String reason) {
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("paymentId", UUID.randomUUID().toString());
        result.put("status", "FAILED");
        result.put("amount", amount);
        result.put("transactionId", "");
        result.put("failureReason", reason);

        publishToKafka(orderId, result);
        log.info("❌ Published FAILED payment for order: {} - Reason: {}", orderId, reason);
    }

    /**
     * Publish fraud detection result
     *
     * @param orderId The order ID
     * @param amount The payment amount
     */
    public void publishFraudDetection(String orderId, double amount) {
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("paymentId", UUID.randomUUID().toString());
        result.put("status", "FAILED");
        result.put("amount", amount);
        result.put("transactionId", "");
        result.put("failureReason", "Fraud detected");
        result.put("fraudScore", 95);

        publishToKafka(orderId, result);
        log.info("🚨 Published FRAUD detection for order: {}", orderId);
    }

    /**
     * Publish card expired error
     *
     * @param orderId The order ID
     * @param amount The payment amount
     */
    public void publishCardExpired(String orderId, double amount) {
        publishFailedPayment(orderId, amount, "Card expired");
    }

    /**
     * Publish network error
     *
     * @param orderId The order ID
     * @param amount The payment amount
     */
    public void publishNetworkError(String orderId, double amount) {
        publishFailedPayment(orderId, amount, "Network error - please retry");
    }

    /**
     * Don't publish anything - simulates timeout scenario
     *
     * @param orderId The order ID
     */
    public void simulateTimeout(String orderId) {
        log.warn("⏱️ Simulating TIMEOUT - no payment result published for order: {}", orderId);
        // Intentionally do nothing - no message published
    }

    /**
     * Internal method to publish message to Kafka
     */
    private void publishToKafka(String orderId, Map<String, Object> message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    PAYMENT_RESULT_TOPIC,
                    orderId,
                    json
            );

            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("❌ Failed to publish to Kafka", exception);
                } else {
                    log.debug("📤 Published to {}: partition={}, offset={}",
                            metadata.topic(), metadata.partition(), metadata.offset());
                }
            }).get(); // Wait for confirmation

        } catch (Exception e) {
            log.error("❌ Error publishing to Kafka", e);
            throw new RuntimeException("Failed to publish to Kafka", e);
        }
    }

    /**
     * Close the Kafka producer
     */
    public void close() {
        if (producer != null) {
            producer.close();
            log.info("🛑 Kafka producer closed");
        }
    }
}
