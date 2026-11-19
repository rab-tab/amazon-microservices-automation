
package com.amazon.tests.regression.kafka.orders.publishing.reliability;

import com.amazon.tests.utils.kafka.KafkaIdempotenceInfrastructure;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

public class ProducerIdempotenceTest {

    private static final String TOPIC =
            "producer.idempotence.test";

    private static final int MESSAGE_COUNT = 20;

    private static final Duration POLL_TIMEOUT =
            Duration.ofMillis(500);

    private static final Duration VERIFICATION_TIMEOUT =
            Duration.ofSeconds(15);

    private static final Duration QUIET_PERIOD =
            Duration.ofSeconds(3);

    private String producerBootstrapServers() {
        return KafkaIdempotenceInfrastructure
                .getProducerBootstrapServers();
    }

    private String consumerBootstrapServers() {
        return KafkaIdempotenceInfrastructure
                .getConsumerBootstrapServers();
    }

    // ---------------------------------------------------------
    // Producer configuration
    // ---------------------------------------------------------

    private Properties producerProperties() {

        Properties properties = new Properties();

        properties.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                producerBootstrapServers()
        );

        properties.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName()
        );

        properties.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName()
        );

        properties.put(
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                true
        );

        properties.put(
                ProducerConfig.ACKS_CONFIG,
                "all"
        );

        properties.put(
                ProducerConfig.RETRIES_CONFIG,
                Integer.MAX_VALUE
        );

        properties.put(
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,
                5
        );

        properties.put(
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                30000
        );

        properties.put(
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                120000
        );

        return properties;
    }

    // ---------------------------------------------------------
    // Consumer configuration
    // ---------------------------------------------------------

    private Properties consumerProperties() {

        Properties properties = new Properties();

        properties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                consumerBootstrapServers()
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
                "idempotence-verification-" + UUID.randomUUID()
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

    // ---------------------------------------------------------
    // Test 01: Verify producer idempotence configuration
    // ---------------------------------------------------------

    @Test
    public void test01_ProducerIdempotenceConfigurationEnabled() {

        Properties properties = producerProperties();

        assertThat(
                properties.getProperty(
                        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG
                )
        ).isEqualTo("true");

        assertThat(
                properties.getProperty(
                        ProducerConfig.ACKS_CONFIG
                )
        ).isEqualTo("all");

        assertThat(
                properties.getProperty(
                        ProducerConfig.RETRIES_CONFIG
                )
        ).isEqualTo(String.valueOf(Integer.MAX_VALUE));

        assertThat(
                properties.getProperty(
                        ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION
                )
        ).isEqualTo("5");
    }

    // ---------------------------------------------------------
    // Test 02: Sequential message delivery
    // ---------------------------------------------------------

    @Test
    public void test02_SequentialMessagesPersistExactlyOnce()
            throws Exception {

        String testRunId = UUID.randomUUID().toString();

        Set<String> expectedMessages = new HashSet<>();

        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(producerProperties())) {

            for (int i = 1; i <= MESSAGE_COUNT; i++) {

                String key = testRunId + "-key-" + i;
                String value = testRunId + "-message-" + i;

                ProducerRecord<String, String> record =
                        new ProducerRecord<>(
                                TOPIC,
                                key,
                                value
                        );

                RecordMetadata metadata = producer.send(record).get();

                assertThat(metadata.topic())
                        .isEqualTo(TOPIC);

                expectedMessages.add(value);
            }

            producer.flush();
        }

        List<String> actualMessages =
                collectMessages(testRunId, MESSAGE_COUNT);

        assertExactlyOnce(
                expectedMessages,
                actualMessages
        );
    }

    // ---------------------------------------------------------
    // Test 03: Concurrent message delivery
    // ---------------------------------------------------------

    @Test
    public void test03_ConcurrentMessagesPersistExactlyOnce()
            throws Exception {

        String testRunId = UUID.randomUUID().toString();

        Set<String> expectedMessages = new HashSet<>();

        ExecutorService executor =
                Executors.newFixedThreadPool(5);

        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(producerProperties())) {

            List<Future<RecordMetadata>> futures =
                    IntStream.rangeClosed(1, MESSAGE_COUNT)
                            .mapToObj(i -> {

                                String key =
                                        testRunId + "-key-" + i;

                                String value =
                                        testRunId + "-message-" + i;

                                expectedMessages.add(value);

                                ProducerRecord<String, String> record =
                                        new ProducerRecord<>(
                                                TOPIC,
                                                key,
                                                value
                                        );

                                return executor.submit(
                                        () -> producer.send(record).get()
                                );
                            })
                            .toList();

            for (Future<RecordMetadata> future : futures) {

                RecordMetadata metadata =
                        future.get();

                assertThat(metadata.topic())
                        .isEqualTo(TOPIC);
            }

            producer.flush();

        } finally {

            executor.shutdownNow();

            try {
                executor.close();
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Producer executor did not terminate",
                        e
                );
            }
        }

        List<String> actualMessages =
                collectMessages(testRunId, MESSAGE_COUNT);

        assertExactlyOnce(
                expectedMessages,
                actualMessages
        );
    }

    // ---------------------------------------------------------
    // Read messages directly from Kafka
    // ---------------------------------------------------------

    private List<String> collectMessages(
            String testRunId,
            int expectedCount
    ) {

        List<String> messages = new ArrayList<>();

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(consumerProperties())) {

            TopicPartition partition =
                    new TopicPartition(TOPIC, 0);

            consumer.assign(List.of(partition));

            consumer.seekToBeginning(List.of(partition));

            long deadline =
                    System.nanoTime()
                            + VERIFICATION_TIMEOUT.toNanos();

            long lastMessageTime = System.nanoTime();

            boolean expectedCountReached = false;

            while (System.nanoTime() < deadline) {

                ConsumerRecords<String, String> records =
                        consumer.poll(POLL_TIMEOUT);

                if (!records.isEmpty()) {

                    for (ConsumerRecord<String, String> record : records) {

                        String value = record.value();

                        if (value != null &&
                                value.startsWith(testRunId + "-message-")) {

                            messages.add(value);
                        }
                    }

                    lastMessageTime = System.nanoTime();
                }

                if (messages.size() >= expectedCount) {
                    expectedCountReached = true;
                }

                if (expectedCountReached &&
                        System.nanoTime() - lastMessageTime
                                >= QUIET_PERIOD.toNanos()) {
                    break;
                }
            }
        }

        assertThat(messages)
                .as("Expected messages should arrive before verification timeout")
                .hasSizeGreaterThanOrEqualTo(expectedCount);

        return messages;
    }

    // ---------------------------------------------------------
    // Exactly-once assertion for this test's observed records
    // ---------------------------------------------------------

    private void assertExactlyOnce(
            Set<String> expectedMessages,
            List<String> actualMessages
    ) {

        assertThat(actualMessages)
                .as("No duplicate records should be observed")
                .doesNotHaveDuplicates();

        assertThat(new HashSet<>(actualMessages))
                .as("All expected messages should be present")
                .isEqualTo(expectedMessages);

        assertThat(actualMessages)
                .as("No unexpected messages should be present")
                .hasSize(expectedMessages.size());
    }
}
