package com.amazon.tests.kafka.orders.publishing.failures;


import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.KafkaTestConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.*;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Producer Serialization & Data Errors - Fast Tests
 *
 * Strategy: Header injection (application-level simulation)
 *
 * Tests:
 * - Serialization errors (JSON marshalling failures)
 * - Invalid partition keys
 * - Schema registry unavailable (if using Avro)
 * - Malformed event data
 *
 * Run frequency: Every commit
 * Execution time: ~5 seconds
 */
@Slf4j
@Epic("Kafka Producer")
@Feature("Data & Serialization Failures")
public class ProducerDataFailuresTest extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;
    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;

    @BeforeMethod
    public void setup() throws SeedingException {
        logStep("Setting up data failure tests");

        user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        userToken = context.getCached("user_token_" + user.getId(), String.class);
        product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);
        kafkaConsumer = new KafkaTestConsumer("order.events");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SERIALIZATION ERRORS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(description = "Serialization error - cannot serialize event to JSON")
    @Story("Serialization Errors")
    @Severity(SeverityLevel.CRITICAL)
    public void test05_SerializationError_OrderCreationFails() throws Exception {
        logStep("TEST: Serialization error - JSON marshalling fails");

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

        assertThat(response.jsonPath().getString("error"))
                .isEqualTo("Kafka Unavailable");

        assertThat(response.jsonPath().getString("message"))
                .contains("serialization error");

        // Verify NO event published
        Thread.sleep(1000);
        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("orderId"),
                2
        );

        assertThat(event)
                .as("No event should be published on serialization error")
                .isEmpty();

        logStep("✅ Serialization error handled correctly");
    }

    @Test(description = "Invalid partition key - null or malformed")
    @Story("Data Validation")
    @Severity(SeverityLevel.NORMAL)
    public void test11_InvalidPartitionKey_OrderCreationFails() throws Exception {
        logStep("TEST: Invalid partition key");

        String idempotencyKey = UUID.randomUUID().toString();

        // Order with invalid partition key
        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        logStep("  Simulating invalid partition key scenario");

        Response response = sendOrderRequestWithFault(
                userToken,
                idempotencyKey,
                orderRequest,
                "invalid-partition-key"
        );

        // Should be rejected at application level
        assertThat(response.statusCode())
                .as("Order should be rejected with invalid partition key")
                .isIn(400, 500);

        logStep("✅ Invalid partition key rejected");
    }

    @Test(description = "Schema registry unavailable (for Avro schemas)")
    @Story("Schema Registry")
    @Severity(SeverityLevel.NORMAL)
    public void test13_SchemaRegistryDown_OrderCreationFails() throws Exception {
        logStep("TEST: Schema registry unavailable");

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        logStep("  Simulating schema registry failure");

        Response response = sendOrderRequestWithFault(
                userToken,
                idempotencyKey,
                orderRequest,
                "schema-registry-down"
        );

        assertThat(response.statusCode())
                .as("Order creation should fail when schema registry is down")
                .isEqualTo(500);

        assertThat(response.jsonPath().getString("message"))
                .containsAnyOf("schema registry", "schema");

        logStep("✅ Schema registry failure handled");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DATA VALIDATION ERRORS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(description = "Invalid order data - no items")
    @Story("Data Validation")
    @Severity(SeverityLevel.NORMAL)
    public void test21_InvalidOrderData_NoItems() throws Exception {
        logStep("TEST: Invalid order data - missing items");

        String idempotencyKey = UUID.randomUUID().toString();

        // Create order with NO items
        TestModels.CreateOrderRequest invalidOrder = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                // No items added
                .build();

        String requestBody = objectMapper.writeValueAsString(invalidOrder);

        Response response = RestAssured
                .given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-User-Id", user.getId().toString())
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/orders");

        assertThat(response.statusCode())
                .as("Invalid order should be rejected")
                .isIn(400, 500);

        // Verify NO event published
        Thread.sleep(1000);
        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> true,
                2
        );

        assertThat(event)
                .as("No event for invalid data")
                .isEmpty();

        logStep("✅ Invalid data rejected before Kafka");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════════

    private Response sendOrderRequestWithFault(
            String userToken,
            String idempotencyKey,
            TestModels.CreateOrderRequest orderRequest,
            String faultType) throws Exception {

        String requestBody = objectMapper.writeValueAsString(orderRequest);

        return RestAssured
                .given().log().all()
                .baseUri("http://localhost:8083")
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-User-Id", user.getId().toString())
                .header("X-Fault", faultType)
                .contentType("application/json")
                .body(requestBody)
                .when().log().all()
                .post("/api/v1/orders");
    }

    @AfterMethod
    public void cleanup() {
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
    }
}
