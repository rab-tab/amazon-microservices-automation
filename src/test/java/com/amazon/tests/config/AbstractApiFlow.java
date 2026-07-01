package com.amazon.tests.config;

import com.amazon.tests.dataseeding.core.SeedingContext;
import io.restassured.response.Response;

import java.util.UUID;

public abstract class AbstractApiFlow {

    public String idempotencyKey ;
    protected final TestEnvironment env;
    protected final RetryExecutor retryExecutor;
    protected final SeedingContext context;

    protected Response response;

    protected AbstractApiFlow(
            TestEnvironment env,
            SeedingContext context,
            RetryExecutor retryExecutor) {

        this.env = env;
        this.context=context;
        this.retryExecutor = retryExecutor;
    }

    protected final void executeApiFlow() throws Exception {

        logRequest();
        idempotencyKey = UUID.randomUUID().toString();

        Object request = buildRequest();

        response = retryExecutor.executeWithRetry(
                () -> {
                    try {
                        return executeRequest(request);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        verifyHttpResponse(response);

        extractAndValidateResponse(response);
    }

    protected void logRequest() {
        // default no-op
    }

    protected abstract Object buildRequest();

    protected abstract Response executeRequest(Object request) throws Exception;

    protected abstract void verifyHttpResponse(Response response);

    protected void extractAndValidateResponse(Response response) {
        // default no-op
    }
}
