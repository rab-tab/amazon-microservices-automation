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

/**
 * Kafka Event Publishing - Failure Scenarios
 *
 * Tests negative scenarios and failure handling in Kafka event publishing:
 * - Kafka broker unavailable / timeouts / retry exhaustion / ISR / serialization /
 *   message size / buffer overflow — all simulated via X-Fault header, no real
 *   Kafka cluster mutation needed (safe to run anywhere, unlike ConfigurationFailures).
 * - Invalid order data rejected before any event is published.
 */
@Slf4j
@Epic("Amazon Microservices")
@Feature("Kafka - Event Publishing Failures")
public class OrderEventPublishingFailureTest extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;
    private PurchaseResult purchase;
    private OrderApiClient orderApiClient;

    public enum FaultCategory {
        BROKER_CONNECTIVITY, PRODUCER_LIMITS, SERIALIZATION_DATA,TOPIC_FAILURE
    }


    @BeforeMethod
    public void setup() {
        logStep("Setting up Kafka failure tests");

        purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
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

    // ══════════════════════════════════════════════════════════════
    // KAFKA INFRASTRUCTURE FAILURES (simulated via X-Fault)
    // ══════════════════════════════════════════════════════════════

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
                // ── Kafka broker / connectivity failures ──
                { FaultCategory.BROKER_CONNECTIVITY, "Producer timeout", "kafka-timeout", "Simulated Kafka timeout - producer timed out" },
                { FaultCategory.BROKER_CONNECTIVITY, "Retry exhaustion", "kafka-retry-failure", "Simulated retry failure - max retries exceeded" },
                { FaultCategory.BROKER_CONNECTIVITY, "Acknowledgment failure (insufficient ISR)", "kafka-ack-failure", "Simulated ack failure - insufficient in-sync replicas" },

                // ── Producer configuration & limit failures ──
                { FaultCategory.PRODUCER_LIMITS, "Message too large", "message-too-large", "Simulated message too large - event exceeds max.message.bytes" },
                { FaultCategory.PRODUCER_LIMITS, "Producer buffer full", "buffer-full", "Simulated buffer full - producer buffer overflow" },
                { FaultCategory.PRODUCER_LIMITS, "Producer quota exceeded", "quota-exceeded", "quota" },
                { FaultCategory.PRODUCER_LIMITS, "Record batch too large", "batch-too-large", "batch" },
                { FaultCategory.PRODUCER_LIMITS, "Compression failure", "compression-error", "compression" },

                // ── Producer serialization & data failures ──
                { FaultCategory.SERIALIZATION_DATA, "Serialization error", "serialization-error", "Simulated serialization error - cannot serialize event" },
                { FaultCategory.SERIALIZATION_DATA, "Invalid partition key", "invalid-partition-key", "" },
                { FaultCategory.SERIALIZATION_DATA, "Schema registry unavailable", "schema-registry-down", "schema" },
                // ── Topic & partition failures ──
                { FaultCategory.TOPIC_FAILURE,"Topic does not exist", "topic-not-exist", "" },
                { FaultCategory.TOPIC_FAILURE,"Topic authorization failure", "topic-auth-failure", "" }
        };
    }

    @Test(dataProvider = "kafkaFaultScenarios")
    @Story("Event Publishing - Failures")
    @Severity(SeverityLevel.NORMAL)
    @Description("Various simulated Kafka producer failures cause order creation to fail with the expected error message")
    public void testKafkaFaultScenario(FaultCategory category,String scenario, String faultHeader, String expectedMessage) throws Exception {
        logStep("[" + category + "] " + scenario);
        logStep("TEST: " + scenario + " - simulating X-Fault: " + faultHeader);

        ServiceResponse response = createOrderWithFault(faultHeader);

        assertThat(response.getStatusCode()).as("Order creation should fail on " + scenario).isEqualTo(500);
        assertThat(response.getBody()).as("Error message should indicate " + scenario).contains(expectedMessage);

        logStep("✅ " + scenario + " handled correctly");
    }

    // ══════════════════════════════════════════════════════════════
    // INVALID DATA SCENARIOS
    // ══════════════════════════════════════════════════════════════

    @Test
    @Story("Event Publishing - Validation")
    @Severity(SeverityLevel.NORMAL)
    @Description("Invalid order data rejected before event publishing")
    public void test08_InvalidData_RejectedBeforePublishing() throws Exception {
        logStep("TEST 8: Invalid order data rejected - no event published");

        TestModels.CreateOrderRequest invalidOrder = TestModels.CreateOrderRequest.builder()
                .items(List.of()) // no items — invalid
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

    // ══════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════

    private ServiceResponse createOrderWithFault(String faultType) {
        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        return orderApiClient.createOrderWithFault(
                userId(), TestDataFactory.newIdempotencyKey(), orderRequest, faultType);
    }
}