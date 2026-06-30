package com.amazon.tests.utils.facade;

import com.amazon.tests.config.ConfigManager;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

@Slf4j
public class PaymentFacade {

    public void processPayment(String orderId)
    {
        Response paymentResp = given()
                .spec(new io.restassured.builder.RequestSpecBuilder()
                        .setBaseUri(ConfigManager.getInstance().getPaymentServiceUrl())
                        .setContentType(io.restassured.http.ContentType.JSON)
                        .build())
                .pathParam("orderId", orderId)
                .when()
                .get("/api/v1/payments/order/{orderId}")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(404))) // may be processing
                .extract().response();

        if (paymentResp.statusCode() == 200) {
            String paymentStatus = paymentResp.jsonPath().getString("status");
            log.info("Payment status: " + paymentStatus);
            assertThat(paymentStatus).isIn("SUCCESS", "FAILED", "PROCESSING");
        }
    }
}
