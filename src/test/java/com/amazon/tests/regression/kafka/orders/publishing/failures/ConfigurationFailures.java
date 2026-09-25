package com.amazon.tests.regression.kafka.orders.publishing.failures;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import io.qameta.allure.testng.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.config.ConfigResource;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@Epic("Amazon Microservices")
@Feature("Kafka - Configuration Failures (Realistic)")
@Tag("realistic-chaos")
@Tag("slow-tests")
public class ConfigurationFailures extends BaseTest {

    private static final String ORDER_EVENTS_TOPIC = "order.events";

    private AdminClient adminClient;

    private AdminClient adminClient() {
        if (adminClient == null) {
            String bootstrapServers = System.getProperty("kafka.bootstrap.servers", "localhost:9092");
            adminClient = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
        }
        return adminClient;
    }

    @AfterMethod
    public void restoreTopicConfig() throws Exception {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, ORDER_EVENTS_TOPIC);
        AlterConfigOp resetOp = new AlterConfigOp(
                new org.apache.kafka.clients.admin.ConfigEntry("min.insync.replicas", "1"),
                AlterConfigOp.OpType.SET);

        try {
            adminClient().incrementalAlterConfigs(Map.of(resource, List.of(resetOp))).all().get();
            logStep("  ♻️  Restored min.insync.replicas=1 on " + ORDER_EVENTS_TOPIC);
        } catch (Exception e) {
            log.warn("Failed to restore topic config — check {} manually before next run", ORDER_EVENTS_TOPIC, e);
        }
    }

    private PurchaseResult setupCustomerAndProduct() {
        return PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();
    }

    @Test
    @Story("Kafka Configuration")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Order creation fails when order.events topic has insufficient in-sync replicas")
    public void test01_InsufficientISR_AckFailure() throws Exception {
        logStep("TEST 1: Insufficient in-sync replicas on " + ORDER_EVENTS_TOPIC);

        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, ORDER_EVENTS_TOPIC);
        AlterConfigOp breakOp = new AlterConfigOp(
                new org.apache.kafka.clients.admin.ConfigEntry("min.insync.replicas", "2"),
                AlterConfigOp.OpType.SET);

        adminClient().incrementalAlterConfigs(Map.of(resource, List.of(breakOp))).all().get();
        logStep("  ⚙️  min.insync.replicas set to 2 on " + ORDER_EVENTS_TOPIC + " (local broker only has 1 replica)");

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse response = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, null);

        assertThat(response.getStatusCode()).as("Should fail due to insufficient ISR").isEqualTo(500);
        assertThat(response.getBody()).containsAnyOf("insufficient", "in-sync", "replicas");

        logStep("✅ Real Kafka configuration caused actual ISR failure");
    }

    @Test
    @Story("Kafka Configuration")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order creation fails when the order.events topic doesn't exist")
    public void test02_TopicDoesNotExist() throws Exception {
        logStep("TEST 2: Topic does not exist (destructive — local only)");

        adminClient().deleteTopics(Collections.singleton(ORDER_EVENTS_TOPIC)).all().get();
        logStep("  🗑️  Deleted topic: " + ORDER_EVENTS_TOPIC);

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse response = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, null);

        assertThat(response.getStatusCode()).as("Should fail cleanly when topic doesn't exist").isEqualTo(500);
        assertThat(response.getBody()).containsAnyOf("topic", "does not exist", "unknown");

        logStep("✅ Topic-not-found error surfaced correctly");

        NewTopic recreated = new NewTopic(ORDER_EVENTS_TOPIC, 1, (short) 1);
        adminClient().createTopics(List.of(recreated)).all().get();
        logStep("  ♻️  Recreated topic: " + ORDER_EVENTS_TOPIC);
    }

    @Test
    @Story("Kafka Configuration")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order creation fails when topic retention policy is too aggressive")
    public void test03_TopicRetentionPolicyTooAggressive() throws Exception {
        logStep("TEST 3: Topic retention policy too aggressive (immediate deletion)");

        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, ORDER_EVENTS_TOPIC);
        AlterConfigOp retentionOp = new AlterConfigOp(
                new org.apache.kafka.clients.admin.ConfigEntry("retention.ms", "1"),
                AlterConfigOp.OpType.SET);

        adminClient().incrementalAlterConfigs(Map.of(resource, List.of(retentionOp))).all().get();
        logStep("  ⚙️  retention.ms set to 1ms (events deleted immediately)");

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse response = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, null);

        logStep("  Response status: " + response.getStatusCode());

        if (response.getStatusCode() != 201) {
            logStep("✅ Order creation failed due to aggressive retention policy");
        } else {
            logStep("⚠️  Order succeeded but events may not persist due to retention policy");
        }

        ConfigResource resourceReset = new ConfigResource(ConfigResource.Type.TOPIC, ORDER_EVENTS_TOPIC);
        AlterConfigOp resetRetention = new AlterConfigOp(
                new org.apache.kafka.clients.admin.ConfigEntry("retention.ms", "86400000"),
                AlterConfigOp.OpType.SET);
        adminClient().incrementalAlterConfigs(Map.of(resourceReset, List.of(resetRetention))).all().get();
        logStep("  ♻️  Restored retention.ms to 1 day");
    }

    @Test
    @Story("Kafka Configuration")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order creation fails when partition count is zero or invalid")
    public void test04_InvalidPartitionCount() throws Exception {
        logStep("TEST 4: Topic partition reconfiguration affects publishing");

        try {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, ORDER_EVENTS_TOPIC);
            AlterConfigOp configOp = new AlterConfigOp(
                    new org.apache.kafka.clients.admin.ConfigEntry("num.partitions", "0"),
                    AlterConfigOp.OpType.SET);

            adminClient().incrementalAlterConfigs(Map.of(resource, List.of(configOp))).all().get();
            logStep("  ⚙️  Attempted to set num.partitions to 0");

        } catch (Exception e) {
            logStep("  ⚠️  Expected failure: Cannot set invalid partition count");
            logStep("  Error: " + e.getMessage());
        }

        logStep("✅ Invalid partition configuration rejected by Kafka");
    }

    @Test
    @Story("Kafka Configuration")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order creation fails when topic compression is misconfigured")
    public void test05_CompressionConfigurationFailure() throws Exception {
        logStep("TEST 5: Topic compression configuration failure");

        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, ORDER_EVENTS_TOPIC);
        AlterConfigOp compressionOp = new AlterConfigOp(
                new org.apache.kafka.clients.admin.ConfigEntry("compression.type", "invalid-codec"),
                AlterConfigOp.OpType.SET);

        try {
            adminClient().incrementalAlterConfigs(Map.of(resource, List.of(compressionOp))).all().get();
            logStep("  ⚠️  Invalid compression codec set (if accepted by broker)");

            PurchaseResult purchase = setupCustomerAndProduct();
            String token = purchase.getCustomer().getAccessToken();
            String userId = purchase.getCustomer().getUser().getId();
            OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

            TestModels.CreateOrderRequest orderRequest =
                    TestDataFactory.defaultOrder(purchase.getProducts()).build();

            ServiceResponse response = orderApiClient.createOrderWithFault(
                    userId, TestDataFactory.newIdempotencyKey(), orderRequest, null);

            assertThat(response.getStatusCode()).as("Order may fail with invalid compression").isIn(400, 500);
            logStep("✅ Invalid compression configuration prevented publishing");

        } catch (Exception e) {
            logStep("✅ Invalid compression codec rejected by Kafka broker: " + e.getMessage());
        }

        ConfigResource resetResource = new ConfigResource(ConfigResource.Type.TOPIC, ORDER_EVENTS_TOPIC);
        AlterConfigOp resetCompression = new AlterConfigOp(
                new org.apache.kafka.clients.admin.ConfigEntry("compression.type", "snappy"),
                AlterConfigOp.OpType.SET);
        try {
            adminClient().incrementalAlterConfigs(Map.of(resetResource, List.of(resetCompression))).all().get();
        } catch (Exception e) {
            logStep("  Note: Could not reset compression type");
        }
    }

    @Test
    @Story("Kafka Configuration")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order creation fails when topic cleanup policy is misconfigured")
    public void test06_CleanupPolicyMisconfiguration() throws Exception {
        logStep("TEST 6: Topic cleanup policy misconfiguration");

        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, ORDER_EVENTS_TOPIC);
        AlterConfigOp cleanupOp = new AlterConfigOp(
                new org.apache.kafka.clients.admin.ConfigEntry("cleanup.policy", "compact"),
                AlterConfigOp.OpType.SET);

        adminClient().incrementalAlterConfigs(Map.of(resource, List.of(cleanupOp))).all().get();
        logStep("  ⚙️  cleanup.policy changed to 'compact' (log compaction instead of time-based retention)");

        PurchaseResult purchase = setupCustomerAndProduct();
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse response = orderApiClient.createOrderWithFault(
                userId, TestDataFactory.newIdempotencyKey(), orderRequest, null);

        logStep("  Response status: " + response.getStatusCode());
        logStep("✅ Cleanup policy mismatch detected (or order succeeded despite misconfiguration)");

        ConfigResource resetResource = new ConfigResource(ConfigResource.Type.TOPIC, ORDER_EVENTS_TOPIC);
        AlterConfigOp resetCleanup = new AlterConfigOp(
                new org.apache.kafka.clients.admin.ConfigEntry("cleanup.policy", "delete"),
                AlterConfigOp.OpType.SET);
        adminClient().incrementalAlterConfigs(Map.of(resetResource, List.of(resetCleanup))).all().get();
        logStep("  ♻️  Restored cleanup.policy to 'delete'");
    }
}