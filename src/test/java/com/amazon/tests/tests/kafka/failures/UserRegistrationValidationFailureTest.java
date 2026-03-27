package com.amazon.tests.tests.kafka.failures;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.tests.BaseTest;
import com.amazon.tests.utils.KafkaTestConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.*;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@Epic("Amazon Microservices")
@Feature("Kafka Event Verification")
public class UserRegistrationValidationFailureTest extends BaseTest {
    private static final int KAFKA_WAIT_SECONDS = 15;

    @Test(priority = 1)
    @Story("user.registered topic")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify USER_REGISTERED event is not published to Kafka when a user registers with invalid details")
    public void testUserRegisteredEventNotPublishedOnInvalidRequest() {
        TestModels.RegisterRequest invalidUser = new TestModels.RegisterRequest();
        invalidUser.setEmail("invalid-email"); // malformed
        invalidUser.setFirstName(null);        // missing required field

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            given()
                    .spec(RestAssuredConfig.getUserServiceSpec())
                    .body(invalidUser)
                    .when()
                    .post("/api/v1/auth/register")
                    .then()
                    .statusCode(400);

            Optional<JsonNode> event = consumer.waitForMessage(
                    node -> node.has("email") &&
                            "invalid-email".equals(node.get("email").asText()),
                    5
            );

            assertThat(event).isNotPresent(); // 🔥 critical assertion
        }
    }
}
