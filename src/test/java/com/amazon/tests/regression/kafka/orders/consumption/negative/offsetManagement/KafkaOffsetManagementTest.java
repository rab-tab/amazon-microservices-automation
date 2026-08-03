package com.amazon.tests.regression.kafka.orders.consumption.negative.offsetManagement;

import com.amazon.tests.BaseTest;
import com.amazon.tests.config.kafka.KafkaConfig;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka Offset Management - Processing Guarantees & Business Impact
 *
 * ⚠️ IMPORTANT: "redelivery" tests here republish a HAND-CONSTRUCTED
 * ORDER_CREATED payload sharing the same orderId, not a byte-for-byte
 * replay of the actual original event order-service published. This is
 * a real, useful test (does payment-service correctly dedupe two
 * logically-similar events for one order), but it is NOT a faithful
 * simulation of true Kafka message redelivery unless payment-service's
 * dedup key is orderId alone (not tied to exact event payload/offset).
 * Confirm this assumption with whoever owns payment-service's consumer
 * before treating a pass here as proof of true-redelivery safety.
 *
 * ⚠️ Same limitation as other Kafka idempotency tests in this package:
 * paymentCount is derived from Order.paymentId (0 or 1 only) via REST,
 * not a real DB row count — cannot detect a genuine multi-Payment-row
 * bug. Needs a real DB count query once available.
 */
@Slf4j
@Epic("Kafka Consumer Offset Management")
@Feature("Processing Guarantees & Business Impact")
public class KafkaOffsetManagementTest extends BaseTest {

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String PAYMENT_RESULT_TOPIC = "payment.result";

    private KafkaTestConsumer paymentResultMonitor;
    private KafkaProducer<String, String> kafkaProducer;
    private String userId;
    private String userToken;
    private TestModels.ProductResponse product;

    @BeforeClass
    public void setup() {
        logStep("Setting up offset management tests");

        PurchaseResult purchase = PurchaseWorkflow.start(executor, authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        userId = purchase.getCustomer().getUser().getId();
        userToken = purchase.getCustomer().getAccessToken();
        product = purchase.getFirstProduct();

        paymentResultMonitor = new KafkaTestConsumer(PAYMENT_RESULT_TOPIC);
        kafkaProducer = new KafkaProducer<>(KafkaConfig.getProducerProperties());

        logStep("✅ Setup complete — user: " + userId);
    }

    @AfterClass
    public void cleanup() {
        if (paymentResultMonitor != null) paymentResultMonitor.close();
        if (kafkaProducer != null) kafkaProducer.close();
        logStep("✅ Cleanup complete");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 1: EVENT REDELIVERY - IDEMPOTENCY PREVENTS DUPLICATE PAYMENT
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 1)
    @Story("At-Least-Once Processing")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Order event redelivered once — payment NOT duplicated")
    public void test01_EventRedelivered_NoDuplicatePayment() throws Exception {
        logStep("TEST 1: Event redelivery — idempotency prevents duplicate");

        paymentResultMonitor.seekToEnd();

        String orderId = createOrder();
        logStep("  ✓ Order created: " + orderId);

        JsonNode firstPaymentResult = waitForPaymentResult(orderId);
        String paymentId1 = firstPaymentResult.path("paymentId").asText();
        logStep("  ✓ First processing — paymentId: " + paymentId1);

        Response afterFirst = getOrder(orderId);
        String statusAfterFirst = afterFirst.jsonPath().getString("status");
        assertThat(statusAfterFirst).isNotEqualTo("PENDING");
        assertThat(afterFirst.jsonPath().getString("paymentId")).isNotNull();

        logStep("  💥 REPUBLISHING order event (simulated redelivery)");
        publishOrderEventToKafka(orderId, buildOrderCreatedEvent(orderId));

        Thread.sleep(15000);

        Response afterRedelivery = getOrder(orderId);
        String statusAfterRedelivery = afterRedelivery.jsonPath().getString("status");
        String paymentIdAfterRedelivery = afterRedelivery.jsonPath().getString("paymentId");

        logStep("  After redelivery — status: " + statusAfterRedelivery + ", paymentId: " + paymentIdAfterRedelivery);

        assertThat(statusAfterRedelivery)
                .as("Order status should NOT change after redelivery")
                .isEqualTo(statusAfterFirst);

        assertThat(paymentIdAfterRedelivery)
                .as("Payment ID should remain the same (idempotency prevented duplicate)")
                .isEqualTo(paymentId1);

        assertThat(countPaymentsForOrder(orderId))
                .as("Only ONE payment should exist")
                .isEqualTo(1);

        logStep("✅ REDELIVERY IDEMPOTENCY VALIDATED — paymentId unchanged: " + paymentIdAfterRedelivery);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 2: MULTIPLE REDELIVERIES - IDEMPOTENCY HOLDS
    // ══════════════════════════════════════════════════════════════════════════

    @Test(priority = 2)
    @Story("Multiple Redeliveries")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Event redelivered multiple times — only one payment created")
    public void test02_MultipleRedeliveries_OnlyOnePayment() throws Exception {
        logStep("TEST 2: Multiple redeliveries — idempotency holds");

        paymentResultMonitor.seekToEnd();

        String orderId = createOrder();
        logStep("  ✓ Order created: " + orderId);

        JsonNode firstPaymentResult = waitForPaymentResult(orderId);
        String paymentId1 = firstPaymentResult.path("paymentId").asText();
        logStep("  ✓ Payment 1 ID: " + paymentId1);

        logStep("  Publishing same event 3 more times (multiple redeliveries)");
        for (int i = 2; i <= 4; i++) {
            publishOrderEventToKafka(orderId, buildOrderCreatedEvent(orderId));
            logStep("    Redelivery #" + i + " published");
        }

        Thread.sleep(20000);

        Response finalOrder = getOrder(orderId);
        String finalPaymentId = finalOrder.jsonPath().getString("paymentId");

        assertThat(finalPaymentId)
                .as("Payment ID should remain the same after 4 redeliveries")
                .isEqualTo(paymentId1);

        assertThat(countPaymentsForOrder(orderId))
                .as("ONLY ONE payment despite 4 event redeliveries")
                .isEqualTo(1);

        logStep("✅ MULTIPLE REDELIVERIES HANDLED — 4 redeliveries, still 1 payment, idempotency enforced");
    }

    // ══════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════

    private String createOrder() {
        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        Response createResponse = RestAssured
                .given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .body(orderRequest)
                .when()
                .post("/api/orders");

        assertThat(createResponse.statusCode()).isEqualTo(201);
        return createResponse.jsonPath().getString("id");
    }

    private JsonNode waitForPaymentResult(String orderId) {
        Optional<JsonNode> paymentResult = paymentResultMonitor.waitForMessage(
                msg -> orderId.equals(msg.path("orderId").asText()), 20);
        assertThat(paymentResult).as("Payment result should be published").isPresent();
        return paymentResult.get();
    }

    private String buildOrderCreatedEvent(String orderId) {
        return String.format(
                "{\"eventType\":\"ORDER_CREATED\",\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":99.99,\"timestamp\":%d}",
                orderId, userId, System.currentTimeMillis());
    }

    private void publishOrderEventToKafka(String orderId, String event) {
        try {
            kafkaProducer.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, orderId, event)).get();
            kafkaProducer.flush();
        } catch (Exception e) {
            log.error("Failed to publish event", e);
            throw new RuntimeException(e);
        }
    }

    private Response getOrder(String orderId) {
        return RestAssured
                .given()
                .baseUri(context.getConfig().baseUrl())
                .header("Authorization", "Bearer " + userToken)
                .when()
                .get("/api/orders/" + orderId);
    }

    /**
     * ⚠️ See class Javadoc — 0/1 only, cannot detect a genuine multi-row
     * duplicate-payment bug. Replace with a real DB count once available.
     */
    private int countPaymentsForOrder(String orderId) {
        try {
            Response response = getOrder(orderId);
            if (response.statusCode() == 200) {
                String paymentId = response.jsonPath().getString("paymentId");
                return paymentId != null && !paymentId.isEmpty() ? 1 : 0;
            }
        } catch (Exception e) {
            log.warn("Failed to count payments: {}", e.getMessage());
        }
        return 0;
    }
}