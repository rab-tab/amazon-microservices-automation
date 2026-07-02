package com.amazon.tests.utils.apiClients;

import com.amazon.tests.auth.AuthStrategy;
import com.amazon.tests.config.RequestBuilder;
import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.HttpExecutor;
import com.amazon.tests.transport.RestAssuredExecutor;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

public class OrderApiClient {

    private final SeedingContext context;
    private final HttpExecutor executor;
    private AuthStrategy authStrategy;

    // Primary constructor
    public OrderApiClient(SeedingContext context,
                          AuthStrategy authStrategy,
                          HttpExecutor executor) {

        this.context = context;
        this.authStrategy = authStrategy;
        this.executor = executor;
    }

    // Convenience constructor
    public OrderApiClient(SeedingContext context,
                          AuthStrategy authStrategy) {

        this(context,
                authStrategy,
                new RestAssuredExecutor());
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

        RequestSpecification spec= RequestBuilder.defaultSpec();
        authStrategy.authenticate(spec);

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