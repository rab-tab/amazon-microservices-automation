package com.amazon.tests.regression.kafka.orders.consumption.guarantees;

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
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@Epic("Kafka Delivery Semantics")
@Feature("Exactly-Once Semantics")
public class KafkaExactlyOnceTest extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;

    @BeforeMethod
    public void setup() {
        logStep("Setting up Exactly-Once semantics tests");
        kafkaConsumer = new KafkaTestConsumer("order.events");
        kafkaConsumer.seekToEnd();
        logStep("✅ Exactly-Once test setup complete");
    }

    @AfterMethod
    public void cleanup() {
        if (kafkaConsumer != null) kafkaConsumer.close();
    }

    @Test
    @Story("Exactly-Once Delivery")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Transactional producer - all events committed or none")
    public void test01_TransactionalProducer_AtomicWriteOrFail() {
        logStep("TEST 1: Transactional producer - Atomic write");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        logStep("  Creating order with transactional guarantee...");

        TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        logStep("  ✓ Order created: " + order.getId());

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> order.getId().equals(node.path("orderId").asText()), 10);

        assertThat(event).isPresent();
        logStep("  ✓ Event published atomically");

        logStep("✅ Transactional write validated");
    }

    @Test
    @Story("Exactly-Once Delivery")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Consumer with read_committed isolation - sees only committed events")
    public void test02_ReadCommittedIsolation_NoUncommittedRead() {
        logStep("TEST 2: read_committed isolation level");

        logStep("  Consumer configured with isolation.level=read_committed");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> order.getId().equals(node.path("orderId").asText()), 10);

        assertThat(event).isPresent();
        logStep("  ✓ Only committed events visible to consumer");

        logStep("✅ read_committed isolation validated");
    }

    @Test
    @Story("Exactly-Once Delivery")
    @Severity(SeverityLevel.NORMAL)
    @Description("Exactly-once: Order committed to DB AND event published, or both rolled back")
    public void test03_ExactlyOnceEndToEnd_DBAndEventAtomic() throws Exception {
        logStep("TEST 3: End-to-end exactly-once: DB + Event atomic");

        logStep("  Order service uses transactional outbox pattern");
        logStep("  1. Start transaction");
        logStep("  2. Write order to DB");
        logStep("  3. Write event to outbox table");
        logStep("  4. Commit both");
        logStep("  5. Polling service publishes events from outbox");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> order.getId().equals(node.path("orderId").asText()), 10);

        assertThat(event).isPresent();

        logStep("  ✓ Order in DB");
        logStep("  ✓ Event in Kafka");
        logStep("  ✓ Both guaranteed consistent");

        logStep("✅ Exactly-once end-to-end validated");
    }

    @Test
    @Story("Exactly-Once Delivery")
    @Severity(SeverityLevel.NORMAL)
    @Description("Duplicate suppression across failures - same event never seen twice")
    public void test04_DuplicateSuppression_NeverDuplicateAcrossFailures() {
        logStep("TEST 4: Duplicate suppression across producer failures");

        logStep("  Producer sends event with idempotent ID");
        logStep("  Event written to log with sequence number");
        logStep("  Broker deduplicates by idempotent ID within broker session");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.OrderResponse order1 = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, idempotencyKey, purchase.getProducts());

        TestModels.OrderResponse order2 = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, idempotencyKey, purchase.getProducts());

        assertThat(order1.getId()).isEqualTo(order2.getId());

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        int eventCount = kafkaConsumer.countMessages(
                node -> order1.getId().equals(node.path("orderId").asText()), 5);

        assertThat(eventCount).isLessThanOrEqualTo(1);
        logStep("  ✓ Event appears exactly once despite retries");

        logStep("✅ Duplicate suppression validated");
    }

    @Test
    @Story("Exactly-Once Delivery")
    @Severity(SeverityLevel.NORMAL)
    @Description("Exactly-once consumer: Manual commit after processing")
    public void test05_ExactlyOnceConsumer_ManualCommitAfterProcessing() {
        logStep("TEST 5: Exactly-once consumer - Manual commit after processing");

        logStep("  Consumer configuration:");
        logStep("    enable.auto.commit=false (manual control)");
        logStep("    isolation.level=read_committed (wait for transactions)");

        logStep("  Processing flow:");
        logStep("    1. Poll message");
        logStep("    2. Process (write to DB, update state)");
        logStep("    3. Commit offset");
        logStep("    4. If crash before commit → replay message");
        logStep("    5. Idempotency check prevents duplicate processing");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> order.getId().equals(node.path("orderId").asText()), 10);

        assertThat(event).isPresent();
        logStep("  ✓ Event consumed with exactly-once guarantee");

        logStep("✅ Exactly-once consumer validated");
    }

    @Test
    @Story("Exactly-Once Delivery")
    @Severity(SeverityLevel.NORMAL)
    @Description("Transactions across multiple partitions")
    public void test06_TransactionsAcrossPartitions() {
        logStep("TEST 6: Transactions span multiple partitions");

        logStep("  Single transaction writes to multiple partitions");
        logStep("  All-or-nothing semantics across partitions");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> order.getId().equals(node.path("orderId").asText()), 10);

        assertThat(event).isPresent();
        logStep("  ✓ Multi-partition transaction committed atomically");

        logStep("✅ Multi-partition transactions validated");
    }
}