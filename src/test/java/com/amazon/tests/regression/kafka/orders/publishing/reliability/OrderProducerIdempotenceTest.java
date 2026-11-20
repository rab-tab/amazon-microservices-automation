
package com.amazon.tests.regression.kafka.orders.publishing.reliability;

import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.utils.kafka.KafkaIdempotenceInfrastructure;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.testng.annotations.Test;

import java.util.*;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

public class OrderProducerIdempotenceTest extends BaseTest {

    private static final String ORDER_CREATED = "ORDER_CREATED";

    private static final int EVENT_OBSERVATION_TIMEOUT_SECONDS = 15;
    private static final int DUPLICATE_OBSERVATION_TIMEOUT_SECONDS = 10;

    private Proxy producerProxy() {
        return KafkaIdempotenceInfrastructure.getKafkaProxy();
    }

    private Predicate<JsonNode> orderCreatedFor(String orderId) {
        return event ->
                event != null
                        && event.has("eventType")
                        && ORDER_CREATED.equals(event.get("eventType").asText())
                        && event.has("orderId")
                        && orderId.equals(event.get("orderId").asText());
    }

    private List<JsonNode> collectOrderEvents(
            KafkaTestConsumer consumer,
            String orderId) {

        return consumer.collectMessages(
                orderCreatedFor(orderId),
                DUPLICATE_OBSERVATION_TIMEOUT_SECONDS
        );
    }

    private void assertExactlyOneOrderCreated(
            KafkaTestConsumer consumer,
            String orderId) {

        List<JsonNode> events = collectOrderEvents(consumer, orderId);

        assertThat(events)
                .as("Exactly one ORDER_CREATED event should exist for order %s", orderId)
                .hasSize(1);
    }

    private void assertOrderEventExists(
            KafkaTestConsumer consumer,
            String orderId) {

        Optional<JsonNode> event = consumer.waitForMessage(
                orderCreatedFor(orderId),
                EVENT_OBSERVATION_TIMEOUT_SECONDS
        );

        assertThat(event)
                .as("ORDER_CREATED should be published for order %s", orderId)
                .isNotNull();
    }

    private String createOrder() throws SeedingException {

        var purchase = PurchaseWorkflow.start(
                        context.getExecutor(),
                        authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        return purchase.getOrder().getId();
    }

    /*
     * CASE 1:
     * Lost acknowledgement.
     *
     * The downstream timeout interrupts the broker-to-producer
     * response path. The consumer uses a separate direct route.
     *
     * Note:
     * This verifies event uniqueness under an acknowledgement-path
     * interruption. To prove the exact append-then-lost-ack sequence,
     * correlate broker append evidence and producer retry metrics.
     */
    @Test
    public void test01_LostAcknowledgement_DoesNotDuplicateOrderEvent()
            throws Exception {

        logStep("Create a consumer before triggering the order flow");

        try (KafkaTestConsumer consumer =
                     new KafkaTestConsumer(KafkaIdempotenceInfrastructure.getTopic())) {

            logStep("Inject a temporary downstream acknowledgement-path fault");

            Toxic acknowledgementFault =
                    producerProxy()
                            .toxics()
                            .timeout(
                                    "lost-acknowledgement",
                                    ToxicDirection.DOWNSTREAM,
                                    1000
                            );

            String orderId;

            try {
                logStep("Create an order through the actual Order Service API");
                orderId = createOrder();
                logStep("Wait for the order event through the direct consumer route");
                assertOrderEventExists(consumer, orderId);

            } finally {
                acknowledgementFault.remove();
            }

            logStep("Observe the event stream after the producer route recovers");
            assertExactlyOneOrderCreated(consumer, orderId);
        }
    }

    /*
     * CASE 2:
     * Multiple orders in flight during a temporary publishing fault.
     *
     * This verifies that multiple order events are not duplicated.
     * It does not, by itself, guarantee that the producer formed
     * one batch or retried that batch.
     */
    @Test
    public void test02_InFlightBatchRetry_PreservesOrderingAndPreventsDuplicates()
            throws Exception {
        final int orderCount = 3;
        List<String> orderIds = new ArrayList<>();
        try (KafkaTestConsumer consumer =
                     new KafkaTestConsumer(KafkaIdempotenceInfrastructure.getTopic())) {
            logStep("Introduce a temporary publishing-path fault");
            Toxic publishingFault =
                    producerProxy()
                            .toxics()
                            .latency(
                                    "in-flight-publishing-delay",
                                    ToxicDirection.UPSTREAM,
                                    2000
                            );

            try {
                logStep("Create multiple orders through the Order Service");
                for (int i = 0; i < orderCount; i++) {
                    orderIds.add(createOrder());
                }

            } finally {
                publishingFault.remove();
            }

            logStep("Verify that every order has one ORDER_CREATED event");
            Map<String, List<JsonNode>> eventsByOrder =
                    new HashMap<>();
            for (String orderId : orderIds) {
                List<JsonNode> events = collectOrderEvents(consumer, orderId);
                eventsByOrder.put(orderId, events);
                assertThat(events)
                        .as("One event expected for order %s", orderId)
                        .hasSize(1);
            }

            assertThat(eventsByOrder).hasSize(orderCount);
        }
    }

    /*
     * CASE 3:
     * Connection reset during publishing.
     *
     * A temporary timeout closes the producer-side connection.
     * After the fault is removed, the test checks that the order
     * event is available and is not duplicated.
     */
    @Test
    public void test03_ConnectionResetDuringPublishing_RecoversWithoutDuplicates()
            throws Exception {

        try (KafkaTestConsumer consumer =
                     new KafkaTestConsumer(KafkaIdempotenceInfrastructure.getTopic())) {

            logStep("Inject a connection interruption on the producer route");

            Toxic connectionReset =
                    producerProxy()
                            .toxics()
                            .timeout(
                                    "publishing-connection-reset",
                                    ToxicDirection.DOWNSTREAM,
                                    1000
                            );

            String orderId;
            try {
                logStep("Create an order through the actual Order Service API");
                orderId = createOrder();
            } finally {
                connectionReset.remove();
            }
            logStep("Verify that the producer recovers and the event is published");
            assertOrderEventExists(consumer, orderId);
            logStep("Verify that no duplicate event is stored");
            assertExactlyOneOrderCreated(consumer, orderId);
        }
    }
}
