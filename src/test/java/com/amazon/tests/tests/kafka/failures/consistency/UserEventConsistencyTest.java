package com.amazon.tests.tests.kafka.failures.consistency;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.KafkaTestConsumer;
import com.amazon.tests.utils.RedisValidator;
import com.amazon.tests.utils.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@Epic("Amazon Microservices")
@Feature("Kafka Event Consistency")
public class UserEventConsistencyTest extends BaseTest {

    private static final int KAFKA_WAIT_SECONDS = 10;

    @Test
    @Story("user.registered topic — DB failure consistency")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify ghost event is produced when DB transaction fails after Kafka publish, causing inconsistency between DB and Kafka")
    public void testGhostEventOnDbFailure() {

        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();

        long testStartTime = System.currentTimeMillis();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            // 🔥 Trigger failure AFTER Kafka + Redis
            given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .header("X-Fault", "fail-after-kafka")
                    .body(user)
                    .when()
                    .post("/api/v1/auth/register")
                    .then()
                    .statusCode(500);

            logStep("Triggered simulated failure after Kafka publish");

            // 🔥 Kafka SHOULD still contain event (this is the bug)
            Optional<JsonNode> event = consumer.waitForMessage(
                    node -> node.has("email")
                            && user.getEmail().equals(node.get("email").asText()),
                    KAFKA_WAIT_SECONDS
            );

            // 🔥 Kafka validation
            assertThat(event)
                    .as("Kafka should contain ghost event even though DB failed")
                    .isPresent();

            String userId = event.get().get("userId").asText();

            // 🔥 DB validation
            given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .when()
                    .get("/api/v1/users/{id}", userId)
                    .then()
                    .statusCode(404);

            // 🔥 Redis validation (IMPORTANT)
            boolean cacheExists = RedisValidator.userCacheExists(userId);

            assertThat(cacheExists)
                    .as("Redis should contain stale cache due to rollback")
                    .isTrue();

            logStep("🚨 Ghost state confirmed: Kafka + Redis present, DB missing");
        }
    }
}