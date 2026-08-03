package com.amazon.tests.regression.kafka.orders.consumption.negative.dlq;


import com.amazon.tests.BaseTest;
import com.amazon.tests.config.kafka.KafkaConfig;
import io.qameta.allure.*;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.*;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@Epic("Amazon Microservices")
@Feature("Kafka - Dead Letter Queue")
public class KafkaDLQTests extends BaseTest {

    private static final String PAYMENT_REQUEST_TOPIC = "payment.request";
    private static final String PAYMENT_REQUEST_DLQ = "payment.request.DLQ";
    private static final String PAYMENT_SERVICE_URL = "http://localhost:8084";

    private KafkaProducer<String, String> kafkaProducer;
    private KafkaConsumer<String, String> dlqConsumer;

    @BeforeClass
    public void setup() {
        logStep("Setting up DLQ tests");

        kafkaProducer = new KafkaProducer<>(KafkaConfig.getProducerProperties());

        Properties consumerProps = KafkaConfig.getConsumerProperties();
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-test-consumer-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        dlqConsumer = new KafkaConsumer<>(consumerProps);
        dlqConsumer.subscribe(Collections.singletonList(PAYMENT_REQUEST_DLQ));

        logStep("✅ Kafka producer/consumer initialized for " + PAYMENT_REQUEST_DLQ);
    }

    @AfterClass
    public void tearDown() {
        logStep("Cleaning up Kafka clients");
        if (kafkaProducer != null) kafkaProducer.close();
        if (dlqConsumer != null) dlqConsumer.close();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DLQ TESTS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Story("DLQ - Automated")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Malformed event sent to DLQ topic with error metadata preserved")
    public void test90_MalformedEvent_SentToDLQ() throws Exception {
        logStep("TEST 90: Verify malformed event sent to DLQ");

        String eventKey = "dlq-test-" + UUID.randomUUID();
        String malformedJson = "{\"orderId\":\"abc\",\"amount\":INVALID_JSON}";

        logStep("  Publishing malformed event to " + PAYMENT_REQUEST_TOPIC);
        kafkaProducer.send(new ProducerRecord<>(PAYMENT_REQUEST_TOPIC, eventKey, malformedJson)).get();
        kafkaProducer.flush();
        logStep("  ✓ Published with key: " + eventKey);

        ConsumerRecord<String, String> dlqRecord = pollForRecordByKey(eventKey, 30);

        assertThat(dlqRecord).as("Malformed event should be in DLQ topic").isNotNull();
        logStep("  ✓ Target event found in DLQ");

        Map<String, String> headers = new HashMap<>();
        for (Header header : dlqRecord.headers()) {
            headers.put(header.key(), new String(header.value()));
        }

        assertThat(headers)
                .as("DLQ headers should contain error information")
                .containsKeys("kafka_dlt-exception-fqcn", "kafka_dlt-exception-message");

        logStep("  ✓ Exception type: " + headers.get("kafka_dlt-exception-fqcn"));
        logStep("  ✓ Exception message: " + headers.get("kafka_dlt-exception-message"));
        logStep("✅ DLQ functionality verified — malformed event routed, error metadata preserved");
    }

    @Test
    @Story("DLQ - Automated")
    @Severity(SeverityLevel.NORMAL)
    @Description("DLQ monitoring endpoint returns failed events")
    public void test91_DLQMonitoringEndpoint() {
        logStep("TEST 91: DLQ monitoring endpoint");

        Response resp = given()
                .baseUri(PAYMENT_SERVICE_URL)
                .when()
                .get("/actuator/dlq/payment.request?limit=10")
                .then()
                .extract()
                .response();

        logStep("  Response status: " + resp.statusCode());

        assertThat(resp.statusCode())
                .as("DLQ monitoring endpoint should be available")
                .isEqualTo(200);

        String dlqTopic = resp.jsonPath().getString("dlqTopic");
        Integer eventCount = resp.jsonPath().getInt("count");

        assertThat(dlqTopic).isEqualTo("payment.request.DLQ");
        assertThat(eventCount).as("Should have at least one event from prior DLQ tests").isGreaterThan(0);

        logStep("✅ DLQ monitoring endpoint working — topic: " + dlqTopic + ", count: " + eventCount);
    }

    @Test
    @Story("DLQ - Automated")
    @Severity(SeverityLevel.NORMAL)
    @Description("Multiple bad events all routed to DLQ")
    public void test92_MultipleBadEvents_AllInDLQ() throws Exception {
        logStep("TEST 92: Multiple bad events sent to DLQ");

        List<String> eventKeys = new ArrayList<>();
        logStep("  Publishing 3 malformed events...");
        for (int i = 0; i < 3; i++) {
            String eventKey = "multi-dlq-" + UUID.randomUUID();
            kafkaProducer.send(new ProducerRecord<>(PAYMENT_REQUEST_TOPIC, eventKey, "{INVALID_" + i + "}")).get();
            eventKeys.add(eventKey);
            logStep("    " + (i + 1) + ". Published: " + eventKey);
        }
        kafkaProducer.flush();

        logStep("  Polling DLQ for all 3 events...");
        Set<String> foundKeys = new HashSet<>();
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && foundKeys.size() < eventKeys.size()) {
            ConsumerRecords<String, String> records = dlqConsumer.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, String> rec : records) {
                if (eventKeys.contains(rec.key())) {
                    foundKeys.add(rec.key());
                    logStep("    Found in DLQ: " + rec.key());
                }
            }
        }

        assertThat(foundKeys).as("All 3 bad events should be in DLQ").hasSize(eventKeys.size());
        logStep("✅ All bad events successfully routed to DLQ");
    }

    // ══════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════

    private ConsumerRecord<String, String> pollForRecordByKey(String key, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = dlqConsumer.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, String> rec : records) {
                if (key.equals(rec.key())) {
                    return rec;
                }
            }
        }
        return null;
    }
}