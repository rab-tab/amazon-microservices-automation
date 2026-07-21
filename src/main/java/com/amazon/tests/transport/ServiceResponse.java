package com.amazon.tests.transport;


import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@Builder
public class ServiceResponse {
    private final int statusCode;
    private final Object body;
    private final Map<String, String> headers;
    private final Map<String, Object> attributes;

    public ServiceResponse(int statusCode, Object body, Map<String, String> metadata, Map<String, String> headers, Map<String, Object> attributes) {
        this.statusCode = statusCode;
        this.body = body;
        this.headers = headers;
        this.attributes = attributes;
    }
}
