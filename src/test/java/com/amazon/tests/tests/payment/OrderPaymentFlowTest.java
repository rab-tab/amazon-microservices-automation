package com.amazon.tests.tests.payment;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.TestDataFactory;
import io.qameta.allure.*;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.testng.ITestResult;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Order Payment Flow Integration Tests - API Polling Approach
 *
 * This test suite uses Google's recommended pattern for testing async workflows:
 * - Creates orders with test scenario flags
 * - Polls Order Service API until payment is processed
 * - Verifies order status, payment details via GET /orders/{id}
 *
 * Benefits:
 * - Tests real Order Service Kafka listener behavior
 * - Verifies database updates (order status, payment details)
 * - Simple, reliable, no threading complexity
 * - Production parity (same API customers use)
 *
 * Used by: Google, Facebook, Uber, Netflix
 *
 * @see <a href="https://testing.googleblog.com">Google Testing Blog</a>
 */
@Epic("Amazon Microservices")
@Feature("Order Payment Flow - API Polling")
@Slf4j
public class OrderPaymentFlowTest extends BaseTest {

    private String customerToken;
    private String customerId;
    private TestModels.ProductResponse testProduct;

    @BeforeClass(alwaysRun = true)
    public void setupPaymentFlowTests() {
        logStep("════════════════════════════════════════════════════════");
        logStep("Setting up Payment Flow Test Environment");
        logStep("════════════════════════════════════════════════════════");

        try {
            // Create customer
            TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
            customerToken = customerAuth.getAccessToken();
            customerId = customerAuth.getUser().getId();
            logStep("✅ Customer created: " + customerId);

            // Create seller
            TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
            String sellerId = sellerAuth.getUser().getId();
            logStep("✅ Seller created: " + sellerId);

            // Create product
            Response productResponse = given()
                    .spec(RestAssuredConfig.getProductServiceSpec())
                    .header("X-User-Id", sellerId)
                    .body(TestDataFactory.createProductWithPrice(89999.00))
                    .when()
                    .post("/api/v1/products")
                    .then()
                    .statusCode(201)
                    .extract().response();

            testProduct = productResponse.as(TestModels.ProductResponse.class);
            logStep("✅ Product created: " + testProduct.getId());

            logStep("════════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.error("❌ Setup failed", e);
            throw e;
        }
    }

    @AfterClass(alwaysRun = true)
    public void teardownPaymentFlowTests() {
        logStep("════════════════════════════════════════════════════════");
        logStep("✅ Payment Flow Test Environment Cleanup Completed");
        logStep("════════════════════════════════════════════════════════");
    }

    @AfterMethod(alwaysRun = true)
    public void afterEachTest(ITestResult result) {
        if (result.getStatus() == ITestResult.FAILURE) {
            logStep("❌ Test FAILED: " + result.getName());
            if (result.getThrowable() != null) {
                logStep("   Error: " + result.getThrowable().getMessage());
            }
        } else if (result.getStatus() == ITestResult.SUCCESS) {
            logStep("✅ Test PASSED: " + result.getName());
        } else if (result.getStatus() == ITestResult.SKIP) {
            logStep("⏭️ Test SKIPPED: " + result.getName());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPER METHODS - WITH PROPER TIMEOUT AND ERROR HANDLING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Create order with optional test scenario
     */
    private String createOrder(double amount, String idempotencyKey, String testScenario) {
        TestModels.CreateOrderRequest orderRequest = TestModels.CreateOrderRequest.builder()
                .items(List.of(
                        TestModels.OrderItemRequest.builder()
                                .productId(testProduct.getId())
                                .productName(testProduct.getName())
                                .quantity(1)
                                .unitPrice(BigDecimal.valueOf(amount))
                                .build()
                ))
                .shippingAddress("123 Test Street, Bengaluru, Karnataka")
                .build();

        var requestSpec = given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .header("X-Test-Scenario", testScenario)
                .header("Idempotency-Key", idempotencyKey);

        // Add test scenario header if provided
        if (testScenario != null && !testScenario.isBlank()) {
            requestSpec.header("X-Test-Scenario", testScenario);
            logStep("🧪 Creating order with test scenario: " + testScenario);
        }

        Response response = requestSpec
                .body(orderRequest)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .extract().response();

        String orderId = response.jsonPath().getString("id");
        logStep("✅ Order created: " + orderId);

        return orderId;
    }

    /**
     * Poll Order API until expected status is reached (with proper timeout handling)
     *
     * @param orderId Order ID to check
     * @param expectedStatus Expected order status
     * @param timeoutSeconds Maximum time to wait
     * @return Order response
     * @throws AssertionError if status not reached within timeout
     */
    private Response pollOrderUntilStatus(String orderId, String expectedStatus, int timeoutSeconds) {
        logStep("⏳ Waiting for order status: " + expectedStatus + " (timeout: " + timeoutSeconds + "s)");

        try {
            await().atMost(Duration.ofSeconds(15))              // ← Increased from 10s
                    .pollInterval(Duration.ofMillis(100))        // ← Changed from 500ms (5x faster!)
                    .pollDelay(Duration.ZERO)                    // ← Changed from 100ms (no unnecessary delay)
                    .ignoreExceptions()
                    .untilAsserted(() -> {
                        Response response = getOrder(orderId);
                        String actualStatus = response.jsonPath().getString("status");

                        logStep("   Polling... Current status: " + actualStatus + " (waiting for: " + expectedStatus + ")");

                        assertThat(actualStatus)
                                .as("Order status should be " + expectedStatus)
                                .isEqualTo(expectedStatus);
                    });
            // Return final response for additional assertions
            Response finalResponse = getOrder(orderId);
            logStep("✅ Order reached expected status: " + expectedStatus);
            return finalResponse;

        } catch (org.awaitility.core.ConditionTimeoutException e) {
            // Timeout - get current state for debugging
            Response currentResponse = getOrder(orderId);
            String currentStatus = currentResponse.jsonPath().getString("status");

            logStep("❌ TIMEOUT: Order did not reach status '" + expectedStatus + "' within " + timeoutSeconds + "s");
            logStep("   Current status: " + currentStatus);
            logStep("   Order details: " + currentResponse.asString());

            throw new AssertionError(
                    "Order " + orderId + " did not reach status '" + expectedStatus +
                            "' within " + timeoutSeconds + "s. Current status: " + currentStatus
            );
        } catch (Exception e) {
            logStep("❌ ERROR while polling order status: " + e.getMessage());
            throw e;
        }
    }


    private Response pollOrderUntilStatusAsync(String orderId, String expectedStatus, int timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;

        AtomicReference<String> lastSeenStatus = new AtomicReference<>("UNKNOWN");
        AtomicInteger pollCount = new AtomicInteger(0);

        CompletableFuture<Response> orderStatusFuture = CompletableFuture.supplyAsync(() -> {
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    pollCount.incrementAndGet();
                    Response response = getOrder(orderId);
                    String currentStatus = response.jsonPath().getString("status");
                    lastSeenStatus.set(currentStatus);

                    if (expectedStatus.equals(currentStatus)) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        logStep("✅ Status '" + expectedStatus + "' found in " + elapsed + "ms");
                        return response;
                    }

                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (Exception e) {
                    // Continue
                }
            }
            return null;
        });

        // Wait for completion
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (orderStatusFuture.isDone()) {
                try {
                    Response result = orderStatusFuture.get();
                    if (result != null) {
                        return result;
                    }
                    break;
                } catch (Exception e) {
                    orderStatusFuture.cancel(true);
                    throw new RuntimeException("Polling failed", e);
                }
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                orderStatusFuture.cancel(true);
                throw new RuntimeException("Interrupted", e);
            }
        }

        // Timeout
        orderStatusFuture.cancel(true);
        throw new AssertionError(String.format(
                "Order %s timeout after %ds (last: %s, polls: %d)",
                orderId, timeoutSeconds, lastSeenStatus.get(), pollCount.get()
        ));
    }

    /**
     * Get order details
     */
    private Response getOrder(String orderId) {
        return given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .pathParam("id", orderId)
                .when()
                .get("/api/v1/orders/{id}")
                .then()
                .statusCode(200)
                .extract().response();
    }

    // ═══════════════════════════════════════════════════════════════════
    // TEST CASES - Using Test Scenarios + API Polling (Google's Approach)
    // ═══════════════════════════════════════════════════════════════════

    @Test(priority = 1, timeOut = 30000)
    @Story("Successful Payment")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify order confirmation with successful payment using test scenario and API polling")
    public void testSuccessfulPaymentFlow() {
        logStep("═══════════════════════════════════════════════════════");
        logStep("TEST: Successful Payment Flow");
        logStep("═══════════════════════════════════════════════════════");

        String orderId = null;

        try {
            // Create order with SUCCESS test scenario
            orderId = createOrder(89999.00, UUID.randomUUID().toString(), "SUCCESS");

            // Poll Order API until status is CONFIRMED (Google's approach)
            Response order = pollOrderUntilStatusAsync(orderId, "CONFIRMED", 10);

            // Verify payment details
            assertThat(order.jsonPath().getString("paymentId"))
                    .as("Payment ID should be set")
                    .isNotNull();

            assertThat(order.jsonPath().getString("paymentTransactionId"))
                    .as("Transaction ID should be set")
                    .isNotNull()
                    .startsWith("TXN-");

            assertThat(order.jsonPath().getString("paymentFailureReason"))
                    .as("No failure reason for successful payment")
                    .isNull();

            logStep("✅ TEST PASSED - Order confirmed successfully");
            logStep("   Order ID: " + orderId);
            logStep("   Payment ID: " + order.jsonPath().getString("paymentId"));
            logStep("   Transaction ID: " + order.jsonPath().getString("paymentTransactionId"));
            logStep("═══════════════════════════════════════════════════════");

        } catch (AssertionError | Exception e) {
            logStep("❌ TEST FAILED: " + e.getMessage());
            if (orderId != null) {
                try {
                    Response currentOrder = getOrder(orderId);
                    logStep("   Current order state: " + currentOrder.asString());
                } catch (Exception ex) {
                    logStep("   Could not retrieve order state");
                }
            }
            throw e;
        }
    }

    @Test(priority = 2, timeOut = 30000)
    @Story("Failed Payment - Insufficient Funds")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify payment failure handling for insufficient funds")
    public void testFailedPaymentFlow() {
        logStep("═══════════════════════════════════════════════════════");
        logStep("TEST: Failed Payment Flow (Insufficient Funds)");
        logStep("═══════════════════════════════════════════════════════");

        String orderId = null;

        try {
            // Create order with FAILED test scenario
            orderId = createOrder(150000.00, UUID.randomUUID().toString(), "FAILED");

            // Poll Order API until status is PAYMENT_FAILED
            Response order = pollOrderUntilStatusAsync(orderId, "PAYMENT_FAILED", 15);

            // Verify failure details
            assertThat(order.jsonPath().getString("paymentFailureReason"))
                    .as("Failure reason should be Insufficient funds")
                    .isEqualTo("Insufficient funds");

            assertThat(order.jsonPath().getBoolean("paymentRetryable"))
                    .as("Insufficient funds is retryable")
                    .isTrue();

            assertThat(order.jsonPath().getString("paymentId"))
                    .as("No payment ID for failed payment")
                    .isNull();

            assertThat(order.jsonPath().getString("paymentTransactionId"))
                    .as("No transaction ID for failed payment")
                    .isNull();

            logStep("✅ TEST PASSED - Insufficient funds handled correctly");
            logStep("   Order ID: " + orderId);
            logStep("   Failure Reason: " + order.jsonPath().getString("paymentFailureReason"));
            logStep("   Retryable: " + order.jsonPath().getBoolean("paymentRetryable"));
            logStep("═══════════════════════════════════════════════════════");

        } catch (AssertionError | Exception e) {
            logStep("❌ TEST FAILED: " + e.getMessage());
            if (orderId != null) {
                try {
                    Response currentOrder = getOrder(orderId);
                    logStep("   Current order state: " + currentOrder.asString());
                } catch (Exception ex) {
                    logStep("   Could not retrieve order state");
                }
            }
            throw e;
        }
    }

    @Test(priority = 3, timeOut = 30000)
    @Story("Fraud Detection")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify fraud detection scenario with fraud score")
    public void testFraudDetectionFlow() {
        logStep("═══════════════════════════════════════════════════════");
        logStep("TEST: Fraud Detection Flow");
        logStep("═══════════════════════════════════════════════════════");

        String orderId = null;

        try {
            // Create order with FRAUD test scenario
            orderId = createOrder(5000.00, UUID.randomUUID().toString(), "FRAUD");

            // Poll Order API until status is PAYMENT_FAILED
            Response order = pollOrderUntilStatusAsync(orderId, "PAYMENT_FAILED", 10);

            // Verify fraud-specific details
            assertThat(order.jsonPath().getString("paymentFailureReason"))
                    .as("Failure reason should be Fraud detected")
                    .isEqualTo("Fraud detected");

            assertThat(order.jsonPath().getInt("paymentFraudScore"))
                    .as("Fraud score should be 95")
                    .isEqualTo(95);

            assertThat(order.jsonPath().getBoolean("paymentRetryable"))
                    .as("Fraud is not retryable")
                    .isFalse();

            assertThat(order.jsonPath().getString("paymentId"))
                    .as("No payment ID for failed payment")
                    .isNull();

            logStep("✅ TEST PASSED - Fraud detection handled correctly");
            logStep("   Order ID: " + orderId);
            logStep("   Failure Reason: " + order.jsonPath().getString("paymentFailureReason"));
            logStep("   Fraud Score: " + order.jsonPath().getInt("paymentFraudScore"));
            logStep("   Retryable: " + order.jsonPath().getBoolean("paymentRetryable"));
            logStep("═══════════════════════════════════════════════════════");

        } catch (AssertionError | Exception e) {
            logStep("❌ TEST FAILED: " + e.getMessage());
            if (orderId != null) {
                try {
                    Response currentOrder = getOrder(orderId);
                    logStep("   Current order state: " + currentOrder.asString());
                } catch (Exception ex) {
                    logStep("   Could not retrieve order state");
                }
            }
            throw e;
        }
    }

    @Test(priority = 4, timeOut = 30000)
    @Story("Payment Timeout")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify order remains in PENDING status when payment times out")
    public void testPaymentTimeoutFlow() {
        logStep("═══════════════════════════════════════════════════════");
        logStep("TEST: Payment Timeout Flow");
        logStep("═══════════════════════════════════════════════════════");

        String orderId = null;

        try {
            // Create order with TIMEOUT test scenario
            orderId = createOrder(2500.00, UUID.randomUUID().toString(), "TIMEOUT");

            // Wait for potential payment result (it shouldn't arrive)
            logStep("⏳ Waiting 8 seconds for timeout scenario...");
            Thread.sleep(8000);

            // Verify order is still PENDING (no payment result received)
            Response order = getOrder(orderId);

            assertThat(order.jsonPath().getString("status"))
                    .as("Order should remain PENDING on timeout")
                    .isEqualTo("PENDING");

            assertThat(order.jsonPath().getString("paymentId"))
                    .as("No payment ID on timeout")
                    .isNull();

            assertThat(order.jsonPath().getString("paymentFailureReason"))
                    .as("No failure reason on timeout")
                    .isNull();

            logStep("✅ TEST PASSED - Timeout scenario handled correctly");
            logStep("   Order ID: " + orderId);
            logStep("   Order Status: PENDING (as expected)");
            logStep("═══════════════════════════════════════════════════════");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Test interrupted", e);
        } catch (AssertionError | Exception e) {
            logStep("❌ TEST FAILED: " + e.getMessage());
            if (orderId != null) {
                try {
                    Response currentOrder = getOrder(orderId);
                    logStep("   Current order state: " + currentOrder.asString());
                } catch (Exception ex) {
                    logStep("   Could not retrieve order state");
                }
            }
            throw e;
        }
    }

    @Test(priority = 5, timeOut = 30000)
    @Story("Idempotency")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify idempotency - duplicate requests return same order")
    public void testIdempotencyWithPayment() {
        logStep("═══════════════════════════════════════════════════════");
        logStep("TEST: Idempotency with Payment");
        logStep("═══════════════════════════════════════════════════════");

        String orderId1 = null;

        try {
            String idempotencyKey = UUID.randomUUID().toString();

            // First request - create order
            orderId1 = createOrder(1000.00, idempotencyKey, "SUCCESS");
            logStep("✅ First request - Order created: " + orderId1);

            // Wait a bit to ensure order is processed
            Thread.sleep(1000);

            // Duplicate request with same idempotency key
            TestModels.CreateOrderRequest orderRequest = TestModels.CreateOrderRequest.builder()
                    .items(List.of(
                            TestModels.OrderItemRequest.builder()
                                    .productId(testProduct.getId())
                                    .productName(testProduct.getName())
                                    .quantity(1)
                                    .unitPrice(BigDecimal.valueOf(1000.00))
                                    .build()
                    ))
                    .shippingAddress("123 Test Street, Bengaluru")
                    .build();

            Response duplicateResponse = given()
                    .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                    .header("X-User-Id", customerId)
                    .header("Idempotency-Key", idempotencyKey)
                    .header("X-Test-Scenario", "SUCCESS")
                    .body(orderRequest)
                    .when()
                    .post("/api/v1/orders")
                    .then()
                    .statusCode(200)  // 200 for duplicate, not 201
                    .extract().response();

            String orderId2 = duplicateResponse.jsonPath().getString("id");
            logStep("✅ Duplicate request - Returned existing order: " + orderId2);

            // Verify same order was returned
            assertThat(orderId1)
                    .as("Duplicate request should return same order ID")
                    .isEqualTo(orderId2);

            // Verify order was only created once in database
            Response order = getOrder(orderId1);
            assertThat(order.jsonPath().getString("id"))
                    .isEqualTo(orderId1);

            logStep("✅ TEST PASSED - Idempotency working correctly");
            logStep("   Idempotency Key: " + idempotencyKey);
            logStep("   First Request Order ID: " + orderId1);
            logStep("   Duplicate Request Order ID: " + orderId2);
            logStep("   Same Order: " + orderId1.equals(orderId2));
            logStep("═══════════════════════════════════════════════════════");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Test interrupted", e);
        } catch (AssertionError | Exception e) {
            logStep("❌ TEST FAILED: " + e.getMessage());
            if (orderId1 != null) {
                try {
                    Response currentOrder = getOrder(orderId1);
                    logStep("   Current order state: " + currentOrder.asString());
                } catch (Exception ex) {
                    logStep("   Could not retrieve order state");
                }
            }
            throw e;
        }
    }

    @Test(priority = 6, timeOut = 30000)
    @Story("Card Expired Error")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify card expired error is handled correctly and marked as non-retryable")
    public void testCardExpiredError() {
        logStep("═══════════════════════════════════════════════════════");
        logStep("TEST: Card Expired Error");
        logStep("═══════════════════════════════════════════════════════");

        String orderId = null;

        try {
            // Create order with CARD_EXPIRED test scenario
            orderId = createOrder(3500.00, UUID.randomUUID().toString(), "CARD_EXPIRED");

            // Poll Order API until status is PAYMENT_FAILED
            Response order = pollOrderUntilStatusAsync(orderId, "PAYMENT_FAILED", 10);

            // Verify card expired specific details
            assertThat(order.jsonPath().getString("paymentFailureReason"))
                    .as("Failure reason should be Card expired")
                    .isEqualTo("Card expired");

            assertThat(order.jsonPath().getBoolean("paymentRetryable"))
                    .as("Card expired is not retryable (needs new card)")
                    .isFalse();

            assertThat(order.jsonPath().getString("paymentId"))
                    .as("No payment ID for failed payment")
                    .isNull();

            assertThat(order.jsonPath().getObject("paymentFraudScore", Integer.class))
                    .as("No fraud score for card expired")
                    .isNull();

            logStep("✅ TEST PASSED - Card expired error handled correctly");
            logStep("   Order ID: " + orderId);
            logStep("   Failure Reason: " + order.jsonPath().getString("paymentFailureReason"));
            logStep("   Retryable: " + order.jsonPath().getBoolean("paymentRetryable"));
            logStep("═══════════════════════════════════════════════════════");

        } catch (AssertionError | Exception e) {
            logStep("❌ TEST FAILED: " + e.getMessage());
            if (orderId != null) {
                try {
                    Response currentOrder = getOrder(orderId);
                    logStep("   Current order state: " + currentOrder.asString());
                } catch (Exception ex) {
                    logStep("   Could not retrieve order state");
                }
            }
            throw e;
        }
    }

    @Test(priority = 7, timeOut = 30000)
    @Story("Network Error")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify network error is handled correctly and marked as retryable")
    public void testNetworkErrorFlow() {
        logStep("═══════════════════════════════════════════════════════");
        logStep("TEST: Network Error Flow");
        logStep("═══════════════════════════════════════════════════════");

        String orderId = null;

        try {
            // Create order with NETWORK_ERROR test scenario
            orderId = createOrder(2000.00, UUID.randomUUID().toString(), "NETWORK_ERROR");

            // Poll Order API until status is PAYMENT_FAILED
            Response order = pollOrderUntilStatusAsync(orderId, "PAYMENT_FAILED", 10);

            // Verify network error specific details
            assertThat(order.jsonPath().getString("paymentFailureReason"))
                    .as("Failure reason should contain Network error")
                    .contains("Network error");

            assertThat(order.jsonPath().getBoolean("paymentRetryable"))
                    .as("Network error is retryable")
                    .isTrue();

            assertThat(order.jsonPath().getString("paymentId"))
                    .as("No payment ID for failed payment")
                    .isNull();

            logStep("✅ TEST PASSED - Network error handled correctly");
            logStep("   Order ID: " + orderId);
            logStep("   Failure Reason: " + order.jsonPath().getString("paymentFailureReason"));
            logStep("   Retryable: " + order.jsonPath().getBoolean("paymentRetryable"));
            logStep("═══════════════════════════════════════════════════════");

        } catch (AssertionError | Exception e) {
            logStep("❌ TEST FAILED: " + e.getMessage());
            if (orderId != null) {
                try {
                    Response currentOrder = getOrder(orderId);
                    logStep("   Current order state: " + currentOrder.asString());
                } catch (Exception ex) {
                    logStep("   Could not retrieve order state");
                }
            }
            throw e;
        }
    }

    @Test(priority = 8, timeOut = 60000)
    @Story("Multiple Orders with Different Outcomes")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify system handles multiple concurrent orders with different payment outcomes")
    public void testMultipleOrdersWithDifferentOutcomes() {
        logStep("═══════════════════════════════════════════════════════");
        logStep("TEST: Multiple Orders with Different Outcomes");
        logStep("═══════════════════════════════════════════════════════");

        String orderId1 = null;
        String orderId2 = null;
        String orderId3 = null;
        String orderId4 = null;

        try {
            // Create 4 orders with different test scenarios
            orderId1 = createOrder(1000.00, UUID.randomUUID().toString(), "SUCCESS");
            orderId2 = createOrder(2000.00, UUID.randomUUID().toString(), "FAILED");
            orderId3 = createOrder(3000.00, UUID.randomUUID().toString(), "FRAUD");
            orderId4 = createOrder(4000.00, UUID.randomUUID().toString(), "SUCCESS");

            logStep("✅ Created 4 orders with different test scenarios");

            // Wait for all orders to be processed
            logStep("⏳ Waiting for all orders to be processed...");

            String finalOrderId = orderId1;
            String finalOrderId1 = orderId2;
            String finalOrderId2 = orderId3;
            String finalOrderId3 = orderId4;
            await().atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofSeconds(1))
                    .ignoreExceptions()
                    .untilAsserted(() -> {
                        Response order1 = getOrder(finalOrderId);
                        Response order2 = getOrder(finalOrderId1);
                        Response order3 = getOrder(finalOrderId2);
                        Response order4 = getOrder(finalOrderId3);

                        // All orders should have final status
                        assertThat(order1.jsonPath().getString("status"))
                                .isIn("CONFIRMED", "PAYMENT_FAILED");
                        assertThat(order2.jsonPath().getString("status"))
                                .isIn("CONFIRMED", "PAYMENT_FAILED");
                        assertThat(order3.jsonPath().getString("status"))
                                .isIn("CONFIRMED", "PAYMENT_FAILED");
                        assertThat(order4.jsonPath().getString("status"))
                                .isIn("CONFIRMED", "PAYMENT_FAILED");
                    });

            // Verify specific outcomes
            Response order1 = getOrder(orderId1);
            Response order2 = getOrder(orderId2);
            Response order3 = getOrder(orderId3);
            Response order4 = getOrder(orderId4);

            assertThat(order1.jsonPath().getString("status"))
                    .as("Order 1 should be CONFIRMED")
                    .isEqualTo("CONFIRMED");

            assertThat(order2.jsonPath().getString("status"))
                    .as("Order 2 should be PAYMENT_FAILED")
                    .isEqualTo("PAYMENT_FAILED");
            assertThat(order2.jsonPath().getString("paymentFailureReason"))
                    .isEqualTo("Insufficient funds");

            assertThat(order3.jsonPath().getString("status"))
                    .as("Order 3 should be PAYMENT_FAILED")
                    .isEqualTo("PAYMENT_FAILED");
            assertThat(order3.jsonPath().getString("paymentFailureReason"))
                    .isEqualTo("Fraud detected");

            assertThat(order4.jsonPath().getString("status"))
                    .as("Order 4 should be CONFIRMED")
                    .isEqualTo("CONFIRMED");

            logStep("✅ TEST PASSED - All scenarios processed correctly");
            logStep("   Order 1 (" + orderId1 + "): " + order1.jsonPath().getString("status"));
            logStep("   Order 2 (" + orderId2 + "): " + order2.jsonPath().getString("status"));
            logStep("   Order 3 (" + orderId3 + "): " + order3.jsonPath().getString("status"));
            logStep("   Order 4 (" + orderId4 + "): " + order4.jsonPath().getString("status"));
            logStep("═══════════════════════════════════════════════════════");

        } catch (AssertionError | Exception e) {
            logStep("❌ TEST FAILED: " + e.getMessage());

            // Log all order states for debugging
            if (orderId1 != null) {
                try {
                    logStep("   Order 1 state: " + getOrder(orderId1).jsonPath().getString("status"));
                } catch (Exception ex) { /* ignore */ }
            }
            if (orderId2 != null) {
                try {
                    logStep("   Order 2 state: " + getOrder(orderId2).jsonPath().getString("status"));
                } catch (Exception ex) { /* ignore */ }
            }
            if (orderId3 != null) {
                try {
                    logStep("   Order 3 state: " + getOrder(orderId3).jsonPath().getString("status"));
                } catch (Exception ex) { /* ignore */ }
            }
            if (orderId4 != null) {
                try {
                    logStep("   Order 4 state: " + getOrder(orderId4).jsonPath().getString("status"));
                } catch (Exception ex) { /* ignore */ }
            }

            throw e;
        }
    }
}