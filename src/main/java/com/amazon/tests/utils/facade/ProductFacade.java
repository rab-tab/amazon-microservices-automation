package com.amazon.tests.utils.facade;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.testData.TestDataFactory;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@Slf4j
public class ProductFacade {

    public TestModels.ProductResponse createProduct(TestModels.AuthResponse  sellerData) {
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


    public void getProduct(String id){
        given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .pathParam("id", id)
                .when()
                .get("/api/v1/products/{id}")
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("price", equalTo(49.99f));
    }

    public void browseProducts(){
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

}

