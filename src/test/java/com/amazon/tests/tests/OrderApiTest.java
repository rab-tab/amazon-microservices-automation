package com.amazon.tests.tests;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.TestDataFactory;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

@Epic("Amazon Microservices")
@Feature("Order Management")
public class OrderApiTest extends BaseTest {

    private String customerToken;
    private String customerId;
    private String createdOrderId;
    private TestModels.ProductResponse testProduct;

    @BeforeClass
    public void setup() {
        logStep("Setting up customer and product for order tests");

        // Create customer
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        customerToken = customerAuth.getAccessToken();
        customerId = customerAuth.getUser().getId();

        // Create a seller and product
        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        String sellerId = sellerAuth.getUser().getId();

        Response productResponse = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerId)
                .body(TestDataFactory.createProductWithPrice(29.99))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();

        testProduct = productResponse.as(TestModels.ProductResponse.class);
        logStep("Test product created: " + testProduct.getId());
    }

    @Test(priority = 1)
    @Story("Create Order")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify customer can create a new order")
    public void testCreateOrder() {
        TestModels.CreateOrderRequest orderRequest = TestDataFactory.createOrderRequest(
                testProduct.getId(),
                testProduct.getName(),
                testProduct.getPrice()
        );

        logStep("Creating order for customer: " + customerId);

        Response response = given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .body(orderRequest)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("userId", equalTo(customerId))
                .body("status", equalTo("PENDING"))
                .body("items", hasSize(1))
                .body("items[0].productId", equalTo(testProduct.getId()))
                .body("totalAmount", notNullValue())
                .extract().response();

        TestModels.OrderResponse order = response.as(TestModels.OrderResponse.class);
        assertThat(order.getId()).isNotBlank();
        assertThat(order.getStatus()).isEqualTo("PENDING");
        assertThat(order.getItems()).hasSize(1);

        createdOrderId = order.getId();
        logStep("Order created: " + createdOrderId);
    }

    @Test(priority = 2)
    @Story("Create Order")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify order creation fails with empty items")
    public void testCreateOrderWithEmptyItems() {
        TestModels.CreateOrderRequest emptyOrder = TestModels.CreateOrderRequest.builder()
                .items(List.of())
                .shippingAddress("123 Test St")
                .build();

        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .body(emptyOrder)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(400);
    }

    @Test(priority = 3)
    @Story("Create Order")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify order creation fails without shipping address")
    public void testCreateOrderWithoutShippingAddress() {
        TestModels.CreateOrderRequest noAddress = TestModels.CreateOrderRequest.builder()
                .items(List.of(
                        TestModels.OrderItemRequest.builder()
                                .productId(testProduct.getId())
                                .productName(testProduct.getName())
                                .quantity(1)
                                .unitPrice(testProduct.getPrice())
                                .build()
                ))
                .build();

        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .body(noAddress)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(400)
                .body("validationErrors.shippingAddress", notNullValue());
    }

    @Test(priority = 4)
    @Story("Create Order")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify order creation fails with zero quantity")
    public void testCreateOrderWithZeroQuantity() {
        TestModels.CreateOrderRequest zeroQty = TestModels.CreateOrderRequest.builder()
                .items(List.of(
                        TestModels.OrderItemRequest.builder()
                                .productId(testProduct.getId())
                                .productName(testProduct.getName())
                                .quantity(0)  // Invalid
                                .unitPrice(testProduct.getPrice())
                                .build()
                ))
                .shippingAddress("123 Test St")
                .build();

        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .body(zeroQty)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(400);
    }

    @Test(priority = 5, dependsOnMethods = "testCreateOrder")
    @Story("Get Order")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify order can be retrieved by ID")
    public void testGetOrderById() {
        logStep("Fetching order: " + createdOrderId);

        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .pathParam("id", createdOrderId)
                .when()
                .get("/api/v1/orders/{id}")
                .then()
                .statusCode(200)
                .body("id", equalTo(createdOrderId))
                .body("userId", equalTo(customerId))
                .body("items", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test(priority = 6)
    @Story("Get Order")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify 404 returned for non-existent order")
    public void testGetNonExistentOrder() {
        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .pathParam("id", "00000000-0000-0000-0000-000000000000")
                .when()
                .get("/api/v1/orders/{id}")
                .then()
                .statusCode(404);
    }

    @Test(priority = 7, dependsOnMethods = "testCreateOrder")
    @Story("Get User Orders")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify user can retrieve all their orders")
    public void testGetUserOrders() {
        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .pathParam("userId", customerId)
                .queryParam("page", 0)
                .queryParam("size", 10)
                .when()
                .get("/api/v1/orders/user/{userId}")
                .then()
                .statusCode(200)
                .body("orders", notNullValue())
                .body("totalElements", greaterThanOrEqualTo(1));
    }

    @Test(priority = 8)
    @Story("Create Multi-Item Order")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify customer can create an order with multiple items")
    public void testCreateMultiItemOrder() {
        // Create second product
        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResponse = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(49.99))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        TestModels.ProductResponse secondProduct = productResponse.as(TestModels.ProductResponse.class);

        TestModels.CreateOrderRequest multiItemOrder = TestDataFactory.createMultiItemOrderRequest(
                List.of(
                        TestModels.OrderItemRequest.builder()
                                .productId(testProduct.getId())
                                .productName(testProduct.getName())
                                .quantity(2)
                                .unitPrice(testProduct.getPrice())
                                .build(),
                        TestModels.OrderItemRequest.builder()
                                .productId(secondProduct.getId())
                                .productName(secondProduct.getName())
                                .quantity(1)
                                .unitPrice(secondProduct.getPrice())
                                .build()
                )
        );

        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .body(multiItemOrder)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .body("items", hasSize(2))
                .body("totalAmount", notNullValue());
    }

    @Test(priority = 9, dependsOnMethods = "testCreateOrder")
    @Story("Cancel Order")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify customer can cancel a pending order")
    public void testCancelOrder() {
        // Create a fresh order to cancel
        TestModels.CreateOrderRequest orderRequest = TestDataFactory.createOrderRequest(
                testProduct.getId(), testProduct.getName(), testProduct.getPrice());

        Response createResponse = given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .body(orderRequest)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .extract().response();

        String newOrderId = createResponse.jsonPath().getString("id");

        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .pathParam("id", newOrderId)
                .when()
                .patch("/api/v1/orders/{id}/cancel")
                .then()
                .statusCode(200)
                .body("status", equalTo("CANCELLED"));
    }

    @Test(priority = 10)
    @Story("Total Amount Calculation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify total amount is calculated correctly for an order")
    public void testOrderTotalAmountCalculation() {
        BigDecimal unitPrice = BigDecimal.valueOf(25.00);
        int quantity = 4;
        BigDecimal expectedTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));

        TestModels.CreateOrderRequest orderRequest = TestModels.CreateOrderRequest.builder()
                .items(List.of(
                        TestModels.OrderItemRequest.builder()
                                .productId(testProduct.getId())
                                .productName(testProduct.getName())
                                .quantity(quantity)
                                .unitPrice(unitPrice)
                                .build()
                ))
                .shippingAddress("789 Math Ave")
                .build();

        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .body(orderRequest)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .body("totalAmount", equalTo(expectedTotal.floatValue()));
    }
}
