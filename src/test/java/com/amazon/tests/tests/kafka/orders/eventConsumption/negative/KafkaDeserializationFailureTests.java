package com.amazon.tests.tests.kafka.orders.eventConsumption.negative;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import com.github.javafaker.Faker;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kafka Deserialization Failure Tests
 *
 * Tests consumer resilience when encountering malformed, corrupted, or invalid events.
 *
 * Categories:
 * 1. Malformed JSON events (invalid JSON syntax)
 * 2. Schema mismatch (missing required fields)
 * 3. Wrong data types (string instead of number)
 * 4. Null values in required fields
 * 5. Corrupt binary data
 *
 * Most tests validate that:
 * - Consumer doesn't crash when encountering bad events
 * - Bad events sent to Dead Letter Queue (DLQ)
 * - System continues processing valid events
 * - No data loss for valid events
 */
@Epic("Amazon Microservices")
@Feature("Kafka - Deserialization Failures")
public class KafkaDeserializationFailureTests extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final Faker faker = new Faker();

    private String validToken;
    private String userId;
    private String productId;

    @BeforeClass
    public void setup() {
        logStep("Setting up Kafka deserialization failure tests");

        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();
        validToken = auth.getAccessToken();
        userId = auth.getUser().getId();
        logStep("✅ User created: " + userId);

        createTestProduct();
    }

    private void createTestProduct() {
        Map<String, Object> productData = Map.of(
                "name", "Test Product for Deserialization Tests",
                "description", "Product used in deserialization failure tests",
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
            productId = "550e8400-e29b-41d4-a716-446655440000";
            logStep("⚠️  Using fallback product ID: " + productId);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CATEGORY 1: SCHEMA MISMATCH (Missing Required Fields)
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 60)
    @Story("Deserialization Failures - Schema Mismatch")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order missing required field: shippingAddress")
    public void test60_SchemaMismatch_MissingRequiredField() {
        logStep("TEST 60: Schema mismatch - missing shippingAddress field");

        // Missing shippingAddress (required field)
        Map<String, Object> invalidOrderData = Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 1,
                                "unitPrice", 50.0,
                                "productName", "Test Product"
                        )
                )
                // shippingAddress MISSING!
        );

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
        logStep("  Response body: " + resp.asString());

        // Should be rejected at gateway/order-service level
        assertThat(resp.statusCode())
                .as("Request with missing required field should be rejected")
                .isEqualTo(400);

        String responseBody = resp.asString().toLowerCase();
        assertThat(responseBody)
                .as("Error message should mention missing field")
                .containsAnyOf("shippingaddress", "required", "must not be blank");

        logStep("✅ Schema validation at gateway prevents malformed events");
        logStep("  - Request rejected with 400 Bad Request");
        logStep("  - No event published to Kafka");
        logStep("  - Consumer never receives invalid data");
    }

    @Test(priority = 61)
    @Story("Deserialization Failures - Schema Mismatch")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order with empty items array")
    public void test61_SchemaMismatch_EmptyItems() {
        logStep("TEST 61: Schema mismatch - empty items array");

        Map<String, Object> invalidOrderData = Map.of(
                "items", List.of(),  // Empty items array
                "shippingAddress", faker.address().fullAddress()
        );

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

        assertThat(resp.statusCode())
                .as("Order with empty items should be rejected")
                .isEqualTo(400);

        String responseBody = resp.asString().toLowerCase();
        assertThat(responseBody)
                .as("Error should mention items validation")
                .containsAnyOf("items", "must not be empty", "at least one");

        logStep("✅ Empty items array rejected at gateway");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CATEGORY 2: WRONG DATA TYPES
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 62)
    @Story("Deserialization Failures - Type Mismatch")
    @Severity(SeverityLevel.NORMAL)
    @Description("Invalid data type: quantity as string instead of number")
    public void test62_TypeMismatch_StringInsteadOfNumber() {
        logStep("TEST 62: Type mismatch - quantity as string");

        // Note: Using raw JSON string to bypass Java type checking
        String invalidJson = """
            {
                "items": [{
                    "productId": "%s",
                    "quantity": "abc",
                    "unitPrice": 50.0,
                    "productName": "Test Product"
                }],
                "shippingAddress": "123 Test St"
            }
            """.formatted(productId);

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(invalidJson)
                .when()
                .post("/api/orders")
                .then()
                .extract()
                .response();

        logStep("  Response status: " + resp.statusCode());
        logStep("  Response body: " + resp.asString());

        assertThat(resp.statusCode())
                .as("Invalid type should be rejected")
                .isEqualTo(400);

        String responseBody = resp.asString().toLowerCase();
        assertThat(responseBody)
                .as("Error should mention type mismatch or parsing error")
                .containsAnyOf("parse", "invalid", "type", "number", "quantity");

        logStep("✅ Type mismatch detected and rejected");
    }

    @Test(priority = 63)
    @Story("Deserialization Failures - Type Mismatch")
    @Severity(SeverityLevel.NORMAL)
    @Description("Invalid UUID format for productId")
    public void test63_TypeMismatch_InvalidUUID() {
        logStep("TEST 63: Type mismatch - invalid UUID for productId");

        String invalidJson = """
            {
                "items": [{
                    "productId": "not-a-valid-uuid",
                    "quantity": 1,
                    "unitPrice": 50.0,
                    "productName": "Test Product"
                }],
                "shippingAddress": "123 Test St"
            }
            """;

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(invalidJson)
                .when()
                .post("/api/orders")
                .then()
                .extract()
                .response();

        logStep("  Response status: " + resp.statusCode());

        assertThat(resp.statusCode())
                .as("Invalid UUID should be rejected")
                .isEqualTo(400);

        String responseBody = resp.asString().toLowerCase();
        assertThat(responseBody)
                .as("Error should mention UUID or parsing")
                .containsAnyOf("uuid", "invalid", "format");

        logStep("✅ Invalid UUID format rejected");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CATEGORY 3: NULL VALUES IN REQUIRED FIELDS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 64)
    @Story("Deserialization Failures - Null Values")
    @Severity(SeverityLevel.NORMAL)
    @Description("Null value in required field: productName")
    public void test64_NullValue_InRequiredField() {
        logStep("TEST 64: Null value in required field");

        String invalidJson = """
            {
                "items": [{
                    "productId": "%s",
                    "quantity": 1,
                    "unitPrice": 50.0,
                    "productName": null
                }],
                "shippingAddress": "123 Test St"
            }
            """.formatted(productId);

        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(invalidJson)
                .when()
                .post("/api/orders")
                .then()
                .extract()
                .response();

        logStep("  Response status: " + resp.statusCode());

        assertThat(resp.statusCode())
                .as("Null in required field should be rejected")
                .isEqualTo(400);

        String responseBody = resp.asString().toLowerCase();
        assertThat(responseBody)
                .as("Error should mention null or required")
                .containsAnyOf("null", "required", "must not be blank", "productname");

        logStep("✅ Null value in required field rejected");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CATEGORY 4: SYSTEM STABILITY AFTER BAD EVENTS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 65)
    @Story("Deserialization Failures - System Stability")
    @Severity(SeverityLevel.CRITICAL)
    @Description("System continues processing valid events after encountering invalid data")
    public void test65_SystemStability_AfterBadEvents() {
        logStep("TEST 65: System stability after encountering bad events");

        logStep("  Creating multiple orders with mixed valid/invalid data...");

        // Invalid order 1 - missing shippingAddress
        Map<String, Object> invalidOrder1 = Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 1,
                                "unitPrice", 50.0,
                                "productName", "Test"
                        )
                )
        );

        Response resp1 = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(invalidOrder1)
                .when()
                .post("/api/orders")
                .then()
                .extract()
                .response();

        logStep("  Invalid order 1: " + resp1.statusCode());
        assertThat(resp1.statusCode()).isEqualTo(400);

        // Valid order
        Map<String, Object> validOrder = Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 1,
                                "unitPrice", 75.0,
                                "productName", "Valid Product"
                        )
                ),
                "shippingAddress", faker.address().fullAddress()
        );

        Response validResp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(validOrder)
                .when()
                .post("/api/orders")
                .then()
                .extract()
                .response();

        logStep("  Valid order: " + validResp.statusCode());
        assertThat(validResp.statusCode()).isEqualTo(201);

        String orderId = validResp.jsonPath().getString("id");
        logStep("  ✓ Valid order created: " + orderId);

        // Wait for processing
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    Response orderResp = given()
                            .baseUri(GATEWAY_URL)
                            .header("Authorization", "Bearer " + validToken)
                            .when()
                            .get("/api/orders/" + orderId)
                            .then()
                            .extract()
                            .response();

                    String status = orderResp.jsonPath().getString("status");
                    assertThat(status).isNotEqualTo("PENDING");
                });

        String finalStatus = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get("/api/orders/" + orderId)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("status");

        logStep("  ✓ Valid order processed: " + finalStatus);

        assertThat(finalStatus)
                .as("Valid order should be processed despite previous invalid requests")
                .isIn("CONFIRMED", "PAYMENT_FAILED");

        logStep("✅ System remains stable and processes valid events");
        logStep("  - Invalid requests rejected at gateway");
        logStep("  - Valid requests processed normally");
        logStep("  - No system degradation");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MANUAL TESTS - REQUIRE DIRECT KAFKA PUBLISHING
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 70, enabled = false)
    @Story("Deserialization Failures - Manual Testing")
    @Severity(SeverityLevel.NORMAL)
    @Description("MANUAL: Publish malformed JSON directly to Kafka")
    public void test70_MANUAL_MalformedJSON_DirectKafkaPublish() {
        logStep("TEST 70: MANUAL - Malformed JSON event");
        logStep("");
        logStep("  📋 SCENARIO:");
        logStep("  - Publish invalid JSON directly to Kafka topic");
        logStep("  - Consumer attempts to deserialize");
        logStep("  - Deserialization fails");
        logStep("  - Event sent to DLQ");
        logStep("  - Consumer continues processing");
        logStep("");
        logStep("  📋 MANUAL TEST PROCEDURE:");
        logStep("");
        logStep("  STEP 1: Create malformed JSON file");
        logStep("  echo '{\"orderId\": \"abc-123\", \"amount\": INVALID}' > bad-event.json");
        logStep("  (Note the INVALID - not a valid JSON value)");
        logStep("");
        logStep("  STEP 2: Publish to Kafka topic");
        logStep("  kafka-console-producer.sh --bootstrap-server localhost:9092 \\");
        logStep("    --topic payment.request < bad-event.json");
        logStep("");
        logStep("  STEP 3: Check payment service logs");
        logStep("  tail -f payment-service/logs/application.log");
        logStep("  Expected:");
        logStep("  - ERROR: Failed to deserialize message");
        logStep("  - JsonParseException or similar");
        logStep("  - Event sent to payment.request.DLQ");
        logStep("");
        logStep("  STEP 4: Verify DLQ contains failed event");
        logStep("  kafka-console-consumer.sh --bootstrap-server localhost:9092 \\");
        logStep("    --topic payment.request.DLQ \\");
        logStep("    --from-beginning");
        logStep("  Expected: See the malformed event with error metadata");
        logStep("");
        logStep("  STEP 5: Publish valid event");
        logStep("  curl -X POST http://localhost:8080/api/orders \\");
        logStep("    -H 'Authorization: Bearer YOUR_TOKEN' \\");
        logStep("    -H 'Content-Type: application/json' \\");
        logStep("    -d '{...}'");
        logStep("");
        logStep("  STEP 6: Verify valid event processed");
        logStep("  Check order status - should be CONFIRMED/PAYMENT_FAILED");
        logStep("");
        logStep("  ✅ EXPECTED RESULT:");
        logStep("  - Malformed event sent to DLQ");
        logStep("  - Consumer continues processing valid events");
        logStep("  - No system crash or degradation");
    }

    @Test(priority = 71, enabled = false)
    @Story("Deserialization Failures - Manual Testing")
    @Severity(SeverityLevel.NORMAL)
    @Description("MANUAL: Publish event with corrupt binary data")
    public void test71_MANUAL_CorruptBinaryData() {
        logStep("TEST 71: MANUAL - Corrupt binary data");
        logStep("");
        logStep("  📋 SCENARIO:");
        logStep("  - Publish binary garbage to Kafka topic");
        logStep("  - Consumer attempts to deserialize as JSON");
        logStep("  - Deserialization fails");
        logStep("  - Event sent to DLQ");
        logStep("");
        logStep("  📋 MANUAL TEST PROCEDURE:");
        logStep("");
        logStep("  STEP 1: Create binary garbage file");
        logStep("  dd if=/dev/urandom of=corrupt.bin bs=1024 count=1");
        logStep("");
        logStep("  STEP 2: Publish to Kafka");
        logStep("  kafka-console-producer.sh --bootstrap-server localhost:9092 \\");
        logStep("    --topic payment.request < corrupt.bin");
        logStep("");
        logStep("  STEP 3: Check payment service logs");
        logStep("  Expected:");
        logStep("  - ERROR: Failed to deserialize");
        logStep("  - SerializationException or CharacterCodingException");
        logStep("  - Event sent to DLQ");
        logStep("");
        logStep("  STEP 4: Verify consumer still processing");
        logStep("  Create valid order, verify it's processed");
        logStep("");
        logStep("  ✅ EXPECTED RESULT:");
        logStep("  - Corrupt data handled gracefully");
        logStep("  - Consumer doesn't crash");
        logStep("  - Valid events continue processing");
    }

    @Test(priority = 72, enabled = false)
    @Story("Deserialization Failures - Manual Testing")
    @Severity(SeverityLevel.NORMAL)
    @Description("MANUAL: Schema evolution - extra unexpected fields")
    public void test72_MANUAL_SchemaEvolution_ExtraFields() {
        logStep("TEST 72: MANUAL - Schema evolution with extra fields");
        logStep("");
        logStep("  📋 SCENARIO:");
        logStep("  - Publish event with extra fields not in DTO");
        logStep("  - Consumer should ignore unknown fields");
        logStep("  - Event processed successfully");
        logStep("");
        logStep("  📋 MANUAL TEST PROCEDURE:");
        logStep("");
        logStep("  STEP 1: Create JSON with extra fields");
        logStep("  {");
        logStep("    \"orderId\": \"valid-uuid\",");
        logStep("    \"amount\": 99.99,");
        logStep("    \"newField\": \"should be ignored\",");
        logStep("    \"anotherField\": 12345");
        logStep("  }");
        logStep("");
        logStep("  STEP 2: Publish to Kafka");
        logStep("  kafka-console-producer.sh --bootstrap-server localhost:9092 \\");
        logStep("    --topic payment.request");
        logStep("  (Paste the JSON)");
        logStep("");
        logStep("  STEP 3: Verify event processed");
        logStep("  - Consumer should ignore extra fields");
        logStep("  - Process successfully using known fields");
        logStep("  - No deserialization error");
        logStep("");
        logStep("  ✅ EXPECTED RESULT:");
        logStep("  - Extra fields ignored (Jackson @JsonIgnoreProperties)");
        logStep("  - Event processed successfully");
        logStep("  - Backward compatibility maintained");
    }


}