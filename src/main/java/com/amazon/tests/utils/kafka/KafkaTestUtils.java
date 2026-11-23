
package com.amazon.tests.utils.kafka;

import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.models.TestModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.rekawek.toxiproxy.Proxy;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka utilities for integration and regression testing.
 *
 * Supports:
 * - Publishing mock payment results.
 * - Creating orders for Kafka-related tests.
 * - Validating order API responses.
 * - Waiting for order events.
 * - Restoring Toxiproxy after fault injection.
 */
@Slf4j
public class KafkaTestUtils {

    private static final String PAYMENT_RESULT_TOPIC = "payment.result";

    private static final String ORDER_EVENTS_TOPIC = "order.events";

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper;

    public KafkaTestUtils(KafkaProducer<String, String> producer) {
        this.producer = producer;
        this.objectMapper = new ObjectMapper();
        log.info("KafkaTestUtils initialized");
    }

    // =========================================================
    // PAYMENT RESULT PUBLISHING
    // =========================================================

    public void publishSuccessfulPayment(String orderId, double amount) {
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("paymentId", UUID.randomUUID().toString());
        result.put("status", "SUCCESS");
        result.put("amount", amount);
        result.put(
                "transactionId",
                "TXN-" + UUID.randomUUID().toString()
                        .substring(0, 8).toUpperCase()
        );

        publishToKafka(orderId, result);
        log.info("Published SUCCESS payment for order: {}", orderId);
    }

    public void publishFailedPayment(String orderId, double amount) {
        publishFailedPayment(orderId, amount, "Insufficient funds");
    }

    public void publishFailedPayment(
            String orderId,
            double amount,
            String reason
    ) {
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("paymentId", UUID.randomUUID().toString());
        result.put("status", "FAILED");
        result.put("amount", amount);
        result.put("transactionId", "");
        result.put("failureReason", reason);

        publishToKafka(orderId, result);
        log.info("Published FAILED payment for order: {} - Reason: {}",
                orderId, reason);
    }

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
        log.info("Published FRAUD detection for order: {}", orderId);
    }

    public void publishCardExpired(String orderId, double amount) {
        publishFailedPayment(orderId, amount, "Card expired");
    }

    public void publishNetworkError(String orderId, double amount) {
        publishFailedPayment(
                orderId,
                amount,
                "Network error - please retry"
        );
    }

    public void simulateTimeout(String orderId) {
        log.warn(
                "Simulating TIMEOUT - no payment result published for order: {}",
                orderId
        );
    }

    private void publishToKafka(
            String orderId,
            Map<String, Object> message
    ) {
        try {
            String json = objectMapper.writeValueAsString(message);

            ProducerRecord<String, String> record =
                    new ProducerRecord<>(
                            PAYMENT_RESULT_TOPIC,
                            orderId,
                            json
                    );

            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish to Kafka", exception);
                } else {
                    log.debug(
                            "Published to {}: partition={}, offset={}",
                            metadata.topic(),
                            metadata.partition(),
                            metadata.offset()
                    );
                }
            }).get();

        } catch (Exception e) {
            log.error("Error publishing to Kafka", e);
            throw new RuntimeException("Failed to publish to Kafka", e);
        }
    }

    // =========================================================
    // ORDER API TEST HELPERS
    // =========================================================

    public static Response createOrder(
            SeedingContext context,
            ObjectMapper objectMapper,
            TestModels.UserResponse user,
            TestModels.ProductResponse product,
            String userToken
    ) throws Exception {

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest =
                OrderBuilder.anOrder()
                        .withNamespace(context.getNamespace())
                        .addItem(product, 1)
                        .build();

        String requestBody =
                objectMapper.writeValueAsString(orderRequest);

        return RestAssured
                .given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-User-Id", user.getId().toString())
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/orders");
    }

    public static void assertOrderAccepted(Response response) {

        log.info(
                "Order API response: status={}, body={}",
                response.statusCode(),
                response.asString()
        );

        assertThat(response.statusCode())
                .as("Order creation should be accepted independently "
                        + "of asynchronous Kafka publishing")
                .isEqualTo(201);

        assertThat(response.jsonPath().getString("id"))
                .as("Created order should contain an ID")
                .isNotBlank();
    }

    // =========================================================
    // KAFKA EVENT HELPERS
    // =========================================================

    public static Optional<JsonNode> waitForOrderEvent(
            KafkaTestConsumer kafkaConsumer,
            String orderId,
            int timeoutSeconds
    ) {
        return kafkaConsumer.waitForMessage(
                node -> ORDER_EVENTS_TOPIC.equals(ORDER_EVENTS_TOPIC)
                        && node.has("eventType")
                        && "ORDER_CREATED".equals(
                        node.path("eventType").asText()
                )
                        && orderId.equals(
                        node.path("orderId").asText()
                ),
                timeoutSeconds
        );
    }

    // =========================================================
    // TOXIPROXY HELPERS
    // =========================================================

    public static void restoreProxy(Proxy kafkaProxy)
            throws IOException {

        if (kafkaProxy == null) {
            return;
        }

        kafkaProxy.enable();

        for (var toxic : kafkaProxy.toxics().getAll()) {
            try {
                toxic.remove();
            } catch (IOException exception) {
                throw new IOException(
                        "Failed to remove Toxiproxy toxic: "
                                + toxic.getName(),
                        exception
                );
            }
        }

        log.info("Kafka proxy restored and all toxics removed");
    }

    // =========================================================
    // RESOURCE CLEANUP
    // =========================================================

    public void close() {
        if (producer != null) {
            producer.close();
            log.info("Kafka producer closed");
        }
    }
}
