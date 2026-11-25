
package com.amazon.tests.regression.kafka.orders.publishing.failures;

import com.amazon.tests.config.kafka.KafkaTestContainerConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.kafka.KafkaContainer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;

public class ConfigurationFailures {

    private KafkaContainer kafkaContainer;
    private AdminClient adminClient;

    private String bootstrapServers;
    private String topicName;

    @BeforeClass
    public void setUp() {
        kafkaContainer = KafkaTestContainerConfig.start();

        bootstrapServers = kafkaContainer.getBootstrapServers();

        Properties adminProperties = new Properties();
        adminProperties.put(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers
        );

        adminClient = AdminClient.create(adminProperties);
    }

    @AfterMethod(alwaysRun = true)
    public void cleanUpTopic() throws Exception {
        if (topicName != null) {
            adminClient.deleteTopics(Set.of(topicName))
                    .all()
                    .get();

            topicName = null;
        }
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        if (adminClient != null) {
            adminClient.close(Duration.ofSeconds(5));
        }

        KafkaTestContainerConfig.stop();
    }

    @Test
    public void test01_InvalidCompressionTypeRejected() {

        Properties properties = producerProperties();

        properties.put(
                ProducerConfig.COMPRESSION_TYPE_CONFIG,
                "invalid-codec"
        );

        try {
            new KafkaProducer<String, String>(properties);

            fail(
                    "Expected ConfigException for invalid compression.type"
            );

        } catch (ConfigException expected) {
            // Expected: Kafka rejects an unsupported compression type.
        }
    }

    @Test
    public void test02_ValidCompressionTypePublishesSuccessfully()
            throws Exception {

        topicName = "test.configuration.compression."
                + UUID.randomUUID();

        adminClient.createTopics(Set.of(
                new NewTopic(topicName, 1, (short) 1)
        )).all().get();

        Properties properties = producerProperties();

        properties.put(
                ProducerConfig.COMPRESSION_TYPE_CONFIG,
                "gzip"
        );

        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(properties)) {

            var metadata = producer.send(
                    new ProducerRecord<>(
                            topicName,
                            "configuration-test",
                            "valid-compression"
                    )
            ).get();

            assertNotNull(metadata);
        }
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
}
