package com.amazon.tests.config;

import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.models.TestModels;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;

import static com.amazon.tests.utils.retry.RetryHandler.executeWithRetry;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class CreateOrderFlow extends AbstractApiFlow{


    protected CreateOrderFlow(TestEnvironment env, SeedingContext context, RetryExecutor retryExecutor) {
        super(env, context, retryExecutor);
    }

    @Override
    protected Object buildRequest() {
        return OrderBuilder.anOrder()
                .withNamespace(context.getNamespace())
                .addItem(env.getProduct(), 2)
                .build();
    }

    @Override
    protected Response executeRequest(Object request) throws Exception {
        return executeWithRetry(() -> {
            try {
                return sendOrderRequestWithFault(
                        env.getUserToken(),
                        idempotencyKey,
                        (TestModels.CreateOrderRequest) request,
                        scenario.getFaultHeader()
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    protected void verifyHttpResponse(Response response) {
        assertThat(response.statusCode())
                .isEqualTo(201);

        assertThat(response.jsonPath().getString("status"))
                .isEqualTo("PENDING");

    }

    @Override
    protected void extractAndValidateResponse(Response response) {

        String orderId =
                response.jsonPath().getString("id");

        String initialStatus = response.jsonPath().getString("status");

        log.info("  ✓ Order created: {}", orderId);
        log.info("  ✓ Initial status: {}", initialStatus);
        assertThat(initialStatus)
                .as("Order should start in PENDING status")
                .isEqualTo("PENDING");

    }
}
