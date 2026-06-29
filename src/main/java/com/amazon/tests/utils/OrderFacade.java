package com.amazon.tests.utils;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

@Slf4j
public class OrderFacade {

    public TestModels.OrderResponse createOrder(String proudctId, String productName, BigDecimal productPrice, String customerToken, String customerId) {
        TestModels.CreateOrderRequest orderReq = TestDataFactory.createOrderRequest(
                proudctId, productName, productPrice);
        orderReq.setShippingAddress("123 Amazon Way, Seattle, WA 98101");

        Response orderResp = given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .body(orderReq)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .body("status", equalTo("PENDING"))
                .body("userId", equalTo(customerId))
                .extract().response();

        TestModels.OrderResponse order = orderResp.as(TestModels.OrderResponse.class);
        assertThat(order.getId()).isNotBlank();
        assertThat(order.getTotalAmount()).isPositive();
        log.info("Order placed: " + order.getId() + " | Total: $" + order.getTotalAmount());
        return order;


    }

    public void getOrder(String customerToken, String customerId, String orderId) {
        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .pathParam("userId", customerId)
                .queryParam("page", 0)
                .queryParam("size", 10)
                .when()
                .get("/api/v1/orders/user/{userId}")
                .then()
                .statusCode(200)
                .body("orders.find { it.id == '" + orderId + "' }", notNullValue());

    }

    public void verofyOrderStatus(String orderId, String customerToken) {
        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .pathParam("id", orderId)
                .when()
                .get("/api/v1/orders/{id}")
                .then()
                .statusCode(200)
                .body("status", anyOf(
                        equalTo("PENDING"),
                        equalTo("CONFIRMED"),
                        equalTo("PAYMENT_FAILED"),
                        equalTo("PAYMENT_PROCESSING")
                ));

    }
}
