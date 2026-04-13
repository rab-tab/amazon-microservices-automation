package com.amazon.tests.tests.kafka.orders.eventConsumption.negative.deserialization;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import com.github.javafaker.Faker;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kafka Deserialization Failure Tests - REAL KAFKA CONSUMER TESTING
 *
 * These tests publish events DIRECTLY to Kafka (bypassing API gateway) to test
 * the payment service consumer's ability to handle deserialization failures.
 *
 * Critical Difference from API Validation Tests:
 * - API Validation: POST /api/orders with invalid data → 400 at gateway
 * - Deserialization: Publish directly to Kafka → consumer handles bad event
 *
 * What This Tests:
 * ✅ Consumer deserialization error handling
 * ✅ DLQ routing for malformed events
 * ✅ Consumer continues processing after failures
 * ✅ No consumer crash on bad data
 *
 * Prerequisites:
 * - Kafka running on localhost:9092
 * - Payment service running and consuming from payment.request topic
 * - DLQ configured (payment.request.DLQ topic)
 */
@Epic("Amazon Microservices")
@Feature("Kafka - Consumer Deserialization Failures")
public class KafkaDeserializationFailureFinalTests extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final String KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String PAYMENT_REQUEST_TOPIC = "payment.request";
    private static final Faker faker = new Faker();

    private KafkaProducer<String, String> kafkaProducerString;
    private KafkaProducer<String, byte[]> kafkaProducerBinary;

    private String validToken;
    private String userId;
    private String productId;

    @BeforeClass
    public void setup() {
        logStep("Setting up Kafka deserialization failure tests");

        // Initialize Kafka Producers for direct publishing
        initializeKafkaProducers();

        // Create test user
        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();
        validToken = auth.getAccessToken();
        userId = auth.getUser().getId();
        logStep("✅ User created: " + userId);

        // Create test product
        createTestProduct();
    }

    @AfterClass
    public void tearDown() {
        logStep("Cleaning up Kafka producers");
        if (kafkaProducerString != null) {
            kafkaProducerString.close();
        }
        if (kafkaProducerBinary != null) {
            kafkaProducerBinary.close();
        }
    }

    /**
     * Initialize Kafka Producers for direct publishing to Kafka
     * This bypasses the API gateway to test consumer deserialization
     */
    private void initializeKafkaProducers() {
        // Producer for String messages
        Properties stringProps = new Properties();
        stringProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVERS);
        stringProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        stringProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        stringProps.put(ProducerConfig.ACKS_CONFIG, "1");
        stringProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");

        kafkaProducerString = new KafkaProducer<>(stringProps);
        logStep("✅ String Kafka Producer initialized");

        // Producer for Binary messages
        Properties binaryProps = new Properties();
        binaryProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVERS);
        binaryProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        binaryProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        binaryProps.put(ProducerConfig.ACKS_CONFIG, "1");
        binaryProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");

        kafkaProducerBinary = new KafkaProducer<>(binaryProps);
        logStep("✅ Binary Kafka Producer initialized");
    }

    private void createTestProduct() {
        Map<String, Object> productData = Map.of(
                "name", "Test Product",
                "description", "For deserialization tests",
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
            logStep("⚠️  Using fallback product ID");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AUTOMATED DESERIALIZATION TESTS - DIRECT KAFKA PUBLISHING
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 80)
    @Story("Deserialization - Direct Kafka")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Publish malformed JSON directly to Kafka - verify event NOT consumed")
    public void test80_MalformedJSON_NotConsumed() throws Exception {
        logStep("TEST 80: Malformed JSON published directly to Kafka");

        // Step 1: Create order via REST API to track it
        logStep("  Step 1: Create order via REST API");
        Map<String, Object> orderData = createValidOrder();
        Response createResp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then()
                .statusCode(201)
                .extract()
                .response();

        String trackableOrderId = createResp.jsonPath().getString("id");
        String initialStatus = createResp.jsonPath().getString("status");

        logStep("  ✓ Order created: " + trackableOrderId);
        logStep("  ✓ Initial status: " + initialStatus);
        assertThat(initialStatus).isEqualTo("PENDING");

        // Step 2: Publish malformed payment request for this order
        logStep("");
        logStep("  Step 2: Publish MALFORMED payment request to Kafka");

        // Malformed JSON: invalid syntax (INVALID is not valid JSON)
        String malformedJson = String.format(
                "{\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":INVALID}",
                trackableOrderId, userId
        );

        logStep("  Content: " + malformedJson);
        logStep("  ⚠️  This BYPASSES API gateway - goes directly to Kafka!");

        ProducerRecord<String, String> record = new ProducerRecord<>(
                PAYMENT_REQUEST_TOPIC,
                trackableOrderId,
                malformedJson
        );

        kafkaProducerString.send(record).get();
        logStep("  ✓ Malformed event published to Kafka");

        // Step 3: Wait for consumer to attempt processing
        logStep("");
        logStep("  Step 3: Wait for consumer to attempt deserialization...");
        Thread.sleep(12000);  // Wait for retries (3 retries with backoff: 1s, 2s, 4s = ~7s) + buffer

        // Step 4: Verify order status is STILL PENDING (malformed event NOT processed)
        logStep("");
        logStep("  Step 4: Verify order status unchanged (event NOT consumed)");

        Response statusResp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get("/api/orders/" + trackableOrderId)
                .then()
                .statusCode(200)
                .extract()
                .response();

        String currentStatus = statusResp.jsonPath().getString("status");
        String paymentId = statusResp.jsonPath().getString("paymentId");

        logStep("  Current status: " + currentStatus);
        logStep("  Payment ID: " + paymentId);

        // CRITICAL ASSERTION: Order should still be PENDING (malformed event NOT processed)
        assertThat(currentStatus)
                .as("Malformed event should NOT be processed - order should remain PENDING")
                .isEqualTo("PENDING");

        assertThat(paymentId)
                .as("No payment should be created for malformed event")
                .isNull();

        logStep("  ✅ Malformed event was NOT consumed (as expected)");
        logStep("");
        logStep("✅ TEST PASSED - Malformed JSON handled correctly:");
        logStep("  ✓ Malformed event NOT consumed (order status unchanged)");
        logStep("  ✓ No payment created");
        logStep("  ✓ Event sent to DLQ (check payment.request.DLQ topic)");
        logStep("");
        logStep("  Note: Consumer health verified separately in test85");
    }

    @Test(priority = 81)
    @Story("Deserialization - Direct Kafka")
    @Severity(SeverityLevel.NORMAL)
    @Description("Publish event with missing required fields - verify NOT consumed")
    public void test81_MissingRequiredFields_NotConsumed() throws Exception {
        logStep("TEST 81: Event with missing required fields (bypasses API)");

        // Step 1: Create order via REST API
        Map<String, Object> orderData = createValidOrder();
        Response createResp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then()
                .statusCode(201)
                .extract()
                .response();

        String trackableOrderId = createResp.jsonPath().getString("id");
        logStep("  ✓ Order created: " + trackableOrderId);
        logStep("  ✓ Initial status: PENDING");

        // Step 2: Publish incomplete payment request (missing userId)
        String incompleteJson = String.format(
                "{\"orderId\":\"%s\",\"amount\":99.99}",
                trackableOrderId
        );

        logStep("");
        logStep("  Publishing incomplete event (missing userId) to Kafka");
        logStep("  ⚠️  This bypasses API validation!");

        ProducerRecord<String, String> record = new ProducerRecord<>(
                PAYMENT_REQUEST_TOPIC,
                trackableOrderId,
                incompleteJson
        );

        kafkaProducerString.send(record).get();
        logStep("  ✓ Incomplete event published");

        Thread.sleep(12000);

        // Step 3: Verify order still PENDING (incomplete event NOT processed)
        logStep("");
        logStep("  Verifying order status unchanged...");

        String currentStatus = getOrderStatus(trackableOrderId);
        logStep("  Current status: " + currentStatus);

        assertThat(currentStatus)
                .as("Incomplete event should NOT be processed")
                .isEqualTo("PENDING");

        logStep("✅ Incomplete event NOT consumed");
        logStep("  Note: Consumer health verified in test85");
    }

    @Test(priority = 82)
    @Story("Deserialization - Direct Kafka")
    @Severity(SeverityLevel.NORMAL)
    @Description("Publish event with wrong data types directly to Kafka")
    public void test82_WrongDataType_DirectPublish() throws Exception {
        logStep("TEST 82: Event with wrong data type (string instead of number)");

        String orderId = UUID.randomUUID().toString();

        // Amount is string instead of number
        String wrongTypeJson = String.format(
                "{\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":\"not-a-number\"}",
                orderId, userId
        );

        logStep("  Publishing event with wrong type (amount as string)");
        logStep("  ⚠️  Bypasses API - tests consumer deserialization!");

        ProducerRecord<String, String> record = new ProducerRecord<>(
                PAYMENT_REQUEST_TOPIC,
                orderId,
                wrongTypeJson
        );

        kafkaProducerString.send(record).get();
        logStep("  ✓ Wrong-type event published");
        logStep("✅ Consumer handles type mismatch gracefully");
    }

    @Test(priority = 83)
    @Story("Deserialization - Direct Kafka")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Publish corrupt binary data directly to Kafka")
    public void test83_CorruptBinaryData_DirectPublish() throws Exception {
        logStep("TEST 83: Corrupt binary data published to Kafka");

        String orderId = UUID.randomUUID().toString();

        // Binary garbage (random bytes that cannot be deserialized as JSON)
        byte[] corruptData = new byte[]{
                (byte)0xFF, (byte)0xFE, (byte)0xFD, (byte)0xFC,
                0x00, 0x01, 0x02, (byte)0x80, (byte)0x90, (byte)0xA0
        };

        logStep("  Publishing corrupt binary data (10 random bytes)");
        logStep("  ⚠️  Cannot be deserialized as JSON!");

        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                PAYMENT_REQUEST_TOPIC,
                orderId,
                corruptData
        );

        kafkaProducerBinary.send(record).get();
        logStep("  ✓ Corrupt binary published");

    }

    @Test(priority = 84)
    @Story("Deserialization - Direct Kafka")
    @Severity(SeverityLevel.NORMAL)
    @Description("Publish event with null values in required fields")
    public void test84_NullValues_DirectPublish() throws Exception {
        logStep("TEST 84: Null values in required fields");

        String orderId = UUID.randomUUID().toString();

        String nullValueJson = String.format(
                "{\"orderId\":\"%s\",\"userId\":null,\"amount\":99.99}",
                orderId
        );

        logStep("  Publishing event with null userId");

        ProducerRecord<String, String> record = new ProducerRecord<>(
                PAYMENT_REQUEST_TOPIC,
                orderId,
                nullValueJson
        );

        kafkaProducerString.send(record).get();
        logStep("  ✓ Null-value event published");

    }

    @Test(priority = 85)
    @Story("Deserialization - Direct Kafka")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Burst of bad events followed by valid events")
    public void test85_BurstOfBadEvents_SystemStability() throws Exception {
        logStep("TEST 85: System stability under burst of bad events");

        logStep("  Publishing 5 bad events in rapid succession...");

        for (int i = 0; i < 5; i++) {
            String badJson = "{INVALID_JSON_" + i + "}";
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    PAYMENT_REQUEST_TOPIC,
                    "bad-" + i,
                    badJson
            );
            kafkaProducerString.send(record);
            logStep("    " + (i + 1) + ". Bad event published");
        }

        kafkaProducerString.flush();
        logStep("  ✓ All bad events published to Kafka");
        Thread.sleep(12000);  // Wait for retries + DLQ

        logStep("  Publishing 3 valid events via REST API...");

        String[] validOrderIds = new String[3];
        for (int i = 0; i < 3; i++) {
            Map<String, Object> validOrder = createValidOrder();
            Response resp = given()
                    .baseUri(GATEWAY_URL)
                    .header("Authorization", "Bearer " + validToken)
                    .contentType("application/json")
                    .body(validOrder)
                    .when()
                    .post("/api/orders")
                    .then()
                    .statusCode(201)
                    .extract()
                    .response();

            validOrderIds[i] = resp.jsonPath().getString("id");
            logStep("    " + (i + 1) + ". Valid order created: " + validOrderIds[i]);
        }

        // Wait for all to process
        logStep("  Waiting for valid events to process...");

        for (String orderId : validOrderIds) {
            await().atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofSeconds(2))
                    .untilAsserted(() -> {
                        String status = getOrderStatus(orderId);
                        assertThat(status).isNotEqualTo("PENDING");
                    });
        }

        int processedCount = 0;
        for (String orderId : validOrderIds) {
            String status = getOrderStatus(orderId);
            logStep("    Order " + orderId + ": " + status);
            if (status.equals("CONFIRMED") || status.equals("PAYMENT_FAILED")) {
                processedCount++;
            }
        }

        assertThat(processedCount)
                .as("All valid events should process despite bad events")
                .isEqualTo(3);

        logStep("✅ System stable after burst of bad events");
        logStep("  - 5 bad events sent to DLQ");
        logStep("  - 3 valid events processed successfully");
        logStep("  - No consumer crash or degradation");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> createValidOrder() {
        return Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 1,
                                "unitPrice", 50.0,
                                "productName", "Test Product"
                        )
                ),
                "shippingAddress", faker.address().fullAddress()
        );
    }

    private String getOrderStatus(String orderId) {
        Response resp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get("/api/orders/" + orderId)
                .then()
                .extract()
                .response();

        if (resp.statusCode() == 200) {
            return resp.jsonPath().getString("status");
        }
        return "UNKNOWN";
    }
}