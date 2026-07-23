package com.amazon.tests.utils.apiClients;


import com.amazon.tests.transport.*;

import java.util.Map;

public class GatewayApiClient {

    private final RequestExecutor executor;

    public GatewayApiClient(RequestExecutor executor) {
        this.executor = executor;
    }

    public ServiceResponse get(String endpoint, String token) {
        return get(endpoint, token, Map.of());
    }

    public ServiceResponse get(String endpoint, String token, Map<String, String> extraHeaders) {
        return execute(HttpMethod.GET, endpoint, token, extraHeaders);
    }

    public ServiceResponse options(String endpoint, Map<String, String> headers) {
        return execute(HttpMethod.OPTIONS, endpoint, null, headers);
    }

    private ServiceResponse execute(HttpMethod method, String endpoint, String token, Map<String, String> headers) {
        ServiceRequest.ServiceRequestBuilder builder = ServiceRequest.builder()
                .method(method)
                .endpoint(endpoint)
                .targetService(ServiceType.GATEWAY);

        if (token != null) builder.token(token);
        if (headers != null && !headers.isEmpty()) builder.headers(headers);

        return executor.execute(builder.build());
    }
}