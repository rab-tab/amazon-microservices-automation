package com.amazon.tests.transport;

public abstract class ApiClient {
    protected final RequestExecutor executor;
    protected ApiClient(RequestExecutor executor) { this.executor = executor; }
}
