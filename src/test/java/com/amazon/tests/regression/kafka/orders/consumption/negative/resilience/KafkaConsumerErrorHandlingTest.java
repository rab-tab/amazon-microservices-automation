package com.amazon.tests.regression.kafka.orders.consumption.negative.resilience;

import com.amazon.tests.BaseTest;
import com.amazon.tests.config.kafka.KafkaConfig;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka Consumer Error Handling & Recovery
 *
 * ⚠️ DELIBERATELY TRIMMED from the original file. Three tests were
 * removed because they had zero real assertions — every code path
 * logged a result and passed regardless of actual behavior:
 *
 * - test31_ExternalApiTimeout_EventReprocessed: both the "result
 *   present" and "result absent" branches logged and returned
 *   successfully. Author's own comments acknowledged the correct
 *   behavior was unknown ("Option 1/2/3, implementation-dependent").
 *   Needs a design decision from payment-service's owner on intended
 *   timeout behavior before this can be a real, assertable test.
 * - test32_CircuitBreakerOpens_AfterConsecutiveFailures: same pattern
 *   — logged "circuit breaker OPEN" or "may not be implemented" with
 *   no assertion either way. Needs confirmation circuit breaking is
 *   actually implemented before writing a real assertion.
 * - test33_ExponentialBackoff_RetryDelaysIncrease: explicitly
 *   self-described as "observational" — told the human to go read
 *   logs manually rather than asserting on anything itself.
 *
 * These aren't lost forever — if/when the underlying behaviors are
 * confirmed implemented, they're worth rewriting with real assertions
 * rather than restoring as-is.
 *
 * ⚠️ test30 directly runs `docker stop/start payment-postgres` via
 * shell exec. This is environment-fragile (assumes a specific
 * container name, Docker socket access from the test runner, and a
 * Docker-based — not Kubernetes/EKS — deployment) and operationally
 * risky: if the test fails or the JVM crashes between STOP and the
 * @AfterMethod restart, the database stays down and silently breaks
 * every subsequent test in the run for an unrelated reason. Consider
 * migrating to a Toxiproxy-based approach (same pattern already used
 * for the Redis/Kafka chaos tests elsewhere in this suite) for safer,
 * contained failure injection instead of stopping a shared dependency
 * directly.
 */
@Slf4j
@Epic("Kafka Consumer Error Handling")
@Feature("Resilience & Recovery")
public class KafkaConsumerErrorHandlingTest extends BaseTest {

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String PAYMENT_RESULT_TOPIC = "payment.result";
    private static final String DB_CONTAINER = "payment-postgres";

    private KafkaProducer<String, String> kafkaProducer;
    private KafkaTestConsumer paymentResultMonitor;
    private String userId;

    @BeforeMethod
    public void setup() {
        logStep("Setting up error handling & recovery tests");

        PurchaseResult purchase = PurchaseWorkflow.start(executor, authStrategy)
                .registerCustomer()
                .execute();
        userId = purchase.getCustomer().getUser().getId();

        kafkaProducer = new KafkaProducer<>(KafkaConfig.getProducerProperties());
        paymentResultMonitor = new KafkaTestConsumer(PAYMENT_RESULT_TOPIC);

        logStep("✅ Setup complete — user: " + userId);
    }

    @AfterMethod
    public void cleanup() {
        if (kafkaProducer != null) kafkaProducer.close();
        if (paymentResultMonitor != null) paymentResultMonitor.close();

        // Safety net specific to test30 — ensures the DB container is back
        // up regardless of how the test exited (pass, fail, or exception).
        ensureDatabaseRunning();

        logStep("✅ Cleanup complete");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 30: TEMPORARY DATABASE OUTAGE
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 30)
    @Story("Database Outage Recovery")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Database goes down during event processing - verify retry and recovery")
    public void test30_DatabaseOutage_ConsumerRetriesAndRecovers() throws Exception {
        logStep("TEST 30: Temporary database outage");

        String orderId = UUID.randomUUID().toString();
        paymentResultMonitor.seekToEnd();

        logStep("  Publishing ORDER_CREATED event");
        String orderEvent = String.format(
                "{\"eventType\":\"ORDER_CREATED\",\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":99.99,\"timestamp\":%d}",
                orderId, userId, System.currentTimeMillis());

        kafkaProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, orderId, orderEvent)).get();
        kafkaProducer.flush();
        logStep("  ✓ ORDER_CREATED event published");

        logStep("  💥 SIMULATING DATABASE OUTAGE — stopping " + DB_CONTAINER);
        String stopOutput = executeCommand("docker stop " + DB_CONTAINER);
        logStep("    Docker stop output: " + stopOutput);
        logStep("  ✓ Database stopped");

        Thread.sleep(15000);

        logStep("  Checking payment result was NOT published during outage...");
        Optional<JsonNode> resultDuringOutage = paymentResultMonitor.waitForMessage(
                msg -> orderId.equals(msg.path("orderId").asText()), 5);

        if (resultDuringOutage.isEmpty()) {
            logStep("  ✓ No payment result during database outage (as expected)");
        } else {
            logStep("  ⚠️ Payment result published despite outage — consumer may have processed before DB stopped");
        }

        logStep("  🔧 RESTORING DATABASE — starting " + DB_CONTAINER);
        String startOutput = executeCommand("docker start " + DB_CONTAINER);
        logStep("    Docker start output: " + startOutput);
        logStep("  ✓ Database start command issued — waiting for readiness");
        Thread.sleep(15000);

        logStep("  Waiting for consumer to retry and succeed...");
        Optional<JsonNode> resultAfterRecovery = paymentResultMonitor.waitForMessage(
                msg -> orderId.equals(msg.path("orderId").asText()), 45);

        assertThat(resultAfterRecovery)
                .as("Payment result should be published after database recovery")
                .isPresent();

        String paymentStatus = resultAfterRecovery.get().path("status").asText();
        logStep("✅ DATABASE OUTAGE RECOVERY VALIDATED — event eventually processed: " + paymentStatus);
    }

    // ══════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════

    private String executeCommand(String command) {
        StringBuilder output = new StringBuilder();
        try {
            logStep("    Executing: " + command);
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = process.waitFor();
            logStep("    Exit code: " + exitCode);
            return output.toString().trim();
        } catch (Exception e) {
            log.error("Failed to execute command: {}", command, e);
            return "ERROR: " + e.getMessage();
        }
    }

    private void ensureDatabaseRunning() {
        logStep("Ensuring database is running after test...");
        try {
            String status = executeCommand("docker ps --filter name=" + DB_CONTAINER + " --format '{{.Status}}'");
            if (!status.contains("Up")) {
                logStep("  Database not running — starting...");
                executeCommand("docker start " + DB_CONTAINER);
                Thread.sleep(15000);
            }
            logStep("  ✓ Database is running");
        } catch (Exception e) {
            log.error("Failed to ensure database is running", e);
        }
    }
}