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
    private final Map<String, String> headers;    // REST headers / gRPC metadata / SQS message attributes
    @Singular
    private final Map<String, Object> attributes;  // <-- extensibility escape hatch
    private final String token;
    private final ServiceType targetService;

    public <T> T getAttribute(String key, Class<T> type, T defaultValue) {
        Object v = attributes.get(key);
        return v != null ? type.cast(v) : defaultValue;
    }

    public <T> T getAttribute(String key, Class<T> type) {
        return getAttribute(key, type, null);
    }
}