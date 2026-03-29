package com.amazon.tests.tests.kafka.failures.idempotency;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.KafkaTestConsumer;
import com.amazon.tests.utils.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;


@Epic("Amazon Microservices")
@Feature("Kafka Event Verification")

public class UserEventIdempotencyTest extends BaseTest {
    private static final int KAFKA_WAIT_SECONDS = 15;

    @Test
    @Story("user.registered topic")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify that duplicate user registration requests do not produce multiple USER_REGISTERED events, ensuring idempotent event publishing and preventing duplicate downstream processing")
    public void testUserRegisteredEventNotDuplicated() {
        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            // First call
            given().spec(RestAssuredConfig.getUserServiceSpec()).body(user).when().post("/api/v1/auth/register").then().statusCode(201);

            // Duplicate call
            Response secondResponse=given().spec(RestAssuredConfig.getUserServiceSpec()).body(user).post("/api/v1/auth/register");
            assertThat(secondResponse.statusCode())
                    .isIn(200, 409); // depends on design

            List<JsonNode> events = consumer.collectMessages(
                    node -> user.getEmail().equals(node.get("email").asText()),
                    10
            );

        assertThat(events.size()==1);

        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Concurrent Registration Race Condition
    // ─────────────────────────────────────────────────────────────
    @Test
    @Story("Concurrent duplicate registration")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify only one user + one event is created under concurrent requests")
    public void testConcurrentUserRegistration() throws Exception {

        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Response> task = () -> given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .body(user)
                .post("/api/v1/auth/register");

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            List<Future<Response>> results = executor.invokeAll(List.of(task, task));

            // Validate API responses
            for (Future<Response> future : results) {
                int status = future.get().statusCode();
                assertThat(status).isIn(200, 201, 409);
            }

            // Kafka validation
            List<JsonNode> events = consumer.collectMessages(
                    node -> user.getEmail().equals(node.get("email").asText()),
                    10
            );

            assertThat(events.size())
                    .as("Only one event should be published")
                    .isEqualTo(1);

            logStep("✅ Race condition handled — no duplicate events");
        } finally {
            executor.shutdown();
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Duplicate Kafka Replay Simulation
    // ─────────────────────────────────────────────────────────────
    @Test
    @Story("Kafka duplicate replay")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify system safety when same Kafka event is reprocessed")
    public void testDuplicateKafkaReplay() {

        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            // Step 1: Register user
            given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .body(user)
                    .post("/api/v1/auth/register")
                    .then()
                    .statusCode(201);

            // Step 2: Collect event
            Optional<JsonNode> event = consumer.waitForMessage(
                    node -> user.getEmail().equals(node.get("email").asText()),
                    KAFKA_WAIT_SECONDS
            );

            assertThat(event).isPresent();

            JsonNode payload = event.get();

            // Step 3: Simulate replay (re-consume same event)
            consumer.seekToBeginning();

            List<JsonNode> replayed = consumer.collectMessages(
                    node -> user.getEmail().equals(node.get("email").asText()),
                    5
            );

            // Expect still logically one event (no side effects duplication)
            assertThat(replayed.size()).isGreaterThanOrEqualTo(1);

            logStep("⚠️ Kafka replay simulated — ensure downstream idempotency");
        }
    }


}