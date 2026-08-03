package com.amazon.tests.regression.kafka.orders.consumption.positive;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kafka Event Consumption - Positive Flows
 *
 * ⚠️ test22_EventsProcessedInFIFOOrder was removed (was already
 * @Test(enabled = false)). Even enabled, its implementation captured
 * creation timestamps but never compared them against anything — the
 * only real assertion was "all N orders eventually reached a terminal
 * state," identical to test21, not an ordering check. If FIFO ordering
 * verification is genuinely wanted, it needs to be built for real
 * (compare Payment Service's processing timestamps or Kafka consumer
 * offsets against publish order) rather than restored as-is.
 */
@Epic("Amazon Microservices")
@Feature("Kafka - Event Consumption")
public class CreateOrderEventConsumptionTest extends BaseTest {

    private OrderApiClient orderApiClient;
    private String token;
    private String userId;
    private TestModels.ProductResponse product;

    @BeforeClass
    public void setup() {
        logStep("Setting up Kafka event consumption tests");

        PurchaseResult purchase = PurchaseWorkflow.start(executor, authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(99.99, 1000)
                .execute();

        token = purchase.getCustomer().getAccessToken();
        userId = purchase.getCustomer().getUser().getId();
        product = purchase.getFirstProduct();

        orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), executor);

        logStep("✅ Setup complete — product: " + product.getId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // POSITIVE TEST CASES
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("Event Consumption - Positive")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Payment service consumes ORDER_CREATED event and processes payment")
    public void test20_PaymentServiceConsumesOrderEvent() {
        logStep("TEST 20: Payment service consumes and processes ORDER_CREATED event");

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), List.of(product));

        logStep("  ✓ Order created: " + order.getId() + " | initial status: " + order.getStatus());
        assertThat(order.getStatus()).as("Order should start in PENDING status").isEqualTo("PENDING");

        logStep("  Waiting for payment service to consume ORDER_CREATED event...");
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(orderApiClient.getOrder(token, userId, order.getId()).getStatus())
                        .as("Order status should change from PENDING")
                        .isNotEqualTo("PENDING"));

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token, userId, order.getId());
        logStep("  ✓ Final status: " + finalOrder.getStatus());

        if (finalOrder.getStatus().equals("CONFIRMED")) {
            assertThat(finalOrder.getPaymentId()).as("CONFIRMED order should have payment ID").isNotNull();
        }

        assertThat(finalOrder.getStatus())
                .as("Order should be CONFIRMED (payment success) or PAYMENT_FAILED")
                .isIn("CONFIRMED", "PAYMENT_FAILED");

        logStep("✅ Payment service successfully consumed and processed ORDER_CREATED event — "
                + "Order Service → Kafka → Payment Service → Kafka → Order Service");
    }

    @Test(priority = 2)
    @Story("Event Consumption - Positive")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Multiple independent orders all processed to a terminal state")
    public void test21_MultipleIndependentOrders_AllProcessedCorrectly() {
        logStep("TEST 21: Multiple independent order events all processed correctly");

        int orderCount = 3;
        String[] orderIds = new String[orderCount];

        logStep("  Creating " + orderCount + " orders rapidly...");
        for (int i = 0; i < orderCount; i++) {
            TestModels.OrderResponse order = orderApiClient.createOrder(
                    userId, TestDataFactory.newIdempotencyKey(), List.of(product));
            orderIds[i] = order.getId();
            logStep("    Order " + (i + 1) + " created: " + orderIds[i]);
        }

        logStep("  Waiting for payment service to process all events...");
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    long processedCount = java.util.Arrays.stream(orderIds)
                            .filter(id -> !orderApiClient.getOrder(token, userId, id).getStatus().equals("PENDING"))
                            .count();
                    logStep("    Processed: " + processedCount + "/" + orderCount);
                    assertThat(processedCount).as("All orders should be processed").isEqualTo(orderCount);
                });

        for (int i = 0; i < orderCount; i++) {
            String status = orderApiClient.getOrder(token, userId, orderIds[i]).getStatus();
            logStep("  ✓ Order " + (i + 1) + " final status: " + status);
            assertThat(status).as("Each order should reach a final state").isIn("CONFIRMED", "PAYMENT_FAILED");
        }

        logStep("✅ All independent order events processed correctly — no cross-order interference");
    }
}