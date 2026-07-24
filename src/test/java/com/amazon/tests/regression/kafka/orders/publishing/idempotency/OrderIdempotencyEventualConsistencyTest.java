package com.amazon.tests.regression.kafka.orders.publishing.idempotency;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.pollinterval.PollInterval;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies that idempotent order creation holds up correctly under
 * eventual consistency: exactly one ORDER_CREATED event is published
 * for a duplicate request, and the order reliably reaches a terminal
 * state (CONFIRMED/PAYMENT_FAILED) despite async payment processing.
 */
@Slf4j
public class OrderIdempotencyEventualConsistencyTest extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;
    private PurchaseResult purchase;
    private OrderApiClient orderApiClient;

    @BeforeMethod
    public void setup() {
        logStep("Setting up idempotency + eventual consistency test");

        purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        orderApiClient = new OrderApiClient(
                new BearerAuthStrategy(purchase.getCustomer().getAccessToken()),
                context.getExecutor());

        kafkaConsumer = new KafkaTestConsumer("order.events");
        kafkaConsumer.seekToEnd();

        logStep("✅ Setup complete — user: " + purchase.getCustomer().getUser().getId());
    }

    @AfterMethod
    public void cleanup() {
        if (kafkaConsumer != null) kafkaConsumer.close();
    }

    private String userId() {
        return purchase.getCustomer().getUser().getId();
    }

    private String token() {
        return purchase.getCustomer().getAccessToken();
    }

    @Test(description = "Idempotent requests with eventual consistency verification")
    @Story("Async Communication Patterns")
    @Severity(SeverityLevel.CRITICAL)
    public void testIdempotentRequestWithEventualConsistency() {
        logStep("TEST: Idempotency + Eventual Consistency");

        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        // ══════════════════════════════════════════════════════
        // PART 1: IDEMPOTENCY
        // ══════════════════════════════════════════════════════
        logStep("PART 1: Testing Idempotency");

        TestModels.OrderResponse firstOrder = orderApiClient.createOrder(userId(), idempotencyKey, purchase.getProducts());
        String orderId = firstOrder.getId();
        logStep("  ✓ First request: Order created: " + orderId);

        TestModels.OrderResponse duplicateOrder = orderApiClient.createOrder(userId(), idempotencyKey, purchase.getProducts());
        assertThat(duplicateOrder.getId()).isEqualTo(orderId);
        logStep("  ✓ Duplicate request: Returned existing order");

        // ══════════════════════════════════════════════════════
        // PART 2: EVENT VERIFICATION
        // ══════════════════════════════════════════════════════
        logStep("PART 2: Verifying Kafka Events");

        List<JsonNode> events = kafkaConsumer.collectMessages(
                node -> node.has("orderId") && orderId.equals(node.get("orderId").asText()),
                5
        );

        assertThat(events).as("Only ONE ORDER_CREATED event should be published").hasSize(1);
        logStep("  ✓ Exactly 1 event published (idempotent)");

        // ══════════════════════════════════════════════════════
        // PART 3: EVENTUAL CONSISTENCY - Wait for Payment Processing
        // ══════════════════════════════════════════════════════
        logStep("PART 3: Verifying Eventual Consistency (Payment Processing)");

        OrderState finalState = pollForEventualConsistency(orderId);

        assertThat(finalState.getStatus()).as("Order should eventually reach final state").isIn("CONFIRMED", "PAYMENT_FAILED");

        logStep("  ✓ Order reached final state: " + finalState.getStatus());
        logStep("  ✓ Polling attempts: " + finalState.getAttempts());
        logStep("  ✓ Total wait time: " + finalState.getTotalWaitTimeMs() + "ms");

        // ══════════════════════════════════════════════════════
        // PART 4: CONSISTENCY VERIFICATION
        // ══════════════════════════════════════════════════════
        logStep("PART 4: Verifying Data Consistency");

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token(), userId(), orderId);
        assertThat(finalOrder.getId()).isEqualTo(orderId);
        assertThat(finalOrder.getTotalAmount())
                .as("Total amount should remain unchanged")
                .isEqualByComparingTo(firstOrder.getTotalAmount());

        logStep("✅ COMPLETE: Idempotency + Events + Eventual Consistency verified!");
    }

    // ══════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════

    /**
     * Poll for eventual consistency with exponential backoff.
     * Demonstrates Awaitility custom PollInterval usage.
     */
    private OrderState pollForEventualConsistency(String orderId) {
        logStep("  🔄 Polling for eventual consistency...");

        AtomicInteger attemptCounter = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        try {
            await()
                    .pollDelay(Duration.ZERO)
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(new ExponentialPollInterval(Duration.ofMillis(100), Duration.ofSeconds(5)))
                    .ignoreExceptions()
                    .untilAsserted(() -> {
                        int attempt = attemptCounter.incrementAndGet();

                        TestModels.OrderResponse response = orderApiClient.getOrder(token(), userId(), orderId);
                        String status = response.getStatus();

                        if (attempt % 5 == 0) {
                            log.info("  Attempt {}: Current status = {}", attempt, status);
                        }

                        assertThat(status).as("Order should eventually reach final state").isIn("CONFIRMED", "PAYMENT_FAILED");
                    });

            TestModels.OrderResponse finalResponse = orderApiClient.getOrder(token(), userId(), orderId);
            long totalWaitTime = System.currentTimeMillis() - startTime;

            return new OrderState(finalResponse.getStatus(), attemptCounter.get(), totalWaitTime);

        } catch (Exception e) {
            long totalWaitTime = System.currentTimeMillis() - startTime;
            log.error("  ❌ Eventual consistency timeout after {} attempts ({}ms)", attemptCounter.get(), totalWaitTime);
            throw new AssertionError(
                    "Order did not reach final state within timeout. Attempts: "
                            + attemptCounter.get() + ", Time: " + totalWaitTime + "ms", e);
        }
    }

    /**
     * Custom exponential backoff interval for Awaitility.
     * Polling intervals: 100ms → 200ms → 400ms → 800ms → 1600ms → capped at maxMs.
     */
    private static class ExponentialPollInterval implements PollInterval {
        private final long initialMs;
        private final long maxMs;
        private int attempt = 0;

        ExponentialPollInterval(Duration initial, Duration max) {
            this.initialMs = initial.toMillis();
            this.maxMs = max.toMillis();
        }

        @Override
        public Duration next(int pollCount, Duration previousDuration) {
            long backoff = initialMs * (long) Math.pow(2, attempt);
            attempt++;
            return Duration.ofMillis(Math.min(backoff, maxMs));
        }
    }

    @lombok.Value
    private static class OrderState {
        String status;
        int attempts;
        long totalWaitTimeMs;
    }
}