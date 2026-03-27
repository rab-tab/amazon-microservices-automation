package com.amazon.tests.tests.kafka.failures.resilience;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.KafkaTestConsumer;
import com.amazon.tests.utils.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@Epic("Amazon Microservices")
@Feature("Kafka Event Resilience")
public class UserEventResilienceTest extends BaseTest {

    private static final int KAFKA_WAIT_SECONDS = 10;
    @Test
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
}
