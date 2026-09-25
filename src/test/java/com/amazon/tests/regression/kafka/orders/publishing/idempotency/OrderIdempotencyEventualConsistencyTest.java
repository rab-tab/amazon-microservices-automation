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

@Slf4j
public class OrderIdempotencyEventualConsistencyTest extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;
    private PurchaseResult purchase;
    private OrderApiClient orderApiClient;

    @BeforeMethod
    public void setup() {
        logStep("Setting up idempotency + eventual consistency test");

        purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        orderApiClient = new OrderApiClient(
                new BearerAuthStrategy(purchase.getCustomer().getAccessToken()),
                context.getExecutor());

        kafkaConsumer = new KafkaTestConsumer("order.events");
        kafkaConsumer.seekToEnd();

        logStep("✅ Setup complete");
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
    public void test01_IdempotentRequestWithEventualConsistency() {
        logStep("TEST 1: Idempotency + Eventual Consistency");

        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        logStep("PART 1: Testing Idempotency");

        TestModels.OrderResponse firstOrder = orderApiClient.createOrder(userId(), idempotencyKey, purchase.getProducts());
        String orderId = firstOrder.getId();
        logStep("  ✓ First request: Order created: " + orderId);

        TestModels.OrderResponse duplicateOrder = orderApiClient.createOrder(userId(), idempotencyKey, purchase.getProducts());
        assertThat(duplicateOrder.getId()).isEqualTo(orderId);
        logStep("  ✓ Duplicate request: Returned existing order");

        logStep("PART 2: Verifying Kafka Events");

        List<JsonNode> events = kafkaConsumer.collectMessages(
                node -> node.has("orderId") && orderId.equals(node.get("orderId").asText()),
                5
        );

        assertThat(events).as("Only ONE ORDER_CREATED event").hasSize(1);
        logStep("  ✓ Exactly 1 event published");

        logStep("PART 3: Verifying Eventual Consistency");

        OrderState finalState = pollForEventualConsistency(orderId);

        assertThat(finalState.getStatus()).isIn("CONFIRMED", "PAYMENT_FAILED");

        logStep("  ✓ Final status: " + finalState.getStatus());
        logStep("  ✓ Polling attempts: " + finalState.getAttempts());
        logStep("  ✓ Total wait time: " + finalState.getTotalWaitTimeMs() + "ms");

        logStep("PART 4: Verifying Data Consistency");

        TestModels.OrderResponse finalOrder = orderApiClient.getOrder(token(), userId(), orderId);
        assertThat(finalOrder.getId()).isEqualTo(orderId);

        logStep("✅ COMPLETE: Idempotency + Events + Eventual Consistency verified");
    }

    @Test(description = "Rapid duplicate requests all return same order")
    @Story("Async Communication Patterns")
    @Severity(SeverityLevel.CRITICAL)
    public void test02_RapidDuplicateRequests() {
        logStep("TEST 2: Rapid duplicate requests");

        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        TestModels.OrderResponse firstOrder = orderApiClient.createOrder(userId(), idempotencyKey, purchase.getProducts());
        String orderId = firstOrder.getId();

        logStep("  Sending 5 rapid duplicate requests...");
        for (int i = 0; i < 5; i++) {
            TestModels.OrderResponse duplicate = orderApiClient.createOrder(userId(), idempotencyKey, purchase.getProducts());
            assertThat(duplicate.getId()).isEqualTo(orderId);
            logStep("    ✓ Duplicate " + (i + 1) + " returned same order");
        }

        List<JsonNode> events = kafkaConsumer.collectMessages(
                node -> node.has("orderId") && orderId.equals(node.get("orderId").asText()),
                3
        );

        assertThat(events).as("Only 1 event despite 6 total requests").hasSize(1);

        logStep("✅ Rapid duplicates handled correctly - single event published");
    }

    @Test(description = "Idempotency works across different API calls/sessions")
    @Story("Async Communication Patterns")
    @Severity(SeverityLevel.NORMAL)
    public void test03_IdempotencyAcrossSessionBoundaries() {
        logStep("TEST 3: Idempotency across session boundaries");

        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        TestModels.OrderResponse order1 = orderApiClient.createOrder(userId(), idempotencyKey, purchase.getProducts());
        String orderId = order1.getId();

        logStep("  First request completed");

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        TestModels.OrderResponse order2 = orderApiClient.createOrder(userId(), idempotencyKey, purchase.getProducts());

        assertThat(order2.getId()).isEqualTo(orderId);

        logStep("✅ Idempotency key honored across session boundary");
    }

    @Test(description = "Different idempotency keys produce different orders")
    @Story("Async Communication Patterns")
    @Severity(SeverityLevel.NORMAL)
    public void test04_DifferentIdempotencyKeysDifferentOrders() {
        logStep("TEST 4: Different idempotency keys produce different orders");

        String key1 = TestDataFactory.newIdempotencyKey();
        String key2 = TestDataFactory.newIdempotencyKey();

        TestModels.OrderResponse order1 = orderApiClient.createOrder(userId(), key1, purchase.getProducts());
        TestModels.OrderResponse order2 = orderApiClient.createOrder(userId(), key2, purchase.getProducts());

        assertThat(order1.getId()).isNotEqualTo(order2.getId());

        logStep("  ✓ Key1 → Order " + order1.getId());
        logStep("  ✓ Key2 → Order " + order2.getId());

        List<JsonNode> events = kafkaConsumer.collectMessages(
                node -> node.has("orderId") && (order1.getId().equals(node.get("orderId").asText()) ||
                        order2.getId().equals(node.get("orderId").asText())),
                5
        );

        assertThat(events).as("Two different orders should produce two events").hasSize(2);

        logStep("✅ Different keys produce different orders and events");
    }

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
            throw new AssertionError(
                    "Order did not reach final state. Attempts: " + attemptCounter.get() + ", Time: " + totalWaitTime + "ms", e);
        }
    }

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