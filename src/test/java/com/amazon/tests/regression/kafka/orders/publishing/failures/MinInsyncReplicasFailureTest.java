package com.amazon.tests.regression.kafka.orders.publishing.failures;

import com.amazon.tests.utils.kafka.BrokerController;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.NotEnoughReplicasAfterAppendException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class MinInsyncReplicasFailureTest {

    private static final String BOOTSTRAP_SERVERS =
            System.getProperty(
                    "kafka.bootstrap.servers",
                    "localhost:9092,localhost:9093,localhost:9094"
            );

    private static final short REPLICATION_FACTOR = 3;
    private static final int MIN_INSYNC_REPLICAS = 3;

    @Test
    public void test01_MinInsyncReplicasNotMet_ProducerWriteFails()
            throws Exception {

        String topic = "isr-failure-" + UUID.randomUUID();

        Properties adminProperties = new Properties();
        adminProperties.put(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                BOOTSTRAP_SERVERS
        );

        int stoppedBrokerId = -1;
        boolean topicCreated = false;

        try (AdminClient admin = AdminClient.create(adminProperties)) {

            // 1. Create a dedicated topic with RF=3 and min ISR=3.
            NewTopic newTopic = new NewTopic(
                    topic,
                    1,
                    REPLICATION_FACTOR
            ).configs(Map.of(
                    "min.insync.replicas",
                    String.valueOf(MIN_INSYNC_REPLICAS)
            ));

            getWithTimeout(
                    admin.createTopics(List.of(newTopic)).all(),
                    30_000
            );

            topicCreated = true;

            // 2. Wait until all three replicas are in the ISR.
            TopicPartitionInfo initialPartition =
                    waitForIsrSize(admin, topic, 3, 60);

            assertThat(initialPartition.isr()).hasSize(3);

            int leaderId = initialPartition.leader().id();

            // 3. Choose a follower, never the leader.
            int followerId = initialPartition.isr().stream()
                    .map(Node::id)
                    .filter(id -> id != leaderId)
                    .findFirst()
                    .orElseThrow(() ->
                            new AssertionError("No follower found in ISR"));

            // 4. Stop the follower using the infrastructure controller.
            BrokerController.stopBroker(followerId);
            stoppedBrokerId = followerId;

            // 5. Wait until ISR drops to two.
            TopicPartitionInfo reducedPartition =
                    waitForIsrSize(admin, topic, 2, 60);

            assertThat(reducedPartition.leader().id())
                    .as("Partition leader should remain available")
                    .isEqualTo(leaderId);

            assertThat(reducedPartition.isr())
                    .extracting(Node::id)
                    .doesNotContain(followerId);

            // 6-7. Publish with acks=all and verify Kafka rejects it.
            assertProducerWriteRejected(topic);

            // 8. Restart follower.
            BrokerController.startBroker(followerId);
            stoppedBrokerId = -1;

            // 9. Wait until all three replicas rejoin ISR.
            TopicPartitionInfo recoveredPartition =
                    waitForIsrSize(admin, topic, 3, 120);

            assertThat(recoveredPartition.isr()).hasSize(3);

            // 10. Verify publishing succeeds after recovery.
            assertProducerWriteSucceeds(topic);

        } finally {

            // Attempt recovery even if an assertion or publish fails.
            if (stoppedBrokerId != -1) {
                BrokerController.startBroker(stoppedBrokerId);
            }

            if (topicCreated) {
                try (AdminClient cleanupAdmin =
                             AdminClient.create(adminProperties)) {

                    getWithTimeout(
                            cleanupAdmin.deleteTopics(List.of(topic)).all(),
                            30_000
                    );
                }
            }
        }
    }

    private void assertProducerWriteRejected(String topic)
            throws Exception {

        try (KafkaProducer<String, String> producer =
                     createProducer()) {

            ProducerRecord<String, String> record =
                    new ProducerRecord<>(
                            topic,
                            "order-123",
                            "{\"eventType\":\"ORDER_CREATED\"}"
                    );

            try {
                RecordMetadata metadata = getWithTimeout(
                        producer.send(record),
                        15_000
                );

                fail("Expected Kafka to reject the write because ISR < min.insync.replicas");

            } catch (ExecutionException exception) {

                Throwable cause = exception;

                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }

                assertThat(cause)
                        .as("Kafka should reject writes below minimum ISR")
                        .isInstanceOfAny(
                                NotEnoughReplicasException.class,
                                NotEnoughReplicasAfterAppendException.class
                        );
            }
        }
    }

    private void assertProducerWriteSucceeds(String topic)
            throws Exception {

        try (KafkaProducer<String, String> producer =
                     createProducer()) {

            ProducerRecord<String, String> record =
                    new ProducerRecord<>(
                            topic,
                            "order-456",
                            "{\"eventType\":\"ORDER_CREATED\"}"
                    );

            RecordMetadata metadata = getWithTimeout(
                    producer.send(record),
                    15_000
            );
        }
    }

    private KafkaProducer<String, String> createProducer() {

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
        properties.put(ProducerConfig.RETRIES_CONFIG, 0);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);

        return new KafkaProducer<>(properties);
    }

    private TopicPartitionInfo waitForIsrSize(
            AdminClient admin,
            String topic,
            int expectedIsrSize,
            int timeoutSeconds
    ) throws Exception {

        long deadline = System.nanoTime()
                + Duration.ofSeconds(timeoutSeconds).toNanos();

        while (System.nanoTime() < deadline) {

            TopicDescription description = getWithTimeout(
                    admin.describeTopics(List.of(topic)).allTopicNames(),
                    5_000
            ).get(topic);

            TopicPartitionInfo partition =
                    description.partitions().get(0);

            if (partition.isr().size() == expectedIsrSize) {
                return partition;
            }

            Thread.sleep(1000);
        }

        throw new AssertionError(
                "Timed out waiting for ISR size " + expectedIsrSize
                        + " on topic " + topic
        );
    }


    private <T> T getWithTimeout(
            org.apache.kafka.common.KafkaFuture<T> future,
            long timeoutMillis
    ) throws Exception {

        long deadline = System.nanoTime()
                + java.time.Duration.ofMillis(timeoutMillis).toNanos();

        while (!future.isDone()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Kafka operation timed out");
            }
            Thread.sleep(50);
        }

        return future.get();
    }

    private <T> T getWithTimeout(
            java.util.concurrent.Future<T> future,
            long timeoutMillis
    ) throws Exception {

        long deadline = System.nanoTime()
                + java.time.Duration.ofMillis(timeoutMillis).toNanos();

        while (!future.isDone()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Producer operation timed out");
            }
            Thread.sleep(50);
        }

        return future.get();
    }
}

