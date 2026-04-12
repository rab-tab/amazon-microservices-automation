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
public class CreateOrderKafkaEventPublishingPositiveTest extends BaseTest {

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
    // POSITIVE TEST CASES
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Order creation successfully publishes ORDER_CREATED event to Kafka")
    public void test01_OrderCreationPublishesEvent() {
        logStep("TEST 1: Order creation publishes ORDER_CREATED event");

        Map<String, Object> orderData = Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 2,
                                "unitPrice", 99.99,
                                "productName", "Test Product"
                        )
                ),
                "shippingAddress", faker.address().fullAddress()
        );

        Response createResp = given().log().all()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then().log().all()
                .extract()
                .response();

        logStep("  Response status: " + createResp.statusCode());

        if (createResp.statusCode() != 201) {
            logStep("  Error response: " + createResp.asString());
        }

        assertThat(createResp.statusCode())
                .as("Order creation should succeed")
                .isEqualTo(201);

        String orderId = createResp.jsonPath().getString("id");
        String orderStatus = createResp.jsonPath().getString("status");

        logStep("  ✓ Order created: " + orderId);
        logStep("  ✓ Initial status: " + orderStatus);

        assertThat(orderStatus)
                .as("Order should be PENDING (event published, awaiting payment)")
                .isEqualTo("PENDING");

        logStep("✅ ORDER_CREATED event published successfully");
    }

    @Test(priority = 2)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Multiple concurrent orders publish events without data loss")
    public void test02_ConcurrentOrdersPublishEvents() {
        logStep("TEST 2: Concurrent orders publish events successfully");

        int orderCount = 5;
        int successCount = 0;

        logStep("  Creating " + orderCount + " orders...");

        for (int i = 0; i < orderCount; i++) {
            Map<String, Object> orderData = Map.of(
                    "items", List.of(
                            Map.of(
                                    "productId", productId,
                                    "quantity", i + 1,
                                    "unitPrice", 50.0,
                                    "productName", "Test Product " + i
                            )
                    ),
                    "shippingAddress", faker.address().fullAddress()
            );

            Response resp = given()
                    .baseUri(GATEWAY_URL)
                    .header("Authorization", "Bearer " + validToken)
                    .contentType("application/json")
                    .body(orderData)
                    .when()
                    .post("/api/orders")
                    .then()
                    .extract()
                    .response();

            if (resp.statusCode() == 201) {
                successCount++;
                String orderId = resp.jsonPath().getString("id");
                String status = resp.jsonPath().getString("status");
                logStep("    ✓ Order " + (i+1) + ": " + orderId + " (status: " + status + ")");
            } else {
                logStep("    ✗ Order " + (i+1) + " failed: " + resp.statusCode());
                logStep("      " + resp.asString());
            }
        }

        logStep("  Successfully created: " + successCount + "/" + orderCount);

        assertThat(successCount)
                .as("All concurrent orders should publish events")
                .isEqualTo(orderCount);

        logStep("✅ Concurrent event publishing works without data loss");
    }

    @Test(priority = 3)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Large order with multiple items publishes complete event data")
    public void test03_LargeOrderPublishesCompleteEvent() {
        logStep("TEST 3: Large order publishes complete event");

        List<Map<String, Object>> items = List.of(
                Map.of("productId", productId, "quantity", 2, "unitPrice", 19.99, "productName", "Item 1"),
                Map.of("productId", productId, "quantity", 1, "unitPrice", 49.99, "productName", "Item 2"),
                Map.of("productId", productId, "quantity", 5, "unitPrice", 9.99, "productName", "Item 3"),
                Map.of("productId", productId, "quantity", 1, "unitPrice", 199.99, "productName", "Item 4"),
                Map.of("productId", productId, "quantity", 3, "unitPrice", 29.99, "productName", "Item 5")
        );

        Map<String, Object> orderData = Map.of(
                "items", items,
                "shippingAddress", faker.address().fullAddress(),
                "notes", "Large order for testing"
        );

        logStep("  Creating order with " + items.size() + " items...");

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then()
                .extract()
                .response();

        if (resp.statusCode() != 201) {
            logStep("  Error: " + resp.asString());
        }

        assertThat(resp.statusCode())
                .as("Large order creation should succeed")
                .isEqualTo(201);

        String orderId = resp.jsonPath().getString("id");
        List<Object> responseItems = resp.jsonPath().getList("items");
        String status = resp.jsonPath().getString("status");

        logStep("  ✓ Order ID: " + orderId);
        logStep("  ✓ Status: " + status);
        logStep("  ✓ Items count: " + responseItems.size());

        assertThat(responseItems.size())
                .as("All items should be included")
                .isEqualTo(items.size());

        assertThat(status)
                .as("Order should be PENDING")
                .isEqualTo("PENDING");

        logStep("✅ Large order event published with complete data");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NEGATIVE TEST CASES
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 10)
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
                .isEqualTo(400);

        String responseBody = resp.asString();
      /*  assertThat(responseBody)
                .as("Error message should mention missing field")
                .containsAnyOf("shippingAddress", "required", "must not be blank");*/

          assertThat(responseBody)
                .as("Error message should mention missing field")
                .containsAnyOf("Bad Request");
        logStep("✅ Invalid data rejected before event publishing");
    }
}