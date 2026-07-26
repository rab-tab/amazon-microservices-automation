package com.amazon.tests.transport;

import com.amazon.tests.config.restAsssured.RestAssuredConfig;
import com.amazon.tests.config.restAsssured.RestClient;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Map;
import java.util.stream.Collectors;


public class RestHttpClient implements RequestExecutor {

    private final RestClient restClient;
    private final RestAssuredConfig restAssuredConfig;  // reuse what you already have — don't build a parallel policy system

    public RestHttpClient(RestClient restClient, RestAssuredConfig restAssuredConfig) {
        this.restClient = restClient;
        this.restAssuredConfig = restAssuredConfig;
    }



    @Override
    public ServiceResponse execute(ServiceRequest request) {
        RequestSpecification spec = resolveSpec(request);

        Map<String, Object> pathParams = request.getAttribute(RequestAttributes.PATH_PARAMS, Map.class, Map.of());
        Map<String, Object> queryParams = request.getAttribute(RequestAttributes.QUERY_PARAMS, Map.class, Map.of());

        if (!pathParams.isEmpty()) spec = spec.pathParams(pathParams);
        if (!queryParams.isEmpty()) spec = spec.queryParams(queryParams);
        if (request.getHeaders() != null && !request.getHeaders().isEmpty()) {
            spec = spec.headers(request.getHeaders());
        }

        Response response = switch (request.getMethod()) {
            case GET     -> restClient.get(request.getEndpoint(), spec);
            case POST    -> restClient.post(request.getEndpoint(), spec, request.getPayload());
            case PUT     -> restClient.put(request.getEndpoint(), spec, request.getPayload());
            case DELETE  -> restClient.delete(request.getEndpoint(), spec);
            case PATCH   -> restClient.patch(request.getEndpoint(), spec, request.getPayload());
            case OPTIONS -> restClient.options(request.getEndpoint(), spec);
        };

        return ServiceResponse.builder()
                .statusCode(response.getStatusCode())
                .body(response.getBody().asString())
                .headers(toMap(response))
                .build();
    }

    private RequestSpecification resolveSpec(ServiceRequest request) {
        return switch (request.getTargetService()) {
            case ORDER   -> restAssuredConfig.getOrderServiceSpec(request.getToken());
            case PAYMENT -> restAssuredConfig.getPaymentServiceSpec(request.getToken());
            case PRODUCT -> restAssuredConfig.getProductServiceSpec(request.getToken());
            case USER    -> restAssuredConfig.getUserServiceSpec(request.getToken());
            case GATEWAY -> restAssuredConfig.getGatewaySpec(request.getToken());
        };
    }

    private Map<String, String> toMap(Response response) {
        return response.getHeaders().asList().stream()
                .collect(Collectors.toMap(
                        Header::getName,
                        Header::getValue,
                        (existing, replacement) -> existing  // handle duplicate header names gracefully
                ));
    }
}