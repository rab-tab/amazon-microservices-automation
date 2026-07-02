package com.amazon.tests.transport;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

public interface HttpExecutor {
    Response get(RequestSpecification spec,
                 String path);

    Response post(RequestSpecification spec,
                  String path,
                  Object body);

    Response put(RequestSpecification spec,
                 String path,
                 Object body);

    Response delete(RequestSpecification spec,
                    String path);
}
