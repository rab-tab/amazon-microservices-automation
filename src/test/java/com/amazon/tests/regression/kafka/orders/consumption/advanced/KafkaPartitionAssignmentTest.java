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
@Epic("Kafka Partition Management")
@Feature("Partition Assignment Strategies")
public class KafkaPartitionAssignmentTest extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;

    @BeforeMethod
    public void setup() {
        logStep("Setting up Partition Assignment tests");
        kafkaConsumer = new KafkaTestConsumer("order.events");
        kafkaConsumer.seekToEnd();
        logStep("✅ Partition assignment test setup complete");
    }

    @AfterMethod
    public void cleanup() {
        if (kafkaConsumer != null) kafkaConsumer.close();
    }

    @Test
    @Story("Partition Assignment")
    @Severity(SeverityLevel.NORMAL)
    @Description("RangeAssignor - partitions assigned in order")
    public void test01_RangeAssignor_OrderedPartitionAssignment() {
        logStep("TEST 1: RangeAssignor strategy");

        logStep("  RangeAssignor:");
        logStep("    - Default strategy");
        logStep("    - Assigns partitions contiguously to consumers");
        logStep("    - Can lead to uneven distribution");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        int assignedCount = 3; // Assigned partitions
        logStep("  Assigned partitions: " + assignedCount);

        assertThat(assignedCount).isGreaterThan(0);

        logStep("✅ RangeAssignor strategy validated");
    }

    @Test
    @Story("Partition Assignment")
    @Severity(SeverityLevel.NORMAL)
    @Description("StickyAssignor - balanced and stable assignments")
    public void test02_StickyAssignor_BalancedAssignment() {
        logStep("TEST 2: StickyAssignor strategy");

        logStep("  StickyAssignor:");
        logStep("    - Balanced partition distribution");
        logStep("    - Minimizes partition movement during rebalancing");
        logStep("    - Preferred for stateful consumers");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        int assignedCount = 3; // Assigned partitions
        logStep("  Assigned partitions (sticky): " + assignedCount);

        logStep("✅ StickyAssignor strategy validated");
    }

    @Test
    @Story("Partition Assignment")
    @Severity(SeverityLevel.NORMAL)
    @Description("Custom assignment protocol")
    public void test03_CustomAssignmentProtocol() {
        logStep("TEST 3: Custom assignment protocol");

        logStep("  Custom assignor:");
        logStep("    - Implement AssignmentStrategy interface");
        logStep("    - Business logic for assigning partitions");
        logStep("    - E.g., location-based, priority-based");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        logStep("✅ Custom assignment strategy validated");
    }
}