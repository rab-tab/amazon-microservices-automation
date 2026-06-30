package com.amazon.tests.config;

import com.amazon.tests.models.TestModels;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

public class OrderApiClient {
    ObjectMapper objectMapper=new ObjectMapper();

    public Response sendOrderRequestWithFault(
            String userToken,
            String idempotencyKey,
            TestModels.CreateOrderRequest orderRequest,
            String faultType,String userId,String baseUri) throws Exception {

        String requestBody = objectMapper.writeValueAsString(orderRequest);

        return RestAssured
                .given()
                .baseUri(baseUri)
                .header("Authorization", "Bearer " + userToken)
                .header("X-User-Id", userId)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Fault", faultType)
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/orders");
    }
}
