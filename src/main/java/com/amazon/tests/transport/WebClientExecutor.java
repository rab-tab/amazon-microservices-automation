package com.amazon.tests.transport;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

public class WebClientExecutor implements HttpExecutor {
    @Override
    public Response get(RequestSpecification spec, String path) {
        throw new UnsupportedOperationException(
                "WebClientExecutor not implemented");
    }

    @Override
    public Response post(RequestSpecification spec, String path, Object body) {
        throw new UnsupportedOperationException(
                "WebClientExecutor not implemented");
    }

    @Override
    public Response put(RequestSpecification spec, String path, Object body) {
        throw new UnsupportedOperationException(
                "WebClientExecutor not implemented");
    }

    @Override
    public Response delete(RequestSpecification spec, String path) {
        throw new UnsupportedOperationException(
                "WebClientExecutor not implemented");
    }
}
