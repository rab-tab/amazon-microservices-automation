package com.amazon.tests.utils.apiClients;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.*;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;

public class PaymentApiClient extends ApiClient{
    private final KafkaTestConsumer consumer;

    public PaymentApiClient(KafkaTestConsumer consumer, RequestExecutor executor) {
        super(executor);
        this.consumer = consumer;

    }

    /**
     * Waits until a FAILED payment event is published for the given order.
     */

    public TestModels.PaymentResponse getPayment(String orderId) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.GET)
                .endpoint("/api/v1/payments/order/{orderId}")
                .attribute(RequestAttributes.PATH_PARAMS, Map.of("orderId", orderId))
                .targetService(ServiceType.PAYMENT)
                .build();

        ServiceResponse response = executor.execute(request);
        if (!response.isSuccess()) {
            throw new IllegalStateException(
                    "Failed to fetch payment for order " + orderId
                            + ", status=" + response.getStatusCode()
                            + ", body=" + response.getBody());
        }
        return response.as(TestModels.PaymentResponse.class);
    }


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
