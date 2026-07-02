package com.amazon.tests.utils.apiClients;

import com.amazon.tests.transport.HttpExecutor;
import com.amazon.tests.config.RequestBuilder;
import com.amazon.tests.transport.RestAssuredExecutor;
import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.models.TestModels;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

public class OrderApiClient {

    private final SeedingContext context;
    private final HttpExecutor executor;

    public OrderApiClient(SeedingContext context,
                          HttpExecutor executor) {
        this.context = context;
        this.executor = executor;
    }

    public OrderApiClient(SeedingContext context) {

        this(
                context,
                new RestAssuredExecutor()
        );
    }
    // ============================================================
    // CREATE ORDER
    // ============================================================

    public Response createOrder(
            String userToken,
            String userId,
            String idempotencyKey,
            TestModels.CreateOrderRequest request) {

        return createOrderInternal(
                userToken,
                userId,
                idempotencyKey,
                request,
                null
        );
    }

    public Response createOrderWithFault(
            String userToken,
            String userId,
            String idempotencyKey,
            TestModels.CreateOrderRequest request,
            String faultHeader) {

        return createOrderInternal(
                userToken,
                userId,
                idempotencyKey,
                request,
                faultHeader
        );
    }

    // ============================================================
    // GET ORDER
    // ============================================================

    public Response getOrder(
            String userToken,
            String userId,
            String orderId) {

        RequestSpecification spec =
                RequestBuilder.withBearerAuth(userToken);

        spec.header("X-User-Id", userId);

        return executor.get(
                spec,
                "/api/orders/" + orderId
        );
    }

    // ============================================================
    // PRIVATE HELPERS
    // ============================================================

    private Response createOrderInternal(
            String userToken,
            String userId,
            String idempotencyKey,
            TestModels.CreateOrderRequest request,
            String faultHeader) {

        RequestSpecification spec =
                RequestBuilder.withBearerAuth(userToken);

        spec.header("X-User-Id", userId);
        spec.header("Idempotency-Key", idempotencyKey);

        if (faultHeader != null && !faultHeader.isBlank()) {
            spec.header("X-Fault", faultHeader);
        }

        return executor.post(
                spec,
                "/api/orders",
                request
        );
    }
}