package com.amazon.tests.utils.apiClients;

import com.amazon.tests.auth.AuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OrderApiClient {

    private final RequestExecutor executor;
    private final AuthStrategy authStrategy;

    public OrderApiClient(AuthStrategy authStrategy, RequestExecutor executor) {
        this.authStrategy = authStrategy;
        this.executor = executor;
    }

    // ============================================================
    // CREATE ORDER
    // ============================================================

    public TestModels.OrderResponse createOrder(String userId, String idempotencyKey,
                                                List<TestModels.ProductResponse> products) {
        TestModels.CreateOrderRequest payload = buildOrderRequest(products);
        return requireSuccess(createOrderInternal(userId, idempotencyKey, payload, null), 201)
                .as(TestModels.OrderResponse.class);
    }

    public ServiceResponse createOrderWithFault(String userId, String idempotencyKey,
                                                TestModels.CreateOrderRequest request, String faultHeader) {
        Map<String, String> extra = faultHeader != null && !faultHeader.isBlank()
                ? Map.of("X-Fault", faultHeader) : null;
        return createOrderInternal(userId, idempotencyKey, request, extra);
    }
    public ServiceResponse cancelOrderRaw(String token, String userId, String orderId) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.DELETE)
                .endpoint("/api/v1/orders/{id}")
                .attribute(RequestAttributes.PATH_PARAMS, Map.of("id", orderId))
                .token(token)
                .header("X-User-Id", userId)
                .targetService(ServiceType.ORDER)
                .build();
        return executor.execute(request);
    }

    public TestModels.OrderResponse createOrderWithTestScenario(String userId, String idempotencyKey,
                                                                List<TestModels.ProductResponse> products,
                                                                String testScenario) {
        TestModels.CreateOrderRequest payload = buildOrderRequest(products);
        Map<String, String> extra = testScenario != null ? Map.of("X-Test-Scenario", testScenario) : null;
        ServiceResponse response = createOrderInternal(userId, idempotencyKey, payload, extra);

        if (response.getStatusCode() != 200 && response.getStatusCode() != 201) {
            throw new IllegalStateException(String.format(
                    "Expected status 200 or 201 but got %d. Body: %s",
                    response.getStatusCode(), response.getBody()));
        }
        return response.as(TestModels.OrderResponse.class);
    }
    private TestModels.CreateOrderRequest buildOrderRequest(List<TestModels.ProductResponse> products) {
        List<TestModels.OrderItemRequest> items = products.stream()
                .map(p -> TestModels.OrderItemRequest.builder()
                        .productId(p.getId())
                        .productName(p.getName())
                        .unitPrice(p.getPrice())
                        .quantity(1)
                        .build())
                .toList();

        return TestModels.CreateOrderRequest.builder()
                .items(items)
                .shippingAddress("123 Amazon Way, Seattle, WA 98101")
                .build();
    }

    // ============================================================
    // GET ORDER
    // ============================================================

    public TestModels.OrderResponse getOrder(String token, String userId, String orderId) {
        Map<String, String> headers = new HashMap<>(authStrategy.extraAuthHeaders());
        headers.put("X-User-Id", userId);

        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.GET)
                .endpoint("/api/v1/orders/{id}")
                .attribute(RequestAttributes.PATH_PARAMS, Map.of("id", orderId))
                .token(token)
                .headers(headers)
                .targetService(ServiceType.ORDER)
                .build();

        return requireSuccess(executor.execute(request), 200).as(TestModels.OrderResponse.class);
    }

    // ============================================================
    // CANCEL ORDER
    // ============================================================

    public void cancelOrder(String token, String userId, String orderId) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.DELETE)
                .endpoint("/api/v1/orders/{id}/")
                .attribute(RequestAttributes.PATH_PARAMS, Map.of("id", orderId))
                .token(token)
                .headers(Map.of("X-User-Id", userId))
                .targetService(ServiceType.ORDER)
                .build();

        requireSuccess(executor.execute(request), 200);
    }

    // ============================================================
    // PRIVATE HELPERS
    // ============================================================

    // createOrderInternal now takes a generic extra-headers map instead of a single faultHeader string
    private ServiceResponse createOrderInternal(String userId, String idempotencyKey,
                                                TestModels.CreateOrderRequest payload,
                                                Map<String, String> extraHeaders) {
        Map<String, String> headers = new HashMap<>(authStrategy.extraAuthHeaders());
        headers.put("X-User-Id", userId);
        headers.put("Idempotency-Key", idempotencyKey);
        if (extraHeaders != null) headers.putAll(extraHeaders);

        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.POST)
                .endpoint("/api/v1/orders")
                .payload(payload)
                .token(authStrategy.getToken())
                .headers(headers)
                .targetService(ServiceType.ORDER)
                .build();

        return executor.execute(request);
    }

    /** Infra-level sanity check — fails fast on unexpected transport errors, not business assertions. */
    private ServiceResponse requireSuccess(ServiceResponse response, int... expectedStatuses) {
        int actual = response.getStatusCode();
        boolean matched = Arrays.stream(expectedStatuses).anyMatch(s -> s == actual);
        if (!matched) {
            throw new IllegalStateException(String.format(
                    "Expected status %s but got %d. Body: %s",
                    Arrays.toString(expectedStatuses), actual, response.getBody()));
        }
        return response;
    }
}