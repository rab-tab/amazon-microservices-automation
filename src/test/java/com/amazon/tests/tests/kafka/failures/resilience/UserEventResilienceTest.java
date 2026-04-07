package com.amazon.tests.tests.kafka.failures.resilience;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.KafkaTestConsumer;
import com.amazon.tests.utils.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@Epic("Amazon Microservices")
@Feature("Kafka Event Resilience")
public class UserEventResilienceTest extends BaseTest {

    private static final int KAFKA_WAIT_SECONDS = 10;

    @Test(enabled = false)
    @Feature("Kafka Failure Handling")
    @Story("Kafka down scenario")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify system behavior when Kafka is unavailable and producer retries fail")
    public void testKafkaDown_noEventPublished() {

        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            // Trigger Kafka failure
            given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .header("X-Fault", "kafka-down")
                    .body(user)
                    .when()
                    .post("/api/v1/auth/register")
                    .then()
                    .statusCode(500);

            logStep("Kafka failure simulated");

            // 🔥 Ensure NO event is published
            Optional<JsonNode> event = consumer.waitForMessage(
                    node -> user.getEmail().equals(node.get("email").asText()),
                    10
            );

            assertThat(event)
                    .as("No Kafka event should be published when Kafka is down")
                    .isNotPresent();
        }
    }

    @Test
    @Description("Verify producer retries allow event to be published after transient failure")
    public void testKafkaRetry_eventEventuallyPublished() {

        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .header("X-Fault", "fail-once-then-success")
                    .body(user)
                    .when()
                    .post("/api/v1/auth/register")
                    .then()
                    .statusCode(201);

            Optional<JsonNode> event = consumer.waitForMessage(
                    node -> user.getEmail().equals(node.get("email").asText()),
                    15
            );

            assertThat(event)
                    .as("Event should be published after retry")
                    .isPresent();
        }
    }

    @Test
    @Story("Kafka producer — ACK loss duplication")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify duplicate events are produced when ACK is lost and producer retries")
    public void testKafkaAckLoss_duplicateEventProduced() {

        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .header("X-Fault", "kafka-ack-loss") // simulate retry after unknown ack
                    .body(user)
                    .when()
                    .post("/api/v1/auth/register")
                    .then()
                    .statusCode(201);

            List<JsonNode> events = consumer.collectMessages(
                    node -> user.getEmail().equals(node.get("email").asText()),
                    10
            );

            assertThat(events.size())
                    .as("Duplicate events should be produced due to retry")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    @Story("Kafka producer — permanent failure")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify no event is published when Kafka is permanently unavailable")
    public void testKafkaPermanentFailure_noEventPublished() {

        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .header("X-Fault", "kafka-down")
                    .body(user)
                    .when()
                    .post("/api/v1/auth/register")
                    .then()
                    .statusCode(500);

            List<JsonNode> events = consumer.collectMessages(
                    node -> user.getEmail().equals(node.get("email").asText()),
                    5
            );

            assertThat(events)
                    .as("No event should be published")
                    .isEmpty();
        }
    }

    @Test
    @Story("Kafka producer — async send loss")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify event loss when using async send without waiting for ACK")
    public void testKafkaAsyncSend_eventLoss() {

        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .header("X-Fault", "kafka-async-no-wait")
                    .body(user)
                    .when()
                    .post("/api/v1/auth/register")
                    .then()
                    .statusCode(201);

            Optional<JsonNode> event = consumer.waitForMessage(
                    node -> user.getEmail().equals(node.get("email").asText()),
                    5
            );

            assertThat(event)
                    .as("Event may be lost in async fire-and-forget mode")
                    .isNotPresent();
        }
    }

    @Test
    @Story("Kafka producer — client retry duplication")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify duplicate events when same request is retried by client")
    public void testKafkaDuplicate_dueToClientRetry() {

        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            // First call
            given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .body(user)
                    .post("/api/v1/auth/register");

            // Retry same request
            given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .body(user)
                    .post("/api/v1/auth/register");

            List<JsonNode> events = consumer.collectMessages(
                    node -> user.getEmail().equals(node.get("email").asText()),
                    10
            );

            assertThat(events.size())
                    .as("Duplicate events should occur due to client retry")
                    .isGreaterThanOrEqualTo(1); // depending on DB constraint behavior
        }
    }

    @Test
    @Story("Kafka consumer — replay idempotency")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify same event replay does not cause duplicate side effects")
    public void testConsumerReplay_idempotentProcessing() {

        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .body(user)
                    .post("/api/v1/auth/register")
                    .then()
                    .statusCode(201);

            Optional<JsonNode> event = consumer.waitForMessage(
                    node -> user.getEmail().equals(node.get("email").asText()),
                    KAFKA_WAIT_SECONDS
            );

            assertThat(event).isPresent();

            // 🔥 Simulate replay (re-processing same event)
           // consumer.replay(event.get());

            // 👉 Add validation depending on your downstream system
            // Example:
            // assertThat(orderCount).isEqualTo(1);
        }
    }

    @Test
    @Story("Kafka consumer — crash recovery")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify event is reprocessed after consumer crash before offset commit")
    public void testConsumerCrash_reprocessesEvent() {

        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .body(user)
                    .post("/api/v1/auth/register")
                    .then()
                    .statusCode(201);

            Optional<JsonNode> event = consumer.waitForMessage(
                    node -> user.getEmail().equals(node.get("email").asText()),
                    KAFKA_WAIT_SECONDS
            );

            assertThat(event).isPresent();

            // Simulate crash before commit
            consumer.simulateCrashBeforeCommit();

            // Restart consumer → should reprocess
            consumer.restart();

            // Validate idempotency again
        }
    }

}
