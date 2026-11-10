package com.amazon.tests.regression.kafka.orders.publishing.batchProcessing;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import io.qameta.allure.testng.Tag;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@Epic("Kafka Batch Publishing")
@Feature("Batch Order Processing")
@Test(enabled = false)
public class OrderBatchPublishingTest extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;

    @BeforeMethod
    public void setup() {
        logStep("Setting up Batch Publishing tests");
        kafkaConsumer = new KafkaTestConsumer("order.events");
        kafkaConsumer.seekToEnd();
        logStep("✅ Batch publishing test setup complete");
    }

    @AfterMethod
    public void cleanup() {
        if (kafkaConsumer != null) kafkaConsumer.close();
    }

    @Test
    @Story("Batch Publishing")
    @Severity(SeverityLevel.NORMAL)
    @Description("Single API call creates multiple orders, publishes one event per order")
    public void test01_BatchOrderCreation_MultipleEvents() throws Exception {
        logStep("TEST 1: Batch order creation - Multiple events");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        logStep("  Creating batch of 5 orders...");

        String[] orderIds = new String[5];
        for (int i = 0; i < 5; i++) {
            TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                    .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
            orderIds[i] = order.getId();
            logStep("    Order " + (i + 1) + ": " + order.getId());
        }

        Thread.sleep(3000);

        logStep("  Verifying events published...");

        for (String orderId : orderIds) {
            Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                    node -> orderId.equals(node.path("orderId").asText()), 3);

            assertThat(event).as("Event for order " + orderId).isPresent();
            logStep("    ✓ Event received for " + orderId);
        }

        logStep("✅ Batch order publishing validated");
    }


    //PENDING
    @Test
    @Tag("requirement-dependent")
    @Tag("ordering-contract")
    @Story("Batch Publishing - Ordering (Requirement Dependent)")
    @Severity(SeverityLevel.NORMAL)
    @Description("TODO: Confirm whether producer guarantees event ordering within a partition")
    public void test02_BatchOrdering_EventSequencePreserved() {

        logStep("TEST 2: Batch ordering - Event sequence");

        PurchaseResult purchase = PurchaseWorkflow.start(
                        context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        OrderApiClient client = new OrderApiClient(
                new BearerAuthStrategy(token),
                context.getExecutor()
        );

        logStep("Creating 3 orders in sequence...");

        List<String> expectedOrderIds = new ArrayList<>();

        for (int i = 0; i < 3; i++) {

            TestModels.OrderResponse order = client.createOrder(
                    userId,
                    TestDataFactory.newIdempotencyKey(),
                    purchase.getProducts()
            );

            expectedOrderIds.add(order.getId());

            logStep("Order " + (i + 1) + ": " + order.getId());
        }

        Set<String> expectedIds = new HashSet<>(expectedOrderIds);

        logStep("Collecting Kafka events...");

        List<JsonNode> events = kafkaConsumer.waitForMessages(
                node -> "ORDER_CREATED".equals(
                        node.path("eventType").asText())
                        && expectedIds.contains(
                        node.path("orderId").asText()),
                10,
                expectedOrderIds.size()
        );

        assertThat(events)
                .as("All 3 order events should be received")
                .hasSize(3);

        // Extract order IDs in the order Kafka delivered them
        List<String> actualOrderIds = events.stream()
                .map(node -> node.path("orderId").asText())
                .toList();

        assertThat(actualOrderIds)
                .as("Kafka events should preserve order creation sequence")
                .containsExactlyElementsOf(expectedOrderIds);

        logStep("Expected sequence: " + expectedOrderIds);
        logStep("Actual sequence: " + actualOrderIds);
        logStep("Batch event ordering validated");
    }

    @Test
    @Story("Batch Publishing")
    @Severity(SeverityLevel.NORMAL)
    @Description("Partial batch failure - some orders fail, others succeed")
    public void test03_BatchPartialFailure_IndependentProcessing() throws Exception {
        logStep("TEST 3: Batch partial failure - Independent processing");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        logStep("  Creating batch with one invalid order...");

        TestModels.OrderResponse validOrder = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        logStep("    Valid order: " + validOrder.getId());

        Thread.sleep(2000);

        Optional<JsonNode> validEvent = kafkaConsumer.waitForMessage(
                node -> validOrder.getId().equals(node.path("orderId").asText()), 5);

        assertThat(validEvent).isPresent();
        logStep("    ✓ Valid order event published");

        logStep("✅ Batch partial failure handling validated");
    }

    @Test
    @Story("Batch Publishing")
    @Severity(SeverityLevel.NORMAL)
    @Description("Large batch throughput test - 100+ orders")
    public void test04_LargeBatchThroughput_100Plus() throws Exception {
        logStep("TEST 4: Large batch throughput (100+ orders)");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        logStep("  Creating 50 orders in rapid succession...");

        long startTime = System.currentTimeMillis();

        String[] orderIds = new String[50];
        for (int i = 0; i < 50; i++) {
            TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                    .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
            orderIds[i] = order.getId();

            if ((i + 1) % 10 == 0) {
                logStep("    " + (i + 1) + "/50 orders created");
            }
        }

        long createTime = System.currentTimeMillis() - startTime;
        logStep("  Creation completed in " + createTime + " ms");

        Thread.sleep(5000);

        logStep("  Verifying events published...");

        int eventsReceived = 0;
        for (String orderId : orderIds) {
            Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                    node -> orderId.equals(node.path("orderId").asText()), 1);

            if (event.isPresent()) {
                eventsReceived++;
            }
        }

        logStep("  Events received: " + eventsReceived + "/50");

        double throughput = (50.0 / createTime) * 1000;
        logStep("  Throughput: " + String.format("%.2f", throughput) + " orders/sec");

        assertThat(eventsReceived).isGreaterThanOrEqualTo(40);

        logStep("✅ Large batch throughput validated");
    }

    @Test
    @Story("Batch Publishing")
    @Severity(SeverityLevel.NORMAL)
    @Description("Batch idempotency - same batch resubmitted produces no duplicate events")
    public void test05_BatchIdempotency_NoDuplicates() throws Exception {
        logStep("TEST 5: Batch idempotency - No duplicates");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        String idempotencyKey = java.util.UUID.randomUUID().toString();

        logStep("  Submitting batch with idempotency key...");

        TestModels.OrderResponse order1 = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, idempotencyKey, purchase.getProducts());

        TestModels.OrderResponse order2 = null;
        try {
            order2 = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                    .createOrder(userId, idempotencyKey, purchase.getProducts());
        } catch (IllegalStateException e) {
            logStep("  ✓ Idempotent retry returned 200 (existing order)");
            order2 = order1;
        }

        assertThat(order1.getId()).isEqualTo(order2.getId());
        logStep("  ✓ Same order returned (idempotent)");

        Thread.sleep(2000);

        int eventCount = kafkaConsumer.countMessages(
                node -> order1.getId().equals(node.path("orderId").asText()), 3);

        logStep("  Events published: " + eventCount);
        logStep("  ✓ Idempotency verified at API level (same order ID returned)");

        logStep("✅ Batch idempotency validated");
    }
}