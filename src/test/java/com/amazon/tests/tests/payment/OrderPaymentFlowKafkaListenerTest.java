package com.amazon.tests.tests.payment;

import com.amazon.tests.config.KafkaConfig;
import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.PaymentResultListener;
import com.amazon.tests.utils.TestDataFactory;
import io.qameta.allure.*;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.testng.annotations.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

@Epic("Amazon Microservices")
@Feature("Order Payment Flow")
@Slf4j
public class OrderPaymentFlowKafkaListenerTest extends BaseTest {

    private String customerToken;
    private String customerId;
    private TestModels.ProductResponse testProduct;

    // Kafka components
    private KafkaConsumer<String, String> kafkaConsumer;
    private PaymentResultListener paymentListener;
    private ExecutorService listenerExecutor;
    private Future<?> listenerFuture;

    @BeforeClass(alwaysRun = true)
    public void setupPaymentFlowTests() {
        logStep("════════════════════════════════════════════════════════");
        logStep("Setting up Payment Flow Test Environment");

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

            // Initialize Kafka listener
            initializeKafka();

            logStep("════════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.error("❌ Setup failed", e);
            emergencyShutdown();
            throw e;
        }
    }

    @AfterClass(alwaysRun = true)
    public void teardownPaymentFlowTests() {
        logStep("Cleanup starting...");
        emergencyShutdown();
        logStep("✅ Cleanup completed");
    }

    @BeforeMethod
    public void beforeEachTest() {
        if (paymentListener != null) {
            paymentListener.clearResults();
        }
    }

    private void initializeKafka() {
        try {
            kafkaConsumer = new KafkaConsumer<>(KafkaConfig.getConsumerProperties());
            paymentListener = new PaymentResultListener(kafkaConsumer);

            listenerExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "PaymentListener");
                t.setDaemon(true);
                return t;
            });

            listenerFuture = listenerExecutor.submit(paymentListener);

            await().atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> paymentListener.isRunning(), running -> running);

            logStep("✅ Kafka listener initialized");

        } catch (Exception e) {
            log.error("❌ Kafka init failed", e);
            throw new RuntimeException("Kafka initialization failed", e);
        }
    }

    private void emergencyShutdown() {
        if (paymentListener != null) paymentListener.stop();
        if (listenerFuture != null) listenerFuture.cancel(true);

        if (listenerExecutor != null) {
            listenerExecutor.shutdownNow();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (kafkaConsumer != null) kafkaConsumer.close(Duration.ofMillis(500));
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════

    private String createOrder(double amount) {
        return createOrder(amount, UUID.randomUUID().toString(), null);
    }

    private String createOrder(double amount, String idempotencyKey) {
        return createOrder(amount, idempotencyKey, null);
    }

    /**
     * ⭐ NEW: Create order with optional test scenario
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
                .shippingAddress("123 Test Street, Bengaluru")
                .build();

        // Build request with optional test scenario header
        var requestSpec = given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .header("Idempotency-Key", idempotencyKey);

        // ⭐ Add test scenario header if provided
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

        return response.jsonPath().getString("id");
    }

    private void verifyOrderStatus(String orderId, String expectedStatus, String expectedPaymentStatus) {
        Response response = given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .pathParam("id", orderId)
                .when()
                .get("/api/v1/orders/{id}")
                .then()
                .statusCode(200)
                .extract().response();

        response.then()
                .body("id", equalTo(orderId))
                .body("status", equalTo(expectedStatus))
                .body("paymentStatus", equalTo(expectedPaymentStatus));
    }

    // ═══════════════════════════════════════════════════════════════════
    // TEST CASES - Using Test Scenarios
    // ═══════════════════════════════════════════════════════════════════

    @Test(timeOut = 30000, priority = 1)
    @Story("Successful Payment")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify order confirmation with successful payment using test scenario")
    public void testSuccessfulPaymentFlow() {
        try {
            // ⭐ Use SUCCESS test scenario
            String orderId = createOrder(89999.00, UUID.randomUUID().toString(), "SUCCESS");
            logStep("✅ Order created: " + orderId);

            // Wait for Payment Service to process
            Map<String, Object> paymentResult = paymentListener.waitForPaymentResult(orderId, 10);

            assertThat(paymentResult)
                    .as("Payment result should not be null")
                    .isNotNull();

            assertThat(paymentResult.get("status"))
                    .as("Payment status should be SUCCESS")
                    .isEqualTo("SUCCESS");

            assertThat(paymentResult.get("transactionId"))
                    .asString()
                    .startsWith("TXN-");

            logStep("✅ Test PASSED - Payment Service processed SUCCESS scenario");

        } catch (Exception e) {
            log.error("❌ Test failed", e);
            throw e;
        }
    }

    @Test(timeOut = 30000, priority = 2)
    @Story("Failed Payment")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify payment failure handling using test scenario")
    public void testFailedPaymentFlow() {
        try {
            // ⭐ Use FAILED test scenario
            String orderId = createOrder(150000.00, UUID.randomUUID().toString(), "FAILED");
            logStep("✅ Order created: " + orderId);

            // Wait for Payment Service to process
            Map<String, Object> paymentResult = paymentListener.waitForPaymentResult(orderId, 10);

            assertThat(paymentResult).isNotNull();
            assertThat(paymentResult.get("status")).isEqualTo("FAILED");
            assertThat(paymentResult.get("failureReason")).isEqualTo("Insufficient funds");

            logStep("✅ Test PASSED - Payment Service processed FAILED scenario");

        } catch (Exception e) {
            log.error("❌ Test failed", e);
            throw e;
        }
    }

    @Test(timeOut = 30000, priority = 3)
    @Story("Fraud Detection")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify fraud detection using test scenario")
    public void testFraudDetectionFlow() {
        try {
            // ⭐ Use FRAUD test scenario
            String orderId = createOrder(5000.00, UUID.randomUUID().toString(), "FRAUD");
            logStep("✅ Order created: " + orderId);

            Map<String, Object> paymentResult = paymentListener.waitForPaymentResult(orderId, 10);

            assertThat(paymentResult).isNotNull();
            assertThat(paymentResult.get("status")).isEqualTo("FAILED");
            assertThat(paymentResult.get("failureReason")).isEqualTo("Fraud detected");
            assertThat(paymentResult.get("fraudScore")).isEqualTo(95);

            logStep("✅ Test PASSED - Payment Service processed FRAUD scenario");

        } catch (Exception e) {
            log.error("❌ Test failed", e);
            throw e;
        }
    }

    @Test(timeOut = 30000, priority = 4)
    @Story("Payment Timeout")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify payment timeout handling using test scenario")
    public void testPaymentTimeoutFlow() {
        try {
            // ⭐ Use TIMEOUT test scenario
            String orderId = createOrder(2500.00, UUID.randomUUID().toString(), "TIMEOUT");
            logStep("✅ Order created: " + orderId);

            // Payment Service won't publish result for TIMEOUT scenario
            Thread.sleep(8000);

            Map<String, Object> paymentResult = paymentListener.getPaymentResult(orderId);
            assertThat(paymentResult).isNull();

            logStep("✅ Test PASSED - Payment Service processed TIMEOUT scenario");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Test(timeOut = 30000, priority = 5)
    @Story("Idempotency")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify idempotency with payment using test scenario")
    public void testIdempotencyWithPayment() {
        try {
            String idempotencyKey = UUID.randomUUID().toString();

            // ⭐ Use SUCCESS test scenario
            String orderId1 = createOrder(1000.00, idempotencyKey, "SUCCESS");
            logStep("✅ First order: " + orderId1);

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
                    .shippingAddress("123 Test Street")
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
                    .statusCode(200)
                    .extract().response();

            String orderId2 = duplicateResponse.jsonPath().getString("id");
            assertThat(orderId1).isEqualTo(orderId2);

            logStep("✅ Test PASSED - Idempotency working correctly");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Test(timeOut = 30000, priority = 6)
    @Story("Card Expired")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify card expired error handling using test scenario")
    public void testCardExpiredError() {
        try {
            // ⭐ Use CARD_EXPIRED test scenario
            String orderId = createOrder(3500.00, UUID.randomUUID().toString(), "CARD_EXPIRED");
            logStep("✅ Order created: " + orderId);

            Map<String, Object> paymentResult = paymentListener.waitForPaymentResult(orderId, 10);

            assertThat(paymentResult).isNotNull();
            assertThat(paymentResult.get("status")).isEqualTo("FAILED");
            assertThat(paymentResult.get("failureReason")).isEqualTo("Card expired");

            logStep("✅ Test PASSED - Payment Service processed CARD_EXPIRED scenario");

        } catch (Exception e) {
            log.error("❌ Test failed", e);
            throw e;
        }
    }

    @Test(timeOut = 60000, priority = 7)
    @Story("Multiple Orders")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify multiple orders with different payment outcomes using test scenarios")
    public void testMultipleOrdersWithDifferentOutcomes() {
        try {
            // ⭐ Create orders with different test scenarios
            String orderId1 = createOrder(1000.00, UUID.randomUUID().toString(), "SUCCESS");
            String orderId2 = createOrder(2000.00, UUID.randomUUID().toString(), "FAILED");
            String orderId3 = createOrder(3000.00, UUID.randomUUID().toString(), "FRAUD");
            String orderId4 = createOrder(4000.00, UUID.randomUUID().toString(), "SUCCESS");

            logStep("✅ Created 4 orders with different test scenarios");

            // Wait for all payment results
            await().atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        assertThat(paymentListener.hasPaymentResult(orderId1)).isTrue();
                        assertThat(paymentListener.hasPaymentResult(orderId2)).isTrue();
                        assertThat(paymentListener.hasPaymentResult(orderId3)).isTrue();
                        assertThat(paymentListener.hasPaymentResult(orderId4)).isTrue();
                    });

            // Verify outcomes
            assertThat(paymentListener.getPaymentResult(orderId1).get("status")).isEqualTo("SUCCESS");
            assertThat(paymentListener.getPaymentResult(orderId2).get("status")).isEqualTo("FAILED");
            assertThat(paymentListener.getPaymentResult(orderId3).get("status")).isEqualTo("FAILED");
            assertThat(paymentListener.getPaymentResult(orderId4).get("status")).isEqualTo("SUCCESS");

            logStep("✅ Test PASSED - All scenarios processed correctly");

        } catch (Exception e) {
            log.error("❌ Test failed", e);
            throw e;
        }
    }
}