package com.amazon.tests.config.restAsssured;

import lombok.Getter;

import java.util.Map;

@Getter
public class RequestSpecificationOptions {
    private String token;
    private Map<String, String> headers;
    private Map<String, ?> queryParams;
    private Map<String, ?> pathParams;
    private Object body;
    private Map<String, ?> cookies;
    private Long connectionTimeout;
    private Long socketTimeout;

    public static RequestSpecificationOptions builder() { return new RequestSpecificationOptions(); }

    public RequestSpecificationOptions token(String token) { this.token = token; return this; }
    public RequestSpecificationOptions headers(Map<String, String> headers) { this.headers = headers; return this; }
    public RequestSpecificationOptions queryParams(Map<String, ?> queryParams) { this.queryParams = queryParams; return this; }
    public RequestSpecificationOptions pathParams(Map<String, ?> pathParams) { this.pathParams = pathParams; return this; }
    public RequestSpecificationOptions body(Object body) { this.body = body; return this; }
    public RequestSpecificationOptions cookies(Map<String, ?> cookies) { this.cookies = cookies; return this; }
    public RequestSpecificationOptions timeouts(long connectionTimeout, long socketTimeout) {
        this.connectionTimeout = connectionTimeout;
        this.socketTimeout = socketTimeout;
        return this;
    }
}
