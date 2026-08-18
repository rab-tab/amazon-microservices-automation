package com.amazon.tests.config.restAsssured;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
public class RequestSpecificationOptions {
    private String token;
    private Map<String, String> headers;
    private Map<String, ?> queryParams;
    private Map<String, ?> pathParams;
    private Object body;
    private Map<String, ?> cookies;
    private Long connectionTimeout;
    private Long socketTimeout;

}
