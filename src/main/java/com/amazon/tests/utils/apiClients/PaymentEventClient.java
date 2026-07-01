package com.amazon.tests.utils.apiClients;

import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

public class PaymentEventClient {
    private final KafkaTestConsumer consumer;

    public PaymentEventClient(KafkaTestConsumer consumer) {
        this.consumer = consumer;
    }

    /**
     * Waits until a FAILED payment event is published for the given order.
     */
    public Optional<JsonNode> waitForPaymentFailed(
            String orderId,
            int timeoutSeconds) {

        return consumer.waitForMessage(
                node ->
                        node.has("orderId")
                                && orderId.equals(node.get("orderId").asText())
                                && "FAILED".equals(node.get("status").asText()),
                timeoutSeconds
        );
    }

    /**
     * Waits until a SUCCESS payment event is published.
     */
    public Optional<JsonNode> waitForPaymentSucceeded(
            String orderId,
            int timeoutSeconds) {

        return consumer.waitForMessage(
                node ->
                        node.has("orderId")
                                && orderId.equals(node.get("orderId").asText())
                                && "SUCCESS".equals(node.get("status").asText()),
                timeoutSeconds
        );
    }

    /**
     * Generic wait method.
     */
    public Optional<JsonNode> waitForStatus(
            String orderId,
            String status,
            int timeoutSeconds) {

        return consumer.waitForMessage(
                node ->
                        node.has("orderId")
                                && orderId.equals(node.get("orderId").asText())
                                && status.equals(node.get("status").asText()),
                timeoutSeconds
        );
    }
}
