package com.amazon.tests.utils.apiClients;

import com.amazon.tests.config.RequestBuilder;
import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.models.TestModels;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

public class OrderApiClient {
    private final SeedingContext context;

        public OrderApiClient(SeedingContext context) {
            this.context = context;
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

            return RestAssured
                    .given()
                    .spec(RequestBuilder.withBearerAuth(userToken))
                    .header("X-User-Id", userId)
                    .when()
                    .get("/api/orders/" + orderId);
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

            RequestSpecification spec = RequestBuilder
                    .withBearerAuth(userToken);

            spec.header("X-User-Id", userId);
            spec.header("Idempotency-Key", idempotencyKey);

            if (faultHeader != null && !faultHeader.isBlank()) {
                spec.header("X-Fault", faultHeader);
            }

            return RestAssured
                    .given()
                    .spec(spec)
                    .body(request)
                    .when()
                    .post("/api/orders");
        }
}
