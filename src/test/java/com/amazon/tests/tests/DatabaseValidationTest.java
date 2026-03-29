package com.amazon.tests.tests;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.DatabaseValidator;
import com.amazon.tests.utils.RedisValidator;
import com.amazon.tests.utils.TestDataFactory;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.awaitility.Awaitility;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database Validation Tests.
 *
 * These tests call REST APIs and then directly query PostgreSQL
 * to verify that:
 *  - data was persisted correctly
 *  - status transitions are reflected in the DB
 *  - relational integrity (order items, payments) is maintained
 *  - soft deletes / status changes work correctly
 *  - Redis cache is populated after API calls
 */
@Epic("Amazon Microservices")
@Feature("Database Validation")
public class DatabaseValidationTest extends BaseTest {

    // ─── User DB Tests ─────────────────────────────────────────────────

    @Test(priority = 1)
    @Story("User Persistence")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify user is correctly persisted to DB after registration")
    public void testUserPersistedToDatabase() {
        TestModels.RegisterRequest user = TestDataFactory.createRandomUser();
        logStep("Registering user and verifying DB persistence: " + user.getEmail());

        Response response = given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .body(user)
                .when()
                .post("/api/v1/auth/register")
                .then()
                .statusCode(201)
                .extract().response();

        String userId = response.jsonPath().getString("user.id");

        // ── DB Assertion ──────────────────────────────────────────
        assertThat(DatabaseValidator.userExistsById(userId))
                .as("User should exist in DB after registration")
                .isTrue();

        Map<String, Object> dbUser = DatabaseValidator.getUserByEmail(user.getEmail());
        assertThat(dbUser).isNotEmpty();
        assertThat(dbUser.get("email").toString()).isEqualTo(user.getEmail());
        assertThat(dbUser.get("first_name").toString()).isEqualTo(user.getFirstName());
        assertThat(dbUser.get("last_name").toString()).isEqualTo(user.getLastName());
        assertThat(dbUser.get("username").toString()).isEqualTo(user.getUsername());
        assertThat(dbUser.get("role").toString()).isEqualTo("CUSTOMER");
        assertThat(dbUser.get("status").toString()).isEqualTo("ACTIVE");

        // Verify password is HASHED (not plain text)
        String storedPassword = dbUser.get("password").toString();
        assertThat(storedPassword).doesNotContain(user.getPassword());
        assertThat(storedPassword).startsWith("$2a$");  // BCrypt prefix

        logStep("✅ User persisted correctly to users_db for userId: " + userId);
    }

    @Test(priority = 2)
    @Story("User Persistence")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify user count increases by 1 after registration")
    public void testUserCountIncreasesAfterRegistration() {
        long countBefore = DatabaseValidator.countUsers();

        given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .body(TestDataFactory.createRandomUser())
                .when()
                .post("/api/v1/auth/register")
                .then()
                .statusCode(201);

        long countAfter = DatabaseValidator.countUsers();
        assertThat(countAfter).isEqualTo(countBefore + 1);

        logStep("✅ User count: before=" + countBefore + " after=" + countAfter);
    }

    @Test(priority = 3)
    @Story("User Status")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify user status changes to INACTIVE in DB after delete")
    public void testUserDeactivationReflectedInDatabase() {
        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();
        String userId = auth.getUser().getId();
        String token = auth.getAccessToken();

        logStep("Verifying user is ACTIVE before delete");
        assertThat(DatabaseValidator.getUserStatusById(userId)).isEqualTo("ACTIVE");

        given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", userId)
                .header("X-User-Role", "CUSTOMER")
                .pathParam("id", userId)
                .when()
                .delete("/api/v1/users/{id}")
                .then()
                .statusCode(204);

        // DB should now show INACTIVE (soft delete)
        assertThat(DatabaseValidator.getUserStatusById(userId)).isEqualTo("INACTIVE");
        logStep("✅ User soft-delete reflected correctly in DB");
    }

    // ─── Product DB Tests ──────────────────────────────────────────────

    @Test(priority = 4)
    @Story("Product Persistence")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify product is persisted to DB with correct fields")
    public void testProductPersistedToDatabase() {
        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        TestModels.ProductRequest productReq = TestDataFactory.createProductWithPrice(149.99);

        Response response = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(productReq)
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();

        String productId = response.jsonPath().getString("id");

        // ── DB Assertion ──────────────────────────────────────────
        assertThat(DatabaseValidator.productExistsById(productId))
                .as("Product should exist in DB")
                .isTrue();

        Map<String, Object> dbProduct = DatabaseValidator.getProductById(productId);
        assertThat(dbProduct).isNotEmpty();
        assertThat(dbProduct.get("name").toString()).isEqualTo(productReq.getName());
        assertThat(((Number) dbProduct.get("stock_quantity")).intValue())
                .isEqualTo(productReq.getStockQuantity());
        assertThat(dbProduct.get("status").toString()).isEqualTo("ACTIVE");
        assertThat(dbProduct.get("seller_id").toString())
                .isEqualTo(sellerAuth.getUser().getId());

        logStep("✅ Product persisted correctly for productId: " + productId);
    }

    @Test(priority = 5)
    @Story("Stock Management")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify stock quantity updates are reflected in DB immediately")
    public void testStockUpdateReflectedInDatabase() {
        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        TestModels.ProductRequest productReq = TestDataFactory.createProductWithPrice(25.00);
        productReq.setStockQuantity(100);

        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(productReq)
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        String productId = productResp.jsonPath().getString("id");

        int initialStock = DatabaseValidator.getProductStockById(productId);
        assertThat(initialStock).isEqualTo(100);

        // Update stock by -20
        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .pathParam("id", productId)
                .queryParam("quantity", -20)
                .when()
                .patch("/api/v1/products/{id}/stock")
                .then()
                .statusCode(204);

        int updatedStock = DatabaseValidator.getProductStockById(productId);
        assertThat(updatedStock).isEqualTo(80);

        logStep("✅ Stock updated: 100 → 80 in DB for productId: " + productId);
    }

    @Test(priority = 6)
    @Story("Product Soft Delete")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify soft-deleted product shows DISCONTINUED status in DB")
    public void testProductSoftDeleteInDatabase() {
        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        String sellerId = sellerAuth.getUser().getId();

        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerId)
                .body(TestDataFactory.createProductWithPrice(10.00))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        String productId = productResp.jsonPath().getString("id");

        assertThat(DatabaseValidator.getProductStatusById(productId)).isEqualTo("ACTIVE");

        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerId)
                .pathParam("id", productId)
                .when()
                .delete("/api/v1/products/{id}")
                .then()
                .statusCode(204);

        assertThat(DatabaseValidator.getProductStatusById(productId)).isEqualTo("DISCONTINUED");
        logStep("✅ Product soft-delete: status DISCONTINUED in DB for productId: " + productId);
    }

    // ─── Order DB Tests ────────────────────────────────────────────────

    @Test(priority = 7)
    @Story("Order Persistence")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify order and order items are persisted to DB correctly")
    public void testOrderAndItemsPersistedToDatabase() {
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(34.99))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        TestModels.ProductResponse product = productResp.as(TestModels.ProductResponse.class);

        Response orderResp = given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                .header("X-User-Id", customerId)
                .body(TestDataFactory.createOrderRequest(
                        product.getId(), product.getName(), product.getPrice()))
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .extract().response();
        String orderId = orderResp.jsonPath().getString("id");

        // ── Order Table Assertion ─────────────────────────────────
        assertThat(DatabaseValidator.orderExistsById(orderId)).isTrue();

        Map<String, Object> dbOrder = DatabaseValidator.getOrderById(orderId);
        assertThat(dbOrder).isNotEmpty();
        assertThat(dbOrder.get("user_id").toString()).isEqualTo(customerId);
        assertThat(dbOrder.get("status").toString()).isEqualTo("PENDING");

        // ── Order Items Table Assertion ───────────────────────────
        List<Map<String, Object>> items = DatabaseValidator.getOrderItemsByOrderId(orderId);
        assertThat(items).isNotEmpty();
        assertThat(items.get(0).get("product_id").toString()).isEqualTo(product.getId());

        logStep("✅ Order and items persisted correctly to DB for orderId: " + orderId);
    }

    @Test(priority = 8)
    @Story("Order Status DB Transition")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify order status transition to CANCELLED is persisted in DB")
    public void testOrderCancellationPersistedToDatabase() {
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(9.99))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        TestModels.ProductResponse product = productResp.as(TestModels.ProductResponse.class);

        Response orderResp = given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                .header("X-User-Id", customerId)
                .body(TestDataFactory.createOrderRequest(
                        product.getId(), product.getName(), product.getPrice()))
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .extract().response();
        String orderId = orderResp.jsonPath().getString("id");

        assertThat(DatabaseValidator.getOrderStatus(orderId)).isEqualTo("PENDING");

        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                .header("X-User-Id", customerId)
                .pathParam("id", orderId)
                .when()
                .patch("/api/v1/orders/{id}/cancel")
                .then()
                .statusCode(200);

        assertThat(DatabaseValidator.getOrderStatus(orderId)).isEqualTo("CANCELLED");
        logStep("✅ Order cancellation CANCELLED status persisted to DB for orderId: " + orderId);
    }

    @Test(priority = 9)
    @Story("Order Count per User")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify order count per user increments correctly in DB")
    public void testOrderCountPerUserInDatabase() {
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(5.99))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        TestModels.ProductResponse product = productResp.as(TestModels.ProductResponse.class);

        long countBefore = DatabaseValidator.countOrdersByUserId(customerId);

        // Create 2 orders
        for (int i = 0; i < 2; i++) {
            given()
                    .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                    .header("X-User-Id", customerId)
                    .body(TestDataFactory.createOrderRequest(
                            product.getId(), product.getName(), product.getPrice()))
                    .when()
                    .post("/api/v1/orders")
                    .then()
                    .statusCode(201);
        }

        long countAfter = DatabaseValidator.countOrdersByUserId(customerId);
        assertThat(countAfter).isEqualTo(countBefore + 2);
        logStep("✅ Order count for user: before=" + countBefore + " after=" + countAfter);
    }

    // ─── Payment DB Tests ──────────────────────────────────────────────

    @Test(priority = 10)
    @Story("Payment Saga Persistence")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify payment record is persisted in DB after Kafka saga processing")
    public void testPaymentPersistedAfterSaga() {
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(44.99))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        TestModels.ProductResponse product = productResp.as(TestModels.ProductResponse.class);

        Response orderResp = given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                .header("X-User-Id", customerId)
                .body(TestDataFactory.createOrderRequest(
                        product.getId(), product.getName(), product.getPrice()))
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .extract().response();
        String orderId = orderResp.jsonPath().getString("id");

        // Payment saga is async — poll DB until payment appears
        Awaitility.await("Payment record should appear in DB after saga")
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    assertThat(DatabaseValidator.paymentExistsForOrder(orderId)).isTrue();
                });

        Map<String, Object> payment = DatabaseValidator.getPaymentByOrderId(orderId);
        assertThat(payment).isNotEmpty();
        assertThat(payment.get("order_id").toString()).isEqualTo(orderId);
        assertThat(payment.get("user_id").toString()).isEqualTo(customerId);
        String paymentStatus = payment.get("status").toString();
        assertThat(paymentStatus).isIn("SUCCESS", "FAILED");

        logStep("✅ Payment persisted in DB for orderId: " + orderId
                + " | status: " + paymentStatus);
    }

    @Test(priority = 11)
    @Story("Order Status Sync after Payment")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify order status in DB is CONFIRMED or PAYMENT_FAILED after saga completes")
    public void testOrderStatusSyncedAfterPaymentSaga() {
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(89.99))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        TestModels.ProductResponse product = productResp.as(TestModels.ProductResponse.class);

        Response orderResp = given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerAuth.getAccessToken()))
                .header("X-User-Id", customerId)
                .body(TestDataFactory.createOrderRequest(
                        product.getId(), product.getName(), product.getPrice()))
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .extract().response();
        String orderId = orderResp.jsonPath().getString("id");

        // Initial status should be PENDING
        assertThat(DatabaseValidator.getOrderStatus(orderId)).isEqualTo("PENDING");

        // After saga completes, status should update
        Awaitility.await("Order status should change after payment saga")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    String status = DatabaseValidator.getOrderStatus(orderId);
                    assertThat(status).isIn("CONFIRMED", "PAYMENT_FAILED");
                });

        logStep("✅ Order status synced correctly in DB after saga for orderId: " + orderId);
    }

    // ─── Redis Cache Tests ─────────────────────────────────────────────

    @Test(priority = 12)
    @Story("Redis Caching")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify Redis is reachable and user cache is populated after fetch")
    public void testRedisCachePopulatedAfterUserFetch() {
        assertThat(RedisValidator.isRedisUp())
                .as("Redis must be running for cache tests")
                .isTrue();

        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();
        String userId = auth.getUser().getId();

        // Fetch user — should cache it in Redis
        given()
                .spec(RestAssuredConfig.getUserServiceSpec())
                .header("Authorization", "Bearer " + auth.getAccessToken())
                .pathParam("id", userId)
                .when()
                .get("/api/v1/users/{id}")
                .then()
                .statusCode(200);

        // After GET, user should be in Redis cache with TTL
        Awaitility.await("User should be cached in Redis")
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertThat(RedisValidator.userCacheExists(userId)).isTrue();
                });

        long ttl = RedisValidator.getTtl("user:" + userId);
        assertThat(ttl).isGreaterThan(0);

        logStep("✅ User cache in Redis verified for userId: " + userId + " TTL=" + ttl + "s");
    }
}
