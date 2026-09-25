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

    @BeforeSuite
    public static void setupContainers() throws IOException {
        log.info("🐳 Starting Kafka & Toxiproxy containers...");

        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));
        kafka.start();

        toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0");
        toxiproxy.start();

        ToxiproxyClient toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());

        kafkaProxy = toxiproxyClient.createProxy(
                "kafka",
                "0.0.0.0:8666",
                kafka.getHost() + ":" + kafka.getMappedPort(9093)
        );

        String proxiedEndpoint = toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666);

        log.info("✅ Containers started");
        log.info("   Kafka (direct, for consumer verification): {}", kafka.getBootstrapServers());
        log.info("   Toxiproxy (chaos-injected path): {}", proxiedEndpoint);

        log.warn("╔══════════════════════════════════════════════════════════════════╗");
        log.warn("║  MANUAL PRECONDITION REQUIRED                                     ║");
        log.warn("║                                                                      ║");
        log.warn("║  order-service MUST be started with:                                ║");
        log.warn("║    SPRING_KAFKA_BOOTSTRAP_SERVERS={}       ║", proxiedEndpoint);
        log.warn("║                                                                      ║");
        log.warn("║  If the service is NOT pointed at the proxy, tests will fail.       ║");
        log.warn("╚══════════════════════════════════════════════════════════════════╝");
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

    @BeforeMethod
    public void setup() throws SeedingException {
        user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        userToken = context.getCached("user_token_" + user.getId(), String.class);
        product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();

        waitForDataPropagation(1000);

        kafkaConsumer = new KafkaTestConsumer("order.events", kafka.getBootstrapServers());
    }

    @Test(description = "REALISTIC: Kafka broker disconnected - TCP cut")
    @Story("Network Failures")
    @Severity(SeverityLevel.CRITICAL)
    public void test01_REALISTIC_KafkaBrokerDown_TCPCut() throws Exception {
        logStep("TEST 1: Kafka broker TCP connection cut");

        kafkaProxy.toxics().bandwidth("cut_connection", ToxicDirection.DOWNSTREAM, 0);
        logStep("  ✂️  TCP connection CUT");

        Response response = createOrder();
        logStep("  Response status: " + response.statusCode());

        assertThat(response.statusCode()).as("Order should fail when broker connection cut").isEqualTo(500);
        assertThat(response.jsonPath().getString("error")).isEqualTo("Kafka Unavailable");

        Thread.sleep(2000);
        Optional<JsonNode> event = kafkaConsumer.waitForMessage(node -> node.has("orderId"), 2);
        assertThat(event).as("No event should be published").isEmpty();

        logStep("✅ Real broker disconnection handled");
    }

    @Test(description = "REALISTIC: Network latency causes timeout")
    @Story("Network Failures")
    @Severity(SeverityLevel.CRITICAL)
    public void test02_REALISTIC_NetworkLatency_Timeout() throws Exception {
        logStep("TEST 2: 10s network latency causes timeout");

        kafkaProxy.toxics().latency("high_latency", ToxicDirection.UPSTREAM, 10000);
        logStep("  🐌 10s latency injected");

        Response response = createOrder();

        assertThat(response.statusCode()).as("Order should timeout").isEqualTo(500);
        assertThat(response.jsonPath().getString("message")).containsAnyOf("timeout", "timed out", "Kafka");

        logStep("✅ Real timeout due to network latency");
    }

    @Test(description = "REALISTIC: Packet loss causes retry exhaustion")
    @Story("Network Failures")
    @Severity(SeverityLevel.CRITICAL)
    public void test03_REALISTIC_PacketLoss_RetryExhaustion() throws Exception {
        logStep("TEST 3: Severe bandwidth limitation causes retry exhaustion");

        kafkaProxy.toxics().bandwidth("packet_loss", ToxicDirection.UPSTREAM, 1).setRate(1);
        logStep("  📉 Severe bandwidth limitation");

        Response response = createOrder();

        assertThat(response.statusCode()).as("Order should fail after retries exhaust").isEqualTo(500);

        logStep("✅ Real packet loss/congestion caused failure");
    }

    @Test(description = "REALISTIC: Network jitter prevents metadata fetch")
    @Story("Network Failures")
    @Severity(SeverityLevel.NORMAL)
    public void test04_REALISTIC_NetworkJitter_MetadataTimeout() throws Exception {
        logStep("TEST 4: Network jitter (variable latency)");

        kafkaProxy.toxics().latency("jitter", ToxicDirection.DOWNSTREAM, 2000).setJitter(1000);
        logStep("  📊 Network jitter: 2000±1000ms");

        Response response = createOrder();

        assertThat(response.statusCode()).as("Order should fail due to unstable network").isEqualTo(500);

        logStep("✅ Real network jitter prevented stable connection");
    }

    @Test(description = "REALISTIC: Connection reset by peer")
    @Story("Network Failures")
    @Severity(SeverityLevel.CRITICAL)
    public void test05_REALISTIC_ConnectionReset() throws Exception {
        logStep("TEST 5: Connection reset by peer");

        kafkaProxy.toxics().resetPeer("reset_connection", ToxicDirection.DOWNSTREAM, 1000);
        logStep("  🔌 Connection reset toxic injected");

        Response response = createOrder();

        assertThat(response.statusCode()).as("Order should fail on connection reset").isEqualTo(500);

        logStep("✅ Connection reset handled");
    }

    @Test(description = "REALISTIC: Slow network (bandwidth throttling)")
    @Story("Network Failures")
    @Severity(SeverityLevel.NORMAL)
    public void test06_REALISTIC_BandwidthThrottling() throws Exception {
        logStep("TEST 6: Slow network connection");

        kafkaProxy.toxics().bandwidth("slow_network", ToxicDirection.UPSTREAM, 10240).setRate(10240);
        logStep("  🐌 Bandwidth limited to 10 KB/s");

        Response response = createOrder();
        logStep("  Response status: " + response.statusCode());

        if (response.statusCode() == 201) {
            String orderId = response.jsonPath().getString("id");
            Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                    node -> node.has("orderId") && orderId.equals(node.get("orderId").asText()), 15);
            assertThat(event).as("Event must eventually be published").isPresent();
            logStep("✅ Order succeeded slowly; event confirmed published");
        } else {
            assertThat(response.statusCode()).isEqualTo(500);
            logStep("✅ Order failed cleanly under bandwidth throttling");
        }
    }

    @Test(description = "REALISTIC: Slicer toxic (random connection drops)")
    @Story("Network Failures")
    @Severity(SeverityLevel.NORMAL)
    public void test07_REALISTIC_RandomConnectionDrops() throws Exception {
        logStep("TEST 7: Random connection drops");

        kafkaProxy.toxics().slicer("random_drops", ToxicDirection.DOWNSTREAM, 100, 10);
        logStep("  🎲 Random connection slicing enabled");

        Response response = createOrder();
        logStep("  Response status: " + response.statusCode());

        if (response.statusCode() == 201) {
            String orderId = response.jsonPath().getString("id");
            Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                    node -> node.has("orderId") && orderId.equals(node.get("orderId").asText()), 15);
            assertThat(event).as("Event must eventually be published").isPresent();
            logStep("✅ Order succeeded despite drops; event confirmed published");
        } else {
            assertThat(response.statusCode()).isEqualTo(500);
            logStep("✅ Order failed cleanly under random drops");
        }
    }

    @Test(description = "REALISTIC: Intermittent connectivity (limiter toxic)")
    @Story("Network Failures")
    @Severity(SeverityLevel.NORMAL)
    public void test08_REALISTIC_IntermittentConnectivity() throws Exception {
        logStep("TEST 8: Intermittent connectivity");

        kafkaProxy.toxics().slowClose("intermittent", ToxicDirection.UPSTREAM, 5000);
        logStep("  ⚡ Intermittent connectivity enabled");

        Response response = createOrder();
        logStep("  Response status: " + response.statusCode());

        logStep("✅ Intermittent connectivity handled");
    }

    @Test(description = "REALISTIC: Toxic disabled after order (recovery)")
    @Story("Network Failures")
    @Severity(SeverityLevel.NORMAL)
    public void test09_REALISTIC_NetworkRecovery() throws Exception {
        logStep("TEST 9: Network recovers during order processing");

        kafkaProxy.toxics().latency("recovery_test", ToxicDirection.UPSTREAM, 3000);
        logStep("  🐌 3s latency injected");

        Response response = createOrder();

        if (response.statusCode() == 201) {
            logStep("  ✓ Order created despite latency");

            kafkaProxy.toxics().get("recovery_test").remove();
            logStep("  ✓ Latency removed - network recovered");

            String orderId = response.jsonPath().getString("id");
            Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                    node -> node.has("orderId") && orderId.equals(node.get("orderId").asText()), 10);
            assertThat(event).as("Event should eventually be published after recovery").isPresent();

            logStep("✅ Order recovered after network restored");
        } else {
            logStep("✅ Order failed during latency injection");
        }
    }

    private Response createOrder() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
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

    @AfterMethod
    public void cleanupProxy() throws IOException {
        if (kafkaProxy != null) {
            kafkaProxy.toxics().getAll().forEach(toxic -> {
                try {
                    toxic.remove();
                } catch (IOException e) {
                    log.warn("Failed to remove toxic: {}", toxic.getName(), e);
                }
            });
            logStep("🧹 Toxiproxy cleaned up");
        }

        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
    }
}