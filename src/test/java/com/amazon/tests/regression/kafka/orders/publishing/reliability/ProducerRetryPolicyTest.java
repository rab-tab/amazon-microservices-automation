
package com.amazon.tests.regression.kafka.orders.publishing.reliability;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.*;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class ProducerRetryPolicyTest extends BaseTest {

    private static final String ORDER_EVENTS_TOPIC = "order.events";

    private static final String DIAGNOSTICS_ENDPOINT =
            "/internal/test/kafka/producer";

    private static final int EXPECTED_RETRIES = 3;
    private static final int EXPECTED_RETRY_BACKOFF_MS = 1000;

    private static final Duration RETRY_OBSERVATION_TIMEOUT =
            Duration.ofSeconds(45);

    private static final Duration EVENT_DELIVERY_TIMEOUT =
            Duration.ofSeconds(60);

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper();

    private static KafkaContainer kafka;
    private static ToxiproxyContainer toxiproxy;

    private static ToxiproxyClient toxiproxyClient;
    private static Proxy kafkaProxy;

    private static String proxiedBootstrapServers;

    private KafkaConsumer<String, String> kafkaConsumer;

    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;

    private String userToken;

    /*
     * ------------------------------------------------------------
     * CONTAINER LIFECYCLE
     * ------------------------------------------------------------
     */

    @BeforeSuite(alwaysRun = true)
    public static void startContainers() throws IOException {

        kafka = new KafkaContainer(
                DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
        );

        kafka.start();

        toxiproxy = new ToxiproxyContainer(
                "ghcr.io/shopify/toxiproxy:2.5.0"
        );

        toxiproxy.start();

        toxiproxyClient = new ToxiproxyClient(
                toxiproxy.getHost(),
                toxiproxy.getControlPort()
        );

        kafkaProxy = toxiproxyClient.createProxy(
                "kafka",
                "0.0.0.0:8666",
                kafka.getHost() + ":" + kafka.getMappedPort(9093)
        );

        proxiedBootstrapServers =
                toxiproxy.getHost() + ":" +
                        toxiproxy.getMappedPort(8666);

        System.out.println("Kafka direct endpoint: "
                + kafka.getBootstrapServers());

        System.out.println("Kafka proxied endpoint: "
                + proxiedBootstrapServers);

        System.out.println(
                "Order Service must use SPRING_KAFKA_BOOTSTRAP_SERVERS="
                        + proxiedBootstrapServers
        );

        /*
         * IMPORTANT:
         *
         * The Order Service is a separate process.
         * It must be configured to use the proxy endpoint.
         *
         * Kafka metadata must also advertise an address that
         * routes through Toxiproxy. Otherwise, the producer may
         * bypass the proxy after its initial bootstrap connection.
         */
    }

    @AfterSuite(alwaysRun = true)
    public static void stopContainers() {

        if (toxiproxy != null) {
            toxiproxy.stop();
        }

        if (kafka != null) {
            kafka.stop();
        }
    }

    /*
     * ------------------------------------------------------------
     * TEST SETUP
     * ------------------------------------------------------------
     */

    @BeforeMethod(alwaysRun = true)
    public void setUp() throws Exception {

        user = UserSeeder.builder(context)
                .count(1)
                .build()
                .seed()
                .getFirst();

        userToken = context.getCached(
                "user_token_" + user.getId(),
                String.class
        );

        product = ProductSeeder.builder(context)
                .count(1)
                .highStock()
                .build()
                .seed()
                .getFirst();

        kafkaConsumer = createKafkaConsumer();

        assignConsumerToTopicEnd();
    }

    /*
     * ------------------------------------------------------------
     * TEST 01
     * Verify producer configuration, retry behaviour,
     * recovery and eventual event delivery.
     * ------------------------------------------------------------
     */

    @Test(
            description = "Verify Kafka producer retries and recovers after network failure",
            timeOut = 150000
    )
    public void testProducerRetriesAndRecoversAfterNetworkFailure()
            throws Exception {

        /*
         * STEP 1:
         * Verify the actual producer configuration.
         */

        JsonNode initialDiagnostics = getProducerDiagnostics();

        JsonNode producerConfig =
                initialDiagnostics.path("producerConfig");

        assertThat(producerConfig.path("acks").asText())
                .as("Producer acks configuration")
                .isEqualTo("all");

        assertThat(producerConfig.path("retries").asInt())
                .as("Producer retry count")
                .isEqualTo(EXPECTED_RETRIES);

        assertThat(producerConfig.path("retry.backoff.ms").asInt())
                .as("Producer retry backoff")
                .isEqualTo(EXPECTED_RETRY_BACKOFF_MS);

        /*
         * STEP 2:
         * Publish a warm-up order.  * This ensures that the producer has been initialized
         * and establishes a baseline for producer metrics.
         */

        CreatedOrder warmupOrder = createOrder();
        assertThat(warmupOrder.response().statusCode())
                .as("Warm-up order creation")
                .isEqualTo(201);
        boolean warmupEventReceived = awaitOrderEvent(warmupOrder.orderId(), EVENT_DELIVERY_TIMEOUT);
        assertThat(warmupEventReceived)
                .as("Warm-up ORDER_CREATED event")
                .isTrue();
        /*
         * STEP 3:
         * Capture the baseline retry metric. record-retry-total is cumulative for the producer.
         */

        JsonNode baselineDiagnostics = getProducerDiagnostics();
        double baselineRetryCount =
                getMetricValue(
                        baselineDiagnostics,
                        "record-retry-total"
                );

        System.out.println("Baseline record-retry-total: "+ baselineRetryCount);

        /*
         * STEP 4:
         * Install a connection-reset toxic. UPSTREAM represents traffic from the Kafka producer
         * (client) towards the Kafka broker.
         */

        var resetToxic = kafkaProxy.toxics().resetPeer("producer_connection_reset", ToxicDirection.UPSTREAM, 1000);
        long faultStartTime = System.nanoTime();

        try {
            /*
             * STEP 5:
             * Create an order while the network fault is active. The Order Service uses asynchronous Kafka publishing,
             * so HTTP 201 does not prove that the event was delivered.
             */
            CreatedOrder targetOrder = createOrder();
            assertThat(targetOrder.response().statusCode())
                    .as("Order should be accepted by Order Service")
                    .isEqualTo(201);

            System.out.println("Target order ID: " + targetOrder.orderId());
            /*
             * STEP 6:
             * Observe retry metric increments.
             * The metric is cumulative. We compare it with the
             * baseline captured before the network failure.
             */

            List<RetryObservation> observations = observeRetryIncrements(baselineRetryCount, RETRY_OBSERVATION_TIMEOUT);
            assertThat(observations)
                    .as("Producer should perform at least one retry")
                    .isNotEmpty();

            double latestRetryCount = observations.get(observations.size() - 1).retryCount();
            System.out.println("Observed retry metric: " + latestRetryCount);

            for (RetryObservation observation : observations) {
                System.out.println(
                        "Retry metric observed at "
                                + observation.elapsedMillis()
                                + " ms; cumulative count="
                                + observation.retryCount()
                );
            }

            /*
             * STEP 7:
             * Remove the toxic immediately after observing retry
             * activity. This gives the producer an opportunity
             * to retry and deliver before delivery.timeout.ms.
             */

            resetToxic.remove();
            System.out.println("Toxiproxy connection-reset toxic removed.");
            /*
             * STEP 8:
             * Verify that the original target order's event   * eventually reaches Kafka.
             */
            boolean targetEventReceived = awaitOrderEvent(
                    targetOrder.orderId(),
                    EVENT_DELIVERY_TIMEOUT
            );

            assertThat(targetEventReceived)
                    .as("ORDER_CREATED event should be delivered after recovery")
                    .isTrue();

            System.out.println("Network recovery and event delivery verified.");

        } finally {
            /*
             * Always remove the toxic, even if an assertion fails.
             */
            try {resetToxic.remove();
            } catch (Exception ignored) {
                // It may already have been removed.
            }
        }
    }

    /*
     * ------------------------------------------------------------
     * ORDER CREATION
     * ------------------------------------------------------------
     */

    private CreatedOrder createOrder() throws Exception {

        TestModels.CreateOrderRequest orderRequest =
                OrderBuilder.anOrder()
                        .withNamespace(context.getNamespace())
                        .addItem(product, 1)
                        .build();

        String requestBody =
                OBJECT_MAPPER.writeValueAsString(orderRequest);

        Response response = given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header(
                        "Idempotency-Key",
                        UUID.randomUUID().toString()
                )
                .header("X-User-Id", user.getId().toString())
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/orders");

        String orderId = extractOrderId(response);

        return new CreatedOrder(orderId, response);
    }

    private String extractOrderId(Response response)
            throws IOException {

        JsonNode root =
                OBJECT_MAPPER.readTree(response.asString());

        String[] possiblePaths = {
                "orderId",
                "id",
                "data.orderId",
                "data.id",
                "order.orderId",
                "order.id"
        };

        for (String path : possiblePaths) {

            JsonNode value = root.at(
                    "/" + path.replace(".", "/")
            );

            if (!value.isMissingNode()
                    && !value.isNull()
                    && !value.asText().isBlank()) {

                return value.asText();
            }
        }

        throw new IllegalStateException(
                "Could not extract order ID from response: "
                        + response.asString()
        );
    }

    /*
     * ------------------------------------------------------------
     * PRODUCER DIAGNOSTICS
     * ------------------------------------------------------------
     */

    private JsonNode getProducerDiagnostics()
            throws IOException {

        Response response = RestAssured.given()
                .baseUri(context.getConfig().baseUrl())
                .when()
                .get(DIAGNOSTICS_ENDPOINT);

        assertThat(response.statusCode())
                .as("Kafka producer diagnostics endpoint")
                .isEqualTo(200);

        return OBJECT_MAPPER.readTree(response.asString());
    }

    private double getMetricValue(
            JsonNode diagnostics,
            String metricName) {

        JsonNode metric = diagnostics
                .path("producerMetrics")
                .path(metricName);

        assertThat(metric.isNumber())
                .as("Producer metric must be available: " + metricName)
                .isTrue();

        return metric.asDouble();
    }

    /*
     * ------------------------------------------------------------
     * RETRY OBSERVATION
     * ------------------------------------------------------------
     */

    private List<RetryObservation> observeRetryIncrements(double baseline, Duration timeout) throws Exception {
        List<RetryObservation> observations = new ArrayList<>();
        long startTime = System.nanoTime();
        long deadline = System.nanoTime() + timeout.toNanos();
        double lastObservedCount = baseline;
        while (System.nanoTime() < deadline) {
            JsonNode diagnostics = getProducerDiagnostics();
            double currentCount = getMetricValue(diagnostics, "record-retry-total");
            if (currentCount > lastObservedCount) {
                long elapsedMillis =
                        Duration.ofNanos(
                                System.nanoTime() - startTime
                        ).toMillis();
                observations.add(
                        new RetryObservation(
                                currentCount,
                                elapsedMillis
                        )
                );
                lastObservedCount = currentCount;

                /*
                 * Stop observing as soon as retry activity is
                 * detected. The caller then removes the toxic.
                 */
                return observations;
            }
            Thread.sleep(100);
        }
        return observations;
    }

    /*
     * ------------------------------------------------------------
     * KAFKA CONSUMER
     * ------------------------------------------------------------
     */

    private KafkaConsumer<String, String> createKafkaConsumer() {

        Properties properties = new Properties();

        properties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()
        );

        properties.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                "producer-retry-test-" + UUID.randomUUID()
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
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "latest"
        );

        properties.put(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                "false"
        );

        return new KafkaConsumer<>(properties);
    }

    private void assignConsumerToTopicEnd() {

        List<org.apache.kafka.common.PartitionInfo> partitions =
                kafkaConsumer.partitionsFor(
                        ORDER_EVENTS_TOPIC,
                        Duration.ofSeconds(10)
                );

        assertThat(partitions)
                .as("Kafka topic partitions")
                .isNotEmpty();

        Collection<TopicPartition> topicPartitions =
                new ArrayList<>();

        for (var partition : partitions) {

            topicPartitions.add(
                    new TopicPartition(
                            ORDER_EVENTS_TOPIC,
                            partition.partition()
                    )
            );
        }

        kafkaConsumer.assign(topicPartitions);
        kafkaConsumer.seekToEnd(topicPartitions);
    }

    private boolean awaitOrderEvent(
            String expectedOrderId,
            Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                JsonNode event = OBJECT_MAPPER.readTree(record.value());
                JsonNode orderIdNode = event.path("orderId");
                if (!orderIdNode.isMissingNode()
                        && expectedOrderId.equals(
                        orderIdNode.asText()
                )) {
                    System.out.println(
                            "Matching ORDER_CREATED event found. "
                                    + "Order ID: " + expectedOrderId
                                    + ", partition: " + record.partition()
                                    + ", offset: " + record.offset()
                    );
                    return true;
                }
            }
        }
        return false;
    }

    /*
     * ------------------------------------------------------------
     * CLEANUP
     * ------------------------------------------------------------
     */

    @AfterMethod(alwaysRun = true)
    public void cleanup() throws IOException {

        if (kafkaProxy != null) {

            kafkaProxy.toxics().getAll().forEach(toxic -> {
                try {
                    toxic.remove();
                } catch (IOException e) {
                    throw new RuntimeException(
                            "Failed to remove Toxiproxy toxic", e
                    );
                }
            });
        }

        if (kafkaConsumer != null) {
            kafkaConsumer.close();
            kafkaConsumer = null;
        }
    }

    /*
     * ------------------------------------------------------------
     * TEST DATA TYPES
     * ------------------------------------------------------------
     */

    private record CreatedOrder(
            String orderId,
            Response response
    ) {}

    private record RetryObservation(
            double retryCount,
            long elapsedMillis
    ) {}
}
