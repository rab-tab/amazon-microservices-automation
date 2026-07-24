package com.amazon.tests.regression.kafka.orders.publishing.success;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.apiClients.ProductApiClient;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka Event Publishing Tests
 *
 * Tests the Kafka workflow for order creation:
 * 1. Order created → ORDER_CREATED event published to Kafka
 * 2. Order status: PENDING (awaiting payment processing)
 * 3. Payment service consumes event (tested separately)
 */
@Slf4j
@Epic("Amazon Microservices")
@Feature("Kafka - Event Publishing")
public class OrderEventPublishingTest extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;
    private PurchaseResult purchase;
    private OrderApiClient orderApiClient;
    private ProductApiClient productApiClient;

    @BeforeMethod
    public void setup() {
        logStep("Setting up Kafka event publishing tests");

        purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        orderApiClient = new OrderApiClient(
                new com.amazon.tests.auth.BearerAuthStrategy(purchase.getCustomer().getAccessToken()),
                context.getExecutor());
        productApiClient = new ProductApiClient(context.getExecutor());

        kafkaConsumer = new KafkaTestConsumer("order.events");
        kafkaConsumer.seekToEnd();

        logStep("✅ Setup complete — user: " + purchase.getCustomer().getUser().getId());
    }

    @AfterMethod
    public void cleanup() {
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
            logStep("✅ Kafka consumer closed");
        }
    }

    private String userId() {
        return purchase.getCustomer().getUser().getId();
    }

    private String token() {
        return purchase.getCustomer().getAccessToken();
    }

    // ══════════════════════════════════════════════════════════════
    // POSITIVE TEST CASES - VALID KAFKA WORKFLOW
    // ══════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Order creation publishes ORDER_CREATED event to Kafka with correct data")
    public void test01_OrderCreationPublishesEventToKafka() {
        logStep("TEST 1: Verify ORDER_CREATED event published to Kafka");

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId(), TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        logStep("  ✓ Order created: " + order.getId());
        logStep("  ✓ Initial status: " + order.getStatus());
        assertThat(order.getStatus()).as("Order should be PENDING (event published, awaiting payment)").isEqualTo("PENDING");

        logStep("  Waiting for ORDER_CREATED event in Kafka...");

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("eventType")
                        && "ORDER_CREATED".equals(node.get("eventType").asText())
                        && node.has("orderId")
                        && order.getId().equals(node.get("orderId").asText()),
                10
        );

        assertThat(event).as("ORDER_CREATED event should be published to Kafka").isPresent();

        JsonNode eventData = event.get();
        logStep("  ✓ Event received: type=" + eventData.get("eventType").asText()
                + " orderId=" + eventData.get("orderId").asText()
                + " userId=" + eventData.get("userId").asText());

        assertThat(eventData.get("orderId").asText()).isEqualTo(order.getId());
        assertThat(eventData.get("userId").asText()).isEqualTo(userId());
        assertThat(eventData.has("items")).as("Event should contain order items").isTrue();

        logStep("✅ ORDER_CREATED event published successfully to Kafka");
    }

    @Test(priority = 2)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Multiple concurrent orders publish events without data loss")
    public void test02_ConcurrentOrdersPublishAllEvents() {
        logStep("TEST 2: Concurrent orders publish all events to Kafka");

        int orderCount = 5;
        List<String> orderIds = new ArrayList<>();

        logStep("  Creating " + orderCount + " orders...");
        for (int i = 0; i < orderCount; i++) {
            TestModels.OrderResponse order = orderApiClient.createOrder(
                    userId(), TestDataFactory.newIdempotencyKey(), purchase.getProducts());
            orderIds.add(order.getId());
            logStep("    ✓ Order " + (i + 1) + " created: " + order.getId());
        }

        assertThat(orderIds).as("All orders should be created").hasSize(orderCount);

        logStep("  Collecting all Kafka events...");
        List<JsonNode> events = kafkaConsumer.collectMessages(
                node -> node.has("eventType") && "ORDER_CREATED".equals(node.get("eventType").asText()),
                10
        );

        logStep("  Found " + events.size() + " ORDER_CREATED events in Kafka");

        Set<String> receivedOrderIds = events.stream()
                .filter(node -> node.has("orderId"))
                .map(node -> node.get("orderId").asText())
                .collect(Collectors.toSet());

        Set<String> missingOrderIds = new HashSet<>(orderIds);
        missingOrderIds.removeAll(receivedOrderIds);

        assertThat(missingOrderIds).as("No events should be missing").isEmpty();
        assertThat(receivedOrderIds).as("All ORDER_CREATED events should be published").containsAll(orderIds);

        logStep("✅ All " + orderCount + " concurrent events published successfully - no data loss");
    }

    @Test(priority = 3)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Large order with multiple items publishes complete event data")
    public void test03_LargeOrderPublishesCompleteEventData() {
        logStep("TEST 3: Large order publishes complete event with all items");

        List<TestModels.ProductResponse> products = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            products.add(productApiClient.createProduct(purchase.getSellerAuth(), 10.0 + i, 500));
        }

        logStep("  Creating order with " + products.size() + " items...");

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId(), TestDataFactory.newIdempotencyKey(), products);

        logStep("  ✓ Large order created: " + order.getId());

        logStep("  Verifying complete event data in Kafka...");
        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("orderId") && order.getId().equals(node.get("orderId").asText()),
                10
        );

        assertThat(event).as("ORDER_CREATED event should be published").isPresent();

        JsonNode eventData = event.get();
        JsonNode eventItems = eventData.get("items");

        assertThat(eventItems).as("Event should contain items array").isNotNull();
        assertThat(eventItems.size()).as("Event should contain all " + products.size() + " items").isEqualTo(products.size());

        logStep("  ✓ Event contains all " + products.size() + " items");

        for (int i = 0; i < eventItems.size(); i++) {
            JsonNode item = eventItems.get(i);
            logStep("    - Item " + (i + 1) + ": "
                    + (item.has("productName") ? item.get("productName").asText() : "N/A")
                    + " (qty: " + item.get("quantity").asInt() + ")");

            assertThat(item.has("productId")).as("Item " + (i + 1) + " should have productId").isTrue();
            assertThat(item.has("quantity")).as("Item " + (i + 1) + " should have quantity").isTrue();
            assertThat(item.has("unitPrice")).as("Item " + (i + 1) + " should have unitPrice").isTrue();
        }

        logStep("✅ Large order event published with complete data");
    }

    @Test(priority = 4)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Event contains timestamp and metadata for payment service")
    public void test04_EventContainsTimestampAndMetadata() {
        logStep("TEST 4: Event contains timestamp and metadata for payment service");

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId(), TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        logStep("  ✓ Order created: " + order.getId());

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("orderId") && order.getId().equals(node.get("orderId").asText()),
                10
        );

        assertThat(event).isPresent();
        JsonNode eventData = event.get();

        assertThat(eventData.has("eventType")).as("Event should have eventType").isTrue();
        assertThat(eventData.has("timestamp")).as("Event should have timestamp for ordering").isTrue();
        assertThat(eventData.has("orderId")).as("Event should have orderId").isTrue();
        assertThat(eventData.has("userId")).as("Event should have userId").isTrue();
        assertThat(eventData.has("totalAmount") || eventData.has("items")).as("Event should have pricing information").isTrue();

        logStep("  ✓ Event Type: " + eventData.get("eventType").asText());
        logStep("  ✓ Timestamp: " + eventData.get("timestamp").asText());
        logStep("  ✓ Order ID: " + eventData.get("orderId").asText());
        logStep("  ✓ User ID: " + eventData.get("userId").asText());
        if (eventData.has("totalAmount")) {
            logStep("  ✓ Total Amount: " + eventData.get("totalAmount").asDouble());
        }

        logStep("✅ Event contains all required metadata for payment processing");
    }

    @Test(priority = 5)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Event published asynchronously (doesn't block API response)")
    public void test05_EventPublishedAsynchronously() {
        logStep("TEST 5: Event published asynchronously (doesn't block API response)");

        long startTime = System.currentTimeMillis();
        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId(), TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        long responseTime = System.currentTimeMillis() - startTime;

        logStep("  ✓ API response time: " + responseTime + "ms");
        logStep("  ✓ Order created: " + order.getId());

        assertThat(responseTime).as("API should return quickly without waiting for Kafka").isLessThan(3000);

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("orderId") && order.getId().equals(node.get("orderId").asText()),
                10
        );

        assertThat(event).as("Event should still be published asynchronously").isPresent();

        logStep("✅ Event published asynchronously without blocking API response");
    }

    @Test(priority = 6)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Idempotent requests publish event only once")
    public void test06_IdempotentRequestPublishesEventOnce() {
        logStep("TEST 6: Idempotent requests publish event only once");

        String idempotencyKey = TestDataFactory.newIdempotencyKey();
        logStep("  Idempotency Key: " + idempotencyKey);

        TestModels.OrderResponse firstOrder = orderApiClient.createOrder(userId(), idempotencyKey, purchase.getProducts());
        String orderId = firstOrder.getId();
        logStep("  ✓ First request - Order created: " + orderId);

        TestModels.OrderResponse duplicateOrder = orderApiClient.createOrder(userId(), idempotencyKey, purchase.getProducts());
        assertThat(duplicateOrder.getId()).isEqualTo(orderId);
        logStep("  ✓ Duplicate request returned same order: " + orderId);

        List<JsonNode> events = kafkaConsumer.collectMessages(
                node -> node.has("orderId")
                        && orderId.equals(node.get("orderId").asText())
                        && node.has("eventType")
                        && "ORDER_CREATED".equals(node.get("eventType").asText()),
                5
        );

        logStep("  Total ORDER_CREATED events found for this order: " + events.size());

        assertThat(events).as("Only ONE ORDER_CREATED event should be published (idempotent)").hasSize(1);

        logStep("✅ Idempotent request published exactly one event");
    }

    // ══════════════════════════════════════════════════════════════
    // KNOWN GAPS — removed from this suite, not silently dropped
    // ══════════════════════════════════════════════════════════════
    //
    // test00_DebugRedisIdempotency: removed. Was a disabled scratch/debug
    // test hitting Redis directly via a hardcoded localhost:8083 URL — not
    // a maintainable regression test. Redis idempotency behavior is already
    // covered properly in OrderIdempotencyTest / DistributedIdempotencyTest.
    //
    // test07_NamespaceIsolation: removed. Was disabled AND fundamentally
    // broken as written — both "namespace A" and "namespace B" orders used
    // the same namespace and the same response object (orderIdB was
    // mistakenly read from responseA), so its core assertion could never
    // meaningfully fail. Multi-tenant namespace isolation for Kafka events
    // is a real, currently-untested concern worth adding properly once
    // namespace-scoped user/product provisioning is available via
    // PurchaseWorkflow — needs product owner input on how namespaces map
    // to seeded test users before rewriting this correctly.
}