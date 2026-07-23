package com.amazon.tests.regression.kafka.saga;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Saga Timeout Scenarios - Async Timeout Handling
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Tests timeout scenario in Saga pattern when Payment Service doesn't respond.
 *
 * REAL-WORLD SCENARIOS:
 * - Payment gateway down
 * - Payment Service crashed
 * - Network partition between services
 * - Kafka consumer lag/down
 *
 * EXPECTED BEHAVIOR:
 * - Order created successfully (status: PENDING)
 * - ORDER_CREATED event published to Kafka
 * - NO payment result received (timeout simulation)
 * - Order REMAINS in PENDING state (no automatic compensation)
 * - Order stays queryable throughout
 * - Manual intervention or retry mechanism needed in production
 *
 * @author Test Automation Team
 */
@Slf4j
@Epic("Kafka Saga Pattern")
@Feature("Saga Timeout Handling")
@Test(groups = {"saga", "timeout"})
public class SagaTimeoutTest extends BaseTest {

    private KafkaTestConsumer orderEventsConsumer;
    private KafkaTestConsumer paymentResultConsumer;
    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;

    @BeforeMethod
    public void setupClass() throws SeedingException {
        logStep("Setting up Saga Timeout test");

        user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        userToken = context.getCached("user_token_" + user.getId(), String.class);
        product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        orderEventsConsumer = new KafkaTestConsumer("order.events");
        paymentResultConsumer = new KafkaTestConsumer("payment.result");

        logStep("✅ Saga timeout test setup complete");
    }

    @AfterClass
    public void cleanup() {
        if (orderEventsConsumer != null) {
            orderEventsConsumer.close();
        }
        if (paymentResultConsumer != null) {
            paymentResultConsumer.close();
        }
        logStep("✅ Saga timeout test cleanup complete");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // COMPREHENSIVE TIMEOUT TEST
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Payment Timeout")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Order remains PENDING when Payment Service doesn't respond, stays queryable, no auto-compensation")
    public void testPaymentTimeout_NoResponseFromPaymentService() throws Exception {
        logStep("TEST: Payment timeout - No response from Payment Service");

        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();
        String idempotencyKey = UUID.randomUUID().toString();

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Create Order with Timeout Scenario
        // Payment Service will NOT publish any response
        // ═══════════════════════════════════════════════════════════════
        logStep("  Creating order with payment timeout scenario...");

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 2)
                .build();

        Response createResponse = given()
                .contentType("application/json")
                .header("X-User-Id", user.getId().toString())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Fault", "payment-timeout")  // Simulates timeout
                .body(orderRequest)
                .when()
                .post(context.getConfig().baseUrl() + "/api/orders");

        assertThat(createResponse.statusCode())
                .as("Order creation should succeed")
                .isEqualTo(201);

        String orderId = createResponse.jsonPath().getString("id");
        String initialStatus = createResponse.jsonPath().getString("status");

        logStep("  ✓ Order created: {}", orderId);
        logStep("  ✓ Initial status: {}", initialStatus);

        assertThat(initialStatus)
                .as("Order should start in PENDING status")
                .isEqualTo("PENDING");

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Verify ORDER_CREATED Event Published
        // ═══════════════════════════════════════════════════════════════
        logStep("  Verifying ORDER_CREATED event published to Kafka...");

        Optional<JsonNode> orderCreatedEvent = orderEventsConsumer.waitForMessage(
                node -> node.has("eventType") &&
                        "ORDER_CREATED".equals(node.get("eventType").asText()) &&
                        orderId.equals(node.get("orderId").asText()),
                10
        );

        assertThat(orderCreatedEvent)
                .as("ORDER_CREATED event should be published to order.events")
                .isPresent();

        logStep("  ✓ ORDER_CREATED event published successfully");

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Verify NO Payment Result (Timeout)
        // Payment Service receives the request but doesn't respond
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for payment result (expecting timeout)...");

        Optional<JsonNode> paymentResult = paymentResultConsumer.waitForMessage(
                node -> node.has("orderId") &&
                        orderId.equals(node.get("orderId").asText()),
                15  // Wait 15 seconds - should timeout with no result
        );

        assertThat(paymentResult)
                .as("Payment result should NOT be published (timeout scenario)")
                .isEmpty();

        logStep("  ✓ No payment result received (timeout confirmed)");

        // ═══════════════════════════════════════════════════════════════
        // STEP 4: Poll Order Status Over Time - Verify Remains PENDING
        // Simulates monitoring an order that's "stuck" waiting for payment
        // ═══════════════════════════════════════════════════════════════
        logStep("  Polling order status over 10 seconds to verify it remains PENDING...");

        int pollCount = 10;
        int pollIntervalMs = 1000;

        for (int i = 0; i < pollCount; i++) {
            Thread.sleep(pollIntervalMs);

            Response currentOrder = given()
                    .header("X-User-Id", user.getId().toString())
                    .header("Authorization", "Bearer " + userToken)
                    .when()
                    .get(context.getConfig().baseUrl() + "/api/orders/" + orderId);

            String currentStatus = currentOrder.jsonPath().getString("status");

            logStep("    Poll {}/{}: Status = {}", i + 1, pollCount, currentStatus);

            // Verify order remains PENDING (no auto-compensation)
            assertThat(currentStatus)
                    .as("Order should remain PENDING throughout polling period")
                    .isEqualTo("PENDING");

            // Verify no payment-related fields are set
            assertThat(currentOrder.jsonPath().getString("paymentId"))
                    .as("No payment ID should be assigned")
                    .isNull();

            assertThat(currentOrder.jsonPath().getString("paymentFailureReason"))
                    .as("No failure reason should be present")
                    .isNull();
        }

        logStep("  ✓ Order remained in PENDING state for entire {} second period", pollCount);

        // ═══════════════════════════════════════════════════════════════
        // STEP 5: Verify Order Queryability
        // Ensure timeout doesn't break order retrieval or listing
        // ═══════════════════════════════════════════════════════════════
        logStep("  Verifying order queryability...");

        // Query by ID
        Response queriedOrder = given()
                .header("X-User-Id", user.getId().toString())
                .header("Authorization", "Bearer " + userToken)
                .when()
                .get(context.getConfig().baseUrl() + "/api/orders/" + orderId);

        assertThat(queriedOrder.statusCode())
                .as("Order should be queryable by ID")
                .isEqualTo(200);

        assertThat(queriedOrder.jsonPath().getString("id"))
                .as("Order ID should match")
                .isEqualTo(orderId);

        assertThat(queriedOrder.jsonPath().getString("status"))
                .as("Final status check - should still be PENDING")
                .isEqualTo("PENDING");

        logStep("  ✓ Order queryable by ID");

        // Query in order list
        Response userOrders = given()
                .header("X-User-Id", user.getId().toString())
                .header("Authorization", "Bearer " + userToken)
                .when()
                .get(context.getConfig().baseUrl() + "/api/orders");

        assertThat(userOrders.statusCode())
                .as("Order list should be queryable")
                .isEqualTo(200);

        assertThat(userOrders.jsonPath().getList("orders.id"))
                .as("Order should appear in user's order list")
                .contains(orderId);

        logStep("  ✓ Order appears in user's order list");

        // ═══════════════════════════════════════════════════════════════
        // STEP 6: Summary
        // ═══════════════════════════════════════════════════════════════
        logStep("✅ TIMEOUT TEST COMPLETE - All validations passed:");
        logStep("   1. ✅ Order created successfully");
        logStep("   2. ✅ ORDER_CREATED event published to Kafka");
        logStep("   3. ✅ NO payment result received (timeout confirmed)");
        logStep("   4. ✅ Order remained PENDING for {} seconds (no auto-compensation)", pollCount);
        logStep("   5. ✅ Order queryable by ID and appears in order list");
        logStep("");
        logStep("⚠️  PRODUCTION NOTE:");
        logStep("   This order would require manual intervention or a retry mechanism.");
        logStep("   Possible actions:");
        logStep("   - Retry payment processing");
        logStep("   - Cancel order after timeout threshold");
        logStep("   - Alert operations team for investigation");
    }
}