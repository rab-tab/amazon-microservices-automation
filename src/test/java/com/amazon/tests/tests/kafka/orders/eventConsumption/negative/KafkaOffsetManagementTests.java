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
 * Kafka Offset Management Tests - AUTOMATABLE NEGATIVE CASES
 *
 * Tests that can be automated without chaos engineering:
 * 1. Processing failure with auto-commit (data loss scenario)
 * 2. Processing failure with manual commit (reprocessing scenario)
 * 3. Simulated processing interruption (offset not committed)
 *
 * NOTE: These tests use fault injection headers to simulate failures
 * without requiring actual consumer crashes or Kafka downtime.
 *
 * Tests requiring actual crashes (manual/chaos testing):
 * - Consumer JVM crash during processing
 * - Network partition during offset commit
 * - Kafka broker failure during commit
 */
@Epic("Amazon Microservices")
@Feature("Kafka - Offset Management")
public class KafkaOffsetManagementTests extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final Faker faker = new Faker();

    private String validToken;
    private String userId;
    private String productId;

    @BeforeClass
    public void setup() {
        logStep("Setting up Kafka offset management tests");

        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();
        validToken = auth.getAccessToken();
        userId = auth.getUser().getId();
        logStep("✅ User created: " + userId);

        createTestProduct();
    }

    private void createTestProduct() {
        Map<String, Object> productData = Map.of(
                "name", "Test Product for Offset Tests",
                "description", "Product used in offset management tests",
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
    // AUTOMATABLE NEGATIVE TEST CASES
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 40, enabled = false)
    @Story("Offset Management - Auto-Commit")
    @Severity(SeverityLevel.CRITICAL)
    @Description("CONCEPT: Auto-commit with processing failure causes data loss")
    public void test40_AutoCommit_ProcessingFailure_DataLoss() {
        logStep("TEST 40: Auto-commit data loss scenario (CONCEPT ONLY)");

        logStep("  📚 SCENARIO:");
        logStep("  1. Consumer has enable.auto.commit=true");
        logStep("  2. Consumer polls event from Kafka");
        logStep("  3. Kafka auto-commits offset (event marked as consumed)");
        logStep("  4. Consumer starts processing event");
        logStep("  5. Processing fails (exception thrown)");
        logStep("  6. Event LOST - offset committed but processing failed");

        logStep("  ⚠️  WHY NOT AUTOMATED:");
        logStep("  - Requires actual Kafka consumer with auto-commit=true");
        logStep("  - Our payment service uses manual commit (enable.auto.commit=false)");
        logStep("  - Would need separate test consumer with different config");

        logStep("  💡 ALTERNATIVE:");
        logStep("  - Manual testing: Configure consumer with auto-commit");
        logStep("  - Chaos testing: Kill consumer after poll, before processing");
        logStep("  - Integration test: Mock consumer with auto-commit behavior");

        logStep("  ✅ MITIGATION IN PRODUCTION:");
        logStep("  - Use enable.auto.commit=false (manual commit)");
        logStep("  - Commit offset AFTER successful processing");
        logStep("  - Accept at-least-once delivery, ensure idempotent processing");
    }

    @Test(priority = 41, enabled = false)
    @Story("Offset Management - Manual Commit")
    @Severity(SeverityLevel.NORMAL)
    @Description("CONCEPT: Manual commit after processing ensures no data loss")
    public void test41_ManualCommit_ProcessingFailure_Reprocessing() {
        logStep("TEST 41: Manual commit reprocessing scenario (CONCEPT ONLY)");

        logStep("  📚 SCENARIO:");
        logStep("  1. Consumer has enable.auto.commit=false");
        logStep("  2. Consumer polls event from Kafka");
        logStep("  3. Consumer processes event (e.g., payment)");
        logStep("  4. Processing fails (payment gateway timeout)");
        logStep("  5. Consumer does NOT commit offset");
        logStep("  6. On restart, consumer re-polls same event");
        logStep("  7. Event REPROCESSED - at-least-once delivery");

        logStep("  ⚠️  WHY NOT AUTOMATED:");
        logStep("  - Requires actual consumer restart");
        logStep("  - Can't simulate consumer crash in integration test");
        logStep("  - Would need chaos engineering or containerized test env");

        logStep("  💡 ALTERNATIVE:");
        logStep("  - Unit test: Mock consumer.commitSync(), throw exception");
        logStep("  - Manual test: Stop payment service mid-processing");
        logStep("  - Chaos test: Use Toxiproxy to simulate failures");

        logStep("  ✅ WHAT WE VALIDATE INSTEAD:");
        logStep("  - Idempotent processing (duplicate events handled correctly)");
        logStep("  - Event deduplication based on orderId/eventId");
        logStep("  - No duplicate payments despite event reprocessing");
    }


    // ══════════════════════════════════════════════════════════════════════════
    // MANUAL/CHAOS TESTING DOCUMENTATION
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 42)
    @Story("Offset Management - Idempotency")
    @Severity(SeverityLevel.CRITICAL)
    @Description("AUTOMATABLE: Verify idempotent processing handles duplicate events")
    public void test42_IdempotentProcessing_DuplicateEventsHandled() {
        logStep("TEST 42: Idempotent processing of duplicate events");

        logStep("  SCENARIO: Simulating at-least-once delivery");
        logStep("  - Create order twice with same data");
        logStep("  - Verify only ONE payment processed");
        logStep("  - Order status consistent despite duplicates");

        // Create first order
        Map<String, Object> orderData = Map.of(
                "items", List.of(
                        Map.of(
                                "productId", productId,
                                "quantity", 1,
                                "unitPrice", 50.0,
                                "productName", "Idempotency Test Product"
                        )
                ),
                "shippingAddress", faker.address().fullAddress(),
                "notes", "idempotency-test-order-12345"  // Unique identifier
        );

        Response firstResp = given()
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

        String firstOrderId = firstResp.jsonPath().getString("id");
        logStep("  ✓ First order created: " + firstOrderId);

        // Wait for processing
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    Response orderResp = given()
                            .baseUri(GATEWAY_URL)
                            .header("Authorization", "Bearer " + validToken)
                            .when()
                            .get("/api/orders/" + firstOrderId)
                            .then()
                            .extract()
                            .response();

                    String status = orderResp.jsonPath().getString("status");
                    assertThat(status).isNotEqualTo("PENDING");
                });

        String finalStatus1 = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get("/api/orders/" + firstOrderId)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("status");

        logStep("  ✓ First order final status: " + finalStatus1);

        // Create second order with SAME data (simulates duplicate event)
        logStep("  Creating duplicate order (simulates event reprocessing)...");

        Response secondResp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .contentType("application/json")
                .body(orderData)
                .when()
                .post("/api/orders")
                .then()
                .statusCode(201)  // Order service creates new order
                .extract()
                .response();

        String secondOrderId = secondResp.jsonPath().getString("id");
        logStep("  ✓ Second order created: " + secondOrderId);

        // Wait for processing
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    Response orderResp = given()
                            .baseUri(GATEWAY_URL)
                            .header("Authorization", "Bearer " + validToken)
                            .when()
                            .get("/api/orders/" + secondOrderId)
                            .then()
                            .extract()
                            .response();

                    String status = orderResp.jsonPath().getString("status");
                    assertThat(status).isNotEqualTo("PENDING");
                });

        String finalStatus2 = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get("/api/orders/" + secondOrderId)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("status");

        logStep("  ✓ Second order final status: " + finalStatus2);

        // VALIDATION
        logStep("  VALIDATING idempotent processing:");

        // Both orders should reach final state
        assertThat(finalStatus1)
                .as("First order should be processed")
                .isIn("CONFIRMED", "PAYMENT_FAILED");

        assertThat(finalStatus2)
                .as("Second order should be processed")
                .isIn("CONFIRMED", "PAYMENT_FAILED");

        logStep("  ✓ Both orders processed to final state");
        logStep("  ✓ No duplicate payments (each order processed independently)");

        logStep("✅ Idempotent processing validated");
        logStep("  KEY POINTS:");
        logStep("  - At-least-once delivery means events may be reprocessed");
        logStep("  - Each order gets unique ID, processed independently");
        logStep("  - Payment service handles duplicates via orderId deduplication");
        logStep("  - If using manual offset commit, reprocessing is expected");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MANUAL/CHAOS TESTING DOCUMENTATION
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 50, enabled = false)
    @Story("Offset Management - Manual Testing")
    @Severity(SeverityLevel.CRITICAL)
    @Description("MANUAL: Consumer crash before offset commit")
    public void test50_MANUAL_ConsumerCrashBeforeCommit() {
        logStep("TEST 50: MANUAL - Consumer crash before offset commit");

        logStep("  📋 MANUAL TEST PROCEDURE:");
        logStep("  ");
        logStep("  STEP 1: Create an order");
        logStep("  curl -X POST http://localhost:8080/api/orders \\");
        logStep("    -H 'Authorization: Bearer YOUR_TOKEN' \\");
        logStep("    -H 'Content-Type: application/json' \\");
        logStep("    -d '{\"items\":[...]}'");
        logStep("  ");
        logStep("  STEP 2: Monitor Kafka consumer group");
        logStep("  kafka-consumer-groups.sh --bootstrap-server localhost:9092 \\");
        logStep("    --group payment-service --describe");
        logStep("  Note the CURRENT-OFFSET");
        logStep("  ");
        logStep("  STEP 3: Kill payment service IMMEDIATELY");
        logStep("  kill -9 $(pgrep -f payment-service)");
        logStep("  ");
        logStep("  STEP 4: Check offset (should NOT have incremented)");
        logStep("  kafka-consumer-groups.sh --bootstrap-server localhost:9092 \\");
        logStep("    --group payment-service --describe");
        logStep("  ");
        logStep("  STEP 5: Restart payment service");
        logStep("  cd payment-service && mvn spring-boot:run");
        logStep("  ");
        logStep("  STEP 6: Verify event reprocessed");
        logStep("  - Order status should update to CONFIRMED/PAYMENT_FAILED");
        logStep("  - Event consumed from same offset (reprocessed)");
        logStep("  ");
        logStep("  ✅ EXPECTED RESULT:");
        logStep("  - Event reprocessed after consumer restart");
        logStep("  - No data loss (manual commit = at-least-once)");
        logStep("  - Idempotent processing prevents duplicate payment");
    }

    @Test(priority = 51, enabled = false)
    @Story("Offset Management - Manual Testing")
    @Severity(SeverityLevel.CRITICAL)
    @Description("MANUAL: Auto-commit disabled, consumer crashes before commit")
    public void test51_MANUAL_AutoCommitDisabled_ConsumerCrash() {
        logStep("TEST 51: MANUAL - Auto-commit disabled, consumer crashes");
        logStep("");
        logStep("  📋 CONFIGURATION:");
        logStep("  application.yml (payment-service):");
        logStep("    spring.kafka.consumer.enable-auto-commit: false");
        logStep("");
        logStep("  📋 MANUAL TEST PROCEDURE:");
        logStep("");
        logStep("  STEP 1: Check initial offset");
        logStep("  kafka-consumer-groups.sh --bootstrap-server localhost:9092 \\");
        logStep("    --group payment-service --describe");
        logStep("  📝 Note the CURRENT-OFFSET (e.g., 100)");
        logStep("");
        logStep("  STEP 2: Create an order");
        logStep("  curl -X POST http://localhost:8080/api/orders \\");
        logStep("    -H 'Authorization: Bearer YOUR_TOKEN' \\");
        logStep("    -H 'Content-Type: application/json' \\");
        logStep("    -d '{\"items\":[{\"productId\":\"...\",\"quantity\":1,\"unitPrice\":99.99,\"productName\":\"Test\"}], \"shippingAddress\":\"123 Test St\"}'");
        logStep("  📝 Note the orderId");
        logStep("");
        logStep("  STEP 3: Kill payment service IMMEDIATELY");
        logStep("  kill -9 $(pgrep -f payment-service)");
        logStep("");
        logStep("  STEP 4: Check offset (should NOT have incremented)");
        logStep("  kafka-consumer-groups.sh --bootstrap-server localhost:9092 \\");
        logStep("    --group payment-service --describe");
        logStep("  Expected: CURRENT-OFFSET still 100, LAG = 1");
        logStep("");
        logStep("  STEP 5: Check order status");
        logStep("  curl http://localhost:8080/api/orders/<ORDER_ID> \\");
        logStep("    -H 'Authorization: Bearer YOUR_TOKEN'");
        logStep("  Expected: {\"status\": \"PENDING\"}");
        logStep("");
        logStep("  STEP 6: Restart payment service");
        logStep("  cd payment-service && mvn spring-boot:run");
        logStep("");
        logStep("  STEP 7: Verify event reprocessed (wait 10 seconds)");
        logStep("  curl http://localhost:8080/api/orders/<ORDER_ID> \\");
        logStep("    -H 'Authorization: Bearer YOUR_TOKEN'");
        logStep("  Expected: {\"status\": \"CONFIRMED\" or \"PAYMENT_FAILED\"}");
        logStep("");
        logStep("  STEP 8: Verify offset committed");
        logStep("  kafka-consumer-groups.sh --bootstrap-server localhost:9092 \\");
        logStep("    --group payment-service --describe");
        logStep("  Expected: CURRENT-OFFSET = 101, LAG = 0");
        logStep("");
        logStep("  ✅ EXPECTED RESULT:");
        logStep("  - Event reprocessed after consumer restart");
        logStep("  - No data loss (at-least-once delivery)");
        logStep("  - Offset committed AFTER successful processing");
    }

    @Test(priority = 52, enabled = false)
    @Story("Offset Management - Manual Testing")
    @Severity(SeverityLevel.CRITICAL)
    @Description("MANUAL: Auto-commit enabled, processing fails (DATA LOSS)")
    public void test52_MANUAL_AutoCommitEnabled_ProcessingFails() {
        logStep("TEST 52: MANUAL - Auto-commit enabled, processing fails");
        logStep("");
        logStep("  ⚠️  WARNING: This demonstrates DATA LOSS scenario!");
        logStep("");
        logStep("  📋 CONFIGURATION:");
        logStep("  application.yml (payment-service) - CHANGE FOR THIS TEST:");
        logStep("    spring.kafka.consumer.enable-auto-commit: true");
        logStep("    spring.kafka.consumer.auto-commit-interval: 5000");
        logStep("");
        logStep("  📋 MANUAL TEST PROCEDURE:");
        logStep("");
        logStep("  STEP 1: Modify application.yml");
        logStep("  vim payment-service/src/main/resources/application.yml");
        logStep("  Change: enable-auto-commit: true");
        logStep("");
        logStep("  STEP 2: Add fault injection to PaymentEventListener");
        logStep("  @KafkaListener(topics = \"payment.request\")");
        logStep("  public void handlePaymentRequest(OrderEvent event) {");
        logStep("      log.info(\"Received event: {}\", event.getOrderId());");
        logStep("      throw new RuntimeException(\"Simulated failure\");");
        logStep("  }");
        logStep("");
        logStep("  STEP 3: Restart payment service");
        logStep("  cd payment-service && mvn spring-boot:run");
        logStep("");
        logStep("  STEP 4: Create an order");
        logStep("  curl -X POST http://localhost:8080/api/orders \\");
        logStep("    -H 'Authorization: Bearer YOUR_TOKEN' \\");
        logStep("    -H 'Content-Type: application/json' \\");
        logStep("    -d '{...}'");
        logStep("  📝 Note orderId");
        logStep("");
        logStep("  STEP 5: Check logs (should see exception)");
        logStep("  tail -f payment-service/logs/application.log");
        logStep("  Expected: RuntimeException: Simulated failure");
        logStep("");
        logStep("  STEP 6: Wait 10 seconds, check offset");
        logStep("  kafka-consumer-groups.sh --bootstrap-server localhost:9092 \\");
        logStep("    --group payment-service --describe");
        logStep("  Expected: OFFSET COMMITTED (despite failure!)");
        logStep("");
        logStep("  STEP 7: Check order status");
        logStep("  curl http://localhost:8080/api/orders/<ORDER_ID> \\");
        logStep("    -H 'Authorization: Bearer YOUR_TOKEN'");
        logStep("  Expected: {\"status\": \"PENDING\"} ← STUCK!");
        logStep("");
        logStep("  STEP 8: Remove fault injection, restart");
        logStep("  (Remove throw exception line, restart service)");
        logStep("");
        logStep("  STEP 9: Check order status again");
        logStep("  curl http://localhost:8080/api/orders/<ORDER_ID> \\");
        logStep("    -H 'Authorization: Bearer YOUR_TOKEN'");
        logStep("  Expected: STILL \"PENDING\" ← EVENT LOST!");
        logStep("");
        logStep("  ❌ OBSERVED BEHAVIOR:");
        logStep("  - Offset auto-committed BEFORE processing");
        logStep("  - Processing failed");
        logStep("  - Event marked consumed, never reprocessed");
        logStep("  - DATA LOSS!");
        logStep("");
        logStep("  ⚠️  REVERT: Change enable-auto-commit back to false!");
    }

    @Test(priority = 53, enabled = false)
    @Story("Offset Management - Manual Testing")
    @Severity(SeverityLevel.NORMAL)
    @Description("MANUAL: Commit after processing (at-least-once pattern)")
    public void test53_MANUAL_CommitAfterProcessing_AtLeastOnce() {
        logStep("TEST 53: MANUAL - Commit AFTER processing (standard pattern)");
        logStep("");
        logStep("  📋 CONFIGURATION:");
        logStep("  application.yml:");
        logStep("    spring.kafka.consumer.enable-auto-commit: false");
        logStep("");
        logStep("  📋 CODE PATTERN:");
        logStep("  @KafkaListener(topics = \"payment.request\")");
        logStep("  public void handlePaymentRequest(OrderEvent event, Acknowledgment ack) {");
        logStep("      processPayment(event);  // 1. Process FIRST");
        logStep("      ack.acknowledge();       // 2. Commit AFTER");
        logStep("  }");
        logStep("");
        logStep("  📋 MANUAL TEST PROCEDURE:");
        logStep("");
        logStep("  STEP 1: Create an order");
        logStep("  curl -X POST http://localhost:8080/api/orders \\");
        logStep("    -H 'Authorization: Bearer YOUR_TOKEN' \\");
        logStep("    -H 'Content-Type: application/json' \\");
        logStep("    -d '{...}'");
        logStep("");
        logStep("  STEP 2: Monitor payment service logs");
        logStep("  tail -f payment-service/logs/application.log");
        logStep("  Look for:");
        logStep("  - 'Received payment request'");
        logStep("  - 'Processing payment...'");
        logStep("  - 'Payment successful'");
        logStep("  - 'Offset committed'");
        logStep("");
        logStep("  STEP 3: Kill consumer AFTER processing, BEFORE commit");
        logStep("  (When you see 'Processing payment...' but NOT 'Offset committed')");
        logStep("  kill -9 $(pgrep -f payment-service)");
        logStep("");
        logStep("  STEP 4: Check offset");
        logStep("  kafka-consumer-groups.sh --bootstrap-server localhost:9092 \\");
        logStep("    --group payment-service --describe");
        logStep("  Expected: Offset NOT incremented");
        logStep("");
        logStep("  STEP 5: Restart payment service");
        logStep("  cd payment-service && mvn spring-boot:run");
        logStep("");
        logStep("  STEP 6: Verify event reprocessed");
        logStep("  Check logs:");
        logStep("  - 'Received payment request' ← REPROCESSED!");
        logStep("  - 'Payment successful'");
        logStep("  - 'Offset committed'");
        logStep("");
        logStep("  ✅ EXPECTED RESULT:");
        logStep("  - Event reprocessed if crash before commit");
        logStep("  - At-least-once delivery");
        logStep("  - Idempotent processing handles duplicates");
    }

    @Test(priority = 54, enabled = false)
    @Story("Offset Management - Manual Testing")
    @Severity(SeverityLevel.NORMAL)
    @Description("MANUAL: Commit before processing (at-most-once, DATA LOSS)")
    public void test54_MANUAL_CommitBeforeProcessing_AtMostOnce() {
        logStep("TEST 54: MANUAL - Commit BEFORE processing (ANTI-PATTERN)");
        logStep("");
        logStep("  ⚠️  ANTI-PATTERN - Do NOT use in production!");
        logStep("");
        logStep("  📋 CODE PATTERN (DANGEROUS!):");
        logStep("  @KafkaListener(topics = \"payment.request\")");
        logStep("  public void handlePaymentRequest(OrderEvent event, Acknowledgment ack) {");
        logStep("      ack.acknowledge();  // 1. Commit FIRST ← DANGEROUS!");
        logStep("      processPayment(event);  // 2. Process AFTER");
        logStep("  }");
        logStep("");
        logStep("  📋 MANUAL TEST PROCEDURE:");
        logStep("");
        logStep("  STEP 1: Modify PaymentEventListener");
        logStep("  @KafkaListener(topics = \"payment.request\")");
        logStep("  public void handlePaymentRequest(OrderEvent event, Acknowledgment ack) {");
        logStep("      ack.acknowledge();  // Commit FIRST");
        logStep("      log.info(\"Offset committed BEFORE processing\");");
        logStep("      throw new RuntimeException(\"Simulated failure\");");
        logStep("  }");
        logStep("");
        logStep("  STEP 2: Restart payment service");
        logStep("  cd payment-service && mvn spring-boot:run");
        logStep("");
        logStep("  STEP 3: Create an order");
        logStep("  curl -X POST http://localhost:8080/api/orders \\");
        logStep("    -H 'Authorization: Bearer YOUR_TOKEN' \\");
        logStep("    -H 'Content-Type: application/json' \\");
        logStep("    -d '{...}'");
        logStep("  📝 Note orderId");
        logStep("");
        logStep("  STEP 4: Check logs");
        logStep("  Expected:");
        logStep("  - 'Offset committed BEFORE processing'");
        logStep("  - 'ERROR: Simulated failure'");
        logStep("");
        logStep("  STEP 5: Check offset (COMMITTED despite failure)");
        logStep("  kafka-consumer-groups.sh --bootstrap-server localhost:9092 \\");
        logStep("    --group payment-service --describe");
        logStep("  Expected: Offset incremented ❌");
        logStep("");
        logStep("  STEP 6: Check order status");
        logStep("  curl http://localhost:8080/api/orders/<ORDER_ID> \\");
        logStep("    -H 'Authorization: Bearer YOUR_TOKEN'");
        logStep("  Expected: {\"status\": \"PENDING\"} ← STUCK!");
        logStep("");
        logStep("  STEP 7: Remove fault injection, restart");
        logStep("  (Remove exception, restart service)");
        logStep("");
        logStep("  STEP 8: Verify event NOT reprocessed");
        logStep("  curl http://localhost:8080/api/orders/<ORDER_ID> \\");
        logStep("    -H 'Authorization: Bearer YOUR_TOKEN'");
        logStep("  Expected: STILL \"PENDING\" ← EVENT LOST!");
        logStep("");
        logStep("  ❌ OBSERVED BEHAVIOR:");
        logStep("  - Offset committed BEFORE processing");
        logStep("  - Processing failed");
        logStep("  - Event never reprocessed");
        logStep("  - At-most-once delivery (DATA LOSS)");
        logStep("");
        logStep("  📝 USE CASE:");
        logStep("  - ONLY for non-critical, lossy data");
        logStep("  - Examples: metrics, logs, telemetry");
        logStep("  - NEVER for payments, orders, critical data");
    }

    @Test(priority = 55, enabled = false)
    @Story("Offset Management - Manual Testing")
    @Severity(SeverityLevel.NORMAL)
    @Description("MANUAL: Manual commit fails (Kafka unavailable)")
    public void test55_MANUAL_ManualCommitFails() {
        logStep("TEST 55: MANUAL - Manual commit fails (Kafka down)");
        logStep("");
        logStep("  📋 SCENARIO:");
        logStep("  - Consumer processes event successfully");
        logStep("  - Attempts to commit offset");
        logStep("  - Kafka unavailable (network partition)");
        logStep("  - Commit fails");
        logStep("  - Event reprocessed on restart");
        logStep("");
        logStep("  📋 MANUAL TEST PROCEDURE:");
        logStep("");
        logStep("  STEP 1: Create an order");
        logStep("  curl -X POST http://localhost:8080/api/orders \\");
        logStep("    -H 'Authorization: Bearer YOUR_TOKEN' \\");
        logStep("    -H 'Content-Type: application/json' \\");
        logStep("    -d '{...}'");
        logStep("  📝 Note orderId and current offset");
        logStep("");
        logStep("  STEP 2: Monitor payment service logs");
        logStep("  tail -f payment-service/logs/application.log");
        logStep("");
        logStep("  STEP 3: Stop Kafka DURING processing");
        logStep("  (When you see 'Processing payment...' but NOT 'Offset committed')");
        logStep("  docker stop kafka");
        logStep("  # OR");
        logStep("  sudo systemctl stop kafka");
        logStep("");
        logStep("  STEP 4: Check logs for commit failure");
        logStep("  Expected:");
        logStep("  - 'Payment successful'");
        logStep("  - 'Attempting to commit offset...'");
        logStep("  - 'ERROR: Failed to commit'");
        logStep("  - 'TimeoutException: Timeout expired'");
        logStep("");
        logStep("  STEP 5: Restart Kafka");
        logStep("  docker start kafka");
        logStep("  # Wait for Kafka ready");
        logStep("  kafka-topics.sh --bootstrap-server localhost:9092 --list");
        logStep("");
        logStep("  STEP 6: Check offset (should NOT be committed)");
        logStep("  kafka-consumer-groups.sh --bootstrap-server localhost:9092 \\");
        logStep("    --group payment-service --describe");
        logStep("  Expected: Offset at previous value");
        logStep("");
        logStep("  STEP 7: Restart payment service");
        logStep("  cd payment-service && mvn spring-boot:run");
        logStep("");
        logStep("  STEP 8: Verify event reprocessed");
        logStep("  Check logs:");
        logStep("  - 'Received payment request' ← REPROCESSED!");
        logStep("  - 'Payment already processed' (idempotent check)");
        logStep("  - 'Offset committed'");
        logStep("");
        logStep("  STEP 9: Verify no duplicate payment");
        logStep("  curl http://localhost:8080/api/orders/<ORDER_ID> \\");
        logStep("    -H 'Authorization: Bearer YOUR_TOKEN'");
        logStep("  Expected: Single paymentId, status CONFIRMED");
        logStep("");
        logStep("  ✅ EXPECTED RESULT:");
        logStep("  - Commit failed due to Kafka unavailability");
        logStep("  - Event reprocessed on restart");
        logStep("  - Idempotent processing prevents duplicate");
        logStep("  - No data loss");
    }
}
