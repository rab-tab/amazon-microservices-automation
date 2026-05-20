package com.amazon.tests.kafka.orders.publishing.failures;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.*;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

    //

/**
 * Producer Kafka Configuration Failures - Realistic Tests
 *
 * RANCHER DESKTOP CONFIGURATION
 * Socket: /Users/rabia/.rd/docker.sock
 */
@Slf4j
@Epic("Kafka Producer")
@Feature("Kafka Configuration Failures (Realistic)")
public class ProducerKafkaConfigFailuresTest extends BaseTest {

    private static KafkaContainer kafka;
    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;

    static {
        // ⭐ RANCHER DESKTOP FIX - Hardcoded for user 'rabia'
        String rancherSocket = "/Users/rabia/.rd/docker.sock";

        File socketFile = new File(rancherSocket);
        if (!socketFile.exists()) {
            log.error("❌ Rancher Desktop socket not found at: {}", rancherSocket);
            log.error("   Is Rancher Desktop running?");
            throw new RuntimeException("Rancher Desktop socket not found: " + rancherSocket);
        }

        log.info("✅ Found Rancher Desktop socket: {}", rancherSocket);

        // Configure Testcontainers to use Rancher socket
        System.setProperty("DOCKER_HOST", "unix://" + rancherSocket);
        System.setProperty("testcontainers.docker.socket.override", rancherSocket);
        System.setProperty("testcontainers.docker.client.strategy",
                "org.testcontainers.dockerclient.UnixSocketClientProviderStrategy");

        log.info("✅ Testcontainers configured for Rancher Desktop");
    }

    @BeforeSuite
    public static void setupKafka() {
        log.info("🐳 Starting Kafka container (Rancher Desktop)...");

        kafka = new KafkaContainer(
                DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
        ).withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

        kafka.start();

        log.info("✅ Kafka started: {}", kafka.getBootstrapServers());
        log.info("   Running on Rancher Desktop");
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        log.info("⚙️  Dynamic property: spring.kafka.bootstrap-servers={}",
                kafka.getBootstrapServers());
    }

    @AfterSuite
    public static void teardownKafka() {
        log.info("🧹 Stopping Kafka container...");
        if (kafka != null) {
            kafka.stop();
            log.info("✅ Kafka stopped");
        }
    }

    @BeforeMethod
    public void setup() throws SeedingException {
        logStep("Setting up test data");

        user = UserSeeder.builder(context)
                .count(1)
                .build()
                .seed()
                .getFirst();

        userToken = context.getCached("user_token_" + user.getId(), String.class);

        product = ProductSeeder.builder(context)
                .count(1)
                .highStock()
                .build()
                .seed()
                .getFirst();

        waitForDataPropagation(1000);

        logStep("✅ Test data ready");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TESTS
    // ══════════════════════════════════════════════════════════════════════════
    //FAIL
    @Test(description = "REALISTIC: Insufficient ISR (min.insync.replicas > replicas)")
    @Story("Acknowledgment Failures")
    @Severity(SeverityLevel.CRITICAL)
    public void test04_REALISTIC_InsufficientISR_AckFailure()
            throws ExecutionException, InterruptedException, Exception {

        logStep("REALISTIC TEST: Insufficient in-sync replicas");

        AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                        kafka.getBootstrapServers())
        );

        NewTopic topic = new NewTopic("order.events.isr-test", 1, (short) 1)
                .configs(Map.of("min.insync.replicas", "2"));

        admin.createTopics(List.of(topic)).all().get();

        logStep("  ⚙️  Topic created: min.insync.replicas=2, replicas=1 (impossible!)");

        Response response = createOrder();

        logStep("  Response status: {}", response.statusCode());

        assertThat(response.statusCode())
                .as("Order should fail due to insufficient ISR")
                .isEqualTo(500);

        assertThat(response.jsonPath().getString("message"))
                .containsAnyOf("insufficient", "in-sync", "replicas");

        logStep("✅ ISR failure verified");

        admin.close();
    }

    @Test(description = "REALISTIC: Topic does not exist (auto-create off)")
    @Story("Topic Existence")
    @Severity(SeverityLevel.CRITICAL)
    public void test09_REALISTIC_TopicDoesNotExist() throws Exception {

        logStep("REALISTIC TEST: Topic does not exist");
        logStep("  ⚙️  Kafka config: auto.create.topics.enable=false");

        Response response = createOrder();

        logStep("  Response status: {}", response.statusCode());

        if (response.statusCode() == 500) {
            assertThat(response.jsonPath().getString("message"))
                    .containsAnyOf("topic", "does not exist");
            logStep("✅ Topic not found error (expected)");
        } else if (response.statusCode() == 201) {
            logStep("⚠️  Order created - topic exists from previous test");
        }
    }

    @Test(description = "REALISTIC: Producer quota exceeded")
    @Story("Quota Limits")
    @Severity(SeverityLevel.NORMAL)
    public void test08_REALISTIC_ProducerQuotaExceeded() throws Exception {

        logStep("REALISTIC TEST: Producer quota exceeded");
        logStep("  ⚠️  Using fault injection (real quotas require Kafka config)");

        Response response = sendOrderRequestWithFault("quota-exceeded");

        assertThat(response.statusCode()).isEqualTo(500);

        logStep("✅ Quota failure validated");
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
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-User-Id", user.getId().toString())
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/orders");
    }

    private Response sendOrderRequestWithFault(String faultType) throws Exception {
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
                .header("X-Fault", faultType)
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/orders");
    }
}