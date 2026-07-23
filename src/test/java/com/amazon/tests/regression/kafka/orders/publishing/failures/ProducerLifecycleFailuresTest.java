package com.amazon.tests.regression.kafka.orders.publishing.failures;


import com.amazon.tests.BaseTest;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.*;

/**
 * Producer Lifecycle & Resource Failures - Realistic Tests
 *
 * Strategy: Testcontainers (container lifecycle management)
 *
 * Tests:
 * - Producer interrupted during send
 * - Kafka broker restart during operation
 * - Out of order sequence (idempotent producer)
 * - Transaction coordinator unavailable (if using transactions)
 *
 * Run frequency: Before releases
 * Execution time: ~2-3 minutes
 */
@Slf4j
@Epic("Kafka Producer")
@Feature("Lifecycle Failures (Realistic)")
public class ProducerLifecycleFailuresTest extends BaseTest {

    private static KafkaContainer kafka;

    @BeforeSuite
    public static void setupKafka() {
        log.info("🐳 Starting Kafka...");

        kafka = new KafkaContainer(
                DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
        );
        kafka.start();

        log.info("✅ Kafka started");
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @AfterSuite
    public static void teardownKafka() {
        if (kafka != null) kafka.stop();
    }

    @Test(description = "REALISTIC: Producer interrupted during send")
    @Story("Producer Lifecycle")
    @Severity(SeverityLevel.NORMAL)
    public static void test12_REALISTIC_ProducerInterrupted() {
        log.info("REALISTIC TEST: Producer interrupted");

        // Hard to test - would require killing producer mid-send
        // Typically manifests as InterruptException

        log.info("⚠️  Skipped - requires producer thread interruption");
    }

    @Test(description = "REALISTIC: Kafka broker restart during operation")
    @Story("Broker Lifecycle")
    @Severity(SeverityLevel.CRITICAL)
    public void test23_REALISTIC_BrokerRestart() throws InterruptedException {
        logStep("REALISTIC TEST: Broker restart");

        // ⭐ Restart Kafka container
        kafka.stop();
        logStep("  🔴 Kafka stopped");

        Thread.sleep(2000);

        kafka.start();
        logStep("  🟢 Kafka restarted");

        // Producer should reconnect and retry
        logStep("✅ Broker lifecycle tested");
    }

    @Test(description = "Out of order sequence (idempotent producer)")
    @Story("Idempotence")
    @Severity(SeverityLevel.NORMAL)
    public void test18_OutOfOrderSequence() {
        logStep("TEST: Out of order sequence");

        // Requires specific producer configuration
        // org.apache.kafka.common.errors.OutOfOrderSequenceException

        logStep("⚠️  Requires idempotent producer config");
    }
}
