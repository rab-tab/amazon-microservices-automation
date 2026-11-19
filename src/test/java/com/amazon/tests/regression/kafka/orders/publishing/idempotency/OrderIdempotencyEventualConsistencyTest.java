
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Slf4j
public class OrderIdempotencyEventualConsistencyTest extends BaseTest {

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String ORDER_CREATED = "ORDER_CREATED";

    private static final int EVENT_OBSERVATION_SECONDS = 5;
    private static final int EVENTUAL_CONSISTENCY_TIMEOUT_SECONDS = 60;

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
                context.getExecutor()
        );

        // KafkaTestConsumer constructor assigns partitions and seeks to end.
        kafkaConsumer = new KafkaTestConsumer(ORDER_EVENTS_TOPIC);

        logStep("Setup complete");
    }

    @AfterMethod
    public void cleanup() {
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
            kafkaConsumer = null;
        }
    }

    private String userId() {
        return purchase.getCustomer().getUser().getId();
    }

    private String token() {
        return purchase.getCustomer().getAccessToken();
    }

    /**
     * Matches only ORDER_CREATED events for the requested order IDs.
     */
    private Predicate<JsonNode> orderCreatedEventFor(Set<String> orderIds) {
        return node ->
                ORDER_CREATED.equals(node.path("eventType").asText())
                        && orderIds.contains(node.path("orderId").asText());
    }

    /**
     * collectMessages polls for the complete observation window.
     * This allows us to detect duplicate matching events arriving
     * during that window instead of stopping after the first match.
     */
    private List<JsonNode> collectOrderCreatedEvents(
            Set<String> orderIds,
            int timeoutSeconds) {

        return kafkaConsumer.collectMessages(
                orderCreatedEventFor(orderIds),
                timeoutSeconds
        );
    }

    private void assertExactlyOneCreatedEvent(String orderId) {
        List<JsonNode> events = collectOrderCreatedEvents(
                Set.of(orderId),
                EVENT_OBSERVATION_SECONDS
        );

        assertThat(events)
                .as("Exactly one ORDER_CREATED event should exist for order %s", orderId)
                .hasSize(1);
    }

    @Test(description = "Idempotent requests with eventual consistency verification")
    @Story("Async Communication Patterns")
    @Severity(SeverityLevel.CRITICAL)
    public void test01_IdempotentRequestWithEventualConsistency() {

        logStep("TEST 1: Idempotency + Eventual Consistency");

        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        // PART 1: Idempotency

        logStep("PART 1: Testing Idempotency");
        TestModels.OrderResponse firstOrder =
                orderApiClient.createOrder(
                        userId(),
                        idempotencyKey,
                        purchase.getProducts()
                );
        String orderId = firstOrder.getId();
        assertThat(orderId)
                .as("First request should create an order")
                .isNotBlank();
        logStep("First request created order: " + orderId);
        TestModels.OrderResponse duplicateOrder =
                orderApiClient.createOrder(
                        userId(),
                        idempotencyKey,
                        purchase.getProducts()
                );

        assertThat(duplicateOrder.getId())
                .as("Duplicate request should return the original order")
                .isEqualTo(orderId);

        logStep("Duplicate request returned the existing order");

        // PART 2: Kafka event verification
        logStep("PART 2: Verifying Kafka Events");
        assertExactlyOneCreatedEvent(orderId);
        logStep("Exactly one ORDER_CREATED event observed");

        // PART 3: Eventual consistency

        logStep("PART 3: Verifying Eventual Consistency");
        OrderState finalState = pollForEventualConsistency(orderId);
        assertThat(finalState.getStatus())
                .isIn("CONFIRMED", "PAYMENT_FAILED");

        logStep("Final status: " + finalState.getStatus());
        logStep("Polling attempts: " + finalState.getAttempts());
        logStep("Total wait time: " + finalState.getTotalWaitTimeMs() + "ms");

        // PART 4: Final order data verification

        logStep("PART 4: Verifying Data Consistency");
        TestModels.OrderResponse finalOrder =
                orderApiClient.getOrder(token(), userId(), orderId);
        assertThat(finalOrder.getId())
                .isEqualTo(orderId);
        assertThat(finalOrder.getStatus())
                .isEqualTo(finalState.getStatus());
        logStep("Final order ID and status verified");
        logStep("COMPLETE: Idempotency + Events + Eventual Consistency verified");
    }

    @Test(description = "Rapid sequential duplicate requests return the same order")
    @Story("Async Communication Patterns")
    @Severity(SeverityLevel.CRITICAL)
    public void test02_RapidDuplicateRequests() {

        logStep("TEST 2: Rapid duplicate requests");
        String idempotencyKey = TestDataFactory.newIdempotencyKey();
        TestModels.OrderResponse firstOrder =
                orderApiClient.createOrder(
                        userId(),
                        idempotencyKey,
                        purchase.getProducts()
                );
        String orderId = firstOrder.getId();
        assertThat(orderId).isNotBlank();
        logStep("Original order created: " + orderId);
        logStep("Sending 5 sequential duplicate requests");
        for (int i = 0; i < 5; i++) {
            TestModels.OrderResponse duplicate =
                    orderApiClient.createOrder(
                            userId(),
                            idempotencyKey,
                            purchase.getProducts()
                    );
            assertThat(duplicate.getId())
                    .as("Duplicate request %s should return the original order", i + 1)
                    .isEqualTo(orderId);
            logStep("Duplicate " + (i + 1) + " returned the same order");
        }

        // Six requests in total: one original + five duplicates.
        assertExactlyOneCreatedEvent(orderId);

        logStep("Exactly one ORDER_CREATED event observed");
        logStep("Rapid sequential duplicate requests handled correctly");
    }

    @Test(description = "Idempotency persists across delayed sequential requests")
    @Story("Async Communication Patterns")
    @Severity(SeverityLevel.NORMAL)
    public void test03_IdempotencyPersistsAcrossDelayedRequests() {

        logStep("TEST 3: Idempotency across delayed requests");
        String idempotencyKey = TestDataFactory.newIdempotencyKey();
        TestModels.OrderResponse firstOrder =
                orderApiClient.createOrder(
                        userId(),
                        idempotencyKey,
                        purchase.getProducts()
                );

        String orderId = firstOrder.getId();
        assertThat(orderId).isNotBlank();
        logStep("First request completed: " + orderId);
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting between duplicate requests", e);
        }

        TestModels.OrderResponse secondOrder =
                orderApiClient.createOrder(
                        userId(),
                        idempotencyKey,
                        purchase.getProducts()
                );

        assertThat(secondOrder.getId())
                .as("Delayed request should return the original order")
                .isEqualTo(orderId);

        assertExactlyOneCreatedEvent(orderId);

        logStep("Delayed duplicate request returned the original order");
        logStep("Exactly one ORDER_CREATED event observed");
    }

    @Test(description = "Different idempotency keys create different orders")
    @Story("Async Communication Patterns")
    @Severity(SeverityLevel.NORMAL)
    public void test04_DifferentIdempotencyKeysCreateDifferentOrders() {

        logStep("TEST 4: Different idempotency keys create different orders");
        String key1 = TestDataFactory.newIdempotencyKey();
        String key2 = TestDataFactory.newIdempotencyKey();

        TestModels.OrderResponse order1 =
                orderApiClient.createOrder(
                        userId(),
                        key1,
                        purchase.getProducts()
                );

        TestModels.OrderResponse order2 =
                orderApiClient.createOrder(
                        userId(),
                        key2,
                        purchase.getProducts()
                );

        String orderId1 = order1.getId();
        String orderId2 = order2.getId();

        assertThat(orderId1)
                .isNotBlank();

        assertThat(orderId2)
                .isNotBlank()
                .isNotEqualTo(orderId1);

        logStep("Key 1 created order: " + orderId1);
        logStep("Key 2 created order: " + orderId2);

        // Verify one event for EACH order, not merely two events
        // whose order IDs could both belong to the same order.
        List<JsonNode> events = collectOrderCreatedEvents(
                Set.of(orderId1, orderId2),
                EVENT_OBSERVATION_SECONDS
        );

        assertThat(events)
                .as("Exactly two ORDER_CREATED events should be observed")
                .hasSize(2);

        assertThat(events)
                .filteredOn(event -> orderId1.equals(event.path("orderId").asText()))
                .as("First order should have exactly one creation event")
                .hasSize(1);

        assertThat(events)
                .filteredOn(event -> orderId2.equals(event.path("orderId").asText()))
                .as("Second order should have exactly one creation event")
                .hasSize(1);

        logStep("Each order produced exactly one ORDER_CREATED event");
        logStep("Different idempotency keys handled correctly");
    }

    /**
     * Polls the Order Service until the order reaches a terminal state.
     */
    private OrderState pollForEventualConsistency(String orderId) {

        logStep("Polling for eventual consistency...");

        AtomicInteger attemptCounter = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        try {

            await()
                    .pollDelay(Duration.ZERO)
                    .atMost(Duration.ofSeconds(EVENTUAL_CONSISTENCY_TIMEOUT_SECONDS))
                    .pollInterval(
                            new ExponentialPollInterval(
                                    Duration.ofMillis(100),
                                    Duration.ofSeconds(5)
                            )
                    )
                    .untilAsserted(() -> {

                        int attempt = attemptCounter.incrementAndGet();

                        TestModels.OrderResponse response =
                                orderApiClient.getOrder(token(), userId(), orderId);

                        String status = response.getStatus();

                        if (attempt % 5 == 0) {
                            log.info(
                                    "Attempt {}: Current order status = {}",
                                    attempt,
                                    status
                            );
                        }

                        assertThat(status)
                                .as("Order should eventually reach a terminal state")
                                .isIn("CONFIRMED", "PAYMENT_FAILED");
                    });

            TestModels.OrderResponse finalResponse =
                    orderApiClient.getOrder(token(), userId(), orderId);

            long totalWaitTime = System.currentTimeMillis() - startTime;

            return new OrderState(
                    finalResponse.getStatus(),
                    attemptCounter.get(),
                    totalWaitTime
            );

        } catch (Exception e) {

            long totalWaitTime = System.currentTimeMillis() - startTime;

            throw new AssertionError(
                    "Order did not reach a terminal state. "
                            + "Order ID: " + orderId
                            + ", Attempts: " + attemptCounter.get()
                            + ", Wait time: " + totalWaitTime + "ms",
                    e
            );
        }
    }

    /**
     * Exponential polling interval:
     * 100ms -> 200ms -> 400ms -> 800ms -> 1600ms -> 3200ms -> 5000ms.
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
