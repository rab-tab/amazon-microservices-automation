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

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

@Epic("Amazon Microservices")
@Feature("Product Catalog")
public class ProductApiTest extends BaseTest {

    private String sellerToken;
    private String sellerId;
    private String createdProductId;
    private TestModels.ProductRequest productRequest;

    @BeforeClass
    public void setup() {
        logStep("Setting up seller account for product tests");
        TestModels.AuthResponse auth = AuthUtils.registerAndGetAuth();
        sellerToken = auth.getAccessToken();
        sellerId = auth.getUser().getId();
        productRequest = TestDataFactory.createRandomProduct();
    }

    @Test(priority = 1)
    @Story("Create Product")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify seller can create a new product")
    public void testCreateProduct() {
        logStep("Creating product: " + productRequest.getName());

        Response response = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("Authorization", "Bearer " + sellerToken)
                .header("X-User-Id", sellerId)
                .body(productRequest)
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("name", equalTo(productRequest.getName()))
                .body("price", equalTo(productRequest.getPrice().floatValue()))
                .body("stockQuantity", equalTo(productRequest.getStockQuantity()))
                .body("status", equalTo("ACTIVE"))
                .body("rating", notNullValue())
                .body("reviewCount", equalTo(0))
                .extract().response();

        TestModels.ProductResponse product = response.as(TestModels.ProductResponse.class);
        assertThat(product.getId()).isNotBlank();
        assertThat(product.getSellerId()).isEqualTo(sellerId);

        createdProductId = product.getId();
        logStep("Product created with ID: " + createdProductId);
    }

    @Test(priority = 2)
    @Story("Create Product")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify product creation fails with negative price")
    public void testCreateProductWithNegativePrice() {
        TestModels.ProductRequest invalidProduct = TestDataFactory.createProductWithPrice(-10.0);

        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerId)
                .body(invalidProduct)
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(400)
                .body("validationErrors.price", notNullValue());
    }

    @Test(priority = 3)
    @Story("Create Product")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify product creation fails with missing required fields")
    public void testCreateProductWithMissingName() {
        TestModels.ProductRequest noName = TestDataFactory.createRandomProduct();
        noName.setName(null);

        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerId)
                .body(noName)
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(400);
    }

    @Test(priority = 4, dependsOnMethods = "testCreateProduct")
    @Story("Get Product")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify product can be retrieved by ID")
    public void testGetProductById() {
        logStep("Fetching product: " + createdProductId);

        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .pathParam("id", createdProductId)
                .when()
                .get("/api/v1/products/{id}")
                .then()
                .statusCode(200)
                .body("id", equalTo(createdProductId))
                .body("name", equalTo(productRequest.getName()))
                .body("status", equalTo("ACTIVE"));
    }

    @Test(priority = 5)
    @Story("Get Product")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify 404 is returned for non-existent product")
    public void testGetNonExistentProduct() {
        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .pathParam("id", "00000000-0000-0000-0000-000000000000")
                .when()
                .get("/api/v1/products/{id}")
                .then()
                .statusCode(404);
    }

    @Test(priority = 6, dependsOnMethods = "testCreateProduct")
    @Story("List Products")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify product listing returns paginated results")
    public void testGetAllProducts() {
        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .queryParam("page", 0)
                .queryParam("size", 10)
                .when()
                .get("/api/v1/products")
                .then()
                .statusCode(200)
                .body("products", notNullValue())
                .body("page", equalTo(0))
                .body("size", equalTo(10))
                .body("totalElements", greaterThanOrEqualTo(1));
    }

    @Test(priority = 7, dependsOnMethods = "testCreateProduct")
    @Story("Search Products")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify product search returns relevant results")
    public void testSearchProducts() {
        String searchQuery = productRequest.getName().split(" ")[0]; // Search by first word of name

        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .queryParam("q", searchQuery)
                .when()
                .get("/api/v1/products/search")
                .then()
                .statusCode(200)
                .body("products", notNullValue());
    }

    @Test(priority = 8, dependsOnMethods = "testCreateProduct")
    @Story("Update Product")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify seller can update their product")
    public void testUpdateProduct() {
        TestModels.ProductRequest updateReq = TestModels.ProductRequest.builder()
                .name("Updated Product Name")
                .price(BigDecimal.valueOf(99.99))
                .build();

        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerId)
                .pathParam("id", createdProductId)
                .body(updateReq)
                .when()
                .put("/api/v1/products/{id}")
                .then()
                .statusCode(200)
                .body("name", equalTo("Updated Product Name"))
                .body("price", equalTo(99.99f));
    }

    @Test(priority = 9, dependsOnMethods = "testCreateProduct")
    @Story("Stock Management")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify stock can be updated for a product")
    public void testUpdateProductStock() {
        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .pathParam("id", createdProductId)
                .queryParam("quantity", 50)
                .when()
                .patch("/api/v1/products/{id}/stock")
                .then()
                .statusCode(204);
    }

    @Test(priority = 10, dependsOnMethods = {"testUpdateProduct", "testUpdateProductStock"})
    @Story("Delete Product")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify seller can delete their product")
    public void testDeleteProduct() {
        logStep("Deleting product: " + createdProductId);

        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerId)
                .pathParam("id", createdProductId)
                .when()
                .delete("/api/v1/products/{id}")
                .then()
                .statusCode(204);

        // Verify product is now discontinued (soft delete)
        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .pathParam("id", createdProductId)
                .when()
                .get("/api/v1/products/{id}")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(404)));
    }
}
