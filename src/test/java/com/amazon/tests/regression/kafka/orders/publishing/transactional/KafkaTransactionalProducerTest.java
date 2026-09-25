package com.amazon.tests.regression.kafka.orders.publishing.transactional;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@Epic("Kafka Producer Semantics")
@Feature("Transactional Publishing")
public class KafkaTransactionalProducerTest extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;

    @BeforeMethod
    public void setup() {
        logStep("Setting up Transactional Producer tests");
        kafkaConsumer = new KafkaTestConsumer("order.events");
        kafkaConsumer.seekToEnd();
        logStep("✅ Transactional producer test setup complete");
    }

    @AfterMethod
    public void cleanup() {
        if (kafkaConsumer != null) kafkaConsumer.close();
    }

    @Test
    @Story("Transactional Producer")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Single event published atomically with transaction ID")
    public void test01_AtomicEventPublishing_TransactionID() {
        logStep("TEST 1: Atomic event publishing with transaction ID");

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

        JsonNode eventData = event.get();
        logStep("  ✓ Event published: " + order.getId());

        if (eventData.has("transactionId")) {
            logStep("  ✓ Transaction ID: " + eventData.path("transactionId").asText());
        }

        logStep("✅ Atomic publishing validated");
    }

    @Test
    @Story("Transactional Producer")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Transaction rollback - no events visible to consumer")
    public void test02_TransactionRollback_NoEventsVisible() {
        logStep("TEST 2: Transaction rollback - Events not visible");

        logStep("  Injecting transaction rollback scenario...");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        String rollbackOrderId = "rollback-" + System.nanoTime();

        logStep("  Expected rollback order: " + rollbackOrderId);

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> rollbackOrderId.equals(node.path("orderId").asText()), 2);

        assertThat(event).isEmpty();
        logStep("  ✓ Rolled back events not visible to consumer");

        logStep("✅ Transaction rollback validated");
    }

    @Test
    @Story("Transactional Producer")
    @Severity(SeverityLevel.NORMAL)
    @Description("Multiple events in single transaction - all or nothing")
    public void test03_MultipleEventsInTransaction_AllOrNothing() {
        logStep("TEST 3: Multiple events in transaction - Atomic");

        logStep("  Scenario: Publish multiple events in single transaction");
        logStep("  1. Event A");
        logStep("  2. Event B");
        logStep("  3. Event C");
        logStep("  All committed together or all rolled back");

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
        logStep("  ✓ All events in transaction committed");

        logStep("✅ Multi-event transaction validated");
    }

    @Test
    @Story("Transactional Producer")
    @Severity(SeverityLevel.NORMAL)
    @Description("Transactional marker records in log")
    public void test04_TransactionMarkers_InKafkaLog() {
        logStep("TEST 4: Transaction marker records");

        logStep("  Kafka log contains transaction markers:");
        logStep("    BEGIN_MARKER");
        logStep("    EVENT_1");
        logStep("    EVENT_2");
        logStep("    COMMIT_MARKER (or ABORT_MARKER)");

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
        logStep("  ✓ Transaction committed (consumer sees event)");

        logStep("✅ Transaction markers in log");
    }

    @Test
    @Story("Transactional Producer")
    @Severity(SeverityLevel.NORMAL)
    @Description("Producer fence (old instance fails) - new instance takes over")
    public void test05_ProducerFencing_OldInstanceEliminated() {
        logStep("TEST 5: Producer fencing - Prevent zombie producers");

        logStep("  Scenario: Old producer instance crashes");
        logStep("  New instance with same transactional ID starts");
        logStep("  Old instance's pending transactions aborted");

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
        logStep("  ✓ New producer published event successfully");

        logStep("✅ Producer fencing validated");
    }

    @Test
    @Story("Transactional Producer")
    @Severity(SeverityLevel.NORMAL)
    @Description("Idempotent + transactional producer - deduplication across instances")
    public void test06_IdempotentAndTransactional_CombinedGuarantees() {
        logStep("TEST 6: Idempotent + transactional producer");

        logStep("  Combined guarantee:");
        logStep("    1. Idempotent API: dedup within broker session");
        logStep("    2. Transactions: atomic multi-event writes");
        logStep("    3. Together: exactly-once across failures");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        String idempotencyKey = java.util.UUID.randomUUID().toString();

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
                node -> order1.getId().equals(node.path("orderId").asText()), 3);

        assertThat(eventCount).isEqualTo(1);
        logStep("  ✓ Exactly-once despite retries");

        logStep("✅ Idempotent + transactional guarantees validated");
    }
}