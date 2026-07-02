package com.amazon.tests.transport;

import com.amazon.tests.transport.HttpExecutor;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

public class MockExecutor implements HttpExecutor {

    @Override
    public Response get(RequestSpecification spec,
                        String path) {

        throw new UnsupportedOperationException(
                "MockExecutor not implemented yet");
    }

    @Override
    public Response post(RequestSpecification spec,
                         String path,
                         Object body) {

        throw new UnsupportedOperationException(
                "MockExecutor not implemented yet");
    }

    @Override
    public Response put(RequestSpecification spec, String path, Object body) {
        throw new UnsupportedOperationException(
                "MockExecutor not implemented yet");
    }

    @Override
    public Response delete(RequestSpecification spec, String path) {
        throw new UnsupportedOperationException(
                "MockExecutor not implemented yet");
    }
}

