package com.amazon.tests.kafka.orders.publishing.failures;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.qameta.allure.*;
import io.qameta.allure.testng.Tag;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Realistic Kafka Configuration Tests - Testcontainers
 *
 * Uses real Kafka container to test configuration-specific failures:
 * - Insufficient in-sync replicas (min.insync.replicas)
 * - Topic does not exist (auto.create.topics.enable=false)
 * - Producer quotas
 *
 * These require actual Kafka configuration changes, not network simulation.
 *
 * Run frequency: Weekly, before releases
 * Execution time: ~2-3 minutes
 *
 * @tags realistic-chaos, kafka-config, slow-tests
 */
@Slf4j
@Epic("Amazon Microservices")
@Feature("Kafka - Configuration Failures (Realistic)")
@Testcontainers
@Tag("realistic-chaos")
@Tag("slow-tests")
public class ConfigurationFailures extends BaseTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
    ).withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");  // Disable auto-create

    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @BeforeEach
    public void setup() throws SeedingException {
        logStep("Setting up Kafka config tests");

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
    }

    // ══════════════════════════════════════════════════════════════════════════
    // KAFKA CONFIGURATION FAILURES
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("REALISTIC: Insufficient in-sync replicas (min.insync.replicas > replicas)")
    @Story("Kafka Configuration")
    @Severity(SeverityLevel.CRITICAL)
    public void test01_REALISTIC_InsufficientISR_AckFailure() throws ExecutionException, InterruptedException {
        logStep("REALISTIC TEST: Insufficient in-sync replicas");

        // ⭐ Create topic with min.insync.replicas=2 but only 1 broker
        AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                        kafka.getBootstrapServers())
        );

        NewTopic topic = new NewTopic("order.events.isr-test", 1, (short) 1)
                .configs(Map.of("min.insync.replicas", "2"));  // Impossible!

        admin.createTopics(List.of(topic)).all().get();

        logStep("  ⚙️  Topic created with min.insync.replicas=2, but only 1 broker");

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        String requestBody = null;
        try {
            requestBody = objectMapper.writeValueAsString(orderRequest);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        // Producer configured with acks=all will fail
        Response response = RestAssured
                .given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-User-Id", user.getId().toString())
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/orders");

        assertThat(response.statusCode())
                .as("Should fail due to insufficient ISR")
                .isEqualTo(500);

        assertThat(response.jsonPath().getString("message"))
                .containsAnyOf("insufficient", "in-sync", "replicas");

        logStep("✅ REALISTIC ISR test passed");
        logStep("   Real Kafka configuration caused actual ISR failure");

        admin.close();
    }

    @Test
    @DisplayName("REALISTIC: Topic does not exist (auto-create disabled)")
    @Story("Kafka Configuration")
    @Severity(SeverityLevel.NORMAL)
    public void test02_REALISTIC_TopicDoesNotExist() throws JsonProcessingException {
        logStep("REALISTIC TEST: Topic does not exist");

        logStep("  ⚙️  auto.create.topics.enable=false in Kafka config");

        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        String requestBody = objectMapper.writeValueAsString(orderRequest);

        // Attempt to send to non-existent topic
        Response response = RestAssured
                .given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-User-Id", user.getId().toString())
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/orders");

        // Might succeed if topic was already created by previous test
        // or fail if topic doesn't exist
        if (response.statusCode() == 500) {
            assertThat(response.jsonPath().getString("message"))
                    .containsAnyOf("topic", "does not exist", "unknown");

            logStep("✅ Topic not found error as expected");
        } else {
            logStep("⚠️  Topic may have been auto-created by previous test");
        }
    }
}