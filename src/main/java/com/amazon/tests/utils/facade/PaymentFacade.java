package com.amazon.tests.utils.facade;

import com.amazon.tests.config.ConfigManager;
import com.amazon.tests.models.TestModels;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;

import static io.restassured.RestAssured.given;

@Slf4j

public class PaymentFacade {

    public TestModels.PaymentResponse getPayment(String orderId) {

        Response response = given()
                .spec(new RequestSpecBuilder()
                        .setBaseUri(ConfigManager.getInstance().getPaymentServiceUrl())
                        .setContentType(ContentType.JSON)
                        .build())
                .pathParam("orderId", orderId)
                .when()
                .get("/api/v1/payments/order/{orderId}")
                .then()
                .statusCode(200)
                .extract()
                .response();

        return response.as(TestModels.PaymentResponse.class);
    }

}

