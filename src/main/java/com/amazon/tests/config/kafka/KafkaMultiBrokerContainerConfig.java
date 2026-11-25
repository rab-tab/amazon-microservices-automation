package com.amazon.tests.config.kafka;


import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;


public final class KafkaMultiBrokerContainerConfig {

    private static final String KAFKA_IMAGE =
            System.getProperty(
                    "kafka.container.image",
                    "confluentinc/cp-kafka:7.7.0"
            );

    private static final String CLUSTER_ID =
            "MkU3OEVBNTcwNTJENDM2Qk";

    private static final int BROKER_PORT = 9092;
    private static final int CONTROLLER_PORT = 9093;
    private static final int EXTERNAL_PORT = 19092;

    private static final int BROKER_COUNT = 3;

    private static Network network;

    private static final List<FixedPortKafkaContainer> brokers =
            new ArrayList<>();

    private static String bootstrapServers;

    private KafkaMultiBrokerContainerConfig() {
    }

    public static synchronized void start() {
        if (!brokers.isEmpty()
                && brokers.stream().allMatch(
                GenericContainer::isRunning
        )) {
            return;
        }

        stop();

        network = Network.newNetwork();

        String controllerVoters = buildControllerVoters();

        for (int nodeId = 1; nodeId <= BROKER_COUNT; nodeId++) {

            int externalPort = EXTERNAL_PORT + nodeId - 1;

            String brokerAlias = "kafka-" + nodeId;

            FixedPortKafkaContainer broker =
                    new FixedPortKafkaContainer(
                            DockerImageName.parse(KAFKA_IMAGE),
                            externalPort
                    )
                            .withNetwork(network)
                            .withNetworkAliases(brokerAlias)
                            .withEnv("CLUSTER_ID", CLUSTER_ID)
                            .withEnv("KAFKA_NODE_ID", String.valueOf(nodeId))
                            .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                            .withEnv(
                                    "KAFKA_CONTROLLER_QUORUM_VOTERS",
                                    controllerVoters
                            )
                            .withEnv(
                                    "KAFKA_LISTENERS",
                                    "INTERNAL://0.0.0.0:" + BROKER_PORT
                                            + ",EXTERNAL://0.0.0.0:" + EXTERNAL_PORT
                                            + ",CONTROLLER://0.0.0.0:"
                                            + CONTROLLER_PORT
                            )
                            .withEnv(
                                    "KAFKA_ADVERTISED_LISTENERS",
                                    "INTERNAL://" + brokerAlias + ":" + BROKER_PORT
                                            + ",EXTERNAL://localhost:" + externalPort
                            )
                            .withEnv(
                                    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                                    "INTERNAL:PLAINTEXT,"
                                            + "EXTERNAL:PLAINTEXT,"
                                            + "CONTROLLER:PLAINTEXT"
                            )
                            .withEnv(
                                    "KAFKA_INTER_BROKER_LISTENER_NAME",
                                    "INTERNAL"
                            )
                            .withEnv(
                                    "KAFKA_CONTROLLER_LISTENER_NAMES",
                                    "CONTROLLER"
                            )
                            .withEnv(
                                    "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR",
                                    "3"
                            )
                            .withEnv(
                                    "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR",
                                    "3"
                            )
                            .withEnv(
                                    "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR",
                                    "2"
                            )
                            .withEnv(
                                    "KAFKA_DEFAULT_REPLICATION_FACTOR",
                                    "3"
                            )
                            .withEnv("KAFKA_MIN_INSYNC_REPLICAS", "2")
                            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                            .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                            .withStartupTimeout(Duration.ofMinutes(3));

            brokers.add(broker);
        }

        // Start all brokers on the same Docker network.
        brokers.forEach(GenericContainer::start);

        bootstrapServers = brokers.stream()
                .map(broker ->
                        "localhost:" + broker.getExternalPort()
                )
                .reduce((first, second) -> first + "," + second)
                .orElseThrow(
                        () -> new IllegalStateException(
                                "Kafka brokers did not start"
                        )
                );

        waitForCluster();
    }

    private static String buildControllerVoters() {
        List<String> voters = new ArrayList<>();

        for (int nodeId = 1; nodeId <= BROKER_COUNT; nodeId++) {
            voters.add(
                    nodeId + "@kafka-" + nodeId + ":" + CONTROLLER_PORT
            );
        }

        return String.join(",", voters);
    }

    private static void waitForCluster() {
        long deadline = System.currentTimeMillis() + 120_000L;

        while (System.currentTimeMillis() < deadline) {
            try {
                org.apache.kafka.clients.admin.AdminClient admin =
                        org.apache.kafka.clients.admin.AdminClient.create(
                                java.util.Map.of(
                                        org.apache.kafka.clients.admin
                                                .AdminClientConfig
                                                .BOOTSTRAP_SERVERS_CONFIG,
                                        bootstrapServers
                                )
                        );

                try {
                    int brokerCount = admin.describeCluster()
                            .nodes()
                            .get()
                            .size();

                    if (brokerCount == BROKER_COUNT) {
                        return;
                    }
                } finally {
                    admin.close(Duration.ofSeconds(2));
                }

            } catch (Exception ignored) {
                // Brokers may still be forming the KRaft quorum.
            }

            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for Kafka cluster",
                        exception
                );
            }
        }

        throw new IllegalStateException(
                "Three-broker Kafka cluster did not become ready. "
                        + "Bootstrap servers: " + bootstrapServers
        );
    }

    public static String getBootstrapServers() {
        start();
        return bootstrapServers;
    }

    public static List<FixedPortKafkaContainer> getBrokers() {
        start();
        return List.copyOf(brokers);
    }

    public static synchronized void stop() {
        for (FixedPortKafkaContainer broker : brokers) {
            try {
                broker.stop();
            } catch (Exception ignored) {
                // Continue stopping remaining brokers.
            }
        }

        brokers.clear();

        if (network != null) {
            network.close();
            network = null;
        }

        bootstrapServers = null;
    }

    public static final class FixedPortKafkaContainer
            extends GenericContainer<FixedPortKafkaContainer> {

        private final int externalPort;

        FixedPortKafkaContainer(
                DockerImageName image,
                int externalPort
        ) {
            super(image);
            this.externalPort = externalPort;

            addFixedExposedPort(externalPort, EXTERNAL_PORT);

            withExposedPorts(BROKER_PORT, CONTROLLER_PORT);
            waitingFor(
                    Wait.forListeningPort()
                            .withStartupTimeout(Duration.ofMinutes(3))
            );
        }

        public int getExternalPort() {
            return externalPort;
        }
    }
}
