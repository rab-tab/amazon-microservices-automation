package com.amazon.tests.utils.apiClients;

import com.amazon.tests.transport.*;

import java.util.Map;

public class GatewayApiClient extends ApiClient {

    public GatewayApiClient(RequestExecutor executor) {
        super(executor);
    }

    public ServiceResponse get(String endpoint, String token) {
        return get(endpoint, token, Map.of());
    }

    public ServiceResponse get(String endpoint, String token, Map<String, String> extraHeaders) {
        return execute(HttpMethod.GET, endpoint, token, extraHeaders, null);
    }

    public ServiceResponse post(String endpoint, String token, Object body) {
        return post(endpoint, token, Map.of(), body);
    }

    public ServiceResponse post(String endpoint, String token, Map<String, String> extraHeaders, Object body) {
        return execute(HttpMethod.POST, endpoint, token, extraHeaders, body);
    }

    public ServiceResponse delete(String endpoint, String token) {
        return execute(HttpMethod.DELETE, endpoint, token, Map.of(), null);
    }

    public ServiceResponse options(String endpoint, Map<String, String> headers) {
        return execute(HttpMethod.OPTIONS, endpoint, null, headers, null);
    }

    private ServiceResponse execute(HttpMethod method, String endpoint, String token,
                                    Map<String, String> headers, Object body) {
        ServiceRequest.ServiceRequestBuilder builder = ServiceRequest.builder()
                .method(method)
                .endpoint(endpoint)
                .targetService(ServiceType.GATEWAY);

        if (token != null) builder.token(token);
        if (headers != null && !headers.isEmpty()) builder.headers(headers);
        if (body != null) builder.payload(body);

        return executor.execute(builder.build());
    }
}