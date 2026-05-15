package com.amazon.tests.kafka.saga;


import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.KafkaTestConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.response.Response;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
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
 * Tests timeout scenarios in Saga pattern:
 *
 * 1. Payment Service Timeout - No response from Payment Service
 * 2. Order remains in PENDING state
 * 3. No compensation occurs (waiting for manual intervention or retry)
 *
 * Real-world scenarios:
 * - Payment gateway down
 * - Payment Service crashed
 * - Network partition between services
 * - Kafka consumer lag/down
 *
 * Expected behavior:
 * - Order created successfully (status: PENDING)
 * - Payment request published to Kafka
 * - No payment result received (timeout simulation)
 * - Order remains PENDING (no automatic compensation)
 * - Manual intervention or retry mechanism needed
 *
 * @author Test Automation Team
 */
@Test(groups = {"saga", "timeout"})
public class SagaTimeoutTest extends BaseTest {

    private KafkaTestConsumer orderEventsConsumer;
    private KafkaTestConsumer paymentResultConsumer;
    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;
    private String BASE_URL;

    @BeforeClass
    public void setupClass() throws SeedingException {
        logStep("Setting up Saga Timeout tests");
        BASE_URL=context.getConfig().baseUrl();
        user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        userToken = context.getCached("user_token_" + user.getId(), String.class);
        product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        orderEventsConsumer = new KafkaTestConsumer("order.events");
        paymentResultConsumer = new KafkaTestConsumer("payment.result");

        logStep("✅ Saga timeout test setup complete");
    }

    @BeforeMethod
    public void beforeEachTest() {
        orderEventsConsumer.seekToEnd();
        paymentResultConsumer.seekToEnd();

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterClass
    public void cleanup() {
        if (orderEventsConsumer != null) {
            orderEventsConsumer.close();
        }
        if (paymentResultConsumer != null) {
            paymentResultConsumer.close();
        }
        logStep("✅ Saga timeout test consumers closed");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 1: PAYMENT TIMEOUT - No Response from Payment Service
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 1)
    public void test01_PaymentTimeout_NoResponseFromPaymentService() throws Exception {
        logStep("TEST 1: Payment timeout - No response from Payment Service");

        String idempotencyKey = UUID.randomUUID().toString();
        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 2)
                .build();

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Create Order with Timeout Scenario
        // Payment Service will NOT publish any response
        // ═══════════════════════════════════════════════════════════════
        logStep("  Creating order with payment timeout scenario...");

        Response createResponse = given()
                .contentType("application/json")
                .header("X-User-Id", user.getId().toString())
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Fault", "payment-timeout")  // Maps to TIMEOUT
                .body(orderRequest)
                .when()
                .post(BASE_URL + "/api/v1/orders");

        assertThat(createResponse.statusCode())
                .as("Order creation should succeed")
                .isEqualTo(201);

        String orderId = createResponse.jsonPath().getString("id");
        String initialStatus = createResponse.jsonPath().getString("status");

        logStep("  ✓ Order created: " + orderId);
        logStep("  ✓ Initial status: " + initialStatus);

        assertThat(initialStatus)
                .as("Order should start in PENDING status")
                .isEqualTo("PENDING");

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Verify ORDER_CREATED Event Published
        // ═══════════════════════════════════════════════════════════════
        logStep("  Verifying ORDER_CREATED event...");

        Optional<JsonNode> orderCreatedEvent = orderEventsConsumer.waitForMessage(
                node -> node.has("eventType") &&
                        "ORDER_CREATED".equals(node.get("eventType").asText()) &&
                        orderId.equals(node.get("orderId").asText()),
                10
        );

        assertThat(orderCreatedEvent)
                .as("ORDER_CREATED event should be published")
                .isPresent();

        logStep("  ✓ ORDER_CREATED event published to order.events");

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Verify NO Payment Result (Timeout)
        // Payment Service receives the request but doesn't respond
        // ═══════════════════════════════════════════════════════════════
        logStep("  Waiting for payment result (expecting timeout)...");

        Optional<JsonNode> paymentResult = paymentResultConsumer.waitForMessage(
                node -> orderId.equals(node.get("orderId").asText()),
                15  // Wait 15 seconds - should timeout
        );

        assertThat(paymentResult)
                .as("Payment result should NOT be published (timeout scenario)")
                .isEmpty();

        logStep("  ✓ No payment result received (timeout simulated)");

        // ═══════════════════════════════════════════════════════════════
        // STEP 4: Verify Order Remains in PENDING State
        // No automatic compensation - manual intervention needed
        // ═══════════════════════════════════════════════════════════════
        logStep("  Verifying order remains in PENDING state...");

        // Wait a bit to ensure no delayed response
        Thread.sleep(5000);

        Response currentOrder = given()
                .header("X-User-Id", user.getId().toString())
                .when()
                .get(BASE_URL + "/api/v1/orders/" + orderId);

        String currentStatus = currentOrder.jsonPath().getString("status");

        assertThat(currentStatus)
                .as("Order should remain in PENDING state (no compensation without response)")
                .isEqualTo("PENDING");

        assertThat(currentOrder.jsonPath().getString("paymentId"))
                .as("No payment ID should be assigned")
                .isNull();

        assertThat(currentOrder.jsonPath().getString("paymentFailureReason"))
                .as("No failure reason should be present")
                .isNull();

        logStep("  ✓ Order remains in PENDING state (awaiting payment response)");
        logStep("  ⚠️  Manual intervention or retry mechanism would be needed in production");

        logStep("  ✅ Timeout scenario complete - Order stuck in PENDING, no auto-compensation");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 2: DELAYED RESPONSE - Late Payment Response After Timeout
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 2)
    public void test02_DelayedResponse_OrderStillInPending() throws Exception {
        logStep("TEST 2: Delayed response - Order remains PENDING during wait");

        String idempotencyKey = UUID.randomUUID().toString();
        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // ═══════════════════════════════════════════════════════════════
        // Create order with timeout scenario
        // ═══════════════════════════════════════════════════════════════
        Response createResponse = given()
                .contentType("application/json")
                .header("X-User-Id", user.getId().toString())
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Fault", "payment-timeout")
                .body(orderRequest)
                .when()
                .post(BASE_URL + "/api/v1/orders");

        String orderId = createResponse.jsonPath().getString("id");
        logStep("  ✓ Order created: " + orderId);

        // ═══════════════════════════════════════════════════════════════
        // Poll order status multiple times - should remain PENDING
        // ═══════════════════════════════════════════════════════════════
        logStep("  Polling order status over 10 seconds...");

        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);

            Response currentOrder = given()
                    .header("X-User-Id", user.getId().toString())
                    .when()
                    .get(BASE_URL + "/api/v1/orders/" + orderId);

            String status = currentOrder.jsonPath().getString("status");

            logStep("    Poll " + (i + 1) + "/10: Status = " + status);

            assertThat(status)
                    .as("Order should remain PENDING throughout polling")
                    .isEqualTo("PENDING");
        }

        logStep("  ✅ Order remained in PENDING state for entire wait period");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 3: VERIFY ORDER QUERYABLE - Timeout Doesn't Break Order Retrieval
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 3)
    public void test03_TimeoutOrder_StillQueryable() throws Exception {
        logStep("TEST 3: Timeout order - Still queryable via API");

        String idempotencyKey = UUID.randomUUID().toString();
        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // ═══════════════════════════════════════════════════════════════
        // Create order with timeout
        // ═══════════════════════════════════════════════════════════════
        Response createResponse = given()
                .contentType("application/json")
                .header("X-User-Id", user.getId().toString())
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Fault", "payment-timeout")
                .body(orderRequest)
                .when()
                .post(BASE_URL + "/api/v1/orders");

        String orderId = createResponse.jsonPath().getString("id");
        logStep("  ✓ Order created: " + orderId);

        Thread.sleep(5000);  // Wait for timeout

        // ═══════════════════════════════════════════════════════════════
        // Verify order is queryable and has correct data
        // ═══════════════════════════════════════════════════════════════
        Response queriedOrder = given()
                .header("X-User-Id", user.getId().toString())
                .when()
                .get(BASE_URL + "/api/v1/orders/" + orderId);

        assertThat(queriedOrder.statusCode())
                .as("Order should be queryable")
                .isEqualTo(200);

        assertThat(queriedOrder.jsonPath().getString("id"))
                .as("Order ID should match")
                .isEqualTo(orderId);

        assertThat(queriedOrder.jsonPath().getString("status"))
                .as("Order status should be PENDING")
                .isEqualTo("PENDING");

        // Verify order appears in user's order list
        Response userOrders = given()
                .header("X-User-Id", user.getId().toString())
                .when()
                .get(BASE_URL + "/api/v1/orders");

        assertThat(userOrders.jsonPath().getList("orders.id"))
                .as("Order should appear in user's order list")
                .contains(orderId);

        logStep("  ✅ Timeout order is fully queryable and appears in order list");
    }
}
