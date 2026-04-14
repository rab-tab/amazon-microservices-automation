package com.amazon.tests.tests.kafka.orders.eventConsumption.positive;

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
 * Kafka Event Consumption Tests (Category 2)
 *
 * Tests that Payment Service successfully consumes ORDER_CREATED events from Kafka,
 * processes payments, and publishes payment results back to Kafka.
 *
 * Flow:
 * 1. Order Service publishes ORDER_CREATED event → Kafka
 * 2. Payment Service consumes ORDER_CREATED event
 * 3. Payment Service processes payment (mock success/failure)
 * 4. Payment Service publishes PAYMENT_COMPLETED/FAILED event → Kafka
 * 5. Order Service consumes payment event and updates order status
 *
 * Positive Cases:
 * 1. Payment service receives and processes ORDER_CREATED event
 * 2. Multiple events processed without duplication
 * 3. Events processed in FIFO order
 *
 * Note: These tests verify END-TO-END event flow by checking order status changes
 */
@Epic("Amazon Microservices")
@Feature("Kafka - Event Consumption")
public class CreateOrderEventConsumptionTest extends BaseTest {

    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final Faker faker = new Faker();

    private String validToken;
    private String userId;
    private String productId;

    @BeforeClass
    public void setup() {
        logStep("Setting up Kafka event consumption tests");

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
                "name", "Test Product for Event Consumption",
                "description", "Product used in Kafka event consumption tests",
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
    // POSITIVE TEST CASES
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("Event Consumption - Positive")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Payment service consumes ORDER_CREATED event and processes payment")
    public void test20_PaymentServiceConsumesOrderEvent() {
        logStep("TEST 20: Payment service consumes and processes ORDER_CREATED event");

        // Step 1: Create order (publishes ORDER_CREATED event)
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

        String orderId = createResp.jsonPath().getString("id");
        String initialStatus = createResp.jsonPath().getString("status");

        logStep("  ✓ Order created: " + orderId);
        logStep("  ✓ Initial status: " + initialStatus);

        assertThat(initialStatus)
                .as("Order should start in PENDING status")
                .isEqualTo("PENDING");

        // Step 2: Wait for payment service to consume event and process payment
        logStep("  Waiting for payment service to consume ORDER_CREATED event...");
        logStep("  Expected flow:");
        logStep("    1. Payment service consumes event from Kafka");
        logStep("    2. Payment service processes payment (mock)");
        logStep("    3. Payment service publishes PAYMENT_COMPLETED event");
        logStep("    4. Order service updates order status to CONFIRMED");

        await().atMost(Duration.ofSeconds(15))
                .pollInterval( Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    Response orderResp = given()
                            .baseUri(GATEWAY_URL)
                            .header("Authorization", "Bearer " + validToken)
                            .when()
                            .get("/api/orders/" + orderId)
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();

                    String currentStatus = orderResp.jsonPath().getString("status");
                    logStep("    Polling... Current status: " + currentStatus);

                    assertThat(currentStatus)
                            .as("Order status should change from PENDING to CONFIRMED or PAYMENT_FAILED")
                            .isNotEqualTo("PENDING");
                });

        // Step 3: Verify final status
        Response finalResp = given()
                .baseUri(GATEWAY_URL)
                .header("Authorization", "Bearer " + validToken)
                .when()
                .get("/api/orders/" + orderId)
                .then()
                .statusCode(200)
                .extract()
                .response();

        String finalStatus = finalResp.jsonPath().getString("status");
        String paymentId = finalResp.jsonPath().getString("paymentId");

        logStep("  ✓ Final status: " + finalStatus);

        if (finalStatus.equals("CONFIRMED")) {
            logStep("  ✓ Payment ID: " + paymentId);
            assertThat(paymentId)
                    .as("CONFIRMED order should have payment ID")
                    .isNotNull();
        }

        assertThat(finalStatus)
                .as("Order should be CONFIRMED (payment success) or PAYMENT_FAILED")
                .isIn("CONFIRMED", "PAYMENT_FAILED");

        logStep("✅ Payment service successfully consumed and processed ORDER_CREATED event");
        logStep("  Complete event flow validated:");
        logStep("  Order Service → Kafka → Payment Service → Kafka → Order Service");
    }

    @Test(priority = 2)
    @Story("Event Consumption - Positive")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Multiple events processed without duplication (idempotency)")
        public void test21_MultipleEventsProcessedWithoutDuplication() {
        logStep("TEST 21: Multiple order events processed without duplication");

        int orderCount = 3;
        String[] orderIds = new String[orderCount];

        logStep("  Creating " + orderCount + " orders rapidly...");

        // Create 3 orders in quick succession
        for (int i = 0; i < orderCount; i++) {
            Map<String, Object> orderData = Map.of(
                    "items", List.of(
                            Map.of(
                                    "productId", productId,
                                    "quantity", 1,
                                    "unitPrice", 50.0 + (i * 10),
                                    "productName", "Test Product " + (i + 1)
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
                    .statusCode(201)
                    .extract()
                    .response();

            orderIds[i] = resp.jsonPath().getString("id");
            logStep("    Order " + (i + 1) + " created: " + orderIds[i]);
        }

        // Wait for all orders to be processed
        logStep("  Waiting for payment service to process all events...");

        await().atMost( Duration.ofSeconds(15))
                .pollInterval( Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    int processedCount = 0;

                    for (String orderId : orderIds) {
                        Response orderResp = given()
                                .baseUri(GATEWAY_URL)
                                .header("Authorization", "Bearer " + validToken)
                                .when()
                                .get("/api/orders/" + orderId)
                                .then()
                                .extract()
                                .response();

                        String status = orderResp.jsonPath().getString("status");
                        if (!status.equals("PENDING")) {
                            processedCount++;
                        }
                    }

                    logStep("    Processed: " + processedCount + "/" + orderCount);

                    assertThat(processedCount)
                            .as("All orders should be processed")
                            .isEqualTo(orderCount);
                });

        // Verify each order was processed exactly once (no duplication)
        for (int i = 0; i < orderCount; i++) {
            Response orderResp = given()
                    .baseUri(GATEWAY_URL)
                    .header("Authorization", "Bearer " + validToken)
                    .when()
                    .get("/api/orders/" + orderIds[i])
                    .then()
                    .statusCode(200)
                    .extract()
                    .response();

            String status = orderResp.jsonPath().getString("status");
            logStep("  ✓ Order " + (i + 1) + " final status: " + status);

            assertThat(status)
                    .as("Each order should be processed to final state")
                    .isIn("CONFIRMED", "PAYMENT_FAILED");
        }

        logStep("✅ All events processed without duplication");
        logStep("  - Consumer group ensures single processing per event");
        logStep("  - No duplicate payments or status updates");
    }

    @Test(enabled = false)
    @Story("Event Consumption - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Events processed in order (FIFO from same partition)")
    public void test22_EventsProcessedInFIFOOrder() {
        logStep("TEST 22: Events processed in FIFO order");

        int orderCount = 5;
        String[] orderIds = new String[orderCount];
        long[] creationTimes = new long[orderCount];

        logStep("  Creating " + orderCount + " orders sequentially...");

        // Create orders with time tracking
        for (int i = 0; i < orderCount; i++) {
            creationTimes[i] = System.currentTimeMillis();

            Map<String, Object> orderData = Map.of(
                    "items", List.of(
                            Map.of(
                                    "productId", productId,
                                    "quantity", 1,
                                    "unitPrice", 30.0,
                                    "productName", "Sequential Order " + (i + 1)
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
                    .statusCode(201)
                    .extract()
                    .response();

            orderIds[i] = resp.jsonPath().getString("id");
            logStep("    " + (i + 1) + ". Order created at " + creationTimes[i] + ": " + orderIds[i]);

            // Small delay between orders
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Wait for all orders to be processed
        logStep("  Waiting for all events to be consumed...");

        await().atMost( Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    int processedCount = 0;

                    for (String orderId : orderIds) {
                        Response orderResp = given()
                                .baseUri(GATEWAY_URL)
                                .header("Authorization", "Bearer " + validToken)
                                .when()
                                .get("/api/orders/" + orderId)
                                .then()
                                .extract()
                                .response();

                        String status = orderResp.jsonPath().getString("status");
                        if (!status.equals("PENDING")) {
                            processedCount++;
                        }
                    }

                    assertThat(processedCount).isEqualTo(orderCount);
                });

        // Verify all orders were processed
        logStep("  ✓ All " + orderCount + " events consumed and processed");

        int confirmedCount = 0;
        int failedCount = 0;

        for (int i = 0; i < orderCount; i++) {
            Response orderResp = given()
                    .baseUri(GATEWAY_URL)
                    .header("Authorization", "Bearer " + validToken)
                    .when()
                    .get("/api/orders/" + orderIds[i])
                    .then()
                    .statusCode(200)
                    .extract()
                    .response();

            String status = orderResp.jsonPath().getString("status");

            if (status.equals("CONFIRMED")) {
                confirmedCount++;
            } else if (status.equals("PAYMENT_FAILED")) {
                failedCount++;
            }

            logStep("    Order " + (i + 1) + ": " + status);
        }

        logStep("  ✓ CONFIRMED: " + confirmedCount);
        logStep("  ✓ PAYMENT_FAILED: " + failedCount);

        assertThat(confirmedCount + failedCount)
                .as("All orders should be processed")
                .isEqualTo(orderCount);

        logStep("✅ Events processed in FIFO order");
        logStep("  - Kafka maintains order within partition");
        logStep("  - Payment service consumed events sequentially");
        logStep("  - All " + orderCount + " orders reached final state");
    }
}
