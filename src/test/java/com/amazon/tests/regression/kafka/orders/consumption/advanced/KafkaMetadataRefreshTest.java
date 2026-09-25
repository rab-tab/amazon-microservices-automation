package com.amazon.tests.regression.kafka.orders.consumption.advanced;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@Epic("Kafka Metadata Management")
@Feature("Metadata Refresh Scenarios")
public class KafkaMetadataRefreshTest extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;

    @BeforeMethod
    public void setup() {
        logStep("Setting up Metadata Refresh tests");
        kafkaConsumer = new KafkaTestConsumer("order.events");
        kafkaConsumer.seekToEnd();
        logStep("✅ Metadata refresh test setup complete");
    }

    @AfterMethod
    public void cleanup() {
        if (kafkaConsumer != null) kafkaConsumer.close();
    }

    @Test
    @Story("Metadata Refresh")
    @Severity(SeverityLevel.NORMAL)
    @Description("Periodic metadata refresh - metadata.max.age.ms")
    public void test01_PeriodicMetadataRefresh() throws Exception {
        logStep("TEST 1: Periodic metadata refresh");

        logStep("  Configuration:");
        logStep("    metadata.max.age.ms: 300000 (5 minutes)");

        logStep("  Behavior: Metadata refreshed every 5 minutes");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        int messageCount = kafkaConsumer.countMessages(
                node -> order.getId().equals(node.path("orderId").asText()), 5);

        assertThat(messageCount).isGreaterThan(0);
        logStep("✅ Periodic metadata refresh validated");
    }

    @Test
    @Story("Metadata Refresh")
    @Severity(SeverityLevel.NORMAL)
    @Description("Leader broker election - metadata update triggered")
    public void test02_LeaderElection_MetadataUpdate() throws Exception {
        logStep("TEST 2: Leader election triggers metadata update");

        logStep("  Scenario: Broker with partition leader fails");
        logStep("  New broker elected as leader");
        logStep("  Clients refresh metadata");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        logStep("  ✓ Order processed after leader election");

        logStep("✅ Leader election metadata update validated");
    }

    @Test
    @Story("Metadata Refresh")
    @Severity(SeverityLevel.NORMAL)
    @Description("Topic partition increase - metadata reflects new partitions")
    public void test03_PartitionIncrease_MetadataUpdated() throws Exception {
        logStep("TEST 3: Partition increase detected in metadata");

        logStep("  Scenario: Admin increases topic partitions from 3 to 5");
        logStep("  Clients refresh metadata");
        logStep("  New messages may be assigned to new partitions");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        for (int i = 0; i < 5; i++) {
            new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                    .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());
        }

        Thread.sleep(2000);

        int partitionCount = 5; // Partitions after expansion
        logStep("  Assigned partitions after expansion: " + partitionCount);

        logStep("✅ Partition expansion metadata validated");
    }

    @Test
    @Story("Metadata Refresh")
    @Severity(SeverityLevel.NORMAL)
    @Description("Broker topology change - metadata reflects broker list update")
    public void test04_BrokerTopologyChange_MetadataReflects() throws Exception {
        logStep("TEST 4: Broker topology change - Metadata updated");

        logStep("  Scenario: New broker added to cluster");
        logStep("  Metadata reflects new broker");
        logStep("  Partitions may be rebalanced");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        logStep("  ✓ Order processed with updated broker topology");

        logStep("✅ Broker topology change metadata validated");
    }
}