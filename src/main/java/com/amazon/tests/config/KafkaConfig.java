package com.amazon.tests.config;

// src/main/java/com/amazon/tests/config/KafkaConfig.java


import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.UUID;

/**
 * Kafka configuration for test automation.
 * Provides properties for both Kafka producers and consumers.
 */
@Slf4j
public class KafkaConfig {

    private static final String KAFKA_BOOTSTRAP_SERVERS =
            System.getProperty("kafka.bootstrap.servers", "localhost:9092");

    /**
     * Get Kafka Consumer properties for listening to payment.result topic
     *
     * @return Properties configured for Kafka consumer
     */
    public static Properties getConsumerProperties() {
        Properties props = new Properties();

        // Basic configs
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());

        // Deserializers
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        // Consumer behavior
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000");
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, "300000");

        log.info("✅ Kafka Consumer configured with bootstrap servers: {}", KAFKA_BOOTSTRAP_SERVERS);
        return props;
    }

    /**
     * Get Kafka Producer properties for publishing test messages
     *
     * @return Properties configured for Kafka producer
     */
    public static Properties getProducerProperties() {
        Properties props = new Properties();

        // Basic configs
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVERS);

        // Serializers
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // Producer behavior
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        log.info("✅ Kafka Producer configured with bootstrap servers: {}", KAFKA_BOOTSTRAP_SERVERS);
        return props;
    }

    public static String getBootstrapServers() {
        return KAFKA_BOOTSTRAP_SERVERS;
    }
}