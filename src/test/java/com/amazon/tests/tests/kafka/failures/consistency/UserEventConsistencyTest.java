package com.amazon.tests.tests.kafka.failures.consistency;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.DatabaseValidator;
import com.amazon.tests.utils.KafkaTestConsumer;
import com.amazon.tests.utils.RedisValidator;
import com.amazon.tests.utils.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@Epic("Amazon Microservices")
@Feature("Kafka Event Consistency")
public class UserEventConsistencyTest extends BaseTest {

    private static final int KAFKA_WAIT_SECONDS = 10;

    @Test // PASSED
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
                    .then().log().all()
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
            /*given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .when()
                    .get("/api/v1/users/{id}", userId)
                    .then()
                    .statusCode(404);*/ // via api call

            // 4️⃣ Direct DB validation using DatabaseValidator
            boolean dbExists = DatabaseValidator.userExistsById(userId);
            assertThat(dbExists)
                    .as("DB should NOT contain user due to rollback")
                    .isFalse();

            // 🔥 Redis validation (IMPORTANT)
            boolean cacheExists = RedisValidator.userCacheExists(userId);

            assertThat(cacheExists)
                    .as("Redis should contain stale cache due to rollback")
                    .isTrue();

            logStep("🚨 Ghost state confirmed: Kafka + Redis present, DB missing");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 1. Kafka Failure AFTER DB Commit
    // ─────────────────────────────────────────────────────────────
    @Test
    @Story("Kafka failure after DB commit")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify system inconsistency when DB commit succeeds but Kafka publish fails")
    public void testKafkaFailureAfterDbCommit() {

        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            Response response = given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .header("X-Fault", "kafka-down") // simulate Kafka failure
                    .body(user)
                    .when()
                    .post("/api/v1/auth/register")
                    .then()
                    .extract().response();

            // DB should still succeed (depending on implementation)
            assertThat(response.statusCode()).isIn(201, 500);

            // Extract userId if available
            String userId = response.jsonPath().getString("user.id");

            // Kafka SHOULD NOT have event (inconsistency)
            Optional<JsonNode> event = consumer.waitForMessage(
                    node -> node.has("email")
                            && user.getEmail().equals(node.get("email").asText()),
                    5
            );

            assertThat(event)
                    .as("Kafka should NOT have event when publish fails")
                    .isNotPresent();

            // DB validation (if userId exists)
            if (userId != null) {
                given()
                        .spec(RestAssuredConfig.getUserServiceSpec())
                        .when()
                        .get("/api/v1/users/{id}", userId)
                        .then()
                        .statusCode(200);
            }

            logStep("🚨 DB present but Kafka missing → inconsistency exposed");
        }
    }
    // ─────────────────────────────────────────────────────────────
    // 2. Redis Failure Scenario
    // ─────────────────────────────────────────────────────────────
    @Test
    @Story("Redis failure during user registration")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify system behavior when Redis cache write fails but DB + Kafka succeed")
    public void testRedisFailureDuringRegistration() {

        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            Response response = given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .header("X-Fault", "redis-down")
                    .body(user)
                    .when()
                    .post("/api/v1/auth/register")
                    .then()
                    .statusCode(201)
                    .extract().response();

            String userId = response.jsonPath().getString("user.id");

            // Kafka should still have event
            Optional<JsonNode> event = consumer.waitForMessage(
                    node -> user.getEmail().equals(node.get("email").asText()),
                    KAFKA_WAIT_SECONDS
            );

            assertThat(event).isPresent();

            // Redis should NOT have cache
            boolean cacheExists = RedisValidator.userCacheExists(userId);

            assertThat(cacheExists)
                    .as("Redis should fail but not affect correctness")
                    .isFalse();

            logStep("✅ Kafka + DB success, Redis failure tolerated");
        }
    }

}