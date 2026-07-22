package com.amazon.tests.utils.apiClients;

import com.amazon.tests.config.ConfigManager;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.RequestExecutor;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.util.Optional;

import static io.restassured.RestAssured.given;

public class PaymentApiClient {
    private final KafkaTestConsumer consumer;
    private final RequestExecutor executor;

    public PaymentApiClient(KafkaTestConsumer consumer, RequestExecutor executor) {
        this.consumer = consumer;
        this.executor = executor;
    }

    /**
     * Waits until a FAILED payment event is published for the given order.
     */

    public TestModels.PaymentResponse getPayment(String orderId) {

        Response response = given()
                .spec(new RequestSpecBuilder()
                        .setBaseUri(ConfigManager.getInstance().getPaymentServiceUrl())
                        .setContentType(ContentType.JSON)
                        .build())
                .pathParam("orderId", orderId)
                .when()
                .get("/api/v1/payments/order/{orderId}")
                .then()
                .statusCode(200)
                .extract()
                .response();

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
