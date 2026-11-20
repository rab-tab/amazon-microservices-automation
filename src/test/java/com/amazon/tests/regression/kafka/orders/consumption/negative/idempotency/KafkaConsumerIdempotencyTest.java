package com.amazon.tests.regression.kafka.orders.consumption.negative.idempotency;

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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@Epic("Kafka Consumer Idempotency")
@Feature("Application-Level Event Deduplication")
public class KafkaConsumerIdempotencyTest extends BaseTest {

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String PAYMENT_RESULT_TOPIC = "payment.result";

    private static final int PAYMENT_RESULT_TIMEOUT_SECONDS = 20;
    private static final long DB_POLL_TIMEOUT_NANOS = 20_000_000_000L;
    private static final long DB_POLL_INTERVAL_MILLIS = 250L;

    private KafkaProducer<String, String> kafkaProducer;
    private KafkaTestConsumer paymentResultMonitor;

    private String userId;

    @BeforeMethod
    public void setup() {

        logStep("Setting up Kafka consumer idempotency test");

        PurchaseResult purchase = PurchaseWorkflow.start(executor, authStrategy)
                .registerCustomer()
                .execute();

        userId = purchase.getCustomer().getUser().getId();

        kafkaProducer = new KafkaProducer<>(
                KafkaConfig.getProducerProperties()
        );

        paymentResultMonitor = new KafkaTestConsumer(
                PAYMENT_RESULT_TOPIC
        );

        paymentResultMonitor.seekToEnd();

        logStep("Setup complete — user: " + userId);
    }

    @AfterMethod
    public void cleanup() {

        if (kafkaProducer != null) {
            kafkaProducer.close();
        }

        if (paymentResultMonitor != null) {
            paymentResultMonitor.close();
        }

        logStep("Kafka clients closed");
    }

    @Test(priority = 1)
    @Story("Duplicate Event Processing")
    @Severity(SeverityLevel.CRITICAL)
    @Description(
            "Publish the same ORDER_CREATED event twice and verify " +
                    "that only one payment row exists in payments_db"
    )
    public void test01_DuplicateEventProcessing_OnlyOnePaymentCreated()
            throws Exception {

        logStep("TEST: Duplicate ORDER_CREATED event processing");

        String orderId = UUID.randomUUID().toString();

        String orderCreatedEvent = buildOrderCreatedEvent(orderId);

        logStep("Publishing ORDER_CREATED event for order: " + orderId);

        kafkaProducer.send(
                new ProducerRecord<>(
                        ORDER_EVENTS_TOPIC,
                        orderId,
                        orderCreatedEvent
                )
        ).get();

        logStep("First ORDER_CREATED event published");

        kafkaProducer.send(
                new ProducerRecord<>(
                        ORDER_EVENTS_TOPIC,
                        orderId,
                        orderCreatedEvent
                )
        ).get();

        kafkaProducer.flush();

        logStep("Second ORDER_CREATED event published (duplicate)");

        logStep("Waiting for both payment result events");

        List<JsonNode> paymentResults =
                paymentResultMonitor.waitForMessages(
                        message -> orderId.equals(
                                message.path("orderId").asText()
                        ),
                        PAYMENT_RESULT_TIMEOUT_SECONDS,
                        2
                );

        assertThat(paymentResults)
                .as("Both original and duplicate events should be handled")
                .hasSize(2);

        logStep("Both payment result events received");

        logStep("Checking actual payment row count in payments_db");

        int paymentCount = waitForPaymentCount(orderId);

        assertThat(paymentCount)
                .as("Duplicate ORDER_CREATED events must not create duplicate payment rows")
                .isEqualTo(1);

        logStep(
                "PASS: Two ORDER_CREATED events handled, " +
                        "but only one payment row exists for orderId=" + orderId
        );
    }

    private String buildOrderCreatedEvent(String orderId) {

        return String.format(
                "{\"eventType\":\"ORDER_CREATED\"," +
                        "\"orderId\":\"%s\"," +
                        "\"userId\":\"%s\"," +
                        "\"amount\":99.99," +
                        "\"timestamp\":%d}",
                orderId,
                userId,
                System.currentTimeMillis()
        );
    }

    private int waitForPaymentCount(String orderId)
            throws InterruptedException {

        long deadline = System.nanoTime() + DB_POLL_TIMEOUT_NANOS;

        int paymentCount = 0;

        while (System.nanoTime() < deadline) {

            paymentCount = countPaymentsForOrderViaDB(orderId);

            if (paymentCount == 1) {
                return paymentCount;
            }

            if (paymentCount > 1) {
                return paymentCount;
            }

            Thread.sleep(DB_POLL_INTERVAL_MILLIS);
        }

        return paymentCount;
    }

    private int countPaymentsForOrderViaDB(String orderId) {

        String dbUrl = context.getConfig().databaseHost();
        String dbUser = context.getConfig().databaseUsername();
        String dbPassword = context.getConfig().databasePassword();

        if (dbUrl == null || !dbUrl.contains("/payments_db")) {
            throw new IllegalStateException(
                    "Payment database JDBC URL must point to payments_db. " +
                            "Current configured URL: " + dbUrl
            );
        }

        String sql =
                "SELECT COUNT(*) FROM payments WHERE order_id = ?";

        try {
            Class.forName("org.postgresql.Driver");

            try (Connection connection = DriverManager.getConnection(
                    dbUrl,
                    dbUser,
                    dbPassword
            );
                 PreparedStatement statement =
                         connection.prepareStatement(sql)) {

                statement.setObject(1, UUID.fromString(orderId));

                try (ResultSet resultSet = statement.executeQuery()) {

                    if (resultSet.next()) {

                        int count = resultSet.getInt(1);

                        logStep(
                                "Payment DB row count for orderId=" +
                                        orderId + " is " + count
                        );

                        return count;
                    }
                }
            }

        } catch (Exception e) {

            log.error(
                    "Failed to query payments_db for orderId={}",
                    orderId,
                    e
            );

            throw new IllegalStateException(
                    "Unable to verify payment count in payments_db",
                    e
            );
        }

        throw new IllegalStateException(
                "Payment count query returned no result"
        );
    }
}
