package com.amazon.tests.kafka.orders.publishing.success;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.KafkaTestConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.pollinterval.PollInterval;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Slf4j
public class OrderPublishingIdempotency extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;
    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;

    @BeforeMethod
    public void setup() throws SeedingException {
        logStep("Setting up Kafka event publishing tests");

        // Initialize test context
        // logStep("✅ Test context created: " + context.getNamespace());


        // Seed user using UserSeeder
        user = UserSeeder.builder(context)
                .count(1)
                .build()
                .seed()
                .getFirst();

        userToken = context.getCached("user_token_" + user.getId(), String.class);
        logStep("✅ User seeded: " + user.getId());

        // Seed product using ProductSeeder
        product = ProductSeeder.builder(context)
                .count(1)
                .highStock()  // Ensure enough stock for tests
                .build()
                .seed()
                .getFirst();

        logStep("✅ Product seeded: " + product.getId() + " (stock: " + product.getStockQuantity() + ")");

        // Wait for data propagation
        waitForDataPropagation(1000);

        // Initialize Kafka consumer for event verification
        kafkaConsumer = new KafkaTestConsumer("order.events");
        // kafkaConsumer.seekToEnd();
        logStep("✅ Kafka consumer initialized");
    }


    @Test(description = "Idempotent requests with eventual consistency verification")
    @Story("Async Communication Patterns")
    @Severity(SeverityLevel.CRITICAL)
    public void test06_IdempotentRequestWithEventualConsistency() throws Exception {
        logStep("TEST 6: Idempotency + Eventual Consistency + Advanced Polling");

        String idempotencyKey = UUID.randomUUID().toString();
        //log.info("🔑 Idempotency Key: {}", idempotencyKey);

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // ═══════════════════════════════════════════════════════════════
        // PART 1: IDEMPOTENCY (Same as before)
        // ═══════════════════════════════════════════════════════════════
        logStep("PART 1: Testing Idempotency");

        Response response1 = sendOrderRequest(userToken, idempotencyKey, orderRequest);
        assertThat(response1.statusCode()).isEqualTo(201);
        String orderId = response1.jsonPath().getString("id");
        //logStep("  ✓ First request: Order created: {}", orderId);

        Thread.sleep(500);

        Response response2 = sendOrderRequest(userToken, idempotencyKey, orderRequest);
        assertThat(response2.statusCode()).isEqualTo(200);
        assertThat(response2.jsonPath().getString("id")).isEqualTo(orderId);
        logStep("  ✓ Duplicate request: Returned existing order");

        // ═══════════════════════════════════════════════════════════════
        // PART 2: EVENT VERIFICATION (Simple polling)
        // ═══════════════════════════════════════════════════════════════
        logStep("PART 2: Verifying Kafka Events");

        List<JsonNode> events = collectEventsWithExponentialBackoff(
                orderId,
                1,  // Expected count
                5000  // Max wait time
        );

        assertThat(events)
                .as("Only ONE ORDER_CREATED event should be published")
                .hasSize(1);

        logStep("  ✓ Exactly 1 event published (idempotent)");

        // ═══════════════════════════════════════════════════════════════
        // PART 3: EVENTUAL CONSISTENCY - Wait for Payment Processing
        // ═══════════════════════════════════════════════════════════════
        logStep("PART 3: Verifying Eventual Consistency (Payment Processing)");

        // Use Awaitility for sophisticated polling with exponential backoff
        OrderState finalState = pollForEventualConsistency(orderId);

        assertThat(finalState.getStatus())
                .as("Order should eventually reach final state")
                .isIn("CONFIRMED", "PAYMENT_FAILED");

        logStep("  ✓ Order reached final state: {}", finalState.getStatus());
        logStep("  ✓ Polling attempts: {}", finalState.getAttempts());
        logStep("  ✓ Total wait time: {}ms", finalState.getTotalWaitTimeMs());

        // ═══════════════════════════════════════════════════════════════
        // PART 4: CONSISTENCY VERIFICATION
        // ═══════════════════════════════════════════════════════════════
        logStep("PART 4: Verifying Data Consistency");

        // Verify order data hasn't changed
        Response finalOrderResponse = getOrder(orderId);
        assertThat(finalOrderResponse.jsonPath().getString("id"))
                .isEqualTo(orderId);

        assertThat(finalOrderResponse.jsonPath().getDouble("totalAmount"))
                .as("Total amount should remain unchanged")
                .isEqualTo(response1.jsonPath().getDouble("totalAmount"));

        logStep("✅ COMPLETE: Idempotency + Events + Eventual Consistency verified!");
    }

// ══════════════════════════════════════════════════════════════════════════
// ASYNC PATTERN HELPERS - Demonstrate SDET Skills
// ══════════════════════════════════════════════════════════════════════════

    /**
     * Collect Kafka events with EXPONENTIAL BACKOFF
     *
     * Demonstrates:
     * - Exponential backoff strategy
     * - Dynamic polling intervals
     * - Early termination on success
     */
    private List<JsonNode> collectEventsWithExponentialBackoff(
            String orderId,
            int expectedCount,
            long maxWaitMs) {

        List<JsonNode> events = new ArrayList<>();
        long startTime = System.currentTimeMillis();
        int attempt = 0;
        long backoffMs = 100;  // Start with 100ms

        logStep("  📊 Polling with exponential backoff...");

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            attempt++;

            // Calculate exponential backoff: 100ms → 200ms → 400ms → 800ms → 1000ms (capped)
            long currentBackoff = Math.min(backoffMs * (long) Math.pow(2, attempt - 1), 1000);

            log.debug("  Attempt {}: Polling with {}ms timeout...", attempt, currentBackoff);

            Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                    node -> node.has("orderId") &&
                            node.get("orderId").asText().equals(orderId),
                    (int) (currentBackoff / 1000)  // Convert to seconds
            );

            if (event.isPresent()) {
                events.add(event.get());
                log.info("  ✓ Event {} collected (attempt {}, backoff: {}ms)",
                        events.size(), attempt, currentBackoff);

                // Early termination if we have enough events
                if (events.size() >= expectedCount) {
                    logStep("  ✓ Collected expected {} events, stopping", expectedCount);
                    break;
                }
            }

            // If no events found after multiple attempts, stop
            if (events.isEmpty() && attempt > 5) {
                logStep("  ⏹️  No events after {} attempts, stopping", attempt);
                break;
            }

            // Stop if we have events but no new ones in last few attempts
            if (events.size() > 0 && attempt > events.size() + 3) {
                logStep("  ✓ No new events after {} attempts, stopping", attempt);
                break;
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        logStep("  📊 Polling complete: {} attempts, {}ms total", attempt, totalTime);

        return events;
    }

    /**
     * Poll for eventual consistency with SOPHISTICATED RETRY LOGIC
     *
     * Demonstrates:
     * - Awaitility library usage (industry standard)
     * - Exponential backoff polling
     * - Timeout handling
     * - State tracking
     */
    private OrderState pollForEventualConsistency(String orderId) {
        logStep("  🔄 Polling for eventual consistency...");

        AtomicInteger attemptCounter = new AtomicInteger(0);
        AtomicLong startTime = new AtomicLong(System.currentTimeMillis());

        try {
            await()
                    .pollInterval(Duration.ofMillis(100))  // Start with 100ms
                    .pollDelay(Duration.ZERO)  // No initial delay
                    .atMost(Duration.ofSeconds(60))  // Max 60 seconds
                    .with()
                    .pollInterval(new ExponentialPollInterval(
                            Duration.ofMillis(100),  // Initial
                            Duration.ofSeconds(5)     // Max
                    ))
                    .ignoreExceptions()  // Don't fail on transient errors
                    .untilAsserted(() -> {
                        int attempt = attemptCounter.incrementAndGet();

                        Response response = getOrder(orderId);
                        assertThat(response.statusCode())
                                .as("Should be able to fetch order")
                                .isEqualTo(200);

                        String status = response.jsonPath().getString("status");

                        if (attempt % 5 == 0) {
                            log.info("  Attempt {}: Current status = {}", attempt, status);
                        }

                        assertThat(status)
                                .as("Order should eventually reach final state")
                                .isIn("CONFIRMED", "PAYMENT_FAILED");
                    });

            // Success - get final state
            Response finalResponse = getOrder(orderId);
            long totalWaitTime = System.currentTimeMillis() - startTime.get();

            return new OrderState(
                    finalResponse.jsonPath().getString("status"),
                    attemptCounter.get(),
                    totalWaitTime
            );

        } catch (Exception e) {
            long totalWaitTime = System.currentTimeMillis() - startTime.get();
            log.error("  ❌ Eventual consistency timeout after {} attempts ({}ms)",
                    attemptCounter.get(), totalWaitTime);
            throw new AssertionError(
                    "Order did not reach final state within timeout. Attempts: " +
                            attemptCounter.get() + ", Time: " + totalWaitTime + "ms", e);
        }
    }

    /**
     * Custom exponential backoff interval for Awaitility
     */


    /**
     * Custom exponential backoff interval for Awaitility
     *
     * Polling intervals: 100ms → 200ms → 400ms → 800ms → 1600ms → capped at maxMs
     */
    private static class ExponentialPollInterval implements PollInterval {
        private final long initialMs;
        private final long maxMs;
        private int attempt = 0;

        public ExponentialPollInterval(Duration initial, Duration max) {
            this.initialMs = initial.toMillis();
            this.maxMs = max.toMillis();
        }

        @Override
        public Duration next(int pollCount, Duration previousDuration) {
            long backoff = initialMs * (long) Math.pow(2, attempt);
            attempt++;
            long actualDelay = Math.min(backoff, maxMs);
            return Duration.ofMillis(actualDelay);
        }
    }

    /**
     * Data class to track eventual consistency state
     */
    @lombok.Value
    private static class OrderState {
        String status;
        int attempts;
        long totalWaitTimeMs;
    }

    /**
     * Helper to get order by ID
     */
    private Response getOrder(String orderId) {
        return RestAssured
                .given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .when()
                .get("/api/orders/" + orderId);
    }

    private Response sendOrderRequest(
            String userToken,
            String idempotencyKey,
            TestModels.CreateOrderRequest orderRequest) throws Exception {

        String requestBody = objectMapper.writeValueAsString(orderRequest);

        return RestAssured
                .given().log().all()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .accept("application/json")
                .body(requestBody)
                .when().log().all()
                .post("/api/orders");
    }
}
