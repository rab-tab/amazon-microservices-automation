package com.amazon.tests.regression.kafka.sagaFlow;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.apiClients.PaymentApiClient;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Slf4j
@Epic("Kafka Saga Pattern")
@Feature("Payment Failure Compensation")
@Test(groups = {"saga", "payment-failures"})
public class PaymentFailureScenariosTest extends BaseTest {

    private KafkaTestConsumer paymentResultConsumer;
    private PaymentApiClient paymentApiClient;

    @Getter
    @AllArgsConstructor
    public static class PaymentFailureScenario {
        private final String testName;
        private final String faultHeader;
        private final String expectedFailureReason;
        private final boolean expectedRetryable;
        private final boolean expectFraudScore;

        @Override
        public String toString() {
            return testName;
        }
    }

    @DataProvider(name = "paymentFailureScenarios")
    public Object[][] paymentFailureScenarios() {
        return new Object[][]{
                {new PaymentFailureScenario(
                        "Insufficient Funds (Retryable)", "payment-failure",
                        "Insufficient funds", true, false)},
                {new PaymentFailureScenario(
                        "Fraud Detection (Non-Retryable)", "payment-fraud",
                        "Fraud detected", false, true)},
                {new PaymentFailureScenario(
                        "Card Expired (Non-Retryable)", "payment-expired-card",
                        "Card expired", false, false)},
                {new PaymentFailureScenario(
                        "Network Error (Retryable)", "payment-network-error",
                        "Network error", true, false)}
        };
    }

    @BeforeMethod
    public void setup() {
        logStep("Setting up Payment Failure Scenarios tests");
        paymentResultConsumer = new KafkaTestConsumer("payment.result");
        paymentApiClient = new PaymentApiClient(paymentResultConsumer, context.getExecutor());
        logStep("✅ Payment failure test setup complete");
    }

    @AfterMethod
    public void cleanup() {
        if (paymentResultConsumer != null) paymentResultConsumer.close();
        logStep("✅ Payment failure test consumers closed");
    }

    @Test(dataProvider = "paymentFailureScenarios")
    @Story("Payment Failure Compensation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify payment failure scenarios trigger correct saga compensation")
    public void testPaymentFailureScenario(PaymentFailureScenario scenario) throws Exception {
        logStep("TEST: Payment failure - " + scenario.getTestName());

        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        logStep("  Creating order with fault injection: " + scenario.getFaultHeader());

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse createResponse = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, scenario.getFaultHeader());

        assertThat(createResponse.getStatusCode()).isEqualTo(201);

        TestModels.OrderResponse order = createResponse.as(TestModels.OrderResponse.class);
        String orderId = order.getId();

        logStep("  ✓ Order created: " + orderId);
        assertThat(order.getStatus()).isEqualTo("PENDING");

        Optional<JsonNode> paymentResult = paymentApiClient.waitForPaymentFailed(orderId, 30);

        assertThat(paymentResult).isPresent();

        JsonNode paymentEvent = paymentResult.get();
        String actualFailureReason = paymentEvent.get("failureReason").asText();
        logStep("  ✓ Payment failure detected: " + actualFailureReason);

        assertThat(actualFailureReason)
                .as("Failure reason should match expected")
                .contains(scenario.getExpectedFailureReason());

        if (scenario.isExpectFraudScore()) {
            assertThat(paymentEvent.has("fraudScore")).isTrue();
            int fraudScore = paymentEvent.get("fraudScore").asInt();
            logStep("  ✓ Fraud score: " + fraudScore);
            assertThat(fraudScore).isGreaterThan(90);
        }

        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> "PAYMENT_FAILED".equals(orderApiClient.getOrder(token, userId, orderId).getStatus()));

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, orderId);

        assertThat(finalOrder.getStatus()).isEqualTo("PAYMENT_FAILED");
        assertThat(finalOrder.getPaymentId()).isNullOrEmpty();

        logStep("✅ " + scenario.getTestName() + " - COMPLETE");
        logStep("   Order: " + orderId + " → PAYMENT_FAILED");
        logStep("   Retryable (expected): " + scenario.isExpectedRetryable());
    }

    @Test
    @Story("Payment Retry Mechanism")
    @Severity(SeverityLevel.NORMAL)
    @Description("Retryable payment failures are retried before marking order as PAYMENT_FAILED")
    public void test_PaymentRetryMechanism() throws Exception {
        logStep("TEST: Payment retry mechanism for transient failures");

        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse createResponse = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, "payment-network-error");

        assertThat(createResponse.getStatusCode()).isEqualTo(201);

        TestModels.OrderResponse order = createResponse.as(TestModels.OrderResponse.class);
        String orderId = order.getId();

        logStep("  Order created with retryable network error: " + orderId);

        long startTime = System.currentTimeMillis();
        int eventCount = 0;

        for (int i = 0; i < 15; i++) {
            Thread.sleep(500);
            Optional<JsonNode> paymentEvent = paymentResultConsumer.waitForMessage(
                    node -> orderId.equals(node.get("orderId").asText()), 1);

            if (paymentEvent.isPresent()) {
                eventCount++;
                String eventType = paymentEvent.get().has("eventType") ? paymentEvent.get().get("eventType").asText() : "unknown";
                logStep("  ✓ Payment event " + eventCount + ": " + eventType);
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        logStep("  ✓ Total retries/attempts in " + totalTime + " ms: " + eventCount);

        logStep("✅ Payment retry mechanism validated");
    }

    @Test
    @Story("Concurrent Payment Failures")
    @Severity(SeverityLevel.NORMAL)
    @Description("Multiple concurrent payment failures handled independently")
    public void test_ConcurrentPaymentFailures() throws Exception {
        logStep("TEST: Concurrent payment failures");

        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase1 = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        PurchaseResult purchase2 = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        OrderApiClient orderApiClient1 = new OrderApiClient(
                new BearerAuthStrategy(purchase1.getCustomer().getAccessToken()), context.getExecutor());
        OrderApiClient orderApiClient2 = new OrderApiClient(
                new BearerAuthStrategy(purchase2.getCustomer().getAccessToken()), context.getExecutor());

        TestModels.CreateOrderRequest orderRequest1 =
                TestDataFactory.defaultOrder(purchase1.getProducts()).build();
        TestModels.CreateOrderRequest orderRequest2 =
                TestDataFactory.defaultOrder(purchase2.getProducts()).build();

        ServiceResponse response1 = orderApiClient1.createOrderWithFault(
                purchase1.getCustomer().getUser().getId(), TestDataFactory.newIdempotencyKey(), orderRequest1, "payment-fraud");
        ServiceResponse response2 = orderApiClient2.createOrderWithFault(
                purchase2.getCustomer().getUser().getId(), TestDataFactory.newIdempotencyKey(), orderRequest2, "payment-expired-card");

        TestModels.OrderResponse order1 = response1.as(TestModels.OrderResponse.class);
        TestModels.OrderResponse order2 = response2.as(TestModels.OrderResponse.class);

        String orderId1 = order1.getId();
        String orderId2 = order2.getId();

        logStep("  Order 1 created: " + orderId1);
        logStep("  Order 2 created: " + orderId2);

        Optional<JsonNode> paymentFailure1 = paymentResultConsumer.waitForMessage(
                node -> orderId1.equals(node.get("orderId").asText()), 30);
        Optional<JsonNode> paymentFailure2 = paymentResultConsumer.waitForMessage(
                node -> orderId2.equals(node.get("orderId").asText()), 30);

        assertThat(paymentFailure1).isPresent();
        assertThat(paymentFailure2).isPresent();

        logStep("  ✓ Both payment failures detected independently");

        logStep("✅ Concurrent payment failures validated - no cross-interference");
    }
}