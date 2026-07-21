package com.amazon.tests.transport;

import com.amazon.tests.config.restAsssured.RestAssuredConfigFinal;
import com.amazon.tests.config.restAsssured.RestClient;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Map;
import java.util.stream.Collectors;


public class RestHttpClient implements RequestExecutor {

    private final RestClient restClient;
    private final RestAssuredConfigFinal restAssuredConfig;  // reuse what you already have — don't build a parallel policy system

    public RestHttpClient(RestClient restClient, RestAssuredConfigFinal restAssuredConfig) {
        this.restClient = restClient;
        this.restAssuredConfig = restAssuredConfig;
    }

    @Override
    public ServiceResponse execute(ServiceRequest request) {
        RequestSpecification spec = resolveSpec(request);  // delegates to existing per-service methods

        Map<String, Object> queryParams = request.getAttribute(RequestAttributes.QUERY_PARAMS, Map.class, Map.of());

        Response response = switch (request.getMethod()) {
            case GET    -> restClient.get(request.getEndpoint(), spec, queryParams);
            case POST   -> restClient.post(request.getEndpoint(), spec, request.getPayload(), request.getHeaders());
            case PUT    -> restClient.put(request.getEndpoint(), spec, request.getPayload());
            case DELETE -> restClient.delete(request.getEndpoint(), spec, queryParams);
            case PATCH  -> restClient.patch(request.getEndpoint(), spec, request.getPayload());
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