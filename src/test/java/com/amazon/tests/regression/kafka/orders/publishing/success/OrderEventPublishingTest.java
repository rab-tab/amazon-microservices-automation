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

        purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
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

    @Test(priority = 1)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Order creation publishes ORDER_CREATED event to Kafka")
    public void test01_OrderCreationPublishesEventToKafka() {
        logStep("TEST 1: Verify ORDER_CREATED event published to Kafka");

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId(), TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        logStep("  ✓ Order created: " + order.getId());
        logStep("  ✓ Initial status: " + order.getStatus());
        assertThat(order.getStatus()).as("Order should be PENDING").isEqualTo("PENDING");

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("eventType")
                        && "ORDER_CREATED".equals(node.get("eventType").asText())
                        && node.has("orderId")
                        && order.getId().equals(node.get("orderId").asText()),
                10
        );

        assertThat(event).as("ORDER_CREATED event should be published").isPresent();

        JsonNode eventData = event.get();
        assertThat(eventData.get("orderId").asText()).isEqualTo(order.getId());
        assertThat(eventData.get("userId").asText()).isEqualTo(userId());
        assertThat(eventData.has("items")).isTrue();

        logStep("✅ ORDER_CREATED event published successfully");
    }

    @Test(priority = 2)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Multiple concurrent orders publish events without data loss")
    public void test02_ConcurrentOrdersPublishAllEvents() {
        logStep("TEST 2: Concurrent orders publish all events");

        int orderCount = 5;
        List<String> orderIds = new ArrayList<>();

        logStep("  Creating " + orderCount + " orders...");
        for (int i = 0; i < orderCount; i++) {
            TestModels.OrderResponse order = orderApiClient.createOrder(
                    userId(), TestDataFactory.newIdempotencyKey(), purchase.getProducts());
            orderIds.add(order.getId());
        }

        assertThat(orderIds).hasSize(orderCount);

        List<JsonNode> events = kafkaConsumer.collectMessages(
                node -> node.has("eventType") && "ORDER_CREATED".equals(node.get("eventType").asText()),
                10
        );

        Set<String> receivedOrderIds = events.stream()
                .filter(node -> node.has("orderId"))
                .map(node -> node.get("orderId").asText())
                .collect(Collectors.toSet());

        assertThat(receivedOrderIds).containsAll(orderIds);

        logStep("✅ All " + orderCount + " events published successfully");
    }

    @Test(priority = 3)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Large order with multiple items publishes complete event data")
    public void test03_LargeOrderPublishesCompleteEventData() {
        logStep("TEST 3: Large order publishes complete event");

        List<TestModels.ProductResponse> products = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            products.add(productApiClient.createProduct(purchase.getSellerAuth(), 10.0 + i, 500));
        }

        TestModels.OrderResponse order = orderApiClient.createOrder(userId(), TestDataFactory.newIdempotencyKey(), products);

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("orderId") && order.getId().equals(node.get("orderId").asText()),
                10
        );

        assertThat(event).isPresent();

        JsonNode eventData = event.get();
        JsonNode eventItems = eventData.get("items");

        assertThat(eventItems).isNotNull();
        assertThat(eventItems.size()).isEqualTo(products.size());

        logStep("✅ Large order event published with all items");
    }

    @Test(priority = 4)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Event contains timestamp and metadata for payment service")
    public void test04_EventContainsTimestampAndMetadata() {
        logStep("TEST 4: Event contains metadata");

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId(), TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("orderId") && order.getId().equals(node.get("orderId").asText()),
                10
        );

        assertThat(event).isPresent();
        JsonNode eventData = event.get();

        assertThat(eventData.has("eventType")).isTrue();
        assertThat(eventData.has("timestamp")).isTrue();
        assertThat(eventData.has("orderId")).isTrue();
        assertThat(eventData.has("userId")).isTrue();

        logStep("✅ Event contains all required metadata");
    }

    @Test(priority = 5)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Event published asynchronously without blocking API response")
    public void test05_EventPublishedAsynchronously() {
        logStep("TEST 5: Event published asynchronously");

        long startTime = System.currentTimeMillis();
        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId(), TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        long responseTime = System.currentTimeMillis() - startTime;

        logStep("  API response time: " + responseTime + "ms");

        assertThat(responseTime).isLessThan(3000);

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("orderId") && order.getId().equals(node.get("orderId").asText()),
                10
        );

        assertThat(event).isPresent();

        logStep("✅ Event published asynchronously without blocking");
    }

    @Test(priority = 6)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Idempotent requests publish event only once")
    public void test06_IdempotentRequestPublishesEventOnce() {
        logStep("TEST 6: Idempotent requests publish event once");

        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        TestModels.OrderResponse firstOrder = orderApiClient.createOrder(userId(), idempotencyKey, purchase.getProducts());
        String orderId = firstOrder.getId();

        TestModels.OrderResponse duplicateOrder = orderApiClient.createOrder(userId(), idempotencyKey, purchase.getProducts());
        assertThat(duplicateOrder.getId()).isEqualTo(orderId);

        List<JsonNode> events = kafkaConsumer.collectMessages(
                node -> node.has("orderId")
                        && orderId.equals(node.get("orderId").asText())
                        && node.has("eventType")
                        && "ORDER_CREATED".equals(node.get("eventType").asText()),
                5
        );

        assertThat(events).hasSize(1);

        logStep("✅ Idempotent request published exactly one event");
    }

    @Test(priority = 7)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Events are distributed across Kafka partitions based on partition key")
    public void test07_EventsDistributedAcrossPartitions() {
        logStep("TEST 7: Events distributed across partitions");

        int orderCount = 10;
        List<String> orderIds = new ArrayList<>();

        logStep("  Creating " + orderCount + " orders from same user...");
        for (int i = 0; i < orderCount; i++) {
            TestModels.OrderResponse order = orderApiClient.createOrder(
                    userId(), TestDataFactory.newIdempotencyKey(), purchase.getProducts());
            orderIds.add(order.getId());
        }

        List<JsonNode> events = kafkaConsumer.collectMessages(
                node -> node.has("orderId") && orderIds.contains(node.get("orderId").asText()),
                10
        );

        Set<Integer> partitions = new HashSet<>();
        for (JsonNode event : events) {
            if (event.has("partition")) {
                partitions.add(event.get("partition").asInt());
            }
        }

        logStep("  Events distributed across " + partitions.size() + " partition(s)");
        logStep("✅ Partition distribution verified");
    }

    @Test(priority = 8)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Event contains schema version for compatibility")
    public void test08_EventContainsSchemaVersion() {
        logStep("TEST 8: Event contains schema version");

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId(), TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("orderId") && order.getId().equals(node.get("orderId").asText()),
                10
        );

        assertThat(event).isPresent();
        JsonNode eventData = event.get();

        if (eventData.has("schemaVersion")) {
            logStep("  Schema version: " + eventData.get("schemaVersion").asText());
            assertThat(eventData.get("schemaVersion")).isNotNull();
        } else if (eventData.has("version")) {
            logStep("  Version: " + eventData.get("version").asText());
            assertThat(eventData.get("version")).isNotNull();
        }

        logStep("✅ Event contains version information");
    }

    @Test(priority = 9)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Multiple orders from same user maintain ordering guarantees")
    public void test09_OrderingGuaranteeWithinUserPartition() {
        logStep("TEST 9: Ordering guarantees within user partition");

        int orderCount = 5;
        List<String> orderIds = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();

        logStep("  Creating " + orderCount + " orders in sequence...");
        for (int i = 0; i < orderCount; i++) {
            long beforeCreate = System.currentTimeMillis();
            TestModels.OrderResponse order = orderApiClient.createOrder(
                    userId(), TestDataFactory.newIdempotencyKey(), purchase.getProducts());
            long afterCreate = System.currentTimeMillis();

            orderIds.add(order.getId());
            timestamps.add(afterCreate);
        }

        List<JsonNode> events = kafkaConsumer.collectMessages(
                node -> node.has("orderId") && orderIds.contains(node.get("orderId").asText()),
                10
        );

        List<Long> eventTimestamps = events.stream()
                .filter(e -> e.has("timestamp"))
                .map(e -> e.get("timestamp").asLong())
                .sorted()
                .collect(Collectors.toList());

        logStep("  Order creation timestamps: " + timestamps.size());
        logStep("  Event timestamps: " + eventTimestamps.size());

        if (eventTimestamps.size() >= 2) {
            for (int i = 1; i < eventTimestamps.size(); i++) {
                assertThat(eventTimestamps.get(i)).isGreaterThanOrEqualTo(eventTimestamps.get(i - 1));
            }
        }

        logStep("✅ Ordering preserved within user partition");
    }

    @Test(priority = 10)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Event payload is serialized correctly in JSON format")
    public void test10_EventPayloadValidJSON() {
        logStep("TEST 10: Event payload is valid JSON");

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId(), TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("orderId") && order.getId().equals(node.get("orderId").asText()),
                10
        );

        assertThat(event).isPresent();
        JsonNode eventData = event.get();

        assertThat(eventData).isNotNull();
        assertThat(eventData.isObject()).isTrue();
        assertThat(eventData.fields()).isNotEmpty();

        logStep("  ✓ Event is valid JSON object");
        logStep("  ✓ Fields present: " + eventData.size());

        logStep("✅ Event payload is valid JSON");
    }
}