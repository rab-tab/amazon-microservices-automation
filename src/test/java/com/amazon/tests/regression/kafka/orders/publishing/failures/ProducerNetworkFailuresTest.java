
package com.amazon.tests.regression.kafka.orders.publishing.failures;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.kafka.KafkaTestUtils;
import com.fasterxml.jackson.databind.JsonNode;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.qameta.allure.*;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.*;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@Epic("Kafka Producer")
@Feature("Network Failures")
public class ProducerNetworkFailuresTest extends BaseTest {

    private static KafkaContainer kafka;
    private static ToxiproxyContainer toxiproxy;
    private static Network network;
    private static Proxy kafkaProxy;

    private KafkaTestConsumer kafkaConsumer;

    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String PROXY_NAME = "kafka";

    private static final int TOXIPROXY_CONTROL_PORT = 8474;
    private static final int PROXY_PORT = 8666;

    private static final int EVENT_TIMEOUT_SECONDS = 15;
    private static final int FAULT_OBSERVATION_DELAY_MS = 1000;

    // =========================================================
    // CONTAINER SETUP
    // =========================================================

    @BeforeSuite(alwaysRun = true)
    public static void setupContainers() throws IOException {

        log.info("Starting Kafka and Toxiproxy containers...");
        network = Network.newNetwork();
        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));
        kafka.withNetwork(network).withNetworkAliases("kafka");
        kafka.start();
        toxiproxy = new ToxiproxyContainer(
                "ghcr.io/shopify/toxiproxy:2.5.0");

        /*
         * Expose both:
         * 8474 -> Toxiproxy management API
         * 8666 -> Kafka proxy data listener
         */
        toxiproxy.withNetwork(network)
                .withExposedPorts(
                        TOXIPROXY_CONTROL_PORT,
                        PROXY_PORT
                );
        toxiproxy.start();
        ToxiproxyClient toxiproxyClient = new ToxiproxyClient(
                toxiproxy.getHost(),
                toxiproxy.getMappedPort(TOXIPROXY_CONTROL_PORT)
        );

        /*
         * Toxiproxy and Kafka share a Docker network.
         * The upstream address is Kafka's internal listener,
         * not the host-mapped Kafka address.
         */
        kafkaProxy = toxiproxyClient.createProxy(
                PROXY_NAME,
                "0.0.0.0:" + PROXY_PORT,
                "kafka:9093"
        );

        String proxiedEndpoint =
                toxiproxy.getHost()
                        + ":"
                        + toxiproxy.getMappedPort(PROXY_PORT);

        /*
         * KafkaTestConsumer reads this property when its class
         * initializes. Set it before creating the first consumer.
         */
        System.setProperty(
                "kafka.bootstrap.servers",
                kafka.getBootstrapServers()
        );

        log.info("Kafka direct endpoint: {}", kafka.getBootstrapServers());
        log.info("Kafka proxy endpoint: {}", proxiedEndpoint);
        log.warn(
                "Order Service must use the Toxiproxy endpoint as "
                        + "spring.kafka.bootstrap-servers: {}",
                proxiedEndpoint
        );
        log.warn(
                "Verify Kafka advertised.listeners. The broker metadata "
                        + "returned to Order Service must route subsequent "
                        + "producer connections through Toxiproxy as well."
        );
    }

    // =========================================================
    // CONTAINER TEARDOWN
    // =========================================================

    @AfterSuite(alwaysRun = true)
    public static void teardownContainers() {

        if (kafkaProxy != null) {
            try {
                KafkaTestUtils.restoreProxy(kafkaProxy);
                kafkaProxy.disable();
            } catch (Exception exception) {
                log.warn(
                        "Unable to restore Kafka proxy during shutdown",
                        exception
                );
            }
        }

        if (toxiproxy != null) {
            toxiproxy.stop();
        }

        if (kafka != null) {
            kafka.stop();
        }

        if (network != null) {
            network.close();
        }

        log.info("Kafka and Toxiproxy containers stopped");
    }

    // =========================================================
    // TEST SETUP
    // =========================================================

    @BeforeMethod(alwaysRun = true)
    public void setupTest() throws SeedingException, IOException {

        KafkaTestUtils.restoreProxy(kafkaProxy);

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

        /*
         * Consumer uses the direct Kafka endpoint.
         * It must not use the Toxiproxy endpoint because the
         * tests are injecting faults into the producer connection.
         */
        kafkaConsumer = new KafkaTestConsumer(ORDER_EVENTS_TOPIC);
        kafkaConsumer.seekToEnd();
    }

    // =========================================================
    // TEST 01 - CONNECTION RESET
    // =========================================================

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

        assertOrderAcceptedAndEventPublished(response);

        logStep(
                "Order API accepted the request and ORDER_CREATED "
                        + "was observed after network recovery"
        );
    }

    // =========================================================
    // TEST 02 - HIGH NETWORK LATENCY
    // =========================================================

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

        assertOrderAcceptedAndEventPublished(response);
    }

    // =========================================================
    // TEST 03 - SEVERE BANDWIDTH RESTRICTION
    // =========================================================

    @Test(description = "Severe bandwidth restriction")
    @Story("Bandwidth Restriction")
    @Severity(SeverityLevel.CRITICAL)
    public void test03_SevereBandwidthRestriction() throws Exception {

        logStep("Restricting upstream bandwidth to 1 byte/s");

        kafkaProxy.toxics()
                .bandwidth(
                        "severe_bandwidth",
                        ToxicDirection.UPSTREAM,
                        1
                )
                .setRate(1);

        Response response = createOrder();

        assertOrderAcceptedAndEventPublished(response);
    }

    // =========================================================
    // TEST 04 - NETWORK JITTER
    // =========================================================

    @Test(description = "Variable network latency")
    @Story("Network Jitter")
    @Severity(SeverityLevel.NORMAL)
    public void test04_NetworkJitter() throws Exception {

        logStep(
                "Injecting network jitter: 2000ms with 1000ms variation"
        );

        kafkaProxy.toxics()
                .latency(
                        "network_jitter",
                        ToxicDirection.DOWNSTREAM,
                        2000
                )
                .setJitter(1000);

        Response response = createOrder();

        assertOrderAcceptedAndEventPublished(response);
    }

    // =========================================================
    // TEST 05 - DOWNSTREAM CONNECTION RESET
    // =========================================================

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

        assertOrderAcceptedAndEventPublished(response);
    }

    // =========================================================
    // TEST 06 - BANDWIDTH THROTTLING
    // =========================================================

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

        assertOrderAcceptedAndEventPublished(response);
    }

    // =========================================================
    // TEST 07 - RANDOM CONNECTION SLICING
    // =========================================================

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

        assertOrderAcceptedAndEventPublished(response);
    }

    // =========================================================
    // TEST 08 - SLOW CONNECTION CLOSE
    // =========================================================

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

        assertOrderAcceptedAndEventPublished(response);
    }

    // =========================================================
    // TEST 09 - NETWORK RECOVERY
    // =========================================================

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

        assertOrderAcceptedAndEventPublished(response);

        logStep(
                "ORDER_CREATED was received after the temporary "
                        + "network latency was removed"
        );
    }

    // =========================================================
    // SHARED ORDER EVENT ASSERTION
    // =========================================================

    private void assertOrderAcceptedAndEventPublished(
            Response response
    ) throws Exception {

        assertOrderAccepted(response);

        String orderId = response.jsonPath().getString("id");

        assertThat(orderId)
                .as("Order ID returned by Create Order API")
                .isNotBlank();

        /*
         * Give the producer time to encounter the configured
         * network condition before restoring the proxy.
         */
        waitForDataPropagation(FAULT_OBSERVATION_DELAY_MS);

        logStep("Restoring Kafka proxy and removing network faults");

        KafkaTestUtils.restoreProxy(kafkaProxy);

        /*
         * Collect matching events for the complete observation
         * window so duplicate ORDER_CREATED events can be detected.
         */
        List<JsonNode> events = kafkaConsumer.collectMessages(
                node -> node.has("eventType")
                        && "ORDER_CREATED".equals(
                        node.path("eventType").asText()
                )
                        && orderId.equals(
                        node.path("orderId").asText()
                ),
                EVENT_TIMEOUT_SECONDS
        );

        assertThat(events)
                .as(
                        "Expected exactly one ORDER_CREATED event "
                                + "for orderId " + orderId
                )
                .hasSize(1);

        JsonNode event = events.getFirst();

        assertThat(event.path("eventType").asText())
                .isEqualTo("ORDER_CREATED");

        assertThat(event.path("orderId").asText())
                .isEqualTo(orderId);
    }

    // =========================================================
    // ORDER API HELPERS
    // =========================================================

    private Response createOrder() throws Exception {

        return KafkaTestUtils.createOrder(
                context,
                objectMapper,
                user,
                product,
                userToken
        );
    }

    private void assertOrderAccepted(Response response) {

        KafkaTestUtils.assertOrderAccepted(response);
    }

    // =========================================================
    // TEST CLEANUP
    // =========================================================

    @AfterMethod(alwaysRun = true)
    public void cleanupTest() {

        try {
            KafkaTestUtils.restoreProxy(kafkaProxy);
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
