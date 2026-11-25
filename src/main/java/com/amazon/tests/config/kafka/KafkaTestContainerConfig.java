package com.amazon.tests.config.kafka;


import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

public final class KafkaTestContainerConfig {

    private static final DockerImageName KAFKA_IMAGE =
            DockerImageName.parse("apache/kafka:3.7.0");

    private static KafkaContainer kafkaContainer;

    private KafkaTestContainerConfig() {
    }

    public static synchronized KafkaContainer start() {
        if (kafkaContainer == null || !kafkaContainer.isRunning()) {
            kafkaContainer = new KafkaContainer(KAFKA_IMAGE)
                    .withStartupTimeout(Duration.ofMinutes(2));

            kafkaContainer.start();
        }

        return kafkaContainer;
    }

    public static String getBootstrapServers() {
        return start().getBootstrapServers();
    }

    public static KafkaContainer getContainer() {
        return start();
    }

    public static synchronized void stop() {
        if (kafkaContainer != null) {
            kafkaContainer.stop();
            kafkaContainer = null;
        }
    }
}

