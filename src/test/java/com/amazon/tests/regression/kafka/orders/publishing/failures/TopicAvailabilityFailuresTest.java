package com.amazon.tests.regression.kafka.orders.publishing.failures;


import com.amazon.tests.BaseTest;
import io.qameta.allure.*;
import io.qameta.allure.testng.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Slf4j
@Epic("Amazon Microservices")
@Feature("Kafka - Topic Availability Failures")
@Tag("topic-availability")
@Tag("slow-tests")
public class TopicAvailabilityFailuresTest extends BaseTest {

    private static final String NON_EXISTENT_TOPIC_PREFIX =
            "test.topic.missing.";

    private String bootstrapServers() {
        return System.getProperty(
                "kafka.bootstrap.servers",
                "localhost:9092"
        );
    }

    private Properties producerProperties(
            String bootstrapServers,
            long maxBlockMs
    ) {
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
        properties.put(ProducerConfig.RETRIES_CONFIG, 0);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);

        return properties;
    }

    private boolean topicExists(String topic) throws Exception {

        Properties properties = new Properties();

        properties.put(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers()
        );

        try (AdminClient adminClient = AdminClient.create(properties)) {

            Set<String> topics = adminClient.listTopics()
                    .names()
                    .get();

            return topics.contains(topic);
        }
    }

    private boolean hasCause(
            Throwable failure,
            Class<? extends Throwable> type
    ) {
        Throwable current = failure;

        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    // ---------------------------------------------------------
    // TEST 01: Publishing to a non-existent topic
    // ---------------------------------------------------------

    @Test
    @Story("Unknown Topic")
    @Severity(SeverityLevel.CRITICAL)
    @Description(
            "Publishing to a topic that does not exist fails when broker-side " +
                    "automatic topic creation is disabled."
    )
    public void test01_PublishToNonExistentTopic() throws Exception {

        String topic = NON_EXISTENT_TOPIC_PREFIX + UUID.randomUUID();

        logStep("TEST 1: Publishing to non-existent topic " + topic);

        /*
         * Prerequisite:
         * auto.create.topics.enable=false on every broker.
         *
         * Otherwise Kafka may create the topic during metadata lookup.
         */

        assertThat(topicExists(topic))
                .as("Unique test topic must not exist before publishing")
                .isFalse();

        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(
                             producerProperties(bootstrapServers(), 5_000)
                     )) {

            assertThatThrownBy(() ->
                    producer.send(
                                    new ProducerRecord<>(
                                            topic,
                                            "test-key",
                                            "test-value"
                                    )
                            )
                            .get()
            )
                    .as("Publishing to an unknown topic should fail with a metadata timeout")
                    .satisfies(failure ->
                            assertThat(
                                    hasCause(failure, TimeoutException.class)
                            )
                                    .as(
                                            "Expected Kafka metadata TimeoutException; actual: %s",
                                            failure
                                    )
                                    .isTrue()
                    );

        } finally {

            /*
             * Do not automatically delete the topic here.
             * Deletion could race with another test or another actor.
             */

            assertThat(topicExists(topic))
                    .as(
                            "The unknown topic must not have been auto-created. " +
                                    "Check broker auto.create.topics.enable"
                    )
                    .isFalse();
        }
    }

    // ---------------------------------------------------------
    // TEST 02: Broker unavailable during metadata lookup
    // ---------------------------------------------------------

    @Test
    @Story("Broker Unavailable During Metadata Lookup")
    @Severity(SeverityLevel.CRITICAL)
    @Description(
            "Producer cannot obtain topic metadata when the configured Kafka " +
                    "bootstrap endpoint is unavailable."
    )
    public void test02_BrokerUnavailableDuringMetadataLookup() {

        String unavailableBootstrapServers =
                System.getProperty(
                        "kafka.unavailable.bootstrap.servers"
                );

        if (unavailableBootstrapServers == null
                || unavailableBootstrapServers.trim().isEmpty()) {

            throw new SkipException(
                    "Set -Dkafka.unavailable.bootstrap.servers " +
                            "to a deliberately unavailable test Kafka endpoint."
            );
        }

        logStep(
                "TEST 2: Kafka metadata endpoint unavailable: " +
                        unavailableBootstrapServers
        );

        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(
                             producerProperties(
                                     unavailableBootstrapServers,
                                     3_000
                             )
                     )) {

            assertThatThrownBy(() ->
                    producer.send(
                                    new ProducerRecord<>(
                                            "test.metadata.unavailable",
                                            "test-key",
                                            "test-value"
                                    )
                            )
                            .get()
            )
                    .as(
                            "Publishing must fail when no Kafka broker " +
                                    "can provide metadata"
                    )
                    .isInstanceOf(Exception.class);

        } catch (KafkaException exception) {

            /*
             * Producer construction or send may fail synchronously
             * when the endpoint is unavailable.
             */

            logStep(
                    "Producer failed while contacting unavailable Kafka endpoint: " +
                            exception.getClass().getSimpleName()
            );
        }
    }
}
