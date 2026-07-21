package com.amazon.tests.utils.apiClients;

import com.amazon.tests.auth.AuthStrategy;
import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.*;

import java.util.HashMap;
import java.util.Map;

public class OrderApiClient {

    private final SeedingContext context;
    private final RequestExecutor executor;
    private final AuthStrategy authStrategy;

    public OrderApiClient(SeedingContext context,
                          AuthStrategy authStrategy,
                          RequestExecutor executor) {
        this.context = context;
        this.authStrategy = authStrategy;
        this.executor = executor;
    }

    // ============================================================
    // CREATE ORDER
    // ============================================================

    public ServiceResponse createOrder(
            String userId,
            String idempotencyKey,
            TestModels.CreateOrderRequest request) {

        return createOrderInternal(userId, idempotencyKey, request, null);
    }

    public ServiceResponse createOrderWithFault(
            String userId,
            String idempotencyKey,
            TestModels.CreateOrderRequest request,
            String faultHeader) {

        return createOrderInternal(userId, idempotencyKey, request, faultHeader);
    }

    // ============================================================
    // GET ORDER
    // ============================================================

    public ServiceResponse getOrder(String userId, String orderId) {
        Map<String, String> headers = new HashMap<>(authStrategy.extraAuthHeaders());
        headers.put("X-User-Id", userId);

        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.GET)
                .endpoint("/api/orders/" + orderId)
                .token(authStrategy.getToken())
                .headers(headers)
                .targetService(ServiceType.ORDER)
                .build();

        return executor.execute(request);
    }

    // ============================================================
    // PRIVATE HELPERS
    // ============================================================

    private ServiceResponse createOrderInternal(
            String userId,
            String idempotencyKey,
            TestModels.CreateOrderRequest payload,
            String faultHeader) {

        Map<String, String> headers = new HashMap<>(authStrategy.extraAuthHeaders());
        headers.put("X-User-Id", userId);
        headers.put("Idempotency-Key", idempotencyKey);
        if (faultHeader != null && !faultHeader.isBlank()) {
            headers.put("X-Fault", faultHeader);
        }

        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.POST)
                .endpoint("/api/orders")
                .payload(payload)
                .token(authStrategy.getToken())
                .headers(headers)
                .targetService(ServiceType.ORDER)
                .build();

        return executor.execute(request);
    }
}