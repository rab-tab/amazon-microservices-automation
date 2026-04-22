// src/main/java/com/amazon/tests/utils/PaymentResultListener.java
package com.amazon.tests.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class PaymentResultListener implements Runnable {

    private static final String PAYMENT_RESULT_TOPIC = "payment.result";
    private static final long POLL_TIMEOUT_MS = 500; // Reduced from 1000

    private final KafkaConsumer<String, String> consumer;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running;
    private final AtomicBoolean started;

    @Getter
    private final Map<String, Map<String, Object>> paymentResults;

    public PaymentResultListener(KafkaConsumer<String, String> consumer) {
        this.consumer = consumer;
        this.objectMapper = new ObjectMapper();
        this.running = new AtomicBoolean(false);
        this.started = new AtomicBoolean(false);
        this.paymentResults = new ConcurrentHashMap<>();

        consumer.subscribe(Collections.singletonList(PAYMENT_RESULT_TOPIC));
        log.info("✅ Payment Result Listener initialized for topic: {}", PAYMENT_RESULT_TOPIC);
    }

    @Override
    public void run() {
        running.set(true);
        started.set(true);

        log.info("👂 Payment Result Listener started");

        try {
            while (running.get()) {
                try {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(POLL_TIMEOUT_MS));

                    for (ConsumerRecord<String, String> record : records) {
                        handlePaymentResult(record.value());
                    }

                    // Check running flag more frequently
                    if (!running.get()) {
                        log.info("Stop signal received, breaking loop");
                        break;
                    }

                } catch (WakeupException e) {
                    log.info("Consumer wakeup called - stopping");
                    break;
                } catch (Exception e) {
                    if (running.get()) {
                        log.error("Error in listener loop", e);
                    }
                    break; // Exit on any error
                }
            }
        } finally {
            cleanup();
        }
    }

    private void handlePaymentResult(String message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(message, Map.class);

            String orderId = (String) result.get("orderId");
            String status = (String) result.get("status");

            log.info("📬 Payment result received - Order: {}, Status: {}", orderId, status);
            paymentResults.put(orderId, result);

        } catch (Exception e) {
            log.error("❌ Error handling payment result: {}", message, e);
        }
    }

    private void cleanup() {
        try {
            log.info("Closing Kafka consumer...");
            consumer.close(Duration.ofSeconds(2));
            log.info("✅ Kafka consumer closed");
        } catch (Exception e) {
            log.warn("⚠️ Error closing consumer", e);
        } finally {
            running.set(false);
            log.info("⛔ Payment Result Listener stopped");
        }
    }

    public Map<String, Object> waitForPaymentResult(String orderId, int timeoutSeconds) {
        log.info("⏳ Waiting for payment result for order: {} (timeout: {}s)", orderId, timeoutSeconds);

        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (paymentResults.containsKey(orderId)) {
                log.info("✅ Payment result found for order: {}", orderId);
                return paymentResults.get(orderId);
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("⚠️ Wait interrupted for order: {}", orderId);
                break;
            }
        }

        log.warn("⏱️ Timeout waiting for payment result for order: {}", orderId);
        return null;
    }

    public boolean hasPaymentResult(String orderId) {
        return paymentResults.containsKey(orderId);
    }

    public Map<String, Object> getPaymentResult(String orderId) {
        return paymentResults.get(orderId);
    }

    public void clearResults() {
        paymentResults.clear();
        log.info("🧹 Payment results cleared");
    }

    public void stop() {
        log.info("🛑 Stopping Payment Result Listener...");
        running.set(false);

        try {
            consumer.wakeup();
        } catch (Exception e) {
            log.warn("⚠️ Error waking up consumer", e);
        }
    }

    public boolean isRunning() {
        return started.get() && running.get();
    }
}