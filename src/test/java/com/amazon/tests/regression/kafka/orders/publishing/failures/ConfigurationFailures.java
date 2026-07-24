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

/**
 * Realistic Kafka Configuration Failure Tests
 *
 * Runs against the LOCAL Kafka cluster the system under test is actually
 * wired to. These tests mutate real broker/topic configuration (min.insync.replicas)
 * and MUST NOT be run against shared staging/production — local/dev only.
 *
 * Each test restores the topic config it changed in @AfterMethod, but a
 * crashed run could leave the topic misconfigured — check topic config
 * before other test runs if this suite fails unexpectedly.
 *
 * Run frequency: Weekly, before releases (not part of standard regression).
 */
@Slf4j
@Epic("Amazon Microservices")
@Feature("Kafka - Configuration Failures (Realistic)")
@Tag("realistic-chaos")
@Tag("slow-tests")
public class ConfigurationFailures extends BaseTest {

    private static final String ORDER_EVENTS_TOPIC = "order.events"; // must match the real topic name

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
        // Always restore min.insync.replicas to a sane default (1) after each test,
        // regardless of pass/fail, so this doesn't poison other test runs.
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
        return PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();
    }

    // ══════════════════════════════════════════════════════════════

    @Test
    @Story("Kafka Configuration")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Order creation fails cleanly when the real order.events topic "
            + "has min.insync.replicas set higher than available in-sync replicas")
    public void test01_InsufficientISR_AckFailure() throws Exception {
        logStep("REALISTIC TEST: Insufficient in-sync replicas on " + ORDER_EVENTS_TOPIC);

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
    @Description("Order creation fails cleanly when the order.events topic doesn't exist "
            + "and auto-creation is disabled. Requires the topic to be deleted beforehand — "
            + "destructive, local-only, and will break other concurrently-running tests "
            + "that depend on this topic existing. Do not run alongside other suites.")
    public void test02_TopicDoesNotExist() throws Exception {
        logStep("REALISTIC TEST: Topic does not exist (destructive — local only)");

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

        // Recreate so subsequent tests/suites aren't broken.
        NewTopic recreated = new NewTopic(ORDER_EVENTS_TOPIC, 1, (short) 1);
        adminClient().createTopics(List.of(recreated)).all().get();
        logStep("  ♻️  Recreated topic: " + ORDER_EVENTS_TOPIC);
    }
}