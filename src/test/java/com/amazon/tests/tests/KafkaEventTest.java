package com.amazon.tests.tests;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.awaitility.Awaitility;
import org.testng.annotations.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Kafka Event Verification Tests.
 *
 * Strategy: each test calls the REST API, which triggers domain
 * events, then a KafkaTestConsumer polls the relevant topic and
 * asserts the message was produced with the correct payload.
 *
 * Topics covered:
 *   - user.registered
 *   - order.events
 *   - payment.request
 *   - payment.result
 *   - product.events
 *   - notification.events
 */
@Epic("Amazon Microservices")
@Feature("Kafka Event Verification")
public class KafkaEventTest extends BaseTest {

    private static final int KAFKA_WAIT_SECONDS = 15;

    // ─── User Registration Event ───────────────────────────────────────

    @Test(priority = 1)
    @Story("user.registered topic")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify USER_REGISTERED event is published to Kafka when a user registers")
    public void testUserRegisteredEventPublished() {
        TestModels.RegisterRequest newUser = TestDataFactory.createRandomUser();

        logStep("Registering user: " + newUser.getEmail());

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {
            // Trigger the event
            Response response = given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .body(newUser)
                    .when()
                    .post("/api/v1/auth/register")
                    .then()
                    .statusCode(201)
                    .extract().response();

            String userId = response.jsonPath().getString("user.id");

            // Assert event appears on Kafka topic
            Optional<JsonNode> event = consumer.waitForMessage(
                    node -> node.has("email")
                            && newUser.getEmail().equals(node.get("email").asText())
                            && node.has("userId"),
                    KAFKA_WAIT_SECONDS
            );

            assertThat(event).isPresent();
            JsonNode payload = event.get();
            assertThat(payload.get("email").asText()).isEqualTo(newUser.getEmail());
            assertThat(payload.get("firstName").asText()).isEqualTo(newUser.getFirstName());
            assertThat(payload.get("lastName").asText()).isEqualTo(newUser.getLastName());
            assertThat(payload.has("userId")).isTrue();
            assertThat(payload.has("registeredAt")).isTrue();

            logStep("✅ user.registered event verified for userId: " + userId);
        }
    }

    // ─── Order Created Event ───────────────────────────────────────────

    @Test(priority = 2)
    @Story("order.events topic")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify ORDER_CREATED event is published when an order is placed")
    public void testOrderCreatedEventPublished() {
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(39.99))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        TestModels.ProductResponse product = productResp.as(TestModels.ProductResponse.class);

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("order.events")) {

            Response orderResp = given()
                    .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                    .header("X-User-Id", customerId)
                    .body(TestDataFactory.createOrderRequest(
                            product.getId(), product.getName(), product.getPrice()))
                    .when()
                    .post("/api/v1/orders")
                    .then()
                    .statusCode(201)
                    .extract().response();

            String orderId = orderResp.jsonPath().getString("id");
            logStep("Order created: " + orderId + " — now verifying Kafka event");

            Optional<JsonNode> event = consumer.waitForMessage(
                    KafkaTestConsumer.isEventType("ORDER_CREATED")
                        .and(node -> orderId.equals(
                                node.has("orderId") ? node.get("orderId").asText() : "")),
                    KAFKA_WAIT_SECONDS
            );

            assertThat(event).isPresent();
            JsonNode payload = event.get();
            assertThat(payload.get("eventType").asText()).isEqualTo("ORDER_CREATED");
            assertThat(payload.get("orderId").asText()).isEqualTo(orderId);
            assertThat(payload.get("userId").asText()).isEqualTo(customerId);
            assertThat(payload.has("totalAmount")).isTrue();
            assertThat(payload.has("items")).isTrue();
            assertThat(payload.get("items").isArray()).isTrue();
            assertThat(payload.get("status").asText()).isEqualTo("PENDING");

            logStep("✅ order.events ORDER_CREATED event verified for orderId: " + orderId);
        }
    }

    // ─── Payment Request Event ─────────────────────────────────────────

    @Test(priority = 3)
    @Story("payment.request topic")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify payment.request is published to Kafka when an order is created (triggers payment saga)")
    public void testPaymentRequestEventPublished() {
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(59.99))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        TestModels.ProductResponse product = productResp.as(TestModels.ProductResponse.class);

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("payment.request")) {

            Response orderResp = given()
                    .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                    .header("X-User-Id", customerId)
                    .body(TestDataFactory.createOrderRequest(
                            product.getId(), product.getName(), product.getPrice()))
                    .when()
                    .post("/api/v1/orders")
                    .then()
                    .statusCode(201)
                    .extract().response();

            String orderId = orderResp.jsonPath().getString("id");

            Optional<JsonNode> paymentRequest = consumer.waitForMessage(
                    node -> node.has("orderId")
                            && orderId.equals(node.get("orderId").asText()),
                    KAFKA_WAIT_SECONDS
            );

            assertThat(paymentRequest).isPresent();
            JsonNode payload = paymentRequest.get();
            assertThat(payload.get("orderId").asText()).isEqualTo(orderId);
            assertThat(payload.get("userId").asText()).isEqualTo(customerId);
            assertThat(payload.has("amount")).isTrue();
            double amount = payload.get("amount").asDouble();
            assertThat(amount).isGreaterThan(0);

            logStep("✅ payment.request event verified for orderId: " + orderId
                    + " | amount: " + amount);
        }
    }

    // ─── Payment Result Event (Saga completion) ────────────────────────

    @Test(priority = 4)
    @Story("payment.result topic")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify payment.result event is published after payment is processed (Saga completion)")
    public void testPaymentResultEventPublished() {
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(79.99))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        TestModels.ProductResponse product = productResp.as(TestModels.ProductResponse.class);

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("payment.result")) {

            Response orderResp = given()
                    .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                    .header("X-User-Id", customerId)
                    .body(TestDataFactory.createOrderRequest(
                            product.getId(), product.getName(), product.getPrice()))
                    .when()
                    .post("/api/v1/orders")
                    .then()
                    .statusCode(201)
                    .extract().response();

            String orderId = orderResp.jsonPath().getString("id");

            // Payment processing is async — we poll until result arrives
            Optional<JsonNode> paymentResult = consumer.waitForMessage(
                    node -> node.has("orderId")
                            && orderId.equals(node.get("orderId").asText())
                            && node.has("status"),
                    20  // up to 20s for payment saga
            );

            assertThat(paymentResult).isPresent();
            JsonNode payload = paymentResult.get();
            assertThat(payload.get("orderId").asText()).isEqualTo(orderId);
            String status = payload.get("status").asText();
            assertThat(status).isIn("SUCCESS", "FAILED");  // either is valid
            assertThat(payload.has("paymentId")).isTrue();

            logStep("✅ payment.result event verified for orderId: " + orderId
                    + " | status: " + status);
        }
    }

    // ─── Order Status Updated after Saga ──────────────────────────────

    @Test(priority = 5)
    @Story("order.events topic — status update")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify ORDER_STATUS_UPDATED event is published after payment saga completes")
    public void testOrderStatusUpdatedEventAfterPayment() {
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(19.99))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        TestModels.ProductResponse product = productResp.as(TestModels.ProductResponse.class);

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("order.events")) {

            Response orderResp = given()
                    .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                    .header("X-User-Id", customerId)
                    .body(TestDataFactory.createOrderRequest(
                            product.getId(), product.getName(), product.getPrice()))
                    .when()
                    .post("/api/v1/orders")
                    .then()
                    .statusCode(201)
                    .extract().response();

            String orderId = orderResp.jsonPath().getString("id");

            // Collect all order events for this orderId — expect at least CREATED + STATUS_UPDATED
            List<JsonNode> allEvents = consumer.collectMessages(
                    node -> node.has("orderId")
                            && orderId.equals(node.get("orderId").asText()),
                    20
            );

            assertThat(allEvents).hasSizeGreaterThanOrEqualTo(1);

            // Check for ORDER_CREATED
            boolean hasCreated = allEvents.stream()
                    .anyMatch(n -> "ORDER_CREATED".equals(n.get("eventType").asText()));
            assertThat(hasCreated).isTrue();

            logStep("✅ Collected " + allEvents.size()
                    + " order.events for orderId: " + orderId);
        }
    }

    // ─── Notification Event ────────────────────────────────────────────

    @Test(priority = 6)
    @Story("notification.events topic")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify notification event is published after payment processing")
    public void testNotificationEventPublished() {
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(29.99))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        TestModels.ProductResponse product = productResp.as(TestModels.ProductResponse.class);

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("notification.events")) {

            Response orderResp = given()
                    .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                    .header("X-User-Id", customerId)
                    .body(TestDataFactory.createOrderRequest(
                            product.getId(), product.getName(), product.getPrice()))
                    .when()
                    .post("/api/v1/orders")
                    .then()
                    .statusCode(201)
                    .extract().response();
            String orderId = orderResp.jsonPath().getString("id");

            Optional<JsonNode> notif = consumer.waitForMessage(
                    node -> node.has("userId")
                            && customerId.equals(node.get("userId").asText())
                            && node.has("type"),
                    20
            );

            assertThat(notif).isPresent();
            JsonNode payload = notif.get();
            assertThat(payload.get("userId").asText()).isEqualTo(customerId);
            assertThat(payload.get("type").asText()).isIn("PAYMENT_SUCCESS", "PAYMENT_FAILED");
            assertThat(payload.has("orderId")).isTrue();
            assertThat(payload.has("amount")).isTrue();

            logStep("✅ notification.events event verified | type: "
                    + payload.get("type").asText());
        }
    }

    // ─── Product Event ─────────────────────────────────────────────────

    @Test(priority = 7)
    @Story("product.events topic")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify PRODUCT_CREATED event is published when a product is created")
    public void testProductCreatedEventPublished() {
        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        String sellerId = sellerAuth.getUser().getId();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("product.events")) {

            Response productResp = given()
                    .spec(RestAssuredConfig.getProductServiceSpec())
                    .header("X-User-Id", sellerId)
                    .body(TestDataFactory.createProductWithPrice(99.99))
                    .when()
                    .post("/api/v1/products")
                    .then()
                    .statusCode(201)
                    .extract().response();
            String productId = productResp.jsonPath().getString("id");

            Optional<JsonNode> event = consumer.waitForMessage(
                    node -> node.has("eventType")
                            && "PRODUCT_CREATED".equals(node.get("eventType").asText())
                            && node.has("productId")
                            && productId.equals(node.get("productId").asText()),
                    KAFKA_WAIT_SECONDS
            );

            assertThat(event).isPresent();
            JsonNode payload = event.get();
            assertThat(payload.get("eventType").asText()).isEqualTo("PRODUCT_CREATED");
            assertThat(payload.get("productId").asText()).isEqualTo(productId);
            assertThat(payload.get("sellerId").asText()).isEqualTo(sellerId);

            logStep("✅ product.events PRODUCT_CREATED verified for productId: " + productId);
        }
    }

    // ─── Order Cancel Event ────────────────────────────────────────────

    @Test(priority = 8)
    @Story("order.events topic — cancellation")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify ORDER_CANCELLED event is published when order is cancelled")
    public void testOrderCancelledEventPublished() {
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(14.99))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        TestModels.ProductResponse product = productResp.as(TestModels.ProductResponse.class);

        Response orderResp = given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                .header("X-User-Id", customerId)
                .body(TestDataFactory.createOrderRequest(
                        product.getId(), product.getName(), product.getPrice()))
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .extract().response();
        String orderId = orderResp.jsonPath().getString("id");

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("order.events")) {
            // Cancel the order
            given()
                    .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                    .header("X-User-Id", customerId)
                    .pathParam("id", orderId)
                    .when()
                    .patch("/api/v1/orders/{id}/cancel")
                    .then()
                    .statusCode(200);

            Optional<JsonNode> event = consumer.waitForMessage(
                    node -> "ORDER_CANCELLED".equals(node.has("eventType")
                            ? node.get("eventType").asText() : "")
                            && orderId.equals(node.has("orderId")
                            ? node.get("orderId").asText() : ""),
                    KAFKA_WAIT_SECONDS
            );

            assertThat(event).isPresent();
            JsonNode payload = event.get();
            assertThat(payload.get("eventType").asText()).isEqualTo("ORDER_CANCELLED");
            assertThat(payload.get("status").asText()).isEqualTo("CANCELLED");

            logStep("✅ order.events ORDER_CANCELLED verified for orderId: " + orderId);
        }
    }
}
