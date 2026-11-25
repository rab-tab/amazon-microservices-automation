
package com.amazon.tests.regression.kafka.orders.consumption.retention;

import com.amazon.tests.BaseTest;
import com.amazon.tests.config.kafka.KafkaTestContainerConfig;
import io.qameta.allure.*;
import io.qameta.allure.testng.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.*;

import static org.testng.Assert.assertTrue;

@Slf4j
@Epic("Amazon Microservices")
@Feature("Kafka - Topic Retention")
@Tag("topic-retention")
@Tag("slow-tests")
public class TopicRetentionTest extends BaseTest {

    private static final long RETENTION_MS = 5_000L;
    private static final long SEGMENT_MS = 1_000L;

    private static final long ADMIN_TIMEOUT_SECONDS = 15L;
    private static final long CLEANUP_TIMEOUT_MS = 30_000L;

    private AdminClient adminClient;
    private String bootstrapServers;
    private String topicName;

    @BeforeClass(alwaysRun = true)
    public void setUpAdminClient() {

        // Start the single-broker Testcontainers Kafka instance.
        bootstrapServers =
                KafkaTestContainerConfig.getBootstrapServers();

        Properties properties = new Properties();
        properties.put(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers
        );

        adminClient = AdminClient.create(properties);

        logStep(
                "Started Kafka Testcontainer. Bootstrap servers: "
                        + bootstrapServers
        );
    }

    @AfterMethod(alwaysRun = true)
    public void deleteTestTopic() throws Exception {

        if (topicName != null && adminClient != null) {

            adminClient.deleteTopics(Set.of(topicName))
                    .all()
                    .get();

            logStep("Deleted test topic: " + topicName);

            topicName = null;
        }
    }

    @AfterClass(alwaysRun = true)
    public void closeAdminClient() {

        if (adminClient != null) {
            adminClient.close(Duration.ofSeconds(5));
        }

        // If Kafka is managed at suite level, move this stop operation
        // to the suite lifecycle instead.
        KafkaTestContainerConfig.stop();
    }

    @Test
    @Story("Retention configuration")
    @Severity(SeverityLevel.CRITICAL)
    @Description(
            "Verifies that a dedicated Kafka topic has the expected "
                    + "retention.ms and cleanup.policy configuration."
    )
    public void test01_TopicRetentionConfiguration() throws Exception {

        logStep("TEST 1: Verify topic retention configuration");

        createRetentionTopic();

        Config topicConfig = describeTopicConfig();

        assertTrue(
                topicConfig.get("retention.ms") != null
                        && RETENTION_MS == Long.parseLong(
                        topicConfig.get("retention.ms").value()
                ),
                "Topic retention.ms should be " + RETENTION_MS
        );

        assertTrue(
                topicConfig.get("cleanup.policy") != null
                        && "delete".equals(
                        topicConfig.get("cleanup.policy").value()
                ),
                "Topic cleanup.policy should be delete"
        );

        logStep(
                "Verified retention.ms=" + RETENTION_MS
                        + " and cleanup.policy=delete"
        );
    }

    @Test
    @Story("Expired record cleanup")
    @Severity(SeverityLevel.CRITICAL)
    @Description(
            "Verifies that Kafka advances the log start offset after "
                    + "the retention period expires and an eligible "
                    + "segment is deleted."
    )
    public void test02_ExpiredSegmentIsRemoved() throws Exception {

        logStep("TEST 2: Verify expired segment deletion");

        verifyRetentionCheckInterval();

        createRetentionTopic();

        String expiredKey = "expired-" + UUID.randomUUID();
        String markerKey = "marker-" + UUID.randomUUID();

        TopicPartition partition =
                new TopicPartition(topicName, 0);

        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(producerProperties())) {

            RecordMetadata expiredRecord = sendAndWait(
                    producer,
                    expiredKey,
                    "record-to-expire"
            );

            logStep(
                    "Published record-to-expire at offset "
                            + expiredRecord.offset()
            );

            // Allow the first segment to become eligible for rolling.
            Thread.sleep(SEGMENT_MS + 1_000L);

            // The next append triggers a segment roll.
            RecordMetadata markerRecord = sendAndWait(
                    producer,
                    markerKey,
                    "keep-this-record"
            );

            logStep(
                    "Published marker record at offset "
                            + markerRecord.offset()
            );
        }

        long beginningOffset = waitForRetentionCleanup(partition);

        assertTrue(
                beginningOffset > 0L,
                "Expected the log start offset to advance after retention "
                        + "cleanup. Current beginning offset: "
                        + beginningOffset
        );

        logStep(
                "Retention cleanup verified. Beginning offset: "
                        + beginningOffset
        );
    }

    private void createRetentionTopic() throws Exception {

        topicName = "test.retention." + UUID.randomUUID();

        Map<String, String> configs = new HashMap<>();

        configs.put("cleanup.policy", "delete");
        configs.put("retention.ms", String.valueOf(RETENTION_MS));
        configs.put("segment.ms", String.valueOf(SEGMENT_MS));
        configs.put("retention.bytes", "-1");

        NewTopic topic = new NewTopic(topicName, 1, (short) 1)
                .configs(configs);

        adminClient.createTopics(Set.of(topic))
                .all()
                .get();

        logStep("Created dedicated retention topic: " + topicName);
    }

    private Config describeTopicConfig() throws Exception {

        ConfigResource resource = new ConfigResource(
                ConfigResource.Type.TOPIC,
                topicName
        );

        return adminClient.describeConfigs(Set.of(resource))
                .all()
                .get()
                .get(resource);
    }

    private void verifyRetentionCheckInterval() throws Exception {

        Collection<Node> brokers = adminClient.describeCluster()
                .nodes()
                .get();

        if (brokers.isEmpty()) {
            throw new IllegalStateException(
                    "No Kafka brokers found"
            );
        }

        for (Node broker : brokers) {

            ConfigResource resource = new ConfigResource(
                    ConfigResource.Type.BROKER,
                    String.valueOf(broker.id())
            );

            Config config = adminClient.describeConfigs(Set.of(resource))
                    .all()
                    .get()
                    .get(resource);

            ConfigEntry entry =
                    config.get("log.retention.check.interval.ms");

            if (entry == null || entry.value() == null) {
                throw new SkipException(
                        "Cannot verify log.retention.check.interval.ms "
                                + "for broker " + broker.id()
                );
            }

            long interval = Long.parseLong(entry.value());

            if (interval > 5_000L) {
                throw new SkipException(
                        "Broker " + broker.id()
                                + " retention check interval is "
                                + interval
                                + " ms. Configure it to 5000 ms or less "
                                + "to run this test."
                );
            }

            logStep(
                    "Broker " + broker.id()
                            + " retention check interval: "
                            + interval + " ms"
            );
        }
    }

    private long waitForRetentionCleanup(
            TopicPartition partition
    ) throws Exception {

        long deadline = System.currentTimeMillis()
                + CLEANUP_TIMEOUT_MS;

        long beginningOffset = 0L;

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(consumerProperties())) {

            consumer.assign(Set.of(partition));

            while (System.currentTimeMillis() < deadline) {

                beginningOffset = consumer.beginningOffsets(
                        Set.of(partition)
                ).get(partition);

                if (beginningOffset > 0L) {
                    return beginningOffset;
                }

                Thread.sleep(1_000L);
            }
        }

        return beginningOffset;
    }

    private RecordMetadata sendAndWait(
            KafkaProducer<String, String> producer,
            String key,
            String value
    ) throws Exception {

        return producer.send(
                new ProducerRecord<>(topicName, key, value)
        ).get();
    }

    private Properties producerProperties() {

        Properties properties = new Properties();

        properties.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers
        );

        properties.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName()
        );

        properties.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName()
        );

        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.RETRIES_CONFIG, 3);

        return properties;
    }

    private Properties consumerProperties() {

        Properties properties = new Properties();

        properties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers
        );

        properties.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName()
        );

        properties.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName()
        );

        properties.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                "retention-test-" + UUID.randomUUID()
        );

        properties.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest"
        );

        properties.put(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false
        );

        return properties;
    }
}
