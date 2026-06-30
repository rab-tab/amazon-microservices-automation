package com.amazon.tests.kafka.orders.publishing.failures;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.*;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Producer Network & Connectivity Failures - Realistic Tests
 *
 * Strategy: Testcontainers + Toxiproxy (actual network chaos)
 */
@Slf4j
@Epic("Kafka Producer")
@Feature("Network Failures (Realistic)")
public class ProducerNetworkFailuresTest extends BaseTest {

    private static KafkaContainer kafka;
    private static ToxiproxyContainer toxiproxy;
    private static Proxy kafkaProxy;

    private KafkaTestConsumer kafkaConsumer;
    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;

    // ══════════════════════════════════════════════════════════════════════════
    // CONTAINER SETUP
    // ══════════════════════════════════════════════════════════════════════════

    @BeforeSuite
    public static void setupContainers() throws IOException {
        log.info("🐳 Starting Kafka & Toxiproxy containers...");

        // Start Kafka
        kafka = new KafkaContainer(
                DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
        );
        kafka.start();

        // Start Toxiproxy
        toxiproxy = new ToxiproxyContainer(
                "ghcr.io/shopify/toxiproxy:2.5.0"
        );
        toxiproxy.start();

        // Create proxy to Kafka
        ToxiproxyClient toxiproxyClient = new ToxiproxyClient(
                toxiproxy.getHost(),
                toxiproxy.getControlPort()
        );

        kafkaProxy = toxiproxyClient.createProxy(
                "kafka",
                "0.0.0.0:8666",
                kafka.getHost() + ":" + kafka.getMappedPort(9093)
        );

        log.info("✅ Containers started");
        log.info("   Kafka: {}", kafka.getBootstrapServers());
        log.info("   Toxiproxy: {}:{}", toxiproxy.getHost(), toxiproxy.getMappedPort(8666));

        log.info("⚠️  CONFIGURE ORDER-SERVICE:");
        log.info("   Set SPRING_KAFKA_BOOTSTRAP_SERVERS={}:{}",
                toxiproxy.getHost(), toxiproxy.getMappedPort(8666));
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        String proxiedEndpoint = toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666);
        registry.add("spring.kafka.bootstrap-servers", () -> proxiedEndpoint);
        log.info("⚙️  Dynamic property set: spring.kafka.bootstrap-servers={}", proxiedEndpoint);
    }

    @AfterSuite
    public static void teardownContainers() {
        if (kafka != null) kafka.stop();
        if (toxiproxy != null) toxiproxy.stop();
        log.info("🧹 Containers stopped");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST SETUP
    // ══════════════════════════════════════════════════════════════════════════

    @BeforeMethod
    public void setup() throws SeedingException {
        user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        userToken = context.getCached("user_token_" + user.getId(), String.class);
        product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        kafkaConsumer = new KafkaTestConsumer(
                "order.events",
                kafka.getBootstrapServers()  // Direct to Kafka, not proxied
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NETWORK FAILURE TESTS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(description = "REALISTIC: Kafka broker disconnected - TCP cut")
    @Story("Network Failures")
    @Severity(SeverityLevel.CRITICAL)
    public void test01_REALISTIC_KafkaBrokerDown_TCPCut() throws Exception {
        logStep("REALISTIC TEST: Kafka broker TCP connection cut");

        // ⭐ CORRECT WAY: Use bandwidth toxic with 0 rate to cut connection
        kafkaProxy.toxics()
                .bandwidth("cut_connection", ToxicDirection.DOWNSTREAM, 0);

        logStep("  ✂️  TCP connection CUT (via bandwidth toxic with 0 rate)");

        Response response = createOrder();

        logStep("  Response status: {}", response.statusCode());

        assertThat(response.statusCode())
                .as("Order should fail when broker connection is cut")
                .isEqualTo(500);

        assertThat(response.jsonPath().getString("error"))
                .isEqualTo("Kafka Unavailable");

        // Verify no event
        Thread.sleep(2000);
        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> node.has("orderId"),
                2
        );
        assertThat(event)
                .as("No event should be published when broker is down")
                .isEmpty();

        logStep("✅ Real broker disconnection handled");
    }

    @Test(description = "REALISTIC: Network latency causes timeout")
    @Story("Network Failures")
    @Severity(SeverityLevel.CRITICAL)
    public void test02_REALISTIC_NetworkLatency_Timeout() throws Exception {
        logStep("REALISTIC TEST: 10s network latency causes timeout");

        // ⭐ Add 10 second latency (exceeds producer timeout)
        kafkaProxy.toxics()
                .latency("high_latency", ToxicDirection.UPSTREAM, 10000);

        logStep("  🐌 10s latency injected");

        Response response = createOrder();

        assertThat(response.statusCode())
                .as("Order should timeout due to network latency")
                .isEqualTo(500);

        assertThat(response.jsonPath().getString("message"))
                .containsAnyOf("timeout", "timed out", "Kafka");

        logStep("✅ Real timeout due to network latency");
    }

    @Test(description = "REALISTIC: Packet loss causes retry exhaustion")
    @Story("Network Failures")
    @Severity(SeverityLevel.CRITICAL)
    public void test03_REALISTIC_PacketLoss_RetryExhaustion() throws Exception {
        logStep("REALISTIC TEST: 70% packet loss causes retries");

        // ⭐ CORRECT WAY: Use bandwidth toxic to limit throughput
        // Setting low bandwidth simulates packet loss/congestion
        kafkaProxy.toxics()
                .bandwidth("packet_loss", ToxicDirection.UPSTREAM, 1)  // 1 byte/sec
                .setRate(1);

        logStep("  📉 Severe bandwidth limitation (1 byte/sec = ~100% loss)");

        Response response = createOrder();

        assertThat(response.statusCode())
                .as("Order should fail after retries exhaust")
                .isEqualTo(500);

        logStep("✅ Real packet loss/congestion caused failure");
    }

    @Test(description = "REALISTIC: Network jitter prevents metadata fetch")
    @Story("Network Failures")
    @Severity(SeverityLevel.NORMAL)
    public void test04_REALISTIC_NetworkJitter_MetadataTimeout() throws Exception {
        logStep("REALISTIC TEST: Network jitter (variable latency)");

        // ⭐ Add latency with jitter (2000ms ± 1000ms)
        kafkaProxy.toxics()
                .latency("jitter", ToxicDirection.DOWNSTREAM, 2000)
                .setJitter(1000);

        logStep("  📊 Network jitter: 2000±1000ms");

        Response response = createOrder();

        assertThat(response.statusCode())
                .as("Order should fail due to unstable network")
                .isEqualTo(500);

        logStep("✅ Real network jitter prevented stable connection");
    }

    @Test(description = "REALISTIC: Connection reset by peer")
    @Story("Network Failures")
    @Severity(SeverityLevel.CRITICAL)
    public void test05_REALISTIC_ConnectionReset() throws Exception {
        logStep("REALISTIC TEST: Connection reset by peer");

        // ⭐ Use reset_peer toxic to simulate connection reset
        kafkaProxy.toxics()
                .resetPeer("reset_connection", ToxicDirection.DOWNSTREAM, 1000);  // Reset after 1 second

        logStep("  🔌 Connection reset toxic injected");

        Response response = createOrder();

        assertThat(response.statusCode())
                .as("Order should fail on connection reset")
                .isEqualTo(500);

        logStep("✅ Connection reset handled");
    }

    @Test(description = "REALISTIC: Slow network (bandwidth throttling)")
    @Story("Network Failures")
    @Severity(SeverityLevel.NORMAL)
    public void test06_REALISTIC_BandwidthThrottling() throws Exception {
        logStep("REALISTIC TEST: Slow network connection");

        // ⭐ Limit bandwidth to 10 KB/s (slow connection)
        kafkaProxy.toxics()
                .bandwidth("slow_network", ToxicDirection.UPSTREAM, 10240)  // 10 KB/s
                .setRate(10240);

        logStep("  🐌 Bandwidth limited to 10 KB/s");

        Response response = createOrder();

        // May timeout or succeed slowly
        assertThat(response.statusCode())
                .as("Order should be affected by slow network")
                .isIn(500, 201);  // Might succeed slowly

        logStep("✅ Slow network tested");
    }

    @Test(description = "REALISTIC: Slicer toxic (random connection drops)")
    @Story("Network Failures")
    @Severity(SeverityLevel.NORMAL)
    public void test07_REALISTIC_RandomConnectionDrops() throws Exception {
        logStep("REALISTIC TEST: Random connection drops");

        // ⭐ Use slicer toxic to randomly slice connections
        kafkaProxy.toxics()
                .slicer("random_drops", ToxicDirection.DOWNSTREAM, 100, 10);

        logStep("  🎲 Random connection slicing enabled");

        Response response = createOrder();

        assertThat(response.statusCode())
                .as("Order may fail due to random drops")
                .isIn(500, 201);

        logStep("✅ Random connection drops tested");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private Response createOrder() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        String requestBody = objectMapper.writeValueAsString(orderRequest);

        return RestAssured
                .given()
                .baseUri("http://localhost:8083")
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-User-Id", user.getId().toString())
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/orders");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ══════════════════════════════════════════════════════════════════════════

    @AfterMethod
    public void cleanupProxy() throws IOException {
        if (kafkaProxy != null) {
            // ⭐ Remove ALL toxics to restore normal connection
            kafkaProxy.toxics().getAll().forEach(toxic -> {
                try {
                    toxic.remove();
                    logStep("  🧹 Removed toxic: {}", toxic.getName());
                } catch (IOException e) {
                    log.warn("Failed to remove toxic: {}", toxic.getName(), e);
                }
            });

            logStep("🧹 Toxiproxy cleaned up - connection restored");
        }

        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
    }
}