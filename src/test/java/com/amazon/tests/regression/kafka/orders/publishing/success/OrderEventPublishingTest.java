
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

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

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

        // Create customer, seller and product for the test
        purchase = PurchaseWorkflow.start(
                        context.getExecutor(),
                        authStrategy
                )
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        orderApiClient = new OrderApiClient(
                new com.amazon.tests.auth.BearerAuthStrategy(
                        purchase.getCustomer().getAccessToken()
                ),
                context.getExecutor()
        );

        productApiClient = new ProductApiClient(context.getExecutor());

        // Subscribe before test actions so new events can be captured
        kafkaConsumer = new KafkaTestConsumer("order.events");
        kafkaConsumer.seekToEnd();

        logStep("Setup complete — user: "
                + purchase.getCustomer().getUser().getId());
    }

    @AfterMethod
    public void cleanup() {

        if (kafkaConsumer != null) {
            kafkaConsumer.close();
            logStep("Kafka consumer closed");
        }
    }

    private String userId() {
        return purchase.getCustomer().getUser().getId();
    }

    private String token() {
        return purchase.getCustomer().getAccessToken();
    }

    // ---------------------------------------------------------
    // TEST 01: Order creation publishes ORDER_CREATED event
    // ---------------------------------------------------------

    @Test(priority = 1)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Order creation publishes ORDER_CREATED event to Kafka")
    public void test01_OrderCreationPublishesEventToKafka() {

        logStep("TEST 1: Verify ORDER_CREATED event published to Kafka");

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId(),
                TestDataFactory.newIdempotencyKey(),
                purchase.getProducts()
        );

        logStep("Order created: " + order.getId());
        logStep("Initial status: " + order.getStatus());

        assertThat(order.getStatus())
                .as("Order should be PENDING")
                .isEqualTo("PENDING");

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("eventType")
                        && "ORDER_CREATED".equals(
                        node.path("eventType").asText())
                        && node.has("orderId")
                        && order.getId().equals(
                        node.path("orderId").asText()),
                10
        );

        assertThat(event)
                .as("ORDER_CREATED event should be published")
                .isPresent();

        JsonNode eventData = event.get();

        assertThat(eventData.path("orderId").asText())
                .isEqualTo(order.getId());

        assertThat(eventData.path("userId").asText())
                .isEqualTo(userId());

        assertThat(eventData.has("items"))
                .isTrue();

        logStep("TEST 1 PASSED");
    }

    // ---------------------------------------------------------
    // TEST 02: Concurrent orders publish all events
    // ---------------------------------------------------------

    @Test(priority = 2)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Multiple concurrent orders publish events without data loss")
    public void test02_ConcurrentOrdersPublishAllEvents() {

        logStep("TEST 2: Concurrent orders publish all events");

        int orderCount = 5;

        List<String> orderIds = new ArrayList<>();

        logStep("Creating " + orderCount + " orders...");

        for (int i = 0; i < orderCount; i++) {

            TestModels.OrderResponse order = orderApiClient.createOrder(
                    userId(),
                    TestDataFactory.newIdempotencyKey(),
                    purchase.getProducts()
            );

            orderIds.add(order.getId());
        }

        assertThat(orderIds)
                .hasSize(orderCount)
                .doesNotHaveDuplicates();

        List<JsonNode> events = kafkaConsumer.collectMessages(
                node -> node.has("eventType")
                        && "ORDER_CREATED".equals(
                        node.path("eventType").asText())
                        && node.has("orderId")
                        && orderIds.contains(
                        node.path("orderId").asText()),
                10
        );

        Set<String> receivedOrderIds = events.stream()
                .map(node -> node.path("orderId").asText())
                .collect(Collectors.toSet());

        // Verify no missing or duplicate events
        assertEquals(
                events.size(),
                orderCount,
                "Expected exactly one ORDER_CREATED event per order"
        );

        assertEquals(
                receivedOrderIds.size(),
                orderCount,
                "Expected unique ORDER_CREATED events"
        );

        assertThat(receivedOrderIds)
                .containsExactlyInAnyOrderElementsOf(orderIds);

        logStep("TEST 2 PASSED: All orders published exactly once");
    }

    // ---------------------------------------------------------
    // TEST 03: Large order publishes complete event data
    // ---------------------------------------------------------

    @Test(priority = 3)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Large order with multiple items publishes complete event data")
    public void test03_LargeOrderPublishesCompleteEventData() throws Exception {

        logStep("TEST 3: Large order publishes complete event");

        List<TestModels.ProductResponse> products = new ArrayList<>();

        for (int i = 0; i < 5; i++) {

            products.add(
                    productApiClient.createProduct(
                            purchase.getSellerAuth(),
                            10.0 + i,
                            500
                    )
            );
        }

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId(),
                TestDataFactory.newIdempotencyKey(),
                products
        );

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("eventType")
                        && "ORDER_CREATED".equals(
                        node.path("eventType").asText())
                        && node.has("orderId")
                        && order.getId().equals(
                        node.path("orderId").asText()),
                10
        );

        assertThat(event).isPresent();

        JsonNode eventData = event.get();
        JsonNode eventItems = eventData.get("items");

        assertThat(eventItems)
                .as("Event should contain order items")
                .isNotNull();

        assertThat(eventItems.size())
                .isEqualTo(products.size());

        logStep("TEST 3 PASSED: All order items published");
    }

    // ---------------------------------------------------------
    // TEST 04: ORDER_CREATED event contract
    // ---------------------------------------------------------

    @Test(priority = 4)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify Kafka event payload structure, metadata and schema version")
    public void test04_OrderCreatedEventContract() {

        logStep("TEST 4: Verify ORDER_CREATED event contract");

        String expectedUserId = userId();

        TestModels.OrderResponse order = orderApiClient.createOrder(
                expectedUserId,
                TestDataFactory.newIdempotencyKey(),
                purchase.getProducts()
        );

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("eventType")
                        && "ORDER_CREATED".equals(
                        node.path("eventType").asText())
                        && node.has("orderId")
                        && order.getId().equals(
                        node.path("orderId").asText()),
                10
        );

        assertThat(event)
                .as("ORDER_CREATED event should be published")
                .isPresent();

        JsonNode eventData = event.get();

        // 1. Validate JSON structure
        assertThat(eventData.isObject()).isTrue();
        assertThat(eventData.size()).isGreaterThan(0);

        // 2. Validate required metadata
        assertThat(eventData.hasNonNull("eventType")).isTrue();
        assertThat(eventData.hasNonNull("orderId")).isTrue();
        assertThat(eventData.hasNonNull("userId")).isTrue();
        assertThat(eventData.hasNonNull("timestamp")).isTrue();

        // 3. Validate event data
        assertThat(eventData.path("eventType").asText())
                .isEqualTo("ORDER_CREATED");

        assertThat(eventData.path("orderId").asText())
                .isEqualTo(order.getId());

        assertThat(eventData.path("userId").asText())
                .isEqualTo(expectedUserId);

        // 4. Validate timestamp
        assertThat(eventData.path("timestamp").asText())
                .isNotBlank();

        // 5. Validate schema version
        JsonNode version = eventData.hasNonNull("schemaVersion")
                ? eventData.get("schemaVersion")
                : eventData.get("version");

        assertThat(version)
                .as("Event should contain schemaVersion or version")
                .isNotNull();

        assertThat(version.asText()).isNotBlank();

        logStep("Schema version: " + version.asText());
        logStep("Event fields: " + eventData.size());

        logStep("TEST 4 PASSED: Event contract validated");
    }

    // ---------------------------------------------------------
    // TEST 05: API returns promptly and event is published
    // ---------------------------------------------------------

    @Test(priority = 5)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Order creation API returns promptly and its event is eventually published")
    public void test05_OrderCreationReturnsPromptlyAndEventIsPublished() {

        logStep("TEST 5: Verify prompt API response and eventual event publication");

        long startTime = System.currentTimeMillis();

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId(),
                TestDataFactory.newIdempotencyKey(),
                purchase.getProducts()
        );

        long responseTime = System.currentTimeMillis() - startTime;

        logStep("API response time: " + responseTime + "ms");

        assertThat(responseTime)
                .as("Order creation API should return within the expected threshold")
                .isLessThan(3000);

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("eventType")
                        && "ORDER_CREATED".equals(
                        node.path("eventType").asText())
                        && node.has("orderId")
                        && order.getId().equals(
                        node.path("orderId").asText()),
                10
        );

        assertThat(event)
                .as("ORDER_CREATED event should eventually be published")
                .isPresent();

        logStep("TEST 5 PASSED");
    }

    // ---------------------------------------------------------
    // TEST 06: Idempotent request publishes event only once
    // ---------------------------------------------------------

    @Test(priority = 6)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Idempotent requests publish event only once")
    public void test06_IdempotentRequestPublishesEventOnce() {

        logStep("TEST 6: Idempotent requests publish event once");

        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        TestModels.OrderResponse firstOrder = orderApiClient.createOrder(
                userId(),
                idempotencyKey,
                purchase.getProducts()
        );

        String orderId = firstOrder.getId();

        TestModels.OrderResponse duplicateOrder = orderApiClient.createOrder(
                userId(),
                idempotencyKey,
                purchase.getProducts()
        );

        assertThat(duplicateOrder.getId())
                .isEqualTo(orderId);

        List<JsonNode> events = kafkaConsumer.collectMessages(
                node -> node.has("orderId")
                        && orderId.equals(node.path("orderId").asText())
                        && node.has("eventType")
                        && "ORDER_CREATED".equals(
                        node.path("eventType").asText()),
                5
        );

        assertThat(events)
                .as("Idempotent requests should publish exactly one event")
                .hasSize(1);

        logStep("TEST 6 PASSED: Idempotent request published exactly once");
    }

    // ---------------------------------------------------------
    // TEST 07: Order ID is used as Kafka partition key
    // ---------------------------------------------------------

    @Test(priority = 7)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Events are published using orderId as the Kafka partition key")
    public void test07_OrderIdIsUsedAsKafkaPartitionKey() throws Exception {

        logStep("TEST 7: Verify orderId is used as Kafka partition key");

        int orderCount = 10;

        List<String> expectedOrderIds = new ArrayList<>();

        logStep("Creating " + orderCount + " orders...");

        for (int i = 0; i < orderCount; i++) {

            TestModels.OrderResponse order = orderApiClient.createOrder(
                    userId(),
                    TestDataFactory.newIdempotencyKey(),
                    purchase.getProducts()
            );

            expectedOrderIds.add(order.getId());
        }

        logStep("Collecting ORDER_CREATED events from Kafka...");

        List<ConsumerRecord<String, String>> records =
                kafkaConsumer.collectRecords(
                        record -> {
                            try {
                                JsonNode event = objectMapper.readTree(
                                        record.value()
                                );

                                return event.has("orderId")
                                        && event.has("eventType")
                                        && "ORDER_CREATED".equals(
                                        event.path("eventType").asText())
                                        && expectedOrderIds.contains(
                                        event.path("orderId").asText());

                            } catch (Exception e) {
                                return false;
                            }
                        },
                        10
                );

        // Verify expected event count
        assertEquals(
                records.size(),
                orderCount,
                "Expected one ORDER_CREATED event per order"
        );

        Set<String> actualOrderIds = new HashSet<>();
        Set<Integer> partitions = new HashSet<>();

        logStep("Verifying Kafka record keys and partitions...");

        for (ConsumerRecord<String, String> record : records) {

            JsonNode event = objectMapper.readTree(record.value());

            String orderId = event.path("orderId").asText();
            String kafkaKey = record.key();

            // Verify Kafka key matches orderId
            assertEquals(
                    kafkaKey,
                    orderId,
                    "Kafka record key should match orderId"
            );

            // Verify valid partition
            assertTrue(
                    record.partition() >= 0,
                    "Kafka record should have a valid partition"
            );

            actualOrderIds.add(orderId);
            partitions.add(record.partition());

            logStep("Order: " + orderId
                    + " | Key: " + kafkaKey
                    + " | Partition: " + record.partition());
        }

        // Verify no missing or duplicate order events
        assertEquals(
                actualOrderIds.size(),
                orderCount,
                "Expected one unique ORDER_CREATED event per order"
        );

        assertEquals(
                actualOrderIds,
                new HashSet<>(expectedOrderIds),
                "Received order IDs should match created order IDs"
        );

        logStep("Total events received: " + records.size());
        logStep("Unique Kafka keys: " + actualOrderIds.size());
        logStep("Partitions used: " + partitions);

        logStep("TEST 7 PASSED");
    }

    // ---------------------------------------------------------
    // TEST 09: Ordering guarantee within an order partition
    // ---------------------------------------------------------

    @Test(priority = 9)
    @Story("Event Ordering")
    @Severity(SeverityLevel.NORMAL)
    @Description("Events for the same order are published to the same Kafka partition in the correct order")
    public void test09_OrderingGuaranteeWithinOrderPartition() throws Exception {

        logStep("TEST 9: Ordering guarantee within order partition");

        // 1. Create an order using the customer and product from setup
        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId(),
                TestDataFactory.newIdempotencyKey(),
                purchase.getProducts()
        );

        String orderId = order.getId();

        logStep("Created order: " + orderId);

        assertEquals(
                order.getStatus(),
                "PENDING",
                "New order should initially be PENDING"
        );

        // 2. Collect lifecycle events belonging to this order
        List<ConsumerRecord<String, String>> records =
                kafkaConsumer.collectRecords(
                        record -> {
                            try {
                                JsonNode event = objectMapper.readTree(
                                        record.value()
                                );

                                return orderId.equals(
                                        event.path("orderId").asText()
                                );

                            } catch (Exception e) {
                                return false;
                            }
                        },
                        20
                );

        // 3. Separate lifecycle events
        List<ConsumerRecord<String, String>> createdEvents =
                records.stream()
                        .filter(record ->
                                readEventType(record).equals("ORDER_CREATED"))
                        .toList();

        List<ConsumerRecord<String, String>> updatedEvents =
                records.stream()
                        .filter(record ->
                                readEventType(record).equals("ORDER_STATUS_UPDATED"))
                        .toList();

        // 4. Verify expected event counts
        assertEquals(
                createdEvents.size(),
                1,
                "Expected exactly one ORDER_CREATED event"
        );

        assertEquals(
                updatedEvents.size(),
                1,
                "Expected exactly one ORDER_STATUS_UPDATED event"
        );

        ConsumerRecord<String, String> createdRecord = createdEvents.get(0);
        ConsumerRecord<String, String> updatedRecord = updatedEvents.get(0);

        // 5. Verify Kafka keys
        assertEquals(
                createdRecord.key(),
                orderId,
                "ORDER_CREATED key should be the order ID"
        );

        assertEquals(
                updatedRecord.key(),
                orderId,
                "ORDER_STATUS_UPDATED key should be the order ID"
        );

        // 6. Verify same partition
        assertEquals(
                createdRecord.partition(),
                updatedRecord.partition(),
                "Events with the same key should be routed to the same partition"
        );

        // 7. Verify append order using Kafka offsets
        assertTrue(
                createdRecord.offset() < updatedRecord.offset(),
                "ORDER_CREATED must have a lower offset than ORDER_STATUS_UPDATED"
        );

        // 8. Verify event payload statuses
        JsonNode createdEvent = objectMapper.readTree(
                createdRecord.value()
        );

        JsonNode updatedEvent = objectMapper.readTree(
                updatedRecord.value()
        );

        assertEquals(
                createdEvent.path("status").asText(),
                "PENDING",
                "ORDER_CREATED should contain PENDING status"
        );

        assertEquals(
                updatedEvent.path("status").asText(),
                "CONFIRMED",
                "ORDER_STATUS_UPDATED should contain CONFIRMED status"
        );

        logStep("ORDER_CREATED partition: " + createdRecord.partition());
        logStep("ORDER_CREATED offset: " + createdRecord.offset());
        logStep("ORDER_STATUS_UPDATED offset: " + updatedRecord.offset());

        logStep("TEST 9 PASSED: Correct event ordering verified");
    }

    // ---------------------------------------------------------
    // Kafka event helper
    // ---------------------------------------------------------

    private String readEventType(
            ConsumerRecord<String, String> record) {

        try {

            JsonNode event = objectMapper.readTree(record.value());

            return event.path("eventType").asText();

        } catch (Exception e) {

            throw new RuntimeException(
                    "Unable to parse Kafka event: " + record.value(),
                    e
            );
        }
    }
}
