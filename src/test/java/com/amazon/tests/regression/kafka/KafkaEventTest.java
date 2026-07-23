package com.amazon.tests.regression.kafka;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.AuthApiClient;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.apiClients.ProductApiClient;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka Event Verification Tests.
 *
 * Strategy: each test calls the REST API (via *ApiClient), which triggers
 * domain events, then a KafkaTestConsumer polls the relevant topic and
 * asserts the message was produced with the correct payload.
 *
 * One test per event/topic by design: independent failure signal, independent
 * timeouts (near-instant registration events vs. up-to-20s payment saga
 * events), and separate reporting per @Story. testOrderStatusUpdatedEventAfterPayment
 * is the one legitimate exception — it collects multiple causally-linked
 * events from a single trigger action, not multiple unrelated features.
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

    private AuthApiClient authApiClient;
    private ProductApiClient productApiClient;

    @BeforeClass
    public void setup() {
        authApiClient = new AuthApiClient(context.getExecutor());
        productApiClient = new ProductApiClient(context.getExecutor());
    }

    private OrderApiClient orderApiClient(String token) {
        return new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());
    }

    /** Common setup: customer + seller + one product, reused by every order-triggering test. */
    private record OrderSetup(TestModels.AuthResponse customer, TestModels.ProductResponse product) {}

    private OrderSetup setupCustomerSellerAndProduct(double price) {
        TestModels.AuthResponse customerAuth = authApiClient.registerCustomer();
        TestModels.AuthResponse sellerAuth = authApiClient.registerSeller();
        TestModels.ProductResponse product = productApiClient.createProduct(sellerAuth, price, 100);
        return new OrderSetup(customerAuth, product);
    }

    private TestModels.OrderResponse createOrder(OrderSetup setup) {
        String token = setup.customer().getAccessToken();
        String userId = setup.customer().getUser().getId();
        return orderApiClient(token).createOrder(userId, TestDataFactory.newIdempotencyKey(), List.of(setup.product()));
    }

    // ─── User Registration Event ───────────────────────────────────────

    @Test(priority = 1)
    @Story("user.registered topic")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify USER_REGISTERED event is published to Kafka when a user registers")
    public void testUserRegisteredEventPublished() {
        TestModels.RegisterRequest newUser = TestDataFactory.createRandomUser();
        logStep("Registering user: " + newUser.getEmail());

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {
            TestModels.AuthResponse response = authApiClient.registerCustomer(newUser);
            String userId = response.getUser().getId();

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
        OrderSetup setup = setupCustomerSellerAndProduct(39.99);
        String customerId = setup.customer().getUser().getId();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("order.events")) {
            TestModels.OrderResponse order = createOrder(setup);
            String orderId = order.getId();
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
        OrderSetup setup = setupCustomerSellerAndProduct(59.99);
        String customerId = setup.customer().getUser().getId();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("payment.request")) {
            TestModels.OrderResponse order = createOrder(setup);
            String orderId = order.getId();

            Optional<JsonNode> paymentRequest = consumer.waitForMessage(
                    node -> node.has("orderId") && orderId.equals(node.get("orderId").asText()),
                    KAFKA_WAIT_SECONDS
            );

            assertThat(paymentRequest).isPresent();
            JsonNode payload = paymentRequest.get();
            assertThat(payload.get("orderId").asText()).isEqualTo(orderId);
            assertThat(payload.get("userId").asText()).isEqualTo(customerId);
            assertThat(payload.has("amount")).isTrue();
            double amount = payload.get("amount").asDouble();
            assertThat(amount).isGreaterThan(0);

            logStep("✅ payment.request event verified for orderId: " + orderId + " | amount: " + amount);
        }
    }

    // ─── Payment Result Event (Saga completion) ────────────────────────

    @Test(priority = 4)
    @Story("payment.result topic")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify payment.result event is published after payment is processed (Saga completion)")
    public void testPaymentResultEventPublished() {
        OrderSetup setup = setupCustomerSellerAndProduct(79.99);

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("payment.result")) {
            TestModels.OrderResponse order = createOrder(setup);
            String orderId = order.getId();

            // Payment processing is async — poll until result arrives
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
            assertThat(status).isIn("SUCCESS", "FAILED");  // either is a valid outcome
            assertThat(payload.has("paymentId")).isTrue();

            logStep("✅ payment.result event verified for orderId: " + orderId + " | status: " + status);
        }
    }

    // ─── Order Status Updated after Saga ──────────────────────────────

    @Test(priority = 5)
    @Story("order.events topic — status update")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify ORDER_STATUS_UPDATED event is published after payment saga completes, in addition to ORDER_CREATED")
    public void testOrderStatusUpdatedEventAfterPayment() {
        OrderSetup setup = setupCustomerSellerAndProduct(19.99);

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("order.events")) {
            TestModels.OrderResponse order = createOrder(setup);
            String orderId = order.getId();

            // Collect all order events for this orderId — expect CREATED + STATUS_UPDATED (saga completion)
            List<JsonNode> allEvents = consumer.collectMessages(
                    node -> node.has("orderId") && orderId.equals(node.get("orderId").asText()),
                    20
            );

            boolean hasCreated = allEvents.stream()
                    .anyMatch(n -> "ORDER_CREATED".equals(n.get("eventType").asText()));
            boolean hasStatusUpdated = allEvents.stream()
                    .anyMatch(n -> "ORDER_STATUS_UPDATED".equals(n.get("eventType").asText()));

            assertThat(hasCreated).as("ORDER_CREATED event should be published").isTrue();
            assertThat(hasStatusUpdated).as("ORDER_STATUS_UPDATED event should be published after payment saga completes").isTrue();

            logStep("✅ Collected " + allEvents.size() + " order.events for orderId: " + orderId
                    + " (CREATED=" + hasCreated + ", STATUS_UPDATED=" + hasStatusUpdated + ")");
        }
    }

    // ─── Notification Event ────────────────────────────────────────────

    @Test(priority = 6)
    @Story("notification.events topic")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify notification event is published after payment processing")
    public void testNotificationEventPublished() {
        OrderSetup setup = setupCustomerSellerAndProduct(29.99);
        String customerId = setup.customer().getUser().getId();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("notification.events")) {
            TestModels.OrderResponse order = createOrder(setup);

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

            logStep("✅ notification.events event verified | type: " + payload.get("type").asText());
        }
    }

    // ─── Product Event ─────────────────────────────────────────────────

    @Test(priority = 7)
    @Story("product.events topic")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify PRODUCT_CREATED event is published when a product is created")
    public void testProductCreatedEventPublished() {
        TestModels.AuthResponse sellerAuth = authApiClient.registerSeller();
        String sellerId = sellerAuth.getUser().getId();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("product.events")) {
            TestModels.ProductResponse product = productApiClient.createProduct(sellerAuth, 99.99, 100);
            String productId = product.getId();

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
        OrderSetup setup = setupCustomerSellerAndProduct(14.99);
        TestModels.OrderResponse order = createOrder(setup);
        String orderId = order.getId();

        String token = setup.customer().getAccessToken();
        String userId = setup.customer().getUser().getId();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("order.events")) {
            orderApiClient(token).cancelOrder(token, userId, orderId);

            Optional<JsonNode> event = consumer.waitForMessage(
                    node -> "ORDER_CANCELLED".equals(node.has("eventType") ? node.get("eventType").asText() : "")
                            && orderId.equals(node.has("orderId") ? node.get("orderId").asText() : ""),
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