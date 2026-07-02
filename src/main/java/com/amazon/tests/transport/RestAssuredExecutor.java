package com.amazon.tests.transport;

import com.amazon.tests.transport.HttpExecutor;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

public class RestAssuredExecutor implements HttpExecutor {
    @Override
    public Response get(RequestSpecification spec, String path) {
        return RestAssured
                .given(spec)
                .get(path);
    }

    @Override
    public Response post(RequestSpecification spec, String path, Object body) {
        return RestAssured
                .given(spec)
                .body(body)
                .post(path);
    }

    @Override
    public Response put(RequestSpecification spec, String path, Object body) {
        return RestAssured
                .given(spec)
                .body(body)
                .put(path);
    }

    @Override
    public Response delete(RequestSpecification spec, String path) {
        return RestAssured
                .given(spec)
                .delete(path);
    }
}
