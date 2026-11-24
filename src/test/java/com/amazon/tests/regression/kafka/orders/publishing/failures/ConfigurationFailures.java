package com.amazon.tests.regression.kafka.orders.publishing.failures;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.qameta.allure.testng.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Slf4j
@Epic("Amazon Microservices")
@Feature("Kafka - Configuration Failures")
@Tag("configuration-failures")
@Tag("slow-tests")
public class ConfigurationFailures extends BaseTest {

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String MIN_INSYNC_REPLICAS = "min.insync.replicas";
    private static final String COMPRESSION_TYPE = "compression.type";

    private AdminClient adminClient;

    @BeforeClass(alwaysRun = true)
    public void createAdminClient() {
        String bootstrapServers =
                System.getProperty("kafka.bootstrap.servers", "localhost:9092");

        adminClient = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        );
    }

    @AfterClass(alwaysRun = true)
    public void closeAdminClient() {
        if (adminClient != null) {
            adminClient.close();
        }
    }

    private ConfigResource topicResource() {
        return new ConfigResource(
                ConfigResource.Type.TOPIC,
                ORDER_EVENTS_TOPIC
        );
    }

    private ConfigEntry getTopicConfig(String configName) throws Exception {
        ConfigResource resource = topicResource();

        Config config = adminClient.describeConfigs(
                Collections.singleton(resource)
        ).all().get().get(resource);

        ConfigEntry entry = config.get(configName);

        if (entry == null) {
            throw new IllegalStateException(
                    "Kafka did not return topic configuration: " + configName
            );
        }

        return entry;
    }

    private void setTopicConfig(String configName, String value) throws Exception {
        ConfigResource resource = topicResource();

        AlterConfigOp operation = new AlterConfigOp(
                new ConfigEntry(configName, value),
                AlterConfigOp.OpType.SET
        );

        adminClient.incrementalAlterConfigs(
                Map.of(resource, List.of(operation))
        ).all().get();
    }

    /**
     * Restores the exact pre-test topic setting:
     * - explicit original value -> SET that value
     * - inherited broker default -> DELETE the topic-level override
     */
    private void restoreTopicConfig(
            String configName,
            ConfigEntry originalEntry
    ) throws Exception {
        ConfigResource resource = topicResource();

        AlterConfigOp operation;

        if (originalEntry.isDefault()) {
            operation = new AlterConfigOp(
                    new ConfigEntry(configName, null),
                    AlterConfigOp.OpType.DELETE
            );
        } else {
            operation = new AlterConfigOp(
                    new ConfigEntry(configName, originalEntry.value()),
                    AlterConfigOp.OpType.SET
            );
        }

        adminClient.incrementalAlterConfigs(
                Map.of(resource, List.of(operation))
        ).all().get();

        logStep("Restored " + configName + " to its original topic configuration");
    }

    private int getTopicReplicationFactor() throws Exception {
        TopicDescription description = adminClient.describeTopics(
                Collections.singleton(ORDER_EVENTS_TOPIC)
        ).allTopicNames().get().get(ORDER_EVENTS_TOPIC);

        assertThat(description)
                .as("Topic %s must exist before running this test", ORDER_EVENTS_TOPIC)
                .isNotNull();

        assertThat(description.partitions())
                .as("Topic %s must have at least one partition", ORDER_EVENTS_TOPIC)
                .isNotEmpty();

        return description.partitions().get(0).replicas().size();
    }

    private PurchaseResult setupCustomerAndProduct() {
        return PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();
    }

    private ServiceResponse submitOrder(PurchaseResult purchase) {
        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        OrderApiClient orderApiClient = new OrderApiClient(
                new BearerAuthStrategy(token),
                context.getExecutor()
        );

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        return orderApiClient.createOrderWithFault(
                userId,
                TestDataFactory.newIdempotencyKey(),
                orderRequest,
                null
        );
    }

    @Test
    @Story("Insufficient In-Sync Replicas")
    @Severity(SeverityLevel.CRITICAL)
    @Description(
            "Order publishing fails when min.insync.replicas is greater than the " +
                    "topic replication factor. The application producer must use acks=all."
    )
    public void test01_InsufficientISR_AckFailure() throws Exception {
        logStep("TEST 1: Insufficient in-sync replicas on " + ORDER_EVENTS_TOPIC);

        ConfigEntry originalMinIsr = getTopicConfig(MIN_INSYNC_REPLICAS);

        try {
            int replicationFactor = getTopicReplicationFactor();
            int requiredIsr = replicationFactor + 1;

            /*
             * Setting min.insync.replicas above the replication factor guarantees
             * that the topic cannot satisfy the ISR requirement, even when all
             * configured replicas are healthy.
             *
             * This causes a produce failure only when the application producer
             * uses acks=all. If it uses acks=1, this test should not pass.
             */
            setTopicConfig(MIN_INSYNC_REPLICAS, String.valueOf(requiredIsr));

            logStep(
                    "Set min.insync.replicas=" + requiredIsr +
                            "; topic replication factor=" + replicationFactor
            );

            PurchaseResult purchase = setupCustomerAndProduct();
            ServiceResponse response = submitOrder(purchase);

            logStep("Order API response status: " + response.getStatusCode());

            assertThat(response.getStatusCode())
                    .as("Order request should fail when Kafka cannot satisfy min.insync.replicas")
                    .isBetween(500, 599);

        } finally {
            restoreTopicConfig(MIN_INSYNC_REPLICAS, originalMinIsr);
        }
    }

    @Test
    @Story("Invalid Topic Configuration")
    @Severity(SeverityLevel.NORMAL)
    @Description("Kafka rejects an unsupported compression.type topic configuration value")
    public void test02_InvalidCompressionConfigurationRejected() throws Exception {
        logStep("TEST 2: Invalid compression.type configuration");

        ConfigEntry originalCompression = getTopicConfig(COMPRESSION_TYPE);

        try {
            assertThatThrownBy(
                    () -> setTopicConfig(COMPRESSION_TYPE, "invalid-codec")
            )
                    .as("Kafka should reject an unsupported compression.type value")
                    .isInstanceOf(ExecutionException.class);

            logStep("Kafka rejected the unsupported compression.type value");

        } finally {
            restoreTopicConfig(COMPRESSION_TYPE, originalCompression);
        }
    }
}
