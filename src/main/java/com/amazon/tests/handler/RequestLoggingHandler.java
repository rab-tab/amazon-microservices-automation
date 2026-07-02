package com.amazon.tests.handler;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestLoggingHandler {
    private static final Logger log =
            LoggerFactory.getLogger(RequestLoggingHandler.class);

    public void logRequest(
            String method,
            String endpoint,
            Object requestBody,
            RequestSpecification spec) {

        log.info("========== HTTP REQUEST ==========");
        log.info("Method      : {}", method);
        log.info("Endpoint    : {}", endpoint);
        log.info("Headers     : {}", spec.get());

        if (requestBody != null) {
            log.info("RequestBody : {}", requestBody);
        }

        log.info("==================================");
    }
    public void logResponse(Response response) {

        log.info("========== HTTP RESPONSE ==========");
        log.info("Status Code : {}", response.statusCode());
        log.info("Response    : {}", response.asPrettyString());
        log.info("==================================");
    }
}
