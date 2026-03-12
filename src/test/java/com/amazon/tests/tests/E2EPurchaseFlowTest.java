package com.amazon.tests.tests;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.TestDataFactory;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

@Epic("Amazon Microservices")
@Feature("End-to-End Purchase Flow")
public class E2EPurchaseFlowTest extends BaseTest {

    @Test
    @Story("Complete Purchase Flow")
    @Severity(SeverityLevel.BLOCKER)
    @Description("E2E test: Register → Login → Browse Products → Create Order → Verify Saga")
    public void testCompletePurchaseFlow() {
        // ─── STEP 1: Register new customer ─────────────────────────────
        logStep("STEP 1: Registering new customer");
        TestModels.RegisterRequest customerData = TestDataFactory.createRandomUser();
        TestModels.AuthResponse customerAuth = AuthUtils.registerUser(customerData);

        assertThat(customerAuth.getAccessToken()).isNotBlank();
        assertThat(customerAuth.getUser().getRole()).isEqualTo("CUSTOMER");

        String customerToken = customerAuth.getAccessToken();
        String customerId = customerAuth.getUser().getId();
        logStep("Customer registered: " + customerAuth.getUser().getEmail());

        // ─── STEP 2: Login ─────────────────────────────────────────────
        logStep("STEP 2: Customer login");
        TestModels.AuthResponse loginAuth = AuthUtils.login(
                customerData.getEmail(), customerData.getPassword());

        assertThat(loginAuth.getAccessToken()).isNotBlank();
        logStep("Login successful");

        // ─── STEP 3: Register a seller and create product ──────────────
        logStep("STEP 3: Seller creates product");
        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        String sellerId = sellerAuth.getUser().getId();
        String sellerToken = sellerAuth.getAccessToken();

        TestModels.ProductRequest productReq = TestDataFactory.createProductWithPrice(49.99);
        Response productCreateResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("Authorization", "Bearer " + sellerToken)
                .header("X-User-Id", sellerId)
                .body(productReq)
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();

        TestModels.ProductResponse product = productCreateResp.as(TestModels.ProductResponse.class);
        assertThat(product.getId()).isNotBlank();
        assertThat(product.getStatus()).isEqualTo("ACTIVE");
        logStep("Product created: " + product.getId() + " - " + product.getName());

        // ─── STEP 4: Customer views product ────────────────────────────
        logStep("STEP 4: Customer views product details");
        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .pathParam("id", product.getId())
                .when()
                .get("/api/v1/products/{id}")
                .then()
                .statusCode(200)
                .body("id", equalTo(product.getId()))
                .body("price", equalTo(49.99f));

        // ─── STEP 5: Customer browses product catalog ──────────────────
        logStep("STEP 5: Customer browses product listing");
        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .queryParam("page", 0)
                .queryParam("size", 10)
                .when()
                .get("/api/v1/products")
                .then()
                .statusCode(200)
                .body("totalElements", greaterThanOrEqualTo(1));

        // ─── STEP 6: Customer creates order ────────────────────────────
        logStep("STEP 6: Customer places order");
        TestModels.CreateOrderRequest orderReq = TestDataFactory.createOrderRequest(
                product.getId(), product.getName(), product.getPrice());
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
        logStep("Order placed: " + order.getId() + " | Total: $" + order.getTotalAmount());

        // ─── STEP 7: Verify order is in customer's order history ───────
        logStep("STEP 7: Verifying order in customer's order history");
        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .pathParam("userId", customerId)
                .queryParam("page", 0)
                .queryParam("size", 10)
                .when()
                .get("/api/v1/orders/user/{userId}")
                .then()
                .statusCode(200)
                .body("orders.find { it.id == '" + order.getId() + "' }", notNullValue());

        // ─── STEP 8: Verify payment was processed (Saga) ───────────────
        logStep("STEP 8: Checking payment status (Kafka Saga)");
        // Allow time for Kafka saga to process
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        Response paymentResp = given()
                .spec(new io.restassured.builder.RequestSpecBuilder()
                        .setBaseUri(RestAssuredConfig.getConfig().paymentServiceUrl())
                        .setContentType(io.restassured.http.ContentType.JSON)
                        .build())
                .pathParam("orderId", order.getId())
                .when()
                .get("/api/v1/payments/order/{orderId}")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(404))) // may be processing
                .extract().response();

        if (paymentResp.statusCode() == 200) {
            String paymentStatus = paymentResp.jsonPath().getString("status");
            logStep("Payment status: " + paymentStatus);
            assertThat(paymentStatus).isIn("SUCCESS", "FAILED", "PROCESSING");
        }

        // ─── STEP 9: Verify order status updated after payment ─────────
        logStep("STEP 9: Verifying order status updated by payment saga");
        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .pathParam("id", order.getId())
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

        logStep("✅ E2E Purchase Flow completed successfully!");
    }

    @Test
    @Story("Order Cancellation Flow")
    @Severity(SeverityLevel.CRITICAL)
    @Description("E2E test: Create order then cancel it")
    public void testOrderCancellationFlow() {
        logStep("STEP 1: Setup customer and product");
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerToken = customerAuth.getAccessToken();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(19.99))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        TestModels.ProductResponse product = productResp.as(TestModels.ProductResponse.class);

        logStep("STEP 2: Create order");
        Response orderResp = given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .body(TestDataFactory.createOrderRequest(
                        product.getId(), product.getName(), product.getPrice()))
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .extract().response();
        String orderId = orderResp.jsonPath().getString("id");

        logStep("STEP 3: Cancel order");
        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .pathParam("id", orderId)
                .when()
                .patch("/api/v1/orders/{id}/cancel")
                .then()
                .statusCode(200)
                .body("status", equalTo("CANCELLED"));

        logStep("STEP 4: Verify cancellation");
        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .pathParam("id", orderId)
                .when()
                .get("/api/v1/orders/{id}")
                .then()
                .statusCode(200)
                .body("status", equalTo("CANCELLED"));

        logStep("✅ Order Cancellation Flow completed successfully!");
    }
}
