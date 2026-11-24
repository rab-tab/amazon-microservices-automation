package com.amazon.tests.regression.kafka.orders.consumption.compaction;


import com.amazon.tests.BaseTest;
import io.qameta.allure.*;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
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

import static org.testng.Assert.*;

@Feature("Kafka Log Compaction")
public class LogCompactionTest extends BaseTest {

    private static final String BOOTSTRAP_SERVERS =
            System.getProperty("kafka.bootstrap.servers", "localhost:9092");

    private static final long SEGMENT_MS = 1_000L;
    private static final long COMPACTION_TIMEOUT_MS = 60_000L;

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
    @Story("Latest value per key")
    @Severity(SeverityLevel.CRITICAL)
    @Description(
            "Publishes multiple values for the same key and verifies that " +
                    "compaction eventually removes the older value while retaining " +
                    "the latest value and records belonging to other keys."
    )
    public void test01_CompactionRetainsLatestValuePerKey() throws Exception {
        verifyLogCleanerEnabled();
        createCompactedTopic();

        String sharedKey = "customer-100";
        String independentKeyOne = "customer-200";
        String independentKeyTwo = "customer-300";

        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(producerProperties())) {

            sendAndWait(producer, sharedKey, "old-value");
            sendAndWait(producer, sharedKey, "latest-value");

            sendAndWait(producer, independentKeyOne, "customer-200-value");
            sendAndWait(producer, independentKeyTwo, "customer-300-value");

            // The next record causes the previous segment to roll.
            Thread.sleep(SEGMENT_MS + 500);

            sendAndWait(
                    producer,
                    "segment-marker",
                    "marker-" + UUID.randomUUID()
            );
        }

        long deadline = System.currentTimeMillis()
                + COMPACTION_TIMEOUT_MS;

        List<ConsumerRecord<String, String>> records = new ArrayList<>();

        while (System.currentTimeMillis() < deadline) {
            records = readAllRecords();

            boolean oldValueRemoved = records.stream().noneMatch(record ->
                    sharedKey.equals(record.key())
                            && "old-value".equals(record.value())
            );

            boolean latestValuePresent = records.stream().anyMatch(record ->
                    sharedKey.equals(record.key())
                            && "latest-value".equals(record.value())
            );

            boolean independentRecordsPresent =
                    containsValue(records, independentKeyOne, "customer-200-value")
                            && containsValue(
                            records,
                            independentKeyTwo,
                            "customer-300-value"
                    );

            if (oldValueRemoved
                    && latestValuePresent
                    && independentRecordsPresent) {
                break;
            }

            Thread.sleep(2_000);
        }

        assertFalse(
                records.stream().anyMatch(record ->
                        sharedKey.equals(record.key())
                                && "old-value".equals(record.value())
                ),
                "The older value should eventually be removed by compaction"
        );

        assertTrue(
                containsValue(records, sharedKey, "latest-value"),
                "The latest value for the shared key should remain"
        );

        assertTrue(
                containsValue(
                        records,
                        independentKeyOne,
                        "customer-200-value"
                ),
                "Compaction should preserve the first independent key"
        );

        assertTrue(
                containsValue(
                        records,
                        independentKeyTwo,
                        "customer-300-value"
                ),
                "Compaction should preserve the second independent key"
        );

        long sharedKeyRecordCount = records.stream()
                .filter(record -> sharedKey.equals(record.key()))
                .count();

        assertEquals(
                sharedKeyRecordCount,
                1L,
                "Only one record for the shared key should remain after compaction"
        );
    }

    @Test
    @Story("Tombstone records")
    @Severity(SeverityLevel.NORMAL)
    @Description(
            "Verifies that a null-valued record for a key is written as a " +
                    "Kafka tombstone. The tombstone marks the key for deletion during compaction."
    )
    public void test02_TombstoneIsWrittenAsNullValue() throws Exception {
        verifyLogCleanerEnabled();
        createCompactedTopic();

        String deletedKey = "customer-to-delete";

        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(producerProperties())) {

            sendAndWait(producer, deletedKey, "existing-value");

            // Publish a tombstone for the same key.
            sendAndWait(producer, deletedKey, null);

            Thread.sleep(SEGMENT_MS + 500);

            sendAndWait(
                    producer,
                    "segment-marker",
                    "marker-" + UUID.randomUUID()
            );
        }

        List<ConsumerRecord<String, String>> records = readAllRecords();

        boolean tombstoneFound = records.stream().anyMatch(record ->
                deletedKey.equals(record.key())
                        && record.value() == null
        );

        assertTrue(
                tombstoneFound,
                "Expected a null-valued tombstone record for " + deletedKey
        );
    }

    private void createCompactedTopic() throws Exception {
        topicName = "test.compaction." + UUID.randomUUID();

        Map<String, String> configs = new HashMap<>();
        configs.put("cleanup.policy", "compact");
        configs.put("segment.ms", String.valueOf(SEGMENT_MS));
        configs.put("min.cleanable.dirty.ratio", "0.01");
        configs.put("min.compaction.lag.ms", "0");
        configs.put("max.compaction.lag.ms", "1000");
        configs.put("delete.retention.ms", "10000");

        NewTopic topic = new NewTopic(topicName, 1, (short) 1)
                .configs(configs);

        adminClient.createTopics(Set.of(topic))
                .all()
                .get();
    }

    private void verifyLogCleanerEnabled() throws Exception {
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
                    config.get("log.cleaner.enable");

            if (entry == null || entry.value() == null) {
                throw new SkipException(
                        "Cannot verify log.cleaner.enable for broker "
                                + broker.id()
                );
            }

            if (!Boolean.parseBoolean(entry.value())) {
                throw new SkipException(
                        "Log cleaner is disabled on broker " + broker.id()
                );
            }
        }
    }

    private List<ConsumerRecord<String, String>> readAllRecords()
            throws Exception {

        List<ConsumerRecord<String, String>> result = new ArrayList<>();

        TopicPartition partition = new TopicPartition(topicName, 0);

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(consumerProperties())) {

            consumer.assign(Set.of(partition));

            long beginningOffset = consumer.beginningOffsets(Set.of(partition))
                    .get(partition);

            long endOffset = consumer.endOffsets(Set.of(partition))
                    .get(partition);

            consumer.seek(partition, beginningOffset);

            long deadline = System.currentTimeMillis() + 10_000L;

            while (consumer.position(partition) < endOffset
                    && System.currentTimeMillis() < deadline) {

                ConsumerRecords<String, String> polled =
                        consumer.poll(Duration.ofMillis(500));

                for (ConsumerRecord<String, String> record : polled) {
                    result.add(record);
                }
            }
        }

        return result;
    }

    private boolean containsValue(
            List<ConsumerRecord<String, String>> records,
            String key,
            String expectedValue
    ) {
        return records.stream().anyMatch(record ->
                key.equals(record.key())
                        && expectedValue.equals(record.value())
        );
    }

    private void sendAndWait(
            KafkaProducer<String, String> producer,
            String key,
            String value
    ) throws Exception {
        producer.send(
                new ProducerRecord<>(topicName, key, value)
        ).get();
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
                "compaction-test-" + UUID.randomUUID()
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
