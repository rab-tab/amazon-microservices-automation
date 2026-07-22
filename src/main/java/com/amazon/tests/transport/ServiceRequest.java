package com.amazon.tests.transport;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.Singular;

import java.util.Map;

@Getter
@Setter
@Builder
public class ServiceRequest {
    private final HttpMethod method;              // ignored by gRPC/SQS, used by REST
    private final String endpoint;                // path for REST, method name for gRPC, topic for SQS
    private final Object payload;
    @Singular
    private final Map<String, String> headers;     // REST headers / gRPC metadata / SQS message attributes
    @Singular
    private final Map<String, Object> attributes;   // <-- extensibility escape hatch
    private final String token;
    private final ServiceType targetService;

    public <T> T getAttribute(String key, Class<T> type, T defaultValue) {
        Object v = attributes.get(key);
        return v != null ? type.cast(v) : defaultValue;
    }

    public ServiceRequest(String operation, String httpMethod, Object payload, Map<String, String> metadata, HttpMethod method, String endpoint, Object body, Map<String, String> headers, Map<String, Object> queryParams, String operation1, Object payload1, Map<String, Object> attributes, String token, ServiceType targetService) {
        this.method = method;
        this.endpoint = endpoint;
        this.payload = payload1;
        this.attributes = attributes;
        this.token = token;
        this.targetService = targetService;
        this.headers = headers;


    }
    public <T> T getAttribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        return value != null ? type.cast(value) : null;
    }
    public HttpMethod getMethod() { return method; }

}
