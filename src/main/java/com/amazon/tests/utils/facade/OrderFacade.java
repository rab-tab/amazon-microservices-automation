package com.amazon.tests.utils.facade;

import com.amazon.tests.config.restAsssured.old.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.workflows.PurchaseResult;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

@Slf4j
public class OrderFacade {

    public TestModels.OrderResponse createOrder(PurchaseResult purchase) {

        TestModels.CreateOrderRequest request = buildOrderRequest(
                purchase.getProducts());

        Response orderResp = given()
                .spec(RestAssuredConfig.getOrderServiceSpec(
                        purchase.getCustomerAuth().getAccessToken()))
                .header("X-User-Id",
                        purchase.getCustomerAuth().getUser().getId())
                .body(request)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .body("status", equalTo("PENDING"))
                .body("userId", equalTo(
                        purchase.getCustomerAuth().getUser().getId()))
                .extract()
                .response();

        TestModels.OrderResponse order =
                orderResp.as(TestModels.OrderResponse.class);

        assertThat(order.getId()).isNotBlank();
        assertThat(order.getTotalAmount()).isPositive();

        log.info("Order placed: {} | Total: ${}",
                order.getId(),
                order.getTotalAmount());

        return order;
    }

    private TestModels.CreateOrderRequest buildOrderRequest(
            List<TestModels.ProductResponse> products) {

        List<TestModels.OrderItemRequest> items = products.stream()
                .map(product ->
                        TestModels.OrderItemRequest.builder()
                                .productId(product.getId())
                                .productName(product.getName())
                                .unitPrice(product.getPrice())
                                .quantity(1)
                                .build())
                .toList();

        return TestModels.CreateOrderRequest.builder()
                .items(items)
                .shippingAddress("123 Amazon Way, Seattle, WA 98101")
                .build();
    }

    public TestModels.OrderResponse getOrder(String customerToken, String customerId, String orderId) {
        Response response= (Response) given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .pathParam("userId", customerId)
                .queryParam("page", 0)
                .queryParam("size", 10)
                .when()
                .get("/api/v1/orders/user/{userId}")
                .then()
                .statusCode(200);
                //.body("orders.find { it.id == '" + orderId + "' }", notNullValue());
        return response.as(TestModels.OrderResponse.class);

    }
    public void cancelOrder(String customerToken,
                            String customerId,
                            String orderId) {

        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .pathParam("id", orderId)
                .when()
                .patch("/api/v1/orders/{id}/cancel")
                .then()
                .statusCode(200);
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
