
package com.amazon.tests.regression.kafka.orders.publishing.failures;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.*;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;


@Slf4j
@Epic("Kafka Producer")
@Feature("Network Failures")
public class ProducerNetworkFailuresTest extends BaseTest {

    private static KafkaContainer kafka;
    private static ToxiproxyContainer toxiproxy;
    private static Proxy kafkaProxy;

    private KafkaTestConsumer kafkaConsumer;

    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;

    private String userToken;

    private static final String ORDER_EVENTS_TOPIC = "order.events";

    private static final String PROXY_NAME = "kafka";

    private static final int PROXY_PORT = 8666;

    private static final int EVENT_TIMEOUT_SECONDS = 15;


    // ---------------------------------------------------------
    // CONTAINER SETUP
    // ---------------------------------------------------------

    @BeforeSuite(alwaysRun = true)
    public static void setupContainers() throws IOException {

        log.info("Starting Kafka and Toxiproxy containers...");

        kafka = new KafkaContainer(
                DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
        );

        kafka.start();

        toxiproxy = new ToxiproxyContainer(
                "ghcr.io/shopify/toxiproxy:2.5.0"
        );

        toxiproxy.start();

        ToxiproxyClient toxiproxyClient = new ToxiproxyClient(
                toxiproxy.getHost(),
                toxiproxy.getControlPort()
        );

        kafkaProxy = toxiproxyClient.createProxy(
                PROXY_NAME,
                "0.0.0.0:" + PROXY_PORT,
                kafka.getHost() + ":" + kafka.getMappedPort(9093)
        );

        String proxiedEndpoint =
                toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(PROXY_PORT);

        log.info("Kafka direct endpoint: {}", kafka.getBootstrapServers());
        log.info("Kafka proxy endpoint: {}", proxiedEndpoint);

        log.warn("Order Service must be started with:");
        log.warn("SPRING_KAFKA_BOOTSTRAP_SERVERS={}", proxiedEndpoint);

        log.warn(
                "IMPORTANT: Verify Kafka advertised.listeners. "
                        + "The producer must route its broker connections through "
                        + "Toxiproxy, not just its initial bootstrap connection."
        );
    }


    @AfterSuite(alwaysRun = true)
    public static void teardownContainers() {

        if (kafkaProxy != null) {
            try {
                kafkaProxy.disable();
            } catch (Exception exception) {
                log.warn("Unable to disable Kafka proxy during shutdown", exception);
            }
        }

        if (kafka != null) {
            kafka.stop();
        }

        if (toxiproxy != null) {
            toxiproxy.stop();
        }

        log.info("Kafka and Toxiproxy containers stopped");
    }


    // ---------------------------------------------------------
    // TEST SETUP
    // ---------------------------------------------------------

    @BeforeMethod(alwaysRun = true)
    public void setupTest() throws SeedingException, IOException {

        restoreProxy();

        user = UserSeeder.builder(context)
                .count(1)
                .build()
                .seed()
                .getFirst();

        userToken = context.getCached(
                "user_token_" + user.getId(),
                String.class
        );

        product = ProductSeeder.builder(context)
                .count(1)
                .highStock()
                .build()
                .seed()
                .getFirst();

        waitForDataPropagation(1000);

        kafkaConsumer = new KafkaTestConsumer(
                ORDER_EVENTS_TOPIC,
                kafka.getBootstrapServers()
        );

        kafkaConsumer.seekToEnd();
    }


    // ---------------------------------------------------------
    // TEST 01 - CONNECTION RESET
    // ---------------------------------------------------------

    @Test(description = "Connection reset during Kafka publishing")
    @Story("Connection Reset")
    @Severity(SeverityLevel.CRITICAL)
    public void test01_ConnectionReset() throws Exception {

        logStep("Injecting TCP connection reset");

        kafkaProxy.toxics().resetPeer(
                "connection_reset",
                ToxicDirection.UPSTREAM,
                0
        );

        Response response = createOrder();

        assertOrderAccepted(response);

        logStep("Order API accepted the request despite the network fault");
    }


    // ---------------------------------------------------------
    // TEST 02 - HIGH NETWORK LATENCY
    // ---------------------------------------------------------

    @Test(description = "High network latency")
    @Story("Network Latency")
    @Severity(SeverityLevel.CRITICAL)
    public void test02_HighNetworkLatency() throws Exception {

        logStep("Injecting 10-second upstream latency");

        kafkaProxy.toxics().latency(
                "high_latency",
                ToxicDirection.UPSTREAM,
                10000
        );

        Response response = createOrder();

        assertOrderAccepted(response);
    }


    // ---------------------------------------------------------
    // TEST 03 - SEVERE BANDWIDTH RESTRICTION
    // ---------------------------------------------------------

    @Test(description = "Severe bandwidth restriction")
    @Story("Bandwidth Restriction")
    @Severity(SeverityLevel.CRITICAL)
    public void test03_SevereBandwidthRestriction() throws Exception {

        logStep("Restricting upstream bandwidth");

        kafkaProxy.toxics()
                .bandwidth(
                        "severe_bandwidth",
                        ToxicDirection.UPSTREAM,
                        1
                )
                .setRate(1);

        Response response = createOrder();

        assertOrderAccepted(response);
    }


    // ---------------------------------------------------------
    // TEST 04 - NETWORK JITTER
    // ---------------------------------------------------------

    @Test(description = "Variable network latency")
    @Story("Network Jitter")
    @Severity(SeverityLevel.NORMAL)
    public void test04_NetworkJitter() throws Exception {

        logStep("Injecting network jitter: 2000ms with 1000ms variation");

        kafkaProxy.toxics()
                .latency(
                        "network_jitter",
                        ToxicDirection.DOWNSTREAM,
                        2000
                )
                .setJitter(1000);

        Response response = createOrder();

        assertOrderAccepted(response);
    }


    // ---------------------------------------------------------
    // TEST 05 - CONNECTION RESET DURING SEND
    // ---------------------------------------------------------

    @Test(description = "Connection reset in broker-to-producer direction")
    @Story("Connection Reset")
    @Severity(SeverityLevel.CRITICAL)
    public void test05_DownstreamConnectionReset() throws Exception {

        logStep("Injecting downstream connection reset");

        kafkaProxy.toxics().resetPeer(
                "downstream_reset",
                ToxicDirection.DOWNSTREAM,
                0
        );

        Response response = createOrder();

        assertOrderAccepted(response);
    }


    // ---------------------------------------------------------
    // TEST 06 - BANDWIDTH THROTTLING
    // ---------------------------------------------------------

    @Test(description = "Limited network bandwidth")
    @Story("Bandwidth Throttling")
    @Severity(SeverityLevel.NORMAL)
    public void test06_BandwidthThrottling() throws Exception {

        logStep("Limiting upstream bandwidth to 10 KB/s");

        kafkaProxy.toxics()
                .bandwidth(
                        "slow_network",
                        ToxicDirection.UPSTREAM,
                        10240
                )
                .setRate(10240);

        Response response = createOrder();

        assertOrderAccepted(response);
    }


    // ---------------------------------------------------------
    // TEST 07 - RANDOM CONNECTION SLICING
    // ---------------------------------------------------------

    @Test(description = "Random TCP connection slicing")
    @Story("Intermittent Network Disruption")
    @Severity(SeverityLevel.NORMAL)
    public void test07_RandomConnectionSlicing() throws Exception {

        logStep("Injecting downstream connection slicing");

        kafkaProxy.toxics().slicer(
                "random_slicing",
                ToxicDirection.DOWNSTREAM,
                100,
                10
        );

        Response response = createOrder();

        assertOrderAccepted(response);
    }


    // ---------------------------------------------------------
    // TEST 08 - SLOW CONNECTION CLOSE
    // ---------------------------------------------------------

    @Test(description = "Slow TCP connection close")
    @Story("Connection Lifecycle")
    @Severity(SeverityLevel.NORMAL)
    public void test08_SlowConnectionClose() throws Exception {

        logStep("Injecting slow connection close");

        kafkaProxy.toxics().slowClose(
                "slow_connection_close",
                ToxicDirection.UPSTREAM,
                5000
        );

        Response response = createOrder();

        assertOrderAccepted(response);
    }


    // ---------------------------------------------------------
    // TEST 09 - NETWORK RECOVERY
    // ---------------------------------------------------------

    @Test(description = "Eventual delivery after network recovery")
    @Story("Network Recovery")
    @Severity(SeverityLevel.CRITICAL)
    public void test09_NetworkRecovery() throws Exception {

        logStep("Injecting temporary upstream latency");

        kafkaProxy.toxics().latency(
                "temporary_latency",
                ToxicDirection.UPSTREAM,
                3000
        );

        Response response = createOrder();

        assertOrderAccepted(response);

        String orderId = response.jsonPath().getString("id");

        logStep("Removing latency toxic to restore network");

        kafkaProxy.toxics()
                .get("temporary_latency")
                .remove();

        Optional<JsonNode> event = waitForOrderEvent(orderId);

        assertThat(event)
                .as("Expected ORDER_CREATED event after network recovery")
                .isPresent();

        assertThat(event.get().path("eventType").asText())
                .isEqualTo("ORDER_CREATED");

        assertThat(event.get().path("orderId").asText())
                .isEqualTo(orderId);
    }


    // ---------------------------------------------------------
    // ORDER CREATION
    // ---------------------------------------------------------

    private Response createOrder() throws Exception {

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest =
                OrderBuilder.anOrder()
                        .withNamespace(context.getNamespace())
                        .addItem(product, 1)
                        .build();

        String requestBody = objectMapper.writeValueAsString(orderRequest);

        return RestAssured
                .given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-User-Id", user.getId().toString())
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/orders");
    }


    // ---------------------------------------------------------
    // ASSERTIONS
    // ---------------------------------------------------------

    private void assertOrderAccepted(Response response) {

        log.info(
                "Order API response: status={}, body={}",
                response.statusCode(),
                response.asString()
        );

        assertThat(response.statusCode())
                .as("Order creation should be accepted independently "
                        + "of asynchronous Kafka publishing")
                .isEqualTo(201);

        assertThat(response.jsonPath().getString("id"))
                .as("Created order should contain an ID")
                .isNotBlank();
    }


    private Optional<JsonNode> waitForOrderEvent(String orderId) {

        return kafkaConsumer.waitForMessage(
                node -> node.has("orderId")
                        && orderId.equals(node.path("orderId").asText()),
                EVENT_TIMEOUT_SECONDS
        );
    }


    // ---------------------------------------------------------
    // NETWORK AND CLEANUP HELPERS
    // ---------------------------------------------------------

    private void restoreProxy() throws IOException {

        if (kafkaProxy != null) {
            kafkaProxy.enable();

            kafkaProxy.toxics().getAll().forEach(toxic -> {
                try {
                    toxic.remove();
                } catch (IOException exception) {
                    throw new RuntimeException(
                            "Failed to remove toxic: " + toxic.getName(),
                            exception
                    );
                }
            });
        }
    }


    @AfterMethod(alwaysRun = true)
    public void cleanupTest() {

        try {

            if (kafkaProxy != null) {

                kafkaProxy.enable();

                kafkaProxy.toxics().getAll().forEach(toxic -> {
                    try {
                        toxic.remove();
                    } catch (IOException exception) {
                        log.warn(
                                "Failed to remove toxic {}",
                                toxic.getName(),
                                exception
                        );
                    }
                });
            }

        } catch (IOException exception) {

            log.error("Failed to restore Kafka proxy", exception);

        } finally {

            if (kafkaConsumer != null) {
                kafkaConsumer.close();
                kafkaConsumer = null;
            }
        }
    }
}
