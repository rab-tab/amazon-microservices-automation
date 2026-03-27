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

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;

@Epic("Amazon Microservices")
@Feature("Kafka Event Verification")

public class UserEventIdempotencyTest extends BaseTest {
    private static final int KAFKA_WAIT_SECONDS = 15;

    @Test(priority = 1)
    @Story("user.registered topic")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify that duplicate user registration requests do not produce multiple USER_REGISTERED events, ensuring idempotent event publishing and preventing duplicate downstream processing")
    public void testUserRegisteredEventNotDuplicated() {
        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();

        try (KafkaTestConsumer consumer = new KafkaTestConsumer("user.registered")) {

            // First call
            given().spec(RestAssuredConfig.getUserServiceSpec()).body(user).post("/register").then().statusCode(201);

            // Duplicate call
            Response secondResponse=given().spec(RestAssuredConfig.getUserServiceSpec()).body(user).post("/register");
            assertThat("Duplicate register user api call produces error",secondResponse.statusCode()==200);
                     // .isIn(200, 409); // depending on design

            List<JsonNode> events = consumer.collectMessages(
                    node -> user.getEmail().equals(node.get("email").asText()),
                    10
            );

        assertThat("Duplicate event not published with same user details",events.size()==1);

        }
    }


}