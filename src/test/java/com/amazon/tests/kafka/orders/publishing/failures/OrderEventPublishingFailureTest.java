package com.amazon.tests.kafka.orders.publishing.failures;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka Event Publishing - Failure Scenarios
 *
 * Tests negative scenarios and failure handling in Kafka event publishing:
 * - Kafka broker unavailable
 * - Producer timeouts
 * - Retry exhaustion
 * - Acknowledgment failures
 * - Serialization errors
 * - Invalid data rejection
 *
 * Uses X-Fault header to simulate failures without affecting real Kafka cluster.
 */
@Slf4j
@Epic("Amazon Microservices")
@Feature("Kafka - Event Publishing Failures")
public class OrderEventPublishingFailureTest extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;
    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;

    @BeforeMethod
    public void setup() throws SeedingException {
        logStep("Setting up Kafka failure tests");

        // Seed user
        user = UserSeeder.builder(context)
                .count(1)
                .build()
                .seed()
                .getFirst();

        userToken = context.getCached("user_token_" + user.getId(), String.class);
        logStep("✅ User seeded: " + user.getId());

        // Seed product
        product = ProductSeeder.builder(context)
                .count(1)
                .highStock()
                .build()
                .seed()
                .getFirst();

        logStep("✅ Product seeded: " + product.getId());

        // Wait for data propagation
        waitForDataPropagation(1000);

        // Initialize Kafka consumer
        kafkaConsumer = new KafkaTestConsumer("order.events");
        logStep("✅ Kafka consumer initialized");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // KAFKA BROKER FAILURES
    // ══════════════════════════════════════════════════════════════════════════

    @Test(description = "Kafka broker down - order creation should fail gracefully")
    @Story("Event Publishing Failure Scenarios")
    @Severity(SeverityLevel.CRITICAL)
    public void test01_KafkaBrokerDown_OrderCreationFails() throws Exception {
        logStep("TEST 1: Kafka broker down - order creation should fail");

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        logStep("  Simulating Kafka broker failure with X-Fault: kafka-down");

        Response response = sendOrderRequestWithFault(
                userToken,
                idempotencyKey,
                orderRequest,
                "kafka-down"
        );

        logStep("  Response status: {}", response.statusCode());
        logStep("  Response body: {}", response.asString());

        // Verify HTTP 500 Internal Server Error
        assertThat(response.statusCode())
                .as("Order creation should fail when Kafka is down")
                .isEqualTo(500);

        // Verify error response structure matches your GlobalExceptionHandler
        assertThat(response.contentType())
                .as("Response should be JSON")
                .contains("application/json");

        // Parse error fields
        int status = response.jsonPath().getInt("status");
        String error = response.jsonPath().getString("error");
        String message = response.jsonPath().getString("message");
        String details = response.jsonPath().getString("details");
        String timestamp = response.jsonPath().getString("timestamp");

        // Verify status code in body
        assertThat(status)
                .as("Status in response body should be 500")
                .isEqualTo(500);

        // Verify error type
        assertThat(error)
                .as("Error should be 'Kafka Unavailable'")
                .isEqualTo("Kafka Unavailable");

        // Verify message contains Kafka failure info
        assertThat(message)
                .as("Message should indicate Kafka failure")
                .containsAnyOf(
                        "Simulated Kafka failure",
                        "broker unreachable",
                        "Kafka"
                );

        // Verify details field
        assertThat(details)
                .as("Details should provide user-friendly message")
                .isEqualTo("Unable to publish order event. Please try again later.");

        // Verify timestamp exists
        assertThat(timestamp)
                .as("Response should have timestamp")
                .isNotNull();

        logStep("  ✓ Error response validated:");
        logStep("    - status: {}", status);
        logStep("    - error: {}", error);
        logStep("    - message: {}", message);
        logStep("    - details: {}", details);

        // ================================================================
        // MOST IMPORTANT: Verify NO event was published
        // ================================================================
        logStep("  Verifying no event published to Kafka...");

        // Wait to ensure no delayed events
        Thread.sleep(2000);

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("orderId"),
                2  // Short timeout
        );

        assertThat(event)
                .as("No event should be published when Kafka is down")
                .isEmpty();

        logStep("✅ Order creation properly failed when Kafka unavailable");
        logStep("   - HTTP 500 returned ✓");
        logStep("   - Error response structured correctly ✓");
        logStep("   - No Kafka event published ✓");
    }

    @Test
    @Story("Event Publishing - Failures")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Producer timeout - order creation fails")
    public void test02_ProducerTimeout_OrderCreationFails() throws Exception {
        logStep("TEST 2: Producer timeout - order creation should fail");

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        logStep("  Simulating producer timeout with X-Fault: kafka-timeout");

        Response response = sendOrderRequestWithFault(
                userToken,
                idempotencyKey,
                orderRequest,
                "kafka-timeout"
        );

        assertThat(response.statusCode())
                .as("Order creation should fail on producer timeout")
                .isEqualTo(500);

        assertThat(response.asString())
                .as("Error message should indicate timeout")
                .contains("Simulated Kafka timeout - producer timed out");

        logStep("✅ Producer timeout handled correctly");
    }

    @Test
    @Story("Event Publishing - Failures")
    @Severity(SeverityLevel.NORMAL)
    @Description("Retry exhaustion - order creation fails after max retries")
    public void test03_RetryExhaustion_OrderCreationFails() throws Exception {
        logStep("TEST 3: Retry exhaustion - order creation should fail");

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        logStep("  Simulating retry exhaustion with X-Fault: kafka-retry-failure");

        Response response = sendOrderRequestWithFault(
                userToken,
                idempotencyKey,
                orderRequest,
                "kafka-retry-failure"
        );

        assertThat(response.statusCode())
                .as("Order creation should fail after max retries")
                .isEqualTo(500);

        assertThat(response.asString())
                .as("Error message should indicate retry exhaustion")
                .contains("Simulated retry failure - max retries exceeded");

        logStep("✅ Retry exhaustion handled correctly");
    }

    @Test
    @Story("Event Publishing - Failures")
    @Severity(SeverityLevel.NORMAL)
    @Description("Acknowledgment failure - insufficient in-sync replicas")
    public void test04_AcknowledgmentFailure_InsufficientISR() throws Exception {
        logStep("TEST 4: Acknowledgment failure - insufficient in-sync replicas");

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        logStep("  Simulating ISR failure with X-Fault: kafka-ack-failure");

        Response response = sendOrderRequestWithFault(
                userToken,
                idempotencyKey,
                orderRequest,
                "kafka-ack-failure"
        );

        assertThat(response.statusCode())
                .as("Order creation should fail with insufficient ISR")
                .isEqualTo(500);

        assertThat(response.asString())
                .as("Error message should indicate acknowledgment failure")
                .contains("Simulated ack failure - insufficient in-sync replicas");

        logStep("✅ Acknowledgment failure handled correctly");
    }

    @Test
    @Story("Event Publishing - Failures")
    @Severity(SeverityLevel.NORMAL)
    @Description("Serialization error - cannot serialize event")
    public void test05_SerializationError_OrderCreationFails() throws Exception {
        logStep("TEST 5: Serialization error - order creation should fail");

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        logStep("  Simulating serialization error with X-Fault: serialization-error");

        Response response = sendOrderRequestWithFault(
                userToken,
                idempotencyKey,
                orderRequest,
                "serialization-error"
        );

        assertThat(response.statusCode())
                .as("Order creation should fail on serialization error")
                .isEqualTo(500);

        assertThat(response.asString())
                .as("Error message should indicate serialization failure")
                .contains("Simulated serialization error - cannot serialize event");

        logStep("✅ Serialization error handled correctly");
    }

    @Test
    @Story("Event Publishing - Failures")
    @Severity(SeverityLevel.NORMAL)
    @Description("Message too large - exceeds broker limits")
    public void test06_MessageTooLarge_OrderCreationFails() throws Exception {
        logStep("TEST 6: Message too large - order creation should fail");

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        logStep("  Simulating message too large with X-Fault: message-too-large");

        Response response = sendOrderRequestWithFault(
                userToken,
                idempotencyKey,
                orderRequest,
                "message-too-large"
        );

        assertThat(response.statusCode())
                .as("Order creation should fail when message too large")
                .isEqualTo(500);

        assertThat(response.asString())
                .as("Error message should indicate message size limit")
                .contains("Simulated message too large - event exceeds max.message.bytes");

        logStep("✅ Message size limit enforced correctly");
    }

    @Test
    @Story("Event Publishing - Failures")
    @Severity(SeverityLevel.NORMAL)
    @Description("Producer buffer full - backpressure handling")
    public void test07_BufferFull_OrderCreationFails() throws Exception {
        logStep("TEST 7: Producer buffer full - order creation should fail");

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        logStep("  Simulating buffer full with X-Fault: buffer-full");

        Response response = sendOrderRequestWithFault(
                userToken,
                idempotencyKey,
                orderRequest,
                "buffer-full"
        );

        assertThat(response.statusCode())
                .as("Order creation should fail when buffer is full")
                .isEqualTo(500);

        assertThat(response.asString())
                .as("Error message should indicate buffer overflow")
                .contains("Simulated buffer full - producer buffer overflow");

        logStep("✅ Buffer overflow handled correctly");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // INVALID DATA SCENARIOS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Event Publishing - Validation")
    @Severity(SeverityLevel.NORMAL)
    @Description("Invalid order data rejected before event publishing")
    public void test08_InvalidData_RejectedBeforePublishing() throws Exception {
        logStep("TEST 8: Invalid order data rejected - no event published");

        String idempotencyKey = UUID.randomUUID().toString();

        // Create order with MISSING required field (no items)
        TestModels.CreateOrderRequest invalidOrder = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                // No items added - invalid!
                .build();

        logStep("  Sending order with missing items (invalid)...");

        String requestBody = objectMapper.writeValueAsString(invalidOrder);

        Response response = RestAssured
                .given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/orders");

        logStep("  Response status: " + response.statusCode());
        logStep("  Response body: " + response.asString());

        // Should return 400 Bad Request or 500 (depending on validation)
        assertThat(response.statusCode())
                .as("Invalid order should be rejected")
                .isIn(400, 500);

        // Verify NO event was published
        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> true,
                2
        );

        assertThat(event)
                .as("No event should be published for invalid data")
                .isEmpty();

        logStep("✅ Invalid data rejected before Kafka publishing");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Send order request with fault injection header
     */
    private Response sendOrderRequestWithFault(
            String userToken,
            String idempotencyKey,
            TestModels.CreateOrderRequest orderRequest,
            String faultType) throws Exception {

        String requestBody = objectMapper.writeValueAsString(orderRequest);
        String userId = extractUserIdFromToken(userToken);
        return RestAssured
                .given().log().all()
                .baseUri("http://localhost:8083")
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-User-Id", userId)
                .header("X-Fault", faultType)  // ⭐ Fault injection header
                .contentType("application/json")
                .body(requestBody)
                .when().log().all()
                .post("/api/v1/orders");
    }

    private String extractUserIdFromToken(String token) {
        // Decode JWT and extract user ID
        // For now, return a test UUID
        return "550e8400-e29b-41d4-a716-446655440000";
    }
    // ══════════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ══════════════════════════════════════════════════════════════════════════

    @AfterClass
    public void cleanup() {
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
            logStep("✅ Kafka consumer closed");
        }
    }
}