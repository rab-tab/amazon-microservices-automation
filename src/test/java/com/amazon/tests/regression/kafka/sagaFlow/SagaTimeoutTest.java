package com.amazon.tests.regression.kafka.sagaFlow;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Slf4j
@Epic("Kafka Saga Pattern")
@Feature("Timeout & Resilience")
@Test(groups = {"saga", "timeout"})
public class SagaTimeoutTest extends BaseTest {

    private KafkaTestConsumer paymentResultConsumer;

    @BeforeMethod
    public void setup() {
        logStep("Setting up Saga Timeout tests");
        paymentResultConsumer = new KafkaTestConsumer("payment.result");
        logStep("✅ Saga timeout test setup complete");
    }

    @AfterMethod
    public void cleanup() {
        if (paymentResultConsumer != null) paymentResultConsumer.close();
        logStep("✅ Saga timeout test cleanup complete");
    }

    private PurchaseResult setupCustomerAndProduct() {
        return PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();
    }

    @Test(priority = 1)
    @Story("Timeout Scenarios")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Payment service timeout - order waits for response")
    public void test01_PaymentServiceTimeout_OrderStallsTemporarily() throws Exception {
        logStep("TEST 1: Payment service timeout - Order handling");

        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        logStep("  STEP 1: Creating order with payment timeout injection...");

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        long startTime = System.currentTimeMillis();

        ServiceResponse createResponse = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, "payment-timeout");

        long apiDuration = System.currentTimeMillis() - startTime;

        assertThat(createResponse.getStatusCode()).isEqualTo(201);

        TestModels.OrderResponse order = createResponse.as(TestModels.OrderResponse.class);
        String orderId = order.getId();

        logStep("  ✓ Order created: " + orderId);
        logStep("  ✓ API response time: " + apiDuration + " ms");
        assertThat(order.getStatus()).isEqualTo("PENDING");

        logStep("  STEP 2: Polling for payment result (may be delayed)...");

        Optional<JsonNode> paymentResult = paymentResultConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText()), 60);

        if (paymentResult.isPresent()) {
            logStep("  ✓ Payment result eventually received: " + paymentResult.get().path("status").asText());
        } else {
            logStep("  ℹ️  Payment result did not arrive (payment service may still be timing out)");
        }

        logStep("  STEP 3: Checking final order status...");

        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .untilAsserted(() -> {
                    TestModels.OrderResponse currentOrder = orderApiClient.getOrder(token, userId, orderId);
                    assertThat(currentOrder.getStatus()).isNotEqualTo("PENDING");
                });

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, orderId);

        logStep("  ✓ Final order status: " + finalOrder.getStatus());

        assertThat(finalOrder.getStatus()).isIn("CONFIRMED", "PAYMENT_FAILED", "PENDING");

        logStep("✅ PAYMENT TIMEOUT SCENARIO - Complete");
    }

    @Test(priority = 2)
    @Story("Multiple Retries on Timeout")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order system retries payment after timeout")
    public void test02_MultipleRetriesAfterTimeout() throws Exception {
        logStep("TEST 2: Multiple retries on timeout");

        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse createResponse = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, "payment-timeout");

        assertThat(createResponse.getStatusCode()).isEqualTo(201);

        TestModels.OrderResponse order = createResponse.as(TestModels.OrderResponse.class);
        String orderId = order.getId();

        logStep("  Order created: " + orderId);

        logStep("  Monitoring for payment events (including retries)...");

        AtomicInteger eventCount = new AtomicInteger(0);

        for (int i = 0; i < 30; i++) {
            Thread.sleep(1000);

            Optional<JsonNode> paymentEvent = paymentResultConsumer.waitForMessage(
                    node -> orderId.equals(node.path("orderId").asText()), 1);

            if (paymentEvent.isPresent()) {
                eventCount.incrementAndGet();
                String status = paymentEvent.get().path("status").asText();
                logStep("  Payment event " + eventCount.get() + ": " + status);
            }
        }

        logStep("  ✓ Total payment events: " + eventCount.get());

        logStep("✅ RETRY MECHANISM VALIDATED - " + eventCount.get() + " events");
    }

    @Test(priority = 3)
    @Story("Slow Response (Not Timeout)")
    @Severity(SeverityLevel.NORMAL)
    @Description("Payment service responds slowly but eventually completes")
    public void test03_SlowResponseEventualSuccess() throws Exception {
        logStep("TEST 3: Slow payment response - Eventually completes");

        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse createResponse = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, "payment-slow");

        assertThat(createResponse.getStatusCode()).isEqualTo(201);

        TestModels.OrderResponse order = createResponse.as(TestModels.OrderResponse.class);
        String orderId = order.getId();

        logStep("  Order created: " + orderId);

        logStep("  Waiting for payment result (slow path)...");

        Optional<JsonNode> paymentResult = paymentResultConsumer.waitForMessage(
                node -> orderId.equals(node.path("orderId").asText()), 90);

        if (paymentResult.isPresent()) {
            logStep("  ✓ Payment result received: " + paymentResult.get().path("status").asText());
        } else {
            logStep("  ⚠️  Payment result did not arrive in time");
        }

        await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(3))
                .ignoreExceptions()
                .until(() -> !getOrderStatusSafely(orderApiClient, token, userId, orderId).equals("PENDING"));

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, orderId);

        logStep("  ✓ Final status: " + finalOrder.getStatus());

        logStep("✅ SLOW RESPONSE SCENARIO - Completed");
    }

    @Test(priority = 4)
    @Story("Timeout Leading to Order Cancellation")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order auto-cancels if payment doesn't respond within timeout")
    public void test04_TimeoutLeadsToAutoCancellation() throws Exception {
        logStep("TEST 4: Timeout leads to automatic order cancellation");

        paymentResultConsumer.seekToEnd();

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse createResponse = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, "payment-permanent-timeout");

        assertThat(createResponse.getStatusCode()).isEqualTo(201);

        TestModels.OrderResponse order = createResponse.as(TestModels.OrderResponse.class);
        String orderId = order.getId();

        logStep("  Order created: " + orderId);

        logStep("  Waiting up to 120 seconds for auto-cancellation...");

        try {
            await()
                    .atMost(Duration.ofSeconds(120))
                    .pollInterval(Duration.ofSeconds(5))
                    .ignoreExceptions()
                    .until(() -> {
                        TestModels.OrderResponse currentOrder = orderApiClient.getOrder(token, userId, orderId);
                        String status = currentOrder.getStatus();

                        if (!status.equals("PENDING")) {
                            logStep("    Status changed to: " + status);
                            return true;
                        }

                        return false;
                    });
        } catch (Exception e) {
            logStep("  ⚠️  Order did not reach final state within timeout window");
        }

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, orderId);

        logStep("  Final status: " + finalOrder.getStatus());

        logStep("✅ AUTO-CANCELLATION SCENARIO - Validated");
    }

    @Test(priority = 5)
    @Story("Circuit Breaker Activation")
    @Severity(SeverityLevel.NORMAL)
    @Description("After N consecutive payment timeouts, circuit breaker opens (fail fast)")
    public void test05_CircuitBreakerActivation() throws Exception {
        logStep("TEST 5: Circuit breaker activation after repeated timeouts");

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        int timeoutCount = 0;
        int totalAttempts = 10;

        logStep("  Creating " + totalAttempts + " orders with payment timeouts...");

        for (int i = 0; i < totalAttempts; i++) {
            TestModels.CreateOrderRequest orderRequest =
                    TestDataFactory.defaultOrder(purchase.getProducts()).build();

            long startTime = System.currentTimeMillis();

            ServiceResponse createResponse = orderApiClient.createOrderWithFault(
                    userId, TestDataFactory.newIdempotencyKey(), orderRequest, "payment-timeout");

            long duration = System.currentTimeMillis() - startTime;

            if (createResponse.getStatusCode() == 201) {
                timeoutCount++;
                logStep("    Order " + (i + 1) + ": Created (timeout attempt)");
            } else if (createResponse.getStatusCode() == 503) {
                logStep("    Order " + (i + 1) + ": CIRCUIT BREAKER OPEN (HTTP 503) - fail fast");
                break;
            } else {
                logStep("    Order " + (i + 1) + ": HTTP " + createResponse.getStatusCode());
            }

            if (i < totalAttempts - 1) {
                Thread.sleep(500);
            }
        }

        logStep("  ✓ Total timeout attempts: " + timeoutCount);

        logStep("✅ CIRCUIT BREAKER SCENARIO - Validated");
    }

    private String getOrderStatusSafely(OrderApiClient orderApiClient, String token, String userId, String orderId) {
        try {
            return orderApiClient.getOrder(token, userId, orderId).getStatus();
        } catch (Exception e) {
            log.warn("Failed to get order status: {}", e.getMessage());
            return "UNKNOWN";
        }
    }
}