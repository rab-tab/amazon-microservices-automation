package com.amazon.tests.regression.kafka.orders.consumption.retention;


import com.amazon.tests.BaseTest;
import io.qameta.allure.*;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
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

@Feature("Kafka Topic Retention")
public class TopicRetentionTest extends BaseTest {

    private static final String BOOTSTRAP_SERVERS =
            System.getProperty("kafka.bootstrap.servers", "localhost:9092");

    private static final long RETENTION_MS = 5_000L;
    private static final long SEGMENT_MS = 1_000L;

    private AdminClient adminClient;
    private String topicName;

    @BeforeClass
    public void setUpAdminClient() {
        Properties properties = new Properties();
        properties.put(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                BOOTSTRAP_SERVERS
        );

        adminClient = AdminClient.create(properties);
    }

    @AfterMethod(alwaysRun = true)
    public void deleteTestTopic() throws Exception {
        if (topicName != null) {
            adminClient.deleteTopics(Set.of(topicName))
                    .all()
                    .get();

            topicName = null;
        }
    }

    @AfterClass(alwaysRun = true)
    public void closeAdminClient() {
        if (adminClient != null) {
            adminClient.close(Duration.ofSeconds(5));
        }
    }

    @Test
    @Story("Retention configuration")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that the dedicated topic has the configured retention policy.")
    public void test01_TopicRetentionConfiguration() throws Exception {
        createRetentionTopic();

        Map<ConfigResource, org.apache.kafka.clients.admin.Config> configs =
                adminClient.describeConfigs(Set.of(
                        new ConfigResource(
                                ConfigResource.Type.TOPIC,
                                topicName
                        )
                )).all().get();

        org.apache.kafka.clients.admin.Config topicConfig =
                configs.get(new ConfigResource(
                        ConfigResource.Type.TOPIC,
                        topicName
                ));

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
    }

    @Test
    @Story("Expired record cleanup")
    @Severity(SeverityLevel.CRITICAL)
    @Description(
            "Verifies that Kafka advances the log start offset after the " +
                    "retention period expires and the segment becomes eligible for deletion."
    )
    public void test02_ExpiredSegmentIsRemoved() throws Exception {
        verifyRetentionCheckInterval();

        createRetentionTopic();

        String expiredKey = "expired-" + UUID.randomUUID();
        String markerKey = "marker-" + UUID.randomUUID();

        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(producerProperties())) {

            sendAndWait(producer, topicName, expiredKey, "record-to-expire");

            // Allow the first segment to become eligible for rolling.
            Thread.sleep(SEGMENT_MS + 500);

            // This record causes a new segment to be created.
            sendAndWait(producer, topicName, markerKey, "keep-this-record");
        }

        TopicPartition partition = new TopicPartition(topicName, 0);

        long deadline = System.currentTimeMillis() + 30_000L;
        long beginningOffset = 0L;

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(consumerProperties())) {

            consumer.assign(Set.of(partition));

            while (System.currentTimeMillis() < deadline) {
                beginningOffset = consumer.beginningOffsets(Set.of(partition))
                        .get(partition);

                if (beginningOffset > 0L) {
                    break;
                }

                Thread.sleep(1_000);
            }
        }

        assertTrue(
                beginningOffset > 0L,
                "Expected the log start offset to advance after retention cleanup. " +
                        "Current beginning offset: " + beginningOffset
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
    }

    private void verifyRetentionCheckInterval() throws Exception {
        Collection<Node> brokers = adminClient.describeCluster()
                .nodes()
                .get();

        for (Node broker : brokers) {
            ConfigResource resource = new ConfigResource(
                    ConfigResource.Type.BROKER,
                    String.valueOf(broker.id())
            );

            org.apache.kafka.clients.admin.Config config =
                    adminClient.describeConfigs(Set.of(resource))
                            .all()
                            .get()
                            .get(resource);

            org.apache.kafka.clients.admin.ConfigEntry entry =
                    config.get("log.retention.check.interval.ms");

            if (entry == null || entry.value() == null) {
                throw new SkipException(
                        "Cannot verify log.retention.check.interval.ms for broker "
                                + broker.id()
                );
            }

            long interval = Long.parseLong(entry.value());

            if (interval > 5_000L) {
                throw new SkipException(
                        "Broker " + broker.id()
                                + " retention check interval is " + interval
                                + " ms. Set log.retention.check.interval.ms "
                                + "to 5000 or less to run this cleanup test."
                );
            }
        }
    }

    private Properties producerProperties() {
        Properties properties = new Properties();
        properties.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                BOOTSTRAP_SERVERS
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
                BOOTSTRAP_SERVERS
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

    private void sendAndWait(
            KafkaProducer<String, String> producer,
            String topic,
            String key,
            String value
    ) throws Exception {
        RecordMetadata metadata = producer.send(
                new ProducerRecord<>(topic, key, value)
        ).get();

        assertTrue(metadata.offset() >= 0);
    }
}
