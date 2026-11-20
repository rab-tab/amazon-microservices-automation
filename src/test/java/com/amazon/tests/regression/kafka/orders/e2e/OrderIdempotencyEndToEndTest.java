package com.amazon.tests.regression.kafka.orders.e2e;


import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.config.kafka.KafkaConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class OrderIdempotencyEndToEndTest extends BaseTest {

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String PAYMENT_RESULT_TOPIC = "payment.result";

    private static final String ORDER_CREATED = "ORDER_CREATED";

    private static final int EVENT_OBSERVATION_SECONDS = 10;
    private static final int PAYMENT_TIMEOUT_SECONDS = 30;

    private PurchaseResult purchase;
    private OrderApiClient orderApiClient;

    private KafkaTestConsumer orderEventsMonitor;
    private KafkaTestConsumer paymentResultMonitor;

    private KafkaProducer<String, String> kafkaProducer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeMethod
    public void setup() {

        logStep("Setting up end-to-end order idempotency test");

        purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        orderApiClient = new OrderApiClient(
                new BearerAuthStrategy(purchase.getCustomer().getAccessToken()),
                context.getExecutor()
        );

        // Start consumers before creating orders so events are not missed.
        orderEventsMonitor = new KafkaTestConsumer(ORDER_EVENTS_TOPIC);
        paymentResultMonitor = new KafkaTestConsumer(PAYMENT_RESULT_TOPIC);

        kafkaProducer = new KafkaProducer<>(
                KafkaConfig.getProducerProperties()
        );

        logStep("Setup complete");
    }

    @AfterMethod
    public void cleanup() {

        if (kafkaProducer != null) {
            kafkaProducer.close();
            kafkaProducer = null;
        }

        if (orderEventsMonitor != null) {
            orderEventsMonitor.close();
            orderEventsMonitor = null;
        }

        if (paymentResultMonitor != null) {
            paymentResultMonitor.close();
            paymentResultMonitor = null;
        }
    }

    private String userId() {
        return purchase.getCustomer().getUser().getId();
    }

    private String userToken() {
        return purchase.getCustomer().getAccessToken();
    }

    private List<TestModels.ProductResponse> products() {
        return purchase.getProducts();
    }

    private Predicate<JsonNode> orderCreatedEventFor(String orderId) {
        return event ->
                ORDER_CREATED.equals(event.path("eventType").asText())
                        && orderId.equals(event.path("orderId").asText());
    }

    private Predicate<JsonNode> paymentResultFor(String orderId) {
        return event ->
                orderId.equals(event.path("orderId").asText());
    }

    private void assertExactlyOneOrderCreatedEvent(String orderId) {

        List<JsonNode> events = orderEventsMonitor.collectMessages(
                orderCreatedEventFor(orderId),
                EVENT_OBSERVATION_SECONDS
        );

        assertThat(events)
                .as("Exactly one ORDER_CREATED event should exist for order %s", orderId)
                .hasSize(1);
    }

    private Optional<JsonNode> waitForOrderCreatedEvent(String orderId) {

        Optional<JsonNode> event = orderEventsMonitor.waitForMessage(
                orderCreatedEventFor(orderId),
                EVENT_OBSERVATION_SECONDS
        );

        assertThat(event)
                .as("ORDER_CREATED event should be received for order %s", orderId)
                .isNotNull();

        return event;
    }

    private void waitForPaymentResult(String orderId) {

        Optional<JsonNode> result = paymentResultMonitor.waitForMessage(
                paymentResultFor(orderId),
                PAYMENT_TIMEOUT_SECONDS
        );

        assertThat(result)
                .as("Payment result should be received for order %s", orderId)
                .isNotNull();
    }

    // ------------------------------------------------------------
    // TEST 01: Same idempotency key returns the same order
    // ------------------------------------------------------------

    @Test(description = "Repeated Create Order request with same key returns original order")
    @Story("End-to-End Order Idempotency")
    @Severity(SeverityLevel.CRITICAL)
    public void test01_SameIdempotencyKey_ReturnsSameOrder() {

        logStep("TEST 01: Same idempotency key returns the same order");

        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        TestModels.OrderResponse firstOrder =
                orderApiClient.createOrder(
                        userId(),
                        idempotencyKey,
                        products()
                );

        String orderId = firstOrder.getId();

        assertThat(orderId)
                .as("First request should create an order")
                .isNotBlank();

        TestModels.OrderResponse duplicateOrder =
                orderApiClient.createOrder(
                        userId(),
                        idempotencyKey,
                        products()
                );

        assertThat(duplicateOrder.getId())
                .as("Duplicate request should return the original order")
                .isEqualTo(orderId);

        assertExactlyOneOrderCreatedEvent(orderId);

        waitForPaymentResult(orderId);

        TestModels.OrderResponse finalOrder =
                orderApiClient.getOrder(userToken(), userId(), orderId);

        assertThat(finalOrder.getId()).isEqualTo(orderId);

        logStep("Same key returned the original order");
        logStep("Exactly one ORDER_CREATED event observed");
        logStep("Payment result received");
    }

    // ------------------------------------------------------------
    // TEST 02: Concurrent requests with the same idempotency key
    // ------------------------------------------------------------

    @Test(description = "Concurrent requests with same key create one order")
    @Story("End-to-End Order Idempotency")
    @Severity(SeverityLevel.CRITICAL)
    public void test02_ConcurrentRequestsWithSameKey_CreateOneOrder() {

        logStep("TEST 02: Concurrent duplicate Create Order requests");

        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        int requestCount = 5;

        CountDownLatch startSignal = new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(requestCount)) {

            List<CompletableFuture<TestModels.OrderResponse>> requests =
                    java.util.stream.IntStream.range(0, requestCount)
                            .mapToObj(index ->
                                    CompletableFuture.supplyAsync(() -> {
                                        try {
                                            startSignal.await();

                                            return orderApiClient.createOrder(
                                                    userId(),
                                                    idempotencyKey,
                                                    products()
                                            );

                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            throw new IllegalStateException(
                                                    "Concurrent order request interrupted", e
                                            );
                                        }
                                    }, executor)
                            )
                            .toList();

            // Release all requests together.
            startSignal.countDown();

            CompletableFuture.allOf(
                    requests.toArray(new CompletableFuture[0])
            ).join();

            List<String> orderIds = requests.stream()
                    .map(CompletableFuture::join)
                    .map(TestModels.OrderResponse::getId)
                    .toList();

            assertThat(orderIds)
                    .as("All concurrent requests should return an order ID")
                    .allSatisfy(id -> assertThat(id).isNotBlank());

            assertThat(orderIds)
                    .as("All concurrent requests should return the same order")
                    .containsOnly(orderIds.get(0));

            String orderId = orderIds.get(0);

            assertExactlyOneOrderCreatedEvent(orderId);

            waitForPaymentResult(orderId);

            logStep("All concurrent requests returned order: " + orderId);
            logStep("Exactly one ORDER_CREATED event observed");
        }
    }

    // ------------------------------------------------------------
    // TEST 03: Different idempotency keys create separate orders
    // ------------------------------------------------------------

    @Test(description = "Different idempotency keys create separate orders")
    @Story("End-to-End Order Idempotency")
    @Severity(SeverityLevel.CRITICAL)
    public void test03_DifferentKeys_CreateSeparateOrders() {

        logStep("TEST 03: Different idempotency keys create separate orders");

        String key1 = TestDataFactory.newIdempotencyKey();
        String key2 = TestDataFactory.newIdempotencyKey();

        TestModels.OrderResponse firstOrder =
                orderApiClient.createOrder(
                        userId(),
                        key1,
                        products()
                );

        TestModels.OrderResponse secondOrder =
                orderApiClient.createOrder(
                        userId(),
                        key2,
                        products()
                );

        String orderId1 = firstOrder.getId();
        String orderId2 = secondOrder.getId();

        assertThat(orderId1).isNotBlank();
        assertThat(orderId2)
                .isNotBlank()
                .isNotEqualTo(orderId1);

        List<JsonNode> events = orderEventsMonitor.collectMessages(
                event ->
                        ORDER_CREATED.equals(event.path("eventType").asText())
                                && Set.of(orderId1, orderId2)
                                .contains(event.path("orderId").asText()),
                EVENT_OBSERVATION_SECONDS
        );

        assertThat(events)
                .as("Both orders should publish their own ORDER_CREATED event")
                .hasSize(2);

        assertThat(events)
                .filteredOn(event ->
                        orderId1.equals(event.path("orderId").asText()))
                .hasSize(1);

        assertThat(events)
                .filteredOn(event ->
                        orderId2.equals(event.path("orderId").asText()))
                .hasSize(1);

        waitForPaymentResult(orderId1);
        waitForPaymentResult(orderId2);

        logStep("Different keys created two different orders");
        logStep("Each order produced exactly one ORDER_CREATED event");
        logStep("Both payment results received");
    }

    // ------------------------------------------------------------
    // TEST 04: Duplicate ORDER_CREATED event creates one payment
    // ------------------------------------------------------------

    @Test(description = "Duplicate ORDER_CREATED event creates only one payment")
    @Story("End-to-End Order Idempotency")
    @Severity(SeverityLevel.CRITICAL)
    public void test04_DuplicateOrderEvent_CreatesOnePayment() {

        logStep("TEST 04: Duplicate ORDER_CREATED event payment protection");

        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        TestModels.OrderResponse order =
                orderApiClient.createOrder(
                        userId(),
                        idempotencyKey,
                        products()
                );

        String orderId = order.getId();

        assertThat(orderId).isNotBlank();

        // Capture the actual event generated by Order Service.
        Optional<JsonNode> originalEvent = waitForOrderCreatedEvent(orderId);

        // Wait for the original event to be processed by Payment Service.
        waitForPaymentResult(orderId);

        assertThat(countPaymentsForOrder(orderId))
                .as("Original ORDER_CREATED event should create one payment")
                .isEqualTo(1);

        // Republish the exact same event with the same Kafka key.
        String eventJson;

        try {
            eventJson = objectMapper.writeValueAsString(originalEvent);
        } catch (JsonProcessingException e) {
            throw new AssertionError("Unable to serialize captured ORDER_CREATED event", e);
        }

        kafkaProducer.send(
                new ProducerRecord<>(
                        ORDER_EVENTS_TOPIC,
                        orderId,
                        eventJson
                )
        );

        kafkaProducer.flush();

        // Payment Service should recognize the duplicate and publish
        // the existing payment result rather than create another payment.
        waitForPaymentResult(orderId);

        assertThat(countPaymentsForOrder(orderId))
                .as("Duplicate ORDER_CREATED event must not create another payment")
                .isEqualTo(1);

        logStep("Original order event processed");
        logStep("Duplicate order event republished");
        logStep("Payment database contains exactly one payment");
    }

    // ------------------------------------------------------------
    // DATABASE VERIFICATION
    // ------------------------------------------------------------

    private long countPaymentsForOrder(String orderId) {

        String sql = """
                SELECT COUNT(*)
                FROM payments
                WHERE order_id = ?
                """;

        try (Connection connection = DriverManager.getConnection(
                context.getConfig().databaseHost(),
                context.getConfig().databaseUsername(),
                context.getConfig().databasePassword()
        );
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setObject(1, java.util.UUID.fromString(orderId));

            try (ResultSet resultSet = statement.executeQuery()) {

                if (resultSet.next()) {
                    return resultSet.getLong(1);
                }

                throw new AssertionError(
                        "Payment count query returned no result for order " + orderId
                );
            }

        } catch (Exception e) {
            throw new AssertionError(
                    "Unable to query payments_db for order " + orderId,
                    e
            );
        }
    }
}
