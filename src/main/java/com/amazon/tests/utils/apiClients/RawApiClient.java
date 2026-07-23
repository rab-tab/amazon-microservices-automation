package com.amazon.tests.utils.apiClients;


import com.amazon.tests.transport.*;

import java.util.Map;

/** Generic, typed-method-free client for hitting arbitrary raw endpoints —
 *  used for security/routing tests that need paths not modeled by business API clients. */
public class RawApiClient {

    private final RequestExecutor executor;

    public RawApiClient(RequestExecutor executor) {
        this.executor = executor;
    }

    public ServiceResponse get(ServiceType targetService, String endpoint, String token) {
        return get(targetService, endpoint, token, Map.of());
    }

    public ServiceResponse get(ServiceType targetService, String endpoint, String token, Map<String, String> extraHeaders) {
        return execute(HttpMethod.GET, targetService, endpoint, token, extraHeaders, null);
    }

    public ServiceResponse post(ServiceType targetService, String endpoint, String token, Object body) {
        return execute(HttpMethod.POST, targetService, endpoint, token, Map.of(), body);
    }

    public ServiceResponse delete(ServiceType targetService, String endpoint, String token) {
        return execute(HttpMethod.DELETE, targetService, endpoint, token, Map.of(), null);
    }

    public ServiceResponse options(ServiceType targetService, String endpoint, Map<String, String> headers) {
        return execute(HttpMethod.OPTIONS, targetService, endpoint, null, headers, null);
    }

    private ServiceResponse execute(HttpMethod method, ServiceType targetService, String endpoint,
                                    String token, Map<String, String> headers, Object body) {
        ServiceRequest.ServiceRequestBuilder builder = ServiceRequest.builder()
                .method(method)
                .endpoint(endpoint)
                .targetService(targetService);

        if (token != null) builder.token(token);
        if (headers != null && !headers.isEmpty()) builder.headers(headers);
        if (body != null) builder.payload(body);

        return executor.execute(builder.build());
    }
}
