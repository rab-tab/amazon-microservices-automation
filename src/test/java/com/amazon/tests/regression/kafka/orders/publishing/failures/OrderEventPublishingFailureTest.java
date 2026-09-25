package com.amazon.tests.regression.kafka.orders.publishing.failures;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
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
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@Epic("Amazon Microservices")
@Feature("Kafka - Event Publishing Failures")
public class OrderEventPublishingFailureTest extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;
    private PurchaseResult purchase;
    private OrderApiClient orderApiClient;

    public enum FaultCategory {
        BROKER_CONNECTIVITY, PRODUCER_LIMITS, SERIALIZATION_DATA, TOPIC_FAILURE, SCHEMA_COMPATIBILITY, TRANSACTION_FAILURE
    }

    @BeforeMethod
    public void setup() {
        logStep("Setting up Kafka failure tests");

        purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        orderApiClient = new OrderApiClient(
                new BearerAuthStrategy(purchase.getCustomer().getAccessToken()),
                context.getExecutor());

        kafkaConsumer = new KafkaTestConsumer("order.events");
        kafkaConsumer.seekToEnd();

        logStep("✅ Setup complete — user: " + userId());
    }

    @AfterMethod
    public void cleanup() {
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
            logStep("✅ Kafka consumer closed");
        }
    }

    private String userId() {
        return purchase.getCustomer().getUser().getId();
    }

    @Test(description = "Kafka broker down - order creation should fail gracefully")
    @Story("Event Publishing Failure Scenarios")
    @Severity(SeverityLevel.CRITICAL)
    public void test01_KafkaBrokerDown_OrderCreationFails() throws Exception {
        logStep("TEST 1: Kafka broker down - order creation should fail");

        ServiceResponse response = createOrderWithFault("kafka-down");

        logStep("  Response status: " + response.getStatusCode());
        logStep("  Response body: " + response.getBody());

        assertThat(response.getStatusCode()).as("Order creation should fail when Kafka is down").isEqualTo(500);

        Map<String, Object> body = response.as(Map.class);
        assertThat(body.get("status")).as("Status in response body should be 500").isEqualTo(500);
        assertThat(body.get("error")).as("Error should be 'Kafka Unavailable'").isEqualTo("Kafka Unavailable");
        assertThat((String) body.get("message"))
                .as("Message should indicate Kafka failure")
                .containsAnyOf("Simulated Kafka failure", "broker unreachable", "Kafka");
        assertThat(body.get("details"))
                .as("Details should provide user-friendly message")
                .isEqualTo("Unable to publish order event. Please try again later.");
        assertThat(body.get("timestamp")).as("Response should have timestamp").isNotNull();

        logStep("  ✓ Error response validated: " + body);

        logStep("  Verifying no event published to Kafka...");
        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("userId") && userId().equals(node.get("userId").asText()),
                3
        );

        assertThat(event).as("No event should be published when Kafka is down").isEmpty();

        logStep("✅ Order creation properly failed when Kafka unavailable — HTTP 500, structured error, no event published");
    }

    @DataProvider(name = "kafkaFaultScenarios")
    public Object[][] kafkaFaultScenarios() {
        return new Object[][] {
                { FaultCategory.BROKER_CONNECTIVITY, "Producer timeout", "kafka-timeout", "Simulated Kafka timeout" },
                { FaultCategory.BROKER_CONNECTIVITY, "Retry exhaustion", "kafka-retry-failure", "max retries exceeded" },
                { FaultCategory.BROKER_CONNECTIVITY, "Insufficient ISR", "kafka-ack-failure", "insufficient in-sync replicas" },
                { FaultCategory.PRODUCER_LIMITS, "Message too large", "message-too-large", "max.message.bytes" },
                { FaultCategory.PRODUCER_LIMITS, "Producer buffer full", "buffer-full", "buffer" },
                { FaultCategory.PRODUCER_LIMITS, "Producer quota exceeded", "quota-exceeded", "quota" },
                { FaultCategory.PRODUCER_LIMITS, "Record batch too large", "batch-too-large", "batch" },
                { FaultCategory.PRODUCER_LIMITS, "Compression failure", "compression-error", "compression" },
                { FaultCategory.SERIALIZATION_DATA, "Serialization error", "serialization-error", "cannot serialize" },
                { FaultCategory.SERIALIZATION_DATA, "Invalid partition key", "invalid-partition-key", "" },
                { FaultCategory.SERIALIZATION_DATA, "Schema registry unavailable", "schema-registry-down", "schema" },
                { FaultCategory.TOPIC_FAILURE, "Topic does not exist", "topic-not-exist", "" },
                { FaultCategory.TOPIC_FAILURE, "Topic authorization failure", "topic-auth-failure", "" },
                { FaultCategory.SCHEMA_COMPATIBILITY, "Schema version mismatch", "schema-version-mismatch", "schema" },
                { FaultCategory.TRANSACTION_FAILURE, "Transaction abort", "transaction-abort", "aborted" },
        };
    }

    @Test(dataProvider = "kafkaFaultScenarios")
    @Story("Event Publishing - Failures")
    @Severity(SeverityLevel.NORMAL)
    @Description("Various simulated Kafka producer failures cause order creation to fail")
    public void testKafkaFaultScenario(FaultCategory category, String scenario, String faultHeader, String expectedMessage) throws Exception {
        logStep("[" + category + "] " + scenario);
        logStep("TEST: " + scenario + " - simulating X-Fault: " + faultHeader);

        ServiceResponse response = createOrderWithFault(faultHeader);

        assertThat(response.getStatusCode()).as("Order creation should fail on " + scenario).isEqualTo(500);
        if (!expectedMessage.isEmpty()) {
            assertThat(response.getBody()).as("Error message should indicate " + scenario).contains(expectedMessage);
        }

        logStep("✅ " + scenario + " handled correctly");
    }

    @Test
    @Story("Event Publishing - Validation")
    @Severity(SeverityLevel.NORMAL)
    @Description("Invalid order data rejected before event publishing")
    public void test08_InvalidData_RejectedBeforePublishing() throws Exception {
        logStep("TEST 8: Invalid order data rejected - no event published");

        TestModels.CreateOrderRequest invalidOrder = TestModels.CreateOrderRequest.builder()
                .items(List.of())
                .shippingAddress("123 Test St")
                .build();

        logStep("  Sending order with missing items (invalid)...");

        ServiceResponse response = orderApiClient.createOrderWithFault(
                userId(), TestDataFactory.newIdempotencyKey(), invalidOrder, null);

        logStep("  Response status: " + response.getStatusCode());
        logStep("  Response body: " + response.getBody());

        assertThat(response.getStatusCode()).as("Invalid order should be rejected").isIn(400, 500);

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("userId") && userId().equals(node.get("userId").asText()),
                2
        );

        assertThat(event).as("No event should be published for invalid data").isEmpty();

        logStep("✅ Invalid data rejected before Kafka publishing");
    }

    @Test
    @Story("Event Publishing - Failures")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order fails when payload exceeds maximum Kafka message size")
    public void test09_PayloadExceedsMaxMessageSize() throws Exception {
        logStep("TEST 9: Payload exceeds maximum Kafka message size");

        ServiceResponse response = createOrderWithFault("message-too-large");

        assertThat(response.getStatusCode()).as("Order should fail when message exceeds max size").isEqualTo(500);
        assertThat(response.getBody()).containsAnyOf("too large", "max.message.bytes", "message size");

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("userId") && userId().equals(node.get("userId").asText()),
                2
        );

        assertThat(event).as("No event should be published when size exceeds limit").isEmpty();

        logStep("✅ Oversized payload properly rejected");
    }

    @Test
    @Story("Event Publishing - Failures")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order fails when producer encounters network partition")
    public void test10_ProducerNetworkPartition() throws Exception {
        logStep("TEST 10: Producer encounters network partition");

        ServiceResponse response = createOrderWithFault("network-partition");

        assertThat(response.getStatusCode()).as("Order should fail during network partition").isEqualTo(500);
        assertThat(response.getBody()).containsAnyOf("network", "partition", "unavailable");

        logStep("✅ Network partition handled correctly");
    }

    @Test
    @Story("Event Publishing - Failures")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order fails when Kafka broker version incompatibility occurs")
    public void test11_BrokerVersionIncompatibility() throws Exception {
        logStep("TEST 11: Kafka broker version incompatibility");

        ServiceResponse response = createOrderWithFault("broker-version-mismatch");

        assertThat(response.getStatusCode()).as("Order should fail on version mismatch").isEqualTo(500);
        assertThat(response.getBody()).containsAnyOf("version", "unsupported", "compatibility");

        logStep("✅ Version incompatibility handled");
    }

    @Test
    @Story("Event Publishing - Failures")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order fails when producer SSL/TLS configuration is invalid")
    public void test12_SSLConfigurationFailure() throws Exception {
        logStep("TEST 12: Producer SSL/TLS configuration failure");

        ServiceResponse response = createOrderWithFault("ssl-config-error");

        assertThat(response.getStatusCode()).as("Order should fail with SSL config error").isEqualTo(500);
        assertThat(response.getBody()).containsAnyOf("SSL", "TLS", "certificate", "handshake");

        logStep("✅ SSL configuration error handled");
    }

    @Test
    @Story("Event Publishing - Failures")
    @Severity(SeverityLevel.NORMAL)
    @Description("Order fails when producer authentication token is invalid")
    public void test13_ProducerAuthenticationFailure() throws Exception {
        logStep("TEST 13: Producer SASL authentication failure");

        ServiceResponse response = createOrderWithFault("auth-failure");

        assertThat(response.getStatusCode()).as("Order should fail with auth error").isEqualTo(500);
        assertThat(response.getBody()).containsAnyOf("authentication", "unauthorized", "credentials");

        logStep("✅ Authentication failure handled");
    }

    private ServiceResponse createOrderWithFault(String faultType) {
        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        return orderApiClient.createOrderWithFault(
                userId(), TestDataFactory.newIdempotencyKey(), orderRequest, faultType);
    }
}