package com.amazon.tests.handler;

import io.restassured.specification.RequestSpecification;

public class CorrelationIdHandler {
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    public void apply(
            RequestSpecification requestSpecification,
            String correlationId) {

        requestSpecification.header(
                CORRELATION_ID_HEADER,
                correlationId
        );
    }
}
