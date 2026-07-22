package com.amazon.tests.utils.apiClients;

import com.amazon.tests.commonmodels.enums.ProductType;
import com.amazon.tests.config.restAsssured.old.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.*;
import com.amazon.tests.utils.testData.TestDataFactory;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@Slf4j
public class ProductApiClient {
    private final RequestExecutor executor;

    public ProductApiClient(RequestExecutor executor) {
        this.executor = executor;
    }
    public TestModels.ProductResponse createProduct(TestModels.AuthResponse sellerData) {
        TestModels.ProductRequest productReq = TestDataFactory.createProductWithPrice(49.99);
        Response productCreateResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("Authorization", "Bearer " + sellerData.getAccessToken())
                .header("X-User-Id", sellerData.getUser().getId())
                .body(productReq)
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();

        TestModels.ProductResponse product = productCreateResp.as(TestModels.ProductResponse.class);

        assertThat(product.getId()).isNotBlank();

        assertThat(product.getStatus()).isEqualTo("ACTIVE");

        log.info("Product created: " + product.getId() + " - " + product.getName());
        return product;
    }

    public TestModels.ProductResponse createProduct(TestModels.AuthResponse sellerData, ProductType type) {

        double price;

        switch (type) {

            case CHEAP:
                price = ThreadLocalRandom.current().nextDouble(1.0, 20.0);
                break;

            case EXPENSIVE:
                price = ThreadLocalRandom.current().nextDouble(100.0, 1000.0);
                break;

            case MEDIUM:
            default:
                price = ThreadLocalRandom.current().nextDouble(20.0, 100.0);
                break;
        }

        TestModels.ProductRequest productReq =
                TestDataFactory.createProductWithPrice(price);

        Response productCreateResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("Authorization", "Bearer " + sellerData.getAccessToken())
                .header("X-User-Id", sellerData.getUser().getId())
                .body(productReq)
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract()
                .response();

        TestModels.ProductResponse product =
                productCreateResp.as(TestModels.ProductResponse.class);

        assertThat(product.getId()).isNotBlank();
        assertThat(product.getStatus()).isEqualTo("ACTIVE");

        log.info("Product created: {} - {} (${}})",
                product.getId(),
                product.getName(),
                product.getPrice());

        return product;
    }

    public TestModels.ProductResponse createProduct(TestModels.AuthResponse sellerData, double price, int stockQuantity) {
        TestModels.ProductRequest payload = TestDataFactory.createProductWithPriceAndStock(price, stockQuantity);
        return createProductInternal(sellerData, payload);
    }

    private TestModels.ProductResponse createProductInternal(TestModels.AuthResponse sellerData,
                                                             TestModels.ProductRequest payload) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.POST)
                .endpoint("/api/v1/products")
                .payload(payload)
                .token(sellerData.getAccessToken())
                .header("X-User-Id", sellerData.getUser().getId())
                .targetService(ServiceType.PRODUCT)
                .build();

        TestModels.ProductResponse product =
                requireSuccess(executor.execute(request), 201).as(TestModels.ProductResponse.class);

        assertThat(product.getId()).isNotBlank();
        assertThat(product.getStatus()).isEqualTo("ACTIVE");

        log.info("Product created: {} - {} (${})", product.getId(), product.getName(), product.getPrice());
        return product;
    }

    // =======================


    public void browseProducts() {
        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .queryParam("page", 0)
                .queryParam("size", 10)
                .when()
                .get("/api/v1/products")
                .then()
                .statusCode(200)
                .body("totalElements", greaterThanOrEqualTo(1));
    }

    public List<TestModels.ProductResponse> createProducts(TestModels.AuthResponse sellerData, int count, ProductType type)
    {

        List<TestModels.ProductResponse> products = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            products.add(createProduct(sellerData, type));
        }

        return products;
    }

    public List<TestModels.ProductResponse> createProducts(TestModels.AuthResponse sellerData,int count){

        List<TestModels.ProductResponse> products = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            products.add(createProduct(sellerData));
        }

        return products;
    }
    private final ServiceResponse requireSuccess(ServiceResponse response, int expectedStatus) {
        if (response.getStatusCode() != expectedStatus) {
            throw new IllegalStateException(String.format(
                    "Expected status %d but got %d. Body: %s",
                    expectedStatus, response.getStatusCode(), response.getBody()));
        }
        return response;
    }

    public TestModels.ProductResponse getProduct(String id) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.GET)
                .endpoint("/api/v1/products/{id}")
                .attribute(RequestAttributes.PATH_PARAMS, Map.of("id", id))
                .targetService(ServiceType.PRODUCT)
                .build();

        return requireSuccess(executor.execute(request), 200).as(TestModels.ProductResponse.class);
    }
}
