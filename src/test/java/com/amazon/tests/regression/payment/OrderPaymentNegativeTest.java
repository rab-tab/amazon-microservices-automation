package com.amazon.tests.regression.payment;


import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.apiClients.PaymentApiClient;
import com.amazon.tests.utils.concurrency.OrderStatusPoller;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Amazon Microservices")
@Feature("Order Payment Flow - Negative")
@Slf4j
public class OrderPaymentNegativeTest extends BaseTest {
    private PaymentApiClient paymentApiClient;

    private OrderApiClient orderApiClient(String token) {
        return new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());
    }

    // ══════════════════════════════════════════════════════════════
    // Data-driven: failure-reason scenarios (SUCCESS scenario excluded — that's positive)
    // ══════════════════════════════════════════════════════════════

    @Getter
    @Builder
    static class PaymentFailureScenario {
        String description;
        String testScenarioHeader;
        double amount;
        String expectedFailureReason;
        boolean containsMatch;      // true => "contains", false => exact equals
        boolean expectedRetryable;
        Integer expectedFraudScore; // null if not applicable

        @Override
        public String toString() {
            return description;
        }
    }

    @DataProvider(name = "paymentFailureScenarios")
    public Object[][] paymentFailureScenarios() {
        return new Object[][] {
                {
                        PaymentFailureScenario.builder()
                                .description("Insufficient funds")
                                .testScenarioHeader("FAILED")
                                .amount(150000.00)
                                .expectedFailureReason("Insufficient funds")
                                .containsMatch(false)
                                .expectedRetryable(true)
                                .build()
                },
                {
                        PaymentFailureScenario.builder()
                                .description("Fraud detected")
                                .testScenarioHeader("FRAUD")
                                .amount(5000.00)
                                .expectedFailureReason("Fraud detected")
                                .containsMatch(false)
                                .expectedRetryable(false)
                                .expectedFraudScore(95)
                                .build()
                },
                {
                        PaymentFailureScenario.builder()
                                .description("Card expired")
                                .testScenarioHeader("CARD_EXPIRED")
                                .amount(3500.00)
                                .expectedFailureReason("Card expired")
                                .containsMatch(false)
                                .expectedRetryable(false)
                                .build()
                },
                {
                        PaymentFailureScenario.builder()
                                .description("Network error")
                                .testScenarioHeader("NETWORK_ERROR")
                                .amount(2000.00)
                                .expectedFailureReason("Network error")
                                .containsMatch(true)
                                .expectedRetryable(true)
                                .build()
                }
        };
    }

    @Test(dataProvider = "paymentFailureScenarios", timeOut = 30000)
    @Story("Payment Failure Handling")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify payment failure reasons are surfaced correctly with proper retryable flag")
    public void testPaymentFailureScenario(PaymentFailureScenario scenario) {
        log.info("=== Payment Failure: {} ===", scenario.getDescription());

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProductWithStock(scenario.getAmount(), 10)
                .createOrderWithScenario(scenario.getTestScenarioHeader())
                .execute();

        String token = purchase.getCustomerAuth().getAccessToken();
        String userId = purchase.getCustomerAuth().getUser().getId();
        String orderId = purchase.getOrder().getId();

        // Order-side: confirm terminal status via REST (this field IS real)
        TestModels.OrderResponse order = OrderStatusPoller.waitForStatus(
                orderApiClient(token), token, userId, orderId, "PAYMENT_FAILED", Duration.ofSeconds(15));
        assertThat(order.getPaymentId()).as("No payment ID for failed payment").isNull();

        // Payment-detail side: failure reason/retryable/fraud score come from the Kafka event, not REST
        JsonNode failureEvent = paymentApiClient.waitForPaymentFailed(orderId, 15)
                .orElseThrow(() -> new AssertionError("No FAILED payment event received for order " + orderId));

        String actualReason = failureEvent.get("failureReason").asText();
        if (scenario.isContainsMatch()) {
            assertThat(actualReason).contains(scenario.getExpectedFailureReason());
        } else {
            assertThat(actualReason).isEqualTo(scenario.getExpectedFailureReason());
        }

        assertThat(failureEvent.get("retryable").asBoolean())
                .as("Retryable flag should match expectation")
                .isEqualTo(scenario.isExpectedRetryable());

        if (scenario.getExpectedFraudScore() != null) {
            assertThat(failureEvent.get("fraudScore").asInt())
                    .as("Fraud score should match")
                    .isEqualTo(scenario.getExpectedFraudScore());
        }

        log.info("✅ PASSED: {} — reason={}, retryable={}",
                scenario.getDescription(), actualReason, failureEvent.get("retryable").asBoolean());
    }

    // ══════════════════════════════════════════════════════════════
    // Timeout — distinct shape (stays PENDING, no poll-to-terminal-state)
    // ══════════════════════════════════════════════════════════════

    @Test(timeOut = 30000)
    @Story("Payment Timeout")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify order remains PENDING when payment times out")
    public void testPaymentTimeoutFlow() throws InterruptedException {
        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProductWithStock(2500.00, 10)
                .createOrderWithScenario("TIMEOUT")
                .execute();

        String token = purchase.getCustomerAuth().getAccessToken();
        String userId = purchase.getCustomerAuth().getUser().getId();
        String orderId = purchase.getOrder().getId();

        log.info("⏳ Waiting 8 seconds for timeout scenario...");
        Thread.sleep(8000);

        TestModels.OrderResponse order = orderApiClient(token).getOrder(token, userId, orderId);

        assertThat(order.getStatus()).as("Order should remain PENDING on timeout").isEqualTo("PENDING");
        assertThat(order.getPaymentId()).as("No payment ID on timeout").isNull();
        assertThat(order.getPaymentFailureReason()).as("No failure reason on timeout").isNull();

        log.info("✅ PASSED: Order remained PENDING on timeout — {}", orderId);
    }

    // ══════════════════════════════════════════════════════════════
    // Composite scenario — validates differentiation across concurrent outcomes.
    // Kept as one integration-style test; doesn't fit the DataProvider shape above
    // since it asserts on 4 orders together, not one row at a time.
    // ══════════════════════════════════════════════════════════════

    @Test(timeOut = 60000)
    @Story("Multiple Orders with Different Outcomes")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify system correctly differentiates outcomes across concurrently created orders")
    public void testMultipleOrdersWithDifferentOutcomes() {
        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProductWithStock(1.0, 100) // shared product for all 4 orders below
                .execute();

        String token = purchase.getCustomerAuth().getAccessToken();
        String userId = purchase.getCustomerAuth().getUser().getId();
        OrderApiClient client = orderApiClient(token);

        TestModels.OrderResponse o1 = client.createOrderWithTestScenario(
                userId, UUID.randomUUID().toString(), purchase.getProducts(), "SUCCESS");
        TestModels.OrderResponse o2 = client.createOrderWithTestScenario(
                userId, UUID.randomUUID().toString(), purchase.getProducts(), "FAILED");
        TestModels.OrderResponse o3 = client.createOrderWithTestScenario(
                userId, UUID.randomUUID().toString(), purchase.getProducts(), "FRAUD");
        TestModels.OrderResponse o4 = client.createOrderWithTestScenario(
                userId, UUID.randomUUID().toString(), purchase.getProducts(), "SUCCESS");

        TestModels.OrderResponse r1 = OrderStatusPoller.waitForStatus(client, token, userId, o1.getId(), "CONFIRMED", Duration.ofSeconds(20));
        TestModels.OrderResponse r2 = OrderStatusPoller.waitForStatus(client, token, userId, o2.getId(), "PAYMENT_FAILED", Duration.ofSeconds(20));
        TestModels.OrderResponse r3 = OrderStatusPoller.waitForStatus(client, token, userId, o3.getId(), "PAYMENT_FAILED", Duration.ofSeconds(20));
        TestModels.OrderResponse r4 = OrderStatusPoller.waitForStatus(client, token, userId, o4.getId(), "CONFIRMED", Duration.ofSeconds(20));

        assertThat(r1.getStatus()).isEqualTo("CONFIRMED");
        assertThat(r2.getStatus()).isEqualTo("PAYMENT_FAILED");
        assertThat(r2.getPaymentFailureReason()).isEqualTo("Insufficient funds");
        assertThat(r3.getStatus()).isEqualTo("PAYMENT_FAILED");
        assertThat(r3.getPaymentFailureReason()).isEqualTo("Fraud detected");
        assertThat(r4.getStatus()).isEqualTo("CONFIRMED");

        log.info("✅ PASSED: outcomes differentiated correctly across 4 orders");
    }
}
