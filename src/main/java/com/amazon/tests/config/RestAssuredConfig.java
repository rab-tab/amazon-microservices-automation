package com.amazon.tests.config;

import io.qameta.allure.restassured.AllureRestAssured;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import org.aeonbits.owner.ConfigFactory;

public class RestAssuredConfig {

    private static final TestConfig config = ConfigFactory.create(TestConfig.class,
            System.getProperties(), System.getenv());

    private static RequestSpecification baseSpec;
    private static RequestSpecification authSpec;

    public static RequestSpecification getBaseSpec() {
        if (baseSpec == null) {
            baseSpec = new RequestSpecBuilder()
                    .setBaseUri(config.baseUrl())
                    .setContentType(ContentType.JSON)
                    .setAccept(ContentType.JSON)
                    .addFilter(new AllureRestAssured())
                    .log(LogDetail.ALL)
                    .build();
        }
        return baseSpec;
    }

    public static RequestSpecification getAuthSpec(String token) {
        return new RequestSpecBuilder()
                .addRequestSpecification(getBaseSpec())
                .addHeader("Authorization", "Bearer " + token)
                .build();
    }

    public static RequestSpecification getUserServiceSpec() {
        return new RequestSpecBuilder()
                .setBaseUri(config.userServiceUrl())
                .setContentType(ContentType.JSON)
                .addFilter(new AllureRestAssured())
                .log(LogDetail.ALL)
                .build();
    }

    public static RequestSpecification getProductServiceSpec() {
        return new RequestSpecBuilder()
                .setBaseUri(config.productServiceUrl())
                .setContentType(ContentType.JSON)
                .addFilter(new AllureRestAssured())
                .log(LogDetail.ALL)
                .build();
    }

    public static RequestSpecification getOrderServiceSpec(String token) {
        return new RequestSpecBuilder()
                .setBaseUri(config.orderServiceUrl())
                .setContentType(ContentType.JSON)
                .addHeader("Authorization", "Bearer " + token)
                .addFilter(new AllureRestAssured())
                .log(LogDetail.ALL)
                .build();
    }

    public static ResponseSpecification getSuccessResponseSpec() {
        return new ResponseSpecBuilder()
                .expectContentType(ContentType.JSON)
                .log(LogDetail.ALL)
                .build();
    }

    public static TestConfig getConfig() {
        return config;
    }
}
