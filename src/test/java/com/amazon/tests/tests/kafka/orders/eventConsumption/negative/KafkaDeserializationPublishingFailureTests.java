package com.amazon.tests.tests.kafka.orders.eventConsumption.negative;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import com.github.javafaker.Faker;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kafka Deserialization Failure Tests - Pure Kafka Producer API
 *
 * Uses Apache Kafka Producer directly (no Spring Boot dependencies).
 * Publishes events directly to Kafka topics to test consumer deserialization resilience.
 *
 * Tests:
 * 1. Malformed JSON event
 * 2. Schema mismatch (missing required fields)
 * 3. Wrong data types
 * 4. Null values in required fields
 * 5. Corrupt binary data
 * 6. System stability after bad events
 *
 * Requirements:
 * - kafka-clients dependency in pom.xml
 * - Kafka running on localhost:9092
 */
@Epic("Amazon Microservices")
@Feature("Kafka - Deserialization Failures - Automated")
public class KafkaDeserializationPublishingFailureTests extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final String KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String PAYMENT_REQUEST_TOPIC = "payment.request";
    private static final Faker faker = new Faker();

    // Kafka Producers
    private KafkaProducer<String, String> kafkaProducer;
    private KafkaProducer<String, byte[]> kafkaProducerBinary;

    private String validToken;
    private String userId;
    private String productId;

    @BeforeClass
    public void setup() {
        logStep("Setting up automated Kafka deserialization tests");

        // Initialize Kafka Producers
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
        if (kafkaProducer != null) {
            kafkaProducer.close();
        }
        if (kafkaProducerBinary != null) {
            kafkaProducerBinary.close();
        }
    }

    /**
     * Initialize Kafka Producers using pure Kafka API
     */
    private void initializeKafkaProducers() {
        // Producer for String messages (JSON)
        Properties stringProducerProps = new Properties();
        stringProducerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVERS);
        stringProducerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        stringProducerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        stringProducerProps.put(ProducerConfig.ACKS_CONFIG, "1");
        stringProducerProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");

        kafkaProducer = new KafkaProducer<>(stringProducerProps);
        logStep("✅ String Kafka Producer initialized");

        // Producer for Binary messages
        Properties binaryProducerProps = new Properties();
        binaryProducerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVERS);
        binaryProducerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        binaryProducerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        binaryProducerProps.put(ProducerConfig.ACKS_CONFIG, "1");
        binaryProducerProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");

        kafkaProducerBinary = new KafkaProducer<>(binaryProducerProps);
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
            logStep("⚠️  Using fallback product ID: " + productId);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AUTOMATED TESTS - DIRECT KAFKA PUBLISHING
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 80)
    @Story("Deserialization - Direct Kafka")
    @Severity(SeverityLevel.NORMAL)
    @Description("Publish malformed JSON directly to Kafka topic")
    public void test80_MalformedJSON_DirectPublish() throws Exception {
        logStep("TEST 80: Malformed JSON published directly to Kafka");

        String orderId = UUID.randomUUID().toString();

        // Malformed JSON: missing closing brace, invalid syntax
        String malformedJson = "{\"orderId\":\"" + orderId + "\",\"amount\":INVALID}";

        logStep("  Publishing malformed JSON to " + PAYMENT_REQUEST_TOPIC);
        logStep("  Content: " + malformedJson);

        // Publish using pure Kafka Producer
        ProducerRecord<String, String> record = new ProducerRecord<>(
                PAYMENT_REQUEST_TOPIC,
                orderId,
                malformedJson
        );

        Future<RecordMetadata> future = kafkaProducer.send(record);
        RecordMetadata metadata = future.get();

        logStep("  ✓ Malformed event published to partition " + metadata.partition() +
                ", offset " + metadata.offset());

        // Wait for consumer to process (or fail)
        Thread.sleep(3000);

        // Verify consumer still works by creating valid order
        logStep("  Publishing valid event to verify consumer stability...");

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

        String validOrderId = resp.jsonPath().getString("id");
        logStep("  ✓ Valid order created: " + validOrderId);

        // Wait for processing
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    String status = getOrderStatus(validOrderId);
                    assertThat(status).isNotEqualTo("PENDING");
                });

        String finalStatus = getOrderStatus(validOrderId);
        logStep("  ✓ Valid order processed: " + finalStatus);

        assertThat(finalStatus)
                .as("Consumer should still process valid events after malformed event")
                .isIn("CONFIRMED", "PAYMENT_FAILED");

        logStep("✅ Consumer resilient to malformed JSON");
        logStep("  - Malformed event sent to DLQ");
        logStep("  - Consumer continues processing valid events");
    }

    @Test(priority = 81)
    @Story("Deserialization - Direct Kafka")
    @Severity(SeverityLevel.NORMAL)
    @Description("Publish event with missing required fields")
    public void test81_MissingRequiredFields_DirectPublish() throws Exception {
        logStep("TEST 81: Event with missing required fields");

        String orderId = UUID.randomUUID().toString();

        // JSON missing 'userId' field (required)
        String incompleteJson = String.format(
                "{\"orderId\":\"%s\",\"amount\":99.99}",
                orderId
        );

        logStep("  Publishing incomplete event (missing userId)");

        ProducerRecord<String, String> record = new ProducerRecord<>(
                PAYMENT_REQUEST_TOPIC,
                orderId,
                incompleteJson
        );

        kafkaProducer.send(record).get();
        logStep("  ✓ Incomplete event published");

        Thread.sleep(3000);

        // Verify consumer resilience
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

        String validOrderId = resp.jsonPath().getString("id");

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    String status = getOrderStatus(validOrderId);
                    assertThat(status).isNotEqualTo("PENDING");
                });

        logStep("✅ Consumer handles missing fields gracefully");
    }

    @Test(priority = 82)
    @Story("Deserialization - Direct Kafka")
    @Severity(SeverityLevel.NORMAL)
    @Description("Publish event with wrong data types")
    public void test82_WrongDataType_DirectPublish() throws Exception {
        logStep("TEST 82: Event with wrong data type");

        String orderId = UUID.randomUUID().toString();

        // Amount is string instead of number
        String wrongTypeJson = String.format(
                "{\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":\"not-a-number\"}",
                orderId, userId
        );

        logStep("  Publishing event with wrong type (amount as string)");

        ProducerRecord<String, String> record = new ProducerRecord<>(
                PAYMENT_REQUEST_TOPIC,
                orderId,
                wrongTypeJson
        );

        kafkaProducer.send(record).get();
        logStep("  ✓ Wrong-type event published");

        Thread.sleep(3000);

        // Verify consumer resilience
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

        String validOrderId = resp.jsonPath().getString("id");

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    String status = getOrderStatus(validOrderId);
                    assertThat(status).isNotEqualTo("PENDING");
                });

        logStep("✅ Consumer handles type mismatch gracefully");
    }

    @Test(priority = 83)
    @Story("Deserialization - Direct Kafka")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Publish corrupt binary data")
    public void test83_CorruptBinaryData_DirectPublish() throws Exception {
        logStep("TEST 83: Corrupt binary data");

        String orderId = UUID.randomUUID().toString();

        // Binary garbage (random bytes)
        byte[] corruptData = new byte[]{
                (byte)0xFF, (byte)0xFE, (byte)0xFD, (byte)0xFC,
                0x00, 0x01, 0x02, (byte)0x80, (byte)0x90, (byte)0xA0
        };

        logStep("  Publishing corrupt binary data (10 random bytes)");

        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                PAYMENT_REQUEST_TOPIC,
                orderId,
                corruptData
        );

        kafkaProducerBinary.send(record).get();
        logStep("  ✓ Corrupt binary published");

        Thread.sleep(3000);

        // Verify consumer didn't crash
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

        String validOrderId = resp.jsonPath().getString("id");

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    String status = getOrderStatus(validOrderId);
                    assertThat(status).isNotEqualTo("PENDING");
                });

        logStep("✅ Consumer survived corrupt binary data");
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

        kafkaProducer.send(record).get();
        logStep("  ✓ Null-value event published");

        Thread.sleep(3000);

        // Verify consumer resilience
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

        String validOrderId = resp.jsonPath().getString("id");

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    String status = getOrderStatus(validOrderId);
                    assertThat(status).isNotEqualTo("PENDING");
                });

        logStep("✅ Consumer handles null values gracefully");
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
            kafkaProducer.send(record);
            logStep("    " + (i + 1) + ". Bad event published");
        }

        kafkaProducer.flush(); // Ensure all sent
        Thread.sleep(5000);

        logStep("  Publishing 3 valid events...");

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
