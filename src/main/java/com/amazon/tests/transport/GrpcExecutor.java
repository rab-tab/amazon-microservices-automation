package com.amazon.tests.transport;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

public class GrpcExecutor implements HttpExecutor {
    @Override
    public Response get(RequestSpecification spec, String path) {

        throw new UnsupportedOperationException(
                "gRPC does not support HTTP GET");
    }

    @Override
    public Response post(RequestSpecification spec, String path, Object body) {

        throw new UnsupportedOperationException(
                "gRPC does not support HTTP POST");
    }

    @Override
    public Response put(RequestSpecification spec, String path, Object body) {

        throw new UnsupportedOperationException(
                "gRPC does not support HTTP PUT");
    }

    @Override
    public Response delete(RequestSpecification spec, String path) {

        throw new UnsupportedOperationException(
                "gRPC does not support HTTP DELETE");
    }
}
