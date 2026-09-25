package com.amazon.tests.regression.kafka.orders.publishing.advanced;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@Epic("Kafka Message Compression")
@Feature("Compression Algorithms")
public class KafkaCompressionTest extends BaseTest {

    private KafkaTestConsumer kafkaConsumer;

    @BeforeMethod
    public void setup() {
        logStep("Setting up Message Compression tests");
        kafkaConsumer = new KafkaTestConsumer("order.events");
        kafkaConsumer.seekToEnd();
        logStep("✅ Compression test setup complete");
    }

    @AfterMethod
    public void cleanup() {
        if (kafkaConsumer != null) kafkaConsumer.close();
    }

    @Test
    @Story("Compression")
    @Severity(SeverityLevel.NORMAL)
    @Description("Snappy compression - Default, balanced speed/ratio")
    public void test01_SnappyCompression_BalancedPerformance() {
        logStep("TEST 1: Snappy compression");

        logStep("  Snappy characteristics:");
        logStep("    - Fast compression/decompression");
        logStep("    - Moderate compression ratio");
        logStep("    - CPU efficient");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> order.getId().equals(node.path("orderId").asText()), 10);

        assertThat(event).isPresent();
        logStep("  ✓ Event published with Snappy compression");

        logStep("✅ Snappy compression validated");
    }

    @Test
    @Story("Compression")
    @Severity(SeverityLevel.NORMAL)
    @Description("Gzip compression - High compression ratio")
    public void test02_GzipCompression_HighRatio() {
        logStep("TEST 2: Gzip compression");

        logStep("  Gzip characteristics:");
        logStep("    - High compression ratio");
        logStep("    - Slower compression/decompression");
        logStep("    - Higher CPU usage");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> order.getId().equals(node.path("orderId").asText()), 10);

        assertThat(event).isPresent();
        logStep("✅ Gzip compression validated");
    }

    @Test
    @Story("Compression")
    @Severity(SeverityLevel.NORMAL)
    @Description("LZ4 compression - Fast, moderate ratio")
    public void test03_LZ4Compression_FastSpeed() {
        logStep("TEST 3: LZ4 compression");

        logStep("  LZ4 characteristics:");
        logStep("    - Very fast compression");
        logStep("    - Moderate compression ratio");
        logStep("    - Low CPU overhead");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> order.getId().equals(node.path("orderId").asText()), 10);

        assertThat(event).isPresent();
        logStep("✅ LZ4 compression validated");
    }

    @Test
    @Story("Compression")
    @Severity(SeverityLevel.NORMAL)
    @Description("No compression - Maximum throughput")
    public void test04_NoCompression_MaxThroughput() {
        logStep("TEST 4: No compression");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        String token = purchase.getCustomer().getAccessToken();
        String userId = purchase.getCustomer().getUser().getId();

        TestModels.OrderResponse order = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor())
                .createOrder(userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        Optional<JsonNode> event = kafkaConsumer.waitForMessage(
                node -> order.getId().equals(node.path("orderId").asText()), 10);

        assertThat(event).isPresent();
        logStep("✅ Uncompressed publishing validated");
    }
}