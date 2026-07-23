package com.amazon.tests.regression.kafka.orders.publishing.failures;



import com.amazon.tests.BaseTest;
import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingException;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Producer Topic & Partition Failures - Fast Tests
 *
 * Strategy: Header injection (application-level simulation)
 *
 * Tests:
 * - Topic does not exist
 * - Invalid partition key
 * - Partition leader unavailable
 * - Topic authorization failures
 *
 * Run frequency: Every commit
 * Execution time: ~3 seconds
 */
@Slf4j
@Epic("Kafka Producer")
@Feature("Topic & Partition Failures")
public class ProducerTopicFailuresTest extends BaseTest {

    private TestModels.UserResponse user;
    private TestModels.ProductResponse product;
    private String userToken;

    @BeforeMethod
    public void setup() throws SeedingException {
        user = UserSeeder.builder(context).count(1).build().seed().getFirst();
        userToken = context.getCached("user_token_" + user.getId(), String.class);
        product = ProductSeeder.builder(context).count(1).highStock().build().seed().getFirst();
        waitForDataPropagation(1000);
    }

    @Test(description = "Topic does not exist - auto-create disabled")
    @Story("Topic Existence")
    @Severity(SeverityLevel.CRITICAL)
    public void test09_TopicDoesNotExist_OrderCreationFails() throws Exception {
        logStep("TEST: Topic does not exist");

        Response response = sendOrderRequestWithFault("topic-not-exist");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.jsonPath().getString("message"))
                .containsAnyOf("topic", "does not exist", "unknown topic");

        logStep("✅ Topic existence check handled");
    }

    @Test(description = "Invalid partition key")
    @Story("Partition Keys")
    @Severity(SeverityLevel.NORMAL)
    public void test11_InvalidPartitionKey_Fails() throws Exception {
        logStep("TEST: Invalid partition key");

        Response response = sendOrderRequestWithFault("invalid-partition-key");

        assertThat(response.statusCode()).isIn(400, 500);

        logStep("✅ Invalid partition key rejected");
    }

    @Test(description = "Topic authorization failure")
    @Story("Authorization")
    @Severity(SeverityLevel.CRITICAL)
    public void test22_TopicAuthorizationFailure() throws Exception {
        logStep("TEST: Topic authorization denied");

        Response response = sendOrderRequestWithFault("topic-auth-failure");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.jsonPath().getString("message"))
                .containsAnyOf("authorization", "access denied");

        logStep("✅ Authorization failure handled");
    }

    private Response sendOrderRequestWithFault(String faultType) throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.CreateOrderRequest orderRequest = OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(product, 1)
                .build();

        String requestBody = objectMapper.writeValueAsString(orderRequest);

        return RestAssured
                .given()
                .baseUri("http://localhost:8083")
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-User-Id", user.getId().toString())
                .header("X-Fault", faultType)
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/orders");
    }
}
