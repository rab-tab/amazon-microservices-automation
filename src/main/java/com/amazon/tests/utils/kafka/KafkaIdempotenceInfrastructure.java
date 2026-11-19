package com.amazon.tests.utils.kafka;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.Node;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Slf4j
public class KafkaIdempotenceInfrastructure {

    // ---------------------------------------------------------
    // CONTAINER CONFIGURATION
    // ---------------------------------------------------------

    private static final String KAFKA_IMAGE =
            "confluentinc/cp-kafka:7.5.0";

    private static final String TOXIPROXY_IMAGE =
            "ghcr.io/shopify/toxiproxy:2.5.0";

    private static final String KAFKA_NETWORK_ALIAS = "kafka";

    private static final String TOPIC =
            "producer.idempotence.test";

    private static final String CLUSTER_ID =
            "MkU3OEVBNTcwNTJENDM2Qk";

    // ---------------------------------------------------------
    // KAFKA CONTAINER PORTS
    // ---------------------------------------------------------

    private static final int KAFKA_INTERNAL_PORT = 9092;
    private static final int KAFKA_CONTROLLER_PORT = 9093;
    private static final int KAFKA_PROXIED_PORT = 9094;

    // ---------------------------------------------------------
    // TOXIPROXY PORTS
    // ---------------------------------------------------------

    private static final int TOXIPROXY_LISTEN_PORT = 8666;

    // ---------------------------------------------------------
    // FIXED HOST PORTS
    // ---------------------------------------------------------

    private static final int HOST_KAFKA_DIRECT_PORT = 19092;
    private static final int HOST_TOXIPROXY_PORT = 18666;

    // ---------------------------------------------------------
    // CONTAINERS AND PROXY
    // ---------------------------------------------------------

    private static Network network;

    private static GenericContainer<?> kafka;

    private static ToxiproxyContainer toxiproxy;

    private static Proxy kafkaProxy;

    private static ToxiproxyClient toxiproxyClient;

    // ---------------------------------------------------------
    // BOOTSTRAP ENDPOINTS
    // ---------------------------------------------------------

    private static String producerBootstrapServers;

    private static String consumerBootstrapServers;

    // ---------------------------------------------------------
    // CONTAINER SETUP
    // ---------------------------------------------------------

    @BeforeSuite(alwaysRun = true)
    public static void startInfrastructure() throws Exception {

        log.info("Starting Kafka idempotence infrastructure...");

        // STEP 1: Create shared Docker network.

        network = Network.newNetwork();

        // STEP 2: Start Toxiproxy first.

        ToxiproxyContainer toxiproxy =
                new ToxiproxyContainer(
                        DockerImageName.parse("shopify/toxiproxy:2.5.0")
                );

        toxiproxy
                .withNetwork(network)
                .withNetworkAliases("toxiproxy");

        toxiproxy.start();

        int toxiproxyPort = toxiproxy.getMappedPort(8666);
        int controlPort = toxiproxy.getControlPort();

        String proxyHost = toxiproxy.getHost();

        int proxyMappedPort =
                toxiproxy.getMappedPort(TOXIPROXY_LISTEN_PORT);

        log.info(
                "Toxiproxy started at {}:{}",
                toxiproxyPort,
                controlPort
        );

        // STEP 3: Configure producer and consumer endpoints.

        producerBootstrapServers =
                proxyHost + ":" + HOST_TOXIPROXY_PORT;

        consumerBootstrapServers =
                proxyHost + ":" + HOST_KAFKA_DIRECT_PORT;

        // STEP 4: Start Kafka with separate listeners.

        kafka = new GenericContainer<>(
                DockerImageName.parse(KAFKA_IMAGE)
        )
                .withNetwork(network)
                .withNetworkAliases(KAFKA_NETWORK_ALIAS)

                // Docker dynamically allocates host ports
                .withExposedPorts(
                        KAFKA_INTERNAL_PORT,
                        KAFKA_PROXIED_PORT
                )

                .withEnv("CLUSTER_ID", CLUSTER_ID)
                .withEnv("KAFKA_NODE_ID", "1")
                .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")

                .withEnv(
                        "KAFKA_LISTENERS",
                        "INTERNAL://0.0.0.0:9092," +
                                "PROXIED://0.0.0.0:9094," +
                                "CONTROLLER://0.0.0.0:9093"
                )

                .withEnv(
                        "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                        "INTERNAL:PLAINTEXT," +
                                "PROXIED:PLAINTEXT," +
                                "CONTROLLER:PLAINTEXT"
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
                        "KAFKA_CONTROLLER_QUORUM_VOTERS",
                        "1@localhost:9093"
                )

                .withEnv(
                        "KAFKA_LOG_DIRS",
                        "/tmp/kraft-combined-logs"
                )

                .withEnv(
                        "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR",
                        "1"
                )

                .withEnv(
                        "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR",
                        "1"
                )

                .withEnv(
                        "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR",
                        "1"
                )

                .withEnv(
                        "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS",
                        "0"
                )

                .withEnv(
                        "KAFKA_AUTO_CREATE_TOPICS_ENABLE",
                        "false"
                )

                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(2));
        kafka.start();

        log.info("Kafka container started");

        // STEP 5: Create Toxiproxy -> Kafka proxy.

        toxiproxyClient = new ToxiproxyClient(
                toxiproxy.getHost(),
                toxiproxy.getControlPort()
        );

        kafkaProxy = toxiproxyClient.createProxy(
                "kafka",
                "0.0.0.0:" + TOXIPROXY_LISTEN_PORT,
                KAFKA_NETWORK_ALIAS + ":" + KAFKA_PROXIED_PORT
        );

        log.info(
                "Kafka proxy created: {} -> {}:{}",
                producerBootstrapServers,
                KAFKA_NETWORK_ALIAS,
                KAFKA_PROXIED_PORT
        );

        // STEP 6: Verify advertised broker metadata.

        verifyAdvertisedBroker(
                producerBootstrapServers,
                proxyHost,
                proxyMappedPort,
                "PROXIED"
        );

        verifyAdvertisedBroker(
                consumerBootstrapServers,
                proxyHost,
                HOST_KAFKA_DIRECT_PORT,
                "INTERNAL"
        );

        // STEP 7: Verify proxy isolation.

        verifyProxyIsRequired();

        // STEP 8: Create dedicated test topic.

        createTestTopic();

        log.info("Kafka idempotence infrastructure is ready");
    }

    // ---------------------------------------------------------
    // ADVERTISED BROKER VERIFICATION
    // ---------------------------------------------------------

    private static void verifyAdvertisedBroker(
            String bootstrapServers,
            String expectedHost,
            int expectedPort,
            String listenerName
    ) throws Exception {

        Properties properties = new Properties();

        properties.put(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers
        );

        properties.put(
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
                "10000"
        );

        properties.put(
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                "15000"
        );

        try (AdminClient adminClient = AdminClient.create(properties)) {

            Collection<Node> brokers =
                    adminClient.describeCluster()
                            .nodes()
                            .get();

            log.info(
                    "{} listener broker metadata: {}",
                    listenerName,
                    brokers
            );

            assertThat(brokers)
                    .as(listenerName + " listener should return one broker")
                    .hasSize(1);

            Node broker = brokers.iterator().next();

            assertThat(broker.host())
                    .as(listenerName + " advertised broker host")
                    .isEqualTo(expectedHost);

            assertThat(broker.port())
                    .as(listenerName + " advertised broker port")
                    .isEqualTo(expectedPort);

            log.info(
                    "{} listener metadata verification passed",
                    listenerName
            );
        }
    }

    // ---------------------------------------------------------
    // PROXY ISOLATION VERIFICATION
    // ---------------------------------------------------------

    public static void verifyProxyIsRequired() throws Exception {

        log.info("Verifying that producer connections require Toxiproxy");

        kafkaProxy.disable();

        try {

            Properties properties = new Properties();

            properties.put(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                    producerBootstrapServers
            );

            properties.put(
                    AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
                    "3000"
            );

            properties.put(
                    AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                    "5000"
            );

            assertThatThrownBy(() -> {

                try (AdminClient adminClient =
                             AdminClient.create(properties)) {

                    adminClient.describeCluster()
                            .nodes()
                            .get();
                }

            })
                    .as("Proxied Kafka endpoint should fail when proxy is disabled")
                    .isInstanceOf(Exception.class);

        } finally {

            kafkaProxy.enable();
        }

        log.info("Proxy isolation verification passed");
    }

    // ---------------------------------------------------------
    // TEST TOPIC CREATION
    // ---------------------------------------------------------

    private static void createTestTopic() throws Exception {

        Properties properties = new Properties();

        properties.put(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                consumerBootstrapServers
        );

        try (AdminClient adminClient = AdminClient.create(properties)) {

            NewTopic topic = new NewTopic(
                    TOPIC,
                    1,
                    (short) 1
            );

            adminClient.createTopics(List.of(topic))
                    .all()
                    .get();
        }

        log.info("Created Kafka test topic: {}", TOPIC);
    }

    // ---------------------------------------------------------
    // PUBLIC ACCESSORS
    // ---------------------------------------------------------

    public static String getProducerBootstrapServers() {
        return producerBootstrapServers;
    }

    public static String getConsumerBootstrapServers() {
        return consumerBootstrapServers;
    }

    public static String getTopic() {
        return TOPIC;
    }

    public static Proxy getKafkaProxy() {
        return kafkaProxy;
    }

    public static GenericContainer<?> getKafkaContainer() {
        return kafka;
    }

    public static ToxiproxyContainer getToxiproxyContainer() {
        return toxiproxy;
    }

    // ---------------------------------------------------------
    // INFRASTRUCTURE CLEANUP
    // ---------------------------------------------------------

    @AfterSuite(alwaysRun = true)
    public static void stopInfrastructure() {

        log.info("Stopping Kafka idempotence infrastructure");

        if (kafkaProxy != null) {
            try {
                kafkaProxy.disable();
            } catch (Exception exception) {
                log.warn(
                        "Unable to disable Kafka proxy",
                        exception
                );
            }
        }

        if (kafka != null) {
            kafka.stop();
        }

        if (toxiproxy != null) {
            toxiproxy.stop();
        }

        if (network != null) {
            network.close();
        }

        log.info("Kafka idempotence infrastructure stopped");
    }
}

