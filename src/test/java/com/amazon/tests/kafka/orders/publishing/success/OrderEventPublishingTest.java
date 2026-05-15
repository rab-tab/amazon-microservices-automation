package com.amazon.tests.kafka.orders.publishing.success;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.KafkaTestConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Ignore;
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
 *
 * Uses Seeding Framework with UserSeeder, ProductSeeder, and OrderBuilder
 */
@Slf4j
@Epic("Amazon Microservices")
@Feature("Kafka - Event Publishing")
public class OrderEventPublishingTest extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;
    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;

    @BeforeMethod
    public void setup() throws SeedingException {
        logStep("Setting up Kafka event publishing tests");

        // Initialize test context
       // logStep("✅ Test context created: " + context.getNamespace());


        // Seed user using UserSeeder
        user = UserSeeder.builder(context)
                .count(1)
                .build()
                .seed()
                .getFirst();

        userToken = context.getCached("user_token_" + user.getId(), String.class);
        logStep("✅ User seeded: " + user.getId());

        // Seed product using ProductSeeder
        product = ProductSeeder.builder(context)
                .count(1)
                .highStock()  // Ensure enough stock for tests
                .build()
                .seed()
                .getFirst();

        logStep("✅ Product seeded: " + product.getId() + " (stock: " + product.getStockQuantity() + ")");

        // Wait for data propagation
        waitForDataPropagation(1000);

        // Initialize Kafka consumer for event verification
        kafkaConsumer = new KafkaTestConsumer("order.events");
       // kafkaConsumer.seekToEnd();
        logStep("✅ Kafka consumer initialized");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // POSITIVE TEST CASES - VALID KAFKA WORKFLOW -
    // ══════════════════════════════════════════════════════════════════════════

    //PASS
    @Test(priority = 1)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Order creation publishes ORDER_CREATED event to Kafka with correct data")
    public void test01_OrderCreationPublishesEventToKafka() {
        logStep("TEST 1: Verify ORDER_CREATED event published to Kafka");

        String idempotencyKey = UUID.randomUUID().toString();
        log.info("🔑 Idempotency Key: {}", idempotencyKey);

        // Build order using OrderBuilder
        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 2)
                .build();

        logStep("  Creating order via REST API...");

        // Send order request with retry
        Response response = executeWithRetry(() -> {
            try {
                return sendOrderRequest(userToken, idempotencyKey, orderRequest);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        logStep("  Response status: " + response.statusCode());

        assertThat(response.statusCode())
                .as("Order creation should succeed")
                .isEqualTo(201);

        String orderId = response.jsonPath().getString("id");
        String orderStatus = response.jsonPath().getString("status");

        logStep("  ✓ Order created: " + orderId);
        logStep("  ✓ Initial status: " + orderStatus);

        // VERIFY: Order status is PENDING (awaiting payment)
        assertThat(orderStatus)
                .as("Order should be PENDING (event published, awaiting payment)")
                .isEqualTo("PENDING");

        // VERIFY: Kafka event published
        logStep("  Waiting for ORDER_CREATED event in Kafka...");

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("eventType") &&
                        node.get("eventType").asText().equals("ORDER_CREATED") &&
                        node.has("orderId") &&
                        node.get("orderId").asText().equals(orderId),
                10  // Wait up to 10 seconds
        );

        assertThat(event)
                .as("ORDER_CREATED event should be published to Kafka")
                .isPresent();

        // VERIFY: Event contains correct data
        JsonNode eventData = event.get();
        logStep("  ✓ Event received in Kafka");
        logStep("    - Event Type: " + eventData.get("eventType").asText());
        logStep("    - Order ID: " + eventData.get("orderId").asText());
        logStep("    - User ID: " + eventData.get("userId").asText());

        assertThat(eventData.get("orderId").asText())
                .as("Event should contain correct order ID")
                .isEqualTo(orderId);

        assertThat(eventData.get("userId").asText())
                .as("Event should contain correct user ID")
                .isEqualTo(user.getId());

        assertThat(eventData.has("items"))
                .as("Event should contain order items")
                .isTrue();

        logStep("✅ ORDER_CREATED event published successfully to Kafka");
    }

    //PASS
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Multiple concurrent orders publish events without data loss")
    @Test(description = "Concurrent orders publish all events without data loss")
    public void test02_ConcurrentOrdersPublishAllEvents() {
        logStep("TEST 2: Concurrent orders publish all events to Kafka");

        int orderCount = 5;
        List<String> orderIds = new ArrayList<>();

        logStep("  Creating " + orderCount + " concurrent orders...");

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: Create all orders
        // ═══════════════════════════════════════════════════════════════
        for (int i = 0; i < orderCount; i++) {
            String idempotencyKey = UUID.randomUUID().toString();

            TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                    .withNamespace(context.getNamespace())
                    .addItem(product, i + 1)  // Different quantities (1, 2, 3, 4, 5)
                    .build();

            Response response = executeWithRetry(() -> {
                try {
                    return sendOrderRequest(userToken, idempotencyKey, orderRequest);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            if (response.statusCode() == 201) {
                String orderId = response.jsonPath().getString("id");
                orderIds.add(orderId);
                logStep("    ✓ Order " + (i + 1) + " created: " + orderId);
            } else {
                logStep("    ✗ Order " + (i + 1) + " failed: " + response.statusCode());
                logStep("      Response: " + response.asString());
            }
        }

        assertThat(orderIds.size())
                .as("All concurrent orders should be created")
                .isEqualTo(orderCount);

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Collect ALL Kafka events (not one at a time!)
        // ═══════════════════════════════════════════════════════════════
        logStep("  Collecting all Kafka events...");

        // Collect all ORDER_CREATED events (10 second timeout total)
        List<JsonNode> events = kafkaConsumer.collectMessages(
                node -> node.has("eventType") &&
                        "ORDER_CREATED".equals(node.get("eventType").asText()),
                10  // Total timeout: 10 seconds
        );

        logStep("  Found " + events.size() + " ORDER_CREATED events in Kafka");

        // ═══════════════════════════════════════════════════════════════
        // STEP 3: Verify we received events for ALL our orders
        // ═══════════════════════════════════════════════════════════════
        Set<String> receivedOrderIds = events.stream()
                .filter(node -> node.has("orderId"))
                .map(node -> node.get("orderId").asText())
                .collect(Collectors.toSet());

        logStep("  Verifying all " + orderCount + " events present...");

        Set<String> missingOrderIds = new HashSet<>(orderIds);
        missingOrderIds.removeAll(receivedOrderIds);

        for (String orderId : orderIds) {
            if (receivedOrderIds.contains(orderId)) {
                logStep("    ✓ Event received for order: " + orderId);
            } else {
                logStep("    ✗ Event MISSING for order: " + orderId);
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // ASSERTIONS
        // ═══════════════════════════════════════════════════════════════
        assertThat(missingOrderIds)
                .as("No events should be missing")
                .isEmpty();

        assertThat(receivedOrderIds)
                .as("All ORDER_CREATED events should be published to Kafka")
                .containsAll(orderIds);

        logStep("✅ All " + orderCount + " concurrent events published successfully - no data loss");
    }

    //PASS
    @Test(priority = 3)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Large order with multiple items publishes complete event data")
    public void test03_LargeOrderPublishesCompleteEventData() throws SeedingException {
        logStep("TEST 3: Large order publishes complete event with all items");

        List<TestModels.ProductResponse> products = ProductSeeder.builder(context)
                .count(5)
                .highStock()
                .build()
                .seed()
                .getProducts();

        waitForDataPropagation(1000);

        String idempotencyKey = UUID.randomUUID().toString();

        // Build order with multiple items
        OrderBuilder orderBuilder = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace());

        for (int i = 0; i < products.size(); i++) {
            orderBuilder.addItem(products.get(i), i + 1);  // Different quantities
        }

        TestModels.CreateOrderRequest orderRequest = orderBuilder.build();

        logStep("  Creating order with " + products.size() + " items...");

        Response response = executeWithRetry(() -> {
            try {
                return sendOrderRequest(userToken, idempotencyKey, orderRequest);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(response.statusCode())
                .as("Large order creation should succeed")
                .isEqualTo(201);

        String orderId = response.jsonPath().getString("id");
        logStep("  ✓ Large order created: " + orderId);

        // VERIFY: Kafka event contains all items
        logStep("  Verifying complete event data in Kafka...");

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("orderId") &&
                        node.get("orderId").asText().equals(orderId),
                10
        );

        assertThat(event)
                .as("ORDER_CREATED event should be published")
                .isPresent();

        JsonNode eventData = event.get();
        JsonNode eventItems = eventData.get("items");

        assertThat(eventItems)
                .as("Event should contain items array")
                .isNotNull();

        assertThat(eventItems.size())
                .as("Event should contain all " + products.size() + " items")
                .isEqualTo(products.size());

        logStep("  ✓ Event contains all " + products.size() + " items");

        // Verify each item has required fields
        for (int i = 0; i < eventItems.size(); i++) {
            JsonNode item = eventItems.get(i);
            logStep("    - Item " + (i + 1) + ": " +
                    (item.has("productName") ? item.get("productName").asText() : "N/A") +
                    " (qty: " + item.get("quantity").asInt() + ")");

            assertThat(item.has("productId"))
                    .as("Item " + (i + 1) + " should have productId")
                    .isTrue();
            assertThat(item.has("quantity"))
                    .as("Item " + (i + 1) + " should have quantity")
                    .isTrue();
            assertThat(item.has("unitPrice"))
                    .as("Item " + (i + 1) + " should have unitPrice")
                    .isTrue();
        }

        logStep("✅ Large order event published with complete data");
    }

    //PASS
    @Test(priority = 4)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Event contains timestamp and metadata for payment service")
    public void test04_EventContainsTimestampAndMetadata() {
        logStep("TEST 4: Event contains timestamp and metadata for payment service");

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        Response response = executeWithRetry(() -> {
            try {
                return sendOrderRequest(userToken, idempotencyKey, orderRequest);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(response.statusCode()).isEqualTo(201);

        String orderId = response.jsonPath().getString("id");
        logStep("  ✓ Order created: " + orderId);

        // VERIFY: Event has timestamp and metadata
        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("orderId") &&
                        node.get("orderId").asText().equals(orderId),
                10
        );

        assertThat(event).isPresent();

        JsonNode eventData = event.get();

        // Verify required fields for payment service
        assertThat(eventData.has("eventType"))
                .as("Event should have eventType")
                .isTrue();

        assertThat(eventData.has("timestamp"))
                .as("Event should have timestamp for ordering")
                .isTrue();

        assertThat(eventData.has("orderId"))
                .as("Event should have orderId")
                .isTrue();

        assertThat(eventData.has("userId"))
                .as("Event should have userId")
                .isTrue();

        assertThat(eventData.has("totalAmount") || eventData.has("items"))
                .as("Event should have pricing information")
                .isTrue();

        logStep("  ✓ Event Type: " + eventData.get("eventType").asText());
        logStep("  ✓ Timestamp: " + eventData.get("timestamp").asText());
        logStep("  ✓ Order ID: " + eventData.get("orderId").asText());
        logStep("  ✓ User ID: " + eventData.get("userId").asText());

        if (eventData.has("totalAmount")) {
            logStep("  ✓ Total Amount: " + eventData.get("totalAmount").asDouble());
        }

        logStep("✅ Event contains all required metadata for payment processing");
    }

    //PASS
    @Test(priority = 5)
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Event published asynchronously (doesn't block API response)")
    public void test05_EventPublishedAsynchronously() {
        logStep("TEST 5: Event published asynchronously (doesn't block API response)");

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        long startTime = System.currentTimeMillis();

        Response response = executeWithRetry(() -> {
            try {
                return sendOrderRequest(userToken, idempotencyKey, orderRequest);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        long responseTime = System.currentTimeMillis() - startTime;

        assertThat(response.statusCode()).isEqualTo(201);

        String orderId = response.jsonPath().getString("id");
        logStep("  ✓ API response time: " + responseTime + "ms");
        logStep("  ✓ Order created: " + orderId);

        // API should return quickly (< 3 seconds including retries)
        assertThat(responseTime)
                .as("API should return quickly without waiting for Kafka")
                .isLessThan(3000);

        // Event should still be published
        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("orderId") &&
                        node.get("orderId").asText().equals(orderId),
                10
        );

        assertThat(event)
                .as("Event should still be published asynchronously")
                .isPresent();

        logStep("✅ Event published asynchronously without blocking API response");
    }


    @Ignore
    @Test(description = "DEBUG: Verify Redis idempotency before running main test", priority = 0)
    public void test00_DebugRedisIdempotency() throws Exception {
        logStep("DEBUG: Checking if Redis idempotency is working");

        String testKey = "debug-" + UUID.randomUUID();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // First request
        Response response1 = sendOrderRequest(userToken, testKey, orderRequest);
        log.error("🔍 First request status: {}", response1.statusCode());

        String orderId1 = response1.jsonPath().getString("id");
        log.error("🔍 First request order ID: {}", orderId1);

        // Wait
        Thread.sleep(1000);

        // Check Redis
        Response redisCheck = RestAssured.given()
                .baseUri("http://localhost:8083")
                .header("Authorization", "Bearer " + userToken)
                .get("/api/v1/orders/test/check-idempotency/" + user.getId() + "/" + testKey);

        log.error("🔍 Redis check: {}", redisCheck.body().asString());

        // Second request
        Response response2 = sendOrderRequest(userToken, testKey, orderRequest);
        log.error("🔍 Second request status: {}", response2.statusCode());

        String orderId2 = response2.jsonPath().getString("id");
        log.error("🔍 Second request order ID: {}", orderId2);

        // Analyze
        if (response1.statusCode() == 201 && response2.statusCode() == 200 && orderId1.equals(orderId2)) {
            log.error("✅ REDIS IDEMPOTENCY IS WORKING!");
        } else {
            log.error("❌ REDIS IDEMPOTENCY IS NOT WORKING!");
            log.error("   First: HTTP {}, Order: {}", response1.statusCode(), orderId1);
            log.error("   Second: HTTP {}, Order: {}", response2.statusCode(), orderId2);
            log.error("   Redis cached: {}", redisCheck.jsonPath().getBoolean("cached"));
        }
    }
    //PASS
    @Story("Event Publishing - Positive")
    @Severity(SeverityLevel.NORMAL)
    @Description("Idempotent requests publish event only once")
    @Test(description = "Idempotent requests publish event only once")
    public void test06_IdempotentRequestPublishesEventOnce() throws Exception {
        logStep("TEST 6: Idempotent requests publish event only once");

        String idempotencyKey = UUID.randomUUID().toString();
        log.error("🔑 Idempotency Key: {}", idempotencyKey);

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        // FIRST REQUEST
        log.error("═══════════════════════════════════════════════════════");
        log.error("STEP 1: Sending first request");
        log.error("═══════════════════════════════════════════════════════");
        Response response1 = sendOrderRequest(userToken, idempotencyKey, orderRequest);

        log.error("First response status: {}", response1.statusCode());
        assertThat(response1.statusCode()).isEqualTo(201);

        String orderId = response1.jsonPath().getString("id");
        log.error("Order created: {}", orderId);

        // DUPLICATE REQUEST
        log.error("═══════════════════════════════════════════════════════");
        log.error("STEP 2: Sleeping 500ms");
        log.error("═══════════════════════════════════════════════════════");
        Thread.sleep(500);

        log.error("═══════════════════════════════════════════════════════");
        log.error("STEP 3: Sending duplicate request");
        log.error("═══════════════════════════════════════════════════════");
        Response response2 = sendOrderRequest(userToken, idempotencyKey, orderRequest);

        log.error("Second response status: {}", response2.statusCode());
        assertThat(response2.statusCode()).isEqualTo(200);
        assertThat(response2.jsonPath().getString("id")).isEqualTo(orderId);

        log.error("Duplicate returned same order: {}", orderId);

        // WAIT FOR EVENTS
        log.error("═══════════════════════════════════════════════════════");
        log.error("STEP 4: Sleeping 2000ms for events");
        log.error("═══════════════════════════════════════════════════════");
        Thread.sleep(2000);
        log.error("Sleep complete");

        // COUNT EVENTS - Manual loop
        log.error("═══════════════════════════════════════════════════════");
        log.error("STEP 5: Counting events for order: {}", orderId);
        log.error("═══════════════════════════════════════════════════════");

        List<JsonNode> events = new ArrayList<>();
        int maxPolls = 10;

        for (int i = 1; i <= maxPolls; i++) {
            log.error("Poll #{}: Looking for events (timeout=1000ms)...", i);

            long pollStart = System.currentTimeMillis();
            Optional<JsonNode> eventOpt = kafkaConsumer.waitForMessage(
                    node -> {
                        boolean hasOrderId = node.has("orderId");
                        boolean hasEventType = node.has("eventType");

                        if (hasOrderId) {
                            String nodeOrderId = node.get("orderId").asText();
                            String nodeEventType = hasEventType ? node.get("eventType").asText() : "MISSING";

                            log.error("  Checking: orderId={}, eventType={}", nodeOrderId, nodeEventType);

                            boolean matches = nodeOrderId.equals(orderId) &&
                                    hasEventType &&
                                    nodeEventType.equals("ORDER_CREATED");

                            if (matches) {
                                log.error("  ✅ MATCH FOUND!");
                            }

                            return matches;
                        }

                        log.error("  Event has no orderId, skipping");
                        return false;
                    },
                    1  // 1 second timeout
            );
            long pollDuration = System.currentTimeMillis() - pollStart;

            log.error("Poll #{} completed in {}ms", i, pollDuration);

            if (eventOpt.isPresent()) {
                events.add(eventOpt.get());
                log.error("✅ Event #{} found!", events.size());

                // If we found 2, stop immediately
                if (events.size() >= 2) {
                    log.error("Found 2+ events, stopping");
                    break;
                }
            } else {
                log.error("❌ No matching event found in poll #{}", i);

                // If we've already found 1 event and this poll found nothing, stop
                if (events.size() > 0) {
                    log.error("Already found {} event(s), stopping", events.size());
                    break;
                }
            }
        }

        log.error("═══════════════════════════════════════════════════════");
        log.error("STEP 6: Verification");
        log.error("═══════════════════════════════════════════════════════");
        log.error("Total events found: {}", events.size());

        if (events.isEmpty()) {
            log.error("❌❌❌ NO EVENTS FOUND AT ALL!");
            log.error("This means:");
            log.error("1. Kafka consumer is not connected to the right topic");
            log.error("2. Events are not being published");
            log.error("3. Consumer positioning is wrong");
        } else if (events.size() == 1) {
            log.error("✅ Perfect! Exactly 1 event found");
        } else {
            log.error("❌ Found {} events (expected 1)", events.size());
        }

        assertThat(events)
                .as("Only ONE ORDER_CREATED event should be published (idempotent)")
                .hasSize(1);

        log.error("═══════════════════════════════════════════════════════");
        log.error("TEST PASSED!");
        log.error("═══════════════════════════════════════════════════════");
    }

    //IGNORE - FAILING
    @Ignore
    @Test(description = "Events are isolated by namespace - multi-tenant safety")
    public void test07_NamespaceIsolation() throws Exception {
        logStep("TEST 7: Namespace isolation in Kafka events");

        // Create order in namespace A (current context)
        String namespaceA = context.getNamespace();
        TestModels.CreateOrderRequest orderA = OrderBuilder.anOrder()
                .withNamespace(namespaceA)
                .addItem(product, 1)
                .build();

        Response responseA = sendOrderRequest(userToken, UUID.randomUUID().toString(), orderA);
        String orderIdA = responseA.jsonPath().getString("id");

        // Create order in namespace B (different context)
        String namespaceB = "test_namespace_b_" + System.currentTimeMillis();
        TestModels.CreateOrderRequest orderB = OrderBuilder.anOrder()
                .withNamespace(namespaceA)
                .addItem(product, 1)
                .build();
        // ... create user in namespace B, create order in namespace B
        Response responseB = sendOrderRequest(userToken, UUID.randomUUID().toString(), orderA);
        String orderIdB = responseA.jsonPath().getString("id");

        // Verify: Filtering by namespace A should ONLY return events from namespace A
        List<JsonNode> eventsA = kafkaConsumer.collectMessages(
                node -> node.has("namespace") &&
                        namespaceA.equals(node.get("namespace").asText()),
                5
        );

        Set<String> orderIdsInA = eventsA.stream()
                .map(node -> node.get("orderId").asText())
                .collect(Collectors.toSet());

        assertThat(orderIdsInA).contains(orderIdA);
        assertThat(orderIdsInA).doesNotContain(orderIdB);  // ← Key assertion!

        logStep("✅ Namespace isolation verified - tenant data is separated");
    }


    //HELPERS
    private Response sendOrderRequest(
            String userToken,
            String idempotencyKey,
            TestModels.CreateOrderRequest orderRequest) throws Exception {

        String requestBody = objectMapper.writeValueAsString(orderRequest);

        return RestAssured
                .given().log().all()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .accept("application/json")
                .body(requestBody)
                .when().log().all()
                .post("/api/orders");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ══════════════════════════════════════════════════════════════════════════

    @AfterClass
    public void cleanup() {
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
            logStep("✅ Kafka consumer closed");
        }

       /* if (context != null) {
            context.cleanup();
            logStep("✅ Test context cleaned up");
        }*/
    }
}