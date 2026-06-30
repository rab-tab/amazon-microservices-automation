package com.amazon.tests.config;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.kafka.saga.PaymentFailureScenariosTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.TestMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.RestAssured;
import io.restassured.response.Response;

import java.time.Duration;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class AbstractSagaTest extends BaseTest {
    protected TestEnvironment env;

    protected TestMetrics metrics;

    protected String idempotencyKey;
    protected OrderApiClient orderApiClient=new OrderApiClient();
    private String orderId;
    private RetryExecutor retryExecutor=new RetryExecutor();
    private PaymentFailureScenariosTest.PaymentFailureScenario scenario;


    public void createOrder() throws Exception {
        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(env.getProduct(), 2)
                .build();



        Response createResponse = retryExecutor.executeWithRetry(() -> {
            try {
                return orderApiClient.sendOrderRequestWithFault(
                        env.getUserToken(),
                        idempotencyKey,
                        orderRequest,
                        scenario.getFaultHeader(),
                        env.getUser().getId().toString(),
                        context.getConfig().baseUrl()
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(createResponse.statusCode())
                .as("Order creation should succeed")
                .isEqualTo(201);

        orderId = createResponse.jsonPath().getString("id");
        String initialStatus = createResponse.jsonPath().getString("status");

       logStep("  ✓ Order created: {}", orderId);
       logStep("  ✓ Initial status: {}", initialStatus);

        assertThat(initialStatus)
                .as("Order should start in PENDING status")
                .isEqualTo("PENDING");
    }
    public Response sendOrderRequestWithFault(
            String userToken,
            String idempotencyKey,
            TestModels.CreateOrderRequest orderRequest,
            String faultType,String userId,String baseUri) throws Exception {

        String requestBody = objectMapper.writeValueAsString(orderRequest);

        return RestAssured
                .given()
                .baseUri(baseUri)
                .header("Authorization", "Bearer " + userToken)
                .header("X-User-Id", userId)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Fault", faultType)
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/orders");
    }


    public void waitForOrderStatus (){
        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Verify Order Compensation to PAYMENT_FAILED
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for order compensation...");

        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> {
                    Response response = given()
                            .header("X-User-Id", env.getUser().getId())
                            .header("Authorization", "Bearer " + env.getUserToken())
                            .when()
                            .get(context.getConfig().baseUrl() + "/api/orders/" + orderId);

                    return "PAYMENT_FAILED".equals(response.jsonPath().getString("status"));
                });

    }

    public void getOrder(){
        // ═══════════════════════════════════════════════════════════════
        // STEP 4: Verify Final Order State
        // ═══════════════════════════════════════════════════════════════
        Response finalOrder = given()
                .header("X-User-Id", env.getUser().getId())
                .header("Authorization", "Bearer " + env.getUserToken())
                .when()
                .get(context.getConfig().baseUrl() + "/api/orders/" + orderId);

        String finalStatus = finalOrder.jsonPath().getString("status");
        String storedFailureReason = finalOrder.jsonPath().getString("paymentFailureReason");
        Boolean retryable = finalOrder.jsonPath().getBoolean("paymentRetryable");
        String paymentId = finalOrder.jsonPath().getString("paymentId");

        logStep("  ✓ Final order status: {}", finalStatus);
        logStep("  ✓ Failure reason: {}", storedFailureReason);
        logStep("  ✓ Retryable: {}", retryable);

        // Assert final state
        assertThat(finalStatus)
                .as("Order should be compensated to PAYMENT_FAILED")
                .isEqualTo("PAYMENT_FAILED");

        assertThat(storedFailureReason)
                .as("Failure reason should be stored in order")
                .contains(scenario.getExpectedFailureReason());

        assertThat(retryable)
                .as("Retryable flag should match scenario expectation")
                .isEqualTo(scenario.isExpectedRetryable());

        assertThat(paymentId)
                .as("No payment ID should be assigned on failure")
                .isNullOrEmpty();

        // Verify fraud score stored in order (if applicable)
        if (scenario.isExpectFraudScore()) {
            Integer storedFraudScore = finalOrder.jsonPath().getInt("paymentFraudScore");
            assertThat(storedFraudScore)
                    .as("Fraud score should be stored in order")
                    .isGreaterThan(90);
            logStep("  ✓ Fraud score stored: {}", storedFraudScore);
        }
    }

    public void waitForPaymentEvent()
    {
        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Verify Payment Failure Event Published
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for payment failure event...");

        Optional<JsonNode> paymentResult = env.getConsumers().get("payment.result").waitForMessage(
                node -> node.has("orderId") &&
                        orderId.equals(node.get("orderId").asText()) &&
                        "FAILED".equals(node.get("status").asText()),
                30
        );

        assertThat(paymentResult)
                .as("Payment failure result should be published to payment.result topic")
                .isPresent();

        JsonNode paymentEvent = paymentResult.get();

        // Verify failure reason
        String actualFailureReason = paymentEvent.get("failureReason").asText();
        logStep("  ✓ Payment failure detected: {}", actualFailureReason);

        assertThat(actualFailureReason)
                .as("Failure reason should match expected")
                .contains(scenario.getExpectedFailureReason());

        // Verify fraud score if expected
        if (scenario.isExpectFraudScore()) {
            assertThat(paymentEvent.has("fraudScore"))
                    .as("Fraud score should be present for fraud detection")
                    .isTrue();

            int fraudScore = paymentEvent.get("fraudScore").asInt();
            logStep("  ✓ Fraud score: {}", fraudScore);

            assertThat(fraudScore)
                    .as("Fraud score should be high")
                    .isGreaterThan(90);
        }
    }

    protected final void executeSaga(
            PaymentFailureScenariosTest.PaymentFailureScenario testScenario)
            throws Exception {
        scenario=testScenario;
        createOrder();

        waitForPaymentEvent();

        waitForOrderStatus();

        getOrder();

        /*printSummary(
                saga,
                scenario);*/
    }
}
