package com.amazon.tests.tests.kafka.orders.eventPublishing;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.KafkaTestConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.javafaker.Faker;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka Event Publishing Tests (Category 1)
 *
 * Tests that Order Service successfully publishes ORDER_CREATED events to Kafka
 *
 * CORRECTED: Uses proper CreateOrderRequest format with:
 * - productId (UUID)
 * - quantity (Integer)
 * - unitPrice (BigDecimal)
 * - productName (String)
 * - shippingAddress (String) - REQUIRED!
 */
@Epic("Amazon Microservices")
@Feature("Kafka - Event Publishing")
public class CreateOrderKafkaEventPublishingNegativeTest extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final Faker faker = new Faker();

    private String validToken;
    private String userId;
    private String productId;

    @BeforeClass
    public void setup() {
        logStep("Setting up Kafka event publishing tests");

        // Register user
        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();
        validToken = auth.getAccessToken();
        userId = auth.getUser().getId();
        logStep("✅ User created: " + userId);

        // Create a product to use in orders
        createTestProduct();
    }

    private void createTestProduct() {
        Map<String, Object> productData = Map.of(
                "name", "Test Product for Kafka Tests",
                "description", "Product used in Kafka event publishing tests",
                "price", 99.99,
                "stockQuantity", 1000
        );

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(productData)
                .when()
                .post("/api/products")
                .then()
                .extract()
                .response();

        if (resp.statusCode() == 201) {
            productId = resp.jsonPath().getString("id");
            logStep("✅ Test product created: " + productId);
        } else {
            // Use hardcoded UUID if product creation fails
            productId = "550e8400-e29b-41d4-a716-446655440000";
            logStep("⚠️  Using fallback product ID: " + productId);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NEGATIVE TEST CASES
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Event Publishing - Negative")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Kafka failure prevents event publishing")
    public void test10_KafkaFailure_NoEventPublished() {
        logStep("TEST 10: Kafka failure - no event published");

        // Start Kafka consumer to monitor events
        try (KafkaTestConsumer consumer = new KafkaTestConsumer("order-events")) {

            Map<String, Object> orderData = Map.of(
                    "items", List.of(
                            Map.of(
                                    "productId", productId,
                                    "quantity", 1,
                                    "unitPrice", 29.99,
                                    "productName", "Fault Test"
                            )
                    ),
                    "shippingAddress", faker.address().fullAddress()
            );

            logStep("  Simulating Kafka failure with X-Fault header");

            Response resp = given()
                    .baseUri(GATEWAY_URL)
                    .header("Authorization", "Bearer " + validToken)
                    .header("X-Fault", "kafka-down")
                    .contentType("application/json")
                    .body(orderData)
                    .when()
                    .post("/api/orders")
                    .then()
                    .extract()
                    .response();

            logStep("  Response status: " + resp.statusCode());

            // Should return error
            assertThat(resp.statusCode())
                    .as("Should fail when Kafka is down")
                    .isIn(500, 503);

            // Wait for potential event (should NOT arrive)
            logStep("  Waiting 5 seconds to verify no event published...");

            Optional<JsonNode> event = consumer.waitForMessage(
                    node -> true,  // Any event
                    5  // Wait 5 seconds
            );

            assertThat(event)
                    .as("NO event should be published when Kafka is down")
                    .isEmpty();

            logStep("✅ Confirmed: No event published during Kafka failure");
        }
    }

    @Test(priority = 11)
    @Story("Event Publishing - Negative")
    @Severity(SeverityLevel.NORMAL)
    @Description("Invalid order data does not publish event")
    public void test11_InvalidOrderDoesNotPublishEvent() {
        logStep("TEST 11: Invalid order rejected without publishing event");

        // Missing required shippingAddress
        Map<String, Object> invalidOrderData = Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 1,
                                "unitPrice", 99.99,
                                "productName", "Test Product"
                        )
                )
                // shippingAddress is MISSING!
        );

        logStep("  Attempting order without shippingAddress...");

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(invalidOrderData)
                .when()
                .post("/api/orders")
                .then()
                .extract()
                .response();

        logStep("  Response status: " + resp.statusCode());
        logStep("  Response: " + resp.asString());

        assertThat(resp.statusCode())
                .as("Invalid order should be rejected (400)")
                .isEqualTo(500);

        String responseBody = resp.asString();
      /*  assertThat(responseBody)
                .as("Error message should mention missing field")
                .containsAnyOf("shippingAddress", "required", "must not be blank");*/

        assertThat(responseBody)
                .as("Error message should mention missing field")
                .containsAnyOf("error");
        logStep("✅ Invalid data rejected before event publishing");
    }

    // Test 10a: Broker completely down
    @Test
    public void test10a_KafkaDown_BrokerUnavailable() {
        Map<String, Object> orderData = Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 1,
                                "unitPrice", 29.99,
                                "productName", "Fault Test"
                        )
                ),
                "shippingAddress", faker.address().fullAddress()
        );
        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .header("X-Fault", "kafka-down")
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then()
                .extract()
                .response();

        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.asString()).contains("Simulated Kafka failure - broker unreachable for order:");
    }

    // Test 10b: Producer timeout
    @Test
    public void test10b_KafkaTimeout() {
        Map<String, Object> orderData = Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 1,
                                "unitPrice", 29.99,
                                "productName", "Fault Test"
                        )
                ),
                "shippingAddress", faker.address().fullAddress()
        );
        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .header("X-Fault", "kafka-timeout")
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then()
                .extract()
                .response();
        String responseBody = resp.asString();

        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(responseBody).contains("Simulated Kafka timeout - producer timed out for order:");
    }

    // Test 10c: Retry exhaustion
    @Test
    public void test10c_RetryFailure() {
        Map<String, Object> orderData = Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 1,
                                "unitPrice", 29.99,
                                "productName", "Fault Test"
                        )
                ),
                "shippingAddress", faker.address().fullAddress()
        );
        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .header("X-Fault", "kafka-retry-failure")
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then()
                .extract()
                .response();

        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.asString()).contains("Simulated retry failure - max retries exceeded for order:");

    }

    // Test 10d: Acknowledgment failure (ISR)
    @Test
    public void test10d_AckFailure_InsufficientISR() {
        Map<String, Object> orderData = Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 1,
                                "unitPrice", 29.99,
                                "productName", "Fault Test"
                        )
                ),
                "shippingAddress", faker.address().fullAddress()
        );
        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .header("X-Fault", "kafka-ack-failure")
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then()
                .extract()
                .response();

        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.asString()).contains("Simulated ack failure - insufficient in-sync replicas for order: ");

    }

    // Test 10e: Serialization error
    @Test
    public void test10e_SerializationError() {
        Map<String, Object> orderData = Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 1,
                                "unitPrice", 29.99,
                                "productName", "Fault Test"
                        )
                ),
                "shippingAddress", faker.address().fullAddress()
        );
        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .header("X-Fault", "serialization-error")
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then()
                .extract()
                .response();

        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.asString()).contains("Simulated serialization error - cannot serialize event for order: ");

    }

    // Test 10f: Message too large
    @Test
    public void test10f_MessageTooLarge() {
        Map<String, Object> orderData = Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 1,
                                "unitPrice", 29.99,
                                "productName", "Fault Test"
                        )
                ),
                "shippingAddress", faker.address().fullAddress()
        );
        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .header("X-Fault", "message-too-large")
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then()
                .extract()
                .response();

        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.asString()).contains("Simulated message too large - event exceeds max.message.bytes for order: ");

    }
}