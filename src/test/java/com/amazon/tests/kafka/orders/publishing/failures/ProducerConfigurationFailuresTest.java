package com.amazon.tests.kafka.orders.publishing.failures;


import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Producer Configuration & Limit Failures - Fast Tests
 *
 * Strategy: Header injection (fast simulation)
 *
 * Tests:
 * - Acknowledgment failures (insufficient ISR)
 * - Message size limits
 * - Buffer overflow
 * - Producer quotas
 * - Batch size limits
 * - Compression failures
 *
 * Run frequency: Every commit
 * Execution time: ~5 seconds
 */
@Slf4j
@Epic("Kafka Producer")
@Feature("Configuration & Limit Failures")
public class ProducerConfigurationFailuresTest extends BaseTest {

    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;

    @BeforeMethod
    public void setup() throws SeedingException {
        user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        userToken = context.getCached("user_token_" + user.getId(), String.class);
        product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();
        waitForDataPropagation(1000);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ACKNOWLEDGMENT FAILURES
    // ══════════════════════════════════════════════════════════════════════════
    //PASS
    @Test(description = "Acknowledgment failure - insufficient in-sync replicas")
    @Story("Acknowledgment Failures")
    @Severity(SeverityLevel.CRITICAL)
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

    // ══════════════════════════════════════════════════════════════════════════
    // SIZE & LIMIT FAILURES
    // ══════════════════════════════════════════════════════════════════════════

    @Test(description = "Message too large - exceeds max.message.bytes")
    @Story("Size Limits")
    @Severity(SeverityLevel.NORMAL)
    public void test06_MessageTooLarge_OrderCreationFails() throws Exception {
        logStep("TEST: Message exceeds size limit");

        Response response = sendOrderRequestWithFault("message-too-large");

        assertThat(response.statusCode())
                .as("Order creation should fail when message too large")
                .isEqualTo(500);

        assertThat(response.asString())
                .as("Error message should indicate message size limit")
                .contains("Simulated message too large - event exceeds max.message.bytes");

        logStep("✅ Message size limit enforced correctly");
    }

    @Test(description = "Producer buffer full - backpressure")
    @Story("Buffer Limits")
    @Severity(SeverityLevel.NORMAL)
    public void test07_BufferFull_OrderCreationFails() throws Exception {
        logStep("TEST: Producer buffer overflow");

        Response response = sendOrderRequestWithFault("buffer-full");

        assertThat(response.statusCode())
                .as("Order creation should fail when buffer is full")
                .isEqualTo(500);

        assertThat(response.asString())
                .as("Error message should indicate buffer overflow")
                .contains("Simulated buffer full - producer buffer overflow");

        logStep("✅ Buffer overflow handled correctly");
    }

    @Test(description = "Producer quota exceeded - throttled")
    @Story("Quota Limits")
    @Severity(SeverityLevel.NORMAL)
    public void test08_ProducerQuotaExceeded_Throttled() throws Exception {
        logStep("TEST: Producer quota exceeded");

        Response response = sendOrderRequestWithFault("quota-exceeded");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.jsonPath().getString("message"))
                .containsAnyOf("quota", "throttled");

        logStep("✅ Quota enforcement simulated");
    }

    @Test(description = "Record batch too large - exceeds max.request.size")
    @Story("Batch Limits")
    @Severity(SeverityLevel.NORMAL)
    public void test17_RecordBatchTooLarge_Fails() throws Exception {
        logStep("TEST: Record batch too large");

        Response response = sendOrderRequestWithFault("batch-too-large");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.jsonPath().getString("message"))
                .containsAnyOf("batch", "too large");

        logStep("✅ Batch size limit enforced");
    }

    @Test(description = "Compression failure")
    @Story("Compression")
    @Severity(SeverityLevel.MINOR)
    public void test19_CompressionFailure_Fails() throws Exception {
        logStep("TEST: Compression failure");

        Response response = sendOrderRequestWithFault("compression-error");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.jsonPath().getString("message"))
                .contains("compression");

        logStep("✅ Compression failure handled");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPER
    // ══════════════════════════════════════════════════════════════════════════

    private Response sendOrderRequestWithFault(String faultType) throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

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
}
