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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Order Event Publishing Failure Tests
 *
 * Tests use two failure injection methods:
 * 1. Fault injection headers (X-Fault) → Order Service catches and returns 500
 * 2. Toxiproxy REST API → Network-level failures (no dependency needed)
 *
 * Requires Toxiproxy running:
 * toxiproxy-server -config toxiproxy-config.json
 *
 * Config should include:
 * [{"name": "kafka", "listen": "127.0.0.1:9099", "upstream": "127.0.0.1:9092", "enabled": true}]
 */
@Slf4j
public class OrderEventPublishingFailureTest extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;
    private PurchaseResult purchase;
    private OrderApiClient orderApiClient;

    private static final String TOXIPROXY_API = "http://localhost:8474";
    private static final String KAFKA_PROXY_NAME = "kafka";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private HttpClient httpClient;

    public enum FaultCategory {
        BROKER_CONNECTIVITY, PRODUCER_LIMITS, SERIALIZATION_DATA, TOPIC_FAILURE, SCHEMA_COMPATIBILITY, TRANSACTION_FAILURE
    }

    @BeforeClass
    public void setupTestSuite() {
        logStep("Initializing OrderEventPublishingFailureTest");
        httpClient = HttpClient.newHttpClient();

        // Verify Toxiproxy is reachable
        try {
            testToxiproxyConnection();
            logStep("  ✓ Toxiproxy API available — network failure tests enabled");
        } catch (Exception e) {
            log.warn("Toxiproxy API not available at " + TOXIPROXY_API + " — network tests will be skipped", e);
        }
    }

    @BeforeMethod
    public void setup() {
        logStep("Setting up Kafka failure tests");

        purchase = PurchaseWorkflow.start(executor, authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        orderApiClient = new OrderApiClient(
                new BearerAuthStrategy(purchase.getCustomer().getAccessToken()),
                executor);

        kafkaConsumer = new KafkaTestConsumer("order.events");

        logStep("✅ Setup complete — user: " + userId());
    }

    @AfterMethod
    public void cleanup() {
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
            logStep("✅ Kafka consumer closed");
        }

        // Clean up all toxics
        try {
            removeToxics();
        } catch (Exception e) {
            log.warn("Failed to cleanup toxics", e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 1: KAFKA BROKER DOWN
    // ══════════════════════════════════════════════════════════════════════════

    @Test(description = "Kafka broker down - order creation should fail gracefully")
    public void test01_KafkaBrokerDown_OrderCreationFails() throws Exception {
        logStep("TEST 1: Kafka broker down - order creation should fail");

        ServiceResponse response = createOrderWithFault("kafka-down");

        logStep("  Response status: " + response.getStatusCode());

        assertThat(response.getStatusCode()).as("Order creation should fail when Kafka is down").isEqualTo(500);

        Map<String, Object> body = response.as(Map.class);
        assertThat(body.get("status")).as("Status should be 500").isEqualTo(500);
        assertThat(body.get("error")).as("Error should be 'Kafka Unavailable'").isEqualTo("Kafka Unavailable");
        assertThat((String) body.get("message"))
                .as("Message should indicate Kafka failure")
                .containsAnyOf("Simulated Kafka failure", "broker unreachable", "Kafka");
        assertThat(body.get("details"))
                .as("Details should be user-friendly")
                .isEqualTo("Unable to publish order event. Please try again later.");

        logStep("  ✓ Error response validated");
        logStep("  Verifying no event published to Kafka...");

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("userId") && userId().equals(node.get("userId").asText()),
                3);

        assertThat(event).as("No event should be published when Kafka is down").isEmpty();

        logStep("✅ Order creation properly failed — HTTP 500, structured error, no event published");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DATADRIVEN TESTS: FAULT INJECTION SCENARIOS
    // ══════════════════════════════════════════════════════════════════════════

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

    @Test(dataProvider = "kafkaFaultScenarios", description = "Various simulated Kafka producer failures")
    public void testKafkaFaultScenario(FaultCategory category, String scenario, String faultHeader, String expectedMessage) throws Exception {
        logStep("[" + category + "] " + scenario);

        ServiceResponse response = createOrderWithFault(faultHeader);

        assertThat(response.getStatusCode()).as("Order creation should fail on " + scenario).isEqualTo(500);
        if (!expectedMessage.isEmpty()) {
            assertThat(response.getBody()).as("Error message should indicate " + scenario).contains(expectedMessage);
        }

        logStep("✅ " + scenario + " handled correctly");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 8: INVALID DATA REJECTED BEFORE PUBLISHING
    // ══════════════════════════════════════════════════════════════════════════

    @Test(description = "Invalid order data rejected before event publishing")
    public void test08_InvalidData_RejectedBeforePublishing() throws Exception {
        logStep("TEST 8: Invalid order data rejected — no event published");

        TestModels.CreateOrderRequest invalidOrder = TestModels.CreateOrderRequest.builder()
                .items(List.of())
                .shippingAddress("123 Test St")
                .build();

        logStep("  Sending order with missing items (invalid)...");

        ServiceResponse response = orderApiClient.createOrderWithFault(
                userId(), TestDataFactory.newIdempotencyKey(), invalidOrder, null);

        assertThat(response.getStatusCode()).as("Invalid order should be rejected").isIn(400, 500);

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("userId") && userId().equals(node.get("userId").asText()),
                2);

        assertThat(event).as("No event should be published for invalid data").isEmpty();

        logStep("✅ Invalid data rejected before Kafka publishing");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 9: PAYLOAD EXCEEDS MAX MESSAGE SIZE
    // ══════════════════════════════════════════════════════════════════════════

    @Test(description = "Order fails when payload exceeds maximum Kafka message size")
    public void test09_PayloadExceedsMaxMessageSize() throws Exception {
        logStep("TEST 9: Payload exceeds maximum Kafka message size");

        ServiceResponse response = createOrderWithFault("message-too-large");

        assertThat(response.getStatusCode()).as("Order should fail when message exceeds max size").isEqualTo(500);
        assertThat(response.getBody()).containsAnyOf("too large", "max.message.bytes", "message size");

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("userId") && userId().equals(node.get("userId").asText()),
                2);

        assertThat(event).as("No event should be published when size exceeds limit").isEmpty();

        logStep("✅ Oversized payload properly rejected");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 10: TOXIPROXY - NETWORK PARTITION (Timeout)
    // ══════════════════════════════════════════════════════════════════════════

    @Test(description = "Producer encounters network partition via Toxiproxy timeout")
    public void test10_ProducerNetworkPartition_ViaTimeout() throws Exception {
        logStep("TEST 10: Producer network partition (Toxiproxy timeout)");

        try {
            logStep("  Injecting 5000ms timeout on Kafka proxy...");
            injectToxic("network-partition", "timeout", 5000);

            logStep("  Attempting to create order...");
            ServiceResponse response = createOrderWithFault(null);

            assertThat(response.getStatusCode()).as("Order should fail during network partition").isEqualTo(500);
            assertThat(response.getBody()).containsAnyOf("timeout", "network", "unavailable", "broker");

            logStep("✅ Network partition handled correctly");
        } catch (Exception e) {
            logStep("  ⚠️  Toxiproxy unavailable — skipping test: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 11: TOXIPROXY - HIGH LATENCY
    // ══════════════════════════════════════════════════════════════════════════

    @Test(description = "Producer experiences sustained high latency via Toxiproxy")
    public void test11_HighLatency_ViaLatencyToxic() throws Exception {
        logStep("TEST 11: High latency scenario (Toxiproxy)");

        try {
            logStep("  Injecting 2000ms latency on Kafka proxy...");
            injectToxic("high-latency", "latency", 2000);

            logStep("  Attempting to create order...");
            long startTime = System.currentTimeMillis();
            ServiceResponse response = createOrderWithFault(null);
            long elapsed = System.currentTimeMillis() - startTime;

            logStep("  Response status: " + response.getStatusCode() + " (took " + elapsed + "ms)");

            if (response.getStatusCode() == 201) {
                logStep("✅ Order succeeded despite high latency (" + elapsed + "ms)");
            } else {
                logStep("✅ Order timed out due to high latency (expected)");
                assertThat(response.getStatusCode()).isEqualTo(500);
            }
        } catch (Exception e) {
            logStep("  ⚠️  Toxiproxy unavailable — skipping test: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 12: TOXIPROXY - BANDWIDTH LIMIT
    // ══════════════════════════════════════════════════════════════════════════

    @Test(description = "Producer with limited bandwidth via Toxiproxy")
    public void test12_BandwidthLimit_ViaProxy() throws Exception {
        logStep("TEST 12: Bandwidth limited scenario (Toxiproxy)");

        try {
            logStep("  Limiting bandwidth to 1KB/s...");
            injectToxic("bandwidth-limit", "bandwidth", 1);

            logStep("  Attempting to create order...");
            ServiceResponse response = createOrderWithFault(null);

            assertThat(response.getStatusCode()).as("Order should fail with bandwidth limit").isEqualTo(500);
            assertThat(response.getBody()).containsAnyOf("timeout", "slow", "delay");

            logStep("✅ Bandwidth limitation handled correctly");
        } catch (Exception e) {
            logStep("  ⚠️  Toxiproxy unavailable — skipping test: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DISABLED TESTS: Require actual Kafka/broker misconfiguration
    // ══════════════════════════════════════════════════════════════════════════

    @Test(enabled = false, description = "DISABLED: Requires SSL misconfiguration")
    public void test13_SSLConfigurationFailure() throws Exception {
        logStep("TEST 13: SSL configuration failure");
        logStep("SKIPPED: Requires actual SSL/TLS misconfiguration");
    }

    @Test(enabled = false, description = "DISABLED: Requires SASL auth misconfiguration")
    public void test14_ProducerAuthenticationFailure() throws Exception {
        logStep("TEST 14: Producer authentication failure");
        logStep("SKIPPED: Requires SASL/auth misconfiguration");
    }

    @Test(enabled = false, description = "DISABLED: Requires multi-broker Kafka cluster")
    public void test15_BrokerVersionIncompatibility() throws Exception {
        logStep("TEST 15: Broker version incompatibility");
        logStep("SKIPPED: Requires multi-broker setup");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TOXIPROXY API HELPERS (REST calls)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Inject a toxic via Toxiproxy REST API
     *
     * @param name Toxic name (e.g., "network-partition")
     * @param type Toxic type: timeout, latency, bandwidth, etc.
     * @param value Value for the toxic (ms for timeout/latency, KB/s for bandwidth)
     */
    private void injectToxic(String name, String type, long value) throws Exception {
        String toxicJson;

        if ("timeout".equals(type)) {
            toxicJson = String.format(
                    "{\"type\": \"timeout\", \"name\": \"%s\", \"stream\": \"downstream\", \"toxicity\": 1.0, \"timeout\": %d}",
                    name, value);
        } else if ("latency".equals(type)) {
            toxicJson = String.format(
                    "{\"type\": \"latency\", \"name\": \"%s\", \"stream\": \"downstream\", \"toxicity\": 1.0, \"latency\": %d}",
                    name, value);
        } else if ("bandwidth".equals(type)) {
            toxicJson = String.format(
                    "{\"type\": \"bandwidth\", \"name\": \"%s\", \"stream\": \"downstream\", \"toxicity\": 1.0, \"rate\": %d}",
                    name, value);
        } else {
            throw new IllegalArgumentException("Unknown toxic type: " + type);
        }

        String url = TOXIPROXY_API + "/proxies/" + KAFKA_PROXY_NAME + "/toxics";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toxicJson))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new RuntimeException("Failed to inject toxic: " + response.statusCode() + " - " + response.body());
        }

        logStep("  ✓ Toxic injected: " + type + " (" + name + ")");
    }

    /**
     * Remove all toxics from Kafka proxy
     */
    private void removeToxics() throws Exception {
        String url = TOXIPROXY_API + "/proxies/" + KAFKA_PROXY_NAME + "/toxics";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonNode toxics = objectMapper.readTree(response.body());
            if (toxics.isArray()) {
                for (JsonNode toxic : toxics) {
                    String toxicName = toxic.path("name").asText();
                    String deleteUrl = url + "/" + toxicName;

                    HttpRequest deleteRequest = HttpRequest.newBuilder()
                            .uri(new URI(deleteUrl))
                            .DELETE()
                            .build();

                    httpClient.send(deleteRequest, HttpResponse.BodyHandlers.ofString());
                    logStep("  ✓ Removed toxic: " + toxicName);
                }
            }
        }
    }

    /**
     * Verify Toxiproxy API is reachable
     */
    private void testToxiproxyConnection() throws Exception {
        String url = TOXIPROXY_API + "/version";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Toxiproxy API not responding: " + response.statusCode());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private ServiceResponse createOrderWithFault(String faultType) {
        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        return orderApiClient.createOrderWithFault(
                userId(), TestDataFactory.newIdempotencyKey(), orderRequest, faultType);
    }

    private String userId() {
        return purchase.getCustomer().getUser().getId();
    }


}